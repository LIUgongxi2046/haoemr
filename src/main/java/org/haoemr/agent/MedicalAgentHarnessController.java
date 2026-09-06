package org.haoemr.agent;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.haoemr.agent.MedicalAgentHarnessService.AgentFamilyView;
import org.haoemr.agent.MedicalAgentHarnessService.CreateRunCommand;
import org.haoemr.agent.MedicalAgentHarnessService.OperationsRunView;
import org.haoemr.agent.MedicalAgentHarnessService.OperationsToolInvocationView;
import org.haoemr.agent.MedicalAgentHarnessService.RunContext;
import org.haoemr.agent.MedicalAgentHarnessService.RunView;
import org.haoemr.agent.MedicalAgentHarnessService.RetryRunCommand;
import org.haoemr.contracts.MedicalAgentChildRunWire;
import org.haoemr.contracts.MedicalAgentFamilyWire;
import org.haoemr.contracts.MedicalAgentReleaseWire;
import org.haoemr.contracts.MedicalAgentRoutingWire;
import org.haoemr.contracts.MedicalAgentRunCreateRequestWire;
import org.haoemr.contracts.MedicalAgentRunCancelRequestWire;
import org.haoemr.contracts.MedicalAgentRunEventWire;
import org.haoemr.contracts.MedicalAgentRunRetryRequestWire;
import org.haoemr.contracts.MedicalAgentRunWire;
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
@RequestMapping("/api/v1/medical-agents")
final class MedicalAgentHarnessController {

    private final ClinicalCommandSecurity security;
    private final MedicalAgentHarnessService harness;
    private final AgentOrchestrator orchestrator;

    MedicalAgentHarnessController(ClinicalCommandSecurity security, MedicalAgentHarnessService harness,
            AgentOrchestrator orchestrator) {
        this.security = security;
        this.harness = harness;
        this.orchestrator = orchestrator;
    }

