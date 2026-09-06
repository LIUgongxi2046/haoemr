package org.haoemr.diagnosis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ClinicalDiagnosisWire;
import org.haoemr.contracts.DiagnosisTerminologyEntryWire;
import org.haoemr.contracts.DiagnosisConfirmRequestWire;
import org.haoemr.contracts.DiagnosisControlRequestWire;
import org.haoemr.contracts.DiagnosisCorrectRequestWire;
import org.haoemr.contracts.DiagnosisCreateRequestWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class DiagnosisService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    DiagnosisService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    ClinicalDiagnosisWire create(
            ClinicalIdentity identity, String idempotencyKey, DiagnosisCreateRequestWire request) {
        validateVersionFields(
                request.terminologySystem(), request.terminologyRelease(), request.code(),
                request.diagnosisText(), request.diagnosisRole(), request.certainty(), request.effectiveAt(),
                request.evidenceSummary(), request.planSummary());
        return transactions.execute(status -> {
            requireEncounter(identity.tenantId(), request.patientId(), request.encounterId(), request.facilityId());
            String display = terminologyDisplay(
                    request.terminologySystem(), request.terminologyRelease(), request.code(), request.effectiveAt());
            String hash = sha256(request.patientId() + "|" + request.encounterId() + "|"
                    + request.terminologySystem() + "|" + request.terminologyRelease() + "|"
                    + request.code() + "|" + request.diagnosisText() + "|" + request.diagnosisRole()
                    + "|" + request.certainty() + "|" + request.effectiveAt());
            beginCommand(identity, "DIAGNOSIS_CREATE", idempotencyKey, hash);
            UUID diagnosisId = UUID.randomUUID();
            UUID versionId = UUID.randomUUID();
            jdbc.sql("""
                    insert into clinical_diagnosis(
                      tenant_id, diagnosis_id, patient_id, encounter_id, facility_id,
                      lifecycle_status, current_diagnosis_role, current_version_id, author_user_id)
                    values (:tenant, :diagnosis_id, :patient, :encounter, :facility,
                      'ACTIVE', :role, :version_id, :author)
                    """).param("tenant", identity.tenantId()).param("diagnosis_id", diagnosisId)
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("facility", request.facilityId()).param("role", request.diagnosisRole().name())
                    .param("version_id", versionId).param("author", identity.userId()).update();
            insertVersion(identity, diagnosisId, versionId, 1, request.terminologySystem(),
                    request.terminologyRelease(), request.code(), display, request.diagnosisText(),
                    request.diagnosisRole().name(), request.certainty().name(), request.evidenceSummary(),
                    request.planSummary(), request.effectiveAt(), "CREATED", null, null);
            appendEvidence(identity, request.patientId(), diagnosisId, 1,
                    "DIAGNOSIS_CREATED", "ClinicalDiagnosisCreated");
            completeCommand(identity, "DIAGNOSIS_CREATE", idempotencyKey, 201, diagnosisId);
            return snapshot(identity.tenantId(), diagnosisId, request.patientId(),
                    request.encounterId(), request.facilityId());
        });
    }

    List<ClinicalDiagnosisWire> list(
            ClinicalIdentity identity, UUID patientId, UUID encounterId, UUID facilityId) {
        requireReadableEncounter(identity.tenantId(), patientId, encounterId, facilityId);
        return jdbc.sql("""
                select diagnosis_id from clinical_diagnosis
                where tenant_id = :tenant and patient_id = :patient
                  and encounter_id = :encounter and facility_id = :facility
                order by case when lifecycle_status = 'ACTIVE' and current_diagnosis_role = 'PRIMARY' then 0 else 1 end,
                  updated_at desc, diagnosis_id
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .param("encounter", encounterId).param("facility", facilityId)
                .query(UUID.class).list().stream()
                .map(id -> snapshot(identity.tenantId(), id, patientId, encounterId, facilityId)).toList();
    }

    ClinicalDiagnosisWire confirm(
            ClinicalIdentity identity, String idempotencyKey, UUID diagnosisId,
            DiagnosisConfirmRequestWire request) {
        if (request.expectedRowVersion() == null) {
            throw new DiagnosisException("DIAGNOSIS_CONFIRM_INVALID", 400, "Expected diagnosis version is required");
        }
        return transactions.execute(status -> {
            LockedDiagnosis locked = lock(identity.tenantId(), diagnosisId, request.patientId(),
                    request.encounterId(), request.facilityId());
            requireExpectedActive(locked, request.expectedRowVersion());
            CurrentVersion current = currentVersion(identity.tenantId(), locked.currentVersionId());
            if ("CONFIRMED".equals(current.certainty())) {
                throw new DiagnosisException("DIAGNOSIS_ALREADY_CONFIRMED", 409, "The diagnosis is already confirmed");
            }
            String hash = sha256(diagnosisId + "|" + request.expectedRowVersion() + "|CONFIRM");
            beginCommand(identity, "DIAGNOSIS_CONFIRM", idempotencyKey, hash);
            UUID versionId = UUID.randomUUID();
            long versionNo = current.versionNo() + 1;
            insertVersion(identity, diagnosisId, versionId, versionNo, current.terminologySystem(),
                    current.terminologyRelease(), current.code(), current.codeDisplaySnapshot(),
                    current.diagnosisText(), current.diagnosisRole(), "CONFIRMED", current.evidenceSummary(),
                    current.planSummary(), current.effectiveAt(), "CONFIRMED", null, locked.currentVersionId());
            long rowVersion = moveCurrent(identity.tenantId(), diagnosisId, request.expectedRowVersion(),
                    versionId, current.diagnosisRole());
            appendEvidence(identity, request.patientId(), diagnosisId, rowVersion,
                    "DIAGNOSIS_CONFIRMED", "ClinicalDiagnosisConfirmed");
            completeCommand(identity, "DIAGNOSIS_CONFIRM", idempotencyKey, 200, diagnosisId);
            return snapshot(identity.tenantId(), diagnosisId, request.patientId(),
                    request.encounterId(), request.facilityId());
        });
    }

    ClinicalDiagnosisWire correct(
            ClinicalIdentity identity, String idempotencyKey, UUID diagnosisId,
            DiagnosisCorrectRequestWire request) {
        validateVersionFields(
                request.terminologySystem(), request.terminologyRelease(), request.code(),
                request.diagnosisText(), request.diagnosisRole(), request.certainty(), request.effectiveAt(),
                request.evidenceSummary(), request.planSummary());
        if (request.expectedRowVersion() == null || blank(request.correctionReason())
                || request.correctionReason().length() > 1000) {
            throw new DiagnosisException(
                    "DIAGNOSIS_CORRECTION_INVALID", 400, "Expected version and correction reason are required");
        }
        return transactions.execute(status -> {
            LockedDiagnosis locked = lock(identity.tenantId(), diagnosisId, request.patientId(),
                    request.encounterId(), request.facilityId());
            requireExpectedActive(locked, request.expectedRowVersion());
            CurrentVersion current = currentVersion(identity.tenantId(), locked.currentVersionId());
            String display = terminologyDisplay(
                    request.terminologySystem(), request.terminologyRelease(), request.code(), request.effectiveAt());
            String hash = sha256(diagnosisId + "|" + request.expectedRowVersion() + "|"
                    + request.terminologySystem() + "|" + request.terminologyRelease() + "|" + request.code()
                    + "|" + request.diagnosisText() + "|" + request.diagnosisRole() + "|"
                    + request.certainty() + "|" + request.correctionReason());
            beginCommand(identity, "DIAGNOSIS_CORRECT", idempotencyKey, hash);
            UUID versionId = UUID.randomUUID();
            insertVersion(identity, diagnosisId, versionId, current.versionNo() + 1,
                    request.terminologySystem(), request.terminologyRelease(), request.code(), display,
                    request.diagnosisText(), request.diagnosisRole().name(), request.certainty().name(),
                    request.evidenceSummary(), request.planSummary(), request.effectiveAt(), "CORRECTED",
                    request.correctionReason().trim(), locked.currentVersionId());
            long rowVersion = moveCurrent(identity.tenantId(), diagnosisId, request.expectedRowVersion(),
                    versionId, request.diagnosisRole().name());
            appendEvidence(identity, request.patientId(), diagnosisId, rowVersion,
                    "DIAGNOSIS_CORRECTED", "ClinicalDiagnosisCorrected");
            completeCommand(identity, "DIAGNOSIS_CORRECT", idempotencyKey, 200, diagnosisId);
            return snapshot(identity.tenantId(), diagnosisId, request.patientId(),
                    request.encounterId(), request.facilityId());
        });
    }

    ClinicalDiagnosisWire stop(
            ClinicalIdentity identity, String idempotencyKey, UUID diagnosisId,
            DiagnosisControlRequestWire request) {
        if (request.expectedRowVersion() == null || blank(request.reason()) || request.reason().length() > 1000) {
            throw new DiagnosisException(
                    "DIAGNOSIS_STOP_INVALID", 400, "Expected version and stop reason are required");
        }
        return transactions.execute(status -> {
            LockedDiagnosis locked = lock(identity.tenantId(), diagnosisId, request.patientId(),
                    request.encounterId(), request.facilityId());
            requireExpectedActive(locked, request.expectedRowVersion());
            String hash = sha256(diagnosisId + "|" + request.expectedRowVersion() + "|" + request.reason().trim());
            beginCommand(identity, "DIAGNOSIS_STOP", idempotencyKey, hash);
            long rowVersion = jdbc.sql("""
                    update clinical_diagnosis set lifecycle_status = 'STOPPED',
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and diagnosis_id = :diagnosis_id
                      and lifecycle_status = 'ACTIVE' and row_version = :expected
                    returning row_version
                    """).param("tenant", identity.tenantId()).param("diagnosis_id", diagnosisId)
                    .param("expected", request.expectedRowVersion()).query(Long.class).optional()
                    .orElseThrow(() -> versionConflict());
            jdbc.sql("""
                    insert into diagnosis_control_event(
                      tenant_id, diagnosis_control_event_id, diagnosis_id, previous_status,
                      resulting_status, reason, actor_user_id)
                    values (:tenant, :event_id, :diagnosis_id, 'ACTIVE', 'STOPPED', :reason, :actor)
                    """).param("tenant", identity.tenantId()).param("event_id", UUID.randomUUID())
                    .param("diagnosis_id", diagnosisId).param("reason", request.reason().trim())
                    .param("actor", identity.userId()).update();
            appendEvidence(identity, request.patientId(), diagnosisId, rowVersion,
                    "DIAGNOSIS_STOPPED", "ClinicalDiagnosisStopped");
            completeCommand(identity, "DIAGNOSIS_STOP", idempotencyKey, 200, diagnosisId);
            return snapshot(identity.tenantId(), diagnosisId, request.patientId(),
                    request.encounterId(), request.facilityId());
        });
    }

    private void validateVersionFields(
            String system, String release, String code, String diagnosisText,
            Object role, Object certainty, Instant effectiveAt, String evidence, String plan) {
        if (blank(system) || system.length() > 64 || blank(release) || release.length() > 64
                || blank(code) || code.length() > 128 || blank(diagnosisText) || diagnosisText.length() > 1000
                || role == null || certainty == null || effectiveAt == null
                || (evidence != null && evidence.length() > 2000) || (plan != null && plan.length() > 2000)) {
            throw new DiagnosisException(
                    "DIAGNOSIS_REQUEST_INVALID", 400, "Diagnosis terminology, text, role, certainty and time are required");
        }
    }

    private String terminologyDisplay(String system, String release, String code, Instant effectiveAt) {
        return jdbc.sql("""
                select display_name from diagnosis_terminology_entry
                where terminology_system = :system and terminology_release = :release and code = :code
                  and effective_from <= :effective_date
                  and (effective_to is null or effective_to >= :effective_date)
                """).param("system", system.trim()).param("release", release.trim()).param("code", code.trim())
                .param("effective_date", effectiveAt.atZone(ZoneOffset.UTC).toLocalDate())
                .query(String.class).optional().orElseThrow(() -> new DiagnosisException(
                        "DIAGNOSIS_TERMINOLOGY_INVALID", 409,
                        "The diagnosis code was not valid in the requested terminology release at that time"));
    }

    private void insertVersion(
            ClinicalIdentity identity, UUID diagnosisId, UUID versionId, long versionNo,
            String system, String release, String code, String display, String diagnosisText,
            String role, String certainty, String evidence, String plan, Instant effectiveAt,
            String changeType, String correctionReason, UUID supersedes) {
        jdbc.sql("""
                insert into clinical_diagnosis_version(
                  tenant_id, diagnosis_version_id, diagnosis_id, version_no,
                  terminology_system, terminology_release, code, code_display_snapshot,
                  diagnosis_text, diagnosis_role, certainty, evidence_summary, plan_summary,
                  effective_at, change_type, correction_reason, supersedes_version_id, authored_by)
                values (:tenant, :version_id, :diagnosis_id, :version_no,
                  :system, :release, :code, :display, :diagnosis_text, :role, :certainty,
                  :evidence, :plan, :effective_at, :change_type, :reason, :supersedes, :author)
                """).param("tenant", identity.tenantId()).param("version_id", versionId)
                .param("diagnosis_id", diagnosisId).param("version_no", versionNo)
                .param("system", system.trim()).param("release", release.trim()).param("code", code.trim())
                .param("display", display).param("diagnosis_text", diagnosisText.trim())
                .param("role", role).param("certainty", certainty).param("evidence", blankToNull(evidence))
                .param("plan", blankToNull(plan)).param("effective_at", OffsetDateTime.ofInstant(effectiveAt, ZoneOffset.UTC))
                .param("change_type", changeType).param("reason", correctionReason)
                .param("supersedes", supersedes).param("author", identity.userId()).update();
    }

    private long moveCurrent(UUID tenantId, UUID diagnosisId, long expected, UUID versionId, String role) {
        return jdbc.sql("""
                update clinical_diagnosis set current_version_id = :version_id, current_diagnosis_role = :role,
                  row_version = row_version + 1, updated_at = now()
                where tenant_id = :tenant and diagnosis_id = :diagnosis_id
                  and lifecycle_status = 'ACTIVE' and row_version = :expected
                returning row_version
                """).param("version_id", versionId).param("role", role).param("tenant", tenantId)
                .param("diagnosis_id", diagnosisId).param("expected", expected)
                .query(Long.class).optional().orElseThrow(() -> versionConflict());
    }

    private void requireEncounter(UUID tenantId, UUID patientId, UUID encounterId, UUID facilityId) {
        long count = jdbc.sql("""
                select count(*) from encounter where tenant_id = :tenant and encounter_id = :encounter
                  and patient_id = :patient and facility_id = :facility and status = 'IN_PROGRESS'
                """).param("tenant", tenantId).param("encounter", encounterId)
                .param("patient", patientId).param("facility", facilityId).query(Long.class).single();
        if (count != 1) throw contextDenied();
    }

    private void requireReadableEncounter(UUID tenantId, UUID patientId, UUID encounterId, UUID facilityId) {
        long count = jdbc.sql("""
                select count(*) from encounter where tenant_id = :tenant and encounter_id = :encounter
                  and patient_id = :patient and facility_id = :facility
                  and status in ('PLANNED', 'ARRIVED', 'IN_PROGRESS', 'SUSPENDED', 'FINISHED')
                """).param("tenant", tenantId).param("encounter", encounterId)
                .param("patient", patientId).param("facility", facilityId).query(Long.class).single();
        if (count != 1) throw contextDenied();
    }

    private LockedDiagnosis lock(
            UUID tenantId, UUID diagnosisId, UUID patientId, UUID encounterId, UUID facilityId) {
        return jdbc.sql("""
                select lifecycle_status, current_diagnosis_role, current_version_id, row_version
                from clinical_diagnosis where tenant_id = :tenant and diagnosis_id = :diagnosis_id
                  and patient_id = :patient and encounter_id = :encounter and facility_id = :facility
                for update
                """).param("tenant", tenantId).param("diagnosis_id", diagnosisId).param("patient", patientId)
                .param("encounter", encounterId).param("facility", facilityId)
                .query((rs, row) -> new LockedDiagnosis(
                        rs.getString("lifecycle_status"), rs.getString("current_diagnosis_role"),
                        rs.getObject("current_version_id", UUID.class), rs.getLong("row_version")))
                .optional().orElseThrow(DiagnosisService::contextDenied);
    }

    private CurrentVersion currentVersion(UUID tenantId, UUID versionId) {
        return jdbc.sql("""
                select version_no, terminology_system, terminology_release, code, code_display_snapshot,
                  diagnosis_text, diagnosis_role, certainty, evidence_summary, plan_summary, effective_at
                from clinical_diagnosis_version where tenant_id = :tenant and diagnosis_version_id = :version_id
                """).param("tenant", tenantId).param("version_id", versionId)
                .query((rs, row) -> new CurrentVersion(
                        rs.getLong("version_no"), rs.getString("terminology_system"),
                        rs.getString("terminology_release"), rs.getString("code"),
                        rs.getString("code_display_snapshot"), rs.getString("diagnosis_text"),
                        rs.getString("diagnosis_role"), rs.getString("certainty"),
                        rs.getString("evidence_summary"), rs.getString("plan_summary"),
                        rs.getObject("effective_at", OffsetDateTime.class).toInstant())).single();
    }

    private ClinicalDiagnosisWire snapshot(
            UUID tenantId, UUID diagnosisId, UUID patientId, UUID encounterId, UUID facilityId) {
        return jdbc.sql("""
                select diagnosis.patient_id, diagnosis.encounter_id, diagnosis.lifecycle_status,
                  diagnosis.row_version, version.version_no, version.terminology_system,
                  version.terminology_release, version.code, version.code_display_snapshot,
                  version.diagnosis_text, version.diagnosis_role, version.certainty,
                  version.evidence_summary, version.plan_summary, version.effective_at
                from clinical_diagnosis diagnosis
                join clinical_diagnosis_version version on version.tenant_id = diagnosis.tenant_id
                  and version.diagnosis_version_id = diagnosis.current_version_id
                where diagnosis.tenant_id = :tenant and diagnosis.diagnosis_id = :diagnosis_id
                  and diagnosis.patient_id = :patient and diagnosis.encounter_id = :encounter
                  and diagnosis.facility_id = :facility
                """).param("tenant", tenantId).param("diagnosis_id", diagnosisId).param("patient", patientId)
                .param("encounter", encounterId).param("facility", facilityId)
                .query((rs, row) -> {
                    long rowVersion = rs.getLong("row_version");
                    long versionNo = rs.getLong("version_no");
                    String lifecycle = rs.getString("lifecycle_status");
                    String certainty = rs.getString("certainty");
                    String status = "STOPPED".equals(lifecycle) ? "STOPPED" : certainty;
                    String watermark = sha256(diagnosisId + "|" + status + "|" + rowVersion + "|"
                            + versionNo + "|" + rs.getString("terminology_release") + "|" + rs.getString("code"));
                    return new ClinicalDiagnosisWire(
                            diagnosisId, rs.getObject("patient_id", UUID.class),
                            rs.getObject("encounter_id", UUID.class),
                            ClinicalDiagnosisWire.StatusValue.valueOf(status),
                            ClinicalDiagnosisWire.DiagnosisRoleValue.valueOf(rs.getString("diagnosis_role")),
                            rs.getString("terminology_system"), rs.getString("terminology_release"),
                            rs.getString("code"), rs.getString("code_display_snapshot"),
                            rs.getString("diagnosis_text"), rs.getString("evidence_summary"),
                            rs.getString("plan_summary"),
                            rs.getObject("effective_at", OffsetDateTime.class).toInstant(),
                            versionNo, rowVersion, watermark);
                }).optional().orElseThrow(DiagnosisService::contextDenied);
    }

    private void requireExpectedActive(LockedDiagnosis locked, long expected) {
        if (locked.rowVersion() != expected) throw versionConflict();
        if (!"ACTIVE".equals(locked.lifecycleStatus())) {
            throw new DiagnosisException("DIAGNOSIS_STATE_INVALID", 409, "Only an active diagnosis can change");
        }
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (blank(key) || key.length() > 128) {
            throw new DiagnosisException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new DiagnosisException("IDEMPOTENCY_REPLAY", 409, "This diagnosis command key was already used");
        }
    }

    private void completeCommand(
            ClinicalIdentity identity, String scope, String key, int responseStatus, UUID resourceId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = :status,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("status", responseStatus).param("resource", resourceId)
                .param("tenant", identity.tenantId()).param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID diagnosisId,
            long aggregateVersion, String actionCode, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + actionCode + "|"
                + diagnosisId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'CLINICAL_DIAGNOSIS', :diagnosis_id,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", actionCode).param("diagnosis_id", diagnosisId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId)).param("trace", trace)
                .param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event_id, 'CLINICAL_DIAGNOSIS', :diagnosis_id, :aggregate_version,
                  :event_type, 1, jsonb_build_object('diagnosis_id', :diagnosis_id))
                """).param("tenant", identity.tenantId()).param("event_id", UUID.randomUUID())
                .param("diagnosis_id", diagnosisId).param("aggregate_version", aggregateVersion)
                .param("event_type", eventType).update();
    }

    List<DiagnosisTerminologyEntryWire> searchTerminology(String query, int requestedLimit) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.length() > 100) {
            throw new DiagnosisException("DIAGNOSIS_TERMINOLOGY_QUERY_INVALID", 400,
                    "Diagnosis terminology query must not exceed 100 characters");
        }
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        return jdbc.sql("""
                select terminology_system, terminology_release, code, display_name
                from diagnosis_terminology_entry
                where lifecycle_status='ACTIVE' and effective_from<=current_date
                  and (effective_to is null or effective_to>=current_date)
                  and terminology_release not like 'SYNTHETIC%'
                  and (:query='' or code ilike :pattern or display_name ilike :pattern)
                order by case when code=:query then 0 when code ilike :prefix then 1 else 2 end,
                  terminology_release desc, code
                limit :limit
                """).param("query", normalized).param("pattern", "%" + normalized + "%")
                .param("prefix", normalized + "%").param("limit", limit)
                .query((rs, row) -> new DiagnosisTerminologyEntryWire(
                        rs.getString("terminology_system"), rs.getString("terminology_release"),
                        rs.getString("code"), rs.getString("display_name"))).list();
    }

    private static DiagnosisException versionConflict() {
        return new DiagnosisException("DIAGNOSIS_VERSION_CONFLICT", 409, "The diagnosis changed; reload before retrying");
    }

    static DiagnosisException contextDenied() {
        return new DiagnosisException("CONTEXT_NOT_PERMITTED", 403, "The requested diagnosis context is not permitted");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return blank(value) ? null : value.trim();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record LockedDiagnosis(
            String lifecycleStatus, String currentRole, UUID currentVersionId, long rowVersion) {}
    private record CurrentVersion(
            long versionNo, String terminologySystem, String terminologyRelease, String code,
            String codeDisplaySnapshot, String diagnosisText, String diagnosisRole, String certainty,
            String evidenceSummary, String planSummary, Instant effectiveAt) {}
}
