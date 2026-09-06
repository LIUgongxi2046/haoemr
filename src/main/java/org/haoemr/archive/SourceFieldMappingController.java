package org.haoemr.archive;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.SourceFieldMappingDeactivateRequestWire;
import org.haoemr.contracts.SourceFieldMappingRegisterRequestWire;
import org.haoemr.contracts.SourceFieldMappingWire;
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
final class SourceFieldMappingController {
    private final ClinicalCommandSecurity security;
    private final SourceFieldMappingService mappings;

    SourceFieldMappingController(ClinicalCommandSecurity security, SourceFieldMappingService mappings) {
        this.security = security;
        this.mappings = mappings;
    }

    @GetMapping("/source-field-mappings")
    ResponseEntity<List<SourceFieldMappingWire>> list(
            HttpServletRequest request,
            @RequestParam("source_system_id") UUID sourceSystemId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(mappings.list(identity, sourceSystemId));
    }

    @PostMapping("/source-field-mappings")
    ResponseEntity<SourceFieldMappingWire> register(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody SourceFieldMappingRegisterRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(mappings.register(identity, idempotencyKey, command));
    }

    @PostMapping("/source-field-mappings/{mapping_id}/deactivations")
    ResponseEntity<SourceFieldMappingWire> deactivate(
            HttpServletRequest request,
            @PathVariable("mapping_id") UUID mappingId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody SourceFieldMappingDeactivateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(mappings.deactivate(identity, idempotencyKey, mappingId, command));
    }
}
