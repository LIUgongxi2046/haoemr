package org.haoemr.mentalhealth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.MentalHealthCrisisHandoverCreateRequestWire;
import org.haoemr.contracts.MentalHealthCrisisHandoverWire;
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
final class MentalHealthCrisisHandoverController {
    private final ClinicalCommandSecurity security;
    private final MentalHealthCrisisHandoverService handovers;

    MentalHealthCrisisHandoverController(ClinicalCommandSecurity security, MentalHealthCrisisHandoverService handovers) {
        this.security = security;
        this.handovers = handovers;
    }

    @GetMapping("/mental-health-crisis-handovers")
    ResponseEntity<List<MentalHealthCrisisHandoverWire>> list(
            HttpServletRequest request,
            @RequestParam("patient_id") UUID patientId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientContextId) {
        if (!patientId.equals(patientContextId)) throw MentalHealthCrisisHandoverService.contextDenied();
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(handovers.listRecords(identity, patientId));
    }

    @PostMapping("/mental-health-crisis-handovers")
    ResponseEntity<MentalHealthCrisisHandoverWire> record(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody MentalHealthCrisisHandoverCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(handovers.record(identity, idempotencyKey, command));
    }
}
