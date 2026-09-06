package org.haoemr.emergency;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.EmergencyTriageAssessmentCreateRequestWire;
import org.haoemr.contracts.EmergencyTriageAssessmentWire;
import org.haoemr.contracts.EmergencyClinicalFactVoidRequestWire;
import org.haoemr.security.ClinicalCommandSecurity;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
final class EmergencyTriageController {
    private final ClinicalCommandSecurity security;
    private final EmergencyTriageService assessments;

    EmergencyTriageController(ClinicalCommandSecurity security, EmergencyTriageService assessments) {
        this.security = security;
        this.assessments = assessments;
    }

    @GetMapping("/emergency-triage-assessments")
    ResponseEntity<List<EmergencyTriageAssessmentWire>> list(
            HttpServletRequest request,
            @RequestParam("patient_id") UUID patientId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientContextId) {
        if (!patientId.equals(patientContextId)) throw EmergencyTriageService.contextDenied();
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(assessments.listAssessments(identity, patientId));
    }

    @PostMapping("/emergency-triage-assessments")
    ResponseEntity<EmergencyTriageAssessmentWire> create(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody EmergencyTriageAssessmentCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(assessments.createAssessment(identity, idempotencyKey, command));
    }

    @PostMapping("/emergency-triage-assessments/{triage_assessment_id}/voids")
    ResponseEntity<EmergencyTriageAssessmentWire> voidAssessment(
            HttpServletRequest request,
            @PathVariable("triage_assessment_id") UUID assessmentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody EmergencyClinicalFactVoidRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(assessments.voidAssessment(identity, idempotencyKey, assessmentId, command));
    }
}
