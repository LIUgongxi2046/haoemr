package org.haoemr.quality;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.AdverseEventReportRequestWire;
import org.haoemr.contracts.AdverseEventReviewRequestWire;
import org.haoemr.contracts.AdverseEventWire;
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
final class AdverseEventController {
    private final ClinicalCommandSecurity security;
    private final AdverseEventService events;

    AdverseEventController(ClinicalCommandSecurity security, AdverseEventService events) {
        this.security = security;
        this.events = events;
    }

    @GetMapping("/adverse-events")
    ResponseEntity<List<AdverseEventWire>> list(
            HttpServletRequest request,
            @RequestParam("encounter_id") UUID encounterId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId,
            @RequestHeader("X-Encounter-Context") UUID encounterContextId) {
        if (!encounterId.equals(encounterContextId)) throw AdverseEventService.contextDenied();
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(events.listEvents(identity, organizationId, facilityId, patientId, encounterId));
    }

    @PostMapping("/adverse-events")
    ResponseEntity<AdverseEventWire> report(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody AdverseEventReportRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(events.reportEvent(identity, idempotencyKey, command));
    }

    @PostMapping("/adverse-events/{adverse_event_id}/reviews")
    ResponseEntity<AdverseEventWire> review(
            HttpServletRequest request,
            @PathVariable("adverse_event_id") UUID eventId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody AdverseEventReviewRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(events.reviewEvent(identity, idempotencyKey, eventId, command));
    }
}
