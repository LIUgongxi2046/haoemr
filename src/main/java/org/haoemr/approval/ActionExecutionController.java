package org.haoemr.approval;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ActionExecutionCreateRequestWire;
import org.haoemr.contracts.ActionExecutionTransitionRequestWire;
import org.haoemr.contracts.ActionExecutionWire;
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
final class ActionExecutionController {
    private final ClinicalCommandSecurity security;
    private final ActionExecutionService executions;

    ActionExecutionController(ClinicalCommandSecurity security, ActionExecutionService executions) {
        this.security = security;
        this.executions = executions;
    }

    @GetMapping("/action-executions")
    ResponseEntity<List<ActionExecutionWire>> list(
            HttpServletRequest request,
            @RequestParam("action_approval_id") UUID actionApprovalId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientContextId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientContextId, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(executions.list(identity, actionApprovalId));
    }

    @PostMapping("/action-executions")
    ResponseEntity<ActionExecutionWire> create(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ActionExecutionCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(executions.create(identity, idempotencyKey, command));
    }

    @PostMapping("/action-executions/{execution_id}/successes")
    ResponseEntity<ActionExecutionWire> succeed(
            HttpServletRequest request,
            @PathVariable("execution_id") UUID executionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ActionExecutionTransitionRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(executions.succeed(identity, idempotencyKey, executionId, command));
    }

    @PostMapping("/action-executions/{execution_id}/failures")
    ResponseEntity<ActionExecutionWire> fail(
            HttpServletRequest request,
            @PathVariable("execution_id") UUID executionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ActionExecutionTransitionRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(executions.fail(identity, idempotencyKey, executionId, command));
    }
}
