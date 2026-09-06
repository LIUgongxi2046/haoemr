package org.haoemr.agent;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.AgentRunBudgetConsumptionRecordRequestWire;
import org.haoemr.contracts.AgentRunBudgetConsumptionWire;
import org.haoemr.contracts.AgentRunBudgetSummaryWire;
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
final class AgentRunBudgetConsumptionController {
    private final ClinicalCommandSecurity security;
    private final AgentRunBudgetConsumptionService consumptions;

    AgentRunBudgetConsumptionController(ClinicalCommandSecurity security, AgentRunBudgetConsumptionService consumptions) {
        this.security = security;
        this.consumptions = consumptions;
    }

    @GetMapping("/agent-run-budget-consumptions")
    ResponseEntity<List<AgentRunBudgetConsumptionWire>> list(
            HttpServletRequest request,
            @RequestParam("budget_id") UUID budgetId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(consumptions.list(identity, budgetId));
    }

    @GetMapping("/agent-run-budget-summaries/{budget_id}")
    ResponseEntity<AgentRunBudgetSummaryWire> summary(
            HttpServletRequest request,
            @PathVariable("budget_id") UUID budgetId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(consumptions.summary(identity, budgetId));
    }

    @PostMapping("/agent-run-budget-consumptions")
    ResponseEntity<AgentRunBudgetConsumptionWire> record(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody AgentRunBudgetConsumptionRecordRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(consumptions.record(identity, idempotencyKey, command));
    }
}
