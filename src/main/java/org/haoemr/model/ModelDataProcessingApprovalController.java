package org.haoemr.model;

import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.haoemr.model.ModelDataProcessingApprovalService.ApprovalCommand;
import org.haoemr.model.ModelDataProcessingApprovalService.ApprovalView;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/model-deployments/{modelDeploymentId}/data-processing-approvals")
final class ModelDataProcessingApprovalController {

    private final ClinicalCommandSecurity security;
    private final ModelDataProcessingApprovalService approvals;

    ModelDataProcessingApprovalController(
            ClinicalCommandSecurity security, ModelDataProcessingApprovalService approvals) {
        this.security = security;
        this.approvals = approvals;
    }

    @GetMapping
    ResponseEntity<List<ApprovalView>> list(
            HttpServletRequest request,
            @PathVariable UUID modelDeploymentId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorizeForPurposes(
                request, organizationId, facilityId, null, null, Set.of("AI_PLATFORM_ADMIN"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(approvals.list(identity, modelDeploymentId));
    }

    @PostMapping
    ResponseEntity<ApprovalView> approve(
            HttpServletRequest request,
            @PathVariable UUID modelDeploymentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ApproveRequest command) {
        ClinicalIdentity identity = security.authorizeForPurposes(
                request, command.organizationId(), command.facilityId(), null, null, Set.of("AI_PLATFORM_ADMIN"));
        ApprovalView created = approvals.approve(identity, idempotencyKey, modelDeploymentId,
                new ApprovalCommand(command.legalBasis(), command.piaReference(),
                        command.processorAgreementReference(), command.endpointRegion(), command.retentionDays(),
                        command.allowedContextScopes(), command.expiresAt()));
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(created);
    }

    @PostMapping("/{approvalId}/revocations")
    ResponseEntity<ApprovalView> revoke(
            HttpServletRequest request,
            @PathVariable UUID modelDeploymentId,
            @PathVariable UUID approvalId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody RevokeRequest command) {
        ClinicalIdentity identity = security.authorizeForPurposes(
                request, command.organizationId(), command.facilityId(), null, null, Set.of("AI_PLATFORM_ADMIN"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(approvals.revoke(
                identity, idempotencyKey, modelDeploymentId, approvalId,
                command.expectedRowVersion(), command.reason()));
    }

    record ApproveRequest(UUID organizationId, UUID facilityId, String legalBasis, String piaReference,
            String processorAgreementReference, String endpointRegion, int retentionDays,
            List<String> allowedContextScopes, OffsetDateTime expiresAt) {}

    record RevokeRequest(UUID organizationId, UUID facilityId, long expectedRowVersion, String reason) {}
}
