package org.haoemr.device;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.DeviceCatalogCreateRequestWire;
import org.haoemr.contracts.DeviceCatalogDeactivateRequestWire;
import org.haoemr.contracts.DeviceCatalogWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class DeviceCatalogService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    DeviceCatalogService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    List<DeviceCatalogWire> listDevices(ClinicalIdentity identity, String status) {
        StringBuilder sql = new StringBuilder("""
                select device_id from device where tenant_id = :tenant
                """);
        if (status != null && !status.isBlank()) sql.append(" and status = :status");
        sql.append(" order by device_code, device_id limit 500");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("tenant", identity.tenantId());
        if (status != null && !status.isBlank()) spec = spec.param("status", status.trim());
        List<UUID> ids = spec.query(UUID.class).list();
        return ids.stream().map(id -> device(identity.tenantId(), id)).toList();
    }

    DeviceCatalogWire create(ClinicalIdentity identity, String idempotencyKey, DeviceCatalogCreateRequestWire request) {
        String code = requireText(request.deviceCode(), 2, "device_code");
        String name = requireText(request.displayName(), 2, "display_name");
        return transactions.execute(status -> {
            beginCommand(identity, "DEVICE_CATALOG_CREATE", idempotencyKey, sha256(code));
            UUID deviceId = UUID.randomUUID();
            jdbc.sql("""
                    insert into device(
                      tenant_id, device_id, device_code, display_name, device_type,
                      manufacturer_model, department, gateway, standard_interface,
                      calibration_due, clock_offset_seconds, binding_policy, status)
                    values (:tenant, :device, :code, :name, :type,
                      :model, :department, :gateway, :interface,
                      :calibration, :clock, :policy, 'ACTIVE')
                    """).param("tenant", identity.tenantId()).param("device", deviceId)
                    .param("code", code).param("name", name)
                    .param("type", request.deviceType() == null ? "MONITOR" : request.deviceType().name())
                    .param("model", blankToNull(request.manufacturerModel()))
                    .param("department", blankToNull(request.department()))
                    .param("gateway", blankToNull(request.gateway()))
                    .param("interface", blankToNull(request.standardInterface()))
                    .param("calibration", request.calibrationDue())
                    .param("clock", request.clockOffsetSeconds() == null ? 0 : request.clockOffsetSeconds())
                    .param("policy", blankToNull(request.bindingPolicy())).update();
            appendEvidence(identity, deviceId, "DEVICE_CATALOG_CREATED", "DeviceCatalogCreated");
            completeCommand(identity, "DEVICE_CATALOG_CREATE", idempotencyKey, deviceId);
            return device(identity.tenantId(), deviceId);
        });
    }

    DeviceCatalogWire deactivate(ClinicalIdentity identity, String idempotencyKey, UUID deviceId,
            DeviceCatalogDeactivateRequestWire request) {
        return transactions.execute(status -> {
            beginCommand(identity, "DEVICE_CATALOG_DEACTIVATE", idempotencyKey, sha256(deviceId.toString()));
            String current = jdbc.sql("""
                    select status from device where tenant_id = :tenant and device_id = :device for update
                    """).param("tenant", identity.tenantId()).param("device", deviceId)
                    .query(String.class).optional().orElseThrow(DeviceCatalogService::contextDenied);
            if (!"ACTIVE".equals(current)) {
                throw new DeviceException("DEVICE_STATE_INVALID", 409, "仅活动设备可停用");
            }
            jdbc.sql("""
                    update device set status = 'INACTIVE', row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and device_id = :device
                    """).param("tenant", identity.tenantId()).param("device", deviceId).update();
            appendEvidence(identity, deviceId, "DEVICE_CATALOG_DEACTIVATED", "DeviceCatalogDeactivated");
            completeCommand(identity, "DEVICE_CATALOG_DEACTIVATE", idempotencyKey, deviceId);
            return device(identity.tenantId(), deviceId);
        });
    }

    private DeviceCatalogWire device(UUID tenantId, UUID deviceId) {
        return jdbc.sql("""
                select device_id, device_code, display_name, device_type, manufacturer_model,
                  department, gateway, standard_interface, calibration_due, clock_offset_seconds,
                  binding_policy, status, row_version, created_at, updated_at
                from device where tenant_id = :tenant and device_id = :device
                """).param("tenant", tenantId).param("device", deviceId)
                .query((rs, row) -> new DeviceCatalogWire(
                        rs.getObject("device_id", UUID.class), rs.getString("device_code"),
                        rs.getString("display_name"),
                        DeviceCatalogWire.DeviceTypeValue.valueOf(rs.getString("device_type")),
                        rs.getString("manufacturer_model"), rs.getString("department"),
                        rs.getString("gateway"), rs.getString("standard_interface"),
                        rs.getObject("calibration_due", LocalDate.class),
                        rs.getInt("clock_offset_seconds"), rs.getString("binding_policy"),
                        DeviceCatalogWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getLong("row_version"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .optional().orElseThrow(DeviceCatalogService::contextDenied);
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new DeviceException("DEVICE_REQUEST_INVALID", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new DeviceException("IDEMPOTENCY_REPLAY", 409, "该命令键已使用");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, Object resource) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', cast(:resource as text))
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", String.valueOf(resource)).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, UUID deviceId, String action, String eventType) {
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + deviceId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'DEVICE', :resource,
                  :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", deviceId)
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'DEVICE', :aggregate, 1, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", deviceId).param("event_type", eventType).update();
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw new DeviceException("DEVICE_REQUEST_INVALID", 400, field + " is required");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static DeviceException contextDenied() {
        return new DeviceException("CONTEXT_NOT_PERMITTED", 403, "请求的设备目录上下文不允许访问");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
