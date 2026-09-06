package org.haoemr.tasks;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ClinicalTaskNotificationCreateRequestWire;
import org.haoemr.contracts.ClinicalTaskNotificationDeliverRequestWire;
import org.haoemr.contracts.ClinicalTaskNotificationDispatchRequestWire;
import org.haoemr.contracts.ClinicalTaskNotificationDispatchResultWire;
import org.haoemr.contracts.ClinicalTaskNotificationFailRequestWire;
import org.haoemr.contracts.ClinicalTaskNotificationRecoverRequestWire;
import org.haoemr.contracts.ClinicalTaskNotificationRecoveryResultWire;
import org.haoemr.contracts.ClinicalTaskNotificationWire;
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
final class ClinicalTaskNotificationController {
    private final ClinicalCommandSecurity security;
    private final ClinicalTaskNotificationService notifications;

    ClinicalTaskNotificationController(ClinicalCommandSecurity security, ClinicalTaskNotificationService notifications) {
        this.security = security;
        this.notifications = notifications;
    }

    @GetMapping("/clinical-task-notifications")
    ResponseEntity<List<ClinicalTaskNotificationWire>> list(
            HttpServletRequest request,
            @RequestParam("task_id") UUID taskId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId,
            @RequestHeader("X-Encounter-Context") UUID encounterId) {
        ClinicalIdentity identity = security.authorize(
                request, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(notifications.list(identity, patientId, encounterId, facilityId, taskId));
    }

    @PostMapping("/clinical-task-notifications")
    ResponseEntity<ClinicalTaskNotificationWire> create(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ClinicalTaskNotificationCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(notifications.create(identity, idempotencyKey, command));
    }

    @PostMapping("/clinical-task-notifications/dispatches")
    ResponseEntity<ClinicalTaskNotificationDispatchResultWire> dispatchDue(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ClinicalTaskNotificationDispatchRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(notifications.dispatchDue(identity, idempotencyKey, command));
    }

    @PostMapping("/clinical-task-notifications/recoveries")
    ResponseEntity<ClinicalTaskNotificationRecoveryResultWire> recover(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ClinicalTaskNotificationRecoverRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(notifications.recover(identity, idempotencyKey, command));
    }

    @PostMapping("/clinical-task-notifications/{notification_id}/deliveries")
    ResponseEntity<ClinicalTaskNotificationWire> deliver(
            HttpServletRequest request,
            @PathVariable("notification_id") UUID notificationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ClinicalTaskNotificationDeliverRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(notifications.deliver(identity, idempotencyKey, notificationId, command));
    }

    @PostMapping("/clinical-task-notifications/{notification_id}/failures")
    ResponseEntity<ClinicalTaskNotificationWire> fail(
            HttpServletRequest request,
            @PathVariable("notification_id") UUID notificationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ClinicalTaskNotificationFailRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(notifications.fail(identity, idempotencyKey, notificationId, command));
    }
}
