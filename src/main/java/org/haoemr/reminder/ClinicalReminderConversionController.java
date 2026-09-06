package org.haoemr.reminder;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ClinicalReminderConversionCreateRequestWire;
import org.haoemr.contracts.ClinicalReminderConversionWire;
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
final class ClinicalReminderConversionController {
    private final ClinicalCommandSecurity security;
    private final ClinicalReminderConversionService conversions;

    ClinicalReminderConversionController(ClinicalCommandSecurity security, ClinicalReminderConversionService conversions) {
        this.security = security;
        this.conversions = conversions;
    }

    @GetMapping("/clinical-reminder-conversions")
    ResponseEntity<List<ClinicalReminderConversionWire>> list(
            HttpServletRequest request,
            @RequestParam("reminder_id") UUID reminderId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId,
            @RequestHeader("X-Encounter-Context") UUID encounterId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(conversions.list(identity, reminderId, patientId, facilityId, encounterId));
    }

    @PostMapping("/clinical-reminder-conversions")
    ResponseEntity<ClinicalReminderConversionWire> convert(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ClinicalReminderConversionCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(conversions.convert(identity, idempotencyKey, command));
    }
}
