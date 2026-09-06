package org.haoemr.pediatrics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.PediatricQcReviewCreateRequestWire;
import org.haoemr.contracts.PediatricQcReviewWire;
import org.haoemr.quality.SpecialtyQcReviewGuardService;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class PediatricQcReviewService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final SpecialtyQcReviewGuardService reviewGuard;

    PediatricQcReviewService(JdbcClient jdbc, TransactionTemplate transactions, SpecialtyQcReviewGuardService reviewGuard) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.reviewGuard = reviewGuard;
    }

    PediatricQcReviewWire record(
            ClinicalIdentity identity, String idempotencyKey, PediatricQcReviewCreateRequestWire request) {
        if (request.patientId() == null || request.encounterId() == null || request.reviewedRecordType() == null
                || request.reviewedRecordId() == null || request.reviewConclusion() == null || request.reviewedAt() == null) {
            throw invalid("patient_id, encounter_id, reviewed record, review conclusion and reviewed_at are required");
        }
        String defectDescription = blankToNull(request.defectDescription());
        if (request.reviewConclusion() == PediatricQcReviewCreateRequestWire.ReviewConclusionValue.FAIL
                && defectDescription == null) {
            throw new PediatricQcReviewException(
                    "PEDIATRIC_QC_DEFECT_REQUIRED", 400,
                    "A failed QC review requires a defect description");
        }
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            if (!reviewGuard.isReviewable(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId(), "PEDIATRIC", request.reviewedRecordType().name(), request.reviewedRecordId())) throw new PediatricQcReviewException("PEDIATRIC_QC_REVIEWED_RECORD_NOT_FOUND", 422, "The reviewed record is not available in the current patient encounter context");
            beginCommand(identity, "PEDIATRIC_QC_REVIEW", idempotencyKey,
                    sha256(request.patientId() + "|" + request.reviewedRecordType() + "|" + request.reviewedRecordId()));
            UUID reviewId = UUID.randomUUID();
            jdbc.sql("""
                    insert into pediatric_qc_review(
                      tenant_id, review_id, patient_id, encounter_id, facility_id, reviewed_record_type,
                      reviewed_record_id, review_conclusion, defect_description, reviewed_by, reviewed_at)
                    values (:tenant, :review, :patient, :encounter, :facility, :record_type,
                      :record_id, :conclusion, :defect, :reviewed_by, :reviewed_at)
                    """).param("tenant", identity.tenantId()).param("review", reviewId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId()).param("record_type", request.reviewedRecordType().name())
                    .param("record_id", request.reviewedRecordId()).param("conclusion", request.reviewConclusion().name())
                    .param("defect", defectDescription)
                    .param("reviewed_by", identity.userId())
                    .param("reviewed_at", request.reviewedAt().atOffset(ZoneOffset.UTC)).update();
            if (request.reviewConclusion() == PediatricQcReviewCreateRequestWire.ReviewConclusionValue.FAIL) reviewGuard.createRemediationTask(identity, reviewId, request.patientId(), request.encounterId(), request.facilityId(), "PEDIATRIC", request.reviewedRecordType().name(), request.reviewedRecordId(), defectDescription);
            appendEvidence(identity, request.patientId(), reviewId, 1, "PEDIATRIC_QC_REVIEWED",
                    "PediatricQcReviewed");
            completeCommand(identity, "PEDIATRIC_QC_REVIEW", idempotencyKey, reviewId);
            return review(identity.tenantId(), reviewId);
        });
    }

    List<PediatricQcReviewWire> listRecords(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select review_id from pediatric_qc_review
                where tenant_id = :tenant and patient_id = :patient
                order by reviewed_at desc, review_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> review(identity.tenantId(), id)).toList();
    }

    private PediatricQcReviewWire review(UUID tenantId, UUID reviewId) {
        return jdbc.sql("""
                select review_id, patient_id, encounter_id, facility_id, reviewed_record_type,
                  reviewed_record_id, review_conclusion, defect_description, reviewed_by, reviewed_at, row_version
                from pediatric_qc_review
                where tenant_id = :tenant and review_id = :review
                """).param("tenant", tenantId).param("review", reviewId)
                .query((rs, row) -> new PediatricQcReviewWire(
                        rs.getObject("review_id", UUID.class),
                        rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class),
                        rs.getObject("facility_id", UUID.class),
                        PediatricQcReviewWire.ReviewedRecordTypeValue.valueOf(rs.getString("reviewed_record_type")),
                        rs.getObject("reviewed_record_id", UUID.class),
                        PediatricQcReviewWire.ReviewConclusionValue.valueOf(rs.getString("review_conclusion")),
                        rs.getString("defect_description"),
                        rs.getObject("reviewed_by", UUID.class),
                        rs.getObject("reviewed_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("row_version")))
                .optional().orElseThrow(PediatricQcReviewService::contextDenied);
    }

    private void requireActiveEncounter(UUID tenantId, UUID patientId, UUID encounterId, UUID facilityId) {
        long count = jdbc.sql("""
                select count(*) from encounter
                where tenant_id = :tenant and encounter_id = :encounter and patient_id = :patient
                  and facility_id = :facility and status in ('ARRIVED', 'IN_PROGRESS', 'SUSPENDED')
                """).param("tenant", tenantId).param("encounter", encounterId).param("patient", patientId)
                .param("facility", facilityId).query(Long.class).single();
        if (count != 1) throw contextDenied();
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new PediatricQcReviewException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new PediatricQcReviewException("IDEMPOTENCY_REPLAY", 409,
                    "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID reviewId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", reviewId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID reviewId, long version,
            String action, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + reviewId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'PEDIATRIC_QC_REVIEW', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", reviewId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'PEDIATRIC_QC_REVIEW', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", reviewId).param("version", version).param("event_type", eventType).update();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static PediatricQcReviewException invalid(String message) {
        return new PediatricQcReviewException("PEDIATRIC_QC_REQUEST_INVALID", 400, message);
    }

    static PediatricQcReviewException contextDenied() {
        return new PediatricQcReviewException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested pediatrics QC context is not permitted");
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
