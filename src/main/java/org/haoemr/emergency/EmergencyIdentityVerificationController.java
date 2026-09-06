package org.haoemr.emergency;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.EmergencyIdentityVerificationCreateRequestWire;
import org.haoemr.contracts.EmergencyIdentityVerificationWire;
import org.haoemr.security.ClinicalCommandSecurity;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
final class EmergencyIdentityVerificationController {
    private final ClinicalCommandSecurity security;
    private final EmergencyIdentityVerificationService verifications;

    EmergencyIdentityVerificationController(
            ClinicalCommandSecurity security, EmergencyIdentityVerificationService verifications) {
        this.security = security;
        this.verifications = verifications;
    }

    @GetMapping("/emergency-identity-verifications")
    ResponseEntity<List<EmergencyIdentityVerificationWire>> list(
            HttpServletRequest request,
            @RequestParam("patient_id") UUID patientId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientContextId) {
        if (!patientId.equals(patientContextId)) throw EmergencyIdentityVerificationService.contextDenied();
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(verifications.list(identity, patientId));
    }

    @PostMapping("/emergency-identity-verifications")
    ResponseEntity<EmergencyIdentityVerificationWire> create(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody EmergencyIdentityVerificationCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(verifications.verify(identity, idempotencyKey, command));
    }
}
