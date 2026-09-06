package org.haoemr.tasks;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ClinicalTaskCollaboratorWire;
import org.haoemr.contracts.ClinicalTaskCollaborationRequestWire;
import org.haoemr.contracts.ClinicalTaskCommandRequestWire;
import org.haoemr.contracts.ClinicalTaskDetailWire;
import org.haoemr.contracts.ClinicalTaskExpirationResultWire;
import org.haoemr.contracts.ClinicalTaskWire;
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
final class ClinicalTaskController {
    private final ClinicalCommandSecurity security;
    private final ClinicalTaskService tasks;

    ClinicalTaskController(ClinicalCommandSecurity security, ClinicalTaskService tasks) {
        this.security = security;
        this.tasks = tasks;
    }

    @GetMapping("/clinical-tasks")
    ResponseEntity<List<ClinicalTaskWire>> list(
            HttpServletRequest request,
            @RequestParam("encounter_id") UUID encounterId,
            @RequestParam(value = "include_expired", defaultValue = "false") boolean includeExpired,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId,
            @RequestHeader("X-Encounter-Context") UUID encounterContextId) {
        if (!encounterId.equals(encounterContextId)) throw ClinicalTaskService.contextDenied();
        ClinicalIdentity identity = security.authorize(
                request, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(tasks.list(identity, patientId, encounterId, facilityId, includeExpired));
    }

    @GetMapping("/clinical-tasks/collaborators")
    ResponseEntity<List<ClinicalTaskCollaboratorWire>> collaborators(
            HttpServletRequest request,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId,
            @RequestHeader("X-Encounter-Context") UUID encounterId) {
        ClinicalIdentity identity = security.authorize(
                request, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(tasks.eligibleCollaborators(identity, facilityId));
    }

    @GetMapping("/clinical-tasks/{taskId}")
    ResponseEntity<ClinicalTaskDetailWire> detail(
            HttpServletRequest request,
            @PathVariable UUID taskId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId,
            @RequestHeader("X-Encounter-Context") UUID encounterId) {
        ClinicalIdentity identity = security.authorize(
                request, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(tasks.detail(identity, taskId, patientId, encounterId, facilityId));
    }

    @PostMapping("/clinical-tasks/{taskId}/views")
    ResponseEntity<ClinicalTaskWire> view(
            HttpServletRequest request,
            @PathVariable UUID taskId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ClinicalTaskCommandRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(),
                command.patientId(), command.encounterId());
        return response(tasks.view(identity, idempotencyKey, taskId, command));
    }

    @PostMapping("/clinical-tasks/{taskId}/claims")
    ResponseEntity<ClinicalTaskWire> claim(
            HttpServletRequest request,
            @PathVariable UUID taskId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ClinicalTaskCommandRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(),
                command.patientId(), command.encounterId());
        return response(tasks.claim(identity, idempotencyKey, taskId, command));
    }

    @PostMapping("/clinical-tasks/{taskId}/delegations")
    ResponseEntity<ClinicalTaskWire> delegate(
            HttpServletRequest request,
            @PathVariable UUID taskId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ClinicalTaskCollaborationRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(),
                command.patientId(), command.encounterId());
        return response(tasks.delegate(identity, idempotencyKey, taskId, command));
    }

    @PostMapping("/clinical-tasks/{taskId}/transfers")
    ResponseEntity<ClinicalTaskWire> transfer(
            HttpServletRequest request,
            @PathVariable UUID taskId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ClinicalTaskCollaborationRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(),
                command.patientId(), command.encounterId());
        return response(tasks.transfer(identity, idempotencyKey, taskId, command));
    }

    @PostMapping("/clinical-tasks/expirations")
    ResponseEntity<ClinicalTaskExpirationResultWire> expireOverdue(
            HttpServletRequest request,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId,
            @RequestHeader("X-Encounter-Context") UUID encounterId) {
        ClinicalIdentity identity = security.authorize(
                request, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(tasks.expireOverdueTasks(identity, patientId, encounterId, facilityId));
    }

    @PostMapping("/clinical-tasks/{taskId}/escalations")
    ResponseEntity<ClinicalTaskWire> escalate(
            HttpServletRequest request,
            @PathVariable UUID taskId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ClinicalTaskCollaborationRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(),
                command.patientId(), command.encounterId());
        return response(tasks.escalate(identity, idempotencyKey, taskId, command));
    }

    private static ResponseEntity<ClinicalTaskWire> response(ClinicalTaskWire task) {
        return ResponseEntity.ok().eTag("\"" + task.rowVersion() + "\"")
                .header("X-Data-Watermark", task.dataWatermark())
                .cacheControl(CacheControl.noStore()).body(task);
    }
}
