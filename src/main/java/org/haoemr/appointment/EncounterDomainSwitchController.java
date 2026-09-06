package org.haoemr.appointment;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.EncounterDomainSwitchRecordRequestWire;
import org.haoemr.contracts.EncounterDomainSwitchCorrectionRequestWire;
import org.haoemr.contracts.EncounterDomainSwitchVoidRequestWire;
import org.haoemr.contracts.EncounterDomainSwitchWire;
import org.haoemr.security.ClinicalCommandSecurity;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
final class EncounterDomainSwitchController {
    private final ClinicalCommandSecurity security;
    private final EncounterDomainSwitchService switches;

    EncounterDomainSwitchController(ClinicalCommandSecurity security, EncounterDomainSwitchService switches) {
        this.security = security;
        this.switches = switches;
    }

    @GetMapping("/encounter-domain-switches")
    ResponseEntity<List<EncounterDomainSwitchWire>> list(
            HttpServletRequest request,
            @RequestParam("patient_id") UUID patientId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientContextId) {
        if (!patientId.equals(patientContextId)) throw EncounterDomainSwitchService.contextDenied();
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(switches.listSwitches(identity, patientId));
    }

    @PostMapping("/encounter-domain-switches")
    ResponseEntity<EncounterDomainSwitchWire> record(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody EncounterDomainSwitchRecordRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(switches.record(identity, idempotencyKey, command));
    }

    @PostMapping("/encounter-domain-switches/{domain_switch_id}/corrections")
    ResponseEntity<EncounterDomainSwitchWire> correct(
            HttpServletRequest request,
            @PathVariable("domain_switch_id") UUID switchId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody EncounterDomainSwitchCorrectionRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(switches.correct(identity, idempotencyKey, switchId, command));
    }

    @PostMapping("/encounter-domain-switches/{domain_switch_id}/voids")
    ResponseEntity<EncounterDomainSwitchWire> voidSwitch(
            HttpServletRequest request,
            @PathVariable("domain_switch_id") UUID switchId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody EncounterDomainSwitchVoidRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(switches.voidSwitch(identity, idempotencyKey, switchId, command));
    }
}
