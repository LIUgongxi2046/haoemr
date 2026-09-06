package org.haoemr.approval;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ActionApprovalDecideRequestWire;
import org.haoemr.contracts.ActionApprovalProposeRequestWire;
import org.haoemr.contracts.ActionApprovalWire;
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
final class ActionApprovalController {
    private final ClinicalCommandSecurity security;
    private final ActionApprovalService approvals;

    ActionApprovalController(ClinicalCommandSecurity security, ActionApprovalService approvals) {
        this.security = security;
        this.approvals = approvals;
    }

    @GetMapping("/action-approvals")
    ResponseEntity<List<ActionApprovalWire>> list(
            HttpServletRequest request,
            @RequestParam("patient_id") UUID patientId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientContextId) {
        if (!patientId.equals(patientContextId)) throw ActionApprovalService.contextDenied();
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(approvals.listApprovals(identity, patientId));
    }

    @PostMapping("/action-approvals")
    ResponseEntity<ActionApprovalWire> propose(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ActionApprovalProposeRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(approvals.propose(identity, idempotencyKey, command));
    }

    @PostMapping("/action-approvals/{action_approval_id}/decisions")
    ResponseEntity<ActionApprovalWire> decide(
            HttpServletRequest request,
            @PathVariable("action_approval_id") UUID actionApprovalId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ActionApprovalDecideRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(approvals.decide(identity, idempotencyKey, actionApprovalId, command));
    }
}
