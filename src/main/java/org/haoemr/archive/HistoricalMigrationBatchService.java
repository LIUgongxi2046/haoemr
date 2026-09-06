package org.haoemr.archive;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.HistoricalMigrationBatchReconcileRequestWire;
import org.haoemr.contracts.HistoricalMigrationBatchRollbackRequestWire;
import org.haoemr.contracts.HistoricalMigrationBatchArchiveRequestWire;
import org.haoemr.contracts.HistoricalMigrationBatchObservationStartRequestWire;
import org.haoemr.contracts.HistoricalMigrationBatchStartRequestWire;
import org.haoemr.contracts.HistoricalMigrationBatchSwitchRequestWire;
import org.haoemr.contracts.HistoricalMigrationBatchWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class HistoricalMigrationBatchService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    HistoricalMigrationBatchService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    HistoricalMigrationBatchWire start(
            ClinicalIdentity identity, String idempotencyKey, HistoricalMigrationBatchStartRequestWire request) {
        if (request.sourceSystem() == null || request.startedAt() == null) {
            throw invalid("source_system, record_count and started_at are required");
        }
        String sourceSystem = requireText(request.sourceSystem(), 2, "source_system");
        return transactions.execute(status -> {
            SourceSnapshot source = activeSource(identity.tenantId(), sourceSystem);
            UUID sourceImportId = request.sourceImportId();
            if (sourceImportId != null) requireSourceImport(identity.tenantId(), source.sourceSystemId(), sourceImportId);
            int actualRecordCount = sourceRecordCount(identity.tenantId(), source.sourceSystemId(), sourceImportId);
            if (actualRecordCount == 0) {
                throw new HistoricalMigrationBatchException(
                        "HISTORICAL_MIGRATION_SOURCE_EMPTY", 422,
                        "The active source has no staged patient match records");
            }
            if (request.recordCount() != null && request.recordCount() != actualRecordCount) {
                throw new HistoricalMigrationBatchException(
                        "HISTORICAL_MIGRATION_RECORD_COUNT_MISMATCH", 409,
                        "The submitted record count does not match the server-side staged record count");
            }
            beginCommand(identity, "HISTORICAL_MIGRATION_START", idempotencyKey,
                    sha256(sourceSystem + "|" + sourceImportId + "|" + actualRecordCount));
            UUID batchId = UUID.randomUUID();
            jdbc.sql("""
                    insert into historical_migration_batch(
                      tenant_id, batch_id, source_system, source_system_id, source_import_id, batch_status, record_count,
                      mismatch_count, started_at, created_by)
                    values (:tenant, :batch, :source, :source_id, :source_import_id, 'TRIAL', :record_count,
                      0, :started_at, :created_by)
                    """).param("tenant", identity.tenantId()).param("batch", batchId)
                    .param("source", sourceSystem).param("source_id", source.sourceSystemId())
                    .param("source_import_id", sourceImportId)
                    .param("record_count", actualRecordCount)
                    .param("started_at", request.startedAt().atOffset(ZoneOffset.UTC))
                    .param("created_by", identity.userId()).update();
            appendEvidence(identity, batchId, "HISTORICAL_MIGRATION_STARTED", "HistoricalMigrationStarted");
            completeCommand(identity, "HISTORICAL_MIGRATION_START", idempotencyKey, batchId);
            return batch(identity.tenantId(), batchId);
        });
    }

    HistoricalMigrationBatchWire reconcile(
            ClinicalIdentity identity, String idempotencyKey, UUID batchId,
            HistoricalMigrationBatchReconcileRequestWire request) {
        return transactions.execute(status -> {
            beginCommand(identity, "HISTORICAL_MIGRATION_RECONCILE", idempotencyKey,
                    sha256(batchId + "|" + request.expectedRowVersion()));
            BatchHead head = lockBatch(identity.tenantId(), batchId);
            if (request.expectedRowVersion() == null || head.rowVersion() != request.expectedRowVersion()) {
                throw versionConflict();
            }
            if (!"TRIAL".equals(head.status())) {
                throw new HistoricalMigrationBatchException(
                        "HISTORICAL_MIGRATION_STATE_INVALID", 409,
                        "Only a trial batch can be reconciled");
            }
            if (head.sourceSystemId() == null) {
                throw new HistoricalMigrationBatchException(
                        "HISTORICAL_MIGRATION_SOURCE_UNRESOLVED", 422,
                        "The legacy batch is not linked to a governed source system");
            }
            boolean patientIdentifierMapped = hasPatientIdentifierMapping(
                    identity.tenantId(), head.sourceSystemId());
            List<CandidateSnapshot> candidates = candidateSnapshots(
                    identity.tenantId(), head.sourceSystemId(), head.sourceImportId());
            for (CandidateSnapshot candidate : candidates) {
                boolean matched = patientIdentifierMapped
                        && "RESOLVED".equals(candidate.reviewStatus())
                        && candidate.matchedPatientId() != null;
                String validationCode = !patientIdentifierMapped
                        ? "PATIENT_IDENTIFIER_MAPPING_MISSING"
                        : !"RESOLVED".equals(candidate.reviewStatus())
                                ? "PATIENT_MATCH_NOT_REVIEWED"
                                : candidate.matchedPatientId() == null
                                        ? "TARGET_PATIENT_NOT_SELECTED" : "MATCHED";
                jdbc.sql("""
                        insert into historical_migration_reconciliation_item(
                          tenant_id, reconciliation_item_id, batch_id, candidate_id,
                          source_record_hash, target_patient_id, validation_status,
                          validation_code, reconciled_by)
                        values (:tenant, :item, :batch, :candidate, :source_hash,
                          :target_patient, :validation_status, :validation_code, :actor)
                        """).param("tenant", identity.tenantId()).param("item", UUID.randomUUID())
                        .param("batch", batchId).param("candidate", candidate.candidateId())
                        .param("source_hash", sha256(candidate.sourcePatientIdentifier()))
                        .param("target_patient", candidate.matchedPatientId())
                        .param("validation_status", matched ? "MATCHED" : "MISMATCH")
                        .param("validation_code", validationCode).param("actor", identity.userId()).update();
            }
            int evidenceCount = reconciliationEvidenceCount(identity.tenantId(), batchId);
            if (evidenceCount != head.recordCount()) {
                throw new HistoricalMigrationBatchException(
                        "HISTORICAL_MIGRATION_SOURCE_DRIFT", 409,
                        "The staged source changed after trial start; start a new migration batch");
            }
            int derivedMismatchCount = reconciliationMismatchCount(identity.tenantId(), batchId);
            jdbc.sql("""
                    update historical_migration_batch
                    set batch_status = 'RECONCILED', mismatch_count = :mismatch, completed_at = now(),
                      row_version = row_version + 1
                    where tenant_id = :tenant and batch_id = :batch and row_version = :expected
                    """).param("tenant", identity.tenantId()).param("batch", batchId)
                    .param("mismatch", derivedMismatchCount).param("expected", head.rowVersion()).update();
            appendEvidence(identity, batchId, "HISTORICAL_MIGRATION_RECONCILED", "HistoricalMigrationReconciled");
            completeCommand(identity, "HISTORICAL_MIGRATION_RECONCILE", idempotencyKey, batchId);
            return batch(identity.tenantId(), batchId);
        });
    }

    HistoricalMigrationBatchWire switchBatch(
            ClinicalIdentity identity, String idempotencyKey, UUID batchId,
            HistoricalMigrationBatchSwitchRequestWire request) {
        return transactions.execute(status -> {
            beginCommand(identity, "HISTORICAL_MIGRATION_SWITCH", idempotencyKey,
                    sha256(batchId + "|" + request.expectedRowVersion()));
            BatchHead head = lockBatch(identity.tenantId(), batchId);
            if (request.expectedRowVersion() == null || head.rowVersion() != request.expectedRowVersion()) {
                throw versionConflict();
            }
            if (!"RECONCILED".equals(head.status())) {
                throw new HistoricalMigrationBatchException(
                        "HISTORICAL_MIGRATION_STATE_INVALID", 409,
                        "Only a reconciled batch can be switched");
            }
            if (head.mismatchCount() != 0) {
                throw new HistoricalMigrationBatchException(
                        "HISTORICAL_MIGRATION_MISMATCH", 409,
                        "A batch with reconciliation mismatches cannot be switched");
            }
            if (reconciliationEvidenceCount(identity.tenantId(), batchId) != head.recordCount()) {
                throw new HistoricalMigrationBatchException(
                        "HISTORICAL_MIGRATION_EVIDENCE_INCOMPLETE", 409,
                        "The reconciliation evidence is incomplete; production switch is blocked");
            }
            jdbc.sql("""
                    update historical_migration_batch
                    set batch_status = 'SWITCHED', row_version = row_version + 1
                    where tenant_id = :tenant and batch_id = :batch and row_version = :expected
                    """).param("tenant", identity.tenantId()).param("batch", batchId)
                    .param("expected", head.rowVersion()).update();
            appendEvidence(identity, batchId, "HISTORICAL_MIGRATION_SWITCHED", "HistoricalMigrationSwitched");
            completeCommand(identity, "HISTORICAL_MIGRATION_SWITCH", idempotencyKey, batchId);
            return batch(identity.tenantId(), batchId);
        });
    }

    HistoricalMigrationBatchWire rollback(
            ClinicalIdentity identity, String idempotencyKey, UUID batchId,
            HistoricalMigrationBatchRollbackRequestWire request) {
        return transactions.execute(status -> {
            beginCommand(identity, "HISTORICAL_MIGRATION_ROLLBACK", idempotencyKey,
                    sha256(batchId + "|" + request.expectedRowVersion()));
            BatchHead head = lockBatch(identity.tenantId(), batchId);
            if (request.expectedRowVersion() == null || head.rowVersion() != request.expectedRowVersion()) {
                throw versionConflict();
            }
            if (!"RECONCILED".equals(head.status())) {
                throw new HistoricalMigrationBatchException(
                        "HISTORICAL_MIGRATION_STATE_INVALID", 409,
                        "Only a reconciled batch can be rolled back");
            }
            jdbc.sql("""
                    update historical_migration_batch
                    set batch_status = 'ROLLED_BACK', row_version = row_version + 1
                    where tenant_id = :tenant and batch_id = :batch and row_version = :expected
                    """).param("tenant", identity.tenantId()).param("batch", batchId)
                    .param("expected", head.rowVersion()).update();
            appendEvidence(identity, batchId, "HISTORICAL_MIGRATION_ROLLED_BACK", "HistoricalMigrationRolledBack");
            completeCommand(identity, "HISTORICAL_MIGRATION_ROLLBACK", idempotencyKey, batchId);
            return batch(identity.tenantId(), batchId);
        });
    }

    HistoricalMigrationBatchWire startObservation(
            ClinicalIdentity identity, String idempotencyKey, UUID batchId,
            HistoricalMigrationBatchObservationStartRequestWire request) {
        return transactions.execute(status -> {
            beginCommand(identity, "HISTORICAL_MIGRATION_OBSERVATION_START", idempotencyKey,
                    sha256(batchId + "|" + request.expectedRowVersion()));
            BatchHead head = lockBatch(identity.tenantId(), batchId);
            if (request.expectedRowVersion() == null || head.rowVersion() != request.expectedRowVersion()) {
                throw versionConflict();
            }
            if (!"SWITCHED".equals(head.status())) {
                throw new HistoricalMigrationBatchException(
                        "HISTORICAL_MIGRATION_STATE_INVALID", 409,
                        "Only a switched batch can enter its observation period");
            }
            jdbc.sql("""
                    update historical_migration_batch
                    set batch_status = 'OBSERVING', observation_started_at = now(),
                      row_version = row_version + 1
                    where tenant_id = :tenant and batch_id = :batch and row_version = :expected
                    """).param("tenant", identity.tenantId()).param("batch", batchId)
                    .param("expected", head.rowVersion()).update();
            appendEvidence(identity, batchId, "HISTORICAL_MIGRATION_OBSERVATION_STARTED",
                    "HistoricalMigrationObservationStarted");
            completeCommand(identity, "HISTORICAL_MIGRATION_OBSERVATION_START", idempotencyKey, batchId);
            return batch(identity.tenantId(), batchId);
        });
    }

    HistoricalMigrationBatchWire archive(
            ClinicalIdentity identity, String idempotencyKey, UUID batchId,
            HistoricalMigrationBatchArchiveRequestWire request) {
        String summary = requireText(request.observationSummary(), 10, "observation_summary");
        if (summary.length() > 1000) throw invalid("observation_summary must be at most 1000 characters");
        return transactions.execute(status -> {
            beginCommand(identity, "HISTORICAL_MIGRATION_ARCHIVE", idempotencyKey,
                    sha256(batchId + "|" + request.expectedRowVersion() + "|" + summary));
            BatchHead head = lockBatch(identity.tenantId(), batchId);
            if (request.expectedRowVersion() == null || head.rowVersion() != request.expectedRowVersion()) {
                throw versionConflict();
            }
            if (!"OBSERVING".equals(head.status())) {
                throw new HistoricalMigrationBatchException(
                        "HISTORICAL_MIGRATION_STATE_INVALID", 409,
                        "Only an observing batch can be archived");
            }
            jdbc.sql("""
                    update historical_migration_batch
                    set batch_status = 'ARCHIVED', observation_completed_at = now(),
                      observation_summary = :summary, archived_at = now(), row_version = row_version + 1
                    where tenant_id = :tenant and batch_id = :batch and row_version = :expected
                    """).param("tenant", identity.tenantId()).param("batch", batchId)
                    .param("summary", summary).param("expected", head.rowVersion()).update();
            appendEvidence(identity, batchId, "HISTORICAL_MIGRATION_ARCHIVED", "HistoricalMigrationArchived");
            completeCommand(identity, "HISTORICAL_MIGRATION_ARCHIVE", idempotencyKey, batchId);
            return batch(identity.tenantId(), batchId);
        });
    }

    List<HistoricalMigrationBatchWire> listBatches(ClinicalIdentity identity, String sourceSystem) {
        List<UUID> ids = sourceSystem == null || sourceSystem.isBlank()
                ? jdbc.sql("""
                        select batch_id from historical_migration_batch
                        where tenant_id = :tenant order by started_at desc, batch_id desc limit 200
                        """).param("tenant", identity.tenantId()).query(UUID.class).list()
                : jdbc.sql("""
                        select batch_id from historical_migration_batch
                        where tenant_id = :tenant and source_system = :source
                        order by started_at desc, batch_id desc limit 200
                        """).param("tenant", identity.tenantId()).param("source", sourceSystem).query(UUID.class).list();
        return ids.stream().map(id -> batch(identity.tenantId(), id)).toList();
    }

    private HistoricalMigrationBatchWire batch(UUID tenantId, UUID batchId) {
        return jdbc.sql("""
                select batch_id, source_system, source_import_id, batch_status, record_count, mismatch_count,
                  started_at, completed_at, observation_started_at, observation_completed_at, observation_summary,
                  archived_at, created_by, row_version
                from historical_migration_batch
                where tenant_id = :tenant and batch_id = :batch
                """).param("tenant", tenantId).param("batch", batchId)
                .query((rs, row) -> new HistoricalMigrationBatchWire(
                        rs.getObject("batch_id", UUID.class),
                        rs.getString("source_system"),
                        rs.getObject("source_import_id", UUID.class),
                        HistoricalMigrationBatchWire.BatchStatusValue.valueOf(rs.getString("batch_status")),
                        rs.getInt("record_count"),
                        rs.getInt("mismatch_count"),
                        rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("completed_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("completed_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("observation_started_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("observation_started_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("observation_completed_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("observation_completed_at", OffsetDateTime.class).toInstant(),
                        rs.getString("observation_summary"),
                        rs.getObject("archived_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("archived_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("created_by", UUID.class),
                        rs.getLong("row_version")))
                .optional().orElseThrow(HistoricalMigrationBatchService::contextDenied);
    }

    private BatchHead lockBatch(UUID tenantId, UUID batchId) {
        return jdbc.sql("""
                select batch_status, mismatch_count, row_version, record_count, source_system_id, source_import_id
                from historical_migration_batch
                where tenant_id = :tenant and batch_id = :batch for update
                """).param("tenant", tenantId).param("batch", batchId)
                .query((rs, row) -> new BatchHead(
                        rs.getString("batch_status"), rs.getInt("mismatch_count"), rs.getLong("row_version"),
                        rs.getInt("record_count"), rs.getObject("source_system_id", UUID.class),
                        rs.getObject("source_import_id", UUID.class)))
                .optional().orElseThrow(HistoricalMigrationBatchService::contextDenied);
    }

    private SourceSnapshot activeSource(UUID tenantId, String sourceCode) {
        return jdbc.sql("""
                select source_system_id from source_system_inventory
                where tenant_id = :tenant and source_code = :source and connection_status = 'ACTIVE'
                for update
                """).param("tenant", tenantId).param("source", sourceCode)
                .query((rs, row) -> new SourceSnapshot(rs.getObject("source_system_id", UUID.class)))
                .optional().orElseThrow(() -> new HistoricalMigrationBatchException(
                        "HISTORICAL_MIGRATION_SOURCE_NOT_ACTIVE", 409,
                        "Only an active governed source system can start a migration batch"));
    }

    private void requireSourceImport(UUID tenantId, UUID sourceSystemId, UUID sourceImportId) {
        boolean valid = jdbc.sql("""
                select exists(
                  select 1 from historical_migration_source_import
                  where tenant_id = :tenant and source_system_id = :source and source_import_id = :import
                )
                """).param("tenant", tenantId).param("source", sourceSystemId).param("import", sourceImportId)
                .query(Boolean.class).single();
        if (!valid) throw new HistoricalMigrationBatchException(
                "HISTORICAL_MIGRATION_SOURCE_IMPORT_INVALID", 422,
                "The controlled source import does not belong to this active source system");
    }

    private int sourceRecordCount(UUID tenantId, UUID sourceSystemId, UUID sourceImportId) {
        String sql = sourceImportId == null ? """
                select count(*) from source_patient_match_candidate
                where tenant_id = :tenant and source_system_id = :source
                """ : """
                select count(*) from source_patient_match_candidate
                where tenant_id = :tenant and source_system_id = :source and source_import_id = :import
                """;
        JdbcClient.StatementSpec statement = jdbc.sql(sql).param("tenant", tenantId).param("source", sourceSystemId);
        if (sourceImportId != null) statement.param("import", sourceImportId);
        return statement.query(Integer.class).single();
    }

    private boolean hasPatientIdentifierMapping(UUID tenantId, UUID sourceSystemId) {
        return jdbc.sql("""
                select exists(
                  select 1 from source_field_mapping
                  where tenant_id = :tenant and source_system_id = :source and status = 'ACTIVE'
                    and lower(target_entity) = 'patient' and lower(target_field) = 'identifier'
                )
                """).param("tenant", tenantId).param("source", sourceSystemId).query(Boolean.class).single();
    }

    private List<CandidateSnapshot> candidateSnapshots(UUID tenantId, UUID sourceSystemId, UUID sourceImportId) {
        String sql = sourceImportId == null ? """
                select candidate_id, source_patient_identifier, review_status, matched_patient_id
                from source_patient_match_candidate
                where tenant_id = :tenant and source_system_id = :source
                order by candidate_id
                """ : """
                select candidate_id, source_patient_identifier, review_status, matched_patient_id
                from source_patient_match_candidate
                where tenant_id = :tenant and source_system_id = :source and source_import_id = :import
                order by candidate_id
                """;
        JdbcClient.StatementSpec statement = jdbc.sql(sql).param("tenant", tenantId).param("source", sourceSystemId);
        if (sourceImportId != null) statement.param("import", sourceImportId);
        return statement.query((rs, row) -> new CandidateSnapshot(
                        rs.getObject("candidate_id", UUID.class),
                        rs.getString("source_patient_identifier"),
                        rs.getString("review_status"),
                        rs.getObject("matched_patient_id", UUID.class)))
                .list();
    }

    private int reconciliationEvidenceCount(UUID tenantId, UUID batchId) {
        return jdbc.sql("""
                select count(*) from historical_migration_reconciliation_item
                where tenant_id = :tenant and batch_id = :batch
                """).param("tenant", tenantId).param("batch", batchId).query(Integer.class).single();
    }

    private int reconciliationMismatchCount(UUID tenantId, UUID batchId) {
        return jdbc.sql("""
                select count(*) from historical_migration_reconciliation_item
                where tenant_id = :tenant and batch_id = :batch and validation_status = 'MISMATCH'
                """).param("tenant", tenantId).param("batch", batchId).query(Integer.class).single();
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new HistoricalMigrationBatchException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new HistoricalMigrationBatchException("IDEMPOTENCY_REPLAY", 409,
                    "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID batchId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", batchId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, UUID batchId, String action, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + batchId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'HISTORICAL_MIGRATION_BATCH', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", batchId)
                .param("patient_hash", sha256(identity.tenantId() + "|null"))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'HISTORICAL_MIGRATION_BATCH', :aggregate, 1, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", batchId).param("event_type", eventType).update();
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw invalid(field + " must be at least " + minLength + " characters");
        }
        return value.trim();
    }

    private static HistoricalMigrationBatchException invalid(String message) {
        return new HistoricalMigrationBatchException("HISTORICAL_MIGRATION_REQUEST_INVALID", 400, message);
    }

    private static HistoricalMigrationBatchException versionConflict() {
        return new HistoricalMigrationBatchException(
                "HISTORICAL_MIGRATION_VERSION_CONFLICT", 409, "The batch changed; reload before retrying");
    }

    static HistoricalMigrationBatchException contextDenied() {
        return new HistoricalMigrationBatchException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested historical migration context is not permitted");
    }

    private record BatchHead(
            String status, int mismatchCount, long rowVersion, int recordCount, UUID sourceSystemId,
            UUID sourceImportId) {}

    private record SourceSnapshot(UUID sourceSystemId) {}

    private record CandidateSnapshot(
            UUID candidateId, String sourcePatientIdentifier, String reviewStatus, UUID matchedPatientId) {}

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
