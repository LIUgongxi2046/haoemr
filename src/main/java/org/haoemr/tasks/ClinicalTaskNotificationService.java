package org.haoemr.tasks;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
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
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class ClinicalTaskNotificationService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    ClinicalTaskNotificationService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    ClinicalTaskNotificationWire create(
            ClinicalIdentity identity, String idempotencyKey, ClinicalTaskNotificationCreateRequestWire request) {
        if (request.kind() == null || request.channel() == null) {
            throw invalid("kind and channel are required");
        }
        requireTask(identity.tenantId(), request.taskId(), request.patientId(),
                request.encounterId(), request.facilityId());
        requireRecipient(identity.tenantId(), request.facilityId(), request.recipientUserId());
        return transactions.execute(status -> {
            beginCommand(identity, "TASK_NOTIFICATION_CREATE", idempotencyKey,
                    sha256(request.taskId() + "|" + request.recipientUserId() + "|"
                            + request.kind() + "|" + request.channel()));
            UUID notificationId = UUID.randomUUID();
            jdbc.sql("""
                    insert into clinical_task_notification(
                      tenant_id, notification_id, task_id, recipient_user_id, kind, channel, status, scheduled_at)
                    values (:tenant, :notification, :task, :recipient, :kind, :channel, 'PENDING', :scheduled_at)
                    """).param("tenant", identity.tenantId()).param("notification", notificationId)
                    .param("task", request.taskId()).param("recipient", request.recipientUserId())
                    .param("kind", request.kind().name()).param("channel", request.channel().name())
                    .param("scheduled_at", request.scheduledAt() == null
                            ? OffsetDateTime.now(ZoneOffset.UTC)
                            : request.scheduledAt().atOffset(ZoneOffset.UTC)).update();
            appendEvidence(identity, notificationId, "TASK_NOTIFICATION_CREATED", "TaskNotificationCreated");
            completeCommand(identity, "TASK_NOTIFICATION_CREATE", idempotencyKey, notificationId);
            return notification(identity.tenantId(), notificationId);
        });
    }

    ClinicalTaskNotificationWire deliver(
            ClinicalIdentity identity, String idempotencyKey, UUID notificationId,
            ClinicalTaskNotificationDeliverRequestWire request) {
        return transactions.execute(status -> {
            beginCommand(identity, "TASK_NOTIFICATION_DELIVER", idempotencyKey,
                    sha256(notificationId + "|" + request.expectedRowVersion()));
            NotificationHead head = lockNotification(identity.tenantId(), notificationId);
            requireTask(identity.tenantId(), head.taskId(), request.patientId(),
                    request.encounterId(), request.facilityId());
            if (request.expectedRowVersion() == null || head.rowVersion() != request.expectedRowVersion()) {
                throw versionConflict();
            }
            if (!"PENDING".equals(head.status())) {
                throw new ClinicalTaskNotificationException(
                        "TASK_NOTIFICATION_STATE_INVALID", 409,
                        "Only a pending notification can be delivered");
            }
            requireInAppChannel(head.channel());
            createInAppDelivery(identity.tenantId(), notificationId);
            jdbc.sql("""
                    update clinical_task_notification
                    set status = 'DELIVERED', delivered_at = now(),
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and notification_id = :notification and row_version = :expected
                    """).param("tenant", identity.tenantId()).param("notification", notificationId)
                    .param("expected", head.rowVersion()).update();
            appendEvidence(identity, notificationId, "TASK_NOTIFICATION_DELIVERED", "TaskNotificationDelivered");
            completeCommand(identity, "TASK_NOTIFICATION_DELIVER", idempotencyKey, notificationId);
            return notification(identity.tenantId(), notificationId);
        });
    }

    ClinicalTaskNotificationWire fail(
            ClinicalIdentity identity, String idempotencyKey, UUID notificationId,
            ClinicalTaskNotificationFailRequestWire request) {
        String error = requireText(request.error(), 2, "error");
        return transactions.execute(status -> {
            beginCommand(identity, "TASK_NOTIFICATION_FAIL", idempotencyKey,
                    sha256(notificationId + "|" + request.expectedRowVersion() + "|" + error));
            NotificationHead head = lockNotification(identity.tenantId(), notificationId);
            requireTask(identity.tenantId(), head.taskId(), request.patientId(),
                    request.encounterId(), request.facilityId());
            if (request.expectedRowVersion() == null || head.rowVersion() != request.expectedRowVersion()) {
                throw versionConflict();
            }
            if (!"PENDING".equals(head.status())) {
                throw new ClinicalTaskNotificationException(
                        "TASK_NOTIFICATION_STATE_INVALID", 409,
                        "Only a pending notification can fail delivery");
            }
            jdbc.sql("""
                    update clinical_task_notification
                    set status = 'FAILED', last_error = :error,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and notification_id = :notification and row_version = :expected
                    """).param("tenant", identity.tenantId()).param("notification", notificationId)
                    .param("error", error).param("expected", head.rowVersion()).update();
            appendEvidence(identity, notificationId, "TASK_NOTIFICATION_FAILED", "TaskNotificationFailed");
            completeCommand(identity, "TASK_NOTIFICATION_FAIL", idempotencyKey, notificationId);
            return notification(identity.tenantId(), notificationId);
        });
    }

    ClinicalTaskNotificationRecoveryResultWire recover(
            ClinicalIdentity identity, String idempotencyKey, ClinicalTaskNotificationRecoverRequestWire request) {
        requireTask(identity.tenantId(), request.taskId(), request.patientId(),
                request.encounterId(), request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "TASK_NOTIFICATION_RECOVER", idempotencyKey, sha256(request.taskId().toString()));
            List<UUID> recoveredIds = jdbc.sql("""
                    update clinical_task_notification
                    set status = 'PENDING', attempt_count = attempt_count + 1, last_error = null,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and task_id = :task and status = 'FAILED'
                    returning notification_id
                    """).param("tenant", identity.tenantId()).param("task", request.taskId())
                    .query(UUID.class).list();
            for (UUID notificationId : recoveredIds) {
                appendEvidence(identity, notificationId, "TASK_NOTIFICATION_RECOVERED",
                        "TaskNotificationRecovered");
            }
            completeCommand(identity, "TASK_NOTIFICATION_RECOVER", idempotencyKey, request.taskId());
            return new ClinicalTaskNotificationRecoveryResultWire(recoveredIds.size(), request.taskId());
        });
    }

    List<ClinicalTaskNotificationWire> list(
            ClinicalIdentity identity, UUID patientId, UUID encounterId, UUID facilityId, UUID taskId) {
        requireTask(identity.tenantId(), taskId, patientId, encounterId, facilityId);
        return jdbc.sql("""
                select notification_id from clinical_task_notification
                where tenant_id = :tenant and task_id = :task
                order by created_at desc, notification_id desc limit 200
                """).param("tenant", identity.tenantId()).param("task", taskId)
                .query(UUID.class).list().stream()
                .map(id -> notification(identity.tenantId(), id)).toList();
    }

    ClinicalTaskNotificationDispatchResultWire dispatchDue(
            ClinicalIdentity identity, String idempotencyKey, ClinicalTaskNotificationDispatchRequestWire request) {
        if (request.scheduledBefore() == null) {
            throw invalid("scheduled_before is required");
        }
        int batchSize = request.batchSize() == null ? 100 : request.batchSize();
        if (batchSize < 1 || batchSize > 1000) {
            throw invalid("batch_size must be between 1 and 1000");
        }
        return transactions.execute(status -> {
            beginCommand(identity, "TASK_NOTIFICATION_DISPATCH", idempotencyKey,
                    sha256(request.scheduledBefore() + "|" + batchSize));
            List<UUID> ids = jdbc.sql("""
                    select notification_id from clinical_task_notification
                    where tenant_id = :tenant and status = 'PENDING' and channel = 'IN_APP'
                      and scheduled_at <= :before
                    order by scheduled_at, notification_id limit :batch
                    for update skip locked
                    """).param("tenant", identity.tenantId())
                    .param("before", request.scheduledBefore().atOffset(ZoneOffset.UTC))
                    .param("batch", batchSize).query(UUID.class).list();
            int dispatched = 0;
            if (!ids.isEmpty()) {
                jdbc.sql("""
                        insert into clinical_task_in_app_delivery(
                          tenant_id, delivery_id, notification_id, recipient_user_id, delivered_at)
                        select tenant_id, gen_random_uuid(), notification_id, recipient_user_id, now()
                        from clinical_task_notification
                        where tenant_id = :tenant
                          and notification_id = any(cast(:ids as uuid[])) and status = 'PENDING'
                        on conflict (tenant_id, notification_id) do nothing
                        """).param("tenant", identity.tenantId())
                        .param("ids", postgresUuidArray(ids)).update();
                dispatched = jdbc.sql("""
                        update clinical_task_notification
                        set status = 'DELIVERED', delivered_at = now(), attempt_count = attempt_count + 1,
                          row_version = row_version + 1, updated_at = now()
                        where tenant_id = :tenant
                          and notification_id = any(cast(:ids as uuid[])) and status = 'PENDING'
                        """).param("tenant", identity.tenantId())
                        .param("ids", postgresUuidArray(ids)).update();
                if (dispatched > 0) {
                    for (UUID notificationId : ids) {
                        appendEvidence(identity, notificationId, "TASK_NOTIFICATION_DISPATCHED",
                                "TaskNotificationDispatched");
                    }
                }
            }
            completeCommand(identity, "TASK_NOTIFICATION_DISPATCH", idempotencyKey,
                    ids.isEmpty() ? UUID.randomUUID() : ids.get(0));
            return new ClinicalTaskNotificationDispatchResultWire(dispatched, ids);
        });
    }

    private ClinicalTaskNotificationWire notification(UUID tenantId, UUID notificationId) {
        return jdbc.sql("""
                select notification_id, task_id, recipient_user_id, kind, channel, status,
                  attempt_count, scheduled_at, delivered_at, last_error, row_version
                from clinical_task_notification
                where tenant_id = :tenant and notification_id = :notification
                """).param("tenant", tenantId).param("notification", notificationId)
                .query((rs, row) -> new ClinicalTaskNotificationWire(
                        rs.getObject("notification_id", UUID.class), rs.getObject("task_id", UUID.class),
                        rs.getObject("recipient_user_id", UUID.class),
                        ClinicalTaskNotificationWire.KindValue.valueOf(rs.getString("kind")),
                        ClinicalTaskNotificationWire.ChannelValue.valueOf(rs.getString("channel")),
                        ClinicalTaskNotificationWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getInt("attempt_count"),
                        rs.getObject("scheduled_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("delivered_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("delivered_at", OffsetDateTime.class).toInstant(),
                        rs.getString("last_error"),
                        rs.getLong("row_version")))
                .optional().orElseThrow(ClinicalTaskNotificationService::contextDenied);
    }

    private NotificationHead lockNotification(UUID tenantId, UUID notificationId) {
        return jdbc.sql("""
                select task_id, status, channel, row_version from clinical_task_notification
                where tenant_id = :tenant and notification_id = :notification for update
                """).param("tenant", tenantId).param("notification", notificationId)
                .query((rs, row) -> new NotificationHead(
                        rs.getObject("task_id", UUID.class), rs.getString("status"),
                        rs.getString("channel"), rs.getLong("row_version")))
                .optional().orElseThrow(ClinicalTaskNotificationService::contextDenied);
    }

    private void requireInAppChannel(String channel) {
        if (!"IN_APP".equals(channel)) {
            throw new ClinicalTaskNotificationException(
                    "TASK_NOTIFICATION_EXTERNAL_ADAPTER_REQUIRED", 409,
                    "External notification channels require an acknowledged hospital adapter");
        }
    }

    private void createInAppDelivery(UUID tenantId, UUID notificationId) {
        jdbc.sql("""
                insert into clinical_task_in_app_delivery(
                  tenant_id, delivery_id, notification_id, recipient_user_id, delivered_at)
                select tenant_id, gen_random_uuid(), notification_id, recipient_user_id, now()
                from clinical_task_notification
                where tenant_id = :tenant and notification_id = :notification and status = 'PENDING'
                on conflict (tenant_id, notification_id) do nothing
                """).param("tenant", tenantId).param("notification", notificationId).update();
    }

    private void requireTask(UUID tenantId, UUID taskId, UUID patientId, UUID encounterId, UUID facilityId) {
        long count = jdbc.sql("""
                select count(*) from clinical_task
                where tenant_id = :tenant and task_id = :task and patient_id = :patient
                  and encounter_id = :encounter and facility_id = :facility
                """).param("tenant", tenantId).param("task", taskId).param("patient", patientId)
                .param("encounter", encounterId).param("facility", facilityId).query(Long.class).single();
        if (count != 1) throw contextDenied();
    }

    private void requireRecipient(UUID tenantId, UUID facilityId, UUID recipientUserId) {
        long count = jdbc.sql("""
                select count(*) from app_user account
                where account.tenant_id = :tenant and account.user_id = :recipient
                  and account.status = 'ACTIVE'
                  and exists (
                    select 1 from role_assignment role
                    join workforce_assignment assignment
                      on assignment.tenant_id = role.tenant_id
                     and assignment.source_role_assignment_id = role.role_assignment_id
                    where role.tenant_id = account.tenant_id and role.user_id = account.user_id
                      and role.status = 'ACTIVE' and role.valid_from <= now()
                      and (role.valid_until is null or role.valid_until > now())
                      and assignment.facility_id = :facility and assignment.status = 'ACTIVE'
                      and assignment.valid_from <= now()
                      and (assignment.valid_until is null or assignment.valid_until > now()))
                """).param("tenant", tenantId).param("facility", facilityId)
                .param("recipient", recipientUserId).query(Long.class).single();
        if (count != 1) {
            throw new ClinicalTaskNotificationException(
                    "TASK_NOTIFICATION_RECIPIENT_NOT_ELIGIBLE", 403,
                    "The notification recipient has no active workforce assignment in this facility");
        }
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new ClinicalTaskNotificationException("INVALID_IDEMPOTENCY_KEY", 400,
                    "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new ClinicalTaskNotificationException("IDEMPOTENCY_REPLAY", 409,
                    "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID resourceId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", resourceId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID resourceId, String action, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + resourceId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'CLINICAL_TASK_NOTIFICATION', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", resourceId)
                .param("patient_hash", patientHashForNotification(identity.tenantId(), resourceId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'CLINICAL_TASK_NOTIFICATION', :aggregate, 1, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", resourceId).param("event_type", eventType).update();
    }

    private String patientHashForNotification(UUID tenantId, UUID resourceId) {
        UUID patientId = jdbc.sql("""
                select task.patient_id
                from clinical_task_notification notification
                join clinical_task task on task.tenant_id = notification.tenant_id
                  and task.task_id = notification.task_id
                where notification.tenant_id = :tenant and notification.notification_id = :notification
                """).param("tenant", tenantId).param("notification", resourceId)
                .query(UUID.class).single();
        return sha256(tenantId + "|" + patientId);
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw invalid(field + " must be at least " + minLength + " characters");
        }
        return value.trim();
    }

    private static ClinicalTaskNotificationException invalid(String message) {
        return new ClinicalTaskNotificationException(
                "CLINICAL_TASK_NOTIFICATION_REQUEST_INVALID", 400, message);
    }

    private static ClinicalTaskNotificationException versionConflict() {
        return new ClinicalTaskNotificationException(
                "TASK_NOTIFICATION_VERSION_CONFLICT", 409, "The notification changed; reload before retrying");
    }

    static ClinicalTaskNotificationException contextDenied() {
        return new ClinicalTaskNotificationException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested task notification context is not permitted");
    }

    private record NotificationHead(UUID taskId, String status, String channel, long rowVersion) {}

    private static String postgresUuidArray(List<UUID> values) {
        return "{" + values.stream().map(UUID::toString).reduce((left, right) -> left + "," + right).orElse("") + "}";
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
