package org.haoemr.agent;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.AgentRunBudgetDeactivateRequestWire;
import org.haoemr.contracts.AgentRunBudgetDefineRequestWire;
import org.haoemr.contracts.AgentRunBudgetUpdateRequestWire;
import org.haoemr.contracts.AgentRunBudgetWire;
import org.haoemr.security.ClinicalCommandSecurity;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
final class AgentRunBudgetController {
    private final ClinicalCommandSecurity security;
    private final AgentRunBudgetService budgets;

    AgentRunBudgetController(ClinicalCommandSecurity security, AgentRunBudgetService budgets) {
        this.security = security;
        this.budgets = budgets;
    }

    @GetMapping("/agent-run-budgets")
    ResponseEntity<List<AgentRunBudgetWire>> list(
            HttpServletRequest request,
            @RequestParam(value = "status", required = false) String status,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(budgets.listBudgets(identity, status));
    }

    @PostMapping("/agent-run-budgets")
    ResponseEntity<AgentRunBudgetWire> define(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody AgentRunBudgetDefineRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(budgets.define(identity, idempotencyKey, command));
    }

    @PostMapping("/agent-run-budgets/{budget_id}/deactivations")
    ResponseEntity<AgentRunBudgetWire> deactivate(
            HttpServletRequest request,
            @PathVariable("budget_id") UUID budgetId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody AgentRunBudgetDeactivateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(budgets.deactivate(identity, idempotencyKey, budgetId, command));
    }

    @PutMapping("/agent-run-budgets/{budget_id}")
    ResponseEntity<AgentRunBudgetWire> update(
            HttpServletRequest request,
            @PathVariable("budget_id") UUID budgetId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody AgentRunBudgetUpdateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(budgets.update(identity, idempotencyKey, budgetId, command));
    }
}
