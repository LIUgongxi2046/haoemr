package org.haoemr.results;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ClinicalResultCorrectionRequestWire;
import org.haoemr.contracts.ClinicalResultCreateRequestWire;
import org.haoemr.contracts.ClinicalResultWire;
import org.haoemr.contracts.CriticalValueAcknowledgeRequestWire;
import org.haoemr.contracts.CriticalValueDispositionRequestWire;
import org.haoemr.contracts.CriticalValueWire;
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
final class ResultController {
    private final ClinicalCommandSecurity security;
    private final ResultService results;

    ResultController(ClinicalCommandSecurity security, ResultService results) {
        this.security = security;
        this.results = results;
    }

    @PostMapping("/results")
    ResponseEntity<ClinicalResultWire> create(
            HttpServletRequest httpRequest, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ClinicalResultCreateRequestWire request) {
        ClinicalIdentity identity = authorize(httpRequest, request.organizationId(), request.facilityId(),
                request.patientId(), request.encounterId());
        ClinicalResultWire result = results.create(identity, idempotencyKey, request);
        return ResponseEntity.created(URI.create("/api/v1/results/" + result.resultId()))
                .eTag("\"" + result.rowVersion() + "\"").cacheControl(CacheControl.noStore()).body(result);
    }

    @GetMapping("/results")
    ResponseEntity<List<ClinicalResultWire>> list(
            HttpServletRequest httpRequest, @RequestParam("encounter_id") UUID encounterId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId,
            @RequestHeader("X-Encounter-Context") UUID encounterContextId) {
        if (!encounterId.equals(encounterContextId)) throw ResultService.contextDenied();
        ClinicalIdentity identity = authorize(httpRequest, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(results.list(identity, patientId, encounterId, facilityId));
    }

    @PostMapping("/results/{resultId}/corrections")
    ResponseEntity<ClinicalResultWire> correct(
            HttpServletRequest httpRequest, @PathVariable UUID resultId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ClinicalResultCorrectionRequestWire request) {
        ClinicalIdentity identity = authorize(httpRequest, request.organizationId(), request.facilityId(),
                request.patientId(), request.encounterId());
        ClinicalResultWire result = results.correct(identity, idempotencyKey, resultId, request);
        return ResponseEntity.ok().eTag("\"" + result.rowVersion() + "\"")
                .header("X-Data-Watermark", result.dataWatermark())
                .cacheControl(CacheControl.noStore()).body(result);
    }

    @PostMapping("/critical-values/{criticalValueId}/acknowledge")
    ResponseEntity<CriticalValueWire> acknowledge(
            HttpServletRequest httpRequest, @PathVariable UUID criticalValueId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CriticalValueAcknowledgeRequestWire request) {
        ClinicalIdentity identity = authorize(httpRequest, request.organizationId(), request.facilityId(),
                request.patientId(), request.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(results.acknowledge(identity, idempotencyKey, criticalValueId, request));
    }

    @PostMapping("/critical-values/{criticalValueId}/dispositions")
    ResponseEntity<CriticalValueWire> dispose(
            HttpServletRequest httpRequest, @PathVariable UUID criticalValueId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CriticalValueDispositionRequestWire request) {
        ClinicalIdentity identity = authorize(httpRequest, request.organizationId(), request.facilityId(),
                request.patientId(), request.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(results.dispose(identity, idempotencyKey, criticalValueId, request));
    }

    private ClinicalIdentity authorize(
            HttpServletRequest request, UUID organization, UUID facility, UUID patient, UUID encounter) {
        return security.authorize(request, organization, facility, patient, encounter);
    }
}
