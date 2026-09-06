package org.haoemr.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ToolRegistryDeactivateRequestWire;
import org.haoemr.contracts.ToolRegistryRegisterRequestWire;
import org.haoemr.contracts.ToolRegistryVersionRequestWire;
import org.haoemr.contracts.ToolRegistryWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class ToolRegistryService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    ToolRegistryService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    ToolRegistryWire register(
            ClinicalIdentity identity, String idempotencyKey, ToolRegistryRegisterRequestWire request) {
        String toolCode = requireText(request.toolCode(), 2, "tool_code");
        String toolName = requireText(request.toolName(), 2, "tool_name");
        String toolVersion = requireText(request.toolVersion(), 1, "tool_version");
        if (request.toolType() == null) {
            throw invalid("tool_type is required");
        }
        return transactions.execute(status -> {
            beginCommand(identity, "TOOL_REGISTRY_REGISTER", idempotencyKey,
                    sha256(toolCode + "|" + toolVersion + "|" + request.toolType()));
            UUID registryId = UUID.randomUUID();
            jdbc.sql("""
                    insert into tool_registry(
                      tenant_id, tool_registry_id, tool_code, tool_name, tool_version, tool_type, status)
                    values (:tenant, :registry, :code, :name, :version, :type, 'ACTIVE')
                    """).param("tenant", identity.tenantId()).param("registry", registryId)
                    .param("code", toolCode).param("name", toolName).param("version", toolVersion)
                    .param("type", request.toolType().name()).update();
            appendEvidence(identity, registryId, "TOOL_REGISTRY_REGISTERED", "ToolRegistryRegistered");
            completeCommand(identity, "TOOL_REGISTRY_REGISTER", idempotencyKey, registryId);
            return registry(identity.tenantId(), registryId);
        });
    }

    ToolRegistryWire deactivate(
            ClinicalIdentity identity, String idempotencyKey, UUID registryId,
            ToolRegistryDeactivateRequestWire request) {
        return transactions.execute(status -> {
            beginCommand(identity, "TOOL_REGISTRY_DEACTIVATE", idempotencyKey, sha256(registryId.toString()));
            String currentStatus = jdbc.sql("""
                    select status from tool_registry
                    where tenant_id = :tenant and tool_registry_id = :registry for update
                    """).param("tenant", identity.tenantId()).param("registry", registryId)
                    .query(String.class).optional().orElseThrow(ToolRegistryService::contextDenied);
            if (!"ACTIVE".equals(currentStatus)) {
                throw new ToolRegistryException(
                        "TOOL_REGISTRY_STATE_INVALID", 409, "Only an active tool can be deactivated");
            }
            jdbc.sql("""
                    update tool_registry set status = 'INACTIVE', updated_at = now()
                    where tenant_id = :tenant and tool_registry_id = :registry
                    """).param("tenant", identity.tenantId()).param("registry", registryId).update();
            appendEvidence(identity, registryId, "TOOL_REGISTRY_DEACTIVATED", "ToolRegistryDeactivated");
            completeCommand(identity, "TOOL_REGISTRY_DEACTIVATE", idempotencyKey, registryId);
            return registry(identity.tenantId(), registryId);
        });
    }

    ToolRegistryWire publishVersion(
            ClinicalIdentity identity, String idempotencyKey, UUID currentRegistryId,
            ToolRegistryVersionRequestWire request) {
        String toolName = requireText(request.toolName(), 2, "tool_name");
        String toolVersion = requireText(request.toolVersion(), 1, "tool_version");
        if (request.toolType() == null) throw invalid("tool_type is required");
        return transactions.execute(status -> {
            beginCommand(identity, "TOOL_REGISTRY_PUBLISH_VERSION", idempotencyKey,
                    sha256(currentRegistryId + "|" + toolName + "|" + toolVersion + "|" + request.toolType()));
            RegistryHead current = jdbc.sql("""
                    select tool_code, tool_version, status from tool_registry
                    where tenant_id = :tenant and tool_registry_id = :registry for update
                    """).param("tenant", identity.tenantId()).param("registry", currentRegistryId)
                    .query((rs, row) -> new RegistryHead(
                            rs.getString("tool_code"), rs.getString("tool_version"), rs.getString("status")))
                    .optional().orElseThrow(ToolRegistryService::contextDenied);
            if (!"ACTIVE".equals(current.status())) {
                throw new ToolRegistryException(
                        "TOOL_REGISTRY_STATE_INVALID", 409, "Only an active tool can publish a new version");
            }
            if (current.version().equals(toolVersion)) {
                throw invalid("tool_version must change when publishing a new version");
            }
            jdbc.sql("""
                    update tool_registry set status = 'INACTIVE', updated_at = now()
                    where tenant_id = :tenant and tool_registry_id = :registry
                    """).param("tenant", identity.tenantId()).param("registry", currentRegistryId).update();
            UUID nextRegistryId = UUID.randomUUID();
            jdbc.sql("""
                    insert into tool_registry(
                      tenant_id, tool_registry_id, tool_code, tool_name, tool_version, tool_type, status)
                    values (:tenant, :registry, :code, :name, :version, :type, 'ACTIVE')
                    """).param("tenant", identity.tenantId()).param("registry", nextRegistryId)
                    .param("code", current.code()).param("name", toolName).param("version", toolVersion)
                    .param("type", request.toolType().name()).update();
            appendEvidence(identity, currentRegistryId,
                    "TOOL_REGISTRY_SUPERSEDED", "ToolRegistrySuperseded");
            appendEvidence(identity, nextRegistryId,
                    "TOOL_REGISTRY_VERSION_PUBLISHED", "ToolRegistryVersionPublished");
            completeCommand(identity, "TOOL_REGISTRY_PUBLISH_VERSION", idempotencyKey, nextRegistryId);
            return registry(identity.tenantId(), nextRegistryId);
        });
    }

    List<ToolRegistryWire> listTools(ClinicalIdentity identity, String status) {
        List<UUID> ids = status == null || status.isBlank()
                ? jdbc.sql("""
                        select tool_registry_id from tool_registry
                        where tenant_id = :tenant order by tool_code, tool_registry_id limit 500
                        """).param("tenant", identity.tenantId()).query(UUID.class).list()
                : jdbc.sql("""
                        select tool_registry_id from tool_registry
                        where tenant_id = :tenant and status = :status
                        order by tool_code, tool_registry_id limit 500
                        """).param("tenant", identity.tenantId()).param("status", status).query(UUID.class).list();
        return ids.stream().map(id -> registry(identity.tenantId(), id)).toList();
    }

    private ToolRegistryWire registry(UUID tenantId, UUID registryId) {
        return jdbc.sql("""
                select tool_registry_id, tool_code, tool_name, tool_version, tool_type, status
                from tool_registry where tenant_id = :tenant and tool_registry_id = :registry
                """).param("tenant", tenantId).param("registry", registryId)
                .query((rs, row) -> new ToolRegistryWire(
                        rs.getObject("tool_registry_id", UUID.class), rs.getString("tool_code"),
                        rs.getString("tool_name"), rs.getString("tool_version"),
                        ToolRegistryWire.ToolTypeValue.valueOf(rs.getString("tool_type")),
                        ToolRegistryWire.StatusValue.valueOf(rs.getString("status"))))
                .optional().orElseThrow(ToolRegistryService::contextDenied);
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new ToolRegistryException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new ToolRegistryException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID registryId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", registryId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, UUID registryId, String action, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + registryId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'TOOL_REGISTRY', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", registryId)
                .param("patient_hash", sha256(identity.tenantId() + "|null"))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'TOOL_REGISTRY', :aggregate, 1, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", registryId).param("event_type", eventType).update();
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw invalid(field + " must be at least " + minLength + " characters");
        }
        return value.trim();
    }

    private static ToolRegistryException invalid(String message) {
        return new ToolRegistryException("TOOL_REGISTRY_REQUEST_INVALID", 400, message);
    }

    static ToolRegistryException contextDenied() {
        return new ToolRegistryException("CONTEXT_NOT_PERMITTED", 403,
                "The requested tool registry context is not permitted");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record RegistryHead(String code, String version, String status) {}
}
