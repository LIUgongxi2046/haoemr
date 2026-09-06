package org.haoemr.agent;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.SkillRegistryDeactivateRequestWire;
import org.haoemr.contracts.SkillRegistryRegisterRequestWire;
import org.haoemr.contracts.SkillRegistryVersionRequestWire;
import org.haoemr.contracts.SkillRegistryWire;
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
final class SkillRegistryController {
    private final ClinicalCommandSecurity security;
    private final SkillRegistryService skills;

    SkillRegistryController(ClinicalCommandSecurity security, SkillRegistryService skills) {
        this.security = security;
        this.skills = skills;
    }

    @GetMapping("/skill-registry")
    ResponseEntity<List<SkillRegistryWire>> list(
            HttpServletRequest request,
            @RequestParam(value = "status", required = false) String status,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(skills.listSkills(identity, status));
    }

    @PostMapping("/skill-registry")
    ResponseEntity<SkillRegistryWire> register(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody SkillRegistryRegisterRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(skills.register(identity, idempotencyKey, command));
    }

    @PostMapping("/skill-registry/{skill_registry_id}/deactivations")
    ResponseEntity<SkillRegistryWire> deactivate(
            HttpServletRequest request,
            @PathVariable("skill_registry_id") UUID skillRegistryId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody SkillRegistryDeactivateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(skills.deactivate(identity, idempotencyKey, skillRegistryId, command));
    }

    @PostMapping("/skill-registry/{skill_registry_id}/versions")
    ResponseEntity<SkillRegistryWire> publishVersion(
            HttpServletRequest request,
            @PathVariable("skill_registry_id") UUID skillRegistryId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody SkillRegistryVersionRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(skills.publishVersion(identity, idempotencyKey, skillRegistryId, command));
    }
}
