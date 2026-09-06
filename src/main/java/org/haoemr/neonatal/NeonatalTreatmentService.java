package org.haoemr.neonatal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.NeonatalTreatmentCreateRequestWire;
import org.haoemr.contracts.NeonatalTreatmentWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class NeonatalTreatmentService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    NeonatalTreatmentService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    NeonatalTreatmentWire create(
            ClinicalIdentity identity, String idempotencyKey, NeonatalTreatmentCreateRequestWire request) {
        String assessment = requireText(request.assessment(), 2, "assessment");
        String intervention = requireText(request.intervention(), 2, "intervention");
        if (request.riskFlag() == null || request.recordedAt() == null) {
            throw invalid("risk_flag and recorded_at are required");
        }
        if (request.riskFlag() && assessment.trim().length() < 8) {
            throw invalid("a risk-flagged emergency nursing note requires a detailed assessment");
        }
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "NEONATAL_TREATMENT_CREATE", idempotencyKey,
                    sha256(request.patientId() + "|" + request.encounterId() + "|" + request.recordedAt()));
            UUID noteId = UUID.randomUUID();
            jdbc.sql("""
                    insert into neonatal_treatment_record(
                      tenant_id, note_id, patient_id, encounter_id, facility_id,
                      assessment, intervention, risk_flag, recorded_at)
                    values (:tenant, :note, :patient, :encounter, :facility,
                      :assessment, :intervention, :risk_flag, :recorded_at)
                    """).param("tenant", identity.tenantId()).param("note", noteId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId()).param("assessment", assessment)
                    .param("intervention", intervention).param("risk_flag", request.riskFlag())
                    .param("recorded_at", request.recordedAt().atOffset(ZoneOffset.UTC)).update();
            appendEvidence(identity, request.patientId(), noteId, 1,
                    "NEONATAL_TREATMENT_CREATED", "NeonatalTreatmentCreated");
            completeCommand(identity, "NEONATAL_TREATMENT_CREATE", idempotencyKey, noteId);
            return note(identity.tenantId(), noteId, request.patientId());
        });
    }

    List<NeonatalTreatmentWire> listNotes(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select note_id from neonatal_treatment_record
                where tenant_id = :tenant and patient_id = :patient
                order by recorded_at desc, note_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> note(identity.tenantId(), id, patientId)).toList();
    }

    private NeonatalTreatmentWire note(UUID tenantId, UUID noteId, UUID patientId) {
        return jdbc.sql("""
                select note_id, patient_id, encounter_id, facility_id, assessment, intervention,
                  risk_flag, recorded_at, row_version
                from neonatal_treatment_record where tenant_id = :tenant and note_id = :note and patient_id = :patient
                """).param("tenant", tenantId).param("note", noteId).param("patient", patientId)
                .query((rs, row) -> new NeonatalTreatmentWire(
                        rs.getObject("note_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class), rs.getObject("facility_id", UUID.class),
                        rs.getString("assessment"), rs.getString("intervention"),
                        rs.getBoolean("risk_flag"),
                        rs.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("row_version")))
                .optional().orElseThrow(NeonatalTreatmentService::contextDenied);
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
            throw new NeonatalTreatmentException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new NeonatalTreatmentException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID noteId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", noteId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID noteId, long version,
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
                + noteId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'NEONATAL_TREATMENT', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", noteId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'NEONATAL_TREATMENT', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", noteId).param("version", version).param("event_type", eventType).update();
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw invalid(field + " must be at least " + minLength + " characters");
        }
        return value.trim();
    }

    private static NeonatalTreatmentException invalid(String message) {
        return new NeonatalTreatmentException("NEONATAL_TREATMENT_REQUEST_INVALID", 400, message);
    }

    static NeonatalTreatmentException contextDenied() {
        return new NeonatalTreatmentException("CONTEXT_NOT_PERMITTED", 403,
                "The requested emergency nursing note context is not permitted");
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
