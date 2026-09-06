package org.haoemr.pharmacy;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.PharmacyDispensingPrepareRequestWire;
import org.haoemr.contracts.PharmacyDispensingTransitionRequestWire;
import org.haoemr.contracts.PharmacyDispensingUpdateRequestWire;
import org.haoemr.contracts.PharmacyDispensingVoidRequestWire;
import org.haoemr.contracts.PharmacyDispensingWire;
import org.haoemr.security.ClinicalCommandSecurity;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
final class PharmacyDispensingController {
    private final ClinicalCommandSecurity security;
    private final PharmacyDispensingService dispensings;

    PharmacyDispensingController(ClinicalCommandSecurity security, PharmacyDispensingService dispensings) {
        this.security = security;
        this.dispensings = dispensings;
    }

    @GetMapping("/pharmacy-dispensings")
    ResponseEntity<List<PharmacyDispensingWire>> list(
            HttpServletRequest request,
            @RequestParam("patient_id") UUID patientId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientContextId) {
        if (!patientId.equals(patientContextId)) throw PharmacyDispensingService.contextDenied();
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(dispensings.listDispensings(identity, patientId, facilityId));
    }

    @PostMapping("/pharmacy-dispensings")
    ResponseEntity<PharmacyDispensingWire> prepare(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PharmacyDispensingPrepareRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(dispensings.prepare(identity, idempotencyKey, command));
    }

    @PostMapping("/pharmacy-dispensings/{dispensing_id}/transitions")
    ResponseEntity<PharmacyDispensingWire> transition(
            HttpServletRequest request,
            @PathVariable("dispensing_id") UUID dispensingId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PharmacyDispensingTransitionRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(dispensings.transition(identity, idempotencyKey, dispensingId, command));
    }

    @PatchMapping("/pharmacy-dispensings/{dispensing_id}")
    ResponseEntity<PharmacyDispensingWire> update(
            HttpServletRequest request,
            @PathVariable("dispensing_id") UUID dispensingId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PharmacyDispensingUpdateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(dispensings.update(identity, idempotencyKey, dispensingId, command));
    }

    @PostMapping("/pharmacy-dispensings/{dispensing_id}/voids")
    ResponseEntity<PharmacyDispensingWire> voidDispensing(
            HttpServletRequest request,
            @PathVariable("dispensing_id") UUID dispensingId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PharmacyDispensingVoidRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(dispensings.voidDispensing(identity, idempotencyKey, dispensingId, command));
    }
}
