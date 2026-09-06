package org.haoemr.lab;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.LabSpecimenCollectRequestWire;
import org.haoemr.contracts.LabSpecimenCreateRequestWire;
import org.haoemr.contracts.LabSpecimenReceiveRequestWire;
import org.haoemr.contracts.LabSpecimenRejectRequestWire;
import org.haoemr.contracts.LabSpecimenWire;
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
final class LabSpecimenController {
    private final ClinicalCommandSecurity security;
    private final LabSpecimenService specimens;

    LabSpecimenController(ClinicalCommandSecurity security, LabSpecimenService specimens) {
        this.security = security;
        this.specimens = specimens;
    }

    @GetMapping("/lab-specimens")
    ResponseEntity<List<LabSpecimenWire>> list(
            HttpServletRequest request,
            @RequestParam("encounter_id") UUID encounterId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId,
            @RequestHeader("X-Encounter-Context") UUID encounterContextId) {
        if (!encounterId.equals(encounterContextId)) throw LabSpecimenService.contextDenied();
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(specimens.listSpecimens(identity, organizationId, facilityId, patientId, encounterId));
    }

    @PostMapping("/lab-specimens")
    ResponseEntity<LabSpecimenWire> create(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody LabSpecimenCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(specimens.createSpecimen(identity, idempotencyKey, command));
    }

    @PostMapping("/lab-specimens/{specimen_id}/collections")
    ResponseEntity<LabSpecimenWire> collect(
            HttpServletRequest request,
            @PathVariable("specimen_id") UUID specimenId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody LabSpecimenCollectRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(specimens.collectSpecimen(identity, idempotencyKey, specimenId, command));
    }

    @PostMapping("/lab-specimens/{specimen_id}/receptions")
    ResponseEntity<LabSpecimenWire> receive(
            HttpServletRequest request,
            @PathVariable("specimen_id") UUID specimenId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody LabSpecimenReceiveRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(specimens.receiveSpecimen(identity, idempotencyKey, specimenId, command));
    }

    @PostMapping("/lab-specimens/{specimen_id}/rejections")
    ResponseEntity<LabSpecimenWire> reject(
            HttpServletRequest request,
            @PathVariable("specimen_id") UUID specimenId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody LabSpecimenRejectRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(specimens.rejectSpecimen(identity, idempotencyKey, specimenId, command));
    }
}
