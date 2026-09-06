package org.haoemr.knowledge;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.KnowledgeImportBatchWire;
import org.haoemr.contracts.KnowledgeImportRequestWire;
import org.haoemr.contracts.KnowledgeSourceRegisterRequestWire;
import org.haoemr.contracts.KnowledgeSourceWire;
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
final class KnowledgeSourceController {
    private final ClinicalCommandSecurity security;
    private final KnowledgeSourceService sources;

    KnowledgeSourceController(ClinicalCommandSecurity security, KnowledgeSourceService sources) {
        this.security = security;
        this.sources = sources;
    }

    @GetMapping("/knowledge-sources")
    ResponseEntity<List<KnowledgeSourceWire>> list(
            HttpServletRequest request,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sources.listSources(identity));
    }

    @PostMapping("/knowledge-sources")
    ResponseEntity<KnowledgeSourceWire> register(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody KnowledgeSourceRegisterRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(sources.register(identity, idempotencyKey, command));
    }

    @PostMapping("/knowledge-sources/{source_id}/imports")
    ResponseEntity<KnowledgeImportBatchWire> importSource(
            HttpServletRequest request,
            @PathVariable("source_id") UUID sourceId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody KnowledgeImportRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(202).cacheControl(CacheControl.noStore())
                .body(sources.importSource(identity, idempotencyKey, sourceId, command));
    }

    @GetMapping("/knowledge-sources/{source_id}/imports")
    ResponseEntity<List<KnowledgeImportBatchWire>> listImports(
            HttpServletRequest request,
            @PathVariable("source_id") UUID sourceId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(sources.listImports(identity, sourceId));
    }
}
