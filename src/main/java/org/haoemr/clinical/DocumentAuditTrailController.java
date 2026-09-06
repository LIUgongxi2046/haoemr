package org.haoemr.clinical;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.security.ClinicalCommandSecurity;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/documents/{documentId}/audit-events")
final class DocumentAuditTrailController {
    private final ClinicalCommandSecurity security;
    private final DocumentAuditTrailService audits;

    DocumentAuditTrailController(ClinicalCommandSecurity security, DocumentAuditTrailService audits) {
        this.security = security;
        this.audits = audits;
    }

    @GetMapping
    ResponseEntity<List<DocumentAuditTrailService.DocumentAuditEvent>> list(
            HttpServletRequest request,
            @PathVariable UUID documentId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId,
            @RequestHeader("X-Encounter-Context") UUID encounterId) {
        ClinicalIdentity identity = security.authorize(
                request, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(audits.list(identity, documentId, patientId, encounterId));
    }
}
