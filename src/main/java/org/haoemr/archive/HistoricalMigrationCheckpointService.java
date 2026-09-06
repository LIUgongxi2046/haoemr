package org.haoemr.archive;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.HistoricalMigrationCheckpointRecordRequestWire;
import org.haoemr.contracts.HistoricalMigrationCheckpointWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class HistoricalMigrationCheckpointService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    HistoricalMigrationCheckpointService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    HistoricalMigrationCheckpointWire record(
            ClinicalIdentity identity, String idempotencyKey, HistoricalMigrationCheckpointRecordRequestWire request) {
        if (request.batchId() == null || request.processedRecords() == null || request.checkpointedAt() == null) {
            throw invalid("batch_id, processed_records and checkpointed_at are required");
        }
        if (request.processedRecords() < 0) {
            throw invalid("processed_records must not be negative");
        }
        return transactions.execute(status -> {
            beginCommand(identity, "HISTORICAL_MIGRATION_CHECKPOINT", idempotencyKey,
                    sha256(request.batchId() + "|" + request.processedRecords()));
            String batchStatus = lockBatch(identity.tenantId(), request.batchId());
            if (!"TRIAL".equals(batchStatus) && !"RECONCILED".equals(batchStatus)) {
                throw new HistoricalMigrationCheckpointException(
                        "BATCH_NOT_RESUMABLE", 409, "Only a trial or reconciled batch can be checkpointed");
            }
            Long previous = jdbc.sql("""
                    select processed_records from historical_migration_checkpoint
                    where tenant_id = :tenant and batch_id = :batch
                    order by checkpointed_at desc, checkpoint_id desc limit 1
                    """).param("tenant", identity.tenantId()).param("batch", request.batchId())
                    .query(Long.class).optional().orElse(0L);
            if (request.processedRecords() < previous) {
                throw new HistoricalMigrationCheckpointException(
                        "CHECKPOINT_REGRESSION", 409, "processed_records cannot regress below the previous checkpoint");
            }
            UUID checkpointId = UUID.randomUUID();
            jdbc.sql("""
                    insert into historical_migration_checkpoint(
                      tenant_id, checkpoint_id, batch_id, processed_records, last_source_key,
                      checkpointed_by, checkpointed_at)
                    values (:tenant, :checkpoint, :batch, :processed, :source_key, :actor, :checkpointed_at)
                    """).param("tenant", identity.tenantId()).param("checkpoint", checkpointId)
                    .param("batch", request.batchId()).param("processed", request.processedRecords())
                    .param("source_key", blankToNull(request.lastSourceKey()))
                    .param("actor", identity.userId())
                    .param("checkpointed_at", request.checkpointedAt().atOffset(ZoneOffset.UTC)).update();
            appendEvidence(identity, checkpointId, "HISTORICAL_MIGRATION_CHECKPOINTED", "HistoricalMigrationCheckpointed");
            completeCommand(identity, "HISTORICAL_MIGRATION_CHECKPOINT", idempotencyKey, checkpointId);
            return checkpoint(identity.tenantId(), checkpointId);
        });
    }

    HistoricalMigrationCheckpointWire latest(ClinicalIdentity identity, UUID batchId) {
        return jdbc.sql("""
                select checkpoint_id from historical_migration_checkpoint
                where tenant_id = :tenant and batch_id = :batch
                order by checkpointed_at desc, checkpoint_id desc limit 1
                """).param("tenant", identity.tenantId()).param("batch", batchId)
                .query(UUID.class).optional().map(id -> checkpoint(identity.tenantId(), id))
                .orElseThrow(() -> new HistoricalMigrationCheckpointException(
                        "CHECKPOINT_NOT_FOUND", 404, "No checkpoint exists for this batch"));
    }

    List<HistoricalMigrationCheckpointWire> list(ClinicalIdentity identity, UUID batchId) {
        return jdbc.sql("""
                select checkpoint_id from historical_migration_checkpoint
                where tenant_id = :tenant and batch_id = :batch
                order by checkpointed_at desc, checkpoint_id desc limit 200
                """).param("tenant", identity.tenantId()).param("batch", batchId)
                .query(UUID.class).list().stream()
                .map(id -> checkpoint(identity.tenantId(), id)).toList();
    }

    private HistoricalMigrationCheckpointWire checkpoint(UUID tenantId, UUID checkpointId) {
        return jdbc.sql("""
                select checkpoint_id, batch_id, processed_records, last_source_key,
                  checkpointed_by, checkpointed_at, row_version
                from historical_migration_checkpoint
                where tenant_id = :tenant and checkpoint_id = :checkpoint
                """).param("tenant", tenantId).param("checkpoint", checkpointId)
                .query((rs, row) -> new HistoricalMigrationCheckpointWire(
                        rs.getObject("checkpoint_id", UUID.class),
                        rs.getObject("batch_id", UUID.class),
                        rs.getLong("processed_records"),
                        rs.getString("last_source_key"),
                        rs.getObject("checkpointed_by", UUID.class),
                        rs.getObject("checkpointed_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("row_version")))
                .optional().orElseThrow(HistoricalMigrationCheckpointService::contextDenied);
    }

    private String lockBatch(UUID tenantId, UUID batchId) {
        return jdbc.sql("""
                select batch_status from historical_migration_batch
                where tenant_id = :tenant and batch_id = :batch for update
                """).param("tenant", tenantId).param("batch", batchId)
                .query(String.class).optional().orElseThrow(HistoricalMigrationCheckpointService::contextDenied);
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new HistoricalMigrationCheckpointException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new HistoricalMigrationCheckpointException("IDEMPOTENCY_REPLAY", 409,
                    "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID checkpointId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", checkpointId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, UUID checkpointId, String action, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + checkpointId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'HISTORICAL_MIGRATION_CHECKPOINT', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", checkpointId)
                .param("patient_hash", sha256(identity.tenantId() + "|null"))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'HISTORICAL_MIGRATION_CHECKPOINT', :aggregate, 1, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", checkpointId).param("event_type", eventType).update();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static HistoricalMigrationCheckpointException invalid(String message) {
        return new HistoricalMigrationCheckpointException("HISTORICAL_MIGRATION_CHECKPOINT_REQUEST_INVALID", 400, message);
    }

    static HistoricalMigrationCheckpointException contextDenied() {
        return new HistoricalMigrationCheckpointException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested historical migration checkpoint context is not permitted");
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
