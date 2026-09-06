package org.haoemr.reminder;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ClinicalReminderAcknowledgeRequestWire;
import org.haoemr.contracts.ClinicalReminderCreateRequestWire;
import org.haoemr.contracts.ClinicalReminderSilenceRequestWire;
import org.haoemr.contracts.ClinicalReminderWire;
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
final class ClinicalReminderController {
    private final ClinicalCommandSecurity security;
    private final ClinicalReminderService reminders;

    ClinicalReminderController(ClinicalCommandSecurity security, ClinicalReminderService reminders) {
        this.security = security;
        this.reminders = reminders;
    }

    @GetMapping("/clinical-reminders")
    ResponseEntity<List<ClinicalReminderWire>> list(
            HttpServletRequest request,
            @RequestParam("encounter_id") UUID encounterId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId,
            @RequestHeader("X-Encounter-Context") UUID encounterContextId) {
        if (!encounterId.equals(encounterContextId)) throw ClinicalReminderService.contextDenied();
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(reminders.list(identity, organizationId, facilityId, patientId, encounterId));
    }

    @PostMapping("/clinical-reminders")
    ResponseEntity<ClinicalReminderWire> create(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ClinicalReminderCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(reminders.create(identity, idempotencyKey, command));
    }

    @PostMapping("/clinical-reminders/{reminder_id}/acknowledgements")
    ResponseEntity<ClinicalReminderWire> acknowledge(
            HttpServletRequest request,
            @PathVariable("reminder_id") UUID reminderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ClinicalReminderAcknowledgeRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(reminders.acknowledge(identity, idempotencyKey, reminderId, command));
    }

    @PostMapping("/clinical-reminders/{reminder_id}/silences")
    ResponseEntity<ClinicalReminderWire> silence(
            HttpServletRequest request,
            @PathVariable("reminder_id") UUID reminderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ClinicalReminderSilenceRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(reminders.silence(identity, idempotencyKey, reminderId, command));
    }
}
