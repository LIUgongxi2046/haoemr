package org.haoemr.research;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ResearchProjectCreateRequestWire;
import org.haoemr.contracts.ResearchProjectDeactivateRequestWire;
import org.haoemr.contracts.ResearchProjectWire;
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
final class ResearchProjectController {
    private final ClinicalCommandSecurity security;
    private final ResearchProjectService projects;

    ResearchProjectController(ClinicalCommandSecurity security, ResearchProjectService projects) {
        this.security = security;
        this.projects = projects;
    }

    @GetMapping("/research-projects")
    ResponseEntity<List<ResearchProjectWire>> list(
            HttpServletRequest request,
            @RequestParam(value = "status", required = false) String status,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(projects.listProjects(identity, status));
    }

    @PostMapping("/research-projects")
    ResponseEntity<ResearchProjectWire> create(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ResearchProjectCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(projects.create(identity, idempotencyKey, command));
    }

    @PostMapping("/research-projects/{project_id}/deactivations")
    ResponseEntity<ResearchProjectWire> deactivate(
            HttpServletRequest request,
            @PathVariable("project_id") UUID projectId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ResearchProjectDeactivateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(projects.deactivate(identity, idempotencyKey, projectId, command));
    }
}
