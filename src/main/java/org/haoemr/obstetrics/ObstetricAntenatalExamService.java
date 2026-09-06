package org.haoemr.obstetrics;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ObstetricAntenatalExamCreateRequestWire;
import org.haoemr.contracts.ObstetricAntenatalExamWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class ObstetricAntenatalExamService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    ObstetricAntenatalExamService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    ObstetricAntenatalExamWire record(
            ClinicalIdentity identity, String idempotencyKey, ObstetricAntenatalExamCreateRequestWire request) {
        if (request.patientId() == null || request.encounterId() == null || request.gestationalWeeks() == null
                || request.systolicBp() == null || request.diastolicBp() == null || request.proteinuria() == null
                || request.preeclampsiaRisk() == null || request.examinedAt() == null) {
            throw invalid("patient_id, encounter_id, gestational_weeks, blood pressure, proteinuria, "
                    + "preeclampsia_risk and examined_at are required");
        }
        if (request.gestationalWeeks() < 0 || request.gestationalWeeks() > 45) {
            throw invalid("gestational_weeks must be between 0 and 45");
        }
        boolean preeclampsiaCriteriaMet = (request.systolicBp() >= 140 || request.diastolicBp() >= 90)
                && request.proteinuria() == ObstetricAntenatalExamCreateRequestWire.ProteinuriaValue.POSITIVE;
        if (Boolean.TRUE.equals(request.preeclampsiaRisk()) && !preeclampsiaCriteriaMet) {
            throw new ObstetricAntenatalExamException(
                    "PREECLAMPSIA_RISK_CRITERIA_UNMET", 400,
                    "A preeclampsia risk flag requires hypertension and positive proteinuria");
        }
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "OBSTETRIC_ANTENATAL_EXAM", idempotencyKey,
                    sha256(request.patientId() + "|" + request.gestationalWeeks() + "|" + request.examinedAt()));
            UUID examId = UUID.randomUUID();
            jdbc.sql("""
                    insert into obstetric_antenatal_exam(
                      tenant_id, exam_id, patient_id, encounter_id, facility_id, gestational_weeks,
                      fundal_height_cm, fetal_heart_rate, systolic_bp, diastolic_bp, proteinuria,
                      preeclampsia_risk, examined_at, recorded_by)
                    values (:tenant, :exam, :patient, :encounter, :facility, :weeks,
                      :fundal, :fhr, :systolic, :diastolic, :proteinuria,
                      :preeclampsia, :examined_at, :recorded_by)
                    """).param("tenant", identity.tenantId()).param("exam", examId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId()).param("weeks", request.gestationalWeeks())
                    .param("fundal", request.fundalHeightCm() == null
                            ? null : BigDecimal.valueOf(request.fundalHeightCm()))
                    .param("fhr", request.fetalHeartRate())
                    .param("systolic", request.systolicBp()).param("diastolic", request.diastolicBp())
                    .param("proteinuria", request.proteinuria().name())
                    .param("preeclampsia", request.preeclampsiaRisk())
                    .param("examined_at", request.examinedAt().atOffset(ZoneOffset.UTC))
                    .param("recorded_by", identity.userId()).update();
            appendEvidence(identity, request.patientId(), examId, 1, "OBSTETRIC_ANTENATAL_EXAM_RECORDED",
                    "ObstetricAntenatalExamRecorded");
            completeCommand(identity, "OBSTETRIC_ANTENATAL_EXAM", idempotencyKey, examId);
            return exam(identity.tenantId(), examId);
        });
    }

    List<ObstetricAntenatalExamWire> listRecords(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select exam_id from obstetric_antenatal_exam
                where tenant_id = :tenant and patient_id = :patient
                order by examined_at desc, exam_id desc limit 200
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> exam(identity.tenantId(), id)).toList();
    }

    private ObstetricAntenatalExamWire exam(UUID tenantId, UUID examId) {
        return jdbc.sql("""
                select exam_id, patient_id, encounter_id, facility_id, gestational_weeks, fundal_height_cm,
                  fetal_heart_rate, systolic_bp, diastolic_bp, proteinuria, preeclampsia_risk, examined_at,
                  recorded_by, row_version
                from obstetric_antenatal_exam
                where tenant_id = :tenant and exam_id = :exam
                """).param("tenant", tenantId).param("exam", examId)
                .query((rs, row) -> new ObstetricAntenatalExamWire(
                        rs.getObject("exam_id", UUID.class),
                        rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class),
                        rs.getObject("facility_id", UUID.class),
                        rs.getInt("gestational_weeks"),
                        rs.getBigDecimal("fundal_height_cm") == null
                                ? null : rs.getBigDecimal("fundal_height_cm").doubleValue(),
                        (Integer) rs.getObject("fetal_heart_rate"),
                        rs.getInt("systolic_bp"),
                        rs.getInt("diastolic_bp"),
                        ObstetricAntenatalExamWire.ProteinuriaValue.valueOf(rs.getString("proteinuria")),
                        rs.getBoolean("preeclampsia_risk"),
                        rs.getObject("examined_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("recorded_by", UUID.class),
                        rs.getLong("row_version")))
                .optional().orElseThrow(ObstetricAntenatalExamService::contextDenied);
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
            throw new ObstetricAntenatalExamException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new ObstetricAntenatalExamException("IDEMPOTENCY_REPLAY", 409,
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
                values (:tenant, :audit, now(), :actor, :action, 'OBSTETRIC_ANTENATAL_EXAM', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", examId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'OBSTETRIC_ANTENATAL_EXAM', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", examId).param("version", version).param("event_type", eventType).update();
    }

    private static ObstetricAntenatalExamException invalid(String message) {
        return new ObstetricAntenatalExamException("OBSTETRIC_ANTENATAL_REQUEST_INVALID", 400, message);
    }

    static ObstetricAntenatalExamException contextDenied() {
        return new ObstetricAntenatalExamException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested obstetric antenatal exam context is not permitted");
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
