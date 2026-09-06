package org.haoemr.surgery;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.SurgicalProcedureScheduleRequestWire;
import org.haoemr.contracts.SurgicalProcedureTransitionRequestWire;
import org.haoemr.contracts.SurgicalProcedureWire;
import org.haoemr.contracts.SurgicalTimeOutVerificationRequestWire;
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
final class SurgicalProcedureController {
    private final ClinicalCommandSecurity security;
    private final SurgicalProcedureService procedures;

    SurgicalProcedureController(ClinicalCommandSecurity security, SurgicalProcedureService procedures) {
        this.security = security;
        this.procedures = procedures;
    }

    @GetMapping("/surgical-procedures")
    ResponseEntity<List<SurgicalProcedureWire>> list(
            HttpServletRequest request,
            @RequestParam("patient_id") UUID patientId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientContextId) {
        if (!patientId.equals(patientContextId)) throw SurgicalProcedureService.contextDenied();
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(procedures.listProcedures(identity, patientId));
    }

    @PostMapping("/surgical-procedures")
    ResponseEntity<SurgicalProcedureWire> schedule(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody SurgicalProcedureScheduleRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(procedures.schedule(identity, idempotencyKey, command));
    }

    @PostMapping("/surgical-procedures/{surgical_procedure_id}/transitions")
    ResponseEntity<SurgicalProcedureWire> transition(
            HttpServletRequest request,
            @PathVariable("surgical_procedure_id") UUID surgicalProcedureId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody SurgicalProcedureTransitionRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(procedures.transition(identity, idempotencyKey, surgicalProcedureId, command));
    }

    @PostMapping("/surgical-procedures/{surgical_procedure_id}/time-out-verifications")
    ResponseEntity<SurgicalProcedureWire> verifyTimeOut(
            HttpServletRequest request,
            @PathVariable("surgical_procedure_id") UUID surgicalProcedureId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody SurgicalTimeOutVerificationRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(procedures.verifyTimeOut(identity, idempotencyKey, surgicalProcedureId, command));
    }
}
