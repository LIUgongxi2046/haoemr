package org.haoemr.agent;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.AgentRegistryDeactivateRequestWire;
import org.haoemr.contracts.AgentRegistryRegisterRequestWire;
import org.haoemr.contracts.AgentRegistryVersionRequestWire;
import org.haoemr.contracts.AgentRegistryWire;
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
final class AgentRegistryController {
    private final ClinicalCommandSecurity security;
    private final AgentRegistryService agents;

    AgentRegistryController(ClinicalCommandSecurity security, AgentRegistryService agents) {
        this.security = security;
        this.agents = agents;
    }

    @GetMapping("/agent-registry")
    ResponseEntity<List<AgentRegistryWire>> list(
            HttpServletRequest request,
            @RequestParam(value = "status", required = false) String status,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(agents.listAgents(identity, status));
    }

    @PostMapping("/agent-registry")
    ResponseEntity<AgentRegistryWire> register(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody AgentRegistryRegisterRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(agents.register(identity, idempotencyKey, command));
    }

    @PostMapping("/agent-registry/{agent_registry_id}/deactivations")
    ResponseEntity<AgentRegistryWire> deactivate(
            HttpServletRequest request,
            @PathVariable("agent_registry_id") UUID agentRegistryId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody AgentRegistryDeactivateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(agents.deactivate(identity, idempotencyKey, agentRegistryId, command));
    }

    @PostMapping("/agent-registry/{agent_registry_id}/versions")
    ResponseEntity<AgentRegistryWire> publishVersion(
            HttpServletRequest request,
            @PathVariable("agent_registry_id") UUID agentRegistryId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody AgentRegistryVersionRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(agents.publishVersion(identity, idempotencyKey, agentRegistryId, command));
    }
}
