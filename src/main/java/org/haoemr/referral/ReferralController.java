package org.haoemr.referral;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ReferralCreateRequestWire;
import org.haoemr.contracts.ReferralTransitionRequestWire;
import org.haoemr.contracts.ReferralUpdateRequestWire;
import org.haoemr.contracts.ReferralWire;
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
final class ReferralController {
    private final ClinicalCommandSecurity security;
    private final ReferralService referrals;

    ReferralController(ClinicalCommandSecurity security, ReferralService referrals) {
        this.security = security;
        this.referrals = referrals;
    }

    @GetMapping("/referrals")
    ResponseEntity<List<ReferralWire>> list(
            HttpServletRequest request,
            @RequestParam("patient_id") UUID patientId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientContextId) {
        if (!patientId.equals(patientContextId)) throw ReferralService.contextDenied();
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(referrals.listReferrals(identity, patientId));
    }

    @GetMapping("/referral-targets")
    ResponseEntity<List<ReferralService.ReferralTarget>> targets(
            HttpServletRequest request,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId) {
        ClinicalIdentity identity = security.authorize(
                request, organizationId, facilityId, patientId, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(referrals.listTargets(identity, facilityId));
    }

    @PostMapping("/referrals")
    ResponseEntity<ReferralWire> create(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ReferralCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(referrals.create(identity, idempotencyKey, command));
    }

    @PostMapping("/referrals/{referral_id}/transitions")
    ResponseEntity<ReferralWire> transition(
            HttpServletRequest request,
            @PathVariable("referral_id") UUID referralId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ReferralTransitionRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(referrals.transition(identity, idempotencyKey, referralId, command));
    }

    @PatchMapping("/referrals/{referral_id}")
    ResponseEntity<ReferralWire> update(
            HttpServletRequest request,
            @PathVariable("referral_id") UUID referralId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ReferralUpdateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(referrals.update(identity, idempotencyKey, referralId, command));
    }
}
