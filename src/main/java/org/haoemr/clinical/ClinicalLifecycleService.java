package org.haoemr.clinical;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.haoemr.contracts.DocumentDiffWire;
import org.haoemr.contracts.DocumentCorrectionPropagationWire;
import org.haoemr.contracts.DocumentCorrectionWire;
import org.haoemr.contracts.DocumentVersionWire;
import org.haoemr.contracts.EncounterWire;
import org.haoemr.contracts.EncounterStateEventWire;
import org.haoemr.contracts.PatientSummaryWire;
import org.haoemr.contracts.SignatureRevocationEvidenceWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
final class ClinicalLifecycleService implements ClinicalDocumentGateway, EncounterGateway {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final CorrectionPropagationProvider correctionPropagationProvider;

    ClinicalLifecycleService(
            JdbcClient jdbc,
            TransactionTemplate transactions,
            ObjectMapper objectMapper,
            CorrectionPropagationProvider correctionPropagationProvider) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.correctionPropagationProvider = correctionPropagationProvider;
    }

    List<PatientSummaryWire> searchPatients(ClinicalIdentity identity, String query, int requestedLimit) {
        if (query == null || query.isBlank()) {
            throw new ClinicalCommandException("PURPOSE_REQUIRED", 400, "A patient search query is required");
        }
        int limit = Math.max(1, Math.min(requestedLimit, 20));
        return transactions.execute(status -> {
            List<PatientSummaryWire> results = jdbc.sql("""
                    select distinct patient.patient_id, patient.display_name, patient.sex_code,
                      patient.birth_date, patient.row_version
                    from patient
                    left join patient_identifier identifier
                      on identifier.tenant_id = patient.tenant_id and identifier.patient_id = patient.patient_id
                    where patient.tenant_id = :tenant and patient.status = 'ACTIVE'
                      and (patient.display_name ilike :pattern or identifier.masked_value = :exact
                        or identifier.identifier_hash = decode(:identifier_hash, 'hex'))
                    order by patient.display_name, patient.patient_id
                    limit :limit
                    """)
                    .param("tenant", identity.tenantId()).param("pattern", "%" + query.trim() + "%")
                    .param("exact", query.trim()).param("identifier_hash", sha256(query.trim())).param("limit", limit)
                    .query((rs, row) -> new PatientSummaryWire(
                            rs.getObject("patient_id", UUID.class), rs.getString("display_name"),
                            rs.getString("sex_code"), rs.getDate("birth_date").toLocalDate(),
                            rs.getLong("row_version")))
                    .list();
            appendAudit(identity, "PATIENT_SEARCHED", "PATIENT_QUERY", identity.tenantId(), null, UUID.randomUUID().toString());
            return results;
        });
    }

    PatientSummaryWire createPatient(
            ClinicalIdentity identity,
            String idempotencyKey,
            String displayName,
            String sexCode,
            java.time.LocalDate birthDate,
            String assigningAuthority,
            String identifierType,
            String identifierValue,
            String requestedIdentityStatus,
            List<UUID> acknowledgedCandidatePatientIds) {
        requireText(displayName, "display_name");
        requireText(sexCode, "sex_code");
        requireText(assigningAuthority, "assigning_authority");
        requireText(identifierType, "identifier_type");
        requireText(identifierValue, "identifier_value");
        String identityStatus = requestedIdentityStatus == null ? "ACTIVE" : requestedIdentityStatus.trim().toUpperCase();
        if (!Set.of("ACTIVE", "PENDING_VERIFICATION").contains(identityStatus)) {
            throw new ClinicalCommandException("VALIDATION_FAILED", 400,
                    "identity_status must be ACTIVE or PENDING_VERIFICATION");
        }
        Set<UUID> acknowledged = Set.copyOf(
                acknowledgedCandidatePatientIds == null ? List.of() : acknowledgedCandidatePatientIds);
        return transactions.execute(status -> {
            String requestHash = sha256(String.join("|", displayName, sexCode, birthDate.toString(),
                    assigningAuthority, identifierType, identifierValue, identityStatus, acknowledged.toString()));
            IdempotencyReplay replay = jdbc.sql("""
                    select request_hash, state,
                      nullif(response_ref ->> 'resource_id', '')::uuid as resource_id
                    from idempotency_record where tenant_id = :tenant
                      and command_scope = 'PATIENT_CREATE' and idempotency_key = :key
                    """).param("tenant", identity.tenantId()).param("key", idempotencyKey)
                    .query((rs, row) -> new IdempotencyReplay(rs.getString("request_hash"),
                            rs.getString("state"), rs.getObject("resource_id", UUID.class)))
                    .optional().orElse(null);
            if (replay != null) {
                if (!replay.requestHash().equals(requestHash)) {
                    throw new ClinicalCommandException("IDEMPOTENCY_KEY_REUSED", 409,
                            "This patient registration key was reused with a different request");
                }
                if ("SUCCEEDED".equals(replay.state()) && replay.resourceId() != null) {
                    return patientSummary(identity.tenantId(), replay.resourceId());
                }
                throw new ClinicalCommandException("IDEMPOTENCY_REPLAY", 409,
                        "This patient registration is still being reconciled");
            }
            beginCommand(identity, "PATIENT_CREATE", idempotencyKey, requestHash);
            List<UUID> possibleDuplicates = jdbc.sql("""
                    select patient_id from patient
                    where tenant_id = :tenant and status in ('ACTIVE', 'PENDING_VERIFICATION', 'POSSIBLE_DUPLICATE')
                      and lower(regexp_replace(
                            regexp_replace(display_name,
                              '[[:space:]]*/[[:space:]]*Synthetic Patient.*$', '', 'i'),
                            '[[:space:]·•]', '', 'g')) =
                          lower(regexp_replace(
                            regexp_replace(:name,
                              '[[:space:]]*/[[:space:]]*Synthetic Patient.*$', '', 'i'),
                            '[[:space:]·•]', '', 'g'))
                      and birth_date = :birth and upper(sex_code) = upper(:sex)
                    order by patient_id
                    """).param("tenant", identity.tenantId()).param("name", displayName.trim())
                    .param("birth", birthDate).param("sex", sexCode.trim()).query(UUID.class).list();
            if ("ACTIVE".equals(identityStatus)
                    && possibleDuplicates.stream().anyMatch(candidate -> !acknowledged.contains(candidate))) {
                throw new ClinicalCommandException("POSSIBLE_DUPLICATE", 409,
                        "Possible duplicate identities require explicit review before active registration");
            }
            UUID patientId = UUID.randomUUID();
            jdbc.sql("""
                    insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                    values (:tenant, :patient, :name, :sex, :birth, :status)
                    """)
                    .param("tenant", identity.tenantId()).param("patient", patientId)
                    .param("name", displayName.trim()).param("sex", sexCode.trim()).param("birth", birthDate)
                    .param("status", identityStatus).update();
            jdbc.sql("""
                    insert into patient_identifier(
                      tenant_id, patient_identifier_id, patient_id, assigning_authority,
                      identifier_type, identifier_hash, masked_value, source_system)
                    values (:tenant, :id, :patient, :authority, :type, decode(:hash, 'hex'), :masked, 'HAOEMR')
                    """)
                    .param("tenant", identity.tenantId()).param("id", UUID.randomUUID()).param("patient", patientId)
                    .param("authority", assigningAuthority.trim()).param("type", identifierType.trim())
                    .param("hash", sha256(identifierValue.trim())).param("masked", mask(identifierValue.trim())).update();
            UUID demographicVersionId = UUID.randomUUID();
            jdbc.sql("""
                    insert into patient_demographic_version(
                      tenant_id, patient_id, demographic_version_id, version_no, display_name,
                      sex_code, birth_date, patient_status, change_type, change_reason, changed_by)
                    values (:tenant, :patient, :version, 1, :name, :sex, :birth, :status,
                      'INITIAL_IMPORT', 'Registered in haoemr MPI', :actor)
                    """).param("tenant", identity.tenantId()).param("patient", patientId)
                    .param("version", demographicVersionId).param("name", displayName.trim())
                    .param("sex", sexCode.trim()).param("birth", birthDate).param("status", identityStatus)
                    .param("actor", identity.userId()).update();
            for (UUID possibleDuplicate : possibleDuplicates) {
                boolean patientFirst = patientId.toString().compareTo(possibleDuplicate.toString()) < 0;
                UUID patientA = patientFirst ? patientId : possibleDuplicate;
                UUID patientB = patientFirst ? possibleDuplicate : patientId;
                boolean reviewedAsDistinct = acknowledged.contains(possibleDuplicate);
                jdbc.sql("""
                        insert into patient_match_candidate(
                          tenant_id, candidate_id, patient_a_id, patient_b_id, match_score,
                          match_signals, status, resolved_at, resolved_by, resolution_reason)
                        values (:tenant, :candidate, :a, :b, 0.8000,
                          jsonb_build_object('same_normalized_name', true, 'same_birth_date', true,
                            'same_sex_code', true, 'algorithm_version', 'MPI-RULES-1'),
                          :status, :resolved_at, :resolved_by, :reason)
                        on conflict (tenant_id, patient_a_id, patient_b_id) do nothing
                        """).param("tenant", identity.tenantId()).param("candidate", UUID.randomUUID())
                        .param("a", patientA).param("b", patientB)
                        .param("status", reviewedAsDistinct ? "DISMISSED" : "OPEN")
                        .param("resolved_at", reviewedAsDistinct ? OffsetDateTime.now(java.time.ZoneOffset.UTC) : null)
                        .param("resolved_by", reviewedAsDistinct ? identity.userId() : null)
                        .param("reason", reviewedAsDistinct ? "Registration user confirmed distinct identities" : null)
                        .update();
            }
            String trace = UUID.randomUUID().toString();
            appendAudit(identity, "PATIENT_CREATED", "PATIENT", patientId, patientId, trace);
            appendOutbox(identity.tenantId(), "PATIENT", patientId, 1, "PatientCreated");
            completeCommand(identity, "PATIENT_CREATE", idempotencyKey, patientId);
            return new PatientSummaryWire(patientId, displayName.trim(), sexCode.trim(), birthDate, 1L);
        });
    }

    @Override
    public EncounterWire createEncounter(
            ClinicalIdentity identity,
            String idempotencyKey,
            UUID organizationId,
            UUID facilityId,
            UUID patientId,
            String encounterType,
            String initialStatus,
            UUID departmentId,
            UUID responsibleUserId,
            java.time.Instant startedAt,
            String sourceSystem,
            String sourceKey) {
        return transactions.execute(status -> {
            String effectiveStatus = initialStatus == null ? "IN_PROGRESS" : initialStatus;
            requireEncounterCreationScope(identity.tenantId(), organizationId, facilityId, patientId,
                    departmentId, responsibleUserId);
            String requestHash = sha256(String.join("|", patientId.toString(), encounterType,
                    effectiveStatus, String.valueOf(departmentId), String.valueOf(responsibleUserId),
                    startedAt.toString(), sourceSystem, sourceKey));
            beginCommand(identity, "ENCOUNTER_CREATE", idempotencyKey, requestHash);
            if (jdbc.sql("""
                    select count(*) from encounter
                    where tenant_id=:tenant and source_system=:source_system and source_key=:source_key
                    """).param("tenant", identity.tenantId()).param("source_system", sourceSystem)
                    .param("source_key", sourceKey).query(Long.class).single() > 0) {
                throw new ClinicalCommandException("ENCOUNTER_SOURCE_DUPLICATE", 409,
                        "The source encounter message has already been applied");
            }
            UUID encounterId = UUID.randomUUID();
            setEncounterTransitionContext(identity.userId(), "ENCOUNTER_CREATED", startedAt);
            jdbc.sql("""
                    insert into encounter(
                      tenant_id, encounter_id, patient_id, organization_id, facility_id,
                      encounter_type, status, department_id, responsible_user_id,
                      started_at, source_system, source_key)
                    values (:tenant, :encounter, :patient, :organization, :facility,
                      :type, :status, :department, :responsible,
                      :started, :source_system, :source_key)
                    """)
                    .param("tenant", identity.tenantId()).param("encounter", encounterId)
                    .param("patient", patientId).param("organization", organizationId).param("facility", facilityId)
                    .param("type", encounterType).param("status", effectiveStatus)
                    .param("department", departmentId).param("responsible", responsibleUserId)
                    .param("started", OffsetDateTime.ofInstant(startedAt, java.time.ZoneOffset.UTC))
                    .param("source_system", sourceSystem).param("source_key", sourceKey).update();
            for (UUID roleAssignmentId : identity.roleAssignmentIds()) {
                jdbc.sql("""
                        insert into patient_care_relationship(
                          tenant_id, patient_relationship_id, patient_id, user_id, role_assignment_id,
                          encounter_id, relationship_type, status, valid_from, created_by)
                        values (:tenant, :relationship, :patient, :user, :role, :encounter,
                          'CARE_TEAM', 'ACTIVE', :started, :user)
                        on conflict (tenant_id, patient_id, role_assignment_id, encounter_id, relationship_type)
                        do nothing
                        """).param("tenant", identity.tenantId()).param("relationship", UUID.randomUUID())
                        .param("patient", patientId).param("user", identity.userId()).param("role", roleAssignmentId)
                        .param("encounter", encounterId)
                        .param("started", OffsetDateTime.ofInstant(startedAt, java.time.ZoneOffset.UTC)).update();
            }
            String trace = UUID.randomUUID().toString();
            appendAudit(identity, "ENCOUNTER_CREATED", "ENCOUNTER", encounterId, patientId, trace);
            appendOutbox(identity.tenantId(), "ENCOUNTER", encounterId, 1, "EncounterCreated");
            completeCommand(identity, "ENCOUNTER_CREATE", idempotencyKey, encounterId);
            return encounter(identity.tenantId(), encounterId, patientId, facilityId);
        });
    }

    List<EncounterWire> listPatientEncounters(
            ClinicalIdentity identity, UUID organizationId, UUID facilityId, UUID patientId) {
        requireEncounterCreationScope(identity.tenantId(), organizationId, facilityId, patientId, null, null);
        return jdbc.sql("""
                select * from encounter
                where tenant_id=:tenant and organization_id=:organization
                  and facility_id=:facility and patient_id=:patient
                order by started_at desc, encounter_id desc
                limit 100
                """).param("tenant", identity.tenantId()).param("organization", organizationId)
                .param("facility", facilityId).param("patient", patientId)
                .query((rs, row) -> encounter(rs)).list();
    }

    EncounterWire getEncounter(
            ClinicalIdentity identity, UUID organizationId, UUID facilityId, UUID patientId, UUID encounterId) {
        return encounter(identity.tenantId(), encounterId, patientId, facilityId, organizationId);
    }

    List<EncounterStateEventWire> listEncounterStateEvents(
            ClinicalIdentity identity, UUID organizationId, UUID facilityId, UUID patientId, UUID encounterId) {
        encounter(identity.tenantId(), encounterId, patientId, facilityId, organizationId);
        return jdbc.sql("""
                select encounter_state_event_id, encounter_id, version_no, from_status, to_status,
                  occurred_at, reason, changed_by, created_at
                from encounter_state_event
                where tenant_id=:tenant and encounter_id=:encounter
                order by version_no desc, encounter_state_event_id desc
                """).param("tenant", identity.tenantId()).param("encounter", encounterId)
                .query((rs, row) -> new EncounterStateEventWire(
                        rs.getObject("encounter_state_event_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class), rs.getLong("version_no"),
                        rs.getString("from_status"),
                        EncounterStateEventWire.ToStatusValue.valueOf(rs.getString("to_status")),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        rs.getString("reason"), rs.getObject("changed_by", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant())).list();
    }

    @Override
    public EncounterWire transitionEncounter(
            ClinicalIdentity identity,
            String idempotencyKey,
            UUID organizationId,
            UUID facilityId,
            UUID patientId,
            UUID encounterId,
            long expectedRowVersion,
            String targetStatus,
            java.time.Instant occurredAt,
            String reason) {
        return transactions.execute(status -> {
            String normalizedReason = reason == null ? null : reason.trim();
            if (("SUSPENDED".equals(targetStatus) || "CANCELLED".equals(targetStatus))
                    && (normalizedReason == null || normalizedReason.length() < 4)) {
                throw new ClinicalCommandException("ENCOUNTER_TRANSITION_REASON_REQUIRED", 400,
                        "Suspending or cancelling an encounter requires a reason");
            }
            String requestHash = sha256(String.join("|", encounterId.toString(), String.valueOf(expectedRowVersion),
                    targetStatus, occurredAt.toString(), String.valueOf(normalizedReason)));
            beginCommand(identity, "ENCOUNTER_STATE_TRANSITION", idempotencyKey, requestHash);
            EncounterHead current = jdbc.sql("""
                    select status, row_version, started_at, encounter_type from encounter
                    where tenant_id=:tenant and encounter_id=:encounter and patient_id=:patient
                      and organization_id=:organization and facility_id=:facility
                    for update
                    """).param("tenant", identity.tenantId()).param("encounter", encounterId)
                    .param("patient", patientId).param("organization", organizationId).param("facility", facilityId)
                    .query((rs, row) -> new EncounterHead(rs.getString("status"), rs.getLong("row_version"),
                            rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                            rs.getString("encounter_type")))
                    .optional().orElseThrow(ClinicalLifecycleService::notFound);
            if (current.rowVersion() != expectedRowVersion) {
                throw new ClinicalCommandException("VERSION_CONFLICT", 409,
                        "The encounter state changed before this transition");
            }
            if (!allowedEncounterTransition(current.status(), targetStatus)) {
                throw new ClinicalCommandException("INVALID_ENCOUNTER_TRANSITION", 409,
                        "Illegal encounter state transition: " + current.status() + " -> " + targetStatus);
            }
            if (occurredAt.isBefore(current.startedAt())) {
                throw new ClinicalCommandException("INVALID_ENCOUNTER_TRANSITION_TIME", 400,
                        "The transition time cannot be before the encounter start");
            }
            if ("OUTPATIENT".equals(current.encounterType()) && "FINISHED".equals(targetStatus)) {
                validateOutpatientClosure(identity.tenantId(), patientId, encounterId);
            }
            setEncounterTransitionContext(identity.userId(), normalizedReason, occurredAt);
            int updated = jdbc.sql("""
                    update encounter set status=:target,
                      ended_at=case when :target in ('FINISHED','CANCELLED') then :occurred else null end,
                      row_version=row_version+1, updated_at=now()
                    where tenant_id=:tenant and encounter_id=:encounter and row_version=:expected
                    """).param("target", targetStatus)
                    .param("occurred", OffsetDateTime.ofInstant(occurredAt, java.time.ZoneOffset.UTC))
                    .param("tenant", identity.tenantId()).param("encounter", encounterId)
                    .param("expected", expectedRowVersion).update();
            if (updated != 1) {
                throw new ClinicalCommandException("VERSION_CONFLICT", 409,
                        "The encounter state changed before this transition");
            }
            long nextVersion = expectedRowVersion + 1;
            appendAudit(identity, "ENCOUNTER_STATE_CHANGED", "ENCOUNTER", encounterId, patientId,
                    UUID.randomUUID().toString());
            appendOutbox(identity.tenantId(), "ENCOUNTER", encounterId, nextVersion, "EncounterStateChanged");
            completeCommand(identity, "ENCOUNTER_STATE_TRANSITION", idempotencyKey, encounterId);
            return encounter(identity.tenantId(), encounterId, patientId, facilityId, organizationId);
        });
    }

    @Override
    public DocumentVersionWire createDocument(
            ClinicalIdentity identity,
            String idempotencyKey,
            UUID patientId,
            UUID encounterId,
            String documentTypeCode,
            Map<String, Object> sections) {
        requireText(documentTypeCode, "document_type_code");
        return transactions.execute(status -> {
            String canonicalSections = json(sections);
            String requestHash = sha256(encounterId + "|" + documentTypeCode + "|" + canonicalSections);
            beginCommand(identity, "DOCUMENT_CREATE", idempotencyKey, requestHash);
            UUID documentId = UUID.randomUUID();
            UUID versionId = UUID.randomUUID();
            TemplateBinding template = resolveTemplate(identity.tenantId(), encounterId, documentTypeCode.trim());
            String contentHash = sha256(canonicalSections);
            OffsetDateTime now = OffsetDateTime.now(java.time.ZoneOffset.UTC);
            jdbc.sql("""
                    insert into clinical_document(
                      tenant_id, document_id, patient_id, encounter_id, document_type_code,
                      template_version_id, status, created_by)
                    values (:tenant, :document, :patient, :encounter, :type,
                      :template_version, 'DRAFT', :author)
                    """)
                    .param("tenant", identity.tenantId()).param("document", documentId).param("patient", patientId)
                    .param("encounter", encounterId).param("type", documentTypeCode.trim())
                    .param("template_version", template.templateVersionId()).param("author", identity.userId()).update();
            jdbc.sql("""
                    insert into clinical_document_version(
                      tenant_id, document_id, document_version_id, version_no, status,
                      sections, content_hash, author_user_id, created_at)
                    values (:tenant, :document, :version, 1, 'DRAFT', cast(:sections as jsonb), :hash, :author, :created)
                    """)
                    .param("tenant", identity.tenantId()).param("document", documentId).param("version", versionId)
                    .param("sections", canonicalSections).param("hash", contentHash).param("author", identity.userId())
                    .param("created", now).update();
            jdbc.sql("update clinical_document set current_version_id = :version where tenant_id = :tenant and document_id = :document")
                    .param("version", versionId).param("tenant", identity.tenantId()).param("document", documentId).update();
            appendAudit(identity, "DOCUMENT_DRAFT_CREATED", "CLINICAL_DOCUMENT", documentId, patientId, UUID.randomUUID().toString());
            appendOutbox(identity.tenantId(), "CLINICAL_DOCUMENT", documentId, 1, "DocumentDraftCreated");
            completeCommand(identity, "DOCUMENT_CREATE", idempotencyKey, documentId);
            return new DocumentVersionWire(documentId, versionId, encounterId,
                    template.templateVersionId(), template.versionNo(), 1,
                    DocumentVersionWire.StatusValue.DRAFT, documentTypeCode.trim(), sections, contentHash, 1L, now.toInstant());
        });
    }

    @Override
    public void configureSignaturePolicy(
            ClinicalIdentity identity,
            UUID documentId,
            UUID documentVersionId,
            String requiredSignatureLevel) {
        jdbc.sql("""
                insert into document_signature_policy(
                  tenant_id, document_id, document_version_id, required_signature_level)
                values (:tenant, :document, :version, :required)
                """).param("tenant", identity.tenantId()).param("document", documentId)
                .param("version", documentVersionId).param("required", requiredSignatureLevel).update();
    }

    private String validateAgentDraftSource(UUID tenantId, UUID sourceRunId, UUID patientId,
            UUID encounterId, UUID documentId, UUID currentVersionId) {
        String payload = jdbc.sql("""
                select run.output_payload::text from medical_agent_run run
                where run.tenant_id = :tenant and run.run_id = :run and run.patient_id = :patient
                  and run.encounter_id = :encounter and run.root_agent_code = 'DOCUMENT_DRAFTER'
                  and run.state in ('WAITING_FOR_REVIEW', 'COMPLETED')
                  and run.output_payload ->> 'execution_mode' = 'LIVE_MODEL'
                  and (run.target_type is distinct from 'DOCUMENT' or run.target_id = :document)
                  and exists (select 1 from medical_agent_child_run child
                    cross join lateral jsonb_array_elements(child.source_references) reference
                    where child.tenant_id = run.tenant_id and child.root_run_id = run.run_id
                      and child.state = 'COMPLETED'
                      and jsonb_typeof(child.contribution -> 'draft_sections') = 'object'
                      and reference ->> 'source_type' = 'DOCUMENT_VERSION'
                      and reference ->> 'source_id' = cast(:version as text))
                """).param("tenant", tenantId).param("run", sourceRunId).param("patient", patientId)
                .param("encounter", encounterId).param("document", documentId).param("version", currentVersionId)
                .query(String.class).optional().orElseThrow(() -> new ClinicalCommandException(
                        "MEDICAL_AGENT_DRAFT_SOURCE_STALE", 409,
                        "The candidate is unavailable or belongs to another patient or document version; regenerate it"));
        return sha256(payload);
    }

    DocumentVersionWire saveDraft(
            ClinicalIdentity identity,
            String idempotencyKey,
            UUID documentId,
            UUID patientId,
            UUID encounterId,
            long expectedRowVersion,
            Map<String, Object> sections) {
        return saveDraft(identity, idempotencyKey, documentId, patientId, encounterId, expectedRowVersion, sections, null);
    }

    DocumentVersionWire saveDraft(ClinicalIdentity identity, String idempotencyKey, UUID documentId,
            UUID patientId, UUID encounterId, long expectedRowVersion, Map<String, Object> sections, UUID sourceRunId) {
        return transactions.execute(status -> {
            String canonicalSections = json(sections);
            String requestHash = sha256(documentId + "|" + expectedRowVersion + "|" + canonicalSections + "|" + sourceRunId);
            beginCommand(identity, "DOCUMENT_DRAFT_SAVE", idempotencyKey, requestHash);
            DocumentHead head = jdbc.sql("""
                    select document_type_code, status, current_version_id, row_version
                    from clinical_document
                    where tenant_id = :tenant and document_id = :document
                      and patient_id = :patient and encounter_id = :encounter
                    for update
                    """)
                    .param("tenant", identity.tenantId()).param("document", documentId)
                    .param("patient", patientId).param("encounter", encounterId)
                    .query((rs, row) -> new DocumentHead(
                            rs.getString("document_type_code"), rs.getString("status"),
                            rs.getObject("current_version_id", UUID.class), rs.getLong("row_version")))
                    .optional().orElseThrow(() -> new ClinicalCommandException(
                            "CONTEXT_NOT_PERMITTED", 403, "The requested clinical context is not permitted"));
            if (!"DRAFT".equals(head.status())) {
                throw new ClinicalCommandException("INVALID_DOCUMENT_STATE", 409, "Only a draft document can be saved");
            }
            if (head.rowVersion() != expectedRowVersion) {
                throw versionConflict(documentId, head.currentVersionId(), head.rowVersion());
            }
            String candidateHash = sourceRunId == null ? null : validateAgentDraftSource(
                    identity.tenantId(), sourceRunId, patientId, encounterId, documentId, head.currentVersionId());
            int nextVersion = jdbc.sql("""
                    select coalesce(max(version_no), 0) + 1 from clinical_document_version
                    where tenant_id = :tenant and document_id = :document
                    """)
                    .param("tenant", identity.tenantId()).param("document", documentId)
                    .query(Integer.class).single();
            UUID versionId = UUID.randomUUID();
            String contentHash = sha256(canonicalSections);
            OffsetDateTime now = OffsetDateTime.now(java.time.ZoneOffset.UTC);
            jdbc.sql("""
                    insert into clinical_document_version(
                      tenant_id, document_id, document_version_id, version_no, status,
                      sections, content_hash, based_on_version_id, author_user_id, created_at)
                    values (:tenant, :document, :version, :version_no, 'DRAFT', cast(:sections as jsonb),
                      :hash, :based_on, :author, :created)
                    """)
                    .param("tenant", identity.tenantId()).param("document", documentId).param("version", versionId)
                    .param("version_no", nextVersion).param("sections", canonicalSections).param("hash", contentHash)
                    .param("based_on", head.currentVersionId()).param("author", identity.userId()).param("created", now).update();
            jdbc.sql("""
                    insert into document_signature_policy(
                      tenant_id, document_id, document_version_id, required_signature_level,
                      requires_distinct_signers)
                    select tenant_id, document_id, :new_version, required_signature_level,
                      requires_distinct_signers
                    from document_signature_policy
                    where tenant_id = :tenant and document_id = :document
                      and document_version_id = :previous_version
                    """).param("new_version", versionId).param("tenant", identity.tenantId())
                    .param("document", documentId).param("previous_version", head.currentVersionId()).update();
            int updated = jdbc.sql("""
                    update clinical_document
                    set current_version_id = :version, row_version = row_version + 1, updated_at = :updated
                    where tenant_id = :tenant and document_id = :document and row_version = :expected
                    """)
                    .param("version", versionId).param("updated", now).param("tenant", identity.tenantId())
                    .param("document", documentId).param("expected", expectedRowVersion).update();
            if (updated != 1) {
                throw versionConflict(documentId, head.currentVersionId(), head.rowVersion());
            }
            if (sourceRunId != null) {
                jdbc.sql("""
                        insert into medical_agent_document_adoption(tenant_id, adoption_id, source_run_id,
                          document_id, document_version_id, source_payload_hash, adopted_fields, adopted_by)
                        values (:tenant, :adoption, :run, :document, :version, :hash, cast(:fields as jsonb), :actor)
                        """).param("tenant", identity.tenantId()).param("adoption", UUID.randomUUID())
                        .param("run", sourceRunId).param("document", documentId).param("version", versionId)
                        .param("hash", candidateHash).param("fields", json(sections.keySet()))
                        .param("actor", identity.userId()).update();
                appendAudit(identity, "MEDICAL_AGENT_DRAFT_ADOPTED", "MEDICAL_AGENT_RUN", sourceRunId,
                        patientId, UUID.randomUUID().toString());
            }
            long newRowVersion = expectedRowVersion + 1;
            appendAudit(identity, "DOCUMENT_DRAFT_SAVED", "CLINICAL_DOCUMENT", documentId, patientId, UUID.randomUUID().toString());
            appendOutbox(identity.tenantId(), "CLINICAL_DOCUMENT", documentId, newRowVersion, "DocumentDraftSaved");
            completeCommand(identity, "DOCUMENT_DRAFT_SAVE", idempotencyKey, versionId);
            TemplateBinding template = templateBinding(identity.tenantId(), documentId);
            return new DocumentVersionWire(documentId, versionId, encounterId,
                    template.templateVersionId(), template.versionNo(), nextVersion,
                    DocumentVersionWire.StatusValue.DRAFT, head.documentTypeCode(), sections,
                    contentHash, newRowVersion, now.toInstant());
        });
    }

    DocumentVersionWire voidDocument(
            ClinicalIdentity identity,
            String idempotencyKey,
            UUID documentId,
            UUID patientId,
            UUID encounterId,
            long expectedRowVersion,
            String reason) {
        requireCorrectionReason(reason);
        return transactions.execute(status -> {
            beginCommand(identity, "DOCUMENT_VOID", idempotencyKey,
                    sha256(documentId + "|" + expectedRowVersion + "|" + reason.trim()));
            VoidableDocument head = jdbc.sql("""
                    select document.document_type_code, document.status, document.current_version_id,
                      document.row_version, version.sections::text
                    from clinical_document document
                    join clinical_document_version version
                      on version.tenant_id = document.tenant_id and version.document_id = document.document_id
                      and version.document_version_id = document.current_version_id
                    where document.tenant_id = :tenant and document.document_id = :document
                      and document.patient_id = :patient and document.encounter_id = :encounter
                    for update of document
                    """).param("tenant", identity.tenantId()).param("document", documentId)
                    .param("patient", patientId).param("encounter", encounterId)
                    .query((rs, row) -> new VoidableDocument(
                            rs.getString("document_type_code"), rs.getString("status"),
                            rs.getObject("current_version_id", UUID.class), rs.getLong("row_version"),
                            map(rs.getString("sections"))))
                    .optional().orElseThrow(ClinicalLifecycleService::notFound);
            if (head.rowVersion() != expectedRowVersion) {
                throw versionConflict(documentId, head.currentVersionId(), head.rowVersion());
            }
            if (!"DRAFT".equals(head.status())) {
                throw new ClinicalCommandException("INVALID_DOCUMENT_STATE", 409,
                        "Only a draft document can be voided");
            }
            int nextVersion = jdbc.sql("""
                    select coalesce(max(version_no), 0) + 1 from clinical_document_version
                    where tenant_id = :tenant and document_id = :document
                    """).param("tenant", identity.tenantId()).param("document", documentId)
                    .query(Integer.class).single();
            UUID versionId = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(java.time.ZoneOffset.UTC);
            String canonicalSections = json(head.sections());
            String contentHash = sha256(canonicalSections);
            jdbc.sql("""
                    insert into clinical_document_version(
                      tenant_id, document_id, document_version_id, version_no, status,
                      sections, content_hash, based_on_version_id, author_user_id, created_at)
                    values (:tenant, :document, :version, :version_no, 'VOID', cast(:sections as jsonb),
                      :hash, :based_on, :author, :created)
                    """).param("tenant", identity.tenantId()).param("document", documentId)
                    .param("version", versionId).param("version_no", nextVersion)
                    .param("sections", canonicalSections).param("hash", contentHash)
                    .param("based_on", head.currentVersionId()).param("author", identity.userId())
                    .param("created", now).update();
            int updated = jdbc.sql("""
                    update clinical_document set status = 'VOID', current_version_id = :version,
                      row_version = row_version + 1, updated_at = :updated
                    where tenant_id = :tenant and document_id = :document and row_version = :expected
                    """).param("version", versionId).param("updated", now)
                    .param("tenant", identity.tenantId()).param("document", documentId)
                    .param("expected", expectedRowVersion).update();
            if (updated != 1) throw versionConflict(documentId, head.currentVersionId(), head.rowVersion());
            appendAudit(identity, "DOCUMENT_VOIDED", "CLINICAL_DOCUMENT", documentId, patientId,
                    UUID.randomUUID().toString());
            appendOutbox(identity.tenantId(), "CLINICAL_DOCUMENT", documentId, expectedRowVersion + 1,
                    "DocumentVoided");
            completeCommand(identity, "DOCUMENT_VOID", idempotencyKey, versionId);
            TemplateBinding template = templateBinding(identity.tenantId(), documentId);
            return new DocumentVersionWire(documentId, versionId, encounterId,
                    template.templateVersionId(), template.versionNo(), nextVersion,
                    DocumentVersionWire.StatusValue.VOID, head.documentTypeCode(), head.sections(),
                    contentHash, expectedRowVersion + 1, now.toInstant());
        });
    }

    DocumentCorrectionWire createCorrection(
            ClinicalIdentity identity,
            String idempotencyKey,
            UUID documentId,
            UUID patientId,
            UUID encounterId,
            UUID sourceVersionId,
            long expectedRowVersion,
            String correctionType,
            String reason,
            Map<String, Object> sections) {
        requireCorrectionReason(reason);
        String normalizedType = correctionType == null ? "" : correctionType.trim().toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("CORRECTION", "ADDENDUM").contains(normalizedType)) {
            throw new ClinicalCommandException("VALIDATION_FAILED", 400, "correction_type must be CORRECTION or ADDENDUM");
        }
        return transactions.execute(status -> {
            String canonicalSections = json(sections);
            beginCommand(identity, "DOCUMENT_CORRECTION_CREATE", idempotencyKey,
                    sha256(documentId + "|" + sourceVersionId + "|" + expectedRowVersion + "|"
                            + normalizedType + "|" + reason.trim() + "|" + canonicalSections));
            CorrectionHead head = jdbc.sql("""
                    select document.document_type_code, document.current_version_id, document.row_version,
                      document.status as document_status, version.status as version_status
                    from clinical_document document
                    join clinical_document_version version
                      on version.tenant_id = document.tenant_id and version.document_id = document.document_id
                      and version.document_version_id = document.current_version_id
                    where document.tenant_id = :tenant and document.document_id = :document
                      and document.patient_id = :patient and document.encounter_id = :encounter
                    for update of document
                    """).param("tenant", identity.tenantId()).param("document", documentId)
                    .param("patient", patientId).param("encounter", encounterId)
                    .query((rs, row) -> new CorrectionHead(
                            rs.getString("document_type_code"), rs.getObject("current_version_id", UUID.class),
                            rs.getLong("row_version"), rs.getString("document_status"), rs.getString("version_status")))
                    .optional().orElseThrow(ClinicalLifecycleService::notFound);
            if (head.rowVersion() != expectedRowVersion || !head.currentVersionId().equals(sourceVersionId)) {
                throw versionConflict(documentId, head.currentVersionId(), head.rowVersion());
            }
            if (!"SIGNED".equals(head.versionStatus()) || !Set.of("SIGNED", "VOID").contains(head.documentStatus())) {
                throw new ClinicalCommandException("CORRECTION_SOURCE_NOT_SIGNED", 409,
                        "A correction must start from the current signed document version");
            }
            int nextVersion = jdbc.sql("""
                    select coalesce(max(version_no), 0) + 1 from clinical_document_version
                    where tenant_id = :tenant and document_id = :document
                    """).param("tenant", identity.tenantId()).param("document", documentId)
                    .query(Integer.class).single();
            UUID correctionId = UUID.randomUUID();
            UUID correctionVersionId = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(java.time.ZoneOffset.UTC);
            String contentHash = sha256(canonicalSections);
            jdbc.sql("""
                    insert into clinical_document_version(
                      tenant_id, document_id, document_version_id, version_no, status, sections,
                      content_hash, based_on_version_id, author_user_id, created_at)
                    values (:tenant, :document, :version, :version_no, 'DRAFT', cast(:sections as jsonb),
                      :hash, :source, :actor, :created)
                    """).param("tenant", identity.tenantId()).param("document", documentId)
                    .param("version", correctionVersionId).param("version_no", nextVersion)
                    .param("sections", canonicalSections).param("hash", contentHash).param("source", sourceVersionId)
                    .param("actor", identity.userId()).param("created", now).update();
            jdbc.sql("""
                    insert into document_signature_policy(
                      tenant_id, document_id, document_version_id, required_signature_level,
                      current_signature_level, review_status, requires_distinct_signers)
                    select tenant_id, document_id, :new_version, required_signature_level,
                      null, 'PENDING', requires_distinct_signers
                    from document_signature_policy
                    where tenant_id = :tenant and document_id = :document and document_version_id = :source
                    """).param("new_version", correctionVersionId).param("tenant", identity.tenantId())
                    .param("document", documentId).param("source", sourceVersionId).update();
            jdbc.sql("""
                    insert into document_correction_case(
                      tenant_id, correction_id, document_id, source_document_version_id,
                      correction_document_version_id, correction_type, correction_reason,
                      status, requested_by, requested_at)
                    values (:tenant, :correction, :document, :source, :version, :type, :reason,
                      'DRAFT', :actor, :created)
                    """).param("tenant", identity.tenantId()).param("correction", correctionId)
                    .param("document", documentId).param("source", sourceVersionId)
                    .param("version", correctionVersionId).param("type", normalizedType)
                    .param("reason", reason.trim()).param("actor", identity.userId()).param("created", now).update();
            jdbc.sql("""
                    insert into document_correction_event(
                      tenant_id, correction_event_id, correction_id, event_type, actor_user_id,
                      details, occurred_at)
                    values (:tenant, :event, :correction, 'CORRECTION_CREATED', :actor,
                      jsonb_build_object('source_version_id', :source, 'correction_version_id', :version), :created)
                    """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                    .param("correction", correctionId).param("actor", identity.userId())
                    .param("source", sourceVersionId).param("version", correctionVersionId).param("created", now).update();
            int updated = jdbc.sql("""
                    update clinical_document set current_version_id = :version, status = 'DRAFT',
                      row_version = row_version + 1, updated_at = :updated
                    where tenant_id = :tenant and document_id = :document and row_version = :expected
                    """).param("version", correctionVersionId).param("updated", now)
                    .param("tenant", identity.tenantId()).param("document", documentId)
                    .param("expected", expectedRowVersion).update();
            if (updated != 1) throw versionConflict(documentId, head.currentVersionId(), head.rowVersion());
            appendAudit(identity, "DOCUMENT_CORRECTION_CREATED", "CLINICAL_DOCUMENT", documentId, patientId,
                    UUID.randomUUID().toString());
            appendOutbox(identity.tenantId(), "CLINICAL_DOCUMENT", documentId, expectedRowVersion + 1,
                    "DocumentCorrectionCreated");
            completeCommand(identity, "DOCUMENT_CORRECTION_CREATE", idempotencyKey, correctionId);
            return correction(identity.tenantId(), correctionId);
        });
    }

    List<DocumentCorrectionWire> documentCorrections(
            ClinicalIdentity identity, UUID documentId, UUID patientId, UUID encounterId) {
        long permitted = jdbc.sql("""
                select count(*) from clinical_document where tenant_id = :tenant and document_id = :document
                  and patient_id = :patient and encounter_id = :encounter
                """).param("tenant", identity.tenantId()).param("document", documentId)
                .param("patient", patientId).param("encounter", encounterId).query(Long.class).single();
        if (permitted != 1) throw notFound();
        return jdbc.sql("""
                select correction_id from document_correction_case
                where tenant_id = :tenant and document_id = :document
                order by requested_at desc, correction_id
                """).param("tenant", identity.tenantId()).param("document", documentId)
                .query(UUID.class).list().stream().map(id -> correction(identity.tenantId(), id)).toList();
    }

    SignatureRevocationEvidenceWire revokeSignature(
            ClinicalIdentity identity,
            String idempotencyKey,
            UUID documentId,
            UUID patientId,
            UUID encounterId,
            UUID signatureId,
            long expectedDocumentRowVersion,
            String reason) {
        requireCorrectionReason(reason);
        return transactions.execute(status -> {
            beginCommand(identity, "DOCUMENT_SIGNATURE_REVOKE", idempotencyKey,
                    sha256(documentId + "|" + signatureId + "|" + expectedDocumentRowVersion + "|" + reason.trim()));
            SignatureForRevocation signature = jdbc.sql("""
                    select signature.document_version_id, signature.signer_user_id, signature.signature_status,
                      document.current_version_id, document.row_version
                    from signature_evidence signature
                    join clinical_document document on document.tenant_id = signature.tenant_id
                      and document.document_id = signature.document_id
                    where signature.tenant_id = :tenant and signature.document_id = :document
                      and signature.signature_id = :signature and document.patient_id = :patient
                      and document.encounter_id = :encounter
                    for update of document, signature
                    """).param("tenant", identity.tenantId()).param("document", documentId)
                    .param("signature", signatureId).param("patient", patientId).param("encounter", encounterId)
                    .query((rs, row) -> new SignatureForRevocation(
                            rs.getObject("document_version_id", UUID.class), rs.getObject("signer_user_id", UUID.class),
                            rs.getString("signature_status"), rs.getObject("current_version_id", UUID.class),
                            rs.getLong("row_version"))).optional().orElseThrow(ClinicalLifecycleService::notFound);
            if (signature.rowVersion() != expectedDocumentRowVersion) {
                throw versionConflict(documentId, signature.currentVersionId(), signature.rowVersion());
            }
            if ("REVOKED".equals(signature.status())) {
                throw new ClinicalCommandException("SIGNATURE_ALREADY_REVOKED", 409, "The signature is already revoked");
            }
            if (!signature.signerUserId().equals(identity.userId()) && !hasAdministrativeRole(identity)) {
                throw new ClinicalCommandException("SIGNATURE_REVOCATION_FORBIDDEN", 403,
                        "Only the signer or an authorized medical-records administrator may revoke this signature");
            }
            OffsetDateTime revokedAt = OffsetDateTime.now(java.time.ZoneOffset.UTC);
            UUID revocationId = UUID.randomUUID();
            jdbc.sql("""
                    update signature_evidence set signature_status = 'REVOKED'
                    where tenant_id = :tenant and signature_id = :signature and signature_status <> 'REVOKED'
                    """).param("tenant", identity.tenantId()).param("signature", signatureId).update();
            jdbc.sql("""
                    insert into document_signature_revocation(
                      tenant_id, revocation_id, signature_id, document_id, document_version_id,
                      revocation_reason, revoked_by, revoked_at)
                    values (:tenant, :revocation, :signature, :document, :version, :reason, :actor, :revoked_at)
                    """).param("tenant", identity.tenantId()).param("revocation", revocationId)
                    .param("signature", signatureId).param("document", documentId)
                    .param("version", signature.documentVersionId()).param("reason", reason.trim())
                    .param("actor", identity.userId()).param("revoked_at", revokedAt).update();
            long newRowVersion = expectedDocumentRowVersion;
            if (signature.documentVersionId().equals(signature.currentVersionId())) {
                jdbc.sql("""
                        update clinical_document set status = 'VOID', row_version = row_version + 1, updated_at = :updated
                        where tenant_id = :tenant and document_id = :document and row_version = :expected
                        """).param("updated", revokedAt).param("tenant", identity.tenantId())
                        .param("document", documentId).param("expected", expectedDocumentRowVersion).update();
                newRowVersion++;
            }
            appendAudit(identity, "DOCUMENT_SIGNATURE_REVOKED", "CLINICAL_DOCUMENT", documentId, patientId,
                    UUID.randomUUID().toString());
            appendOutbox(identity.tenantId(), "CLINICAL_DOCUMENT", documentId, newRowVersion,
                    "DocumentSignatureRevoked");
            completeCommand(identity, "DOCUMENT_SIGNATURE_REVOKE", idempotencyKey, revocationId);
            return new SignatureRevocationEvidenceWire(revocationId, signatureId, documentId,
                    signature.documentVersionId(), reason.trim(), identity.userId(), revokedAt.toInstant());
        });
    }

    DocumentCorrectionPropagationWire retryCorrectionPropagation(
            ClinicalIdentity identity,
            String idempotencyKey,
            UUID documentId,
            UUID patientId,
            UUID encounterId,
            UUID propagationId,
            long expectedRowVersion) {
        return transactions.execute(status -> {
            beginCommand(identity, "DOCUMENT_CORRECTION_PROPAGATE", idempotencyKey,
                    sha256(documentId + "|" + propagationId + "|" + expectedRowVersion));
            PropagationHead propagation = jdbc.sql("""
                    select propagation.correction_id, propagation.row_version, correction.status,
                      propagation.destination_code, version.content_hash
                    from document_correction_propagation propagation
                    join document_correction_case correction on correction.tenant_id = propagation.tenant_id
                      and correction.correction_id = propagation.correction_id
                    join clinical_document document on document.tenant_id = correction.tenant_id
                      and document.document_id = correction.document_id
                    join clinical_document_version version on version.tenant_id = correction.tenant_id
                      and version.document_version_id = correction.correction_document_version_id
                    where propagation.tenant_id = :tenant and propagation.propagation_id = :propagation
                      and correction.document_id = :document and document.patient_id = :patient
                      and document.encounter_id = :encounter
                    for update of propagation
                    """).param("tenant", identity.tenantId()).param("propagation", propagationId)
                    .param("document", documentId).param("patient", patientId).param("encounter", encounterId)
                    .query((rs, row) -> new PropagationHead(
                            rs.getObject("correction_id", UUID.class), rs.getLong("row_version"),
                            rs.getString("status"), rs.getString("destination_code"), rs.getString("content_hash")))
                    .optional().orElseThrow(ClinicalLifecycleService::notFound);
            if (propagation.rowVersion() != expectedRowVersion) {
                throw new ClinicalCommandException("VERSION_CONFLICT", 409, "The propagation state changed before retry");
            }
            if (!"SIGNED".equals(propagation.correctionStatus())) {
                throw new ClinicalCommandException("CORRECTION_NOT_SIGNED", 409,
                        "Only a signed correction can be propagated");
            }
            OffsetDateTime attemptedAt = OffsetDateTime.now(java.time.ZoneOffset.UTC);
            CorrectionPropagationProvider.DeliveryReceipt receipt = correctionPropagationProvider.deliver(
                    identity, documentId, propagation.correctionId(), propagationId,
                    propagation.destinationCode(), propagation.contentHash(), attemptedAt.toInstant());
            String nextStatus = receipt.delivered() ? "SUCCEEDED" : "FAILED";
            String eventType = receipt.delivered() ? "PROPAGATION_SUCCEEDED" : "PROPAGATION_FAILED";
            String auditAction = receipt.delivered()
                    ? "DOCUMENT_CORRECTION_PROPAGATION_SUCCEEDED"
                    : "DOCUMENT_CORRECTION_PROPAGATION_FAILED";
            jdbc.sql("""
                    update document_correction_propagation
                    set status = :next_status, attempt_count = attempt_count + 1,
                      last_error_code = :error_code, last_attempt_at = :attempted,
                      delivered_at = :delivered_at, row_version = row_version + 1
                    where tenant_id = :tenant and propagation_id = :propagation and row_version = :expected
                    """).param("next_status", nextStatus)
                    .param("error_code", receipt.errorCode(), Types.VARCHAR)
                    .param("attempted", attemptedAt)
                    .param("delivered_at", receipt.delivered() ? attemptedAt : null, Types.TIMESTAMP_WITH_TIMEZONE)
                    .param("tenant", identity.tenantId())
                    .param("propagation", propagationId).param("expected", expectedRowVersion).update();
            jdbc.sql("""
                    insert into document_correction_event(
                      tenant_id, correction_event_id, correction_id, event_type, actor_user_id, details)
                    values (:tenant, :event, :correction, :event_type, :actor,
                      jsonb_build_object(
                        'propagation_id', :propagation,
                        'provider_code', :provider_code,
                        'receipt_ref', :receipt_ref,
                        'error_code', :error_code,
                        'content_hash', :content_hash))
                    """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                    .param("correction", propagation.correctionId()).param("actor", identity.userId())
                    .param("event_type", eventType).param("propagation", propagationId)
                    .param("provider_code", receipt.providerCode())
                    .param("receipt_ref", receipt.receiptRef(), Types.VARCHAR)
                    .param("error_code", receipt.errorCode(), Types.VARCHAR)
                    .param("content_hash", propagation.contentHash()).update();
            appendAudit(identity, auditAction, "CLINICAL_DOCUMENT", documentId,
                    patientId, UUID.randomUUID().toString());
            appendOutbox(identity.tenantId(), "CLINICAL_DOCUMENT", documentId, expectedRowVersion + 1,
                    receipt.delivered() ? "DocumentCorrectionPropagated" : "DocumentCorrectionPropagationFailed");
            completeCommand(identity, "DOCUMENT_CORRECTION_PROPAGATE", idempotencyKey, propagationId);
            return correctionPropagation(identity.tenantId(), propagationId);
        });
    }

    DocumentVersionWire currentDocument(
            ClinicalIdentity identity, UUID documentId, UUID patientId, UUID encounterId) {
        return jdbc.sql("""
                select document.document_id, document.encounter_id, document.document_type_code,
                  document.row_version, document.template_version_id,
                  template.version_no as template_version_no,
                  version.document_version_id, version.version_no,
                  version.status, version.sections::text, version.content_hash, version.created_at
                from clinical_document document
                join clinical_document_template_version template
                  on template.tenant_id = document.tenant_id
                  and template.template_version_id = document.template_version_id
                join clinical_document_version version
                  on version.tenant_id = document.tenant_id and version.document_id = document.document_id
                  and version.document_version_id = document.current_version_id
                where document.tenant_id = :tenant and document.document_id = :document
                  and document.patient_id = :patient and document.encounter_id = :encounter
                """)
                .param("tenant", identity.tenantId()).param("document", documentId)
                .param("patient", patientId).param("encounter", encounterId)
                .query((rs, row) -> new DocumentVersionWire(
                        rs.getObject("document_id", UUID.class),
                        rs.getObject("document_version_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class),
                        rs.getObject("template_version_id", UUID.class),
                        rs.getInt("template_version_no"),
                        rs.getInt("version_no"),
                        DocumentVersionWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getString("document_type_code"),
                        map(rs.getString("sections")),
                        rs.getString("content_hash"),
                        rs.getLong("row_version"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional().orElseThrow(ClinicalLifecycleService::notFound);
    }

    List<DocumentVersionWire> encounterDocuments(
            ClinicalIdentity identity, UUID patientId, UUID encounterId) {
        return jdbc.sql("""
                select document.document_id, document.encounter_id, document.document_type_code,
                  document.row_version, document.template_version_id,
                  template.version_no as template_version_no,
                  version.document_version_id, version.version_no,
                  version.status, version.sections::text, version.content_hash, version.created_at
                from clinical_document document
                join clinical_document_template_version template
                  on template.tenant_id = document.tenant_id
                  and template.template_version_id = document.template_version_id
                join clinical_document_version version
                  on version.tenant_id = document.tenant_id
                  and version.document_version_id = document.current_version_id
                where document.tenant_id = :tenant and document.patient_id = :patient
                  and document.encounter_id = :encounter
                order by document.updated_at desc, document.document_id
                """)
                .param("tenant", identity.tenantId()).param("patient", patientId)
                .param("encounter", encounterId)
                .query((rs, row) -> documentVersion(rs))
                .list();
    }

    List<DocumentVersionWire> documentVersions(
            ClinicalIdentity identity, UUID documentId, UUID patientId, UUID encounterId) {
        List<DocumentVersionWire> versions = jdbc.sql("""
                select document.document_id, document.encounter_id, document.document_type_code,
                  document.row_version, document.template_version_id,
                  template.version_no as template_version_no,
                  version.document_version_id, version.version_no,
                  version.status, version.sections::text, version.content_hash, version.created_at
                from clinical_document document
                join clinical_document_template_version template
                  on template.tenant_id = document.tenant_id
                  and template.template_version_id = document.template_version_id
                join clinical_document_version version
                  on version.tenant_id = document.tenant_id and version.document_id = document.document_id
                where document.tenant_id = :tenant and document.document_id = :document
                  and document.patient_id = :patient and document.encounter_id = :encounter
                order by version.version_no desc, version.document_version_id
                """)
                .param("tenant", identity.tenantId()).param("document", documentId)
                .param("patient", patientId).param("encounter", encounterId)
                .query((rs, row) -> documentVersion(rs))
                .list();
        if (versions.isEmpty()) throw notFound();
        return versions;
    }

    DocumentDiffWire diff(
            ClinicalIdentity identity,
            UUID documentId,
            UUID patientId,
            UUID encounterId,
            UUID fromVersionId,
            UUID toVersionId) {
        List<VersionSections> versions = jdbc.sql("""
                select document_version_id, sections
                from clinical_document_version version
                where version.tenant_id = :tenant and version.document_id = :document
                  and version.document_version_id in (:from_version, :to_version)
                  and exists (
                    select 1 from clinical_document document
                    where document.tenant_id = version.tenant_id and document.document_id = version.document_id
                      and document.patient_id = :patient and document.encounter_id = :encounter)
                """)
                .param("tenant", identity.tenantId()).param("document", documentId)
                .param("from_version", fromVersionId).param("to_version", toVersionId)
                .param("patient", patientId).param("encounter", encounterId)
                .query((rs, row) -> new VersionSections(
                        rs.getObject("document_version_id", UUID.class), map(rs.getString("sections"))))
                .list();
        Map<String, Object> from = versions.stream().filter(item -> item.id().equals(fromVersionId))
                .findFirst().map(VersionSections::sections).orElseThrow(() -> notFound());
        Map<String, Object> to = versions.stream().filter(item -> item.id().equals(toVersionId))
                .findFirst().map(VersionSections::sections).orElseThrow(() -> notFound());
        Set<String> fields = new LinkedHashSet<>();
        fields.addAll(from.keySet());
        fields.addAll(to.keySet());
        List<String> changed = fields.stream().filter(field -> !java.util.Objects.equals(from.get(field), to.get(field))).toList();
        return new DocumentDiffWire(documentId, fromVersionId, toVersionId, from, to, changed);
    }

    private DocumentVersionWire documentVersion(ResultSet rs) throws SQLException {
        return new DocumentVersionWire(
                rs.getObject("document_id", UUID.class),
                rs.getObject("document_version_id", UUID.class),
                rs.getObject("encounter_id", UUID.class),
                rs.getObject("template_version_id", UUID.class),
                rs.getInt("template_version_no"),
                rs.getInt("version_no"),
                DocumentVersionWire.StatusValue.valueOf(rs.getString("status")),
                rs.getString("document_type_code"),
                map(rs.getString("sections")),
                rs.getString("content_hash"),
                rs.getLong("row_version"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private TemplateBinding resolveTemplate(UUID tenantId, UUID encounterId, String documentTypeCode) {
        return jdbc.sql("""
                select version.template_version_id, version.version_no
                from encounter
                join clinical_document_template template on template.tenant_id = encounter.tenant_id
                  and template.document_type_code = :document_type
                  and template.lifecycle_status = 'ACTIVE'
                  and (template.organization_id is null or template.organization_id = encounter.organization_id)
                  and (template.facility_id is null or template.facility_id = encounter.facility_id)
                  and template.department_id is null
                join clinical_document_template_version version
                  on version.tenant_id = template.tenant_id and version.template_id = template.template_id
                  and version.status = 'PUBLISHED' and version.effective_from <= now()
                  and (version.effective_until is null or version.effective_until > now())
                where encounter.tenant_id = :tenant and encounter.encounter_id = :encounter
                order by (template.facility_id is not null) desc,
                  (template.organization_id is not null) desc, version.version_no desc
                limit 1
                """).param("tenant", tenantId).param("encounter", encounterId)
                .param("document_type", documentTypeCode)
                .query((rs, row) -> new TemplateBinding(
                        rs.getObject("template_version_id", UUID.class), rs.getInt("version_no")))
                .optional().orElseThrow(() -> new ClinicalCommandException(
                        "DOCUMENT_TEMPLATE_NOT_AVAILABLE", 409,
                        "No published document template is available for this encounter scope"));
    }

    private TemplateBinding templateBinding(UUID tenantId, UUID documentId) {
        return jdbc.sql("""
                select document.template_version_id, version.version_no
                from clinical_document document
                join clinical_document_template_version version
                  on version.tenant_id = document.tenant_id
                  and version.template_version_id = document.template_version_id
                where document.tenant_id = :tenant and document.document_id = :document
                """).param("tenant", tenantId).param("document", documentId)
                .query((rs, row) -> new TemplateBinding(
                        rs.getObject("template_version_id", UUID.class), rs.getInt("version_no"))).single();
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        requireText(key, "Idempotency-Key");
        if (key.length() > 128) {
            throw new ClinicalCommandException("INVALID_IDEMPOTENCY_KEY", 400, "Idempotency-Key is too long");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """)
                .param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new ClinicalCommandException("IDEMPOTENCY_REPLAY", 409, "This command key has already been used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID responseId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """)
                .param("resource", responseId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void requireEncounterCreationScope(
            UUID tenantId, UUID organizationId, UUID facilityId, UUID patientId,
            UUID departmentId, UUID responsibleUserId) {
        long validBase = jdbc.sql("""
                select count(*) from patient
                join organization on organization.tenant_id=patient.tenant_id
                  and organization.organization_id=:organization
                  and organization.status='ACTIVE' and organization.effective_from<=now()
                  and (organization.effective_until is null or organization.effective_until>now())
                join facility on facility.tenant_id=organization.tenant_id
                  and facility.organization_id=organization.organization_id
                  and facility.facility_id=:facility
                  and facility.status='ACTIVE' and facility.effective_from<=now()
                  and (facility.effective_until is null or facility.effective_until>now())
                where patient.tenant_id=:tenant and patient.patient_id=:patient
                  and patient.status in ('ACTIVE','PENDING_VERIFICATION')
                """).param("tenant", tenantId).param("organization", organizationId)
                .param("facility", facilityId).param("patient", patientId).query(Long.class).single();
        if (validBase != 1) {
            throw new ClinicalCommandException("ENCOUNTER_SCOPE_INVALID", 409,
                    "The patient, organization or facility is not active in the requested scope");
        }
        if (departmentId != null) {
            long validDepartment = jdbc.sql("""
                    select count(*) from clinical_department
                    where tenant_id=:tenant and facility_id=:facility and department_id=:department
                      and status='ACTIVE' and effective_from<=now()
                      and (effective_until is null or effective_until>now())
                    """).param("tenant", tenantId).param("facility", facilityId)
                    .param("department", departmentId).query(Long.class).single();
            if (validDepartment != 1) {
                throw new ClinicalCommandException("ENCOUNTER_DEPARTMENT_INVALID", 409,
                        "The encounter department is not active in the facility");
            }
        }
        if (responsibleUserId != null) {
            long validResponsible = jdbc.sql("""
                    select count(*) from app_user account
                    where account.tenant_id=:tenant and account.user_id=:user and account.status='ACTIVE'
                      and exists (
                        select 1 from role_assignment assignment
                        where assignment.tenant_id=account.tenant_id and assignment.user_id=account.user_id
                          and assignment.organization_id=:organization
                          and (assignment.facility_id is null or assignment.facility_id=:facility)
                          and assignment.status='ACTIVE' and assignment.valid_from<=now()
                          and (assignment.valid_until is null or assignment.valid_until>now()))
                    """).param("tenant", tenantId).param("user", responsibleUserId)
                    .param("organization", organizationId).param("facility", facilityId)
                    .query(Long.class).single();
            if (validResponsible != 1) {
                throw new ClinicalCommandException("ENCOUNTER_RESPONSIBLE_INVALID", 409,
                        "The responsible clinician has no active assignment in the encounter scope");
            }
        }
    }

    private void setEncounterTransitionContext(UUID actorId, String reason, java.time.Instant occurredAt) {
        jdbc.sql("select set_config('haoemr.encounter_transition_actor', :value, true)")
                .param("value", actorId.toString()).query(String.class).single();
        jdbc.sql("select set_config('haoemr.encounter_transition_reason', :value, true)")
                .param("value", reason == null ? "" : reason).query(String.class).single();
        jdbc.sql("select set_config('haoemr.encounter_transition_time', :value, true)")
                .param("value", occurredAt.toString()).query(String.class).single();
    }

    private static boolean allowedEncounterTransition(String from, String to) {
        return switch (from) {
            case "PLANNED" -> Set.of("ARRIVED", "CANCELLED").contains(to);
            case "ARRIVED" -> Set.of("IN_PROGRESS", "CANCELLED").contains(to);
            case "IN_PROGRESS" -> Set.of("SUSPENDED", "FINISHED").contains(to);
            case "SUSPENDED" -> Set.of("IN_PROGRESS", "CANCELLED").contains(to);
            default -> false;
        };
    }

    private void validateOutpatientClosure(UUID tenantId, UUID patientId, UUID encounterId) {
        long signedDocuments = jdbc.sql("""
                select count(*) from clinical_document
                where tenant_id=:tenant and patient_id=:patient and encounter_id=:encounter
                  and status in ('SIGNED','CORRECTED')
                """).param("tenant", tenantId).param("patient", patientId).param("encounter", encounterId)
                .query(Long.class).single();
        if (signedDocuments < 1) {
            throw new ClinicalCommandException("OUTPATIENT_CLOSURE_DOCUMENT_REQUIRED", 409,
                    "A signed outpatient record is required before the encounter can be finished");
        }
        long confirmedPrimaryDiagnoses = jdbc.sql("""
                select count(*) from clinical_diagnosis diagnosis
                join clinical_diagnosis_version version
                  on version.tenant_id=diagnosis.tenant_id
                 and version.diagnosis_version_id=diagnosis.current_version_id
                where diagnosis.tenant_id=:tenant and diagnosis.patient_id=:patient
                  and diagnosis.encounter_id=:encounter and diagnosis.lifecycle_status='ACTIVE'
                  and diagnosis.current_diagnosis_role='PRIMARY' and version.certainty='CONFIRMED'
                """).param("tenant", tenantId).param("patient", patientId).param("encounter", encounterId)
                .query(Long.class).single();
        if (confirmedPrimaryDiagnoses < 1) {
            throw new ClinicalCommandException("OUTPATIENT_CLOSURE_PRIMARY_DIAGNOSIS_REQUIRED", 409,
                    "A confirmed active primary diagnosis is required before the encounter can be finished");
        }
        long unsignedOrders = jdbc.sql("""
                select count(*) from clinical_order
                where tenant_id=:tenant and patient_id=:patient and encounter_id=:encounter
                  and status in ('DRAFT','VALIDATING')
                """).param("tenant", tenantId).param("patient", patientId).param("encounter", encounterId)
                .query(Long.class).single();
        if (unsignedOrders > 0) {
            throw new ClinicalCommandException("OUTPATIENT_CLOSURE_UNSIGNED_ORDER", 409,
                    "Draft or validating orders must be signed or cancelled before the encounter can be finished");
        }
        long openCriticalValues = jdbc.sql("""
                select count(*) from critical_value_case
                where tenant_id=:tenant and patient_id=:patient and encounter_id=:encounter
                  and state in ('OPEN','ACKNOWLEDGED')
                """).param("tenant", tenantId).param("patient", patientId).param("encounter", encounterId)
                .query(Long.class).single();
        if (openCriticalValues > 0) {
            throw new ClinicalCommandException("OUTPATIENT_CLOSURE_CRITICAL_VALUE_OPEN", 409,
                    "All critical values must be disposed before the encounter can be finished");
        }
    }

    private EncounterWire encounter(UUID tenantId, UUID encounterId, UUID patientId, UUID facilityId) {
        return encounter(tenantId, encounterId, patientId, facilityId, null);
    }

    private EncounterWire encounter(
            UUID tenantId, UUID encounterId, UUID patientId, UUID facilityId, UUID organizationId) {
        String organizationFilter = organizationId == null ? "" : " and organization_id=:organization";
        var query = jdbc.sql("""
                select encounter_id, patient_id, organization_id, facility_id, department_id,
                  responsible_user_id, encounter_type, status, started_at, ended_at,
                  source_system, source_key, row_version
                from encounter
                where tenant_id=:tenant and encounter_id=:encounter
                  and patient_id=:patient and facility_id=:facility
                """ + organizationFilter)
                .param("tenant", tenantId).param("encounter", encounterId)
                .param("patient", patientId).param("facility", facilityId);
        if (organizationId != null) query = query.param("organization", organizationId);
        return query.query((rs, row) -> encounter(rs)).optional()
                .orElseThrow(ClinicalLifecycleService::notFound);
    }

    private EncounterWire encounter(ResultSet rs) throws SQLException {
        OffsetDateTime endedAt = rs.getObject("ended_at", OffsetDateTime.class);
        return new EncounterWire(
                rs.getObject("encounter_id", UUID.class), rs.getObject("patient_id", UUID.class),
                rs.getObject("organization_id", UUID.class), rs.getObject("facility_id", UUID.class),
                rs.getObject("department_id", UUID.class), rs.getObject("responsible_user_id", UUID.class),
                EncounterWire.EncounterTypeValue.valueOf(rs.getString("encounter_type")),
                EncounterWire.StatusValue.valueOf(rs.getString("status")),
                rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                endedAt == null ? null : endedAt.toInstant(), rs.getString("source_system"),
                rs.getString("source_key"), rs.getLong("row_version"));
    }

    private PatientSummaryWire patientSummary(UUID tenantId, UUID patientId) {
        return jdbc.sql("""
                select patient_id, display_name, sex_code, birth_date, row_version from patient
                where tenant_id = :tenant and patient_id = :patient
                """).param("tenant", tenantId).param("patient", patientId)
                .query((rs, row) -> new PatientSummaryWire(rs.getObject("patient_id", UUID.class),
                        rs.getString("display_name"), rs.getString("sex_code"),
                        rs.getObject("birth_date", java.time.LocalDate.class), rs.getLong("row_version")))
                .optional().orElseThrow(ClinicalLifecycleService::notFound);
    }

    private DocumentCorrectionWire correction(UUID tenantId, UUID correctionId) {
        return jdbc.sql("""
                select correction_id, document_id, source_document_version_id,
                  correction_document_version_id, correction_type, correction_reason,
                  status, requested_by, requested_at, signed_at
                from document_correction_case
                where tenant_id = :tenant and correction_id = :correction
                """).param("tenant", tenantId).param("correction", correctionId)
                .query((rs, row) -> new DocumentCorrectionWire(
                        rs.getObject("correction_id", UUID.class), rs.getObject("document_id", UUID.class),
                        rs.getObject("source_document_version_id", UUID.class),
                        rs.getObject("correction_document_version_id", UUID.class),
                        DocumentCorrectionWire.CorrectionTypeValue.valueOf(rs.getString("correction_type")),
                        rs.getString("correction_reason"),
                        DocumentCorrectionWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getObject("requested_by", UUID.class),
                        rs.getObject("requested_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("signed_at", OffsetDateTime.class) == null ? null
                                : rs.getObject("signed_at", OffsetDateTime.class).toInstant(),
                        correctionPropagations(tenantId, correctionId)))
                .optional().orElseThrow(ClinicalLifecycleService::notFound);
    }

    private List<DocumentCorrectionPropagationWire> correctionPropagations(UUID tenantId, UUID correctionId) {
        return jdbc.sql("""
                select propagation_id, destination_code, status, attempt_count, last_error_code,
                  last_attempt_at, delivered_at, row_version, created_at
                from document_correction_propagation
                where tenant_id = :tenant and correction_id = :correction
                order by destination_code, propagation_id
                """).param("tenant", tenantId).param("correction", correctionId)
                .query((rs, row) -> new DocumentCorrectionPropagationWire(
                        rs.getObject("propagation_id", UUID.class), rs.getString("destination_code"),
                        DocumentCorrectionPropagationWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getInt("attempt_count"), rs.getString("last_error_code"),
                        rs.getObject("last_attempt_at", OffsetDateTime.class) == null ? null
                                : rs.getObject("last_attempt_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("delivered_at", OffsetDateTime.class) == null ? null
                                : rs.getObject("delivered_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("row_version"), rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    private DocumentCorrectionPropagationWire correctionPropagation(UUID tenantId, UUID propagationId) {
        return jdbc.sql("""
                select propagation_id, destination_code, status, attempt_count, last_error_code,
                  last_attempt_at, delivered_at, row_version, created_at
                from document_correction_propagation
                where tenant_id = :tenant and propagation_id = :propagation
                """).param("tenant", tenantId).param("propagation", propagationId)
                .query((rs, row) -> new DocumentCorrectionPropagationWire(
                        rs.getObject("propagation_id", UUID.class), rs.getString("destination_code"),
                        DocumentCorrectionPropagationWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getInt("attempt_count"), rs.getString("last_error_code"),
                        rs.getObject("last_attempt_at", OffsetDateTime.class) == null ? null
                                : rs.getObject("last_attempt_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("delivered_at", OffsetDateTime.class) == null ? null
                                : rs.getObject("delivered_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("row_version"), rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional().orElseThrow(ClinicalLifecycleService::notFound);
    }

    private boolean hasAdministrativeRole(ClinicalIdentity identity) {
        return jdbc.sql("""
                select count(*) from role_assignment
                where tenant_id = :tenant and role_assignment_id in (:roles)
                  and user_id = :user and role_code in ('MEDICAL_RECORDS', 'CLINICAL_ADMIN')
                  and status = 'ACTIVE' and valid_from <= now()
                  and (valid_until is null or valid_until > now())
                """).param("tenant", identity.tenantId()).param("roles", identity.roleAssignmentIds())
                .param("user", identity.userId()).query(Long.class).single() > 0;
    }

    private void appendAudit(
            ClinicalIdentity identity,
            String action,
            String resourceType,
            UUID resourceId,
            UUID patientId,
            String traceId) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """)
                .param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String patientHash = patientId == null ? null : sha256(identity.tenantId() + "|" + patientId);
        String eventHash = sha256(String.join("|", identity.tenantId().toString(), auditId.toString(), action,
                resourceId.toString(), traceId, previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, :resource_type, :resource,
                  :patient_hash, :trace, :previous_hash, :event_hash)
                """)
                .param("tenant", identity.tenantId()).param("audit", auditId).param("actor", identity.userId())
                .param("action", action).param("resource_type", resourceType).param("resource", resourceId)
                .param("patient_hash", patientHash).param("trace", traceId).param("previous_hash", previousHash)
                .param("event_hash", eventHash).update();
    }

    private void appendOutbox(UUID tenantId, String aggregateType, UUID aggregateId, long version, String eventType) {
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, :aggregate_type, :aggregate, :version,
                  :event_type, 1, jsonb_build_object('resource_id', :aggregate))
                """)
                .param("tenant", tenantId).param("event", UUID.randomUUID()).param("aggregate_type", aggregateType)
                .param("aggregate", aggregateId).param("version", version).param("event_type", eventType).update();
    }

    private ClinicalCommandException versionConflict(UUID documentId, UUID currentVersionId, long currentRowVersion) {
        String token = UUID.randomUUID().toString();
        return new ClinicalCommandException(
                "VERSION_CONFLICT", 409,
                "The document changed; review the current version before saving again",
                token + ":" + documentId + ":" + currentVersionId + ":" + currentRowVersion);
    }

    private static ClinicalCommandException notFound() {
        return new ClinicalCommandException("CONTEXT_NOT_PERMITTED", 403, "The requested clinical context is not permitted");
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception invalid) {
            throw new ClinicalCommandException("INVALID_DOCUMENT_CONTENT", 400, "Document sections are not valid JSON");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(String json) {
        try {
            return objectMapper.convertValue(objectMapper.readTree(json), Map.class);
        } catch (Exception invalid) {
            throw new IllegalStateException("Stored document JSON is invalid", invalid);
        }
    }

    private static String mask(String value) {
        int visible = Math.min(2, value.length());
        return "*".repeat(Math.max(0, value.length() - visible)) + value.substring(value.length() - visible);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ClinicalCommandException("VALIDATION_FAILED", 400, field + " is required");
        }
    }

    private static void requireCorrectionReason(String reason) {
        if (reason == null || reason.trim().length() < 4 || reason.length() > 2000) {
            throw new ClinicalCommandException("VALIDATION_FAILED", 400,
                    "A correction or revocation reason between 4 and 2000 characters is required");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record DocumentHead(String documentTypeCode, String status, UUID currentVersionId, long rowVersion) {}

    private record VoidableDocument(
            String documentTypeCode,
            String status,
            UUID currentVersionId,
            long rowVersion,
            Map<String, Object> sections) {}

    private record CorrectionHead(
            String documentTypeCode, UUID currentVersionId, long rowVersion,
            String documentStatus, String versionStatus) {}

    private record SignatureForRevocation(
            UUID documentVersionId, UUID signerUserId, String status, UUID currentVersionId, long rowVersion) {}

    private record PropagationHead(
            UUID correctionId,
            long rowVersion,
            String correctionStatus,
            String destinationCode,
            String contentHash) {}

    private record VersionSections(UUID id, Map<String, Object> sections) {}
    private record TemplateBinding(UUID templateVersionId, int versionNo) {}
    private record IdempotencyReplay(String requestHash, String state, UUID resourceId) {}
    private record EncounterHead(String status, long rowVersion, java.time.Instant startedAt, String encounterType) {}
}
