package org.haoemr.clinical;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.haoemr.clinical.DocumentEvidenceService.DocumentAttachmentCreateRequest;
import org.haoemr.clinical.DocumentEvidenceService.DocumentAttachmentWire;
import org.haoemr.clinical.DocumentEvidenceService.DocumentEvidenceLifecycleRequest;
import org.haoemr.clinical.DocumentEvidenceService.DocumentSourceBundleWire;
import org.haoemr.clinical.DocumentEvidenceService.DocumentSourceReferenceCorrectionRequest;
import org.haoemr.clinical.DocumentEvidenceService.DocumentSourceReferenceCreateRequest;
import org.haoemr.clinical.DocumentEvidenceService.DocumentSourceReferenceWire;
import org.haoemr.security.ClinicalCommandSecurity;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/documents/{documentId}")
final class DocumentEvidenceController {
    private final ClinicalCommandSecurity security;
    private final DocumentEvidenceService evidence;

    DocumentEvidenceController(ClinicalCommandSecurity security, DocumentEvidenceService evidence) {
        this.security = security;
        this.evidence = evidence;
    }

    @PostMapping("/attachments")
    ResponseEntity<DocumentAttachmentWire> upload(
            HttpServletRequest httpRequest,
            @PathVariable UUID documentId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody DocumentAttachmentCreateRequest request) {
        var identity = security.authorize(httpRequest, request.organizationId(), request.facilityId(),
                request.patientId(), request.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(evidence.upload(identity, key, documentId, request));
    }

    @PostMapping("/source-references")
    ResponseEntity<DocumentSourceReferenceWire> addReference(
            HttpServletRequest httpRequest,
            @PathVariable UUID documentId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody DocumentSourceReferenceCreateRequest request) {
        var identity = security.authorize(httpRequest, request.organizationId(), request.facilityId(),
                request.patientId(), request.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(evidence.addReference(identity, key, documentId, request));
    }

    @PostMapping("/attachments/{attachmentId}/voids")
    ResponseEntity<DocumentAttachmentWire> voidAttachment(
            HttpServletRequest httpRequest,
            @PathVariable UUID documentId,
            @PathVariable UUID attachmentId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody DocumentEvidenceLifecycleRequest request) {
        var identity = security.authorize(httpRequest, request.organizationId(), request.facilityId(),
                request.patientId(), request.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(evidence.voidAttachment(identity, key, documentId, attachmentId, request));
    }

    @PostMapping("/source-references/{sourceReferenceId}/corrections")
    ResponseEntity<DocumentSourceReferenceWire> correctReference(
            HttpServletRequest httpRequest,
            @PathVariable UUID documentId,
            @PathVariable UUID sourceReferenceId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody DocumentSourceReferenceCorrectionRequest request) {
        var identity = security.authorize(httpRequest, request.organizationId(), request.facilityId(),
                request.patientId(), request.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(evidence.correctReference(identity, key, documentId, sourceReferenceId, request));
    }

    @PostMapping("/source-references/{sourceReferenceId}/revocations")
    ResponseEntity<DocumentSourceReferenceWire> revokeReference(
            HttpServletRequest httpRequest,
            @PathVariable UUID documentId,
            @PathVariable UUID sourceReferenceId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody DocumentEvidenceLifecycleRequest request) {
        var identity = security.authorize(httpRequest, request.organizationId(), request.facilityId(),
                request.patientId(), request.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(evidence.revokeReference(identity, key, documentId, sourceReferenceId, request));
    }

    @GetMapping("/sources")
    ResponseEntity<DocumentSourceBundleWire> sources(
            HttpServletRequest httpRequest,
            @PathVariable UUID documentId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId,
            @RequestHeader("X-Encounter-Context") UUID encounterId,
            @RequestParam("document_version_id") UUID documentVersionId) {
        var identity = security.authorize(httpRequest, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(evidence.bundle(identity, documentId, documentVersionId, patientId, encounterId));
    }
}
