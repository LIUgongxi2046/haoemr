package org.haoemr.quality;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.DataQualityRuleDeactivateRequestWire;
import org.haoemr.contracts.DataQualityRuleRegisterRequestWire;
import org.haoemr.contracts.DataQualityRuleWire;
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
final class DataQualityRuleController {
    private final ClinicalCommandSecurity security;
    private final DataQualityRuleService rules;

    DataQualityRuleController(ClinicalCommandSecurity security, DataQualityRuleService rules) {
        this.security = security;
        this.rules = rules;
    }

    @GetMapping("/data-quality-rules")
    ResponseEntity<List<DataQualityRuleWire>> list(
            HttpServletRequest request,
            @RequestParam(value = "dimension", required = false) String dimension,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(rules.listRules(identity, dimension));
    }

    @PostMapping("/data-quality-rules")
    ResponseEntity<DataQualityRuleWire> register(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DataQualityRuleRegisterRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(rules.register(identity, idempotencyKey, command));
    }

    @PostMapping("/data-quality-rules/{data_quality_rule_id}/deactivations")
    ResponseEntity<DataQualityRuleWire> deactivate(
            HttpServletRequest request,
            @PathVariable("data_quality_rule_id") UUID dataQualityRuleId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DataQualityRuleDeactivateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(rules.deactivate(identity, idempotencyKey, dataQualityRuleId, command));
    }
}
