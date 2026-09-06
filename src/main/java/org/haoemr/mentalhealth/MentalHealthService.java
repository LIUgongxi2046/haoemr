package org.haoemr.mentalhealth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.MentalHealthRecordCreateRequestWire;
import org.haoemr.contracts.MentalHealthRecordWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class MentalHealthService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    MentalHealthService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    MentalHealthRecordWire createRecord(
            ClinicalIdentity identity, String idempotencyKey, MentalHealthRecordCreateRequestWire request) {
        if (request.suicideRiskLevel() == null || request.violenceRiskLevel() == null
                || request.riskAssessedAt() == null) {
            throw invalid("suicide_risk_level, violence_risk_level and risk_assessed_at are required");
        }
        boolean highRisk = request.suicideRiskLevel() == MentalHealthRecordCreateRequestWire.SuicideRiskLevelValue.HIGH
                || request.suicideRiskLevel() == MentalHealthRecordCreateRequestWire.SuicideRiskLevelValue.IMMINENT
                || request.violenceRiskLevel() == MentalHealthRecordCreateRequestWire.ViolenceRiskLevelValue.HIGH;
        String measures = blankToNull(request.protectiveMeasures());
        if (highRisk && measures == null) {
            throw invalid("protective_measures are required when suicide or violence risk is HIGH or IMMINENT");
        }
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "MENTAL_HEALTH_RECORD_CREATE", idempotencyKey,
                    sha256(request.patientId() + "|" + request.encounterId() + "|" + request.suicideRiskLevel()
                            + "|" + request.violenceRiskLevel() + "|" + request.riskAssessedAt()));
            UUID recordId = UUID.randomUUID();
            jdbc.sql("""
                    insert into mental_health_record(
                      tenant_id, mental_health_record_id, patient_id, encounter_id, facility_id,
                      suicide_risk_level, violence_risk_level, risk_assessed_at, protective_measures, status)
                    values (:tenant, :record, :patient, :encounter, :facility,
                      :suicide, :violence, :assessed_at, :measures, 'ACTIVE')
                    """).param("tenant", identity.tenantId()).param("record", recordId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId())
                    .param("suicide", request.suicideRiskLevel().name())
                    .param("violence", request.violenceRiskLevel().name())
                    .param("assessed_at", request.riskAssessedAt().atOffset(ZoneOffset.UTC))
                    .param("measures", measures).update();
            appendEvidence(identity, request.patientId(), recordId, 1, "MENTAL_HEALTH_RECORD_CREATED",
                    "MentalHealthRecordCreated");
            completeCommand(identity, "MENTAL_HEALTH_RECORD_CREATE", idempotencyKey, recordId);
            return record(identity.tenantId(), recordId, request.patientId());
        });
    }

    List<MentalHealthRecordWire> listRecords(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select mental_health_record_id from mental_health_record
                where tenant_id = :tenant and patient_id = :patient
                order by risk_assessed_at desc, mental_health_record_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> record(identity.tenantId(), id, patientId)).toList();
    }

    private MentalHealthRecordWire record(UUID tenantId, UUID recordId, UUID patientId) {
        return jdbc.sql("""
                select mental_health_record_id, patient_id, encounter_id, facility_id, data_classification,
                  suicide_risk_level, violence_risk_level, risk_assessed_at, protective_measures, status, row_version
                from mental_health_record
                where tenant_id = :tenant and mental_health_record_id = :record and patient_id = :patient
                """).param("tenant", tenantId).param("record", recordId).param("patient", patientId)
                .query((rs, row) -> new MentalHealthRecordWire(
                        rs.getObject("mental_health_record_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class), rs.getObject("facility_id", UUID.class),
                        MentalHealthRecordWire.DataClassificationValue.valueOf(rs.getString("data_classification")),
                        MentalHealthRecordWire.SuicideRiskLevelValue.valueOf(rs.getString("suicide_risk_level")),
                        MentalHealthRecordWire.ViolenceRiskLevelValue.valueOf(rs.getString("violence_risk_level")),
                        rs.getObject("risk_assessed_at", OffsetDateTime.class).toInstant(),
                        rs.getString("protective_measures"),
                        MentalHealthRecordWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getLong("row_version")))
                .optional().orElseThrow(MentalHealthService::contextDenied);
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
            throw new MentalHealthException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new MentalHealthException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
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
                values (:tenant, :audit, now(), :actor, :action, 'MENTAL_HEALTH_RECORD', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", recordId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'MENTAL_HEALTH_RECORD', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", recordId).param("version", version).param("event_type", eventType).update();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static MentalHealthException invalid(String message) {
        return new MentalHealthException("MENTAL_HEALTH_REQUEST_INVALID", 400, message);
    }

    static MentalHealthException contextDenied() {
        return new MentalHealthException("CONTEXT_NOT_PERMITTED", 403, "The requested mental health record context is not permitted");
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
