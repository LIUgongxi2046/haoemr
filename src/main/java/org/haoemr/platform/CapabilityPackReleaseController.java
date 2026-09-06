package org.haoemr.platform;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.CapabilityPackReleaseCreateRequestWire;
import org.haoemr.contracts.CapabilityPackReleaseRollbackRequestWire;
import org.haoemr.contracts.CapabilityPackReleaseTransitionRequestWire;
import org.haoemr.contracts.CapabilityPackReleaseWire;
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
final class CapabilityPackReleaseController {
    private final ClinicalCommandSecurity security;
    private final CapabilityPackReleaseService releases;

    CapabilityPackReleaseController(ClinicalCommandSecurity security, CapabilityPackReleaseService releases) {
        this.security = security;
        this.releases = releases;
    }

    @GetMapping("/capability-pack-releases")
    ResponseEntity<List<CapabilityPackReleaseWire>> list(
            HttpServletRequest request,
            @RequestParam(value = "capability_pack_id", required = false) UUID capabilityPackId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(releases.listReleases(identity, capabilityPackId));
    }

    @PostMapping("/capability-pack-releases")
    ResponseEntity<CapabilityPackReleaseWire> create(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CapabilityPackReleaseCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(releases.create(identity, idempotencyKey, command));
    }

    @PostMapping("/capability-pack-releases/{release_id}/start-canary")
    ResponseEntity<CapabilityPackReleaseWire> startCanary(
            HttpServletRequest request,
            @PathVariable("release_id") UUID releaseId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CapabilityPackReleaseTransitionRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(releases.startCanary(identity, idempotencyKey, releaseId, command));
    }

    @PostMapping("/capability-pack-releases/{release_id}/promote")
    ResponseEntity<CapabilityPackReleaseWire> promote(
            HttpServletRequest request,
            @PathVariable("release_id") UUID releaseId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CapabilityPackReleaseTransitionRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(releases.promote(identity, idempotencyKey, releaseId, command));
    }

    @PostMapping("/capability-pack-releases/{release_id}/retire")
    ResponseEntity<CapabilityPackReleaseWire> retire(
            HttpServletRequest request,
            @PathVariable("release_id") UUID releaseId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CapabilityPackReleaseTransitionRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(releases.retire(identity, idempotencyKey, releaseId, command));
    }

    @PostMapping("/capability-pack-releases/{release_id}/rollback")
    ResponseEntity<CapabilityPackReleaseWire> rollback(
            HttpServletRequest request,
            @PathVariable("release_id") UUID releaseId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CapabilityPackReleaseRollbackRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(releases.rollback(identity, idempotencyKey, releaseId, command));
    }
}
