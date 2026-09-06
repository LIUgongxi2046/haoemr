package org.haoemr.knowledge;

import jakarta.servlet.http.HttpServletRequest;
import org.haoemr.contracts.KnowledgeFeedbackCreateRequestWire;
import org.haoemr.contracts.KnowledgeSearchRequestWire;
import org.haoemr.contracts.KnowledgeSearchResultWire;
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
final class KnowledgeSearchController {
    private final ClinicalCommandSecurity security;
    private final KnowledgeSearchService search;

    KnowledgeSearchController(ClinicalCommandSecurity security, KnowledgeSearchService search) {
        this.security = security;
        this.search = search;
    }

    @PostMapping("/knowledge-search")
    ResponseEntity<KnowledgeSearchResultWire> search(
            HttpServletRequest request,
            @RequestBody KnowledgeSearchRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(search.search(identity, command));
    }

    @PostMapping("/knowledge-feedback")
    ResponseEntity<Void> feedback(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody KnowledgeFeedbackCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        search.createFeedback(identity, idempotencyKey, command);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).build();
    }
}
