package org.haoemr.audit;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.haoemr.contracts.AuditEventWire;
import org.haoemr.security.ClinicalCommandSecurity;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
final class AuditEventController {
    private final ClinicalCommandSecurity security;
    private final AuditEventService events;

    AuditEventController(ClinicalCommandSecurity security, AuditEventService events) {
        this.security = security;
        this.events = events;
    }

    @GetMapping("/audit-events")
    ResponseEntity<List<AuditEventWire>> list(
            HttpServletRequest request,
            @RequestParam(value = "action_code", required = false) String actionCode,
            @RequestParam(value = "resource_type", required = false) String resourceType,
            @RequestParam(value = "resource_id", required = false) UUID resourceId,
            @RequestParam(value = "from", required = false) Instant from,
            @RequestParam(value = "to", required = false) Instant to,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorizeForPurposes(
                request, organizationId, facilityId, null, null, Set.of("ADMIN_AUDIT"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(events.list(identity, actionCode, resourceType, resourceId, from, to));
    }
}
