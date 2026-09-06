package org.haoemr.clinical;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class DocumentEvidenceService {
    private static final int MAX_BYTES = 25 * 1024 * 1024;
    private static final Set<String> MEDIA_TYPES = Set.of(
            "application/pdf", "application/dicom", "image/jpeg", "image/png", "text/plain");

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ClinicalObjectStorage storage;

    DocumentEvidenceService(JdbcClient jdbc, TransactionTemplate transactions, ClinicalObjectStorage storage) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.storage = storage;
    }

    DocumentAttachmentWire upload(
            ClinicalIdentity identity, String key, UUID documentId, DocumentAttachmentCreateRequest request) {
        if (request == null || request.documentVersionId() == null || request.patientId() == null
                || request.encounterId() == null || blank(request.originalFilename())
                || blank(request.mediaType()) || blank(request.contentBase64()) || blank(request.targetFieldPath())) {
            throw invalid("Document version, context, filename, media type, content and target field are required");
        }
        if ((request.replacesAttachmentId() == null) != blank(request.replacementReason())) {
            throw invalid("A replacement attachment and replacement reason must be provided together");
        }
        if (request.replacesAttachmentId() != null) requireReason(request.replacementReason());
        String filename = safeFilename(request.originalFilename());
        String mediaType = request.mediaType().trim().toLowerCase();
        if (!MEDIA_TYPES.contains(mediaType)) throw invalid("Attachment media type is not allowed");
        byte[] content;
        try { content = Base64.getDecoder().decode(request.contentBase64()); }
        catch (IllegalArgumentException invalidBase64) { throw invalid("Attachment content is not valid base64"); }
        if (content.length < 1 || content.length > MAX_BYTES) throw invalid("Attachment must be between 1 byte and 25 MiB");
        validateMagic(mediaType, content);
        String contentHash = sha256(content);
        if (!blank(request.expectedSha256()) && !contentHash.equals(request.expectedSha256().toLowerCase())) {
            throw new ClinicalCommandException("ATTACHMENT_HASH_MISMATCH", 409,
                    "The uploaded attachment does not match the expected SHA-256");
        }
        String textProbe = new String(content, 0, Math.min(content.length, 256), StandardCharsets.US_ASCII);
        if (textProbe.contains("EICAR-STANDARD-ANTIVIRUS-TEST-FILE")) {
            throw new ClinicalCommandException("ATTACHMENT_MALWARE_REJECTED", 422,
                    "The attachment was rejected by malware scanning");
        }
        UUID attachmentId = UUID.randomUUID();
        String storageKey = identity.tenantId() + "/documents/" + documentId + "/"
                + request.documentVersionId() + "/" + attachmentId + "-" + contentHash;
        try {
            return transactions.execute(ignored -> {
                begin(identity, "DOCUMENT_ATTACHMENT_UPLOAD", key, sha256(
                        documentId + "|" + request.documentVersionId() + "|" + filename + "|" + contentHash));
                DocumentHead document = lockEditableDocument(
                        identity, documentId, request.documentVersionId(), request.patientId(), request.encounterId());
                if (request.replacesAttachmentId() != null) {
                    lockActiveEvidence(identity.tenantId(), documentId, request.documentVersionId(),
                            "ATTACHMENT", request.replacesAttachmentId());
                }
                storage.put(storageKey, content, mediaType);
                jdbc.sql("""
                        insert into clinical_document_attachment(
                          tenant_id, attachment_id, document_id, document_version_id, patient_id, encounter_id,
                          original_filename, media_type, byte_size, content_hash, storage_key,
                          storage_status, malware_scan_status, uploaded_by)
                        values (:tenant,:attachment,:document,:version,:patient,:encounter,:filename,:media_type,
                          :byte_size,:content_hash,:storage_key,'AVAILABLE','PASSED',:actor)
                        """).param("tenant", identity.tenantId()).param("attachment", attachmentId)
                        .param("document", documentId).param("version", request.documentVersionId())
                        .param("patient", request.patientId()).param("encounter", request.encounterId())
                        .param("filename", filename).param("media_type", mediaType).param("byte_size", content.length)
                        .param("content_hash", contentHash).param("storage_key", storageKey)
                        .param("actor", identity.userId()).update();
                insertReference(identity, document, "ATTACHMENT", attachmentId, contentHash,
                        request.targetFieldPath(), filename, contentHash);
                if (request.replacesAttachmentId() != null) {
                    appendLifecycle(identity, document, "ATTACHMENT", request.replacesAttachmentId(),
                            "SUPERSEDED", attachmentId, null, null, request.replacementReason());
                }
                evidence(identity, "DOCUMENT_ATTACHMENT_ADDED", documentId, request.patientId(), attachmentId,
                        request.replacesAttachmentId() == null
                                ? "DocumentAttachmentAdded" : "DocumentAttachmentReplaced",
                        contentHash);
                complete(identity, "DOCUMENT_ATTACHMENT_UPLOAD", key, attachmentId, 201);
                return attachment(identity.tenantId(), attachmentId);
            });
        } catch (RuntimeException failure) {
            storage.deleteBestEffort(storageKey);
            throw failure;
        }
    }

    DocumentSourceReferenceWire addReference(
            ClinicalIdentity identity, String key, UUID documentId, DocumentSourceReferenceCreateRequest request) {
        if (request == null || request.documentVersionId() == null || request.patientId() == null
                || request.encounterId() == null || request.sourceResourceId() == null
                || blank(request.sourceType()) || blank(request.targetFieldPath())) {
            throw invalid("Document version, context, source and target field are required");
        }
        String type = request.sourceType().trim().toUpperCase();
        if (!Set.of("DIAGNOSIS", "ORDER", "RESULT", "ATTACHMENT").contains(type)) {
            throw invalid("Unsupported document source type");
        }
        return transactions.execute(ignored -> {
            begin(identity, "DOCUMENT_SOURCE_REFERENCE_CREATE", key, sha256(documentId + "|" + request));
            DocumentHead document = lockEditableDocument(
                    identity, documentId, request.documentVersionId(), request.patientId(), request.encounterId());
            ResolvedSource source = resolveSource(identity.tenantId(), request.patientId(), request.encounterId(),
                    type, request.sourceResourceId());
            String excerptHash = blank(request.excerpt()) ? null : sha256(request.excerpt());
            UUID referenceId = insertReference(identity, document, type, request.sourceResourceId(),
                    source.versionRef(), request.targetFieldPath(), source.label(), excerptHash);
            evidence(identity, "DOCUMENT_SOURCE_REFERENCED", documentId, request.patientId(), referenceId,
                    "DocumentSourceReferenced", source.versionRef());
            complete(identity, "DOCUMENT_SOURCE_REFERENCE_CREATE", key, referenceId, 201);
            return reference(identity.tenantId(), referenceId);
        });
    }

    DocumentSourceReferenceWire correctReference(
            ClinicalIdentity identity, String key, UUID documentId, UUID referenceId,
            DocumentSourceReferenceCorrectionRequest request) {
        if (request == null || request.documentVersionId() == null || request.patientId() == null
                || request.encounterId() == null || blank(request.targetFieldPath())) {
            throw invalid("Document version, context and target field are required");
        }
        requireReason(request.reason());
        if (!request.targetFieldPath().matches("^sections\\.[A-Za-z0-9_.-]+$")) {
            throw invalid("Invalid target field path");
        }
        return transactions.execute(ignored -> {
            begin(identity, "DOCUMENT_SOURCE_REFERENCE_CORRECT", key,
                    sha256(documentId + "|" + referenceId + "|" + request));
            DocumentHead document = lockEditableDocument(
                    identity, documentId, request.documentVersionId(), request.patientId(), request.encounterId());
            lockActiveEvidence(identity.tenantId(), documentId, request.documentVersionId(),
                    "SOURCE_REFERENCE", referenceId);
            String excerptHash = blank(request.excerpt()) ? null : sha256(request.excerpt());
            appendLifecycle(identity, document, "SOURCE_REFERENCE", referenceId, "CORRECTED", null,
                    request.targetFieldPath(), excerptHash, request.reason());
            evidence(identity, "DOCUMENT_SOURCE_REFERENCE_CORRECTED", documentId, request.patientId(), referenceId,
                    "DocumentSourceReferenceCorrected", request.targetFieldPath());
            complete(identity, "DOCUMENT_SOURCE_REFERENCE_CORRECT", key, referenceId, 200);
            return reference(identity.tenantId(), referenceId);
        });
    }

    DocumentSourceReferenceWire revokeReference(
            ClinicalIdentity identity, String key, UUID documentId, UUID referenceId,
            DocumentEvidenceLifecycleRequest request) {
        requireLifecycleRequest(request);
        return transactions.execute(ignored -> {
            begin(identity, "DOCUMENT_SOURCE_REFERENCE_REVOKE", key,
                    sha256(documentId + "|" + referenceId + "|" + request));
            DocumentHead document = lockEditableDocument(
                    identity, documentId, request.documentVersionId(), request.patientId(), request.encounterId());
            lockActiveEvidence(identity.tenantId(), documentId, request.documentVersionId(),
                    "SOURCE_REFERENCE", referenceId);
            appendLifecycle(identity, document, "SOURCE_REFERENCE", referenceId, "REVOKED", null,
                    null, null, request.reason());
            evidence(identity, "DOCUMENT_SOURCE_REFERENCE_REVOKED", documentId, request.patientId(), referenceId,
                    "DocumentSourceReferenceRevoked", request.reason());
            complete(identity, "DOCUMENT_SOURCE_REFERENCE_REVOKE", key, referenceId, 200);
            return reference(identity.tenantId(), referenceId);
        });
    }

    DocumentAttachmentWire voidAttachment(
            ClinicalIdentity identity, String key, UUID documentId, UUID attachmentId,
            DocumentEvidenceLifecycleRequest request) {
        requireLifecycleRequest(request);
        return transactions.execute(ignored -> {
            begin(identity, "DOCUMENT_ATTACHMENT_VOID", key,
                    sha256(documentId + "|" + attachmentId + "|" + request));
            DocumentHead document = lockEditableDocument(
                    identity, documentId, request.documentVersionId(), request.patientId(), request.encounterId());
            lockActiveEvidence(identity.tenantId(), documentId, request.documentVersionId(),
                    "ATTACHMENT", attachmentId);
            appendLifecycle(identity, document, "ATTACHMENT", attachmentId, "VOIDED", null,
                    null, null, request.reason());
            evidence(identity, "DOCUMENT_ATTACHMENT_VOIDED", documentId, request.patientId(), attachmentId,
                    "DocumentAttachmentVoided", request.reason());
            complete(identity, "DOCUMENT_ATTACHMENT_VOID", key, attachmentId, 200);
            return attachment(identity.tenantId(), attachmentId);
        });
    }

    DocumentSourceBundleWire bundle(
            ClinicalIdentity identity, UUID documentId, UUID documentVersionId, UUID patientId, UUID encounterId) {
        return transactions.execute(ignored -> {
            requireDocument(identity.tenantId(), documentId, documentVersionId, patientId, encounterId);
            List<DocumentAttachmentWire> attachments = jdbc.sql("""
                select attachment_id, document_id, document_version_id, original_filename, media_type,
                  byte_size, content_hash, storage_status, malware_scan_status, uploaded_by, created_at,
                  (select event_type from clinical_document_evidence_lifecycle_event lifecycle
                    where lifecycle.tenant_id=attachment.tenant_id and lifecycle.evidence_type='ATTACHMENT'
                      and lifecycle.evidence_id=attachment.attachment_id
                    order by occurred_at desc,lifecycle_event_id desc limit 1) lifecycle_event_type,
                  (select replacement_evidence_id from clinical_document_evidence_lifecycle_event lifecycle
                    where lifecycle.tenant_id=attachment.tenant_id and lifecycle.evidence_type='ATTACHMENT'
                      and lifecycle.evidence_id=attachment.attachment_id
                    order by occurred_at desc,lifecycle_event_id desc limit 1) replacement_evidence_id,
                  (select reason from clinical_document_evidence_lifecycle_event lifecycle
                    where lifecycle.tenant_id=attachment.tenant_id and lifecycle.evidence_type='ATTACHMENT'
                      and lifecycle.evidence_id=attachment.attachment_id
                    order by occurred_at desc,lifecycle_event_id desc limit 1) lifecycle_reason
                from clinical_document_attachment attachment
                where tenant_id=:tenant and document_id=:document and document_version_id=:version
                order by created_at, attachment_id
                """).param("tenant", identity.tenantId()).param("document", documentId)
                .param("version", documentVersionId).query((rs, row) -> attachmentWire(rs)).list();
            List<DocumentSourceReferenceWire> references = jdbc.sql("""
                select source_reference_id, document_id, document_version_id, source_type, source_resource_id,
                  source_version_ref,
                  coalesce((select effective_target_field_path
                    from clinical_document_evidence_lifecycle_event lifecycle
                    where lifecycle.tenant_id=reference.tenant_id and lifecycle.evidence_type='SOURCE_REFERENCE'
                      and lifecycle.evidence_id=reference.source_reference_id and lifecycle.event_type='CORRECTED'
                    order by occurred_at desc,lifecycle_event_id desc limit 1), target_field_path) target_field_path,
                  display_label,
                  coalesce((select effective_excerpt_hash
                    from clinical_document_evidence_lifecycle_event lifecycle
                    where lifecycle.tenant_id=reference.tenant_id and lifecycle.evidence_type='SOURCE_REFERENCE'
                      and lifecycle.evidence_id=reference.source_reference_id and lifecycle.event_type='CORRECTED'
                    order by occurred_at desc,lifecycle_event_id desc limit 1), excerpt_hash) excerpt_hash,
                  recorded_by, recorded_at,
                  coalesce((select event_type from clinical_document_evidence_lifecycle_event lifecycle
                    where lifecycle.tenant_id=reference.tenant_id and lifecycle.evidence_type='SOURCE_REFERENCE'
                      and lifecycle.evidence_id=reference.source_reference_id
                    order by occurred_at desc,lifecycle_event_id desc limit 1),
                    case when source_type='ATTACHMENT' then
                      (select event_type from clinical_document_evidence_lifecycle_event lifecycle
                       where lifecycle.tenant_id=reference.tenant_id and lifecycle.evidence_type='ATTACHMENT'
                         and lifecycle.evidence_id=reference.source_resource_id
                       order by occurred_at desc,lifecycle_event_id desc limit 1) end) lifecycle_event_type,
                  coalesce((select reason from clinical_document_evidence_lifecycle_event lifecycle
                    where lifecycle.tenant_id=reference.tenant_id and lifecycle.evidence_type='SOURCE_REFERENCE'
                      and lifecycle.evidence_id=reference.source_reference_id
                    order by occurred_at desc,lifecycle_event_id desc limit 1),
                    case when source_type='ATTACHMENT' then
                      (select reason from clinical_document_evidence_lifecycle_event lifecycle
                       where lifecycle.tenant_id=reference.tenant_id and lifecycle.evidence_type='ATTACHMENT'
                         and lifecycle.evidence_id=reference.source_resource_id
                       order by occurred_at desc,lifecycle_event_id desc limit 1) end) lifecycle_reason
                from clinical_document_source_reference reference
                where tenant_id=:tenant and document_id=:document and document_version_id=:version
                order by recorded_at, source_reference_id
                """).param("tenant", identity.tenantId()).param("document", documentId)
                .param("version", documentVersionId).query((rs, row) -> referenceWire(identity.tenantId(), rs)).list();
            evidence(identity, "DOCUMENT_SOURCES_VIEWED", documentId, patientId, documentVersionId,
                    "DocumentSourcesViewed", String.valueOf(references.size()));
            return new DocumentSourceBundleWire(documentId, documentVersionId, attachments, references,
                    sha256(documentId + "|" + documentVersionId + "|" + attachments + "|" + references));
        });
    }

    private DocumentHead lockEditableDocument(
            ClinicalIdentity identity, UUID documentId, UUID versionId, UUID patientId, UUID encounterId) {
        DocumentHead head = jdbc.sql("""
                select document.document_id, document.current_version_id, document.created_by, document.status,
                  document.patient_id, document.encounter_id
                from clinical_document document
                where document.tenant_id=:tenant and document.document_id=:document
                  and document.patient_id=:patient and document.encounter_id=:encounter
                for update
                """).param("tenant", identity.tenantId()).param("document", documentId)
                .param("patient", patientId).param("encounter", encounterId)
                .query((rs, row) -> new DocumentHead(rs.getObject("document_id", UUID.class),
                        rs.getObject("current_version_id", UUID.class), rs.getObject("created_by", UUID.class),
                        rs.getString("status"), rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class))).optional().orElseThrow(DocumentEvidenceService::denied);
        if (!versionId.equals(head.currentVersionId())) throw new ClinicalCommandException(
                "DOCUMENT_VERSION_CHANGED", 409, "Sources can only be added to the current document version");
        if (!"DRAFT".equals(head.status()) || !identity.userId().equals(head.createdBy())) throw new ClinicalCommandException(
                "DOCUMENT_SOURCE_NOT_EDITABLE", 409, "Only the current draft author can add sources or attachments");
        return head;
    }

    private void lockActiveEvidence(
            UUID tenantId, UUID documentId, UUID documentVersionId, String evidenceType, UUID evidenceId) {
        String table = "ATTACHMENT".equals(evidenceType)
                ? "clinical_document_attachment" : "clinical_document_source_reference";
        String idColumn = "ATTACHMENT".equals(evidenceType) ? "attachment_id" : "source_reference_id";
        UUID locked = jdbc.sql("select " + idColumn + " from " + table
                        + " where tenant_id=:tenant and document_id=:document and document_version_id=:version"
                        + " and " + idColumn + "=:evidence for update")
                .param("tenant", tenantId).param("document", documentId).param("version", documentVersionId)
                .param("evidence", evidenceId).query(UUID.class).optional().orElse(null);
        if (locked == null) throw new ClinicalCommandException(
                "DOCUMENT_EVIDENCE_NOT_FOUND", 404, "The document evidence does not exist in the current version");
        long terminal = jdbc.sql("""
                select count(*) from clinical_document_evidence_lifecycle_event
                where tenant_id=:tenant and evidence_type=:evidence_type and evidence_id=:evidence
                  and event_type in ('REVOKED','SUPERSEDED','VOIDED')
                """).param("tenant", tenantId).param("evidence_type", evidenceType)
                .param("evidence", evidenceId).query(Long.class).single();
        if (terminal > 0) throw new ClinicalCommandException(
                "DOCUMENT_EVIDENCE_NOT_ACTIVE", 409, "The document evidence is already revoked, superseded or voided");
    }

    private void appendLifecycle(
            ClinicalIdentity identity, DocumentHead document, String evidenceType, UUID evidenceId,
            String eventType, UUID replacementEvidenceId, String targetFieldPath, String excerptHash, String reason) {
        jdbc.sql("""
                insert into clinical_document_evidence_lifecycle_event(
                  tenant_id,lifecycle_event_id,document_id,document_version_id,evidence_type,evidence_id,
                  event_type,replacement_evidence_id,effective_target_field_path,effective_excerpt_hash,
                  reason,actor_user_id)
                values (:tenant,:event,:document,:version,:evidence_type,:evidence,:event_type,:replacement,
                  :target_field,:excerpt_hash,:reason,:actor)
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("document", document.documentId()).param("version", document.currentVersionId())
                .param("evidence_type", evidenceType).param("evidence", evidenceId)
                .param("event_type", eventType).param("replacement", replacementEvidenceId)
                .param("target_field", targetFieldPath).param("excerpt_hash", excerptHash)
                .param("reason", reason.trim()).param("actor", identity.userId()).update();
    }

    private static void requireLifecycleRequest(DocumentEvidenceLifecycleRequest request) {
        if (request == null || request.documentVersionId() == null || request.patientId() == null
                || request.encounterId() == null) {
            throw invalid("Document version and context are required");
        }
        requireReason(request.reason());
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.trim().length() < 4 || reason.trim().length() > 2000) {
            throw invalid("A reason between 4 and 2000 characters is required");
        }
    }

    private void requireDocument(UUID tenant, UUID documentId, UUID versionId, UUID patientId, UUID encounterId) {
        long count = jdbc.sql("""
                select count(*) from clinical_document document
                join clinical_document_version version on version.tenant_id=document.tenant_id
                  and version.document_id=document.document_id
                where document.tenant_id=:tenant and document.document_id=:document
                  and document.patient_id=:patient and document.encounter_id=:encounter
                  and version.document_version_id=:version
                """).param("tenant", tenant).param("document", documentId).param("patient", patientId)
                .param("encounter", encounterId).param("version", versionId).query(Long.class).single();
        if (count != 1) throw denied();
    }

    private ResolvedSource resolveSource(
            UUID tenant, UUID patient, UUID encounter, String type, UUID resource) {
        return switch (type) {
            case "DIAGNOSIS" -> jdbc.sql("""
                    select diagnosis.current_version_id::text as version_ref, version.diagnosis_text as label
                    from clinical_diagnosis diagnosis join clinical_diagnosis_version version
                      on version.tenant_id=diagnosis.tenant_id and version.diagnosis_version_id=diagnosis.current_version_id
                    where diagnosis.tenant_id=:tenant and diagnosis.diagnosis_id=:resource
                      and diagnosis.patient_id=:patient and diagnosis.encounter_id=:encounter
                    """).param("tenant", tenant).param("resource", resource).param("patient", patient)
                    .param("encounter", encounter).query((rs, row) -> new ResolvedSource(
                            rs.getString("version_ref"), "诊断：" + rs.getString("label"))).optional().orElseThrow(DocumentEvidenceService::sourceDenied);
            case "ORDER" -> jdbc.sql("""
                    select 'row-'||row_version as version_ref, clinical_indication as label
                    from clinical_order where tenant_id=:tenant and order_id=:resource
                      and patient_id=:patient and encounter_id=:encounter
                    """).param("tenant", tenant).param("resource", resource).param("patient", patient)
                    .param("encounter", encounter).query((rs, row) -> new ResolvedSource(
                            rs.getString("version_ref"), "医嘱：" + rs.getString("label"))).optional().orElseThrow(DocumentEvidenceService::sourceDenied);
            case "RESULT" -> jdbc.sql("""
                    select result.current_version_id::text as version_ref, version.conclusion as label
                    from clinical_result result join clinical_result_version version
                      on version.tenant_id=result.tenant_id and version.result_version_id=result.current_version_id
                    where result.tenant_id=:tenant and result.result_id=:resource
                      and result.patient_id=:patient and result.encounter_id=:encounter
                    """).param("tenant", tenant).param("resource", resource).param("patient", patient)
                    .param("encounter", encounter).query((rs, row) -> new ResolvedSource(
                            rs.getString("version_ref"), "结果：" + rs.getString("label"))).optional().orElseThrow(DocumentEvidenceService::sourceDenied);
            case "ATTACHMENT" -> jdbc.sql("""
                    select content_hash as version_ref, original_filename as label
                    from clinical_document_attachment where tenant_id=:tenant and attachment_id=:resource
                      and patient_id=:patient and encounter_id=:encounter and storage_status='AVAILABLE'
                    """).param("tenant", tenant).param("resource", resource).param("patient", patient)
                    .param("encounter", encounter).query((rs, row) -> new ResolvedSource(
                            rs.getString("version_ref"), "附件：" + rs.getString("label"))).optional().orElseThrow(DocumentEvidenceService::sourceDenied);
            default -> throw invalid("Unsupported document source type");
        };
    }

    private UUID insertReference(
            ClinicalIdentity identity, DocumentHead document, String type, UUID resource, String versionRef,
            String targetFieldPath, String label, String excerptHash) {
        if (!targetFieldPath.matches("^sections\\.[A-Za-z0-9_.-]+$")) throw invalid("Invalid target field path");
        UUID referenceId = UUID.randomUUID();
        return jdbc.sql("""
                insert into clinical_document_source_reference(
                  tenant_id, source_reference_id, document_id, document_version_id, patient_id, encounter_id,
                  source_type, source_resource_id, source_version_ref, target_field_path, display_label,
                  excerpt_hash, recorded_by)
                values (:tenant,:reference,:document,:version,:patient,:encounter,:source_type,:resource,
                  :version_ref,:field_path,:label,:excerpt_hash,:actor)
                returning source_reference_id
                """).param("tenant", identity.tenantId()).param("reference", referenceId)
                .param("document", document.documentId()).param("version", document.currentVersionId())
                .param("patient", document.patientId()).param("encounter", document.encounterId())
                .param("source_type", type).param("resource", resource).param("version_ref", versionRef)
                .param("field_path", targetFieldPath).param("label", label).param("excerpt_hash", excerptHash)
                .param("actor", identity.userId()).query(UUID.class).single();
    }

    private DocumentAttachmentWire attachment(UUID tenant, UUID attachmentId) {
        return jdbc.sql("""
                select attachment_id, document_id, document_version_id, original_filename, media_type,
                  byte_size, content_hash, storage_status, malware_scan_status, uploaded_by, created_at,
                  (select event_type from clinical_document_evidence_lifecycle_event lifecycle
                    where lifecycle.tenant_id=attachment.tenant_id and lifecycle.evidence_type='ATTACHMENT'
                      and lifecycle.evidence_id=attachment.attachment_id
                    order by occurred_at desc,lifecycle_event_id desc limit 1) lifecycle_event_type,
                  (select replacement_evidence_id from clinical_document_evidence_lifecycle_event lifecycle
                    where lifecycle.tenant_id=attachment.tenant_id and lifecycle.evidence_type='ATTACHMENT'
                      and lifecycle.evidence_id=attachment.attachment_id
                    order by occurred_at desc,lifecycle_event_id desc limit 1) replacement_evidence_id,
                  (select reason from clinical_document_evidence_lifecycle_event lifecycle
                    where lifecycle.tenant_id=attachment.tenant_id and lifecycle.evidence_type='ATTACHMENT'
                      and lifecycle.evidence_id=attachment.attachment_id
                    order by occurred_at desc,lifecycle_event_id desc limit 1) lifecycle_reason
                from clinical_document_attachment attachment
                where tenant_id=:tenant and attachment_id=:attachment
                """).param("tenant", tenant).param("attachment", attachmentId)
                .query((rs, row) -> attachmentWire(rs)).single();
    }

    private DocumentSourceReferenceWire reference(UUID tenant, UUID referenceId) {
        return jdbc.sql("""
                select source_reference_id, document_id, document_version_id, source_type, source_resource_id,
                  source_version_ref,
                  coalesce((select effective_target_field_path
                    from clinical_document_evidence_lifecycle_event lifecycle
                    where lifecycle.tenant_id=reference.tenant_id and lifecycle.evidence_type='SOURCE_REFERENCE'
                      and lifecycle.evidence_id=reference.source_reference_id and lifecycle.event_type='CORRECTED'
                    order by occurred_at desc,lifecycle_event_id desc limit 1), target_field_path) target_field_path,
                  display_label,
                  coalesce((select effective_excerpt_hash
                    from clinical_document_evidence_lifecycle_event lifecycle
                    where lifecycle.tenant_id=reference.tenant_id and lifecycle.evidence_type='SOURCE_REFERENCE'
                      and lifecycle.evidence_id=reference.source_reference_id and lifecycle.event_type='CORRECTED'
                    order by occurred_at desc,lifecycle_event_id desc limit 1), excerpt_hash) excerpt_hash,
                  recorded_by, recorded_at,
                  (select event_type from clinical_document_evidence_lifecycle_event lifecycle
                    where lifecycle.tenant_id=reference.tenant_id and lifecycle.evidence_type='SOURCE_REFERENCE'
                      and lifecycle.evidence_id=reference.source_reference_id
                    order by occurred_at desc,lifecycle_event_id desc limit 1) lifecycle_event_type,
                  (select reason from clinical_document_evidence_lifecycle_event lifecycle
                    where lifecycle.tenant_id=reference.tenant_id and lifecycle.evidence_type='SOURCE_REFERENCE'
                      and lifecycle.evidence_id=reference.source_reference_id
                    order by occurred_at desc,lifecycle_event_id desc limit 1) lifecycle_reason
                from clinical_document_source_reference reference
                where tenant_id=:tenant and source_reference_id=:reference
                """).param("tenant", tenant).param("reference", referenceId)
                .query((rs, row) -> referenceWire(tenant, rs)).single();
    }

    private DocumentAttachmentWire attachmentWire(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DocumentAttachmentWire(rs.getObject("attachment_id", UUID.class),
                rs.getObject("document_id", UUID.class), rs.getObject("document_version_id", UUID.class),
                rs.getString("original_filename"), rs.getString("media_type"), rs.getLong("byte_size"),
                rs.getString("content_hash"), rs.getString("storage_status"),
                rs.getString("malware_scan_status"), attachmentState(rs.getString("lifecycle_event_type")),
                rs.getObject("replacement_evidence_id", UUID.class), rs.getString("lifecycle_reason"),
                rs.getObject("uploaded_by", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private DocumentSourceReferenceWire referenceWire(UUID tenant, java.sql.ResultSet rs) throws java.sql.SQLException {
        String type = rs.getString("source_type");
        UUID resource = rs.getObject("source_resource_id", UUID.class);
        String captured = rs.getString("source_version_ref");
        String current = currentVersionRef(tenant, type, resource);
        String state = current == null ? "MISSING" : captured.equals(current) ? "CURRENT" : "STALE";
        String lifecycleEvent = rs.getString("lifecycle_event_type");
        return new DocumentSourceReferenceWire(rs.getObject("source_reference_id", UUID.class),
                rs.getObject("document_id", UUID.class), rs.getObject("document_version_id", UUID.class),
                type, resource, captured, current, state, rs.getString("target_field_path"),
                rs.getString("display_label"), rs.getString("excerpt_hash"),
                referenceState(lifecycleEvent), rs.getString("lifecycle_reason"),
                rs.getObject("recorded_by", UUID.class), rs.getObject("recorded_at", OffsetDateTime.class).toInstant());
    }

    private static String attachmentState(String lifecycleEvent) {
        return "SUPERSEDED".equals(lifecycleEvent) ? "SUPERSEDED"
                : "VOIDED".equals(lifecycleEvent) ? "VOID" : "ACTIVE";
    }

    private static String referenceState(String lifecycleEvent) {
        if ("REVOKED".equals(lifecycleEvent) || "VOIDED".equals(lifecycleEvent)) return "REVOKED";
        if ("SUPERSEDED".equals(lifecycleEvent)) return "SUPERSEDED";
        if ("CORRECTED".equals(lifecycleEvent)) return "CORRECTED";
        return "ACTIVE";
    }

    private String currentVersionRef(UUID tenant, String type, UUID resource) {
        String sql = switch (type) {
            case "DIAGNOSIS" -> "select current_version_id::text from clinical_diagnosis where tenant_id=:tenant and diagnosis_id=:resource";
            case "ORDER" -> "select 'row-'||row_version from clinical_order where tenant_id=:tenant and order_id=:resource";
            case "RESULT" -> "select current_version_id::text from clinical_result where tenant_id=:tenant and result_id=:resource";
            case "ATTACHMENT" -> "select content_hash from clinical_document_attachment where tenant_id=:tenant and attachment_id=:resource and storage_status='AVAILABLE'";
            default -> null;
        };
        if (sql == null) return null;
        return jdbc.sql(sql).param("tenant", tenant).param("resource", resource).query(String.class).optional().orElse(null);
    }

    private void begin(ClinicalIdentity identity, String scope, String key, String hash) {
        if (blank(key) || key.length() > 128) throw invalid("A valid Idempotency-Key is required");
        int inserted = jdbc.sql("""
                insert into idempotency_record(tenant_id,command_scope,idempotency_key,request_hash,state,trace_id,expires_at)
                values (:tenant,:scope,:key,:hash,'IN_PROGRESS',:trace,now()+interval '24 hours')
                on conflict (tenant_id,command_scope,idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", hash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) throw new ClinicalCommandException("IDEMPOTENCY_REPLAY", 409,
                "This document evidence command was already submitted");
    }

    private void complete(ClinicalIdentity identity, String scope, String key, UUID resource, int status) {
        jdbc.sql("""
                update idempotency_record set state='SUCCEEDED', response_status=:status,
                  response_ref=jsonb_build_object('resource_id',:resource)
                where tenant_id=:tenant and command_scope=:scope and idempotency_key=:key
                """).param("status", status).param("resource", resource).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void evidence(
            ClinicalIdentity identity, String action, UUID documentId, UUID patientId, UUID aggregateId,
            String eventType, String evidenceRef) {
        jdbc.sql("select tenant_id from tenant where tenant_id=:tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previous = jdbc.sql("select event_hash from audit_event where tenant_id=:tenant order by occurred_at desc,audit_event_id desc limit 1")
                .param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID(); String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId()+"|"+auditId+"|"+action+"|"+documentId+"|"+trace+"|"+previous);
        jdbc.sql("""
                insert into audit_event(tenant_id,audit_event_id,occurred_at,actor_user_id,action_code,
                  resource_type,resource_id,patient_ref_hash,trace_id,previous_hash,event_hash,details)
                values (:tenant,:audit,now(),:actor,:action,'CLINICAL_DOCUMENT',:document,:patient_hash,
                  :trace,:previous,:event_hash,jsonb_build_object('evidence_ref',:evidence_ref))
                """).param("tenant",identity.tenantId()).param("audit",auditId).param("actor",identity.userId())
                .param("action",action).param("document",documentId).param("patient_hash",sha256(identity.tenantId()+"|"+patientId))
                .param("trace",trace).param("previous",previous).param("event_hash",eventHash)
                .param("evidence_ref",evidenceRef).update();
        long aggregateVersion = jdbc.sql("""
                select coalesce(max(aggregate_version),0)+1 from outbox_event
                where tenant_id=:tenant and aggregate_type='CLINICAL_DOCUMENT_EVIDENCE'
                  and aggregate_id=:aggregate and event_type=:event_type
                """).param("tenant", identity.tenantId()).param("aggregate", aggregateId)
                .param("event_type", eventType).query(Long.class).single();
        jdbc.sql("""
                insert into outbox_event(tenant_id,event_id,aggregate_type,aggregate_id,aggregate_version,
                  event_type,schema_version,payload)
                values (:tenant,:event,'CLINICAL_DOCUMENT_EVIDENCE',:aggregate,:aggregate_version,:event_type,1,
                  jsonb_build_object('document_id',:document,'evidence_ref',:evidence_ref))
                """).param("tenant",identity.tenantId()).param("event",UUID.randomUUID())
                .param("aggregate",aggregateId).param("event_type",eventType).param("document",documentId)
                .param("aggregate_version", aggregateVersion)
                .param("evidence_ref",evidenceRef).update();
    }

    private static void validateMagic(String mediaType, byte[] content) {
        boolean valid = switch (mediaType) {
            case "application/pdf" -> starts(content, "%PDF-".getBytes(StandardCharsets.US_ASCII));
            case "image/png" -> starts(content, new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
            case "image/jpeg" -> content.length >= 3 && (content[0] & 0xff) == 0xff && (content[1] & 0xff) == 0xd8 && (content[2] & 0xff) == 0xff;
            case "application/dicom" -> content.length >= 132 && content[128] == 'D' && content[129] == 'I' && content[130] == 'C' && content[131] == 'M';
            case "text/plain" -> !starts(content, new byte[] {'M', 'Z'}) && !starts(content, new byte[] {0x7f, 'E', 'L', 'F'});
            default -> false;
        };
        if (!valid) throw new ClinicalCommandException("ATTACHMENT_MEDIA_MISMATCH", 422,
                "Attachment bytes do not match the declared media type");
    }

    private static boolean starts(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (content[i] != prefix[i]) return false;
        return true;
    }

    private static String safeFilename(String filename) {
        String value = filename.trim();
        if (value.length() > 512 || value.contains("/") || value.contains("\\") || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid("Attachment filename is unsafe");
        }
        return value;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static ClinicalCommandException invalid(String message) { return new ClinicalCommandException("VALIDATION_FAILED", 400, message); }
    private static ClinicalCommandException denied() { return new ClinicalCommandException("CONTEXT_NOT_PERMITTED", 403, "The requested clinical context is not permitted"); }
    private static ClinicalCommandException sourceDenied() { return new ClinicalCommandException("SOURCE_CONTEXT_NOT_PERMITTED", 403, "The source does not belong to the authorized patient and encounter"); }
    private static String sha256(String value) { return sha256(value.getBytes(StandardCharsets.UTF_8)); }
    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    record DocumentAttachmentCreateRequest(
            @JsonProperty("organization_id") UUID organizationId,
            @JsonProperty("facility_id") UUID facilityId,
            @JsonProperty("patient_id") UUID patientId,
            @JsonProperty("encounter_id") UUID encounterId,
            @JsonProperty("document_version_id") UUID documentVersionId,
            @JsonProperty("original_filename") String originalFilename,
            @JsonProperty("media_type") String mediaType,
            @JsonProperty("content_base64") String contentBase64,
            @JsonProperty("expected_sha256") String expectedSha256,
            @JsonProperty("target_field_path") String targetFieldPath,
            @JsonProperty("replaces_attachment_id") UUID replacesAttachmentId,
            @JsonProperty("replacement_reason") String replacementReason) { }

    record DocumentSourceReferenceCreateRequest(
            @JsonProperty("organization_id") UUID organizationId,
            @JsonProperty("facility_id") UUID facilityId,
            @JsonProperty("patient_id") UUID patientId,
            @JsonProperty("encounter_id") UUID encounterId,
            @JsonProperty("document_version_id") UUID documentVersionId,
            @JsonProperty("source_type") String sourceType,
            @JsonProperty("source_resource_id") UUID sourceResourceId,
            @JsonProperty("target_field_path") String targetFieldPath,
            String excerpt) { }

    record DocumentEvidenceLifecycleRequest(
            @JsonProperty("organization_id") UUID organizationId,
            @JsonProperty("facility_id") UUID facilityId,
            @JsonProperty("patient_id") UUID patientId,
            @JsonProperty("encounter_id") UUID encounterId,
            @JsonProperty("document_version_id") UUID documentVersionId,
            String reason) { }

    record DocumentSourceReferenceCorrectionRequest(
            @JsonProperty("organization_id") UUID organizationId,
            @JsonProperty("facility_id") UUID facilityId,
            @JsonProperty("patient_id") UUID patientId,
            @JsonProperty("encounter_id") UUID encounterId,
            @JsonProperty("document_version_id") UUID documentVersionId,
            @JsonProperty("target_field_path") String targetFieldPath,
            String excerpt,
            String reason) { }

    record DocumentAttachmentWire(
            @JsonProperty("attachment_id") UUID attachmentId,
            @JsonProperty("document_id") UUID documentId,
            @JsonProperty("document_version_id") UUID documentVersionId,
            @JsonProperty("original_filename") String originalFilename,
            @JsonProperty("media_type") String mediaType,
            @JsonProperty("byte_size") long byteSize,
            @JsonProperty("content_hash") String contentHash,
            @JsonProperty("storage_status") String storageStatus,
            @JsonProperty("malware_scan_status") String malwareScanStatus,
            @JsonProperty("evidence_state") String evidenceState,
            @JsonProperty("superseded_by_attachment_id") UUID supersededByAttachmentId,
            @JsonProperty("lifecycle_reason") String lifecycleReason,
            @JsonProperty("uploaded_by") UUID uploadedBy,
            @JsonProperty("created_at") java.time.Instant createdAt) { }

    record DocumentSourceReferenceWire(
            @JsonProperty("source_reference_id") UUID sourceReferenceId,
            @JsonProperty("document_id") UUID documentId,
            @JsonProperty("document_version_id") UUID documentVersionId,
            @JsonProperty("source_type") String sourceType,
            @JsonProperty("source_resource_id") UUID sourceResourceId,
            @JsonProperty("source_version_ref") String sourceVersionRef,
            @JsonProperty("current_version_ref") String currentVersionRef,
            @JsonProperty("freshness") String freshness,
            @JsonProperty("target_field_path") String targetFieldPath,
            @JsonProperty("display_label") String displayLabel,
            @JsonProperty("excerpt_hash") String excerptHash,
            @JsonProperty("evidence_state") String evidenceState,
            @JsonProperty("lifecycle_reason") String lifecycleReason,
            @JsonProperty("recorded_by") UUID recordedBy,
            @JsonProperty("recorded_at") java.time.Instant recordedAt) { }

    record DocumentSourceBundleWire(
            @JsonProperty("document_id") UUID documentId,
            @JsonProperty("document_version_id") UUID documentVersionId,
            List<DocumentAttachmentWire> attachments,
            List<DocumentSourceReferenceWire> references,
            @JsonProperty("data_watermark") String dataWatermark) { }

    private record DocumentHead(UUID documentId, UUID currentVersionId, UUID createdBy, String status, UUID patientId, UUID encounterId) { }
    private record ResolvedSource(String versionRef, String label) { }
}
