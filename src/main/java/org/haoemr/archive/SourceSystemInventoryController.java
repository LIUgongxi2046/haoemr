package org.haoemr.archive;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.SourceSystemInventoryRegisterRequestWire;
import org.haoemr.contracts.SourceSystemInventoryTransitionRequestWire;
import org.haoemr.contracts.SourceSystemInventoryWire;
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
final class SourceSystemInventoryController {
    private final ClinicalCommandSecurity security;
    private final SourceSystemInventoryService sources;

    SourceSystemInventoryController(ClinicalCommandSecurity security, SourceSystemInventoryService sources) {
        this.security = security;
        this.sources = sources;
    }

    @GetMapping("/source-systems")
    ResponseEntity<List<SourceSystemInventoryWire>> list(
            HttpServletRequest request,
            @RequestParam(value = "status", required = false) String status,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(sources.list(identity, status));
    }

    @PostMapping("/source-systems")
    ResponseEntity<SourceSystemInventoryWire> register(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody SourceSystemInventoryRegisterRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(sources.register(identity, idempotencyKey, command));
    }

    @PostMapping("/source-systems/{source_system_id}/configurations")
    ResponseEntity<SourceSystemInventoryWire> configure(
            HttpServletRequest request,
            @PathVariable("source_system_id") UUID sourceSystemId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody SourceSystemInventoryTransitionRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(sources.configure(identity, idempotencyKey, sourceSystemId, command));
    }

    @PostMapping("/source-systems/{source_system_id}/activations")
    ResponseEntity<SourceSystemInventoryWire> activate(
            HttpServletRequest request,
            @PathVariable("source_system_id") UUID sourceSystemId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody SourceSystemInventoryTransitionRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(sources.activate(identity, idempotencyKey, sourceSystemId, command));
    }

    @PostMapping("/source-systems/{source_system_id}/retirements")
    ResponseEntity<SourceSystemInventoryWire> retire(
            HttpServletRequest request,
            @PathVariable("source_system_id") UUID sourceSystemId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody SourceSystemInventoryTransitionRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(sources.retire(identity, idempotencyKey, sourceSystemId, command));
    }
}
