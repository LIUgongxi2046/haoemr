package org.haoemr.model;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.haoemr.contracts.ModelDeploymentDeactivateRequestWire;
import org.haoemr.contracts.ModelDeploymentConnectionTestRequestWire;
import org.haoemr.contracts.ModelDeploymentPublishRequestWire;
import org.haoemr.contracts.ModelDeploymentRegisterRequestWire;
import org.haoemr.contracts.ModelDeploymentUpdateRequestWire;
import org.haoemr.contracts.ModelDeploymentWire;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
final class ModelDeploymentController {
    private final ClinicalCommandSecurity security;
    private final ModelDeploymentService models;

    ModelDeploymentController(ClinicalCommandSecurity security, ModelDeploymentService models) {
        this.security = security;
        this.models = models;
    }

    @GetMapping("/model-deployments")
    ResponseEntity<List<ModelDeploymentWire>> list(
            HttpServletRequest request,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorizeForPurposes(request, organizationId, facilityId, null, null,
                Set.of("AI_PLATFORM_ADMIN", "AI_ASSISTANT_MODEL_SELECTION", "AI_CENTER_OVERVIEW"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(models.list(identity));
    }

    @PostMapping("/model-deployments")
    ResponseEntity<ModelDeploymentWire> register(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ModelDeploymentRegisterRequestWire command) {
        ClinicalIdentity identity = security.authorizeForPurposes(
                request, command.organizationId(), command.facilityId(), null, null, Set.of("AI_PLATFORM_ADMIN"));
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(models.register(identity, idempotencyKey, command));
    }

    @PostMapping("/model-deployments/{model_deployment_id}/deactivations")
    ResponseEntity<ModelDeploymentWire> deactivate(
            HttpServletRequest request,
            @PathVariable("model_deployment_id") UUID deploymentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ModelDeploymentDeactivateRequestWire command) {
        ClinicalIdentity identity = security.authorizeForPurposes(
                request, command.organizationId(), command.facilityId(), null, null, Set.of("AI_PLATFORM_ADMIN"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(models.deactivate(identity, idempotencyKey, deploymentId, command));
    }

    @PutMapping("/model-deployments/{model_deployment_id}")
    ResponseEntity<ModelDeploymentWire> update(
            HttpServletRequest request,
            @PathVariable("model_deployment_id") UUID deploymentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ModelDeploymentUpdateRequestWire command) {
        ClinicalIdentity identity = security.authorizeForPurposes(
                request, command.organizationId(), command.facilityId(), null, null, Set.of("AI_PLATFORM_ADMIN"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(models.update(identity, idempotencyKey, deploymentId, command));
    }

    @PostMapping("/model-deployments/{model_deployment_id}/purges")
    ResponseEntity<Void> purge(
            HttpServletRequest request,
            @PathVariable("model_deployment_id") UUID deploymentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ModelDeploymentDeactivateRequestWire command) {
        ClinicalIdentity identity = security.authorizeForPurposes(
                request, command.organizationId(), command.facilityId(), null, null, Set.of("AI_PLATFORM_ADMIN"));
        models.purge(identity, idempotencyKey, deploymentId, command);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/model-deployments/{model_deployment_id}/publications")
    ResponseEntity<ModelDeploymentWire> publish(
            HttpServletRequest request,
            @PathVariable("model_deployment_id") UUID deploymentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ModelDeploymentPublishRequestWire command) {
        ClinicalIdentity identity = security.authorizeForPurposes(
                request, command.organizationId(), command.facilityId(), null, null, Set.of("AI_PLATFORM_ADMIN"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(models.publish(identity, idempotencyKey, deploymentId, command));
    }

    @PostMapping("/model-deployments/{model_deployment_id}/connection-tests")
    ResponseEntity<ModelDeploymentWire> testConnection(
            HttpServletRequest request,
            @PathVariable("model_deployment_id") UUID deploymentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ModelDeploymentConnectionTestRequestWire command) {
        ClinicalIdentity identity = security.authorizeForPurposes(
                request, command.organizationId(), command.facilityId(), null, null, Set.of("AI_PLATFORM_ADMIN"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(models.testConnection(identity, idempotencyKey, deploymentId, command));
    }
}
