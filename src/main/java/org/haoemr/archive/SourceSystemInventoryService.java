package org.haoemr.archive;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.SourceSystemInventoryRegisterRequestWire;
import org.haoemr.contracts.SourceSystemInventoryTransitionRequestWire;
import org.haoemr.contracts.SourceSystemInventoryWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class SourceSystemInventoryService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    SourceSystemInventoryService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    SourceSystemInventoryWire register(
            ClinicalIdentity identity, String idempotencyKey, SourceSystemInventoryRegisterRequestWire request) {
        if (request.systemType() == null || request.registeredAt() == null) {
            throw invalid("system_type and registered_at are required");
        }
        String sourceCode = requireText(request.sourceCode(), 2, "source_code");
        String displayName = requireText(request.displayName(), 2, "display_name");
        return transactions.execute(status -> {
            beginCommand(identity, "SOURCE_SYSTEM_REGISTER", idempotencyKey, sha256(sourceCode));
            UUID sourceSystemId = UUID.randomUUID();
            jdbc.sql("""
                    insert into source_system_inventory(
                      tenant_id, source_system_id, source_code, display_name, system_type,
                      connection_status, registered_by, registered_at)
                    values (:tenant, :source, :code, :name, :type, 'REGISTERED', :actor, :registered_at)
                    """).param("tenant", identity.tenantId()).param("source", sourceSystemId)
                    .param("code", sourceCode).param("name", displayName)
                    .param("type", request.systemType().name()).param("actor", identity.userId())
                    .param("registered_at", request.registeredAt().atOffset(ZoneOffset.UTC)).update();
            appendEvidence(identity, sourceSystemId, "SOURCE_SYSTEM_REGISTERED", "SourceSystemRegistered");
            completeCommand(identity, "SOURCE_SYSTEM_REGISTER", idempotencyKey, sourceSystemId);
            return source(identity.tenantId(), sourceSystemId);
        });
    }

    SourceSystemInventoryWire configure(
            ClinicalIdentity identity, String idempotencyKey, UUID sourceSystemId,
            SourceSystemInventoryTransitionRequestWire request) {
        return transition(identity, idempotencyKey, sourceSystemId, request,
                "SOURCE_SYSTEM_CONFIGURE", "REGISTERED", "CONFIGURED", "SourceSystemConfigured");
    }

    SourceSystemInventoryWire activate(
            ClinicalIdentity identity, String idempotencyKey, UUID sourceSystemId,
            SourceSystemInventoryTransitionRequestWire request) {
        return transition(identity, idempotencyKey, sourceSystemId, request,
                "SOURCE_SYSTEM_ACTIVATE", "CONFIGURED", "ACTIVE", "SourceSystemActivated");
    }

    SourceSystemInventoryWire retire(
            ClinicalIdentity identity, String idempotencyKey, UUID sourceSystemId,
            SourceSystemInventoryTransitionRequestWire request) {
        return transactions.execute(status -> {
            beginCommand(identity, "SOURCE_SYSTEM_RETIRE", idempotencyKey,
                    sha256(sourceSystemId + "|" + request.expectedRowVersion()));
            SourceHead head = lockSource(identity.tenantId(), sourceSystemId);
            requireVersion(head, request.expectedRowVersion());
            if (!"CONFIGURED".equals(head.status()) && !"ACTIVE".equals(head.status())) {
                throw new SourceSystemInventoryException(
                        "SOURCE_SYSTEM_STATE_INVALID", 409,
                        "Only a configured or active source system can be retired");
            }
            jdbc.sql("""
                    update source_system_inventory
                    set connection_status = 'RETIRED', row_version = row_version + 1
                    where tenant_id = :tenant and source_system_id = :source and row_version = :expected
                    """).param("tenant", identity.tenantId()).param("source", sourceSystemId)
                    .param("expected", head.rowVersion()).update();
            appendEvidence(identity, sourceSystemId, "SOURCE_SYSTEM_RETIRED", "SourceSystemRetired");
            completeCommand(identity, "SOURCE_SYSTEM_RETIRE", idempotencyKey, sourceSystemId);
            return source(identity.tenantId(), sourceSystemId);
        });
    }

    List<SourceSystemInventoryWire> list(ClinicalIdentity identity, String status) {
        List<UUID> ids = status == null || status.isBlank()
                ? jdbc.sql("""
                        select source_system_id from source_system_inventory
                        where tenant_id = :tenant order by source_code, source_system_id limit 500
                        """).param("tenant", identity.tenantId()).query(UUID.class).list()
                : jdbc.sql("""
                        select source_system_id from source_system_inventory
                        where tenant_id = :tenant and connection_status = :status
                        order by source_code, source_system_id limit 500
                        """).param("tenant", identity.tenantId()).param("status", status).query(UUID.class).list();
        return ids.stream().map(id -> source(identity.tenantId(), id)).toList();
    }

    private SourceSystemInventoryWire transition(
            ClinicalIdentity identity, String idempotencyKey, UUID sourceSystemId,
            SourceSystemInventoryTransitionRequestWire request, String scope,
            String fromStatus, String toStatus, String eventType) {
        return transactions.execute(status -> {
            beginCommand(identity, scope, idempotencyKey,
                    sha256(sourceSystemId + "|" + request.expectedRowVersion()));
            SourceHead head = lockSource(identity.tenantId(), sourceSystemId);
            requireVersion(head, request.expectedRowVersion());
            if (!fromStatus.equals(head.status())) {
                throw new SourceSystemInventoryException(
                        "SOURCE_SYSTEM_STATE_INVALID", 409,
                        "Only a " + fromStatus.toLowerCase() + " source system can transition to "
                                + toStatus.toLowerCase());
            }
            jdbc.sql("""
                    update source_system_inventory
                    set connection_status = :to_status, row_version = row_version + 1
                    where tenant_id = :tenant and source_system_id = :source and row_version = :expected
                    """).param("tenant", identity.tenantId()).param("source", sourceSystemId)
                    .param("to_status", toStatus).param("expected", head.rowVersion()).update();
            appendEvidence(identity, sourceSystemId, scope, eventType);
            completeCommand(identity, scope, idempotencyKey, sourceSystemId);
            return source(identity.tenantId(), sourceSystemId);
        });
    }

    private SourceSystemInventoryWire source(UUID tenantId, UUID sourceSystemId) {
        return jdbc.sql("""
                select source_system_id, source_code, display_name, system_type, connection_status,
                  registered_by, registered_at, row_version
                from source_system_inventory
                where tenant_id = :tenant and source_system_id = :source
                """).param("tenant", tenantId).param("source", sourceSystemId)
                .query((rs, row) -> new SourceSystemInventoryWire(
                        rs.getObject("source_system_id", UUID.class),
                        rs.getString("source_code"),
                        rs.getString("display_name"),
                        SourceSystemInventoryWire.SystemTypeValue.valueOf(rs.getString("system_type")),
                        SourceSystemInventoryWire.ConnectionStatusValue.valueOf(rs.getString("connection_status")),
                        rs.getObject("registered_by", UUID.class),
                        rs.getObject("registered_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("row_version")))
                .optional().orElseThrow(SourceSystemInventoryService::contextDenied);
    }

    private SourceHead lockSource(UUID tenantId, UUID sourceSystemId) {
        return jdbc.sql("""
                select connection_status, row_version from source_system_inventory
                where tenant_id = :tenant and source_system_id = :source for update
                """).param("tenant", tenantId).param("source", sourceSystemId)
                .query((rs, row) -> new SourceHead(rs.getString("connection_status"), rs.getLong("row_version")))
                .optional().orElseThrow(SourceSystemInventoryService::contextDenied);
    }

    private static void requireVersion(SourceHead head, Long expected) {
        if (expected == null || head.rowVersion() != expected) {
            throw new SourceSystemInventoryException(
                    "SOURCE_SYSTEM_VERSION_CONFLICT", 409, "The source system changed; reload before retrying");
        }
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new SourceSystemInventoryException("INVALID_IDEMPOTENCY_KEY", 400,
                    "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new SourceSystemInventoryException("IDEMPOTENCY_REPLAY", 409,
                    "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID sourceSystemId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", sourceSystemId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, UUID sourceSystemId, String action, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + sourceSystemId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'SOURCE_SYSTEM_INVENTORY', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", sourceSystemId)
                .param("patient_hash", sha256(identity.tenantId() + "|null"))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'SOURCE_SYSTEM_INVENTORY', :aggregate, 1, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", sourceSystemId).param("event_type", eventType).update();
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw invalid(field + " must be at least " + minLength + " characters");
        }
        return value.trim();
    }

    private static SourceSystemInventoryException invalid(String message) {
        return new SourceSystemInventoryException("SOURCE_SYSTEM_REQUEST_INVALID", 400, message);
    }

    static SourceSystemInventoryException contextDenied() {
        return new SourceSystemInventoryException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested source system context is not permitted");
    }

    private record SourceHead(String status, long rowVersion) {}

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
