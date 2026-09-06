package org.haoemr.dermatology;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.DermatologyBiologicScreeningCreateRequestWire;
import org.haoemr.contracts.DermatologyBiologicScreeningWire;
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
final class DermatologyBiologicScreeningController {
    private final ClinicalCommandSecurity security;
    private final DermatologyBiologicScreeningService screenings;

    DermatologyBiologicScreeningController(
            ClinicalCommandSecurity security, DermatologyBiologicScreeningService screenings) {
        this.security = security;
        this.screenings = screenings;
    }

    @GetMapping("/dermatology-biologic-screenings")
    ResponseEntity<List<DermatologyBiologicScreeningWire>> list(
            HttpServletRequest request,
            @RequestParam("patient_id") UUID patientId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientContextId) {
        if (!patientId.equals(patientContextId)) throw DermatologyBiologicScreeningService.contextDenied();
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(screenings.listRecords(identity, patientId));
    }

    @PostMapping("/dermatology-biologic-screenings")
    ResponseEntity<DermatologyBiologicScreeningWire> record(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DermatologyBiologicScreeningCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(screenings.record(identity, idempotencyKey, command));
    }
}
