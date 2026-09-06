package org.haoemr.infection;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.InfectionMonitoringEventReportRequestWire;
import org.haoemr.contracts.InfectionMonitoringEventResolveRequestWire;
import org.haoemr.contracts.InfectionMonitoringEventWire;
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
final class InfectionEventController {
    private final ClinicalCommandSecurity security;
    private final InfectionEventService events;

    InfectionEventController(ClinicalCommandSecurity security, InfectionEventService events) {
        this.security = security;
        this.events = events;
    }

    @GetMapping("/infection-monitoring-events")
    ResponseEntity<List<InfectionMonitoringEventWire>> list(
            HttpServletRequest request,
            @RequestParam("patient_id") UUID patientId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientContextId) {
        if (!patientId.equals(patientContextId)) throw InfectionEventService.contextDenied();
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(events.listEvents(identity, patientId));
    }

    @PostMapping("/infection-monitoring-events")
    ResponseEntity<InfectionMonitoringEventWire> report(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody InfectionMonitoringEventReportRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(events.report(identity, idempotencyKey, command));
    }

    @PostMapping("/infection-monitoring-events/{infection_event_id}/resolutions")
    ResponseEntity<InfectionMonitoringEventWire> resolve(
            HttpServletRequest request,
            @PathVariable("infection_event_id") UUID infectionEventId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody InfectionMonitoringEventResolveRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(events.resolve(identity, idempotencyKey, infectionEventId, command));
    }
}
