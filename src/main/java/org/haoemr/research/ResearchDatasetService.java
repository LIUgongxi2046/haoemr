package org.haoemr.research;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ResearchDatasetRequestApproveRequestWire;
import org.haoemr.contracts.ResearchDatasetRequestCreateRequestWire;
import org.haoemr.contracts.ResearchDatasetRequestDestroyRequestWire;
import org.haoemr.contracts.ResearchDatasetRequestExportRequestWire;
import org.haoemr.contracts.ResearchDatasetRequestWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class ResearchDatasetService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    ResearchDatasetService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    ResearchDatasetRequestWire create(
            ClinicalIdentity identity, String idempotencyKey, ResearchDatasetRequestCreateRequestWire request) {
        String purpose = request.purpose() == null ? null : request.purpose().trim();
        String scope = request.scopeDescription() == null ? null : request.scopeDescription().trim();
        UUID snapshotId = request.researchCohortSnapshotId();
        if (purpose == null || purpose.length() < 4 || scope == null || scope.length() < 4 || snapshotId == null) {
            throw invalid("purpose, scope_description and research_cohort_snapshot_id are required");
        }
        return transactions.execute(status -> {
            requireMaterializedSnapshot(identity.tenantId(), snapshotId);
            beginCommand(identity, "RESEARCH_DATASET_REQUEST_CREATE", idempotencyKey,
                    sha256(purpose + "|" + scope + "|" + snapshotId));
            UUID requestId = UUID.randomUUID();
            jdbc.sql("""
                    insert into research_dataset_request(
                      tenant_id, request_id, requester_id, purpose, scope_description,
                      research_cohort_snapshot_id, status)
                    values (:tenant, :request, :actor, :purpose, :scope, :snapshot, 'REQUESTED')
                    """).param("tenant", identity.tenantId()).param("request", requestId)
                    .param("actor", identity.userId()).param("purpose", purpose).param("scope", scope)
                    .param("snapshot", snapshotId).update();
            appendEvidence(identity, requestId, 1, "RESEARCH_DATASET_REQUESTED", "ResearchDatasetRequested");
            completeCommand(identity, "RESEARCH_DATASET_REQUEST_CREATE", idempotencyKey, requestId);
            return request(identity.tenantId(), requestId);
        });
    }

    ResearchDatasetRequestWire approve(
            ClinicalIdentity identity, String idempotencyKey, UUID requestId,
            ResearchDatasetRequestApproveRequestWire request) {
        return transition(identity, idempotencyKey, requestId, request.expectedRowVersion(),
                "REQUESTED", "APPROVED", "RESEARCH_DATASET_APPROVED", "ResearchDatasetApproved");
    }

    ResearchDatasetRequestWire export(
            ClinicalIdentity identity, String idempotencyKey, UUID requestId,
            ResearchDatasetRequestExportRequestWire request) {
        String watermark = request.watermark() == null ? null : request.watermark().trim();
        if (watermark == null || watermark.length() < 2) throw invalid("a watermark is required");
        return transactions.execute(status -> {
            beginCommand(identity, "RESEARCH_DATASET_EXPORT", idempotencyKey,
                    sha256(requestId + "|" + request.expectedRowVersion() + "|" + watermark));
            RequestHead current = lock(identity.tenantId(), requestId);
            if (request.expectedRowVersion() == null || current.rowVersion() != request.expectedRowVersion()) {
                throw new ResearchDatasetException("RESEARCH_DATASET_VERSION_CONFLICT", 409, "The request changed; reload before retrying");
            }
            if (!"APPROVED".equals(current.status())) {
                throw new ResearchDatasetException("RESEARCH_DATASET_STATE_INVALID", 409, "Only an approved request can be exported");
            }
            jdbc.sql("""
                    update research_dataset_request set status = 'EXPORTED', exported_at = now(),
                      exported_by = :actor, export_watermark = :watermark,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and request_id = :request and row_version = :expected
                    """).param("actor", identity.userId()).param("watermark", watermark)
                    .param("tenant", identity.tenantId()).param("request", requestId)
                    .param("expected", current.rowVersion()).update();
            appendEvidence(identity, requestId, current.rowVersion() + 1,
                    "RESEARCH_DATASET_EXPORTED", "ResearchDatasetExported");
            completeCommand(identity, "RESEARCH_DATASET_EXPORT", idempotencyKey, requestId);
            return request(identity.tenantId(), requestId);
        });
    }

    ResearchDatasetRequestWire destroy(
            ClinicalIdentity identity, String idempotencyKey, UUID requestId,
            ResearchDatasetRequestDestroyRequestWire request) {
        return transition(identity, idempotencyKey, requestId, request.expectedRowVersion(),
                "EXPORTED", "DESTROYED", "RESEARCH_DATASET_DESTROYED", "ResearchDatasetDestroyed");
    }

    List<ResearchDatasetRequestWire> list(ClinicalIdentity identity) {
        return jdbc.sql("""
                select request_id from research_dataset_request
                where tenant_id = :tenant order by created_at desc, request_id desc limit 200
                """).param("tenant", identity.tenantId()).query(UUID.class).list().stream()
                .map(id -> request(identity.tenantId(), id)).toList();
    }

    byte[] exportFile(ClinicalIdentity identity, UUID requestId) {
        RequestExport export = jdbc.sql("""
                select status, export_watermark, research_cohort_snapshot_id from research_dataset_request
                where tenant_id = :tenant and request_id = :request
                """).param("tenant", identity.tenantId()).param("request", requestId)
                .query((rs, row) -> new RequestExport(rs.getString("status"), rs.getString("export_watermark"),
                        rs.getObject("research_cohort_snapshot_id", UUID.class)))
                .optional().orElseThrow(ResearchDatasetService::contextDenied);
        if (!"EXPORTED".equals(export.status())) throw new ResearchDatasetException(
                "RESEARCH_DATASET_FILE_NOT_AVAILABLE", 409, "Only an exported request has a downloadable data file");
        if (export.snapshotId() == null) throw new ResearchDatasetException(
                "RESEARCH_DATASET_SNAPSHOT_REQUIRED", 409, "This legacy request is not bound to a materialized cohort snapshot");
        StringBuilder csv = new StringBuilder("# watermarked_deidentified_dataset,watermark=")
                .append(csv(export.watermark())).append('\n')
                .append("subject_pseudonym,sex_code,birth_year\n");
        jdbc.sql("""
                select patient_id, sex_code, birth_year from research_cohort_snapshot_member
                where tenant_id = :tenant and research_cohort_snapshot_id = :snapshot order by patient_id
                """).param("tenant", identity.tenantId()).param("snapshot", export.snapshotId()).query((rs, row) -> new PatientExport(
                        rs.getObject("patient_id", UUID.class), rs.getString("sex_code"), rs.getInt("birth_year")))
                .list().forEach(patient -> csv.append(sha256(identity.tenantId() + "|" + requestId + "|" + patient.patientId()).substring(0, 24))
                        .append(',').append(csv(patient.sexCode())).append(',').append(patient.birthYear()).append('\n'));
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private ResearchDatasetRequestWire transition(
            ClinicalIdentity identity, String idempotencyKey, UUID requestId, Long expectedRowVersion,
            String fromStatus, String toStatus, String action, String eventType) {
        return transactions.execute(status -> {
            beginCommand(identity, "RESEARCH_DATASET_" + toStatus, idempotencyKey,
                    sha256(requestId + "|" + expectedRowVersion));
            RequestHead current = lock(identity.tenantId(), requestId);
            if (expectedRowVersion == null || current.rowVersion() != expectedRowVersion) {
                throw new ResearchDatasetException("RESEARCH_DATASET_VERSION_CONFLICT", 409, "The request changed; reload before retrying");
            }
            if (!fromStatus.equals(current.status())) {
                throw new ResearchDatasetException("RESEARCH_DATASET_STATE_INVALID", 409,
                        "Only a " + fromStatus + " request can transition to " + toStatus);
            }
            jdbc.sql("""
                    update research_dataset_request set status = :to_status,
                      approved_by = case when :to_status = 'APPROVED' then :actor else approved_by end,
                      approved_at = case when :to_status = 'APPROVED' then now() else approved_at end,
                      destroyed_at = case when :to_status = 'DESTROYED' then now() else destroyed_at end,
                      destroyed_by = case when :to_status = 'DESTROYED' then :actor else destroyed_by end,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and request_id = :request and row_version = :expected
                    """).param("to_status", toStatus).param("actor", identity.userId())
                    .param("tenant", identity.tenantId()).param("request", requestId)
                    .param("expected", current.rowVersion()).update();
            appendEvidence(identity, requestId, current.rowVersion() + 1, action, eventType);
            completeCommand(identity, "RESEARCH_DATASET_" + toStatus, idempotencyKey, requestId);
            return request(identity.tenantId(), requestId);
        });
    }

    private RequestHead lock(UUID tenantId, UUID requestId) {
        return jdbc.sql("""
                select status, row_version from research_dataset_request
                where tenant_id = :tenant and request_id = :request for update
                """).param("tenant", tenantId).param("request", requestId)
                .query((rs, row) -> new RequestHead(rs.getString("status"), rs.getLong("row_version")))
                .optional().orElseThrow(() -> contextDenied());
    }

    private ResearchDatasetRequestWire request(UUID tenantId, UUID requestId) {
        return jdbc.sql("""
                select request_id, requester_id, purpose, scope_description, status, approved_by,
                  approved_at, rejection_reason, exported_at, exported_by, export_watermark,
                  research_cohort_snapshot_id,
                  destroyed_at, destroyed_by, row_version
                from research_dataset_request where tenant_id = :tenant and request_id = :request
                """).param("tenant", tenantId).param("request", requestId)
                .query((rs, row) -> new ResearchDatasetRequestWire(
                        rs.getObject("request_id", UUID.class), rs.getObject("requester_id", UUID.class),
                        rs.getString("purpose"), rs.getString("scope_description"),
                        ResearchDatasetRequestWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getObject("approved_by", UUID.class),
                        rs.getObject("approved_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("approved_at", OffsetDateTime.class).toInstant(),
                        rs.getString("rejection_reason"),
                        rs.getObject("exported_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("exported_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("exported_by", UUID.class), rs.getString("export_watermark"),
                        rs.getObject("research_cohort_snapshot_id", UUID.class),
                        rs.getObject("destroyed_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("destroyed_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("destroyed_by", UUID.class), rs.getLong("row_version")))
                .optional().orElseThrow(() -> contextDenied());
    }

    private void requireMaterializedSnapshot(UUID tenantId, UUID snapshotId) {
        Boolean materialized = jdbc.sql("""
                select member_snapshot_materialized from research_cohort_snapshot
                where tenant_id = :tenant and research_cohort_snapshot_id = :snapshot
                """).param("tenant", tenantId).param("snapshot", snapshotId)
                .query(Boolean.class).optional().orElseThrow(ResearchDatasetService::contextDenied);
        if (!materialized) throw new ResearchDatasetException(
                "RESEARCH_DATASET_SNAPSHOT_NOT_MATERIALIZED", 409,
                "Choose a cohort snapshot with materialized member attributes");
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new ResearchDatasetException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new ResearchDatasetException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID requestId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", requestId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, UUID requestId, long version, String action, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + requestId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'RESEARCH_DATASET_REQUEST', :resource,
                  null, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", requestId)
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'RESEARCH_DATASET_REQUEST', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", requestId).param("version", version).param("event_type", eventType).update();
    }

    private static ResearchDatasetException invalid(String message) {
        return new ResearchDatasetException("RESEARCH_DATASET_REQUEST_INVALID", 400, message);
    }

    static ResearchDatasetException contextDenied() {
        return new ResearchDatasetException("CONTEXT_NOT_PERMITTED", 403, "The requested research dataset context is not permitted");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record RequestHead(String status, long rowVersion) {}
    private record RequestExport(String status, String watermark, UUID snapshotId) {}
    private record PatientExport(UUID patientId, String sexCode, int birthYear) {}

    private static String csv(String value) {
        return '"' + (value == null ? "" : value.replace("\"", "\"\"")) + '"';
    }
}
