package org.haoemr.mock;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.haoemr.contracts.MockInterfaceWire;
import org.haoemr.contracts.MockInvocationResultWire;
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
@RequestMapping("/api/v1")
final class MockInterfaceController {
    private final ClinicalCommandSecurity security;
    private final MockInterfaceService mocks;
    private final MockInterfaceExecutionService executions;

    MockInterfaceController(
            ClinicalCommandSecurity security, MockInterfaceService mocks,
            MockInterfaceExecutionService executions) {
        this.security = security;
        this.mocks = mocks;
        this.executions = executions;
    }

    @GetMapping("/mock-interfaces")
    ResponseEntity<List<MockInterfaceWire>> list(
            HttpServletRequest request,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(mocks.list());
    }

    @PostMapping("/mock-interfaces/{code}/invoke")
    ResponseEntity<MockInvocationResultWire> invoke(
            HttpServletRequest request,
            @PathVariable("code") String code,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> payload,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(executions.invoke(identity, idempotencyKey, code, payload == null ? Map.of() : payload));
    }

    @GetMapping("/mock-interfaces/runs")
    ResponseEntity<List<Map<String, Object>>> runs(
            HttpServletRequest request,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @org.springframework.web.bind.annotation.RequestParam(value = "workbench_id", required = false)
                    String workbenchId,
            @org.springframework.web.bind.annotation.RequestParam(value = "profile_key", required = false)
                    String profileKey) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(executions.listRuns(identity, workbenchId, profileKey));
    }

    @GetMapping("/mock-interfaces/runs/{run_id}")
    ResponseEntity<Map<String, Object>> run(
            HttpServletRequest request,
            @PathVariable("run_id") UUID runId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(executions.run(identity, runId));
    }

    @GetMapping("/mock-interfaces/runs/{run_id}/evidence")
    ResponseEntity<Map<String, Object>> evidence(
            HttpServletRequest request,
            @PathVariable("run_id") UUID runId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(executions.evidence(identity, runId));
    }
}
