package org.haoemr.patient;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.patient.PatientIdentityService.CandidateCreateRequest;
import org.haoemr.patient.PatientIdentityService.DemographicCorrectionRequest;
import org.haoemr.patient.PatientIdentityService.DemographicVersionWire;
import org.haoemr.patient.PatientIdentityService.MergeApprovalRequest;
import org.haoemr.patient.PatientIdentityService.MergeCaseCreateRequest;
import org.haoemr.patient.PatientIdentityService.MergeCaseWire;
import org.haoemr.patient.PatientIdentityService.MatchCandidateWire;
import org.haoemr.patient.PatientIdentityService.ReversalApprovalRequest;
import org.haoemr.patient.PatientIdentityService.ReversalRequest;
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
final class PatientIdentityController {
    private final ClinicalCommandSecurity security;
    private final PatientIdentityService identities;

    PatientIdentityController(ClinicalCommandSecurity security, PatientIdentityService identities) {
        this.security = security;
        this.identities = identities;
    }

    @PostMapping("/patient-match-candidates")
    ResponseEntity<MatchCandidateWire> detect(HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String key, @RequestBody CandidateCreateRequest body) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(identities.detect(security.authenticate(request), key, body));
    }

    @GetMapping("/patient-match-candidates")
    ResponseEntity<List<MatchCandidateWire>> candidates(HttpServletRequest request,
            @RequestParam(defaultValue = "OPEN") String status) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(identities.candidates(security.authenticate(request), status));
    }

    @PostMapping("/patients/{patientId}/identity-corrections")
    ResponseEntity<DemographicVersionWire> correct(HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String key, @PathVariable UUID patientId,
            @RequestBody DemographicCorrectionRequest body) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(identities.correct(security.authenticate(request), key, patientId, body));
    }

    @GetMapping("/patients/{patientId}/demographic-versions")
    ResponseEntity<List<DemographicVersionWire>> history(HttpServletRequest request, @PathVariable UUID patientId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(identities.history(security.authenticate(request), patientId));
    }

    @PostMapping("/patient-merge-cases")
    ResponseEntity<MergeCaseWire> requestMerge(HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String key, @RequestBody MergeCaseCreateRequest body) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(identities.requestMerge(security.authenticate(request), key, body));
    }

    @GetMapping("/patient-merge-cases")
    ResponseEntity<List<MergeCaseWire>> mergeCases(HttpServletRequest request,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(identities.mergeCases(security.authenticate(request), status));
    }

    @PostMapping("/patient-merge-cases/{caseId}/approve")
    MergeCaseWire approve(HttpServletRequest request, @RequestHeader("Idempotency-Key") String key,
            @PathVariable UUID caseId, @RequestBody MergeApprovalRequest body) {
        return identities.approveMerge(security.authenticate(request), key, caseId, body);
    }

    @PostMapping("/patient-merge-cases/{caseId}/reversal-requests")
    MergeCaseWire requestReversal(HttpServletRequest request, @RequestHeader("Idempotency-Key") String key,
            @PathVariable UUID caseId, @RequestBody ReversalRequest body) {
        return identities.requestReversal(security.authenticate(request), key, caseId, body);
    }

    @PostMapping("/patient-merge-cases/{caseId}/reversal-approve")
    MergeCaseWire approveReversal(HttpServletRequest request, @RequestHeader("Idempotency-Key") String key,
            @PathVariable UUID caseId, @RequestBody ReversalApprovalRequest body) {
        return identities.approveReversal(security.authenticate(request), key, caseId, body);
    }
}
