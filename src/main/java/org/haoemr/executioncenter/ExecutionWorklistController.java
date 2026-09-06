package org.haoemr.executioncenter;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.SpecialtyExecutionCaseCreateRequestWire;
import org.haoemr.contracts.SpecialtyExecutionCaseTransitionRequestWire;
import org.haoemr.contracts.SpecialtyExecutionCaseUpdateRequestWire;
import org.haoemr.contracts.SpecialtyExecutionCaseWire;
import org.haoemr.security.ClinicalCommandSecurity;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/execution-center")
final class ExecutionWorklistController {
    private final ClinicalCommandSecurity security;
    private final ExecutionWorklistService worklists;
    private final SpecialtyExecutionCaseService specialtyCases;

    ExecutionWorklistController(ClinicalCommandSecurity security, ExecutionWorklistService worklists,
            SpecialtyExecutionCaseService specialtyCases) {
        this.security = security;
        this.worklists = worklists;
        this.specialtyCases = specialtyCases;
    }

    @GetMapping("/worklists/{domain}")
    ResponseEntity<List<ExecutionWorklistItem>> list(
            HttpServletRequest request,
            @PathVariable String domain,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(
                request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(worklists.list(identity, organizationId, facilityId, domain));
    }

    @GetMapping("/cases")
    ResponseEntity<List<SpecialtyExecutionCaseWire>> listCases(
            HttpServletRequest request, @RequestParam String domain,
            @RequestParam("patient_id") UUID patientId, @RequestParam("encounter_id") UUID encounterId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(specialtyCases.list(identity, facilityId, patientId, encounterId, domain));
    }

    @GetMapping("/cases/{caseId}")
    ResponseEntity<SpecialtyExecutionCaseWire> getCase(
            HttpServletRequest request, @PathVariable UUID caseId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId,
            @RequestHeader("X-Encounter-Context") UUID encounterId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(specialtyCases.get(identity, facilityId, patientId, encounterId, caseId));
    }

    @PostMapping("/cases")
    ResponseEntity<SpecialtyExecutionCaseWire> createCase(
            HttpServletRequest request, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody SpecialtyExecutionCaseCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(request, command.organizationId(), command.facilityId(),
                command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(specialtyCases.create(identity, idempotencyKey, command));
    }

    @PutMapping("/cases/{caseId}")
    ResponseEntity<SpecialtyExecutionCaseWire> updateCase(
            HttpServletRequest request, @PathVariable UUID caseId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody SpecialtyExecutionCaseUpdateRequestWire command) {
        ClinicalIdentity identity = security.authorize(request, command.organizationId(), command.facilityId(),
                command.patientId(), command.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(specialtyCases.update(identity, caseId, idempotencyKey, command));
    }

    @PostMapping("/cases/{caseId}/transitions")
    ResponseEntity<SpecialtyExecutionCaseWire> transitionCase(
            HttpServletRequest request, @PathVariable UUID caseId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody SpecialtyExecutionCaseTransitionRequestWire command) {
        ClinicalIdentity identity = security.authorize(request, command.organizationId(), command.facilityId(),
                command.patientId(), command.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(specialtyCases.transition(identity, caseId, idempotencyKey, command));
    }
}
