package org.haoemr.clinical;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.haoemr.contracts.DocumentGovernanceSnapshotWire;
import org.haoemr.contracts.DocumentQualityRunEvidenceWire;
import org.haoemr.contracts.EncounterRecordQualityWire;
import org.haoemr.contracts.EncounterRecordQualityItemWire;
import org.haoemr.contracts.QualityFindingWire;
import org.haoemr.contracts.QualityFindingEvidenceWire;
import org.haoemr.contracts.ReviewDecisionEvidenceWire;
import org.haoemr.contracts.SignatureEvidenceWire;
import org.haoemr.contracts.SignatureEvidenceDetailWire;
import org.haoemr.contracts.SignaturePolicyEvidenceWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import tools.jackson.databind.ObjectMapper;

@Service
final class DocumentGovernanceService {

    private static final String RULE_VERSION = "haoemr-core-2";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;
    private final ClinicalSignatureProvider signatures;

    DocumentGovernanceService(
            JdbcClient jdbc,
            TransactionTemplate transactions,
            ObjectMapper objectMapper,
            ApplicationEventPublisher events,
            ClinicalSignatureProvider signatures) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.events = events;
        this.signatures = signatures;
    }

    List<QualityFindingWire> runQualityChecks(
            ClinicalIdentity identity,
            UUID documentId,
            UUID patientId,
            UUID encounterId,
            UUID documentVersionId) {
        return runQualityChecks(identity, documentId, patientId, encounterId, documentVersionId, "MANUAL");
    }

    List<QualityFindingWire> runQualityChecks(
            ClinicalIdentity identity, UUID documentId, UUID patientId, UUID encounterId,
            UUID documentVersionId, String triggerStage) {
        if (!List.of("MANUAL", "POST_SAVE", "PRE_SIGN", "TERMINAL").contains(triggerStage)) {
            throw new ClinicalCommandException("VALIDATION_FAILED", 400, "Unknown quality trigger stage");
        }
        return transactions.execute(status -> {
            VersionForGovernance version = lockCurrentVersion(
                    identity, documentId, patientId, encounterId, documentVersionId);
            Map<String, Object> sections = parse(version.sectionsJson());
            List<FindingSpec> expected = new ArrayList<>();
            for (String field : version.templateRequiredFields()) {
                requireSection(expected, sections, field, templateRequiredRule(field), "BLOCKING",
                        "当前模板必填项未填写：" + field);
            }
            if (!version.templateRequiredFields().contains("assessment")) {
                requireSection(expected, sections, "assessment", "DOC-ASSESSMENT-REQUIRED", "WARNING", "诊断或评估尚未填写");
            }
            if (!version.templateRequiredFields().contains("treatment_plan")) {
                requireSection(expected, sections, "treatment_plan", "DOC-TREATMENT-PLAN-REQUIRED", "WARNING", "治疗与复诊计划尚未填写");
            }
            addIndependentSectionChecks(
                    expected, sections, sourceReferenceCount(identity.tenantId(), documentVersionId));
            String ruleVersion = RULE_VERSION + "+tpl-" + version.templateVersionNo()
                    + "-" + version.templateVersionId().toString().replace("-", "").substring(0, 12);
            // Three equal sections can implicate one target more than once. One
            // rule/target is one finding, in both the result and persisted counts.
            Map<String, FindingSpec> unique = new LinkedHashMap<>();
            expected.forEach(spec -> unique.putIfAbsent(spec.ruleCode(), spec));
            expected = new ArrayList<>(unique.values());
            String sourceWatermark = sourceWatermark(identity.tenantId(), documentVersionId);
            if ("POST_SAVE".equals(triggerStage)) {
                DocumentQualityRunEvidenceWire previous = latestQualityRun(identity.tenantId(), documentId, documentVersionId);
                if (previous != null && previous.findingSnapshots() != null
                        && "POST_SAVE".equals(previous.triggerStage())
                        && previous.ruleVersion().equals(ruleVersion)
                        && previous.contentHash().equals(version.contentHash())
                        && previous.sourceWatermark().equals(sourceWatermark)) {
                    return previous.findingSnapshots();
                }
            }

            List<QualityFindingWire> findings = new ArrayList<>();
            for (FindingSpec spec : expected) {
                UUID findingId = UUID.randomUUID();
                jdbc.sql("""
                        insert into quality_finding(
                          tenant_id, finding_id, document_id, document_version_id, rule_code,
                          rule_version, severity, message, field_path, state)
                        values (:tenant, :finding, :document, :version, :rule, :rule_version,
                          :severity, :message, :field_path, 'OPEN')
                        on conflict (tenant_id, document_version_id, rule_code, rule_version)
                        do update set severity = excluded.severity, message = excluded.message,
                          field_path = excluded.field_path, state = 'OPEN', resolution_reason = null,
                          updated_at = now(), row_version = quality_finding.row_version + 1
                        returning finding_id
                        """)
                        .param("tenant", identity.tenantId()).param("finding", findingId)
                        .param("document", documentId).param("version", documentVersionId)
                        .param("rule", spec.ruleCode()).param("rule_version", ruleVersion)
                        .param("severity", spec.severity()).param("message", spec.message())
                        .param("field_path", "sections." + spec.field()).query(UUID.class).single();
                UUID storedId = jdbc.sql("""
                        select finding_id from quality_finding
                        where tenant_id = :tenant and document_version_id = :version
                          and rule_code = :rule and rule_version = :rule_version
                        """)
                        .param("tenant", identity.tenantId()).param("version", documentVersionId)
                        .param("rule", spec.ruleCode()).param("rule_version", ruleVersion)
                        .query(UUID.class).single();
                findings.add(new QualityFindingWire(
                        storedId,
                        documentVersionId,
                        spec.ruleCode(),
                        QualityFindingWire.SeverityValue.valueOf(spec.severity()),
                        spec.message(),
                        "sections." + spec.field(),
                        QualityFindingWire.StateValue.OPEN));
            }
            resolveNoLongerPresent(identity.tenantId(), documentVersionId, ruleVersion, expected);
            int blockingCount = (int) expected.stream().filter(item -> "BLOCKING".equals(item.severity())).count();
            int warningCount = (int) expected.stream().filter(item -> "WARNING".equals(item.severity())).count();
            String outcome = blockingCount > 0 ? "BLOCKED" : warningCount > 0 ? "WARNING" : "PASSED";
            UUID qualityRunId = UUID.randomUUID();
            OffsetDateTime executedAt = OffsetDateTime.now(ZoneOffset.UTC);
            jdbc.sql("""
                    insert into document_quality_run(
                      tenant_id, quality_run_id, document_id, document_version_id, rule_version,
                      outcome, finding_count, blocking_count, warning_count, content_hash,
                      source_watermark, executed_by, executed_at, trigger_stage, finding_snapshots)
                    values (:tenant, :run, :document, :version, :rule_version,
                      :outcome, :finding_count, :blocking_count, :warning_count, :content_hash,
                      :source_watermark, :executed_by, :executed_at, :trigger_stage, cast(:snapshots as jsonb))
                    """).param("tenant", identity.tenantId()).param("run", qualityRunId)
                    .param("document", documentId).param("version", documentVersionId)
                    .param("rule_version", ruleVersion).param("outcome", outcome)
                    .param("finding_count", expected.size()).param("blocking_count", blockingCount)
                    .param("warning_count", warningCount).param("content_hash", version.contentHash())
                    .param("source_watermark", sourceWatermark)
                    .param("executed_by", identity.userId()).param("executed_at", executedAt)
                    .param("trigger_stage", triggerStage)
                    .param("snapshots", objectMapper.writeValueAsString(findings)).update();
            appendAudit(identity, "DOCUMENT_QUALITY_CHECKED", "CLINICAL_DOCUMENT", documentId, patientId);
            appendQualityRunOutbox(
                    identity.tenantId(), qualityRunId, documentId, documentVersionId, outcome,
                    version.contentHash());
            return List.copyOf(findings);
        });
    }

    EncounterRecordQualityWire encounterQuality(ClinicalIdentity identity, UUID patientId, UUID encounterId) {
        TransactionTemplate reads = new TransactionTemplate(transactions.getTransactionManager());
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return reads.execute(status -> {
        // Bounded, authorized read model; all counts bind the current immutable version.
        List<EncounterRecordQualityItemWire> items = jdbc.sql("""
                select d.document_id, d.current_version_id, d.document_type_code, d.status, v.content_hash,
                  r.outcome, r.content_hash run_content_hash, r.source_watermark,
                  (select count(*) from quality_finding f where f.tenant_id=d.tenant_id
                    and f.document_version_id=d.current_version_id and f.state in ('OPEN','ACKNOWLEDGED')) open_count,
                  (select count(*) from quality_finding f where f.tenant_id=d.tenant_id
                    and f.document_version_id=d.current_version_id and f.state in ('OPEN','ACKNOWLEDGED')
                    and f.severity='BLOCKING') blocking_count
                from clinical_document d
                join clinical_document_version v on v.tenant_id=d.tenant_id
                  and v.document_id=d.document_id and v.document_version_id=d.current_version_id
                left join lateral (select outcome, content_hash, source_watermark from document_quality_run r
                  where r.tenant_id=d.tenant_id and r.document_id=d.document_id
                    and r.document_version_id=d.current_version_id
                  order by executed_at desc, quality_run_id desc limit 1) r on true
                where d.tenant_id=:tenant and d.patient_id=:patient and d.encounter_id=:encounter and d.status<>'VOID'
                order by d.created_at, d.document_id limit 100
                """).param("tenant", identity.tenantId()).param("patient", patientId).param("encounter", encounterId)
                .query((rs, row) -> {
                    UUID versionId = rs.getObject("current_version_id", UUID.class);
                    String outcome = rs.getString("outcome");
                    if (outcome == null) outcome = "NOT_RUN";
                    else if (!rs.getString("content_hash").equals(rs.getString("run_content_hash"))
                            || !sourceWatermark(identity.tenantId(), versionId).equals(rs.getString("source_watermark"))) outcome = "STALE";
                    else if (rs.getInt("blocking_count") > 0) outcome = "BLOCKED";
                    else if (rs.getInt("open_count") > 0 && "PASSED".equals(outcome)) outcome = "WARNING";
                    return new EncounterRecordQualityItemWire(rs.getObject("document_id", UUID.class), versionId,
                            rs.getString("content_hash"), rs.getString("document_type_code"),
                            EncounterRecordQualityItemWire.DocumentStatusValue.valueOf(rs.getString("status")),
                            EncounterRecordQualityItemWire.QualityStateValue.valueOf(outcome),
                            rs.getInt("open_count"), rs.getInt("blocking_count"));
                }).list();
        int total = jdbc.sql("""
                select count(*) from clinical_document where tenant_id=:tenant and patient_id=:patient
                  and encounter_id=:encounter and status<>'VOID'
                """).param("tenant", identity.tenantId()).param("patient", patientId).param("encounter", encounterId)
                .query(Integer.class).single();
        return new EncounterRecordQualityWire(patientId, encounterId, items, total, total > items.size());
        });
    }

    DocumentGovernanceSnapshotWire snapshot(
            ClinicalIdentity identity,
            UUID documentId,
            UUID patientId,
            UUID encounterId,
            UUID documentVersionId) {
        GovernanceHead head = jdbc.sql("""
                select version.status, version.content_hash
                from clinical_document document
                join clinical_document_version version
                  on version.tenant_id = document.tenant_id and version.document_id = document.document_id
                where document.tenant_id = :tenant and document.document_id = :document
                  and document.patient_id = :patient and document.encounter_id = :encounter
                  and version.document_version_id = :version
                """).param("tenant", identity.tenantId()).param("document", documentId)
                .param("patient", patientId).param("encounter", encounterId).param("version", documentVersionId)
                .query((rs, row) -> new GovernanceHead(rs.getString("status"), rs.getString("content_hash")))
                .optional().orElseThrow(() -> new ClinicalCommandException(
                        "CONTEXT_NOT_PERMITTED", 403, "The requested clinical context is not permitted"));

        List<QualityFindingEvidenceWire> findings = jdbc.sql("""
                select finding_id, document_version_id, rule_code, rule_version, severity, message,
                  field_path, state, resolution_reason, row_version, created_at, updated_at
                from quality_finding
                where tenant_id = :tenant and document_id = :document and document_version_id = :version
                order by case severity when 'BLOCKING' then 1 when 'WARNING' then 2 else 3 end,
                  created_at, finding_id
                """).param("tenant", identity.tenantId()).param("document", documentId)
                .param("version", documentVersionId)
                .query((rs, row) -> new QualityFindingEvidenceWire(
                        rs.getObject("finding_id", UUID.class), rs.getObject("document_version_id", UUID.class),
                        rs.getString("rule_code"), rs.getString("rule_version"),
                        QualityFindingEvidenceWire.SeverityValue.valueOf(rs.getString("severity")),
                        rs.getString("message"), rs.getString("field_path"),
                        QualityFindingEvidenceWire.StateValue.valueOf(rs.getString("state")),
                        rs.getString("resolution_reason"), rs.getLong("row_version"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .list();

        DocumentQualityRunEvidenceWire qualityRun = latestQualityRun(
                identity.tenantId(), documentId, documentVersionId);

        List<SignatureEvidenceDetailWire> signatures = jdbc.sql("""
                select signature.signature_id, signature.document_version_id, signature.signer_user_id,
                  signature.signer_display_name as display_name, signature.signature_role, signature.signed_at,
                  signature.content_hash, signature.signature_status, signature.credential_ref
                from signature_evidence signature
                where signature.tenant_id = :tenant and signature.document_id = :document
                  and signature.document_version_id = :version
                order by signature.signed_at, signature.signature_id
                """).param("tenant", identity.tenantId()).param("document", documentId)
                .param("version", documentVersionId)
                .query((rs, row) -> new SignatureEvidenceDetailWire(
                        rs.getObject("signature_id", UUID.class),
                        rs.getObject("document_version_id", UUID.class),
                        rs.getObject("signer_user_id", UUID.class), rs.getString("display_name"),
                        SignatureEvidenceDetailWire.SignatureRoleValue.valueOf(rs.getString("signature_role")),
                        rs.getObject("signed_at", OffsetDateTime.class).toInstant(), rs.getString("content_hash"),
                        SignatureEvidenceDetailWire.SignatureStatusValue.valueOf(rs.getString("signature_status")),
                        rs.getString("credential_ref")))
                .list();

        SignaturePolicyEvidenceWire policy = jdbc.sql("""
                select required_signature_level, current_signature_level, review_status,
                  requires_distinct_signers, row_version
                from document_signature_policy
                where tenant_id = :tenant and document_id = :document and document_version_id = :version
                """).param("tenant", identity.tenantId()).param("document", documentId)
                .param("version", documentVersionId)
                .query((rs, row) -> new SignaturePolicyEvidenceWire(
                        SignaturePolicyEvidenceWire.RequiredSignatureLevelValue.valueOf(
                                rs.getString("required_signature_level")),
                        rs.getString("current_signature_level") == null ? null
                                : SignaturePolicyEvidenceWire.CurrentSignatureLevelValue.valueOf(
                                        rs.getString("current_signature_level")),
                        SignaturePolicyEvidenceWire.ReviewStatusValue.valueOf(rs.getString("review_status")),
                        rs.getBoolean("requires_distinct_signers"), rs.getLong("row_version")))
                .optional().orElse(null);

        List<ReviewDecisionEvidenceWire> decisions = jdbc.sql("""
                select decision.review_decision_id, decision.decision, decision.decision_level,
                  decision.reason, decision.actor_user_id, decision.actor_display_name as display_name,
                  decision.decided_at
                from document_review_decision decision
                where decision.tenant_id = :tenant and decision.document_id = :document
                  and decision.document_version_id = :version
                order by decision.decided_at, decision.review_decision_id
                """).param("tenant", identity.tenantId()).param("document", documentId)
                .param("version", documentVersionId)
                .query((rs, row) -> new ReviewDecisionEvidenceWire(
                        rs.getObject("review_decision_id", UUID.class),
                        ReviewDecisionEvidenceWire.DecisionValue.valueOf(rs.getString("decision")),
                        ReviewDecisionEvidenceWire.DecisionLevelValue.valueOf(rs.getString("decision_level")),
                        rs.getString("reason"), rs.getObject("actor_user_id", UUID.class),
                        rs.getString("display_name"), rs.getObject("decided_at", OffsetDateTime.class).toInstant()))
                .list();

        StringBuilder evidence = new StringBuilder(head.contentHash());
        if (qualityRun != null) {
            evidence.append('|').append(qualityRun.qualityRunId()).append(':').append(qualityRun.outcome());
        }
        findings.forEach(item -> evidence.append('|').append(item.findingId()).append(':').append(item.rowVersion()));
        signatures.forEach(item -> evidence.append('|').append(item.signatureId()).append(':').append(item.signatureStatus()));
        if (policy != null) evidence.append('|').append(policy.reviewStatus()).append(':').append(policy.rowVersion());
        decisions.forEach(item -> evidence.append('|').append(item.reviewDecisionId()).append(':').append(item.decision()));
        return new DocumentGovernanceSnapshotWire(
                qualityRunHistory(identity.tenantId(), documentId, documentVersionId, 20),
                documentId, documentVersionId,
                DocumentGovernanceSnapshotWire.DocumentStatusValue.valueOf(head.status()),
                head.contentHash(), qualityRun, findings, signatures, policy, decisions,
                sha256(evidence.toString()));
    }

    SignatureEvidenceWire sign(
            ClinicalIdentity identity,
            String idempotencyKey,
            UUID documentId,
            UUID patientId,
            UUID encounterId,
            UUID documentVersionId,
            long expectedRowVersion,
            String signatureRole,
            String warningDisposition) {
        if (signatureRole == null || signatureRole.isBlank()) {
            throw new ClinicalCommandException("VALIDATION_FAILED", 400, "signature_role is required");
        }
        return transactions.execute(status -> {
            String normalizedSignatureRole = signatureRole.trim().toUpperCase(java.util.Locale.ROOT);
            beginCommand(identity, idempotencyKey, sha256(documentId + "|" + documentVersionId + "|"
                    + expectedRowVersion + "|" + normalizedSignatureRole));
            VersionForGovernance version = lockCurrentVersion(
                    identity, documentId, patientId, encounterId, documentVersionId);
            if (version.documentRowVersion() != expectedRowVersion) {
                throw new ClinicalCommandException(
                        "VERSION_CONFLICT", 409, "The document changed before signature", UUID.randomUUID().toString());
            }
            if (!"DRAFT".equals(version.documentStatus()) && !"READY_TO_SIGN".equals(version.documentStatus())) {
                throw new ClinicalCommandException("INVALID_DOCUMENT_STATE", 409, "The document is not signable");
            }
            DocumentQualityRunEvidenceWire qualityRun = latestQualityRun(
                    identity.tenantId(), documentId, documentVersionId);
            if (qualityRun == null || !version.contentHash().equals(qualityRun.contentHash())) {
                throw new ClinicalCommandException(
                        "QUALITY_CHECK_REQUIRED", 409,
                        "The current document content must pass a deterministic quality check before signature");
            }
            if (!sourceWatermark(identity.tenantId(), documentVersionId).equals(qualityRun.sourceWatermark())) {
                throw new ClinicalCommandException(
                        "QUALITY_SOURCE_CHECK_REQUIRED", 409,
                        "Referenced clinical facts changed or disappeared; quality checks must run again before signature");
            }
            if (qualityRun.outcome() == DocumentQualityRunEvidenceWire.OutcomeValue.BLOCKED) {
                throw new ClinicalCommandException(
                        "SIGNING_RULE_BLOCKED", 409, "Blocking quality findings must be resolved before signature");
            }
            long blocking = findingCount(identity.tenantId(), documentVersionId, "BLOCKING");
            if (blocking > 0) {
                throw new ClinicalCommandException("SIGNING_RULE_BLOCKED", 409, "Blocking quality findings must be resolved before signature");
            }
            long warnings = findingCount(identity.tenantId(), documentVersionId, "WARNING");
            if (warnings > 0 && (warningDisposition == null || warningDisposition.isBlank())) {
                throw new ClinicalCommandException("WARNING_DISPOSITION_REQUIRED", 409, "Open warnings require a documented disposition");
            }
            if (warnings > 0) {
                jdbc.sql("""
                        update quality_finding set state = 'ACKNOWLEDGED', resolution_reason = :reason,
                          row_version = row_version + 1, updated_at = now()
                        where tenant_id = :tenant and document_version_id = :version
                          and severity = 'WARNING' and state = 'OPEN'
                        """)
                        .param("reason", warningDisposition.trim()).param("tenant", identity.tenantId())
                        .param("version", documentVersionId).update();
            }

            SignaturePolicy policy = lockSignaturePolicy(identity.tenantId(), documentId, documentVersionId);
            boolean finalSignature = true;
            if (policy != null) {
                if ("REJECTED".equals(policy.reviewStatus())) {
                    throw new ClinicalCommandException(
                            "REVIEW_REWRITE_REQUIRED", 409,
                            "The rejected document version must be rewritten and quality checked before signing");
                }
                String expectedLevel = nextSignatureLevel(policy.currentSignatureLevel());
                if (!normalizedSignatureRole.equals(expectedLevel)) {
                    throw new ClinicalCommandException(
                            "SIGNATURE_SEQUENCE_INVALID", 409,
                            "The next required signature level is " + expectedLevel);
                }
                requireAuthorizedSignatureLevel(identity, normalizedSignatureRole);
                if (policy.requiresDistinctSigners() && policy.currentSignatureLevel() != null) {
                    long priorSignatures = jdbc.sql("""
                            select count(*) from signature_evidence
                            where tenant_id = :tenant and document_version_id = :version
                              and signer_user_id = :signer and signature_status <> 'REVOKED'
                            """).param("tenant", identity.tenantId()).param("version", documentVersionId)
                            .param("signer", identity.userId()).query(Long.class).single();
                    if (priorSignatures > 0) {
                        throw new ClinicalCommandException(
                                "SIGNER_SEPARATION_REQUIRED", 403,
                                "A higher review level requires a different authorized signer");
                    }
                }
                finalSignature = normalizedSignatureRole.equals(policy.requiredSignatureLevel());
            }

            OffsetDateTime signedAt = OffsetDateTime.now(ZoneOffset.UTC);
            ClinicalSignatureProvider.SignatureAttestation attestation = signatures.attest(
                    identity, documentId, documentVersionId, normalizedSignatureRole,
                    version.contentHash(), signedAt.toInstant());
            String targetStatus = finalSignature && attestation.valid() ? "SIGNED" : "READY_TO_SIGN";
            int versionUpdated = jdbc.sql("""
                    update clinical_document_version
                    set status = :target_status,
                      signed_at = case when :target_status = 'SIGNED' then :signed else null end
                    where tenant_id = :tenant and document_id = :document
                      and document_version_id = :version and status in ('DRAFT', 'READY_TO_SIGN')
                    """).param("target_status", targetStatus)
                    .param("signed", signedAt).param("tenant", identity.tenantId()).param("document", documentId)
                    .param("version", documentVersionId).update();
            if (versionUpdated != 1) {
                throw new ClinicalCommandException("VERSION_CONFLICT", 409, "The document changed before signature");
            }
            int documentUpdated = jdbc.sql("""
                    update clinical_document
                    set status = :target_status, row_version = row_version + 1, updated_at = :signed
                    where tenant_id = :tenant and document_id = :document
                      and current_version_id = :version and row_version = :expected
                    """).param("target_status", targetStatus)
                    .param("signed", signedAt).param("tenant", identity.tenantId()).param("document", documentId)
                    .param("version", documentVersionId).param("expected", expectedRowVersion).update();
            if (documentUpdated != 1) {
                throw new ClinicalCommandException("VERSION_CONFLICT", 409, "The document changed before signature");
            }
            UUID signatureId = UUID.randomUUID();
            jdbc.sql("""
                    insert into signature_evidence(
                      tenant_id, signature_id, document_id, document_version_id, signer_user_id,
                      signature_role, signature_status, content_hash, credential_ref, signed_at)
                    values (:tenant, :signature, :document, :version, :signer,
                      :role, :signature_status, :hash, :credential_ref, :signed)
                    """)
                    .param("tenant", identity.tenantId()).param("signature", signatureId).param("document", documentId)
                    .param("version", documentVersionId).param("signer", identity.userId())
                    .param("role", normalizedSignatureRole).param("signature_status", attestation.status())
                    .param("hash", version.contentHash()).param("credential_ref", attestation.credentialRef())
                    .param("signed", signedAt).update();
            if (policy != null) {
                jdbc.sql("""
                        update document_signature_policy
                        set current_signature_level = :current_level,
                          review_status = :review_status, row_version = row_version + 1, updated_at = now()
                        where tenant_id = :tenant and document_id = :document
                          and document_version_id = :version and row_version = :expected_policy_version
                        """).param("current_level", normalizedSignatureRole)
                        .param("review_status", finalSignature && attestation.valid() ? "COMPLETED" : "IN_REVIEW")
                        .param("tenant", identity.tenantId()).param("document", documentId)
                        .param("version", documentVersionId).param("expected_policy_version", policy.rowVersion()).update();
            }
            String action = finalSignature && attestation.valid()
                    ? "DOCUMENT_SIGNED" : "DOCUMENT_SIGNATURE_STEP_RECORDED";
            appendAudit(identity, action, "CLINICAL_DOCUMENT", documentId, patientId);
            appendSignatureOutbox(
                    identity.tenantId(), documentId, expectedRowVersion + 1, documentVersionId,
                    finalSignature && attestation.valid()
                            ? "DocumentSigned" : "DocumentSignatureStepRecorded",
                    normalizedSignatureRole);
            completeCommand(identity, idempotencyKey, signatureId);
            if (finalSignature && attestation.valid()) {
                finalizeCorrectionIfPresent(identity, documentId, documentVersionId, signedAt);
                events.publishEvent(new ClinicalDocumentSigned(
                        identity.tenantId(), identity.userId(), patientId, encounterId,
                        documentId, documentVersionId, signedAt.toInstant()));
            }
            return new SignatureEvidenceWire(
                    signatureId, documentVersionId, identity.userId(), signedAt.toInstant(), version.contentHash(),
                    SignatureEvidenceWire.SignatureStatusValue.valueOf(attestation.status()));
        });
    }

    private void finalizeCorrectionIfPresent(
            ClinicalIdentity identity, UUID documentId, UUID documentVersionId, OffsetDateTime signedAt) {
        UUID correctionId = jdbc.sql("""
                update document_correction_case
                set status = 'SIGNED', signed_at = :signed_at
                where tenant_id = :tenant and document_id = :document
                  and correction_document_version_id = :version and status = 'DRAFT'
                returning correction_id
                """).param("signed_at", signedAt).param("tenant", identity.tenantId())
                .param("document", documentId).param("version", documentVersionId)
                .query(UUID.class).optional().orElse(null);
        if (correctionId == null) return;
        jdbc.sql("""
                insert into document_correction_event(
                  tenant_id, correction_event_id, correction_id, event_type, actor_user_id,
                  details, occurred_at)
                values (:tenant, :event, :correction, 'CORRECTION_SIGNED', :actor,
                  jsonb_build_object('document_version_id', :version), :occurred_at)
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("correction", correctionId).param("actor", identity.userId())
                .param("version", documentVersionId).param("occurred_at", signedAt).update();
        jdbc.sql("""
                insert into document_correction_propagation(
                  tenant_id, propagation_id, correction_id, destination_code, status)
                values (:tenant, :propagation, :correction, 'EXTERNAL_SHARED_RECORD', 'PENDING')
                on conflict (tenant_id, correction_id, destination_code) do nothing
                """).param("tenant", identity.tenantId()).param("propagation", UUID.randomUUID())
                .param("correction", correctionId).update();
    }

    void rejectReview(
            ClinicalIdentity identity,
            String idempotencyKey,
            UUID documentId,
            UUID patientId,
            UUID encounterId,
            UUID documentVersionId,
            long expectedRowVersion,
            String rejectionLevel,
            String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 1000) {
            throw new ClinicalCommandException("VALIDATION_FAILED", 400, "A rejection reason is required");
        }
        String normalizedLevel = rejectionLevel == null
                ? ""
                : rejectionLevel.trim().toUpperCase(java.util.Locale.ROOT);
        transactions.executeWithoutResult(status -> {
            beginReviewRejectCommand(
                    identity, idempotencyKey,
                    sha256(documentId + "|" + documentVersionId + "|" + expectedRowVersion + "|"
                            + normalizedLevel + "|" + reason.trim()));
            VersionForGovernance version = lockCurrentVersion(
                    identity, documentId, patientId, encounterId, documentVersionId);
            if (version.documentRowVersion() != expectedRowVersion) {
                throw new ClinicalCommandException(
                        "VERSION_CONFLICT", 409, "The document changed before review rejection");
            }
            if (!"READY_TO_SIGN".equals(version.documentStatus())) {
                throw new ClinicalCommandException(
                        "INVALID_DOCUMENT_STATE", 409, "Only a document under review can be rejected");
            }
            SignaturePolicy policy = lockSignaturePolicy(identity.tenantId(), documentId, documentVersionId);
            if (policy == null || !"IN_REVIEW".equals(policy.reviewStatus())) {
                throw new ClinicalCommandException(
                        "REVIEW_NOT_ACTIVE", 409, "No active multi-level review exists for this document version");
            }
            String expectedLevel = nextSignatureLevel(policy.currentSignatureLevel());
            if (!normalizedLevel.equals(expectedLevel)) {
                throw new ClinicalCommandException(
                        "SIGNATURE_SEQUENCE_INVALID", 409,
                        "Only the next required reviewer " + expectedLevel + " can reject this version");
            }
            requireAuthorizedSignatureLevel(identity, normalizedLevel);
            UUID decisionId = UUID.randomUUID();
            jdbc.sql("""
                    insert into document_review_decision(
                      tenant_id, review_decision_id, document_id, document_version_id,
                      decision, decision_level, reason, actor_user_id)
                    values (:tenant, :decision, :document, :version,
                      'REJECTED', :level, :reason, :actor)
                    """).param("tenant", identity.tenantId()).param("decision", decisionId)
                    .param("document", documentId).param("version", documentVersionId)
                    .param("level", normalizedLevel).param("reason", reason.trim())
                    .param("actor", identity.userId()).update();
            jdbc.sql("""
                    update signature_evidence
                    set signature_status = 'REVOKED'
                    where tenant_id = :tenant and document_version_id = :version
                      and signature_status <> 'REVOKED'
                    """).param("tenant", identity.tenantId()).param("version", documentVersionId).update();
            jdbc.sql("""
                    update document_signature_policy
                    set review_status = 'REJECTED', row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and document_id = :document
                      and document_version_id = :version and row_version = :expected_policy_version
                    """).param("tenant", identity.tenantId()).param("document", documentId)
                    .param("version", documentVersionId).param("expected_policy_version", policy.rowVersion()).update();
            jdbc.sql("""
                    update clinical_document_version set status = 'DRAFT', signed_at = null
                    where tenant_id = :tenant and document_id = :document
                      and document_version_id = :version and status = 'READY_TO_SIGN'
                    """).param("tenant", identity.tenantId()).param("document", documentId)
                    .param("version", documentVersionId).update();
            int updated = jdbc.sql("""
                    update clinical_document
                    set status = 'DRAFT', row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and document_id = :document
                      and current_version_id = :version and row_version = :expected
                      and status = 'READY_TO_SIGN'
                    """).param("tenant", identity.tenantId()).param("document", documentId)
                    .param("version", documentVersionId).param("expected", expectedRowVersion).update();
            if (updated != 1) {
                throw new ClinicalCommandException("VERSION_CONFLICT", 409, "The document changed before review rejection");
            }
            appendAudit(identity, "DOCUMENT_REVIEW_REJECTED", "CLINICAL_DOCUMENT", documentId, patientId);
            appendSignatureOutbox(
                    identity.tenantId(), documentId, expectedRowVersion + 1, documentVersionId,
                    "DocumentReviewRejected", normalizedLevel);
            completeReviewRejectCommand(identity, idempotencyKey, decisionId);
        });
    }

    private VersionForGovernance lockCurrentVersion(
            ClinicalIdentity identity,
            UUID documentId,
            UUID patientId,
            UUID encounterId,
            UUID versionId) {
        return jdbc.sql("""
                select document.status as document_status, document.row_version as document_row_version,
                  version.sections::text as sections, version.content_hash,
                  template_version.template_version_id, template_version.version_no,
                  case
                    when cardinality(template_version.required_fields)=0
                      and template_version.display_rules @> '{"legacy_baseline":true}'::jsonb
                    then array['chief_complaint','present_illness']::text[]
                    else template_version.required_fields
                  end as required_fields
                from clinical_document document
                join clinical_document_version version
                  on version.tenant_id = document.tenant_id and version.document_id = document.document_id
                  and version.document_version_id = document.current_version_id
                join clinical_document_template_version template_version
                  on template_version.tenant_id = document.tenant_id
                  and template_version.template_version_id = document.template_version_id
                where document.tenant_id = :tenant and document.document_id = :document
                  and document.patient_id = :patient and document.encounter_id = :encounter
                  and version.document_version_id = :version
                for update of document, version
                """)
                .param("tenant", identity.tenantId()).param("document", documentId).param("patient", patientId)
                .param("encounter", encounterId).param("version", versionId)
                .query((rs, row) -> new VersionForGovernance(
                        rs.getString("document_status"), rs.getLong("document_row_version"),
                        rs.getString("sections"), rs.getString("content_hash"),
                        rs.getObject("template_version_id", UUID.class), rs.getLong("version_no"),
                        List.of((String[]) rs.getArray("required_fields").getArray())))
                .optional().orElseThrow(() -> new ClinicalCommandException(
                        "CONTEXT_NOT_PERMITTED", 403, "The requested clinical context is not permitted"));
    }

    private long findingCount(UUID tenantId, UUID versionId, String severity) {
        return jdbc.sql("""
                select count(*) from quality_finding
                where tenant_id = :tenant and document_version_id = :version
                  and severity = :severity and state = 'OPEN'
                """)
                .param("tenant", tenantId).param("version", versionId).param("severity", severity)
                .query(Long.class).single();
    }

    private DocumentQualityRunEvidenceWire latestQualityRun(
            UUID tenantId, UUID documentId, UUID documentVersionId) {
        return qualityRunHistory(tenantId, documentId, documentVersionId, 1).stream().findFirst().orElse(null);
    }

    private List<DocumentQualityRunEvidenceWire> qualityRunHistory(
            UUID tenantId, UUID documentId, UUID documentVersionId, int limit) {
        return jdbc.sql("""
                select quality_run_id, document_version_id, rule_version, outcome,
                  finding_count, blocking_count, warning_count, content_hash, source_watermark,
                  executed_by, executed_at, trigger_stage, finding_snapshots::text
                from document_quality_run
                where tenant_id = :tenant and document_id = :document
                  and document_version_id = :version
                order by executed_at desc, quality_run_id desc
                limit :limit
                """).param("tenant", tenantId).param("document", documentId)
                .param("version", documentVersionId).param("limit", limit)
                .query((rs, row) -> new DocumentQualityRunEvidenceWire(
                        rs.getString("trigger_stage"),
                        rs.getString("finding_snapshots") == null ? null
                            : List.of(objectMapper.readValue(rs.getString("finding_snapshots"), QualityFindingWire[].class)),
                        rs.getObject("quality_run_id", UUID.class),
                        rs.getObject("document_version_id", UUID.class), rs.getString("rule_version"),
                        DocumentQualityRunEvidenceWire.OutcomeValue.valueOf(rs.getString("outcome")),
                        rs.getInt("finding_count"), rs.getInt("blocking_count"), rs.getInt("warning_count"),
                        rs.getString("content_hash"), rs.getString("source_watermark"),
                        rs.getObject("executed_by", UUID.class),
                        rs.getObject("executed_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    private String sourceWatermark(UUID tenantId, UUID documentVersionId) {
        List<String> sources = jdbc.sql("""
                select source_type, source_resource_id, source_version_ref,
                  coalesce((select string_agg(event_type||':'||reason||':'
                    ||coalesce(effective_target_field_path,'')||':'||coalesce(effective_excerpt_hash,''), '|'
                    order by occurred_at,lifecycle_event_id)
                    from clinical_document_evidence_lifecycle_event lifecycle
                    where lifecycle.tenant_id=reference.tenant_id
                      and ((lifecycle.evidence_type='SOURCE_REFERENCE'
                            and lifecycle.evidence_id=reference.source_reference_id)
                        or (reference.source_type='ATTACHMENT' and lifecycle.evidence_type='ATTACHMENT'
                            and lifecycle.evidence_id=reference.source_resource_id))), '') lifecycle_state
                from clinical_document_source_reference reference
                where tenant_id = :tenant and document_version_id = :version
                  and not exists (
                    select 1 from clinical_document_evidence_lifecycle_event terminal
                    where terminal.tenant_id=reference.tenant_id
                      and terminal.evidence_type='SOURCE_REFERENCE'
                      and terminal.evidence_id=reference.source_reference_id
                      and terminal.event_type in ('REVOKED','SUPERSEDED','VOIDED')
                  )
                  and not (reference.source_type='ATTACHMENT' and exists (
                    select 1 from clinical_document_evidence_lifecycle_event terminal_attachment
                    where terminal_attachment.tenant_id=reference.tenant_id
                      and terminal_attachment.evidence_type='ATTACHMENT'
                      and terminal_attachment.evidence_id=reference.source_resource_id
                      and terminal_attachment.event_type in ('REVOKED','SUPERSEDED','VOIDED')
                  ))
                order by source_type, source_resource_id, source_version_ref, source_reference_id
                """).param("tenant", tenantId).param("version", documentVersionId)
                .query((rs, row) -> {
                    String type = rs.getString("source_type");
                    UUID resourceId = rs.getObject("source_resource_id", UUID.class);
                    String captured = rs.getString("source_version_ref");
                    String current = currentSourceVersionRef(tenantId, type, resourceId);
                    return type + "|" + resourceId + "|" + captured + "|"
                            + (current == null ? "MISSING" : current) + "|" + rs.getString("lifecycle_state");
                }).list();
        return sha256(String.join("\n", sources));
    }

    private String currentSourceVersionRef(UUID tenantId, String type, UUID resourceId) {
        String sql = switch (type) {
            case "DIAGNOSIS" -> "select current_version_id::text from clinical_diagnosis where tenant_id=:tenant and diagnosis_id=:resource";
            case "ORDER" -> "select 'row-'||row_version from clinical_order where tenant_id=:tenant and order_id=:resource";
            case "RESULT" -> "select current_version_id::text from clinical_result where tenant_id=:tenant and result_id=:resource";
            case "ATTACHMENT" -> "select content_hash from clinical_document_attachment where tenant_id=:tenant and attachment_id=:resource and storage_status='AVAILABLE'";
            default -> null;
        };
        if (sql == null) return null;
        return jdbc.sql(sql).param("tenant", tenantId).param("resource", resourceId)
                .query(String.class).optional().orElse(null);
    }

    private void resolveNoLongerPresent(
            UUID tenantId, UUID versionId, String ruleVersion, List<FindingSpec> expected) {
        List<String> currentRules = expected.stream().map(FindingSpec::ruleCode).toList();
        if (currentRules.isEmpty()) {
            jdbc.sql("""
                    update quality_finding set state = 'RESOLVED', resolution_reason = 'Rule no longer triggered',
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and document_version_id = :version
                      and rule_version = :rule_version and state = 'OPEN'
                    """)
                    .param("tenant", tenantId).param("version", versionId).param("rule_version", ruleVersion).update();
            return;
        }
        jdbc.sql("""
                update quality_finding set state = 'RESOLVED', resolution_reason = 'Rule no longer triggered',
                  row_version = row_version + 1, updated_at = now()
                where tenant_id = :tenant and document_version_id = :version
                  and rule_version = :rule_version and state = 'OPEN'
                  and rule_code not in (:current_rules)
                """)
                .param("tenant", tenantId).param("version", versionId).param("rule_version", ruleVersion)
                .param("current_rules", currentRules).update();
    }

    private static String templateRequiredRule(String field) {
        if ("chief_complaint".equals(field)) return "DOC-CHIEF-COMPLAINT-REQUIRED";
        if ("present_illness".equals(field)) return "DOC-PRESENT-ILLNESS-REQUIRED";
        return "TPL-REQUIRED-" + sha256(field).substring(0, 20).toUpperCase();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) {
        try {
            return objectMapper.convertValue(objectMapper.readTree(json), Map.class);
        } catch (Exception invalid) {
            throw new IllegalStateException("Stored document JSON is invalid", invalid);
        }
    }

    private static void requireSection(
            List<FindingSpec> findings,
            Map<String, Object> sections,
            String field,
            String rule,
            String severity,
            String message) {
        Object value = sections.get(field);
        if (value == null || (value instanceof String text && text.isBlank())) {
            findings.add(new FindingSpec(field, rule, severity, message));
        }
    }

    private static void addIndependentSectionChecks(
            List<FindingSpec> findings, Map<String, Object> sections, long sourceReferenceCount) {
        List<String> clinicalFields = List.of(
                "chief_complaint", "present_illness", "past_history", "allergy_history",
                "physical_exam", "auxiliary_exam", "assessment", "treatment_plan", "followup_plan");
        for (int left = 0; left < clinicalFields.size(); left++) {
            String leftField = clinicalFields.get(left);
            String leftValue = normalizedSection(sections.get(leftField));
            if (leftValue.length() < 8) continue;
            for (int right = left + 1; right < clinicalFields.size(); right++) {
                String rightField = clinicalFields.get(right);
                if (leftValue.equals(normalizedSection(sections.get(rightField)))) {
                    findings.add(new FindingSpec(
                            rightField,
                            "DOC-SECTION-DUPLICATE-" + rightField.replace('_', '-').toUpperCase(java.util.Locale.ROOT),
                            "BLOCKING",
                            "不同病历章节内容完全相同，请核对是否误复制：" + leftField + " / " + rightField));
                }
            }
        }
        if (!normalizedSection(sections.get("auxiliary_exam")).isEmpty() && sourceReferenceCount == 0) {
            findings.add(new FindingSpec(
                    "auxiliary_exam", "DOC-AUXILIARY-EXAM-SOURCE-REQUIRED", "WARNING",
                    "辅助检查已录入，但当前版本未绑定 LIS/PACS 或其他来源证据"));
        }
    }

    private long sourceReferenceCount(UUID tenantId, UUID versionId) {
        return jdbc.sql("""
                select count(*) from clinical_document_source_reference
                where tenant_id = :tenant and document_version_id = :version
                """).param("tenant", tenantId).param("version", versionId)
                .query(Long.class).single();
    }

    private static String normalizedSection(Object value) {
        return value instanceof String text
                ? text.replaceAll("\\s+", "").trim()
                : "";
    }

    private void beginCommand(ClinicalIdentity identity, String key, String requestHash) {
        if (key == null || key.isBlank()) {
            throw new ClinicalCommandException("VALIDATION_FAILED", 400, "Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, 'DOCUMENT_SIGN', :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """)
                .param("tenant", identity.tenantId()).param("key", key).param("hash", requestHash)
                .param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new ClinicalCommandException("IDEMPOTENCY_REPLAY", 409, "This signature command has already been used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String key, UUID signatureId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 201,
                  response_ref = jsonb_build_object('signature_id', :signature)
                where tenant_id = :tenant and command_scope = 'DOCUMENT_SIGN' and idempotency_key = :key
                """)
                .param("signature", signatureId).param("tenant", identity.tenantId()).param("key", key).update();
    }

    private void beginReviewRejectCommand(ClinicalIdentity identity, String key, String requestHash) {
        if (key == null || key.isBlank()) {
            throw new ClinicalCommandException("VALIDATION_FAILED", 400, "Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, 'DOCUMENT_REVIEW_REJECT', :key, :hash, 'IN_PROGRESS', :trace,
                  now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("key", key).param("hash", requestHash)
                .param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new ClinicalCommandException("IDEMPOTENCY_REPLAY", 409, "This review rejection was already used");
        }
    }

    private void completeReviewRejectCommand(ClinicalIdentity identity, String key, UUID decisionId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 204,
                  response_ref = jsonb_build_object('review_decision_id', :decision)
                where tenant_id = :tenant and command_scope = 'DOCUMENT_REVIEW_REJECT'
                  and idempotency_key = :key
                """).param("decision", decisionId).param("tenant", identity.tenantId()).param("key", key).update();
    }

    private void appendAudit(
            ClinicalIdentity identity, String action, String resourceType, UUID resourceId, UUID patientId) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """)
                .param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(String.join("|", identity.tenantId().toString(), auditId.toString(), action,
                resourceId.toString(), trace, previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, :resource_type, :resource,
                  :patient_hash, :trace, :previous_hash, :event_hash)
                """)
                .param("tenant", identity.tenantId()).param("audit", auditId).param("actor", identity.userId())
                .param("action", action).param("resource_type", resourceType).param("resource", resourceId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId)).param("trace", trace)
                .param("previous_hash", previousHash).param("event_hash", eventHash).update();
    }

    private SignaturePolicy lockSignaturePolicy(UUID tenantId, UUID documentId, UUID documentVersionId) {
        return jdbc.sql("""
                select required_signature_level, current_signature_level,
                  review_status, requires_distinct_signers, row_version
                from document_signature_policy
                where tenant_id = :tenant and document_id = :document
                  and document_version_id = :version
                for update
                """).param("tenant", tenantId).param("document", documentId).param("version", documentVersionId)
                .query((rs, row) -> new SignaturePolicy(
                        rs.getString("required_signature_level"), rs.getString("current_signature_level"),
                        rs.getString("review_status"), rs.getBoolean("requires_distinct_signers"),
                        rs.getLong("row_version"))).optional().orElse(null);
    }

    private void requireAuthorizedSignatureLevel(ClinicalIdentity identity, String signatureLevel) {
        if ("AUTHOR".equals(signatureLevel)) return;
        List<String> allowedRoleCodes = switch (signatureLevel) {
            case "ATTENDING" -> List.of("ATTENDING_PHYSICIAN", "CHIEF_PHYSICIAN", "CLINICAL_ADMIN");
            case "CHIEF" -> List.of("CHIEF_PHYSICIAN", "CLINICAL_ADMIN");
            case "MEDICAL_RECORDS" -> List.of("MEDICAL_RECORDS", "CLINICAL_ADMIN");
            default -> List.of();
        };
        if (identity.roleAssignmentIds().isEmpty()) {
            throw new ClinicalCommandException("SIGNATURE_LEVEL_NOT_AUTHORIZED", 403, "No active reviewer role is present");
        }
        long authorized = jdbc.sql("""
                select count(*) from role_assignment
                where tenant_id = :tenant and user_id = :user
                  and role_assignment_id in (:role_assignments)
                  and role_code in (:role_codes) and status = 'ACTIVE'
                  and valid_from <= now() and (valid_until is null or valid_until > now())
                """).param("tenant", identity.tenantId()).param("user", identity.userId())
                .param("role_assignments", identity.roleAssignmentIds()).param("role_codes", allowedRoleCodes)
                .query(Long.class).single();
        if (authorized == 0) {
            throw new ClinicalCommandException(
                    "SIGNATURE_LEVEL_NOT_AUTHORIZED", 403,
                    "The active role assignment does not authorize " + signatureLevel + " review");
        }
    }

    private static String nextSignatureLevel(String currentLevel) {
        if (currentLevel == null) return "AUTHOR";
        return switch (currentLevel) {
            case "AUTHOR" -> "ATTENDING";
            case "ATTENDING" -> "CHIEF";
            case "CHIEF" -> "MEDICAL_RECORDS";
            default -> throw new ClinicalCommandException(
                    "SIGNATURE_SEQUENCE_COMPLETE", 409, "The required signature sequence is already complete");
        };
    }

    private void appendSignatureOutbox(
            UUID tenantId,
            UUID documentId,
            long version,
            UUID documentVersionId,
            String eventType,
            String signatureLevel) {
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'CLINICAL_DOCUMENT', :document, :version,
                  :event_type, 1, jsonb_build_object(
                    'document_version_id', :document_version, 'signature_level', :signature_level))
                """)
                .param("tenant", tenantId).param("event", UUID.randomUUID()).param("document", documentId)
                .param("version", version).param("event_type", eventType)
                .param("document_version", documentVersionId).param("signature_level", signatureLevel).update();
    }

    private void appendQualityRunOutbox(
            UUID tenantId,
            UUID qualityRunId,
            UUID documentId,
            UUID documentVersionId,
            String outcome,
            String contentHash) {
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'DOCUMENT_QUALITY_RUN', :run, 1,
                  'DocumentQualityChecked', 1, jsonb_build_object(
                    'document_id', :document, 'document_version_id', :document_version,
                    'outcome', :outcome, 'content_hash', :content_hash))
                """).param("tenant", tenantId).param("event", UUID.randomUUID()).param("run", qualityRunId)
                .param("document", documentId).param("document_version", documentVersionId)
                .param("outcome", outcome).param("content_hash", contentHash).update();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record FindingSpec(String field, String ruleCode, String severity, String message) {}

    private record VersionForGovernance(
            String documentStatus,
            long documentRowVersion,
            String sectionsJson,
            String contentHash,
            UUID templateVersionId,
            long templateVersionNo,
            List<String> templateRequiredFields) {}

    private record SignaturePolicy(
            String requiredSignatureLevel,
            String currentSignatureLevel,
            String reviewStatus,
            boolean requiresDistinctSigners,
            long rowVersion) {}

    private record GovernanceHead(String status, String contentHash) {}
}
