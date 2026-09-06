package org.haoemr.patient;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
final class PatientIdentityService {
    private static final List<String> MPI_ROLES = List.of(
            "SYSTEM_ADMIN", "CLINICAL_ADMIN", "HEALTH_INFORMATION_MANAGER", "REGISTRAR");
    private static final Set<String> CANDIDATE_STATUSES = Set.of("OPEN", "DISMISSED", "MERGE_REQUESTED", "MERGED");
    private static final Set<String> EDITABLE_PATIENT_STATUSES = Set.of(
            "PENDING_VERIFICATION", "ACTIVE", "POSSIBLE_DUPLICATE");

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    PatientIdentityService(JdbcClient jdbc, TransactionTemplate transactions, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    MatchCandidateWire detect(ClinicalIdentity identity, String key, CandidateCreateRequest request) {
        requireAdministrator(identity);
        if (request == null || request.patientAId() == null || request.patientBId() == null
                || request.patientAId().equals(request.patientBId())) {
            throw invalid("Two different patient identities are required");
        }
        UUID patientA = lesser(request.patientAId(), request.patientBId());
        UUID patientB = greater(request.patientAId(), request.patientBId());
        return transactions.execute(ignored -> {
            begin(identity, "PATIENT_MATCH_DETECT", key, sha256(patientA + "|" + patientB));
            PatientIdentitySnapshot first = patient(identity.tenantId(), patientA, true);
            PatientIdentitySnapshot second = patient(identity.tenantId(), patientB, true);
            requireMatchable(first);
            requireMatchable(second);
            MatchScore match = score(identity.tenantId(), first, second);
            if (match.score() < 0.50) {
                throw invalid("The selected identities do not meet the configurable candidate threshold");
            }
            UUID candidateId = UUID.randomUUID();
            try {
                jdbc.sql("""
                        insert into patient_match_candidate(
                          tenant_id, candidate_id, patient_a_id, patient_b_id, match_score,
                          match_signals, status)
                        values (:tenant, :candidate, :a, :b, :score, cast(:signals as jsonb), 'OPEN')
                        on conflict (tenant_id, patient_a_id, patient_b_id) do update
                          set match_score = excluded.match_score, match_signals = excluded.match_signals,
                              detected_at = now(), row_version = patient_match_candidate.row_version + 1
                          where patient_match_candidate.status = 'OPEN'
                        """).param("tenant", identity.tenantId()).param("candidate", candidateId)
                        .param("a", patientA).param("b", patientB).param("score", match.score())
                        .param("signals", json(match.signals())).update();
            } catch (DataIntegrityViolationException conflict) {
                throw conflict("The match candidate conflicts with current patient identity state");
            }
            MatchCandidateWire result = findCandidate(identity.tenantId(), patientA, patientB);
            evidence(identity, "PATIENT_MATCH_CANDIDATE_DETECTED", "PATIENT_MATCH_CANDIDATE",
                    result.candidateId(), result.rowVersion(), patientA);
            complete(identity, "PATIENT_MATCH_DETECT", key, result.candidateId());
            return result;
        });
    }

    List<MatchCandidateWire> candidates(ClinicalIdentity identity, String status) {
        requireAdministrator(identity);
        String normalized = status == null ? "OPEN" : status.trim().toUpperCase();
        if (!CANDIDATE_STATUSES.contains(normalized)) throw invalid("Candidate status is not valid");
        return jdbc.sql("""
                select candidate.candidate_id, candidate.patient_a_id, a.display_name as patient_a_name,
                  candidate.patient_b_id, b.display_name as patient_b_name, candidate.match_score,
                  candidate.match_signals::text, candidate.status, candidate.detected_at,
                  candidate.resolved_at, candidate.resolved_by, candidate.resolution_reason,
                  candidate.row_version
                from patient_match_candidate candidate
                join patient a on a.tenant_id = candidate.tenant_id and a.patient_id = candidate.patient_a_id
                join patient b on b.tenant_id = candidate.tenant_id and b.patient_id = candidate.patient_b_id
                where candidate.tenant_id = :tenant and candidate.status = :status
                order by candidate.match_score desc, candidate.detected_at, candidate.candidate_id
                """).param("tenant", identity.tenantId()).param("status", normalized)
                .query((rs, row) -> candidate(rs)).list();
    }

    DemographicVersionWire correct(ClinicalIdentity identity, String key, UUID patientId,
            DemographicCorrectionRequest request) {
        requireAdministrator(identity);
        validateCorrection(request);
        return transactions.execute(ignored -> {
            begin(identity, "PATIENT_IDENTITY_CORRECT", key, sha256(patientId + "|" + request));
            PatientIdentitySnapshot current = patient(identity.tenantId(), patientId, true);
            if (!EDITABLE_PATIENT_STATUSES.contains(current.status())) {
                throw conflict("Merged, deceased or void patient identities cannot be corrected");
            }
            if (current.rowVersion() != request.expectedRowVersion()) {
                throw conflict("The patient identity changed; refresh before correcting it");
            }
            String resultingStatus = request.status() == null
                    ? current.status() : request.status().trim().toUpperCase();
            if (!EDITABLE_PATIENT_STATUSES.contains(resultingStatus)) {
                throw invalid("Identity correction can only set a pending, active or possible-duplicate status");
            }
            int version = jdbc.sql("""
                    select coalesce(max(version_no), 0) + 1 from patient_demographic_version
                    where tenant_id = :tenant and patient_id = :patient
                    """).param("tenant", identity.tenantId()).param("patient", patientId)
                    .query(Integer.class).single();
            UUID previousVersion = jdbc.sql("""
                    select demographic_version_id from patient_demographic_version
                    where tenant_id = :tenant and patient_id = :patient order by version_no desc limit 1
                    """).param("tenant", identity.tenantId()).param("patient", patientId)
                    .query(UUID.class).optional().orElse(null);
            int updated = jdbc.sql("""
                    update patient set display_name = :name, sex_code = :sex, birth_date = :birth,
                      status = :status, row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and patient_id = :patient and row_version = :expected
                    """).param("name", request.displayName().trim()).param("sex", request.sexCode().trim())
                    .param("birth", request.birthDate()).param("status", resultingStatus)
                    .param("tenant", identity.tenantId()).param("patient", patientId)
                    .param("expected", request.expectedRowVersion()).update();
            if (updated != 1) throw conflict("The patient identity changed; refresh before correcting it");
            UUID demographicVersionId = UUID.randomUUID();
            jdbc.sql("""
                    insert into patient_demographic_version(
                      tenant_id, patient_id, demographic_version_id, version_no, display_name,
                      sex_code, birth_date, patient_status, change_type, change_reason, changed_by,
                      supersedes_version_id)
                    values (:tenant, :patient, :version_id, :version, :name, :sex, :birth,
                      :status, 'IDENTITY_CORRECTION', :reason, :actor, :previous)
                    """).param("tenant", identity.tenantId()).param("patient", patientId)
                    .param("version_id", demographicVersionId).param("version", version)
                    .param("name", request.displayName().trim()).param("sex", request.sexCode().trim())
                    .param("birth", request.birthDate()).param("status", resultingStatus)
                    .param("reason", request.reason().trim()).param("actor", identity.userId())
                    .param("previous", previousVersion).update();
            evidence(identity, "PATIENT_IDENTITY_CORRECTED", "PATIENT", patientId,
                    request.expectedRowVersion() + 1, patientId);
            outbox(identity.tenantId(), "PATIENT", patientId, request.expectedRowVersion() + 1,
                    "PatientIdentityCorrected");
            complete(identity, "PATIENT_IDENTITY_CORRECT", key, demographicVersionId);
            return findVersion(identity.tenantId(), patientId, demographicVersionId);
        });
    }

    List<DemographicVersionWire> history(ClinicalIdentity identity, UUID patientId) {
        requireAdministrator(identity);
        patient(identity.tenantId(), patientId, false);
        return jdbc.sql("""
                select version.demographic_version_id, version.patient_id, version.version_no,
                  version.display_name, version.sex_code, version.birth_date, version.patient_status,
                  version.change_type, version.change_reason, version.changed_by,
                  version.supersedes_version_id, version.created_at, patient.row_version as patient_row_version
                from patient_demographic_version version
                join patient on patient.tenant_id = version.tenant_id and patient.patient_id = version.patient_id
                where version.tenant_id = :tenant and version.patient_id = :patient
                order by version.version_no desc
                """).param("tenant", identity.tenantId()).param("patient", patientId)
                .query((rs, row) -> demographicVersion(rs)).list();
    }

    MergeCaseWire requestMerge(ClinicalIdentity identity, String key, MergeCaseCreateRequest request) {
        requireAdministrator(identity);
        validateMergeRequest(request);
        return transactions.execute(ignored -> {
            begin(identity, "PATIENT_MERGE_REQUEST", key, sha256(request.toString()));
            PatientIdentitySnapshot source = patient(identity.tenantId(), request.sourcePatientId(), true);
            PatientIdentitySnapshot target = patient(identity.tenantId(), request.targetPatientId(), true);
            requireMatchable(source);
            requireMatchable(target);
            if (request.candidateId() != null) {
                MatchCandidateWire candidate = findCandidate(identity.tenantId(), request.candidateId());
                if (!Set.of(candidate.patientAId(), candidate.patientBId())
                        .equals(Set.of(source.patientId(), target.patientId()))
                        || !"OPEN".equals(candidate.status())) {
                    throw conflict("The selected match candidate is not open for these identities");
                }
            }
            UUID caseId = UUID.randomUUID();
            try {
                jdbc.sql("""
                        insert into patient_merge_case(
                          tenant_id, merge_case_id, candidate_id, source_patient_id, target_patient_id,
                          source_status_before_merge, status, merge_reason, conflict_resolution, requested_by)
                        values (:tenant, :case, :candidate, :source, :target, :source_status,
                          'PENDING_SECOND_REVIEW', :reason, cast(:resolution as jsonb), :actor)
                        """).param("tenant", identity.tenantId()).param("case", caseId)
                        .param("candidate", request.candidateId()).param("source", source.patientId())
                        .param("target", target.patientId()).param("source_status", source.status())
                        .param("reason", request.reason().trim())
                        .param("resolution", json(request.conflictResolution()))
                        .param("actor", identity.userId()).update();
                if (request.candidateId() != null) {
                    jdbc.sql("""
                            update patient_match_candidate set status = 'MERGE_REQUESTED',
                              row_version = row_version + 1
                            where tenant_id = :tenant and candidate_id = :candidate and status = 'OPEN'
                            """).param("tenant", identity.tenantId())
                            .param("candidate", request.candidateId()).update();
                }
            } catch (DataIntegrityViolationException conflict) {
                throw conflict("A merge case is already active for the source identity or conflicts with current data");
            }
            evidence(identity, "PATIENT_MERGE_REQUESTED", "PATIENT_MERGE_CASE", caseId, 1, source.patientId());
            outbox(identity.tenantId(), "PATIENT_MERGE_CASE", caseId, 1, "PatientMergeRequested");
            complete(identity, "PATIENT_MERGE_REQUEST", key, caseId);
            return findCase(identity.tenantId(), caseId);
        });
    }

    List<MergeCaseWire> mergeCases(ClinicalIdentity identity, String status) {
        requireAdministrator(identity);
        String predicate = "";
        JdbcClient.StatementSpec query;
        if (status == null || status.isBlank()) {
            query = jdbc.sql(mergeCaseSelect() + " order by merge_case.requested_at desc, merge_case.merge_case_id")
                    .param("tenant", identity.tenantId());
        } else {
            String normalized = status.trim().toUpperCase();
            if (!Set.of("PENDING_SECOND_REVIEW", "MERGED", "REVERSAL_PENDING", "REVERSED", "REJECTED")
                    .contains(normalized)) throw invalid("Merge case status is not valid");
            predicate = " and merge_case.status = :status";
            query = jdbc.sql(mergeCaseSelect() + predicate
                    + " order by merge_case.requested_at desc, merge_case.merge_case_id")
                    .param("tenant", identity.tenantId()).param("status", normalized);
        }
        return query.query((rs, row) -> mergeCase(rs)).list();
    }

    MergeCaseWire approveMerge(ClinicalIdentity identity, String key, UUID caseId, MergeApprovalRequest request) {
        requireAdministrator(identity);
        if (request == null || request.expectedRowVersion() < 1 || !request.confirmNoClinicalDataLoss()) {
            throw invalid("Expected version and explicit no-data-loss confirmation are required");
        }
        return transactions.execute(ignored -> {
            begin(identity, "PATIENT_MERGE_APPROVE", key, sha256(caseId + "|" + request));
            MergeCaseWire mergeCase = lockCase(identity.tenantId(), caseId);
            if (!"PENDING_SECOND_REVIEW".equals(mergeCase.status())
                    || mergeCase.rowVersion() != request.expectedRowVersion()) {
                throw conflict("The merge case changed or is not awaiting second review");
            }
            if (mergeCase.requestedBy().equals(identity.userId())) {
                throw conflict("The merge requester cannot perform the independent approval");
            }
            PatientIdentitySnapshot source = patient(identity.tenantId(), mergeCase.sourcePatientId(), true);
            PatientIdentitySnapshot target = patient(identity.tenantId(), mergeCase.targetPatientId(), true);
            if (!source.status().equals(mergeCase.sourceStatusBeforeMerge())
                    || !EDITABLE_PATIENT_STATUSES.contains(source.status())
                    || !EDITABLE_PATIENT_STATUSES.contains(target.status())) {
                throw conflict("A patient identity changed after the merge request");
            }
            int updatedPatient = jdbc.sql("""
                    update patient set status = 'MERGED', merged_into_patient_id = :target,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and patient_id = :source and status = :source_status
                    """).param("target", target.patientId()).param("tenant", identity.tenantId())
                    .param("source", source.patientId()).param("source_status", source.status()).update();
            if (updatedPatient != 1) throw conflict("The source identity changed during approval");
            int updatedCase = jdbc.sql("""
                    update patient_merge_case set status = 'MERGED', approved_by = :actor,
                      approved_at = now(), row_version = row_version + 1
                    where tenant_id = :tenant and merge_case_id = :case
                      and status = 'PENDING_SECOND_REVIEW' and row_version = :expected
                      and requested_by <> :actor
                    """).param("actor", identity.userId()).param("tenant", identity.tenantId())
                    .param("case", caseId).param("expected", request.expectedRowVersion()).update();
            if (updatedCase != 1) throw conflict("The merge case changed during approval");
            if (mergeCase.candidateId() != null) {
                jdbc.sql("""
                        update patient_match_candidate set status = 'MERGED', resolved_at = now(),
                          resolved_by = :actor, resolution_reason = :reason, row_version = row_version + 1
                        where tenant_id = :tenant and candidate_id = :candidate and status = 'MERGE_REQUESTED'
                        """).param("actor", identity.userId()).param("reason", mergeCase.mergeReason())
                        .param("tenant", identity.tenantId()).param("candidate", mergeCase.candidateId()).update();
            }
            evidence(identity, "PATIENT_MERGE_APPROVED", "PATIENT_MERGE_CASE", caseId,
                    request.expectedRowVersion() + 1, source.patientId());
            outbox(identity.tenantId(), "PATIENT", source.patientId(), source.rowVersion() + 1, "PatientMerged");
            outbox(identity.tenantId(), "PATIENT_MERGE_CASE", caseId,
                    request.expectedRowVersion() + 1, "PatientMergeApproved");
            complete(identity, "PATIENT_MERGE_APPROVE", key, caseId);
            return findCase(identity.tenantId(), caseId);
        });
    }

    MergeCaseWire requestReversal(ClinicalIdentity identity, String key, UUID caseId, ReversalRequest request) {
        requireAdministrator(identity);
        if (request == null || request.expectedRowVersion() < 1 || request.reason() == null
                || request.reason().trim().length() < 8) {
            throw invalid("Expected version and a detailed reversal reason are required");
        }
        return transactions.execute(ignored -> {
            begin(identity, "PATIENT_MERGE_REVERSAL_REQUEST", key, sha256(caseId + "|" + request));
            int updated = jdbc.sql("""
                    update patient_merge_case set status = 'REVERSAL_PENDING', reversal_reason = :reason,
                      reversal_requested_by = :actor, reversal_requested_at = now(), row_version = row_version + 1
                    where tenant_id = :tenant and merge_case_id = :case and status = 'MERGED'
                      and row_version = :expected
                    """).param("reason", request.reason().trim()).param("actor", identity.userId())
                    .param("tenant", identity.tenantId()).param("case", caseId)
                    .param("expected", request.expectedRowVersion()).update();
            if (updated != 1) throw conflict("The merged case changed or cannot be reversed");
            evidence(identity, "PATIENT_MERGE_REVERSAL_REQUESTED", "PATIENT_MERGE_CASE", caseId,
                    request.expectedRowVersion() + 1, null);
            outbox(identity.tenantId(), "PATIENT_MERGE_CASE", caseId,
                    request.expectedRowVersion() + 1, "PatientMergeReversalRequested");
            complete(identity, "PATIENT_MERGE_REVERSAL_REQUEST", key, caseId);
            return findCase(identity.tenantId(), caseId);
        });
    }

    MergeCaseWire approveReversal(ClinicalIdentity identity, String key, UUID caseId,
            ReversalApprovalRequest request) {
        requireAdministrator(identity);
        if (request == null || request.expectedRowVersion() < 1 || !request.confirmLinksRemainTraceable()) {
            throw invalid("Expected version and link-traceability confirmation are required");
        }
        return transactions.execute(ignored -> {
            begin(identity, "PATIENT_MERGE_REVERSAL_APPROVE", key, sha256(caseId + "|" + request));
            MergeCaseWire mergeCase = lockCase(identity.tenantId(), caseId);
            if (!"REVERSAL_PENDING".equals(mergeCase.status())
                    || mergeCase.rowVersion() != request.expectedRowVersion()) {
                throw conflict("The merge case changed or is not awaiting reversal approval");
            }
            if (identity.userId().equals(mergeCase.reversalRequestedBy())) {
                throw conflict("The reversal requester cannot perform the independent approval");
            }
            int restored = jdbc.sql("""
                    update patient set status = :restored_status, merged_into_patient_id = null,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and patient_id = :source and status = 'MERGED'
                      and merged_into_patient_id = :target
                    """).param("restored_status", mergeCase.sourceStatusBeforeMerge())
                    .param("tenant", identity.tenantId()).param("source", mergeCase.sourcePatientId())
                    .param("target", mergeCase.targetPatientId()).update();
            if (restored != 1) throw conflict("The source identity no longer has the expected canonical mapping");
            int updated = jdbc.sql("""
                    update patient_merge_case set status = 'REVERSED', reversed_by = :actor,
                      reversed_at = now(), row_version = row_version + 1
                    where tenant_id = :tenant and merge_case_id = :case and status = 'REVERSAL_PENDING'
                      and row_version = :expected and reversal_requested_by <> :actor
                    """).param("actor", identity.userId()).param("tenant", identity.tenantId())
                    .param("case", caseId).param("expected", request.expectedRowVersion()).update();
            if (updated != 1) throw conflict("The reversal case changed during approval");
            if (mergeCase.candidateId() != null) {
                jdbc.sql("""
                        update patient_match_candidate set status = 'OPEN', resolved_at = null,
                          resolved_by = null, resolution_reason = null, row_version = row_version + 1
                        where tenant_id = :tenant and candidate_id = :candidate and status = 'MERGED'
                        """).param("tenant", identity.tenantId()).param("candidate", mergeCase.candidateId()).update();
            }
            long patientVersion = jdbc.sql("""
                    select row_version from patient where tenant_id = :tenant and patient_id = :patient
                    """).param("tenant", identity.tenantId()).param("patient", mergeCase.sourcePatientId())
                    .query(Long.class).single();
            evidence(identity, "PATIENT_MERGE_REVERSED", "PATIENT_MERGE_CASE", caseId,
                    request.expectedRowVersion() + 1, mergeCase.sourcePatientId());
            outbox(identity.tenantId(), "PATIENT", mergeCase.sourcePatientId(), patientVersion, "PatientMergeReversed");
            outbox(identity.tenantId(), "PATIENT_MERGE_CASE", caseId,
                    request.expectedRowVersion() + 1, "PatientMergeReversed");
            complete(identity, "PATIENT_MERGE_REVERSAL_APPROVE", key, caseId);
            return findCase(identity.tenantId(), caseId);
        });
    }

    private PatientIdentitySnapshot patient(UUID tenantId, UUID patientId, boolean lock) {
        String suffix = lock ? " for update" : "";
        return jdbc.sql("""
                select patient_id, display_name, sex_code, birth_date, status,
                  merged_into_patient_id, row_version from patient
                where tenant_id = :tenant and patient_id = :patient
                """ + suffix).param("tenant", tenantId).param("patient", patientId)
                .query((rs, row) -> new PatientIdentitySnapshot(
                        rs.getObject("patient_id", UUID.class), rs.getString("display_name"),
                        rs.getString("sex_code"), rs.getObject("birth_date", LocalDate.class),
                        rs.getString("status"), rs.getObject("merged_into_patient_id", UUID.class),
                        rs.getLong("row_version"))).optional()
                .orElseThrow(() -> denied("Patient identity is not available in this tenant"));
    }

    private void requireMatchable(PatientIdentitySnapshot patient) {
        if (!EDITABLE_PATIENT_STATUSES.contains(patient.status())) {
            throw conflict("Merged, deceased or void patient identities cannot enter matching or merge workflows");
        }
    }

    private MatchScore score(UUID tenantId, PatientIdentitySnapshot first, PatientIdentitySnapshot second) {
        Map<String, Object> signals = new LinkedHashMap<>();
        double score = 0;
        boolean sameBirth = first.birthDate().equals(second.birthDate());
        boolean sameSex = first.sexCode().equalsIgnoreCase(second.sexCode());
        boolean sameName = normalizeName(first.displayName()).equals(normalizeName(second.displayName()));
        long sharedIdentifiers = jdbc.sql("""
                select count(*) from patient_identifier a join patient_identifier b
                  on b.tenant_id = a.tenant_id and b.assigning_authority = a.assigning_authority
                  and b.identifier_type = a.identifier_type and b.identifier_hash = a.identifier_hash
                where a.tenant_id = :tenant and a.patient_id = :first and b.patient_id = :second
                  and a.active and b.active
                """).param("tenant", tenantId).param("first", first.patientId())
                .param("second", second.patientId()).query(Long.class).single();
        if (sameBirth) score += 0.40;
        if (sameName) score += 0.30;
        if (sameSex) score += 0.10;
        if (sharedIdentifiers > 0) score += 0.20;
        signals.put("same_birth_date", sameBirth);
        signals.put("same_normalized_name", sameName);
        signals.put("same_sex_code", sameSex);
        signals.put("shared_identifier_count", sharedIdentifiers);
        signals.put("algorithm_version", "MPI-RULES-1");
        return new MatchScore(Math.min(score, 1.0), signals);
    }

    private MatchCandidateWire findCandidate(UUID tenantId, UUID first, UUID second) {
        return jdbc.sql(candidateSelect() + " and candidate.patient_a_id = :a and candidate.patient_b_id = :b")
                .param("tenant", tenantId).param("a", first).param("b", second)
                .query((rs, row) -> candidate(rs)).single();
    }

    private MatchCandidateWire findCandidate(UUID tenantId, UUID candidateId) {
        return jdbc.sql(candidateSelect() + " and candidate.candidate_id = :candidate")
                .param("tenant", tenantId).param("candidate", candidateId)
                .query((rs, row) -> candidate(rs)).optional()
                .orElseThrow(() -> denied("Match candidate is not available in this tenant"));
    }

    private String candidateSelect() {
        return """
                select candidate.candidate_id, candidate.patient_a_id, a.display_name as patient_a_name,
                  candidate.patient_b_id, b.display_name as patient_b_name, candidate.match_score,
                  candidate.match_signals::text, candidate.status, candidate.detected_at,
                  candidate.resolved_at, candidate.resolved_by, candidate.resolution_reason,
                  candidate.row_version
                from patient_match_candidate candidate
                join patient a on a.tenant_id = candidate.tenant_id and a.patient_id = candidate.patient_a_id
                join patient b on b.tenant_id = candidate.tenant_id and b.patient_id = candidate.patient_b_id
                where candidate.tenant_id = :tenant
                """;
    }

    private MatchCandidateWire candidate(ResultSet rs) throws SQLException {
        return new MatchCandidateWire(rs.getObject("candidate_id", UUID.class),
                rs.getObject("patient_a_id", UUID.class), rs.getString("patient_a_name"),
                rs.getObject("patient_b_id", UUID.class), rs.getString("patient_b_name"),
                rs.getDouble("match_score"), map(rs.getString("match_signals")), rs.getString("status"),
                instant(rs, "detected_at"), instant(rs, "resolved_at"),
                rs.getObject("resolved_by", UUID.class), rs.getString("resolution_reason"),
                rs.getLong("row_version"));
    }

    private MergeCaseWire findCase(UUID tenantId, UUID caseId) {
        return jdbc.sql(mergeCaseSelect() + " and merge_case.merge_case_id = :case")
                .param("tenant", tenantId).param("case", caseId)
                .query((rs, row) -> mergeCase(rs)).optional()
                .orElseThrow(() -> denied("Merge case is not available in this tenant"));
    }

    private MergeCaseWire lockCase(UUID tenantId, UUID caseId) {
        return jdbc.sql(mergeCaseSelect() + " and merge_case.merge_case_id = :case for update")
                .param("tenant", tenantId).param("case", caseId)
                .query((rs, row) -> mergeCase(rs)).optional()
                .orElseThrow(() -> denied("Merge case is not available in this tenant"));
    }

    private String mergeCaseSelect() {
        return """
                select merge_case.merge_case_id, merge_case.candidate_id,
                  merge_case.source_patient_id, source.display_name as source_patient_name,
                  merge_case.target_patient_id, target.display_name as target_patient_name,
                  merge_case.source_status_before_merge, merge_case.status, merge_case.merge_reason,
                  merge_case.conflict_resolution::text, merge_case.requested_by, merge_case.requested_at,
                  merge_case.approved_by, merge_case.approved_at, merge_case.reversal_reason,
                  merge_case.reversal_requested_by, merge_case.reversal_requested_at,
                  merge_case.reversed_by, merge_case.reversed_at, merge_case.row_version
                from patient_merge_case merge_case
                join patient source on source.tenant_id = merge_case.tenant_id
                  and source.patient_id = merge_case.source_patient_id
                join patient target on target.tenant_id = merge_case.tenant_id
                  and target.patient_id = merge_case.target_patient_id
                where merge_case.tenant_id = :tenant
                """;
    }

    private MergeCaseWire mergeCase(ResultSet rs) throws SQLException {
        return new MergeCaseWire(rs.getObject("merge_case_id", UUID.class),
                rs.getObject("candidate_id", UUID.class), rs.getObject("source_patient_id", UUID.class),
                rs.getString("source_patient_name"), rs.getObject("target_patient_id", UUID.class),
                rs.getString("target_patient_name"), rs.getString("source_status_before_merge"),
                rs.getString("status"), rs.getString("merge_reason"),
                map(rs.getString("conflict_resolution")), rs.getObject("requested_by", UUID.class),
                instant(rs, "requested_at"), rs.getObject("approved_by", UUID.class),
                instant(rs, "approved_at"), rs.getString("reversal_reason"),
                rs.getObject("reversal_requested_by", UUID.class), instant(rs, "reversal_requested_at"),
                rs.getObject("reversed_by", UUID.class), instant(rs, "reversed_at"),
                rs.getLong("row_version"));
    }

    private DemographicVersionWire findVersion(UUID tenantId, UUID patientId, UUID versionId) {
        return jdbc.sql("""
                select version.demographic_version_id, version.patient_id, version.version_no,
                  version.display_name, version.sex_code, version.birth_date, version.patient_status,
                  version.change_type, version.change_reason, version.changed_by,
                  version.supersedes_version_id, version.created_at, patient.row_version as patient_row_version
                from patient_demographic_version version
                join patient on patient.tenant_id = version.tenant_id and patient.patient_id = version.patient_id
                where version.tenant_id = :tenant and version.patient_id = :patient
                  and version.demographic_version_id = :version
                """).param("tenant", tenantId).param("patient", patientId).param("version", versionId)
                .query((rs, row) -> demographicVersion(rs)).single();
    }

    private DemographicVersionWire demographicVersion(ResultSet rs) throws SQLException {
        return new DemographicVersionWire(rs.getObject("demographic_version_id", UUID.class),
                rs.getObject("patient_id", UUID.class), rs.getInt("version_no"),
                rs.getString("display_name"), rs.getString("sex_code"),
                rs.getObject("birth_date", LocalDate.class), rs.getString("patient_status"),
                rs.getString("change_type"), rs.getString("change_reason"),
                rs.getObject("changed_by", UUID.class), rs.getObject("supersedes_version_id", UUID.class),
                instant(rs, "created_at"), rs.getLong("patient_row_version"));
    }

    private void requireAdministrator(ClinicalIdentity identity) {
        long allowed = jdbc.sql("""
                select count(*) from role_assignment where tenant_id = :tenant and user_id = :user
                  and role_assignment_id = any(cast(:roles as uuid[])) and role_code in (:mpi_roles)
                  and status = 'ACTIVE' and valid_from <= now()
                  and (valid_until is null or valid_until > now())
                """).param("tenant", identity.tenantId()).param("user", identity.userId())
                .param("roles", uuidArray(identity.roleAssignmentIds())).param("mpi_roles", MPI_ROLES)
                .query(Long.class).single();
        if (allowed == 0) throw denied("Patient identity administration scope is not permitted");
    }

    private void begin(ClinicalIdentity identity, String scope, String key, String hash) {
        if (key == null || key.isBlank() || key.length() > 128) throw invalid("A valid Idempotency-Key is required");
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", hash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) throw conflict("This patient identity command key was already submitted");
    }

    private void complete(ClinicalIdentity identity, String scope, String key, UUID resource) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", resource).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void evidence(ClinicalIdentity identity, String action, String type, UUID resource,
            long version, UUID patientId) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previous = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + resource + "|" + trace + "|" + previous);
        jdbc.sql("""
                insert into audit_event(tenant_id, audit_event_id, occurred_at, actor_user_id,
                  action_code, resource_type, resource_id, patient_ref_hash, trace_id,
                  previous_hash, event_hash, details)
                values (:tenant, :audit, now(), :actor, :action, :type, :resource, :patient_hash,
                  :trace, :previous, :hash, jsonb_build_object('aggregate_version', :version))
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("type", type)
                .param("resource", resource).param("patient_hash", patientId == null ? null
                        : sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previous).param("hash", eventHash)
                .param("version", version).update();
    }

    private void outbox(UUID tenantId, String type, UUID resource, long version, String event) {
        jdbc.sql("""
                insert into outbox_event(tenant_id, event_id, aggregate_type, aggregate_id,
                  aggregate_version, event_type, schema_version, payload)
                values (:tenant, :event_id, :type, :resource, :version, :event, 1,
                  jsonb_build_object('resource_id', :resource))
                """).param("tenant", tenantId).param("event_id", UUID.randomUUID()).param("type", type)
                .param("resource", resource).param("version", version).param("event", event).update();
    }

    private void validateCorrection(DemographicCorrectionRequest request) {
        if (request == null || request.expectedRowVersion() < 1 || request.displayName() == null
                || request.displayName().isBlank() || request.sexCode() == null || request.sexCode().isBlank()
                || request.birthDate() == null || request.reason() == null || request.reason().trim().length() < 4) {
            throw invalid("Expected version, complete demographics and a correction reason are required");
        }
    }

    private void validateMergeRequest(MergeCaseCreateRequest request) {
        if (request == null || request.sourcePatientId() == null || request.targetPatientId() == null
                || request.sourcePatientId().equals(request.targetPatientId()) || request.reason() == null
                || request.reason().trim().length() < 8 || request.conflictResolution() == null
                || request.conflictResolution().isEmpty()) {
            throw invalid("Different source and target identities, a reason and conflict resolution are required");
        }
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception invalid) {
            throw invalid("The supplied identity evidence is not valid JSON");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(String json) {
        try {
            return objectMapper.convertValue(objectMapper.readTree(json), Map.class);
        } catch (Exception invalid) {
            throw new IllegalStateException("Stored patient identity JSON is invalid", invalid);
        }
    }

    private static java.time.Instant instant(ResultSet rs, String field) throws SQLException {
        OffsetDateTime value = rs.getObject(field, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static UUID lesser(UUID first, UUID second) {
        return first.toString().compareTo(second.toString()) < 0 ? first : second;
    }
    private static UUID greater(UUID first, UUID second) {
        return first.toString().compareTo(second.toString()) < 0 ? second : first;
    }
    private static String normalizeName(String value) { return value.replaceAll("[\\s·•]", "").toLowerCase(); }
    private static String uuidArray(List<UUID> values) {
        return "{" + values.stream().map(UUID::toString).reduce((a, b) -> a + "," + b).orElse("") + "}";
    }
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
    private static PatientIdentityException invalid(String message) {
        return new PatientIdentityException("PATIENT_IDENTITY_REQUEST_INVALID", 400, message);
    }
    private static PatientIdentityException denied(String message) {
        return new PatientIdentityException("PATIENT_IDENTITY_SCOPE_DENIED", 403, message);
    }
    private static PatientIdentityException conflict(String message) {
        return new PatientIdentityException("PATIENT_IDENTITY_VERSION_CONFLICT", 409, message);
    }

    record CandidateCreateRequest(
            @JsonProperty("patient_a_id") UUID patientAId,
            @JsonProperty("patient_b_id") UUID patientBId) {}
    record DemographicCorrectionRequest(
            @JsonProperty("expected_row_version") long expectedRowVersion,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("sex_code") String sexCode,
            @JsonProperty("birth_date") LocalDate birthDate,
            String status, String reason) {}
    record MergeCaseCreateRequest(
            @JsonProperty("candidate_id") UUID candidateId,
            @JsonProperty("source_patient_id") UUID sourcePatientId,
            @JsonProperty("target_patient_id") UUID targetPatientId,
            String reason,
            @JsonProperty("conflict_resolution") Map<String, Object> conflictResolution) {}
    record MergeApprovalRequest(
            @JsonProperty("expected_row_version") long expectedRowVersion,
            @JsonProperty("confirm_no_clinical_data_loss") boolean confirmNoClinicalDataLoss) {}
    record ReversalRequest(@JsonProperty("expected_row_version") long expectedRowVersion, String reason) {}
    record ReversalApprovalRequest(
            @JsonProperty("expected_row_version") long expectedRowVersion,
            @JsonProperty("confirm_links_remain_traceable") boolean confirmLinksRemainTraceable) {}

    record MatchCandidateWire(UUID candidateId, UUID patientAId, String patientAName,
            UUID patientBId, String patientBName, double matchScore, Map<String, Object> matchSignals,
            String status, java.time.Instant detectedAt, java.time.Instant resolvedAt, UUID resolvedBy,
            String resolutionReason, long rowVersion) {}
    record DemographicVersionWire(UUID demographicVersionId, UUID patientId, int versionNo,
            String displayName, String sexCode, LocalDate birthDate, String patientStatus,
            String changeType, String changeReason, UUID changedBy, UUID supersedesVersionId,
            java.time.Instant createdAt, long patientRowVersion) {}
    record MergeCaseWire(UUID mergeCaseId, UUID candidateId, UUID sourcePatientId,
            String sourcePatientName, UUID targetPatientId, String targetPatientName,
            String sourceStatusBeforeMerge, String status, String mergeReason,
            Map<String, Object> conflictResolution, UUID requestedBy, java.time.Instant requestedAt,
            UUID approvedBy, java.time.Instant approvedAt, String reversalReason,
            UUID reversalRequestedBy, java.time.Instant reversalRequestedAt,
            UUID reversedBy, java.time.Instant reversedAt, long rowVersion) {}

    private record PatientIdentitySnapshot(UUID patientId, String displayName, String sexCode,
            LocalDate birthDate, String status, UUID mergedIntoPatientId, long rowVersion) {}
    private record MatchScore(double score, Map<String, Object> signals) {}
}
