package org.haoemr.clinical;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.haoemr.security.ClinicalCommandSecurity;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/documents/{documentId}/signature-verifications")
final class DocumentSignatureVerificationController {
    private final ClinicalCommandSecurity security;
    private final DocumentSignatureVerificationService verification;

    DocumentSignatureVerificationController(
            ClinicalCommandSecurity security, DocumentSignatureVerificationService verification) {
        this.security = security;
        this.verification = verification;
    }

    @PostMapping
    ResponseEntity<DocumentSignatureVerificationService.VerificationRun> verify(
            HttpServletRequest request,
            @PathVariable UUID documentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody VerificationRequest command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(),
                command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(verification.verify(
                identity, idempotencyKey, documentId, command.patientId(),
                command.encounterId(), command.documentVersionId()));
    }

    record VerificationRequest(
            UUID organizationId, UUID facilityId, UUID patientId,
            UUID encounterId, UUID documentVersionId) {}
}
