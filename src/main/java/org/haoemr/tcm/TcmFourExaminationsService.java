package org.haoemr.tcm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.TcmFourExaminationsCreateRequestWire;
import org.haoemr.contracts.TcmFourExaminationsWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class TcmFourExaminationsService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    TcmFourExaminationsService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    TcmFourExaminationsWire record(
            ClinicalIdentity identity, String idempotencyKey, TcmFourExaminationsCreateRequestWire request) {
        if (request.patientId() == null || request.encounterId() == null || request.examinedAt() == null) {
            throw invalid("patient_id, encounter_id and examined_at are required");
        }
        String inspection = requireText(request.inspection(), 2, "inspection");
        String auscultation = requireText(request.auscultation(), 2, "auscultation");
        String inquiry = requireText(request.inquiry(), 2, "inquiry");
        String palpation = requireText(request.palpation(), 2, "palpation");
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "TCM_FOUR_EXAMINATIONS", idempotencyKey,
                    sha256(request.patientId() + "|" + request.examinedAt()));
            UUID examId = UUID.randomUUID();
            jdbc.sql("""
                    insert into tcm_four_examinations(
                      tenant_id, exam_id, patient_id, encounter_id, facility_id,
                      inspection, auscultation, inquiry, palpation, examined_at, recorded_by)
                    values (:tenant, :exam, :patient, :encounter, :facility,
                      :inspection, :auscultation, :inquiry, :palpation, :examined_at, :recorded_by)
                    """).param("tenant", identity.tenantId()).param("exam", examId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId())
                    .param("inspection", inspection).param("auscultation", auscultation)
                    .param("inquiry", inquiry).param("palpation", palpation)
                    .param("examined_at", request.examinedAt().atOffset(ZoneOffset.UTC))
                    .param("recorded_by", identity.userId()).update();
            appendEvidence(identity, request.patientId(), examId, 1, "TCM_FOUR_EXAMINATIONS_RECORDED",
                    "TcmFourExaminationsRecorded");
            completeCommand(identity, "TCM_FOUR_EXAMINATIONS", idempotencyKey, examId);
            return exam(identity.tenantId(), examId);
        });
    }

    List<TcmFourExaminationsWire> listRecords(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select exam_id from tcm_four_examinations
                where tenant_id = :tenant and patient_id = :patient
                order by examined_at desc, exam_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> exam(identity.tenantId(), id)).toList();
    }

    private TcmFourExaminationsWire exam(UUID tenantId, UUID examId) {
        return jdbc.sql("""
                select exam_id, patient_id, encounter_id, facility_id, inspection, auscultation,
                  inquiry, palpation, examined_at, recorded_by, row_version
                from tcm_four_examinations
                where tenant_id = :tenant and exam_id = :exam
                """).param("tenant", tenantId).param("exam", examId)
                .query((rs, row) -> new TcmFourExaminationsWire(
                        rs.getObject("exam_id", UUID.class),
                        rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class),
                        rs.getObject("facility_id", UUID.class),
                        rs.getString("inspection"),
                        rs.getString("auscultation"),
                        rs.getString("inquiry"),
                        rs.getString("palpation"),
                        rs.getObject("examined_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("recorded_by", UUID.class),
                        rs.getLong("row_version")))
                .optional().orElseThrow(TcmFourExaminationsService::contextDenied);
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
            throw new TcmFourExaminationsException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new TcmFourExaminationsException("IDEMPOTENCY_REPLAY", 409,
                    "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID examId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", examId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID examId, long version,
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
                + examId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'TCM_FOUR_EXAMINATIONS', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", examId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'TCM_FOUR_EXAMINATIONS', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", examId).param("version", version).param("event_type", eventType).update();
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw invalid(field + " must be at least " + minLength + " characters");
        }
        return value.trim();
    }

    private static TcmFourExaminationsException invalid(String message) {
        return new TcmFourExaminationsException("TCM_FOUR_EXAMINATIONS_REQUEST_INVALID", 400, message);
    }

    static TcmFourExaminationsException contextDenied() {
        return new TcmFourExaminationsException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested TCM four examinations context is not permitted");
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
