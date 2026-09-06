package org.haoemr.emergency;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.EmergencyPreadmissionLinkRequestWire;
import org.haoemr.contracts.EmergencyPreadmissionRegisterRequestWire;
import org.haoemr.contracts.EmergencyPreadmissionUpdateRequestWire;
import org.haoemr.contracts.EmergencyPreadmissionVoidRequestWire;
import org.haoemr.contracts.EmergencyPreadmissionWire;
import org.haoemr.security.ClinicalCommandSecurity;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
final class EmergencyPreadmissionController {
    private final ClinicalCommandSecurity security;
    private final EmergencyPreadmissionService preadmissions;

    EmergencyPreadmissionController(ClinicalCommandSecurity security, EmergencyPreadmissionService preadmissions) {
        this.security = security;
        this.preadmissions = preadmissions;
    }

    @GetMapping("/emergency-preadmissions")
    ResponseEntity<List<EmergencyPreadmissionWire>> list(
            HttpServletRequest request,
            @RequestParam("facility_id") UUID facilityId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityContextId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(preadmissions.listPreadmissions(identity, facilityId));
    }

    @PostMapping("/emergency-preadmissions")
    ResponseEntity<EmergencyPreadmissionWire> register(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody EmergencyPreadmissionRegisterRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(preadmissions.register(identity, idempotencyKey, command));
    }

    @PostMapping("/emergency-preadmissions/{preadmission_id}/links")
    ResponseEntity<EmergencyPreadmissionWire> link(
            HttpServletRequest request,
            @PathVariable("preadmission_id") UUID preadmissionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody EmergencyPreadmissionLinkRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.registeredPatientId(), null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(preadmissions.link(identity, idempotencyKey, preadmissionId, command));
    }

    @PutMapping("/emergency-preadmissions/{preadmission_id}")
    ResponseEntity<EmergencyPreadmissionWire> update(
            HttpServletRequest request,
            @PathVariable("preadmission_id") UUID preadmissionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody EmergencyPreadmissionUpdateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(preadmissions.update(identity, idempotencyKey, preadmissionId, command));
    }

    @PostMapping("/emergency-preadmissions/{preadmission_id}/voids")
    ResponseEntity<EmergencyPreadmissionWire> voidPreadmission(
            HttpServletRequest request,
            @PathVariable("preadmission_id") UUID preadmissionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody EmergencyPreadmissionVoidRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(preadmissions.voidPreadmission(identity, idempotencyKey, preadmissionId, command));
    }
}
