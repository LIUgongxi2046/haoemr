package org.haoemr.configuration;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.haoemr.configuration.ConfigurationRuntimeService.RuntimeCommandRequest;
import org.haoemr.configuration.ConfigurationRuntimeService.RuntimeContext;
import org.haoemr.configuration.ConfigurationRuntimeService.RuntimeExecutionWire;
import org.haoemr.configuration.ConfigurationRuntimeService.RuntimeEvidenceWire;
import org.haoemr.configuration.ConfigurationRuntimeService.RuntimeTransitionRequest;
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
@RequestMapping("/api/v1/configuration-runtime")
final class ConfigurationRuntimeController {
    private final ClinicalCommandSecurity security;
    private final ConfigurationRuntimeService runtime;

    ConfigurationRuntimeController(ClinicalCommandSecurity security, ConfigurationRuntimeService runtime) {
        this.security = security;
        this.runtime = runtime;
    }

    @PostMapping("/workflows/{configKey}/instances")
    ResponseEntity<RuntimeExecutionWire> startWorkflow(
            HttpServletRequest request, @PathVariable String configKey,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader(value = "X-Patient-Context", required = false) UUID patientId,
            @RequestHeader(value = "X-Encounter-Context", required = false) UUID encounterId,
            @RequestBody RuntimeCommandRequest command) {
        RuntimeContext context = commandContext(organizationId, facilityId, patientId, encounterId, command);
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, encounterId);
        RuntimeExecutionWire execution = runtime.startWorkflow(identity, context, idempotencyKey, configKey, command);
        return ResponseEntity.created(URI.create("/api/v1/configuration-runtime/executions/" + execution.executionId()))
                .cacheControl(CacheControl.noStore()).body(execution);
    }

    @PostMapping("/workflows/instances/{executionId}/transitions")
    ResponseEntity<RuntimeExecutionWire> transitionWorkflow(
            HttpServletRequest request, @PathVariable UUID executionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader(value = "X-Patient-Context", required = false) UUID patientId,
            @RequestHeader(value = "X-Encounter-Context", required = false) UUID encounterId,
            @RequestBody RuntimeTransitionRequest command) {
        RuntimeContext context = new RuntimeContext(organizationId, facilityId, patientId, encounterId);
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(runtime.transitionWorkflow(identity, context, idempotencyKey, executionId, command));
    }

    @PostMapping("/forms/{configKey}/validate")
    ResponseEntity<RuntimeExecutionWire> validateForm(
            HttpServletRequest request, @PathVariable String configKey,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader(value = "X-Patient-Context", required = false) UUID patientId,
            @RequestHeader(value = "X-Encounter-Context", required = false) UUID encounterId,
            @RequestBody RuntimeCommandRequest command) {
        RuntimeContext context = commandContext(organizationId, facilityId, patientId, encounterId, command);
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(runtime.validateForm(identity, context, idempotencyKey, configKey, command));
    }

    @PostMapping("/rules/{configKey}/evaluate")
    ResponseEntity<RuntimeExecutionWire> evaluateRules(
            HttpServletRequest request, @PathVariable String configKey,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader(value = "X-Patient-Context", required = false) UUID patientId,
            @RequestHeader(value = "X-Encounter-Context", required = false) UUID encounterId,
            @RequestBody RuntimeCommandRequest command) {
        RuntimeContext context = commandContext(organizationId, facilityId, patientId, encounterId, command);
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(runtime.evaluateRules(identity, context, idempotencyKey, configKey, command));
    }

    @PostMapping("/scopes/{configKey}/authorize")
    ResponseEntity<RuntimeExecutionWire> authorizeScope(
            HttpServletRequest request, @PathVariable String configKey,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader(value = "X-Patient-Context", required = false) UUID patientId,
            @RequestHeader(value = "X-Encounter-Context", required = false) UUID encounterId,
            @RequestBody RuntimeCommandRequest command) {
        RuntimeContext context = commandContext(organizationId, facilityId, patientId, encounterId, command);
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(runtime.authorizeScope(identity, context, idempotencyKey, configKey, command));
    }

    @GetMapping("/executions")
    ResponseEntity<List<RuntimeExecutionWire>> executions(
            HttpServletRequest request,
            @RequestParam(value = "config_type", required = false) String configType,
            @RequestParam(value = "config_key", required = false) String configKey,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader(value = "X-Patient-Context", required = false) UUID patientId,
            @RequestHeader(value = "X-Encounter-Context", required = false) UUID encounterId) {
        RuntimeContext context = new RuntimeContext(organizationId, facilityId, patientId, encounterId);
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(runtime.listExecutions(identity, context, configType, configKey));
    }

    @GetMapping("/executions/{executionId}")
    ResponseEntity<RuntimeExecutionWire> execution(
            HttpServletRequest request, @PathVariable UUID executionId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader(value = "X-Patient-Context", required = false) UUID patientId,
            @RequestHeader(value = "X-Encounter-Context", required = false) UUID encounterId) {
        RuntimeContext context = new RuntimeContext(organizationId, facilityId, patientId, encounterId);
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(runtime.getExecution(identity, context, executionId));
    }

    @GetMapping("/executions/{executionId}/evidence")
    ResponseEntity<List<RuntimeEvidenceWire>> evidence(
            HttpServletRequest request, @PathVariable UUID executionId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader(value = "X-Patient-Context", required = false) UUID patientId,
            @RequestHeader(value = "X-Encounter-Context", required = false) UUID encounterId) {
        RuntimeContext context = new RuntimeContext(organizationId, facilityId, patientId, encounterId);
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(runtime.evidence(identity, context, executionId));
    }

    private RuntimeContext commandContext(
            UUID organizationId, UUID facilityId, UUID patientId, UUID encounterId,
            RuntimeCommandRequest command) {
        if (command != null && command.subjectId() != null) {
            String type = command.subjectType() == null ? "" : command.subjectType().trim().toUpperCase();
            if (patientId == null && encounterId == null) {
                throw new ConfigurationException(
                        "CONFIG_RUNTIME_CONTEXT_REQUIRED", 403, "带业务主体的配置运行必须绑定患者或就诊上下文");
            }
            if ("PATIENT".equals(type) && !command.subjectId().equals(patientId)) {
                throw new ConfigurationException(
                        "CONFIG_RUNTIME_CONTEXT_MISMATCH", 403, "配置运行主体与患者上下文不一致");
            }
            if ("ENCOUNTER".equals(type) && !command.subjectId().equals(encounterId)) {
                throw new ConfigurationException(
                        "CONFIG_RUNTIME_CONTEXT_MISMATCH", 403, "配置运行主体与就诊上下文不一致");
            }
        }
        return new RuntimeContext(organizationId, facilityId, patientId, encounterId);
    }
}
