package org.haoemr.agent;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ToolRegistryDeactivateRequestWire;
import org.haoemr.contracts.ToolRegistryRegisterRequestWire;
import org.haoemr.contracts.ToolRegistryVersionRequestWire;
import org.haoemr.contracts.ToolRegistryWire;
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
final class ToolRegistryController {
    private final ClinicalCommandSecurity security;
    private final ToolRegistryService tools;

    ToolRegistryController(ClinicalCommandSecurity security, ToolRegistryService tools) {
        this.security = security;
        this.tools = tools;
    }

    @GetMapping("/tool-registry")
    ResponseEntity<List<ToolRegistryWire>> list(
            HttpServletRequest request,
            @RequestParam(value = "status", required = false) String status,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(tools.listTools(identity, status));
    }

    @PostMapping("/tool-registry")
    ResponseEntity<ToolRegistryWire> register(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ToolRegistryRegisterRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(tools.register(identity, idempotencyKey, command));
    }

    @PostMapping("/tool-registry/{tool_registry_id}/deactivations")
    ResponseEntity<ToolRegistryWire> deactivate(
            HttpServletRequest request,
            @PathVariable("tool_registry_id") UUID toolRegistryId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ToolRegistryDeactivateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(tools.deactivate(identity, idempotencyKey, toolRegistryId, command));
    }

    @PostMapping("/tool-registry/{tool_registry_id}/versions")
    ResponseEntity<ToolRegistryWire> publishVersion(
            HttpServletRequest request,
            @PathVariable("tool_registry_id") UUID toolRegistryId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ToolRegistryVersionRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(tools.publishVersion(identity, idempotencyKey, toolRegistryId, command));
    }
}
