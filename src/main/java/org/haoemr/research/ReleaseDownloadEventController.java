package org.haoemr.research;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ReleaseDownloadEventCreateRequestWire;
import org.haoemr.contracts.ReleaseDownloadEventWire;
import org.haoemr.contracts.ReleaseDownloadValidCountWire;
import org.haoemr.security.ClinicalCommandSecurity;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
final class ReleaseDownloadEventController {
    private final ClinicalCommandSecurity security;
    private final ReleaseDownloadEventService downloads;

    ReleaseDownloadEventController(ClinicalCommandSecurity security, ReleaseDownloadEventService downloads) {
        this.security = security;
        this.downloads = downloads;
    }

    @GetMapping("/release-download-events")
    ResponseEntity<List<ReleaseDownloadEventWire>> list(
            HttpServletRequest request,
            @RequestParam(value = "channel", required = false) String channel,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(downloads.list(identity, channel));
    }

    @GetMapping("/release-download-events/valid-count")
    ResponseEntity<ReleaseDownloadValidCountWire> validCount(
            HttpServletRequest request,
            @RequestParam(value = "channel", required = false) String channel,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(downloads.validCount(identity, channel));
    }

    @PostMapping("/release-download-events")
    ResponseEntity<ReleaseDownloadEventWire> record(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ReleaseDownloadEventCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(downloads.record(identity, idempotencyKey, command));
    }
}
