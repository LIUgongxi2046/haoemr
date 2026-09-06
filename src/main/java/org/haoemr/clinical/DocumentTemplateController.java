package org.haoemr.clinical;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.clinical.DocumentTemplateService.DocumentTemplateCreateRequest;
import org.haoemr.clinical.DocumentTemplateService.DocumentTemplateDeactivateRequest;
import org.haoemr.clinical.DocumentTemplateService.DocumentTemplateVersionCreateRequest;
import org.haoemr.clinical.DocumentTemplateService.DocumentTemplateVersionPublishRequest;
import org.haoemr.clinical.DocumentTemplateService.DocumentTemplateWire;
import org.haoemr.security.ClinicalCommandSecurity;
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
@RequestMapping("/api/v1/admin/document-templates")
final class DocumentTemplateController {
    private final ClinicalCommandSecurity security;
    private final DocumentTemplateService templates;

    DocumentTemplateController(ClinicalCommandSecurity security, DocumentTemplateService templates) {
        this.security = security;
        this.templates = templates;
    }

    @GetMapping
    ResponseEntity<List<DocumentTemplateWire>> list(HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(templates.list(security.authenticate(request)));
    }

    @PostMapping
    ResponseEntity<DocumentTemplateWire> create(HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody DocumentTemplateCreateRequest body) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(templates.create(security.authenticate(request), key, body));
    }

    @PostMapping("/{templateId}/versions")
    ResponseEntity<DocumentTemplateWire> createVersion(HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String key, @PathVariable UUID templateId,
            @RequestBody DocumentTemplateVersionCreateRequest body) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(templates.createVersion(security.authenticate(request), key, templateId, body));
    }

    @PostMapping("/{templateId}/versions/{versionId}/publish")
    DocumentTemplateWire publish(HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String key, @PathVariable UUID templateId,
            @PathVariable UUID versionId, @RequestBody DocumentTemplateVersionPublishRequest body) {
        return templates.publish(security.authenticate(request), key, templateId, versionId, body);
    }

    @PostMapping("/{templateId}/deactivate")
    DocumentTemplateWire deactivate(HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String key, @PathVariable UUID templateId,
            @RequestBody DocumentTemplateDeactivateRequest body) {
        return templates.deactivate(security.authenticate(request), key, templateId, body);
    }
}
