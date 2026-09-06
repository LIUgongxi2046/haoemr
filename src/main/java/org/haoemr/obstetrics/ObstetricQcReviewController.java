package org.haoemr.obstetrics;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ObstetricQcReviewCreateRequestWire;
import org.haoemr.contracts.ObstetricQcReviewWire;
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
final class ObstetricQcReviewController {
    private final ClinicalCommandSecurity security;
    private final ObstetricQcReviewService reviews;

    ObstetricQcReviewController(ClinicalCommandSecurity security, ObstetricQcReviewService reviews) {
        this.security = security;
        this.reviews = reviews;
    }

    @GetMapping("/obstetric-qc-reviews")
    ResponseEntity<List<ObstetricQcReviewWire>> list(
            HttpServletRequest request,
            @RequestParam("patient_id") UUID patientId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientContextId) {
        if (!patientId.equals(patientContextId)) throw ObstetricQcReviewService.contextDenied();
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(reviews.listRecords(identity, patientId));
    }

    @PostMapping("/obstetric-qc-reviews")
    ResponseEntity<ObstetricQcReviewWire> record(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ObstetricQcReviewCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(reviews.record(identity, idempotencyKey, command));
    }
}
