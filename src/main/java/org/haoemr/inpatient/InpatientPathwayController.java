package org.haoemr.inpatient;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import org.haoemr.contracts.InpatientPathwayActionRequestWire;
import org.haoemr.contracts.InpatientPathwayEnrollRequestWire;
import org.haoemr.contracts.InpatientPathwayInstanceWire;
import org.haoemr.contracts.InpatientPathwayVarianceRequestWire;
import org.haoemr.contracts.InpatientPathwayVarianceReviewRequestWire;
import org.haoemr.contracts.InpatientPathwayWorkspaceWire;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inpatient")
final class InpatientPathwayController {

    private final ClinicalCommandSecurity security;
    private final InpatientPathwayService pathways;

    InpatientPathwayController(ClinicalCommandSecurity security, InpatientPathwayService pathways) {
        this.security = security;
        this.pathways = pathways;
    }

    @GetMapping("/admissions/{admissionId}/pathway-workspace")
    ResponseEntity<InpatientPathwayWorkspaceWire> workspace(
            HttpServletRequest request, @PathVariable UUID admissionId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId,
            @RequestHeader("X-Encounter-Context") UUID encounterId) {
        ClinicalIdentity identity = security.authorize(
                request, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                pathways.workspace(identity, admissionId, organizationId, facilityId, patientId, encounterId));
    }

    @PostMapping("/admissions/{admissionId}/pathways")
    ResponseEntity<InpatientPathwayInstanceWire> enroll(
            HttpServletRequest request, @PathVariable UUID admissionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody InpatientPathwayEnrollRequestWire body) {
        ClinicalIdentity identity = authorize(request, body.organizationId(), body.facilityId(),
                body.patientId(), body.encounterId());
        InpatientPathwayInstanceWire instance = pathways.enroll(identity, idempotencyKey, admissionId, body);
        return ResponseEntity.created(URI.create("/api/v1/inpatient/pathways/" + instance.pathwayInstanceId()))
                .eTag("\"" + instance.rowVersion() + "\"").header("X-Data-Watermark", instance.dataWatermark())
                .cacheControl(CacheControl.noStore()).body(instance);
    }

    @PostMapping("/pathways/{instanceId}/refresh")
    ResponseEntity<InpatientPathwayInstanceWire> refresh(
            HttpServletRequest request, @PathVariable UUID instanceId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody InpatientPathwayActionRequestWire body) {
        ClinicalIdentity identity = authorize(request, body.organizationId(), body.facilityId(),
                body.patientId(), body.encounterId());
        return response(pathways.refresh(identity, idempotencyKey, instanceId, body));
    }

    @PostMapping("/pathways/{instanceId}/advance")
    ResponseEntity<InpatientPathwayInstanceWire> advance(
            HttpServletRequest request, @PathVariable UUID instanceId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody InpatientPathwayActionRequestWire body) {
        ClinicalIdentity identity = authorize(request, body.organizationId(), body.facilityId(),
                body.patientId(), body.encounterId());
        return response(pathways.advance(identity, idempotencyKey, instanceId, body));
    }

    @PostMapping("/pathways/{instanceId}/complete")
    ResponseEntity<InpatientPathwayInstanceWire> complete(
            HttpServletRequest request, @PathVariable UUID instanceId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody InpatientPathwayActionRequestWire body) {
        ClinicalIdentity identity = authorize(request, body.organizationId(), body.facilityId(),
                body.patientId(), body.encounterId());
        return response(pathways.complete(identity, idempotencyKey, instanceId, body));
    }

    @PostMapping("/pathways/{instanceId}/variances")
    ResponseEntity<InpatientPathwayInstanceWire> requestVariance(
            HttpServletRequest request, @PathVariable UUID instanceId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody InpatientPathwayVarianceRequestWire body) {
        ClinicalIdentity identity = authorize(request, body.organizationId(), body.facilityId(),
                body.patientId(), body.encounterId());
        InpatientPathwayInstanceWire instance = pathways.requestVariance(identity, idempotencyKey, instanceId, body);
        return ResponseEntity.created(URI.create("/api/v1/inpatient/pathways/" + instanceId + "/variances"))
                .eTag("\"" + instance.rowVersion() + "\"").header("X-Data-Watermark", instance.dataWatermark())
                .cacheControl(CacheControl.noStore()).body(instance);
    }

    @PostMapping("/pathways/{instanceId}/variances/{varianceId}/review")
    ResponseEntity<InpatientPathwayInstanceWire> reviewVariance(
            HttpServletRequest request, @PathVariable UUID instanceId, @PathVariable UUID varianceId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody InpatientPathwayVarianceReviewRequestWire body) {
        ClinicalIdentity identity = authorize(request, body.organizationId(), body.facilityId(),
                body.patientId(), body.encounterId());
        return response(pathways.reviewVariance(identity, idempotencyKey, instanceId, varianceId, body));
    }

    private ClinicalIdentity authorize(
            HttpServletRequest request, UUID organizationId, UUID facilityId,
            UUID patientId, UUID encounterId) {
        return security.authorize(request, organizationId, facilityId, patientId, encounterId);
    }

    private static ResponseEntity<InpatientPathwayInstanceWire> response(InpatientPathwayInstanceWire instance) {
        return ResponseEntity.ok().eTag("\"" + instance.rowVersion() + "\"")
                .header("X-Data-Watermark", instance.dataWatermark())
                .cacheControl(CacheControl.noStore()).body(instance);
    }
}
