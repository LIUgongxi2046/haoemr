package org.haoemr.quality;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.DataQualityFindingTransitionRequestWire;
import org.haoemr.contracts.DataQualityFindingWire;
import org.haoemr.contracts.DataQualityScanRunWire;
import org.haoemr.contracts.DataQualityScanStartRequestWire;
import org.haoemr.contracts.DataQualityTriageAdviceWire;
import org.haoemr.contracts.DataQualityTriageRequestWire;
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
final class DataQualityOperationsController {
    private final ClinicalCommandSecurity security;
    private final DataQualityOperationsService operations;

    DataQualityOperationsController(ClinicalCommandSecurity security, DataQualityOperationsService operations) {
        this.security = security;
        this.operations = operations;
    }

    @PostMapping("/data-quality-rules/{data_quality_rule_id}/scans")
    ResponseEntity<DataQualityScanRunWire> scan(
            HttpServletRequest request,
            @PathVariable("data_quality_rule_id") UUID ruleId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DataQualityScanStartRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(operations.scan(identity, idempotencyKey, command.facilityId(), ruleId));
    }

    @GetMapping("/data-quality-scans")
    ResponseEntity<List<DataQualityScanRunWire>> listScans(
            HttpServletRequest request,
            @RequestParam("data_quality_rule_id") UUID ruleId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(operations.listScans(identity, ruleId));
    }

    @GetMapping("/data-quality-findings")
    ResponseEntity<List<DataQualityFindingWire>> listFindings(
            HttpServletRequest request,
            @RequestParam(value = "data_quality_scan_id", required = false) UUID scanId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(operations.listFindings(identity, facilityId, scanId));
    }

    @PostMapping("/data-quality-findings/{data_quality_finding_id}/transitions")
    ResponseEntity<DataQualityFindingWire> transition(
            HttpServletRequest request,
            @PathVariable("data_quality_finding_id") UUID findingId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DataQualityFindingTransitionRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(operations.transition(identity, idempotencyKey, findingId, command));
    }

    @GetMapping("/data-quality-scans/{data_quality_scan_id}/triage-advice")
    ResponseEntity<List<DataQualityTriageAdviceWire>> listAdvice(
            HttpServletRequest request,
            @PathVariable("data_quality_scan_id") UUID scanId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(operations.listTriageAdvice(identity, scanId));
    }

    @PostMapping("/data-quality-scans/{data_quality_scan_id}/triage-advice")
    ResponseEntity<DataQualityTriageAdviceWire> createAdvice(
            HttpServletRequest request,
            @PathVariable("data_quality_scan_id") UUID scanId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DataQualityTriageRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(operations.createTriageAdvice(identity, idempotencyKey, scanId));
    }
}
