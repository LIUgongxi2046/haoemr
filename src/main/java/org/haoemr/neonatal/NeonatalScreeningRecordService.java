package org.haoemr.neonatal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.NeonatalScreeningRecordCreateRequestWire;
import org.haoemr.contracts.NeonatalScreeningRecordWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class NeonatalScreeningRecordService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    NeonatalScreeningRecordService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    NeonatalScreeningRecordWire record(
            ClinicalIdentity identity, String idempotencyKey, NeonatalScreeningRecordCreateRequestWire request) {
        if (request.patientId() == null || request.motherPatientId() == null || request.encounterId() == null
                || request.screeningType() == null || request.screeningResult() == null || request.screenedAt() == null) {
            throw invalid("patient_id, mother_patient_id, encounter_id, screening_type, "
                    + "screening_result and screened_at are required");
        }
        if (request.motherPatientId().equals(request.patientId())) {
            throw new NeonatalScreeningRecordException(
                    "MOTHER_NEONATE_SAME_PATIENT", 400,
                    "The neonate and mother must be different patients");
        }
        requireMotherFemale(identity.tenantId(), request.motherPatientId());
        String referredTo = blankToNull(request.referredTo());
        if (request.screeningResult() == NeonatalScreeningRecordCreateRequestWire.ScreeningResultValue.REFER
                && referredTo == null) {
            throw new NeonatalScreeningRecordException(
                    "NEONATAL_SCREENING_REFER_REQUIRED", 400,
                    "A referred neonatal screening requires a referral target");
        }
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "NEONATAL_SCREENING_RECORD", idempotencyKey,
                    sha256(request.patientId() + "|" + request.screeningType() + "|" + request.screenedAt()));
            UUID screeningId = UUID.randomUUID();
            jdbc.sql("""
                    insert into neonatal_screening_record(
                      tenant_id, screening_id, patient_id, mother_patient_id, encounter_id, facility_id,
                      screening_type, screening_result, referred_to, screened_at, recorded_by)
                    values (:tenant, :screening, :patient, :mother, :encounter, :facility,
                      :type, :result, :referred_to, :screened_at, :recorded_by)
                    """).param("tenant", identity.tenantId()).param("screening", screeningId)
                    .param("patient", request.patientId()).param("mother", request.motherPatientId())
                    .param("encounter", request.encounterId()).param("facility", request.facilityId())
                    .param("type", request.screeningType().name()).param("result", request.screeningResult().name())
                    .param("referred_to", referredTo)
                    .param("screened_at", request.screenedAt().atOffset(ZoneOffset.UTC))
                    .param("recorded_by", identity.userId()).update();
            appendEvidence(identity, request.patientId(), screeningId, 1, "NEONATAL_SCREENING_RECORDED",
                    "NeonatalScreeningRecorded");
            completeCommand(identity, "NEONATAL_SCREENING_RECORD", idempotencyKey, screeningId);
            return screening(identity.tenantId(), screeningId);
        });
    }

    List<NeonatalScreeningRecordWire> listRecords(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select screening_id from neonatal_screening_record
                where tenant_id = :tenant and patient_id = :patient
                order by screened_at desc, screening_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> screening(identity.tenantId(), id)).toList();
    }

    private NeonatalScreeningRecordWire screening(UUID tenantId, UUID screeningId) {
        return jdbc.sql("""
                select screening_id, patient_id, mother_patient_id, encounter_id, facility_id, screening_type,
                  screening_result, referred_to, screened_at, recorded_by, row_version
                from neonatal_screening_record
                where tenant_id = :tenant and screening_id = :screening
                """).param("tenant", tenantId).param("screening", screeningId)
                .query((rs, row) -> new NeonatalScreeningRecordWire(
                        rs.getObject("screening_id", UUID.class),
                        rs.getObject("patient_id", UUID.class),
                        rs.getObject("mother_patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class),
                        rs.getObject("facility_id", UUID.class),
                        NeonatalScreeningRecordWire.ScreeningTypeValue.valueOf(rs.getString("screening_type")),
                        NeonatalScreeningRecordWire.ScreeningResultValue.valueOf(rs.getString("screening_result")),
                        rs.getString("referred_to"),
                        rs.getObject("screened_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("recorded_by", UUID.class),
                        rs.getLong("row_version")))
                .optional().orElseThrow(NeonatalScreeningRecordService::contextDenied);
    }

    private void requireMotherFemale(UUID tenantId, UUID motherPatientId) {
        String sexCode = jdbc.sql("""
                select sex_code from patient where tenant_id = :tenant and patient_id = :mother
                """).param("tenant", tenantId).param("mother", motherPatientId)
                .query(String.class).optional().orElseThrow(NeonatalScreeningRecordService::contextDenied);
        if (!"F".equals(sexCode)) {
            throw new NeonatalScreeningRecordException(
                    "MOTHER_NOT_FEMALE", 400, "mother_patient_id must reference a female patient");
        }
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
            throw new NeonatalScreeningRecordException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new NeonatalScreeningRecordException("IDEMPOTENCY_REPLAY", 409,
                    "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID screeningId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", screeningId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID screeningId, long version,
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
                + screeningId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'NEONATAL_SCREENING', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", screeningId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'NEONATAL_SCREENING', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", screeningId).param("version", version).param("event_type", eventType).update();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static NeonatalScreeningRecordException invalid(String message) {
        return new NeonatalScreeningRecordException("NEONATAL_SCREENING_REQUEST_INVALID", 400, message);
    }

    static NeonatalScreeningRecordException contextDenied() {
        return new NeonatalScreeningRecordException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested neonatal screening context is not permitted");
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
