package org.haoemr.knowledge;

import jakarta.servlet.http.HttpServletRequest;
import org.haoemr.contracts.KnowledgeGraphImportBatchWire;
import org.haoemr.contracts.KnowledgeGraphImportRequestWire;
import org.haoemr.security.ClinicalCommandSecurity;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
final class KnowledgeGraphImportController {
    private final ClinicalCommandSecurity security;
    private final KnowledgeGraphImportService graphImports;

    KnowledgeGraphImportController(ClinicalCommandSecurity security, KnowledgeGraphImportService graphImports) {
        this.security = security;
        this.graphImports = graphImports;
    }

    @PostMapping("/knowledge-graph/imports")
    ResponseEntity<KnowledgeGraphImportBatchWire> importGraph(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody KnowledgeGraphImportRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(graphImports.importGraph(identity, idempotencyKey, command));
    }
}
