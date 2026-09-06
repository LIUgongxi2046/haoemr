package org.haoemr.diagnosis;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ClinicalDiagnosisWire;
import org.haoemr.contracts.DiagnosisConfirmRequestWire;
import org.haoemr.contracts.DiagnosisControlRequestWire;
import org.haoemr.contracts.DiagnosisCorrectRequestWire;
import org.haoemr.contracts.DiagnosisCreateRequestWire;
import org.haoemr.contracts.DiagnosisTerminologyEntryWire;
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
final class DiagnosisController {
    private final ClinicalCommandSecurity security;
    private final DiagnosisService diagnoses;

    DiagnosisController(ClinicalCommandSecurity security, DiagnosisService diagnoses) {
        this.security = security;
        this.diagnoses = diagnoses;
    }

    @PostMapping("/diagnoses")
    ResponseEntity<ClinicalDiagnosisWire> create(
            HttpServletRequest httpRequest,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DiagnosisCreateRequestWire request) {
        ClinicalIdentity identity = authorize(httpRequest, request.organizationId(), request.facilityId(),
                request.patientId(), request.encounterId());
        ClinicalDiagnosisWire diagnosis = diagnoses.create(identity, idempotencyKey, request);
        return ResponseEntity.created(URI.create("/api/v1/diagnoses/" + diagnosis.diagnosisId()))
                .eTag("\"" + diagnosis.rowVersion() + "\"").cacheControl(CacheControl.noStore()).body(diagnosis);
    }

    @GetMapping("/diagnoses")
    ResponseEntity<List<ClinicalDiagnosisWire>> list(
            HttpServletRequest httpRequest,
            @RequestParam("encounter_id") UUID encounterId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId,
            @RequestHeader("X-Encounter-Context") UUID encounterContextId) {
        if (!encounterId.equals(encounterContextId)) throw DiagnosisService.contextDenied();
        ClinicalIdentity identity = authorize(httpRequest, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(diagnoses.list(identity, patientId, encounterId, facilityId));
    }

    @GetMapping("/diagnosis-terminology")
    ResponseEntity<List<DiagnosisTerminologyEntryWire>> terminology(
            HttpServletRequest httpRequest,
            @RequestParam(value = "query", required = false, defaultValue = "") String query,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId,
            @RequestHeader("X-Encounter-Context") UUID encounterId) {
        authorize(httpRequest, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(diagnoses.searchTerminology(query, limit));
    }

    @PostMapping("/diagnoses/{diagnosisId}/confirm")
    ResponseEntity<ClinicalDiagnosisWire> confirm(
            HttpServletRequest httpRequest, @PathVariable UUID diagnosisId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DiagnosisConfirmRequestWire request) {
        ClinicalIdentity identity = authorize(httpRequest, request.organizationId(), request.facilityId(),
                request.patientId(), request.encounterId());
        return response(diagnoses.confirm(identity, idempotencyKey, diagnosisId, request));
    }

    @PostMapping("/diagnoses/{diagnosisId}/correct")
    ResponseEntity<ClinicalDiagnosisWire> correct(
            HttpServletRequest httpRequest, @PathVariable UUID diagnosisId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DiagnosisCorrectRequestWire request) {
        ClinicalIdentity identity = authorize(httpRequest, request.organizationId(), request.facilityId(),
                request.patientId(), request.encounterId());
        return response(diagnoses.correct(identity, idempotencyKey, diagnosisId, request));
    }

    @PostMapping("/diagnoses/{diagnosisId}/stop")
    ResponseEntity<ClinicalDiagnosisWire> stop(
            HttpServletRequest httpRequest, @PathVariable UUID diagnosisId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DiagnosisControlRequestWire request) {
        ClinicalIdentity identity = authorize(httpRequest, request.organizationId(), request.facilityId(),
                request.patientId(), request.encounterId());
        return response(diagnoses.stop(identity, idempotencyKey, diagnosisId, request));
    }

    private ClinicalIdentity authorize(
            HttpServletRequest request, UUID organization, UUID facility, UUID patient, UUID encounter) {
        return security.authorize(request, organization, facility, patient, encounter);
    }

    private static ResponseEntity<ClinicalDiagnosisWire> response(ClinicalDiagnosisWire diagnosis) {
        return ResponseEntity.ok().eTag("\"" + diagnosis.rowVersion() + "\"")
                .header("X-Data-Watermark", diagnosis.dataWatermark())
                .cacheControl(CacheControl.noStore()).body(diagnosis);
    }
}
