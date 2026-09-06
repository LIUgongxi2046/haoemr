package org.haoemr.archive;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.haoemr.contracts.ArchiveBlockerWire;
import org.haoemr.contracts.ArchiveCaseItemWire;
import org.haoemr.contracts.ArchiveCaseWire;
import org.haoemr.contracts.ArchiveCreateRequestWire;
import org.haoemr.contracts.ArchiveEventWire;
import org.haoemr.contracts.ArchiveExportCreateRequestWire;
import org.haoemr.contracts.ArchiveExportPackageWire;
import org.haoemr.contracts.ArchiveReadinessWire;
import org.haoemr.contracts.ArchiveTransitionRequestWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
final class ArchiveService {
    private static final List<String> ARCHIVE_ROLES = List.of("MEDICAL_RECORDS", "CLINICAL_ADMIN");
    private static final String EXPORT_GENERATOR = "haoemr-archive-json/1";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    ArchiveService(JdbcClient jdbc, TransactionTemplate transactions, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    ArchiveReadinessWire readiness(
            ClinicalIdentity identity, UUID patientId, UUID encounterId, UUID facilityId) {
        EncounterHead encounter = requireEncounter(identity.tenantId(), patientId, encounterId, facilityId);
        List<ArchiveBlockerWire> blockers = blockers(identity.tenantId(), encounterId, encounter.status());
        int count = documentCount(identity.tenantId(), encounterId);
        ArchiveCaseWire existing = findByEncounter(identity.tenantId(), patientId, encounterId, facilityId);
        return new ArchiveReadinessWire(
                patientId, encounterId,
                ArchiveReadinessWire.EncounterStatusValue.valueOf(encounter.status()),
                count, blockers.isEmpty() && existing == null, blockers, existing);
    }

    ArchiveCaseWire create(
            ClinicalIdentity identity, String idempotencyKey, ArchiveCreateRequestWire command) {
        requireRole(identity, command.facilityId(), ARCHIVE_ROLES, "ARCHIVE_ROLE_REQUIRED");
        return transactions.execute(status -> {
            EncounterHead encounter = lockEncounter(
                    identity.tenantId(), command.patientId(), command.encounterId(), command.facilityId());
            List<ArchiveBlockerWire> blockers = blockers(
                    identity.tenantId(), command.encounterId(), encounter.status());
            if (!blockers.isEmpty()) {
                throw new ArchiveException(
                        "ARCHIVE_NOT_READY", 409,
                        "The encounter has " + blockers.size() + " unresolved archive blocker(s)");
            }
            if (findByEncounter(identity.tenantId(), command.patientId(), command.encounterId(), command.facilityId()) != null) {
                throw new ArchiveException("ARCHIVE_ALREADY_EXISTS", 409, "This encounter already has an archive case");
            }
            List<EligibleDocument> documents = eligibleDocuments(identity.tenantId(), command.encounterId());
            String requestHash = sha256(command.patientId() + "|" + command.encounterId() + "|"
                    + documents.stream().map(item -> item.documentVersionId() + ":" + item.contentHash()).toList());
            beginCommand(identity, "ARCHIVE_CREATE", idempotencyKey, requestHash);
            UUID archiveCaseId = UUID.randomUUID();
            OffsetDateTime archivedAt = OffsetDateTime.now(ZoneOffset.UTC);
            String archiveNo = "AR-" + archivedAt.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                    + "-" + archiveCaseId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
            String manifestHash = manifestHash(documents);
            jdbc.sql("""
                    insert into archive_case(
                      tenant_id, archive_case_id, patient_id, encounter_id, archive_no,
                      status, manifest_hash, archived_by, archived_at)
                    values (:tenant, :archive, :patient, :encounter, :archive_no,
                      'ARCHIVED', :manifest_hash, :actor, :archived_at)
                    """).param("tenant", identity.tenantId()).param("archive", archiveCaseId)
                    .param("patient", command.patientId()).param("encounter", command.encounterId())
                    .param("archive_no", archiveNo).param("manifest_hash", manifestHash)
                    .param("actor", identity.userId()).param("archived_at", archivedAt).update();
            int order = 0;
            for (EligibleDocument document : documents) {
                jdbc.sql("""
                        insert into archive_case_item(
                          tenant_id, archive_case_item_id, archive_case_id, document_id,
                          document_version_id, document_type_code, content_hash,
                          signature_summary_hash, item_order)
                        values (:tenant, :item, :archive, :document, :version, :type,
                          :content_hash, :signature_hash, :item_order)
                        """).param("tenant", identity.tenantId()).param("item", UUID.randomUUID())
                        .param("archive", archiveCaseId).param("document", document.documentId())
                        .param("version", document.documentVersionId()).param("type", document.documentTypeCode())
                        .param("content_hash", document.contentHash())
                        .param("signature_hash", document.signatureSummaryHash())
                        .param("item_order", ++order).update();
            }
            appendEvent(identity, archiveCaseId, 1, "ARCHIVED", "Archive manifest created");
            appendAudit(identity, "ARCHIVE_CREATED", archiveCaseId, command.patientId());
            appendOutbox(identity.tenantId(), "ARCHIVE_CASE", archiveCaseId, 1, "ArchiveCreated",
                    Map.of("encounter_id", command.encounterId().toString(), "manifest_hash", manifestHash));
            completeCommand(identity, "ARCHIVE_CREATE", idempotencyKey, archiveCaseId, 201);
            return snapshot(identity.tenantId(), archiveCaseId, command.patientId(), command.encounterId(), command.facilityId());
        });
    }

    ArchiveCaseWire get(
            ClinicalIdentity identity, UUID archiveCaseId, UUID patientId, UUID encounterId, UUID facilityId) {
        return snapshot(identity.tenantId(), archiveCaseId, patientId, encounterId, facilityId);
    }

    ArchiveCaseWire seal(
            ClinicalIdentity identity,
            String idempotencyKey,
            UUID archiveCaseId,
            ArchiveTransitionRequestWire command) {
        requireRole(identity, command.facilityId(), ARCHIVE_ROLES, "ARCHIVE_ROLE_REQUIRED");
        return transition(identity, idempotencyKey, archiveCaseId, command, "SEALED");
    }

    ArchiveCaseWire unseal(
            ClinicalIdentity identity,
            String idempotencyKey,
            UUID archiveCaseId,
            ArchiveTransitionRequestWire command) {
        requireRole(identity, command.facilityId(), List.of("CLINICAL_ADMIN"), "ARCHIVE_UNSEAL_ROLE_REQUIRED");
        if (command.reason() == null || command.reason().trim().length() < 4) {
            throw new ArchiveException("ARCHIVE_UNSEAL_REASON_REQUIRED", 400,
                    "Controlled unseal requires a reason of at least four characters");
        }
        return transition(identity, idempotencyKey, archiveCaseId, command, "UNSEALED");
    }

    ArchiveExportPackageWire export(
            ClinicalIdentity identity,
            String idempotencyKey,
            UUID archiveCaseId,
            ArchiveExportCreateRequestWire command) {
        requireRole(identity, command.facilityId(), ARCHIVE_ROLES, "ARCHIVE_EXPORT_ROLE_REQUIRED");
        if (command.purpose() == null || command.purpose().trim().length() < 2) {
            throw new ArchiveException("ARCHIVE_EXPORT_PURPOSE_REQUIRED", 400, "Export purpose is required");
        }
        if (command.outputFormat() != ArchiveExportCreateRequestWire.OutputFormatValue.JSON) {
            throw new ArchiveException("ARCHIVE_EXPORT_FORMAT_UNSUPPORTED", 400,
                    "The current archive slice supports independently readable JSON only");
        }
        return transactions.execute(status -> {
            LockedArchive locked = lockArchive(
                    identity.tenantId(), archiveCaseId, command.patientId(), command.encounterId(), command.facilityId());
            if (!"SEALED".equals(locked.status())) {
                throw new ArchiveException("ARCHIVE_EXPORT_REQUIRES_SEAL", 409,
                        "Only a sealed archive can be exported");
            }
            String requestHash = sha256(archiveCaseId + "|" + command.purpose().trim() + "|JSON|" + locked.rowVersion());
            beginCommand(identity, "ARCHIVE_EXPORT", idempotencyKey, requestHash);
            UUID exportId = UUID.randomUUID();
            OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
            String content = buildExportContent(
                    identity.tenantId(), archiveCaseId, exportId, locked, command.purpose().trim(), createdAt);
            String contentHash = sha256(content);
            long byteCount = content.getBytes(StandardCharsets.UTF_8).length;
            jdbc.sql("""
                    insert into archive_export_package(
                      tenant_id, export_package_id, archive_case_id, purpose, output_format,
                      status, content_text, content_hash, byte_count, created_by, created_at)
                    values (:tenant, :export, :archive, :purpose, 'JSON', 'READY',
                      :content, :content_hash, :byte_count, :actor, :created_at)
                    """).param("tenant", identity.tenantId()).param("export", exportId)
                    .param("archive", archiveCaseId).param("purpose", command.purpose().trim())
                    .param("content", content).param("content_hash", contentHash)
                    .param("byte_count", byteCount).param("actor", identity.userId())
                    .param("created_at", createdAt).update();
            long eventNo = nextEventNo(identity.tenantId(), archiveCaseId);
            appendEvent(identity, archiveCaseId, eventNo, "EXPORT_CREATED", command.purpose().trim());
            appendAudit(identity, "ARCHIVE_EXPORTED", exportId, command.patientId());
            appendOutbox(identity.tenantId(), "ARCHIVE_EXPORT", exportId, 1, "ArchiveExportCreated",
                    Map.of("archive_case_id", archiveCaseId.toString(), "content_hash", contentHash,
                            "byte_count", byteCount));
            completeCommand(identity, "ARCHIVE_EXPORT", idempotencyKey, exportId, 201);
            return exportSnapshot(identity.tenantId(), exportId, archiveCaseId);
        });
    }

    ArchiveDownload download(
            ClinicalIdentity identity, UUID exportPackageId, UUID patientId, UUID encounterId, UUID facilityId) {
        requireEncounter(identity.tenantId(), patientId, encounterId, facilityId);
        return jdbc.sql("""
                select package.content_text, package.content_hash
                from archive_export_package package
                join archive_case archive on archive.tenant_id = package.tenant_id
                  and archive.archive_case_id = package.archive_case_id
                join encounter encounter on encounter.tenant_id = archive.tenant_id
                  and encounter.encounter_id = archive.encounter_id
                where package.tenant_id = :tenant and package.export_package_id = :export
                  and archive.patient_id = :patient and archive.encounter_id = :encounter
                  and encounter.facility_id = :facility and archive.status = 'SEALED'
                """).param("tenant", identity.tenantId()).param("export", exportPackageId)
                .param("patient", patientId).param("encounter", encounterId).param("facility", facilityId)
                .query((rs, row) -> new ArchiveDownload(rs.getString("content_text"), rs.getString("content_hash")))
                .optional().orElseThrow(() -> new ArchiveException(
                        "ARCHIVE_EXPORT_NOT_AVAILABLE", 403, "The export is not available in this sealed archive context"));
    }

    private ArchiveCaseWire transition(
            ClinicalIdentity identity,
            String idempotencyKey,
            UUID archiveCaseId,
            ArchiveTransitionRequestWire command,
            String target) {
        if (command.expectedRowVersion() == null || command.expectedRowVersion() <= 0) {
            throw new ArchiveException("ARCHIVE_VERSION_REQUIRED", 400, "A positive expected archive version is required");
        }
        return transactions.execute(status -> {
            LockedArchive archive = lockArchive(
                    identity.tenantId(), archiveCaseId, command.patientId(), command.encounterId(), command.facilityId());
            if (archive.rowVersion() != command.expectedRowVersion()) {
                throw new ArchiveException("ARCHIVE_VERSION_CONFLICT", 409, "The archive changed; reload before continuing");
            }
            String expectedState = "SEALED".equals(target)
                    ? ("UNSEALED".equals(archive.status()) ? "UNSEALED" : "ARCHIVED") : "SEALED";
            if (!expectedState.equals(archive.status())) {
                throw new ArchiveException("ARCHIVE_STATE_INVALID", 409,
                        "The archive state does not allow this transition");
            }
            if ("SEALED".equals(target) && archive.archivedBy().equals(identity.userId())) {
                throw new ArchiveException("ARCHIVE_SEPARATION_REQUIRED", 403,
                        "The archive creator cannot seal the same archive case");
            }
            String scope = "ARCHIVE_" + ("SEALED".equals(target) ? "SEAL" : "UNSEAL");
            String requestHash = sha256(archiveCaseId + "|" + command.expectedRowVersion() + "|" + target
                    + "|" + String.valueOf(command.reason()));
            beginCommand(identity, scope, idempotencyKey, requestHash);
            long nextVersion;
            if ("SEALED".equals(target)) {
                nextVersion = jdbc.sql("""
                        update archive_case set status = 'SEALED', sealed_by = :actor, sealed_at = now(),
                          unsealed_by = null, unsealed_at = null, unseal_reason = null,
                          row_version = row_version + 1
                        where tenant_id = :tenant and archive_case_id = :archive and row_version = :expected
                        returning row_version
                        """).param("actor", identity.userId()).param("tenant", identity.tenantId())
                        .param("archive", archiveCaseId).param("expected", command.expectedRowVersion())
                        .query(Long.class).optional().orElseThrow(() -> new ArchiveException(
                                "ARCHIVE_VERSION_CONFLICT", 409, "The archive changed; reload before continuing"));
            } else {
                nextVersion = jdbc.sql("""
                        update archive_case set status = 'UNSEALED', unsealed_by = :actor,
                          unsealed_at = now(), unseal_reason = :reason, row_version = row_version + 1
                        where tenant_id = :tenant and archive_case_id = :archive and row_version = :expected
                        returning row_version
                        """).param("actor", identity.userId()).param("reason", command.reason().trim())
                        .param("tenant", identity.tenantId()).param("archive", archiveCaseId)
                        .param("expected", command.expectedRowVersion()).query(Long.class)
                        .optional().orElseThrow(() -> new ArchiveException(
                                "ARCHIVE_VERSION_CONFLICT", 409, "The archive changed; reload before continuing"));
            }
            String eventType = target;
            appendEvent(identity, archiveCaseId, nextEventNo(identity.tenantId(), archiveCaseId),
                    eventType, command.reason());
            appendAudit(identity, "ARCHIVE_" + eventType, archiveCaseId, command.patientId());
            appendOutbox(identity.tenantId(), "ARCHIVE_CASE", archiveCaseId, nextVersion,
                    "Archive" + ("SEALED".equals(target) ? "Sealed" : "Unsealed"),
                    Map.of("status", target));
            completeCommand(identity, scope, idempotencyKey, archiveCaseId, 200);
            return snapshot(identity.tenantId(), archiveCaseId,
                    command.patientId(), command.encounterId(), command.facilityId());
        });
    }

    private List<ArchiveBlockerWire> blockers(UUID tenantId, UUID encounterId, String encounterStatus) {
        List<ArchiveBlockerWire> blockers = new ArrayList<>();
        if (!"FINISHED".equals(encounterStatus)) {
            blockers.add(new ArchiveBlockerWire(
                    "ENCOUNTER_NOT_FINISHED", "The encounter must be finished before archive", null));
        }
        List<DocumentEligibility> documents = jdbc.sql("""
                select document.document_id, version.document_version_id, version.status,
                  version.content_hash,
                  exists (
                    select 1 from document_quality_run quality
                    where quality.tenant_id = document.tenant_id
                      and quality.document_id = document.document_id
                      and quality.document_version_id = version.document_version_id
                      and quality.content_hash = version.content_hash and quality.outcome = 'PASSED'
                      and quality.quality_run_id = (
                        select latest.quality_run_id from document_quality_run latest
                        where latest.tenant_id = document.tenant_id
                          and latest.document_id = document.document_id
                          and latest.document_version_id = version.document_version_id
                        order by latest.executed_at desc, latest.quality_run_id desc limit 1)) as quality_passed,
                  (select count(*) from signature_evidence signature
                    where signature.tenant_id = document.tenant_id
                      and signature.document_id = document.document_id
                      and signature.document_version_id = version.document_version_id) as signature_count,
                  not exists (
                    select 1 from signature_evidence signature
                    where signature.tenant_id = document.tenant_id
                      and signature.document_id = document.document_id
                      and signature.document_version_id = version.document_version_id
                      and (signature.signature_status <> 'VALID'
                        or signature.content_hash <> version.content_hash)) as signatures_valid
                from clinical_document document
                join clinical_document_version version
                  on version.tenant_id = document.tenant_id
                  and version.document_id = document.document_id
                  and version.document_version_id = document.current_version_id
                where document.tenant_id = :tenant and document.encounter_id = :encounter
                order by document.document_id
                """).param("tenant", tenantId).param("encounter", encounterId)
                .query((rs, row) -> new DocumentEligibility(
                        rs.getObject("document_id", UUID.class),
                        rs.getObject("document_version_id", UUID.class), rs.getString("status"),
                        rs.getString("content_hash"), rs.getBoolean("quality_passed"),
                        rs.getLong("signature_count"), rs.getBoolean("signatures_valid"))).list();
        if (documents.isEmpty()) {
            blockers.add(new ArchiveBlockerWire(
                    "ARCHIVE_DOCUMENT_REQUIRED", "At least one current clinical document is required", null));
        }
        for (DocumentEligibility document : documents) {
            if (!"SIGNED".equals(document.status())) {
                blockers.add(new ArchiveBlockerWire(
                        "DOCUMENT_NOT_SIGNED", "The current document version is not signed", document.documentId()));
            }
            if (!document.qualityPassed()) {
                blockers.add(new ArchiveBlockerWire(
                        "DOCUMENT_QUALITY_NOT_PASSED",
                        "The latest quality run does not pass the current content hash", document.documentId()));
            }
            if (document.signatureCount() == 0) {
                blockers.add(new ArchiveBlockerWire(
                        "SIGNATURE_EVIDENCE_REQUIRED", "Signature evidence is missing", document.documentId()));
            } else if (!document.signaturesValid()) {
                blockers.add(new ArchiveBlockerWire(
                        "SIGNATURE_EVIDENCE_NOT_VALID",
                        "All current signature evidence must be valid and bound to content", document.documentId()));
            }
        }
        for (UUID assetId : jdbc.sql("""
                select medical_record_asset_id from medical_record_asset
                where tenant_id = :tenant and encounter_id = :encounter
                  and status <> 'RETIRED' and integrity_status <> 'VERIFIED'
                order by medical_record_asset_id
                """).param("tenant", tenantId).param("encounter", encounterId).query(UUID.class).list()) {
            blockers.add(new ArchiveBlockerWire(
                    "ASSET_INTEGRITY_REQUIRED",
                    "All linked record assets must pass immutable hash verification before archive", assetId));
        }
        return List.copyOf(blockers);
    }

    private List<EligibleDocument> eligibleDocuments(UUID tenantId, UUID encounterId) {
        return jdbc.sql("""
                select document.document_id, version.document_version_id, document.document_type_code,
                  version.content_hash,
                  string_agg(
                    signature.signature_id::text || ':' || signature.signer_user_id::text || ':' ||
                    signature.signature_role || ':' || signature.signature_status || ':' ||
                    signature.content_hash, '|' order by signature.signed_at, signature.signature_id)
                    as signature_summary
                from clinical_document document
                join clinical_document_version version
                  on version.tenant_id = document.tenant_id
                  and version.document_id = document.document_id
                  and version.document_version_id = document.current_version_id
                join signature_evidence signature
                  on signature.tenant_id = version.tenant_id
                  and signature.document_id = version.document_id
                  and signature.document_version_id = version.document_version_id
                where document.tenant_id = :tenant and document.encounter_id = :encounter
                group by document.document_id, version.document_version_id,
                  document.document_type_code, version.content_hash
                order by document.document_id
                """).param("tenant", tenantId).param("encounter", encounterId)
                .query((rs, row) -> new EligibleDocument(
                        rs.getObject("document_id", UUID.class),
                        rs.getObject("document_version_id", UUID.class),
                        rs.getString("document_type_code"), rs.getString("content_hash"),
                        sha256(rs.getString("signature_summary")))).list();
    }

    private String buildExportContent(
            UUID tenantId,
            UUID archiveCaseId,
            UUID exportId,
            LockedArchive archive,
            String purpose,
            OffsetDateTime createdAt) {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("schema", "haoemr.archive.export.v1");
        root.put("generator", EXPORT_GENERATOR);
        root.put("export_package_id", exportId.toString());
        root.put("created_at", createdAt.toInstant().toString());
        root.put("purpose", purpose);
        root.put("archive", Map.of(
                "archive_case_id", archiveCaseId.toString(),
                "archive_no", archive.archiveNo(),
                "patient_id", archive.patientId().toString(),
                "encounter_id", archive.encounterId().toString(),
                "status", archive.status(),
                "manifest_hash", archive.manifestHash(),
                "row_version", archive.rowVersion()));
        List<Map<String, Object>> documents = new ArrayList<>();
        for (ArchiveExportDocument document : jdbc.sql("""
                select item.document_id, item.document_version_id, item.document_type_code,
                  item.content_hash, item.signature_summary_hash, item.item_order,
                  version.version_no, version.sections::text as sections_json,
                  version.author_user_id, version.created_at, version.signed_at
                from archive_case_item item
                join clinical_document_version version
                  on version.tenant_id = item.tenant_id and version.document_id = item.document_id
                  and version.document_version_id = item.document_version_id
                where item.tenant_id = :tenant and item.archive_case_id = :archive
                order by item.item_order
                """).param("tenant", tenantId).param("archive", archiveCaseId)
                .query((rs, row) -> new ArchiveExportDocument(
                        rs.getObject("document_id", UUID.class), rs.getObject("document_version_id", UUID.class),
                        rs.getString("document_type_code"), rs.getString("content_hash"),
                        rs.getString("signature_summary_hash"), rs.getInt("item_order"),
                        rs.getInt("version_no"), rs.getString("sections_json"),
                        rs.getObject("author_user_id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("signed_at", OffsetDateTime.class))).list()) {
            LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
            entry.put("item_order", document.itemOrder());
            entry.put("document_id", document.documentId().toString());
            entry.put("document_version_id", document.documentVersionId().toString());
            entry.put("document_type_code", document.documentTypeCode());
            entry.put("version_no", document.versionNo());
            entry.put("content_hash", document.contentHash());
            entry.put("signature_summary_hash", document.signatureSummaryHash());
            entry.put("author_user_id", document.authorUserId().toString());
            entry.put("created_at", document.createdAt().toInstant().toString());
            entry.put("signed_at", document.signedAt().toInstant().toString());
            entry.put("sections", readJson(document.sectionsJson()));
            entry.put("quality_evidence", qualityEvidence(tenantId, document.documentId(), document.documentVersionId()));
            entry.put("signature_evidence", signatureEvidence(tenantId, document.documentId(), document.documentVersionId()));
            documents.add(entry);
        }
        root.put("documents", documents);
        root.put("attachment_count", 0);
        root.put("integrity", Map.of(
                "algorithm", "SHA-256",
                "manifest_hash", archive.manifestHash(),
                "document_count", documents.size()));
        return writeJson(root);
    }

    private Map<String, Object> qualityEvidence(UUID tenantId, UUID documentId, UUID versionId) {
        return jdbc.sql("""
                select quality_run_id, rule_version, outcome, finding_count, blocking_count,
                  warning_count, content_hash, executed_by, executed_at
                from document_quality_run
                where tenant_id = :tenant and document_id = :document and document_version_id = :version
                order by executed_at desc, quality_run_id desc limit 1
                """).param("tenant", tenantId).param("document", documentId).param("version", versionId)
                .query((rs, row) -> {
                    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                    result.put("quality_run_id", rs.getObject("quality_run_id", UUID.class).toString());
                    result.put("rule_version", rs.getString("rule_version"));
                    result.put("outcome", rs.getString("outcome"));
                    result.put("finding_count", rs.getInt("finding_count"));
                    result.put("blocking_count", rs.getInt("blocking_count"));
                    result.put("warning_count", rs.getInt("warning_count"));
                    result.put("content_hash", rs.getString("content_hash"));
                    result.put("executed_by", rs.getObject("executed_by", UUID.class).toString());
                    result.put("executed_at", rs.getObject("executed_at", OffsetDateTime.class).toInstant().toString());
                    return (Map<String, Object>) result;
                }).single();
    }

    private List<Map<String, Object>> signatureEvidence(UUID tenantId, UUID documentId, UUID versionId) {
        return jdbc.sql("""
                select signature_id, signer_user_id, signature_role, signature_status,
                  content_hash, credential_ref, signed_at
                from signature_evidence
                where tenant_id = :tenant and document_id = :document and document_version_id = :version
                order by signed_at, signature_id
                """).param("tenant", tenantId).param("document", documentId).param("version", versionId)
                .query((rs, row) -> {
                    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                    result.put("signature_id", rs.getObject("signature_id", UUID.class).toString());
                    result.put("signer_user_id", rs.getObject("signer_user_id", UUID.class).toString());
                    result.put("signature_role", rs.getString("signature_role"));
                    result.put("signature_status", rs.getString("signature_status"));
                    result.put("content_hash", rs.getString("content_hash"));
                    result.put("credential_ref", rs.getString("credential_ref"));
                    result.put("signed_at", rs.getObject("signed_at", OffsetDateTime.class).toInstant().toString());
                    return (Map<String, Object>) result;
                }).list();
    }

    private ArchiveCaseWire findByEncounter(
            UUID tenantId, UUID patientId, UUID encounterId, UUID facilityId) {
        UUID archiveId = jdbc.sql("""
                select archive.archive_case_id
                from archive_case archive
                join encounter encounter on encounter.tenant_id = archive.tenant_id
                  and encounter.encounter_id = archive.encounter_id
                where archive.tenant_id = :tenant and archive.patient_id = :patient
                  and archive.encounter_id = :encounter and encounter.facility_id = :facility
                """).param("tenant", tenantId).param("patient", patientId).param("encounter", encounterId)
                .param("facility", facilityId).query(UUID.class).optional().orElse(null);
        return archiveId == null ? null : snapshot(tenantId, archiveId, patientId, encounterId, facilityId);
    }

    private ArchiveCaseWire snapshot(
            UUID tenantId, UUID archiveCaseId, UUID patientId, UUID encounterId, UUID facilityId) {
        ArchiveHead head = jdbc.sql("""
                select archive.archive_case_id, archive.patient_id, archive.encounter_id,
                  archive.archive_no, archive.status, archive.manifest_hash, archive.archived_by,
                  archive.archived_at, archive.sealed_by, archive.sealed_at, archive.unsealed_by,
                  archive.unsealed_at, archive.unseal_reason, archive.row_version
                from archive_case archive
                join encounter encounter on encounter.tenant_id = archive.tenant_id
                  and encounter.encounter_id = archive.encounter_id
                where archive.tenant_id = :tenant and archive.archive_case_id = :archive
                  and archive.patient_id = :patient and archive.encounter_id = :encounter
                  and encounter.facility_id = :facility
                """).param("tenant", tenantId).param("archive", archiveCaseId).param("patient", patientId)
                .param("encounter", encounterId).param("facility", facilityId)
                .query((rs, row) -> new ArchiveHead(
                        rs.getObject("archive_case_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class), rs.getString("archive_no"),
                        rs.getString("status"), rs.getString("manifest_hash"),
                        rs.getObject("archived_by", UUID.class), rs.getObject("archived_at", OffsetDateTime.class),
                        rs.getObject("sealed_by", UUID.class), rs.getObject("sealed_at", OffsetDateTime.class),
                        rs.getObject("unsealed_by", UUID.class), rs.getObject("unsealed_at", OffsetDateTime.class),
                        rs.getString("unseal_reason"), rs.getLong("row_version")))
                .optional().orElseThrow(() -> new ArchiveException(
                        "ARCHIVE_CONTEXT_NOT_PERMITTED", 403, "The archive is not available in this clinical context"));
        List<ArchiveCaseItemWire> items = jdbc.sql("""
                select archive_case_item_id, document_id, document_version_id, document_type_code,
                  content_hash, signature_summary_hash, item_order
                from archive_case_item where tenant_id = :tenant and archive_case_id = :archive
                order by item_order
                """).param("tenant", tenantId).param("archive", archiveCaseId)
                .query((rs, row) -> new ArchiveCaseItemWire(
                        rs.getObject("archive_case_item_id", UUID.class), rs.getObject("document_id", UUID.class),
                        rs.getObject("document_version_id", UUID.class), rs.getString("document_type_code"),
                        rs.getString("content_hash"), rs.getString("signature_summary_hash"), rs.getInt("item_order")))
                .list();
        List<ArchiveEventWire> events = jdbc.sql("""
                select event.archive_case_event_id, event.event_no, event.event_type,
                  event.actor_user_id, actor.display_name, event.reason, event.occurred_at
                from archive_case_event event
                join app_user actor on actor.tenant_id = event.tenant_id
                  and actor.user_id = event.actor_user_id
                where event.tenant_id = :tenant and event.archive_case_id = :archive
                order by event.event_no
                """).param("tenant", tenantId).param("archive", archiveCaseId)
                .query((rs, row) -> new ArchiveEventWire(
                        rs.getObject("archive_case_event_id", UUID.class), rs.getLong("event_no"),
                        ArchiveEventWire.EventTypeValue.valueOf(rs.getString("event_type")),
                        rs.getObject("actor_user_id", UUID.class), rs.getString("display_name"),
                        rs.getString("reason"), rs.getObject("occurred_at", OffsetDateTime.class).toInstant()))
                .list();
        List<ArchiveExportPackageWire> exports = jdbc.sql("""
                select export_package_id from archive_export_package
                where tenant_id = :tenant and archive_case_id = :archive
                order by created_at desc, export_package_id
                """).param("tenant", tenantId).param("archive", archiveCaseId).query(UUID.class).list().stream()
                .map(exportId -> exportSnapshot(tenantId, exportId, archiveCaseId)).toList();
        String watermark = sha256(head.manifestHash() + "|" + head.status() + "|" + head.rowVersion()
                + "|" + events.stream().map(event -> event.archiveCaseEventId().toString()).toList()
                + "|" + exports.stream().map(ArchiveExportPackageWire::contentHash).toList());
        return new ArchiveCaseWire(
                head.archiveCaseId(), head.patientId(), head.encounterId(), head.archiveNo(),
                ArchiveCaseWire.StatusValue.valueOf(head.status()), head.manifestHash(), head.archivedBy(),
                head.archivedAt().toInstant(), head.sealedBy(), instant(head.sealedAt()),
                head.unsealedBy(), instant(head.unsealedAt()), head.unsealReason(), head.rowVersion(),
                items, events, exports, watermark);
    }

    private ArchiveExportPackageWire exportSnapshot(UUID tenantId, UUID exportId, UUID archiveCaseId) {
        return jdbc.sql("""
                select export_package_id, archive_case_id, purpose, output_format, status,
                  content_hash, byte_count, created_by, created_at
                from archive_export_package
                where tenant_id = :tenant and export_package_id = :export and archive_case_id = :archive
                """).param("tenant", tenantId).param("export", exportId).param("archive", archiveCaseId)
                .query((rs, row) -> new ArchiveExportPackageWire(
                        rs.getObject("export_package_id", UUID.class), rs.getObject("archive_case_id", UUID.class),
                        rs.getString("purpose"), ArchiveExportPackageWire.OutputFormatValue.JSON,
                        ArchiveExportPackageWire.StatusValue.READY, rs.getString("content_hash"),
                        rs.getLong("byte_count"), rs.getObject("created_by", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        "/api/v1/archive/export-packages/" + exportId + "/content"))
                .single();
    }

    private EncounterHead requireEncounter(UUID tenantId, UUID patientId, UUID encounterId, UUID facilityId) {
        return jdbc.sql("""
                select status from encounter where tenant_id = :tenant and patient_id = :patient
                  and encounter_id = :encounter and facility_id = :facility
                """).param("tenant", tenantId).param("patient", patientId).param("encounter", encounterId)
                .param("facility", facilityId).query((rs, row) -> new EncounterHead(rs.getString("status")))
                .optional().orElseThrow(() -> new ArchiveException(
                        "ARCHIVE_CONTEXT_NOT_PERMITTED", 403, "The encounter is not available in this archive context"));
    }

    private EncounterHead lockEncounter(UUID tenantId, UUID patientId, UUID encounterId, UUID facilityId) {
        return jdbc.sql("""
                select status from encounter where tenant_id = :tenant and patient_id = :patient
                  and encounter_id = :encounter and facility_id = :facility for update
                """).param("tenant", tenantId).param("patient", patientId).param("encounter", encounterId)
                .param("facility", facilityId).query((rs, row) -> new EncounterHead(rs.getString("status")))
                .optional().orElseThrow(() -> new ArchiveException(
                        "ARCHIVE_CONTEXT_NOT_PERMITTED", 403, "The encounter is not available in this archive context"));
    }

    private LockedArchive lockArchive(
            UUID tenantId, UUID archiveCaseId, UUID patientId, UUID encounterId, UUID facilityId) {
        return jdbc.sql("""
                select archive.patient_id, archive.encounter_id, archive.archive_no, archive.status,
                  archive.manifest_hash, archive.archived_by, archive.row_version
                from archive_case archive
                join encounter encounter on encounter.tenant_id = archive.tenant_id
                  and encounter.encounter_id = archive.encounter_id
                where archive.tenant_id = :tenant and archive.archive_case_id = :archive
                  and archive.patient_id = :patient and archive.encounter_id = :encounter
                  and encounter.facility_id = :facility for update of archive
                """).param("tenant", tenantId).param("archive", archiveCaseId).param("patient", patientId)
                .param("encounter", encounterId).param("facility", facilityId)
                .query((rs, row) -> new LockedArchive(
                        rs.getObject("patient_id", UUID.class), rs.getObject("encounter_id", UUID.class),
                        rs.getString("archive_no"), rs.getString("status"), rs.getString("manifest_hash"),
                        rs.getObject("archived_by", UUID.class), rs.getLong("row_version")))
                .optional().orElseThrow(() -> new ArchiveException(
                        "ARCHIVE_CONTEXT_NOT_PERMITTED", 403, "The archive is not available in this clinical context"));
    }

    private int documentCount(UUID tenantId, UUID encounterId) {
        return jdbc.sql("select count(*) from clinical_document where tenant_id = :tenant and encounter_id = :encounter")
                .param("tenant", tenantId).param("encounter", encounterId).query(Integer.class).single();
    }

    private void requireRole(
            ClinicalIdentity identity, UUID facilityId, List<String> allowedRoles, String errorCode) {
        if (identity.roleAssignmentIds().isEmpty()) {
            throw new ArchiveException(errorCode, 403, "An active medical records role is required");
        }
        long authorized = jdbc.sql("""
                select count(*) from role_assignment where tenant_id = :tenant and user_id = :user
                  and role_assignment_id in (:assignments) and role_code in (:roles)
                  and (facility_id is null or facility_id = :facility)
                  and status = 'ACTIVE' and valid_from <= now()
                  and (valid_until is null or valid_until > now())
                """).param("tenant", identity.tenantId()).param("user", identity.userId())
                .param("assignments", identity.roleAssignmentIds()).param("roles", allowedRoles)
                .param("facility", facilityId).query(Long.class).single();
        if (authorized == 0) {
            throw new ArchiveException(errorCode, 403, "The active role does not authorize this archive action");
        }
    }

    private void beginCommand(
            ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank()) {
            throw new ArchiveException("IDEMPOTENCY_KEY_REQUIRED", 400, "Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new ArchiveException("IDEMPOTENCY_REPLAY", 409, "This archive command has already been used");
        }
    }

    private void completeCommand(
            ClinicalIdentity identity, String scope, String key, UUID responseId, int responseStatus) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = :status,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("status", responseStatus).param("resource", responseId)
                .param("tenant", identity.tenantId()).param("scope", scope).param("key", key).update();
    }

    private void appendEvent(
            ClinicalIdentity identity, UUID archiveCaseId, long eventNo, String eventType, String reason) {
        jdbc.sql("""
                insert into archive_case_event(
                  tenant_id, archive_case_event_id, archive_case_id, event_no,
                  event_type, actor_user_id, reason)
                values (:tenant, :event, :archive, :event_no, :event_type, :actor, :reason)
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("archive", archiveCaseId).param("event_no", eventNo)
                .param("event_type", eventType).param("actor", identity.userId())
                .param("reason", reason).update();
    }

    private long nextEventNo(UUID tenantId, UUID archiveCaseId) {
        return jdbc.sql("""
                select coalesce(max(event_no), 0) + 1 from archive_case_event
                where tenant_id = :tenant and archive_case_id = :archive
                """).param("tenant", tenantId).param("archive", archiveCaseId).query(Long.class).single();
    }

    private void appendAudit(ClinicalIdentity identity, String action, UUID resourceId, UUID patientId) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(String.join("|", identity.tenantId().toString(), auditId.toString(), action,
                resourceId.toString(), trace, previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'ARCHIVE', :resource,
                  :patient_hash, :trace, :previous_hash, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", resourceId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId)).param("trace", trace)
                .param("previous_hash", previousHash).param("event_hash", eventHash).update();
    }

    private void appendOutbox(
            UUID tenantId,
            String aggregateType,
            UUID aggregateId,
            long version,
            String eventType,
            Map<String, Object> payload) {
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, :aggregate_type, :aggregate_id, :version,
                  :event_type, 1, cast(:payload as jsonb))
                """).param("tenant", tenantId).param("event", UUID.randomUUID())
                .param("aggregate_type", aggregateType).param("aggregate_id", aggregateId)
                .param("version", version).param("event_type", eventType).param("payload", writeJson(payload)).update();
    }

    private String manifestHash(List<EligibleDocument> documents) {
        StringBuilder canonical = new StringBuilder("haoemr.archive.manifest.v1");
        for (EligibleDocument document : documents) {
            canonical.append('|').append(document.documentId()).append(':')
                    .append(document.documentVersionId()).append(':').append(document.documentTypeCode())
                    .append(':').append(document.contentHash()).append(':').append(document.signatureSummaryHash());
        }
        return sha256(canonical.toString());
    }

    private Object readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception invalid) {
            throw new IllegalStateException("Stored document JSON is invalid", invalid);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception invalid) {
            throw new IllegalStateException("Archive JSON generation failed", invalid);
        }
    }

    private static java.time.Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    record ArchiveDownload(String content, String contentHash) {}
    private record EncounterHead(String status) {}
    private record DocumentEligibility(
            UUID documentId, UUID documentVersionId, String status, String contentHash,
            boolean qualityPassed, long signatureCount, boolean signaturesValid) {}
    private record EligibleDocument(
            UUID documentId, UUID documentVersionId, String documentTypeCode,
            String contentHash, String signatureSummaryHash) {}
    private record LockedArchive(
            UUID patientId, UUID encounterId, String archiveNo, String status,
            String manifestHash, UUID archivedBy, long rowVersion) {}
    private record ArchiveHead(
            UUID archiveCaseId, UUID patientId, UUID encounterId, String archiveNo,
            String status, String manifestHash, UUID archivedBy, OffsetDateTime archivedAt,
            UUID sealedBy, OffsetDateTime sealedAt, UUID unsealedBy, OffsetDateTime unsealedAt,
            String unsealReason, long rowVersion) {}
    private record ArchiveExportDocument(
            UUID documentId, UUID documentVersionId, String documentTypeCode,
            String contentHash, String signatureSummaryHash, int itemOrder, int versionNo,
            String sectionsJson, UUID authorUserId, OffsetDateTime createdAt, OffsetDateTime signedAt) {}
}
