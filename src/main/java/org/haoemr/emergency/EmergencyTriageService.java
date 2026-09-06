package org.haoemr.emergency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.EmergencyTriageAssessmentCreateRequestWire;
import org.haoemr.contracts.EmergencyTriageAssessmentWire;
import org.haoemr.contracts.EmergencyClinicalFactVoidRequestWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class EmergencyTriageService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    EmergencyTriageService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    EmergencyTriageAssessmentWire createAssessment(
            ClinicalIdentity identity, String idempotencyKey,
            EmergencyTriageAssessmentCreateRequestWire request) {
        String complaint = requireText(request.chiefComplaint(), 2, "chief_complaint");
        if (request.triageLevel() == null || request.triagedAt() == null
                || request.immediateActionRequired() == null) {
            throw invalid("triage_level, triaged_at and immediate_action_required are required");
        }
        if (request.triageLevel() == EmergencyTriageAssessmentCreateRequestWire.TriageLevelValue.LEVEL_1
                && !request.immediateActionRequired()) {
            throw invalid("LEVEL_1 triage requires immediate_action_required to be true");
        }
        requireActiveEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "EMERGENCY_TRIAGE_CREATE", idempotencyKey,
                    sha256(request.patientId() + "|" + request.encounterId() + "|" + request.triageLevel()
                            + "|" + request.triagedAt()));
            jdbc.sql("""
                    update emergency_triage_assessment
                    set status = 'SUPERSEDED', row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and patient_id = :patient and encounter_id = :encounter
                      and status = 'ACTIVE' and voided_at is null
                    """).param("tenant", identity.tenantId()).param("patient", request.patientId())
                    .param("encounter", request.encounterId()).update();
            UUID assessmentId = UUID.randomUUID();
            jdbc.sql("""
                    insert into emergency_triage_assessment(
                      tenant_id, triage_assessment_id, patient_id, encounter_id, facility_id,
                      triage_level, chief_complaint, triaged_at, immediate_action_required, status)
                    values (:tenant, :assessment, :patient, :encounter, :facility,
                      :level, :complaint, :triaged_at, :immediate, 'ACTIVE')
                    """).param("tenant", identity.tenantId()).param("assessment", assessmentId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId()).param("level", request.triageLevel().name())
                    .param("complaint", complaint)
                    .param("triaged_at", request.triagedAt().atOffset(ZoneOffset.UTC))
                    .param("immediate", request.immediateActionRequired()).update();
            appendEvidence(identity, request.patientId(), assessmentId, 1,
                    "EMERGENCY_TRIAGE_CREATED", "EmergencyTriageCreated");
            completeCommand(identity, "EMERGENCY_TRIAGE_CREATE", idempotencyKey, assessmentId);
            return assessment(identity.tenantId(), assessmentId, request.patientId());
        });
    }

    EmergencyTriageAssessmentWire voidAssessment(
            ClinicalIdentity identity, String idempotencyKey, UUID assessmentId,
            EmergencyClinicalFactVoidRequestWire request) {
        String reason = requireText(request.reason(), 4, "reason");
        return transactions.execute(status -> {
            beginCommand(identity, "EMERGENCY_TRIAGE_VOID", idempotencyKey,
                    sha256(assessmentId + "|" + request.expectedRowVersion() + "|" + reason));
            TriageHead current = jdbc.sql("""
                    select row_version, voided_at from emergency_triage_assessment
                    where tenant_id = :tenant and triage_assessment_id = :assessment
                      and patient_id = :patient and encounter_id = :encounter and facility_id = :facility
                    for update
                    """).param("tenant", identity.tenantId()).param("assessment", assessmentId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId())
                    .query((rs, row) -> new TriageHead(rs.getLong("row_version"),
                            rs.getObject("voided_at", OffsetDateTime.class)))
                    .optional().orElseThrow(EmergencyTriageService::contextDenied);
            if (request.expectedRowVersion() == null || current.rowVersion() != request.expectedRowVersion()) {
                throw new EmergencyTriageException("EMERGENCY_TRIAGE_VERSION_CONFLICT", 409,
                        "The triage assessment changed; reload before retrying");
            }
            if (current.voidedAt() != null) {
                throw new EmergencyTriageException("EMERGENCY_TRIAGE_STATE_INVALID", 409,
                        "The triage assessment is already voided");
            }
            jdbc.sql("""
                    update emergency_triage_assessment
                    set status = 'SUPERSEDED', voided_at = now(), void_reason = :reason,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and triage_assessment_id = :assessment and row_version = :expected
                    """).param("reason", reason).param("tenant", identity.tenantId())
                    .param("assessment", assessmentId).param("expected", current.rowVersion()).update();
            appendEvidence(identity, request.patientId(), assessmentId, current.rowVersion() + 1,
                    "EMERGENCY_TRIAGE_VOIDED", "EmergencyTriageVoided");
            completeCommand(identity, "EMERGENCY_TRIAGE_VOID", idempotencyKey, assessmentId);
            return assessment(identity.tenantId(), assessmentId, request.patientId());
        });
    }

    List<EmergencyTriageAssessmentWire> listAssessments(ClinicalIdentity identity, UUID patientId) {
        return jdbc.sql("""
                select triage_assessment_id from emergency_triage_assessment
                where tenant_id = :tenant and patient_id = :patient
                order by triaged_at desc, triage_assessment_id desc limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> assessment(identity.tenantId(), id, patientId)).toList();
    }

    private EmergencyTriageAssessmentWire assessment(UUID tenantId, UUID assessmentId, UUID patientId) {
        return jdbc.sql("""
                select triage_assessment_id, patient_id, encounter_id, facility_id, triage_level,
                  chief_complaint, triaged_at, immediate_action_required, status,
                  voided_at, void_reason, row_version
                from emergency_triage_assessment
                where tenant_id = :tenant and triage_assessment_id = :assessment and patient_id = :patient
                """).param("tenant", tenantId).param("assessment", assessmentId).param("patient", patientId)
                .query((rs, row) -> new EmergencyTriageAssessmentWire(
                        rs.getObject("triage_assessment_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class), rs.getObject("facility_id", UUID.class),
                        EmergencyTriageAssessmentWire.TriageLevelValue.valueOf(rs.getString("triage_level")),
                        rs.getString("chief_complaint"),
                        rs.getObject("triaged_at", OffsetDateTime.class).toInstant(),
                        rs.getBoolean("immediate_action_required"),
                        EmergencyTriageAssessmentWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getObject("voided_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("voided_at", OffsetDateTime.class).toInstant(),
                        rs.getString("void_reason"),
                        rs.getLong("row_version")))
                .optional().orElseThrow(EmergencyTriageService::contextDenied);
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
            throw new EmergencyTriageException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new EmergencyTriageException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID assessmentId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", assessmentId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID assessmentId, long version,
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
                + assessmentId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'EMERGENCY_TRIAGE', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", assessmentId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'EMERGENCY_TRIAGE', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", assessmentId).param("version", version).param("event_type", eventType).update();
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw invalid(field + " must be at least " + minLength + " characters");
        }
        return value.trim();
    }

    private static EmergencyTriageException invalid(String message) {
        return new EmergencyTriageException("EMERGENCY_TRIAGE_REQUEST_INVALID", 400, message);
    }

    static EmergencyTriageException contextDenied() {
        return new EmergencyTriageException("CONTEXT_NOT_PERMITTED", 403,
                "The requested emergency triage context is not permitted");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record TriageHead(long rowVersion, OffsetDateTime voidedAt) {}
}
