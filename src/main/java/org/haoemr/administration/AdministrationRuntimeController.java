package org.haoemr.administration;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.administration.AdministrationRuntimeService.CancelJobRequest;
import org.haoemr.administration.AdministrationRuntimeService.GovernanceFindingWire;
import org.haoemr.administration.AdministrationRuntimeService.JobRunWire;
import org.haoemr.administration.AdministrationRuntimeService.ResolveFindingRequest;
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
@RequestMapping("/api/v1/admin/runtime")
final class AdministrationRuntimeController {
    private final ClinicalCommandSecurity security;
    private final AdministrationRuntimeService runtime;

    AdministrationRuntimeController(ClinicalCommandSecurity security, AdministrationRuntimeService runtime) {
        this.security = security;
        this.runtime = runtime;
    }

    @GetMapping("/job-runs")
    ResponseEntity<List<JobRunWire>> listRuns(
            HttpServletRequest request,
            @RequestParam(value = "config_id", required = false) UUID configId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(runtime.listRuns(security.authenticate(request), configId));
    }

    @PostMapping("/jobs/{configId}/runs")
    ResponseEntity<JobRunWire> start(
            HttpServletRequest request,
            @PathVariable UUID configId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        ClinicalIdentity identity = security.authenticate(request);
        return ResponseEntity.status(202).cacheControl(CacheControl.noStore())
                .body(runtime.start(identity, configId, idempotencyKey));
    }

    @PostMapping("/job-runs/{runId}/cancel")
    ResponseEntity<JobRunWire> cancel(
            HttpServletRequest request,
            @PathVariable UUID runId,
            @RequestBody CancelJobRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(runtime.cancel(security.authenticate(request), runId, body.expectedVersion()));
    }

    @PostMapping("/job-runs/{runId}/retry")
    ResponseEntity<JobRunWire> retry(
            HttpServletRequest request,
            @PathVariable UUID runId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ResponseEntity.status(202).cacheControl(CacheControl.noStore())
                .body(runtime.retry(security.authenticate(request), runId, idempotencyKey));
    }

    @GetMapping("/job-runs/{runId}/findings")
    ResponseEntity<List<GovernanceFindingWire>> findings(
            HttpServletRequest request,
            @PathVariable UUID runId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(runtime.listFindings(security.authenticate(request), runId));
    }

    @PostMapping("/findings/{findingId}/resolve")
    ResponseEntity<GovernanceFindingWire> resolve(
            HttpServletRequest request,
            @PathVariable UUID findingId,
            @RequestBody ResolveFindingRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(runtime.resolve(security.authenticate(request), findingId,
                        body.expectedVersion(), body.resolution()));
    }
}
