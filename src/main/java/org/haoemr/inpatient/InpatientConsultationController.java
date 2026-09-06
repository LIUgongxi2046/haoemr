package org.haoemr.inpatient;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.InpatientConsultationActionRequestWire;
import org.haoemr.contracts.InpatientConsultationCreateRequestWire;
import org.haoemr.contracts.InpatientConsultationOpinionRequestWire;
import org.haoemr.contracts.InpatientConsultationRejectRequestWire;
import org.haoemr.contracts.InpatientConsultationWire;
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
final class InpatientConsultationController {

    private final ClinicalCommandSecurity security;
    private final InpatientConsultationService consultations;

    InpatientConsultationController(
            ClinicalCommandSecurity security, InpatientConsultationService consultations) {
        this.security = security;
        this.consultations = consultations;
    }

    @GetMapping("/admissions/{admissionId}/consultations")
    ResponseEntity<List<InpatientConsultationWire>> list(
            HttpServletRequest request,
            @PathVariable UUID admissionId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId,
            @RequestHeader("X-Encounter-Context") UUID encounterId) {
        ClinicalIdentity identity = security.authorize(
                request, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(consultations.list(
                        identity, admissionId, organizationId, facilityId, patientId, encounterId));
    }

    @PostMapping("/admissions/{admissionId}/consultations")
    ResponseEntity<InpatientConsultationWire> create(
            HttpServletRequest httpRequest,
            @PathVariable UUID admissionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody InpatientConsultationCreateRequestWire request) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, request.organizationId(), request.facilityId(),
                request.patientId(), request.encounterId());
        InpatientConsultationWire consultation = consultations.create(
                identity, idempotencyKey, admissionId, request);
        return ResponseEntity.created(URI.create(
                        "/api/v1/inpatient/consultations/" + consultation.consultationId()))
                .eTag("\"" + consultation.rowVersion() + "\"")
                .header("X-Data-Watermark", consultation.dataWatermark())
                .cacheControl(CacheControl.noStore()).body(consultation);
    }

    @PostMapping("/consultations/{consultationId}/accept")
    ResponseEntity<InpatientConsultationWire> accept(
            HttpServletRequest httpRequest, @PathVariable UUID consultationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody InpatientConsultationActionRequestWire request) {
        ClinicalIdentity identity = authorize(httpRequest, request);
        return response(consultations.accept(identity, idempotencyKey, consultationId, request));
    }

    @PostMapping("/consultations/{consultationId}/reject")
    ResponseEntity<InpatientConsultationWire> reject(
            HttpServletRequest httpRequest, @PathVariable UUID consultationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody InpatientConsultationRejectRequestWire request) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, request.organizationId(), request.facilityId(),
                request.patientId(), request.encounterId());
        return response(consultations.reject(identity, idempotencyKey, consultationId, request));
    }

    @PostMapping("/consultations/{consultationId}/opinions")
    ResponseEntity<InpatientConsultationWire> signOpinion(
            HttpServletRequest httpRequest, @PathVariable UUID consultationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody InpatientConsultationOpinionRequestWire request) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, request.organizationId(), request.facilityId(),
                request.patientId(), request.encounterId());
        return response(consultations.signOpinion(identity, idempotencyKey, consultationId, request));
    }

    @PostMapping("/consultations/{consultationId}/complete")
    ResponseEntity<InpatientConsultationWire> complete(
            HttpServletRequest httpRequest, @PathVariable UUID consultationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody InpatientConsultationActionRequestWire request) {
        ClinicalIdentity identity = authorize(httpRequest, request);
        return response(consultations.complete(identity, idempotencyKey, consultationId, request));
    }

    private ClinicalIdentity authorize(
            HttpServletRequest httpRequest, InpatientConsultationActionRequestWire request) {
        return security.authorize(httpRequest, request.organizationId(), request.facilityId(),
                request.patientId(), request.encounterId());
    }

    private static ResponseEntity<InpatientConsultationWire> response(InpatientConsultationWire consultation) {
        return ResponseEntity.ok().eTag("\"" + consultation.rowVersion() + "\"")
                .header("X-Data-Watermark", consultation.dataWatermark())
                .cacheControl(CacheControl.noStore()).body(consultation);
    }
}
