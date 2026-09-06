package org.haoemr.reproductive;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ArtPregnancyOutcomeCreateRequestWire;
import org.haoemr.contracts.ArtPregnancyOutcomeWire;
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
final class ArtPregnancyOutcomeController {
    private final ClinicalCommandSecurity security;
    private final ArtPregnancyOutcomeService outcomes;

    ArtPregnancyOutcomeController(ClinicalCommandSecurity security, ArtPregnancyOutcomeService outcomes) {
        this.security = security;
        this.outcomes = outcomes;
    }

    @GetMapping("/art-pregnancy-outcomes")
    ResponseEntity<List<ArtPregnancyOutcomeWire>> list(
            HttpServletRequest request,
            @RequestParam("patient_id") UUID patientId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientContextId) {
        if (!patientId.equals(patientContextId)) throw ArtPregnancyOutcomeService.contextDenied();
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(outcomes.listRecords(identity, patientId));
    }

    @PostMapping("/art-pregnancy-outcomes")
    ResponseEntity<ArtPregnancyOutcomeWire> record(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ArtPregnancyOutcomeCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(outcomes.record(identity, idempotencyKey, command));
    }
}
