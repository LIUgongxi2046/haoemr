package org.haoemr.model;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ModelEvaluationRecordRequestWire;
import org.haoemr.contracts.ModelEvaluationWire;
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
final class ModelEvaluationController {
    private final ClinicalCommandSecurity security;
    private final ModelEvaluationService evaluations;

    ModelEvaluationController(ClinicalCommandSecurity security, ModelEvaluationService evaluations) {
        this.security = security;
        this.evaluations = evaluations;
    }

    @GetMapping("/model-evaluations")
    ResponseEntity<List<ModelEvaluationWire>> list(
            HttpServletRequest request,
            @RequestParam("model_deployment_id") UUID modelDeploymentId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(evaluations.listEvaluations(identity, modelDeploymentId));
    }

    @PostMapping("/model-evaluations")
    ResponseEntity<ModelEvaluationWire> record(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ModelEvaluationRecordRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(evaluations.record(identity, idempotencyKey, command));
    }
}
