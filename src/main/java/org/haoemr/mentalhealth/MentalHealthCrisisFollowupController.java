package org.haoemr.mentalhealth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.MentalHealthCrisisFollowupCreateRequestWire;
import org.haoemr.contracts.MentalHealthCrisisFollowupWire;
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
final class MentalHealthCrisisFollowupController {
    private final ClinicalCommandSecurity security;
    private final MentalHealthCrisisFollowupService followups;

    MentalHealthCrisisFollowupController(ClinicalCommandSecurity security, MentalHealthCrisisFollowupService followups) {
        this.security = security;
        this.followups = followups;
    }

    @GetMapping("/mental-health-crisis-followups")
    ResponseEntity<List<MentalHealthCrisisFollowupWire>> list(
            HttpServletRequest request,
            @RequestParam("patient_id") UUID patientId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientContextId) {
        if (!patientId.equals(patientContextId)) throw MentalHealthCrisisFollowupService.contextDenied();
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(followups.listRecords(identity, patientId));
    }

    @PostMapping("/mental-health-crisis-followups")
    ResponseEntity<MentalHealthCrisisFollowupWire> record(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody MentalHealthCrisisFollowupCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(followups.record(identity, idempotencyKey, command));
    }
}
