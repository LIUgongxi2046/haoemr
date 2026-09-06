package org.haoemr.tasks;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ClinicalTaskTeamQueueEnqueueRequestWire;
import org.haoemr.contracts.ClinicalTaskTeamQueueTransitionRequestWire;
import org.haoemr.contracts.ClinicalTaskTeamQueueWire;
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
final class ClinicalTaskTeamQueueController {
    private final ClinicalCommandSecurity security;
    private final ClinicalTaskTeamQueueService queues;

    ClinicalTaskTeamQueueController(ClinicalCommandSecurity security, ClinicalTaskTeamQueueService queues) {
        this.security = security;
        this.queues = queues;
    }

    @GetMapping("/clinical-task-team-queues")
    ResponseEntity<List<ClinicalTaskTeamQueueWire>> list(
            HttpServletRequest request,
            @RequestParam("department_id") UUID departmentId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(queues.list(identity, organizationId, facilityId, departmentId));
    }

    @PostMapping("/clinical-task-team-queues")
    ResponseEntity<ClinicalTaskTeamQueueWire> enqueue(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ClinicalTaskTeamQueueEnqueueRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(queues.enqueue(identity, idempotencyKey, command));
    }

    @PostMapping("/clinical-task-team-queues/{queue_id}/claims")
    ResponseEntity<ClinicalTaskTeamQueueWire> claim(
            HttpServletRequest request,
            @PathVariable("queue_id") UUID queueId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ClinicalTaskTeamQueueTransitionRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(queues.claim(identity, idempotencyKey, queueId, command));
    }

    @PostMapping("/clinical-task-team-queues/{queue_id}/completions")
    ResponseEntity<ClinicalTaskTeamQueueWire> complete(
            HttpServletRequest request,
            @PathVariable("queue_id") UUID queueId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ClinicalTaskTeamQueueTransitionRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(queues.complete(identity, idempotencyKey, queueId, command));
    }

    @PostMapping("/clinical-task-team-queues/{queue_id}/withdrawals")
    ResponseEntity<ClinicalTaskTeamQueueWire> withdraw(
            HttpServletRequest request,
            @PathVariable("queue_id") UUID queueId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ClinicalTaskTeamQueueTransitionRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(queues.withdraw(identity, idempotencyKey, queueId, command));
    }
}
