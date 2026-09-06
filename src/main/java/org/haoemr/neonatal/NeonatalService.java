package org.haoemr.neonatal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.NeonatalRecordCreateRequestWire;
import org.haoemr.contracts.NeonatalRecordWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class NeonatalService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    NeonatalService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    NeonatalRecordWire createRecord(
            ClinicalIdentity identity, String idempotencyKey, NeonatalRecordCreateRequestWire request) {
        if (request.motherPatientId() == null || request.sexAtBirth() == null || request.birthDatetime() == null
                || request.gestationalAgeWeeks() == null || request.apgar1min() == null
                || request.apgar5min() == null || request.birthWeightG() == null) {
            throw invalid("mother_patient_id, sex_at_birth, birth_datetime, gestational_age_weeks, "
                    + "apgar_1min, apgar_5min and birth_weight_g are required");
        }
        if (request.motherPatientId().equals(request.patientId())) {
            throw invalid("mother cannot be the same patient as the newborn");
        }
        if (request.gestationalAgeWeeks() < 22 || request.gestationalAgeWeeks() > 45) {
            throw invalid("gestational_age_weeks must be between 22 and 45");
        }
        if (request.apgar1min() < 0 || request.apgar1min() > 10
                || request.apgar5min() < 0 || request.apgar5min() > 10) {
            throw invalid("Apgar scores must be between 0 and 10");
        }
        if (request.birthWeightG() < 200 || request.birthWeightG() > 7000) {
            throw invalid("birth_weight_g must be between 200 and 7000");
        }
        requireMotherFemale(identity.tenantId(), request.motherPatientId());
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "NEONATAL_RECORD_CREATE", idempotencyKey,
                    sha256(request.patientId() + "|" + request.motherPatientId() + "|" + request.encounterId()
                            + "|" + request.birthDatetime() + "|" + request.gestationalAgeWeeks()));
            UUID recordId = UUID.randomUUID();
            jdbc.sql("""
                    insert into neonatal_record(
                      tenant_id, neonatal_record_id, patient_id, mother_patient_id, encounter_id, facility_id,
                      birth_datetime, gestational_age_weeks, apgar_1min, apgar_5min, birth_weight_g,
                      sex_at_birth, status)
                    values (:tenant, :record, :patient, :mother, :encounter, :facility,
                      :birth_at, :gestational_weeks, :apgar1, :apgar5, :weight, :sex, 'ACTIVE')
                    """).param("tenant", identity.tenantId()).param("record", recordId)
                    .param("patient", request.patientId()).param("mother", request.motherPatientId())
                    .param("encounter", request.encounterId()).param("facility", request.facilityId())
                    .param("birth_at", request.birthDatetime().atOffset(ZoneOffset.UTC))
                    .param("gestational_weeks", request.gestationalAgeWeeks())
                    .param("apgar1", request.apgar1min()).param("apgar5", request.apgar5min())
                    .param("weight", request.birthWeightG())
                    .param("sex", request.sexAtBirth().name()).update();
            appendEvidence(identity, request.patientId(), recordId, 1, "NEONATAL_RECORD_CREATED", "NeonatalRecordCreated");
            completeCommand(identity, "NEONATAL_RECORD_CREATE", idempotencyKey, recordId);
            return record(identity.tenantId(), recordId, request.patientId());
        });
    }

    List<NeonatalRecordWire> listRecords(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select neonatal_record_id from neonatal_record
                where tenant_id = :tenant and patient_id = :patient
                order by birth_datetime desc, neonatal_record_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> record(identity.tenantId(), id, patientId)).toList();
    }

    private NeonatalRecordWire record(UUID tenantId, UUID recordId, UUID patientId) {
        return jdbc.sql("""
                select neonatal_record_id, patient_id, mother_patient_id, encounter_id, facility_id,
                  birth_datetime, gestational_age_weeks, apgar_1min, apgar_5min, birth_weight_g,
                  sex_at_birth, status, row_version
                from neonatal_record where tenant_id = :tenant and neonatal_record_id = :record and patient_id = :patient
                """).param("tenant", tenantId).param("record", recordId).param("patient", patientId)
                .query((rs, row) -> new NeonatalRecordWire(
                        rs.getObject("neonatal_record_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("mother_patient_id", UUID.class), rs.getObject("encounter_id", UUID.class),
                        rs.getObject("facility_id", UUID.class),
                        rs.getObject("birth_datetime", OffsetDateTime.class).toInstant(),
                        rs.getInt("gestational_age_weeks"), rs.getInt("apgar_1min"), rs.getInt("apgar_5min"),
                        rs.getInt("birth_weight_g"),
                        NeonatalRecordWire.SexAtBirthValue.valueOf(rs.getString("sex_at_birth")),
                        NeonatalRecordWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getLong("row_version")))
                .optional().orElseThrow(NeonatalService::contextDenied);
    }

    private void requireMotherFemale(UUID tenantId, UUID motherPatientId) {
        String sexCode = jdbc.sql("""
                select sex_code from patient where tenant_id = :tenant and patient_id = :mother
                """).param("tenant", tenantId).param("mother", motherPatientId)
                .query(String.class).optional().orElseThrow(NeonatalService::contextDenied);
        if (!"F".equals(sexCode)) {
            throw invalid("mother_patient_id must reference a female patient");
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
            throw new NeonatalException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new NeonatalException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID recordId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", recordId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID recordId, long version,
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
                + recordId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'NEONATAL_RECORD', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", recordId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'NEONATAL_RECORD', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", recordId).param("version", version).param("event_type", eventType).update();
    }

    private static NeonatalException invalid(String message) {
        return new NeonatalException("NEONATAL_REQUEST_INVALID", 400, message);
    }

    static NeonatalException contextDenied() {
        return new NeonatalException("CONTEXT_NOT_PERMITTED", 403, "The requested neonatal record context is not permitted");
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
