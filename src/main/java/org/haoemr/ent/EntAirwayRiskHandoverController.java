package org.haoemr.ent;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.EntAirwayRiskHandoverCreateRequestWire;
import org.haoemr.contracts.EntAirwayRiskHandoverWire;
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
final class EntAirwayRiskHandoverController {
    private final ClinicalCommandSecurity security;
    private final EntAirwayRiskHandoverService handovers;

    EntAirwayRiskHandoverController(ClinicalCommandSecurity security, EntAirwayRiskHandoverService handovers) {
        this.security = security;
        this.handovers = handovers;
    }

    @GetMapping("/ent-airway-risk-handovers")
    ResponseEntity<List<EntAirwayRiskHandoverWire>> list(
            HttpServletRequest request,
            @RequestParam("patient_id") UUID patientId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientContextId) {
        if (!patientId.equals(patientContextId)) throw EntAirwayRiskHandoverService.contextDenied();
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(handovers.listRecords(identity, patientId));
    }

    @PostMapping("/ent-airway-risk-handovers")
    ResponseEntity<EntAirwayRiskHandoverWire> record(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody EntAirwayRiskHandoverCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(handovers.record(identity, idempotencyKey, command));
    }
}
