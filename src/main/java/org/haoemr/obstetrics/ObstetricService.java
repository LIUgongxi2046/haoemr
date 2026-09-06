package org.haoemr.obstetrics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ObstetricRecordCreateRequestWire;
import org.haoemr.contracts.ObstetricRecordWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class ObstetricService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    ObstetricService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    ObstetricRecordWire createRecord(
            ClinicalIdentity identity, String idempotencyKey, ObstetricRecordCreateRequestWire request) {
        if (request.gravidity() == null || request.parity() == null || request.gestationalWeeks() == null
                || request.bloodGroup() == null || request.rhFactor() == null
                || request.highRiskFactors() == null || request.highRiskFactors().trim().length() < 2) {
            throw invalid("gravidity, parity, gestational_weeks, blood_group, rh_factor and high_risk_factors are required");
        }
        if (request.parity() > request.gravidity()) {
            throw invalid("parity cannot exceed gravidity");
        }
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "OBSTETRIC_RECORD_CREATE", idempotencyKey,
                    sha256(request.patientId() + "|" + request.encounterId() + "|" + request.gravidity()
                            + "|" + request.gestationalWeeks()));
            UUID recordId = UUID.randomUUID();
            jdbc.sql("""
                    insert into obstetric_record(
                      tenant_id, obstetric_record_id, patient_id, encounter_id, facility_id,
                      gravidity, parity, gestational_weeks, estimated_due_date, blood_group,
                      rh_factor, high_risk_factors, status)
                    values (:tenant, :record, :patient, :encounter, :facility,
                      :gravidity, :parity, :gestational_weeks, :due_date, :blood_group,
                      :rh_factor, :high_risk_factors, 'ACTIVE')
                    """).param("tenant", identity.tenantId()).param("record", recordId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId()).param("gravidity", request.gravidity())
                    .param("parity", request.parity()).param("gestational_weeks", request.gestationalWeeks())
                    .param("due_date", request.estimatedDueDate()).param("blood_group", request.bloodGroup().name())
                    .param("rh_factor", request.rhFactor().name())
                    .param("high_risk_factors", request.highRiskFactors().trim()).update();
            appendEvidence(identity, request.patientId(), recordId, 1, "OBSTETRIC_RECORD_CREATED", "ObstetricRecordCreated");
            completeCommand(identity, "OBSTETRIC_RECORD_CREATE", idempotencyKey, recordId);
            return record(identity.tenantId(), recordId, request.patientId());
        });
    }

    List<ObstetricRecordWire> listRecords(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select obstetric_record_id from obstetric_record
                where tenant_id = :tenant and patient_id = :patient
                order by created_at desc, obstetric_record_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> record(identity.tenantId(), id, patientId)).toList();
    }

    private ObstetricRecordWire record(UUID tenantId, UUID recordId, UUID patientId) {
        return jdbc.sql("""
                select obstetric_record_id, patient_id, encounter_id, facility_id, gravidity, parity,
                  gestational_weeks, estimated_due_date, blood_group, rh_factor, high_risk_factors,
                  status, row_version
                from obstetric_record where tenant_id = :tenant and obstetric_record_id = :record
                  and patient_id = :patient
                """).param("tenant", tenantId).param("record", recordId).param("patient", patientId)
                .query((rs, row) -> new ObstetricRecordWire(
                        rs.getObject("obstetric_record_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class), rs.getObject("facility_id", UUID.class),
                        rs.getInt("gravidity"), rs.getInt("parity"), rs.getInt("gestational_weeks"),
                        rs.getObject("estimated_due_date", LocalDate.class),
                        ObstetricRecordWire.BloodGroupValue.valueOf(rs.getString("blood_group")),
                        ObstetricRecordWire.RhFactorValue.valueOf(rs.getString("rh_factor")),
                        rs.getString("high_risk_factors"),
                        ObstetricRecordWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getLong("row_version")))
                .optional().orElseThrow(() -> contextDenied());
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
            throw new ObstetricException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new ObstetricException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
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
                values (:tenant, :audit, now(), :actor, :action, 'OBSTETRIC_RECORD', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", recordId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'OBSTETRIC_RECORD', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", recordId).param("version", version).param("event_type", eventType).update();
    }

    private static ObstetricException invalid(String message) {
        return new ObstetricException("OBSTETRIC_RECORD_REQUEST_INVALID", 400, message);
    }

    static ObstetricException contextDenied() {
        return new ObstetricException("CONTEXT_NOT_PERMITTED", 403, "The requested obstetric context is not permitted");
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
