package org.haoemr.agent;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.AIProposalDecisionRequestWire;
import org.haoemr.contracts.AIProposalWire;
import org.haoemr.contracts.AIRunCreateRequestWire;
import org.haoemr.contracts.AIRunSnapshotWire;
import org.haoemr.security.ClinicalCommandSecurity;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai")
final class AgentRunController {

    private final ClinicalCommandSecurity security;
    private final AgentRunService runs;

    AgentRunController(ClinicalCommandSecurity security, AgentRunService runs) {
        this.security = security;
        this.runs = runs;
    }

    @GetMapping("/runs")
    ResponseEntity<List<AIRunSnapshotWire>> list(
            HttpServletRequest request,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(runs.listRuns(identity.tenantId()));
    }

    @PostMapping("/runs")
    ResponseEntity<AIRunSnapshotWire> create(
            HttpServletRequest httpRequest,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Context-Lease-Id") UUID leaseHeader,
            @RequestBody AIRunCreateRequestWire request) {
        if (!leaseHeader.equals(request.contextLeaseId())) {
            throw new AgentRunException("CONTEXT_NOT_PERMITTED", 403, "The requested AI context is not permitted");
        }
        ClinicalIdentity identity = security.authorize(
                httpRequest, request.organizationId(), request.facilityId(), request.patientId(), request.encounterId());
        AIRunSnapshotWire snapshot = runs.createAndRun(
                identity, idempotencyKey, request.contextLeaseId(), request.useCaseCode(), request.documentId(),
                request.documentVersionId(), request.patientId(), request.encounterId());
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/ai/runs/" + snapshot.runId()))
                .cacheControl(CacheControl.noStore()).body(snapshot);
    }

    @GetMapping("/runs/{runId}")
    ResponseEntity<AIRunSnapshotWire> snapshot(HttpServletRequest request, @PathVariable UUID runId) {
        ClinicalIdentity preliminary = security.authenticate(request);
        AgentRunService.RunContext context = runs.context(preliminary.tenantId(), runId);
        ClinicalIdentity identity = security.authorize(
                request, context.organizationId(), context.facilityId(), context.patientId(), context.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(runs.snapshot(identity.tenantId(), runId));
    }

    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<String> events(
            HttpServletRequest request,
            @PathVariable UUID runId,
            @RequestHeader(value = "Last-Event-ID", required = false, defaultValue = "0") long afterSequence) {
        ClinicalIdentity preliminary = security.authenticate(request);
        AgentRunService.RunContext context = runs.context(preliminary.tenantId(), runId);
        ClinicalIdentity identity = security.authorize(
                request, context.organizationId(), context.facilityId(), context.patientId(), context.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(runs.events(identity.tenantId(), runId, afterSequence));
    }

    @PostMapping("/proposals/{proposalId}/decisions")
    ResponseEntity<AIProposalWire> decide(
            HttpServletRequest request,
            @PathVariable UUID proposalId,
            @RequestBody AIProposalDecisionRequestWire decision) {
        ClinicalIdentity preliminary = security.authenticate(request);
        AgentRunService.RunContext actual = runs.proposalContext(preliminary.tenantId(), proposalId);
        if (!actual.organizationId().equals(decision.organizationId())
                || !actual.facilityId().equals(decision.facilityId())
                || !actual.patientId().equals(decision.patientId())
                || !actual.encounterId().equals(decision.encounterId())) {
            throw new AgentRunException("CONTEXT_NOT_PERMITTED", 403, "The requested AI context is not permitted");
        }
        ClinicalIdentity identity = security.authorize(
                request, actual.organizationId(), actual.facilityId(), actual.patientId(), actual.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(runs.decide(
                identity, proposalId, decision.expectedRowVersion(), decision.decision().name(), decision.reason()));
    }
}