    @GetMapping("/routing")
    ResponseEntity<MedicalAgentRoutingWire> routing(
            HttpServletRequest request,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestParam("source_route") String sourceRoute) {
        security.authorizeForPurposes(request, organizationId, facilityId, null, null,
                Set.of("MEDICAL_AGENT_CATALOG"));
        AgentOrchestrator.Routing routing = orchestrator.resolve(sourceRoute);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new MedicalAgentRoutingWire(routing.mainAgentCode(), routing.stageCode()));
    }

    @GetMapping("/catalog")
    ResponseEntity<List<MedicalAgentFamilyWire>> catalog(
            HttpServletRequest request,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorizeForPurposes(request, organizationId, facilityId, null, null,
                Set.of("MEDICAL_AGENT_CATALOG"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(harness.catalog(identity.tenantId()).stream().map(MedicalAgentHarnessController::wire).toList());
    }

    @PostMapping("/runs")
    ResponseEntity<MedicalAgentRunWire> create(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Context-Lease-Id") UUID leaseHeader,
            @RequestBody MedicalAgentRunCreateRequestWire requestWire) {
        if (!leaseHeader.equals(requestWire.contextLeaseId())) {
            throw new AgentRunException("CONTEXT_NOT_PERMITTED", 403,
                    "The requested medical-agent context is not permitted");
        }
        CreateRunCommand command = new CreateRunCommand(requestWire.organizationId(), requestWire.facilityId(),
                requestWire.patientId(), requestWire.encounterId(), requestWire.contextLeaseId(),
                requestWire.mainAgentCode(), requestWire.stageCode(), requestWire.targetType(),
                requestWire.targetId(), requestWire.objective(), requestWire.modelDeploymentId(),
                requestWire.authorizationLevel() == null ? "STANDARD" : requestWire.authorizationLevel().name(),
                requestWire.contextScopes() == null || requestWire.contextScopes().isEmpty()
                        ? List.of("RECORDS", "ORDERS", "RESULTS", "TASKS", "CONFIGURATION")
                        : requestWire.contextScopes().stream().map(Enum::name).toList(),
                requestWire.sourceRoute());
        ClinicalIdentity identity = security.authorizeForPurposes(request, command.organizationId(), command.facilityId(),
                command.patientId(), command.encounterId(), Set.of("MEDICAL_AGENT_COLLABORATION"));
        RunView run = harness.createAndRun(identity, idempotencyKey, command);
        return ResponseEntity.accepted().location(URI.create("/api/v1/medical-agents/runs/" + run.runId()))
                .cacheControl(CacheControl.noStore()).body(wire(run));
    }

    @GetMapping("/runs")
    ResponseEntity<List<MedicalAgentRunWire>> list(
            HttpServletRequest request,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestParam("patient_id") UUID patientId,
            @RequestParam("encounter_id") UUID encounterId) {
        ClinicalIdentity identity = security.authorizeForPurposes(
                request, organizationId, facilityId, patientId, encounterId,
                Set.of("MEDICAL_AGENT_COLLABORATION"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(harness.listRuns(identity.tenantId(), encounterId).stream()
                        .map(MedicalAgentHarnessController::wire).toList());
    }

    @GetMapping("/operations/runs")
    ResponseEntity<List<OperationsRunView>> operationsRuns(
            HttpServletRequest request,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        ClinicalIdentity identity = security.authorizeForPurposes(
                request, organizationId, facilityId, null, null, Set.of("AI_PLATFORM_ADMIN"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(harness.operationsRuns(identity.tenantId(), facilityId, limit));
    }

    @GetMapping("/operations/runs/{runId}/tool-invocations")
    ResponseEntity<List<OperationsToolInvocationView>> operationsToolInvocations(
            HttpServletRequest request,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @PathVariable UUID runId) {
        ClinicalIdentity identity = security.authorizeForPurposes(
                request, organizationId, facilityId, null, null, Set.of("AI_PLATFORM_ADMIN"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(harness.operationsToolInvocations(identity.tenantId(), facilityId, runId));
    }

    @GetMapping("/runs/{runId}")
    ResponseEntity<MedicalAgentRunWire> get(HttpServletRequest request, @PathVariable UUID runId) {
        ClinicalIdentity preliminary = security.authenticate(request);
        RunContext context = harness.context(preliminary.tenantId(), runId);
        ClinicalIdentity identity = security.authorizeForPurposes(
                request, context.organizationId(), context.facilityId(), context.patientId(), context.encounterId(),
                Set.of("MEDICAL_AGENT_COLLABORATION"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(wire(harness.run(identity.tenantId(), runId)));
    }

    @PostMapping("/runs/{runId}/cancellations")
    ResponseEntity<MedicalAgentRunWire> cancel(
            HttpServletRequest request,
            @PathVariable UUID runId,
            @RequestBody MedicalAgentRunCancelRequestWire requestWire) {
        ClinicalIdentity preliminary = security.authenticate(request);
        RunContext context = harness.context(preliminary.tenantId(), runId);
        ClinicalIdentity identity = security.authorizeForPurposes(
                request, context.organizationId(), context.facilityId(), context.patientId(), context.encounterId(),
                Set.of("MEDICAL_AGENT_COLLABORATION"));
        RunView run = harness.requestCancellation(
                identity, runId, requestWire.expectedRowVersion(), requestWire.reason());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(wire(run));
    }

    @PostMapping("/runs/{runId}/retries")
    ResponseEntity<MedicalAgentRunWire> retry(
            HttpServletRequest request,
            @PathVariable UUID runId,
            @RequestHeader("X-Context-Lease-Id") UUID leaseHeader,
            @RequestBody MedicalAgentRunRetryRequestWire requestWire) {
        if (!leaseHeader.equals(requestWire.contextLeaseId())) {
            throw new AgentRunException("CONTEXT_NOT_PERMITTED", 403,
                    "The requested medical-agent context is not permitted");
        }
        ClinicalIdentity preliminary = security.authenticate(request);
        RunContext context = harness.context(preliminary.tenantId(), runId);
        if (!context.organizationId().equals(requestWire.organizationId())
                || !context.facilityId().equals(requestWire.facilityId())) {
            throw new AgentRunException("CONTEXT_NOT_PERMITTED", 403,
                    "The requested medical-agent context is not permitted");
        }
        ClinicalIdentity identity = security.authorizeForPurposes(
                request, context.organizationId(), context.facilityId(), context.patientId(), context.encounterId(),
                Set.of("MEDICAL_AGENT_COLLABORATION"));
        RunView run = harness.retry(identity, runId, new RetryRunCommand(
                requestWire.organizationId(), requestWire.facilityId(), requestWire.contextLeaseId(),
                requestWire.expectedRowVersion()));
        return ResponseEntity.accepted().location(URI.create("/api/v1/medical-agents/runs/" + run.runId()))
                .cacheControl(CacheControl.noStore()).body(wire(run));
    }

    private static MedicalAgentFamilyWire wire(AgentFamilyView family) {
        return new MedicalAgentFamilyWire(wire(family.mainAgent()),
                family.childAgents().stream().map(MedicalAgentHarnessController::wire).toList());
    }

    private static MedicalAgentReleaseWire wire(MedicalAgentHarnessService.AgentReleaseView release) {
        return new MedicalAgentReleaseWire(release.agentCode(), release.releaseVersion(), release.displayName(),
                MedicalAgentReleaseWire.AgentLevelValue.valueOf(release.agentLevel()), release.parentAgentCode(),
                release.stageCode(), release.description(), release.doctorFacingSummary(), release.category(),
                release.displayRole(), release.currentAction(), release.contributionLabel(), release.questionExamples(),
                release.outputSchema(), MedicalAgentReleaseWire.AutonomyLevelValue.valueOf(release.autonomyLevel()),
                release.maxSteps(), release.maxToolCalls(), release.maxDurationSeconds(), release.usageCount());
    }

    private static MedicalAgentRunWire wire(RunView run) {
        return new MedicalAgentRunWire(run.runId(), run.contextLeaseId(), run.rootAgentCode(),
                run.rootAgentVersion(), run.compositionCode(), run.compositionVersion(), run.requestedStage(),
                run.patientId(), run.encounterId(), run.targetType(), run.targetId(), run.objective(),
                MedicalAgentRunWire.StateValue.valueOf(run.state()), run.sequence(), run.output(),
                run.createdAt().toInstant(), run.completedAt() == null ? null : run.completedAt().toInstant(),
                run.rowVersion(), run.attempt(), run.maxAttempts(),
                run.cancelRequestedAt() == null ? null : run.cancelRequestedAt().toInstant(), run.failureCode(),
                run.childRuns().stream().map(child -> new MedicalAgentChildRunWire(
                        child.childRunId(), child.childAgentCode(), child.displayName(), child.displayRole(),
                        child.currentAction(), child.contributionLabel(),
                        MedicalAgentChildRunWire.StateValue.valueOf(child.state()), child.critical(),
                        child.contribution(), child.sourceReferences(),
                        child.startedAt() == null ? null : child.startedAt().toInstant(),
                        child.completedAt() == null ? null : child.completedAt().toInstant())).toList(),
                run.events().stream().map(event -> new MedicalAgentRunEventWire(event.sequence(), event.eventType(),
                        event.childRunId(), event.payload(), event.occurredAt().toInstant())).toList());
    }
}
