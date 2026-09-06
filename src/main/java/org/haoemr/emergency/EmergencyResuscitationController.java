package org.haoemr.emergency;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.EmergencyResuscitationCompleteRequestWire;
import org.haoemr.contracts.EmergencyResuscitationStartRequestWire;
import org.haoemr.contracts.EmergencyResuscitationWire;
import org.haoemr.contracts.EmergencyClinicalFactVoidRequestWire;
import org.haoemr.security.ClinicalCommandSecurity;
import org.haoemr.security.ClinicalIdentity;
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
@RequestMapping("/api/v1")
final class EmergencyResuscitationController {
    private final ClinicalCommandSecurity security;
    private final EmergencyResuscitationService resuscitations;

    EmergencyResuscitationController(ClinicalCommandSecurity security, EmergencyResuscitationService resuscitations) {
        this.security = security;
        this.resuscitations = resuscitations;
    }

    @GetMapping("/emergency-resuscitations")
    ResponseEntity<List<EmergencyResuscitationWire>> list(
            HttpServletRequest request,
            @RequestParam("patient_id") UUID patientId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientContextId) {
        if (!patientId.equals(patientContextId)) throw EmergencyResuscitationService.contextDenied();
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(resuscitations.listResuscitations(identity, patientId));
    }

    @PostMapping("/emergency-resuscitations")
    ResponseEntity<EmergencyResuscitationWire> start(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody EmergencyResuscitationStartRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(resuscitations.start(identity, idempotencyKey, command));
    }

    @PostMapping("/emergency-resuscitations/{resuscitation_id}/completions")
    ResponseEntity<EmergencyResuscitationWire> complete(
            HttpServletRequest request,
            @PathVariable("resuscitation_id") UUID resuscitationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody EmergencyResuscitationCompleteRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(resuscitations.complete(identity, idempotencyKey, resuscitationId, command));
    }

    @PostMapping("/emergency-resuscitations/{resuscitation_id}/voids")
    ResponseEntity<EmergencyResuscitationWire> voidResuscitation(
            HttpServletRequest request,
            @PathVariable("resuscitation_id") UUID resuscitationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody EmergencyClinicalFactVoidRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(resuscitations.voidResuscitation(identity, idempotencyKey, resuscitationId, command));
    }
}
