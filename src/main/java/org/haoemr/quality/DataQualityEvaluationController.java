package org.haoemr.quality;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.DataQualityEvaluationRecordRequestWire;
import org.haoemr.contracts.DataQualityEvaluationWire;
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
final class DataQualityEvaluationController {
    private final ClinicalCommandSecurity security;
    private final DataQualityEvaluationService evaluations;

    DataQualityEvaluationController(ClinicalCommandSecurity security, DataQualityEvaluationService evaluations) {
        this.security = security;
        this.evaluations = evaluations;
    }

    @GetMapping("/data-quality-evaluations")
    ResponseEntity<List<DataQualityEvaluationWire>> list(
            HttpServletRequest request,
            @RequestParam("data_quality_rule_id") UUID dataQualityRuleId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(evaluations.listEvaluations(identity, dataQualityRuleId));
    }

    @PostMapping("/data-quality-evaluations")
    ResponseEntity<DataQualityEvaluationWire> record(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DataQualityEvaluationRecordRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(evaluations.record(identity, idempotencyKey, command));
    }
}
