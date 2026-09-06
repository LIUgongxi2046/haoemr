package org.haoemr.emergency;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.EmergencyObservationCompleteRequestWire;
import org.haoemr.contracts.EmergencyObservationStartRequestWire;
import org.haoemr.contracts.EmergencyObservationWire;
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
final class EmergencyObservationController {
    private final ClinicalCommandSecurity security;
    private final EmergencyObservationService observations;

    EmergencyObservationController(ClinicalCommandSecurity security, EmergencyObservationService observations) {
        this.security = security;
        this.observations = observations;
    }

    @GetMapping("/emergency-observations")
    ResponseEntity<List<EmergencyObservationWire>> list(
            HttpServletRequest request,
            @RequestParam("patient_id") UUID patientId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientContextId) {
        if (!patientId.equals(patientContextId)) throw EmergencyObservationService.contextDenied();
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(observations.listObservations(identity, patientId));
    }

    @PostMapping("/emergency-observations")
    ResponseEntity<EmergencyObservationWire> start(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody EmergencyObservationStartRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(observations.startObservation(identity, idempotencyKey, command));
    }

    @PostMapping("/emergency-observations/{observation_id}/completions")
    ResponseEntity<EmergencyObservationWire> complete(
            HttpServletRequest request,
            @PathVariable("observation_id") UUID observationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody EmergencyObservationCompleteRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(observations.completeObservation(identity, idempotencyKey, observationId, command));
    }

    @PostMapping("/emergency-observations/{observation_id}/voids")
    ResponseEntity<EmergencyObservationWire> voidObservation(
            HttpServletRequest request,
            @PathVariable("observation_id") UUID observationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody EmergencyClinicalFactVoidRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(observations.voidObservation(identity, idempotencyKey, observationId, command));
    }
}
