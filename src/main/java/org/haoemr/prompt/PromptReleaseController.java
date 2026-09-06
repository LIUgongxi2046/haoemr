package org.haoemr.prompt;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.PromptReleasePublishRequestWire;
import org.haoemr.contracts.PromptReleaseRetireRequestWire;
import org.haoemr.contracts.PromptReleaseWire;
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
final class PromptReleaseController {
    private final ClinicalCommandSecurity security;
    private final PromptReleaseService prompts;

    PromptReleaseController(ClinicalCommandSecurity security, PromptReleaseService prompts) {
        this.security = security;
        this.prompts = prompts;
    }

    @GetMapping("/prompt-releases")
    ResponseEntity<List<PromptReleaseWire>> list(
            HttpServletRequest request,
            @RequestParam("prompt_code") String promptCode,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(prompts.listReleases(identity, promptCode));
    }

    @PostMapping("/prompt-releases")
    ResponseEntity<PromptReleaseWire> publish(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PromptReleasePublishRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(prompts.publish(identity, idempotencyKey, command));
    }

    @PostMapping("/prompt-releases/{prompt_release_id}/retirements")
    ResponseEntity<PromptReleaseWire> retire(
            HttpServletRequest request,
            @PathVariable("prompt_release_id") UUID promptReleaseId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PromptReleaseRetireRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(prompts.retire(identity, idempotencyKey, promptReleaseId, command));
    }
}
