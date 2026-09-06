package org.haoemr.archive;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.SourceFieldMappingDeactivateRequestWire;
import org.haoemr.contracts.SourceFieldMappingRegisterRequestWire;
import org.haoemr.contracts.SourceFieldMappingWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class SourceFieldMappingService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    SourceFieldMappingService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    SourceFieldMappingWire register(
            ClinicalIdentity identity, String idempotencyKey, SourceFieldMappingRegisterRequestWire request) {
        if (request.sourceSystemId() == null || request.registeredAt() == null) {
            throw invalid("source_system_id and registered_at are required");
        }
        String sourceField = requireText(request.sourceField(), 1, "source_field");
        String targetEntity = requireText(request.targetEntity(), 2, "target_entity");
        String targetField = requireText(request.targetField(), 1, "target_field");
        return transactions.execute(status -> {
            beginCommand(identity, "SOURCE_FIELD_MAPPING_REGISTER", idempotencyKey,
                    sha256(request.sourceSystemId() + "|" + sourceField + "|" + targetEntity + "|" + targetField));
            requireConfigurableSource(identity.tenantId(), request.sourceSystemId());
            UUID mappingId = UUID.randomUUID();
            jdbc.sql("""
                    insert into source_field_mapping(
                      tenant_id, mapping_id, source_system_id, source_field, target_entity, target_field,
                      status, registered_by, registered_at)
                    values (:tenant, :mapping, :source, :source_field, :target_entity, :target_field,
                      'ACTIVE', :actor, :registered_at)
                    """).param("tenant", identity.tenantId()).param("mapping", mappingId)
                    .param("source", request.sourceSystemId()).param("source_field", sourceField)
                    .param("target_entity", targetEntity).param("target_field", targetField)
                    .param("actor", identity.userId())
                    .param("registered_at", request.registeredAt().atOffset(ZoneOffset.UTC)).update();
            appendEvidence(identity, mappingId, "SOURCE_FIELD_MAPPING_REGISTERED", "SourceFieldMappingRegistered");
            completeCommand(identity, "SOURCE_FIELD_MAPPING_REGISTER", idempotencyKey, mappingId);
            return mapping(identity.tenantId(), mappingId);
        });
    }

    SourceFieldMappingWire deactivate(
            ClinicalIdentity identity, String idempotencyKey, UUID mappingId,
            SourceFieldMappingDeactivateRequestWire request) {
        return transactions.execute(status -> {
            beginCommand(identity, "SOURCE_FIELD_MAPPING_DEACTIVATE", idempotencyKey,
                    sha256(mappingId + "|" + request.expectedRowVersion()));
            MappingHead head = lockMapping(identity.tenantId(), mappingId);
            if (request.expectedRowVersion() == null || head.rowVersion() != request.expectedRowVersion()) {
                throw new SourceFieldMappingException(
                        "SOURCE_FIELD_MAPPING_VERSION_CONFLICT", 409, "The mapping changed; reload before retrying");
            }
            if (!"ACTIVE".equals(head.status())) {
                throw new SourceFieldMappingException(
                        "SOURCE_FIELD_MAPPING_STATE_INVALID", 409, "Only an active mapping can be deactivated");
            }
            jdbc.sql("""
                    update source_field_mapping
                    set status = 'INACTIVE', row_version = row_version + 1
                    where tenant_id = :tenant and mapping_id = :mapping and row_version = :expected
                    """).param("tenant", identity.tenantId()).param("mapping", mappingId)
                    .param("expected", head.rowVersion()).update();
            appendEvidence(identity, mappingId, "SOURCE_FIELD_MAPPING_DEACTIVATED", "SourceFieldMappingDeactivated");
            completeCommand(identity, "SOURCE_FIELD_MAPPING_DEACTIVATE", idempotencyKey, mappingId);
            return mapping(identity.tenantId(), mappingId);
        });
    }

    List<SourceFieldMappingWire> list(ClinicalIdentity identity, UUID sourceSystemId) {
        return jdbc.sql("""
                select mapping_id from source_field_mapping
                where tenant_id = :tenant and source_system_id = :source
                order by target_entity, target_field, mapping_id limit 500
                """).param("tenant", identity.tenantId()).param("source", sourceSystemId)
                .query(UUID.class).list().stream()
                .map(id -> mapping(identity.tenantId(), id)).toList();
    }

    private SourceFieldMappingWire mapping(UUID tenantId, UUID mappingId) {
        return jdbc.sql("""
                select mapping_id, source_system_id, source_field, target_entity, target_field,
                  status, registered_by, registered_at, row_version
                from source_field_mapping
                where tenant_id = :tenant and mapping_id = :mapping
                """).param("tenant", tenantId).param("mapping", mappingId)
                .query((rs, row) -> new SourceFieldMappingWire(
                        rs.getObject("mapping_id", UUID.class),
                        rs.getObject("source_system_id", UUID.class),
                        rs.getString("source_field"),
                        rs.getString("target_entity"),
                        rs.getString("target_field"),
                        SourceFieldMappingWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getObject("registered_by", UUID.class),
                        rs.getObject("registered_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("row_version")))
                .optional().orElseThrow(SourceFieldMappingService::contextDenied);
    }

    private void requireConfigurableSource(UUID tenantId, UUID sourceSystemId) {
        String status = jdbc.sql("""
                select connection_status from source_system_inventory
                where tenant_id = :tenant and source_system_id = :source for update
                """).param("tenant", tenantId).param("source", sourceSystemId)
                .query(String.class).optional().orElseThrow(SourceFieldMappingService::contextDenied);
        if (!"CONFIGURED".equals(status) && !"ACTIVE".equals(status)) {
            throw new SourceFieldMappingException(
                    "SOURCE_SYSTEM_NOT_CONFIGURED", 409,
                    "Only a configured or active source system can receive field mappings");
        }
    }

    private MappingHead lockMapping(UUID tenantId, UUID mappingId) {
        return jdbc.sql("""
                select status, row_version from source_field_mapping
                where tenant_id = :tenant and mapping_id = :mapping for update
                """).param("tenant", tenantId).param("mapping", mappingId)
                .query((rs, row) -> new MappingHead(rs.getString("status"), rs.getLong("row_version")))
                .optional().orElseThrow(SourceFieldMappingService::contextDenied);
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new SourceFieldMappingException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new SourceFieldMappingException("IDEMPOTENCY_REPLAY", 409,
                    "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID mappingId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", mappingId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, UUID mappingId, String action, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + mappingId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'SOURCE_FIELD_MAPPING', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", mappingId)
                .param("patient_hash", sha256(identity.tenantId() + "|null"))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'SOURCE_FIELD_MAPPING', :aggregate, 1, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", mappingId).param("event_type", eventType).update();
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw invalid(field + " must be at least " + minLength + " characters");
        }
        return value.trim();
    }

    private static SourceFieldMappingException invalid(String message) {
        return new SourceFieldMappingException("SOURCE_FIELD_MAPPING_REQUEST_INVALID", 400, message);
    }

    static SourceFieldMappingException contextDenied() {
        return new SourceFieldMappingException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested source field mapping context is not permitted");
    }

    private record MappingHead(String status, long rowVersion) {}

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
