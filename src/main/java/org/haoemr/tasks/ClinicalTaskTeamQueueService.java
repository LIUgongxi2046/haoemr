package org.haoemr.tasks;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ClinicalTaskTeamQueueEnqueueRequestWire;
import org.haoemr.contracts.ClinicalTaskTeamQueueTransitionRequestWire;
import org.haoemr.contracts.ClinicalTaskTeamQueueWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class ClinicalTaskTeamQueueService {
    private static final List<String> TERMINAL_TASK_STATES = List.of("COMPLETED", "WITHDRAWN", "EXPIRED");

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    ClinicalTaskTeamQueueService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    ClinicalTaskTeamQueueWire enqueue(
            ClinicalIdentity identity, String idempotencyKey, ClinicalTaskTeamQueueEnqueueRequestWire request) {
        if (request.departmentId() == null || request.clinicalTaskId() == null || request.enqueuedAt() == null) {
            throw invalid("department_id, clinical_task_id and enqueued_at are required");
        }
        return transactions.execute(status -> {
            beginCommand(identity, "CLINICAL_TASK_TEAM_QUEUE_ENQUEUE", idempotencyKey,
                    sha256(request.departmentId() + "|" + request.clinicalTaskId()));
            requireActiveDepartment(identity.tenantId(), request.facilityId(), request.departmentId());
            requireTeamMembership(identity, request.organizationId(), request.facilityId(), request.departmentId());
            requireEnqueueableTask(identity.tenantId(), request.facilityId(), request.clinicalTaskId());
            UUID queueId = UUID.randomUUID();
            jdbc.sql("""
                    insert into clinical_task_team_queue(
                      tenant_id, queue_id, facility_id, department_id, clinical_task_id,
                      queue_status, enqueued_by, enqueued_at)
                    values (:tenant, :queue, :facility, :department, :task, 'ENQUEUED', :actor, :enqueued_at)
                    """).param("tenant", identity.tenantId()).param("queue", queueId)
                    .param("facility", request.facilityId()).param("department", request.departmentId())
                    .param("task", request.clinicalTaskId()).param("actor", identity.userId())
                    .param("enqueued_at", request.enqueuedAt().atOffset(ZoneOffset.UTC)).update();
            appendEvidence(identity, queueId, "CLINICAL_TASK_TEAM_QUEUE_ENQUEUED", "ClinicalTaskTeamQueueEnqueued");
            completeCommand(identity, "CLINICAL_TASK_TEAM_QUEUE_ENQUEUE", idempotencyKey, queueId);
            return queue(identity.tenantId(), queueId);
        });
    }

    ClinicalTaskTeamQueueWire claim(
            ClinicalIdentity identity, String idempotencyKey, UUID queueId,
            ClinicalTaskTeamQueueTransitionRequestWire request) {
        return transactions.execute(status -> {
            beginCommand(identity, "CLINICAL_TASK_TEAM_QUEUE_CLAIM", idempotencyKey,
                    sha256(queueId + "|" + request.expectedRowVersion()));
            QueueHead head = lockQueue(identity.tenantId(), queueId);
            requireVersion(head, request.expectedRowVersion());
            requireState(head, "ENQUEUED", "Only an enqueued entry can be claimed");
            requireFacility(head, request.facilityId());
            requireTeamMembership(identity, request.organizationId(), request.facilityId(), head.departmentId());
            jdbc.sql("""
                    update clinical_task_team_queue
                    set queue_status = 'CLAIMED', claimed_by = :actor, claimed_at = now(),
                      row_version = row_version + 1
                    where tenant_id = :tenant and queue_id = :queue and row_version = :expected
                    """).param("tenant", identity.tenantId()).param("queue", queueId)
                    .param("actor", identity.userId()).param("expected", head.rowVersion()).update();
            appendEvidence(identity, queueId, "CLINICAL_TASK_TEAM_QUEUE_CLAIMED", "ClinicalTaskTeamQueueClaimed");
            completeCommand(identity, "CLINICAL_TASK_TEAM_QUEUE_CLAIM", idempotencyKey, queueId);
            return queue(identity.tenantId(), queueId);
        });
    }

    ClinicalTaskTeamQueueWire complete(
            ClinicalIdentity identity, String idempotencyKey, UUID queueId,
            ClinicalTaskTeamQueueTransitionRequestWire request) {
        return transition(identity, idempotencyKey, queueId, request, "CLINICAL_TASK_TEAM_QUEUE_COMPLETE",
                "CLAIMED", "COMPLETED", "ClinicalTaskTeamQueueCompleted");
    }

    ClinicalTaskTeamQueueWire withdraw(
            ClinicalIdentity identity, String idempotencyKey, UUID queueId,
            ClinicalTaskTeamQueueTransitionRequestWire request) {
        return transition(identity, idempotencyKey, queueId, request, "CLINICAL_TASK_TEAM_QUEUE_WITHDRAW",
                "ENQUEUED", "WITHDRAWN", "ClinicalTaskTeamQueueWithdrawn");
    }

    List<ClinicalTaskTeamQueueWire> list(
            ClinicalIdentity identity, UUID organizationId, UUID facilityId, UUID departmentId) {
        requireActiveDepartment(identity.tenantId(), facilityId, departmentId);
        requireTeamMembership(identity, organizationId, facilityId, departmentId);
        return jdbc.sql("""
                select queue.queue_id from clinical_task_team_queue queue
                join clinical_task task on task.tenant_id = queue.tenant_id
                  and task.task_id = queue.clinical_task_id
                where queue.tenant_id = :tenant and queue.facility_id = :facility
                  and queue.department_id = :department
                  and task.state not in ('WITHDRAWN', 'EXPIRED')
                order by enqueued_at desc, queue_id desc limit 500
                """).param("tenant", identity.tenantId()).param("facility", facilityId)
                .param("department", departmentId)
                .query(UUID.class).list().stream()
                .map(id -> queue(identity.tenantId(), id)).toList();
    }

    private ClinicalTaskTeamQueueWire transition(
            ClinicalIdentity identity, String idempotencyKey, UUID queueId,
            ClinicalTaskTeamQueueTransitionRequestWire request, String scope,
            String fromStatus, String toStatus, String eventType) {
        return transactions.execute(status -> {
            beginCommand(identity, scope, idempotencyKey, sha256(queueId + "|" + request.expectedRowVersion()));
            QueueHead head = lockQueue(identity.tenantId(), queueId);
            requireVersion(head, request.expectedRowVersion());
            requireState(head, fromStatus,
                    "Only a " + fromStatus.toLowerCase() + " entry can transition to " + toStatus.toLowerCase());
            requireFacility(head, request.facilityId());
            requireTeamMembership(identity, request.organizationId(), request.facilityId(), head.departmentId());
            if ("COMPLETED".equals(toStatus) && !identity.userId().equals(head.claimedBy())) {
                throw new ClinicalTaskTeamQueueException(
                        "TEAM_QUEUE_CLAIMANT_REQUIRED", 403,
                        "Only the clinician who claimed this queue entry can complete it");
            }
            if ("WITHDRAWN".equals(toStatus) && !identity.userId().equals(head.enqueuedBy())) {
                throw new ClinicalTaskTeamQueueException(
                        "TEAM_QUEUE_ENQUEUER_REQUIRED", 403,
                        "Only the clinician who enqueued this entry can withdraw it");
            }
            jdbc.sql("""
                    update clinical_task_team_queue
                    set queue_status = :to_status, row_version = row_version + 1
                    where tenant_id = :tenant and queue_id = :queue and row_version = :expected
                    """).param("tenant", identity.tenantId()).param("queue", queueId)
                    .param("to_status", toStatus).param("expected", head.rowVersion()).update();
            appendEvidence(identity, queueId, scope, eventType);
            completeCommand(identity, scope, idempotencyKey, queueId);
            return queue(identity.tenantId(), queueId);
        });
    }

    private ClinicalTaskTeamQueueWire queue(UUID tenantId, UUID queueId) {
        return jdbc.sql("""
                select queue_id, facility_id, department_id, clinical_task_id, queue_status,
                  enqueued_by, enqueued_at, claimed_by, claimed_at, row_version
                from clinical_task_team_queue
                where tenant_id = :tenant and queue_id = :queue
                """).param("tenant", tenantId).param("queue", queueId)
                .query((rs, row) -> new ClinicalTaskTeamQueueWire(
                        rs.getObject("queue_id", UUID.class),
                        rs.getObject("facility_id", UUID.class),
                        rs.getObject("department_id", UUID.class),
                        rs.getObject("clinical_task_id", UUID.class),
                        ClinicalTaskTeamQueueWire.QueueStatusValue.valueOf(rs.getString("queue_status")),
                        rs.getObject("enqueued_by", UUID.class),
                        rs.getObject("enqueued_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("claimed_by", UUID.class),
                        rs.getObject("claimed_at", OffsetDateTime.class) == null
                                ? null : rs.getObject("claimed_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("row_version")))
                .optional().orElseThrow(ClinicalTaskTeamQueueService::contextDenied);
    }

    private void requireActiveDepartment(UUID tenantId, UUID facilityId, UUID departmentId) {
        long count = jdbc.sql("""
                select count(*) from clinical_department
                where tenant_id = :tenant and facility_id = :facility and department_id = :department
                  and status = 'ACTIVE'
                """).param("tenant", tenantId).param("facility", facilityId)
                .param("department", departmentId).query(Long.class).single();
        if (count != 1) {
            throw new ClinicalTaskTeamQueueException(
                    "DEPARTMENT_SCOPE_DENIED", 403, "The department is not active in the requested facility");
        }
    }

    private void requireEnqueueableTask(UUID tenantId, UUID facilityId, UUID taskId) {
        TaskHead task = jdbc.sql("""
                select state, facility_id from clinical_task
                where tenant_id = :tenant and task_id = :task for update
                """).param("tenant", tenantId).param("task", taskId)
                .query((rs, row) -> new TaskHead(rs.getString("state"), rs.getObject("facility_id", UUID.class)))
                .optional().orElseThrow(ClinicalTaskTeamQueueService::contextDenied);
        if (TERMINAL_TASK_STATES.contains(task.state())) {
            throw new ClinicalTaskTeamQueueException(
                    "TASK_TERMINAL", 409, "A terminal clinical task cannot be enqueued");
        }
        if (!facilityId.equals(task.facilityId())) {
            throw new ClinicalTaskTeamQueueException(
                    "TASK_FACILITY_MISMATCH", 409, "The clinical task belongs to a different facility");
        }
    }

    private void requireTeamMembership(
            ClinicalIdentity identity, UUID organizationId, UUID facilityId, UUID departmentId) {
        String roles = "{" + identity.roleAssignmentIds().stream().map(UUID::toString)
                .reduce((left, right) -> left + "," + right).orElse("") + "}";
        long count = jdbc.sql("""
                select count(*)
                from role_assignment role
                join workforce_assignment assignment
                  on assignment.tenant_id = role.tenant_id
                 and assignment.source_role_assignment_id = role.role_assignment_id
                where role.tenant_id = :tenant and role.user_id = :user
                  and role.organization_id = :organization
                  and role.role_assignment_id = any(cast(:roles as uuid[]))
                  and role.status = 'ACTIVE' and role.valid_from <= now()
                  and (role.valid_until is null or role.valid_until > now())
                  and assignment.status = 'ACTIVE' and assignment.facility_id = :facility
                  and assignment.department_id = :department
                  and assignment.valid_from <= now()
                  and (assignment.valid_until is null or assignment.valid_until > now())
                """).param("tenant", identity.tenantId()).param("user", identity.userId())
                .param("organization", organizationId).param("facility", facilityId)
                .param("department", departmentId).param("roles", roles)
                .query(Long.class).single();
        if (count < 1) {
            throw new ClinicalTaskTeamQueueException(
                    "TEAM_MEMBERSHIP_REQUIRED", 403,
                    "No active department assignment can claim a task in this team queue");
        }
    }

    private QueueHead lockQueue(UUID tenantId, UUID queueId) {
        return jdbc.sql("""
                select queue_status, row_version, facility_id, department_id, enqueued_by, claimed_by
                from clinical_task_team_queue
                where tenant_id = :tenant and queue_id = :queue for update
                """).param("tenant", tenantId).param("queue", queueId)
                .query((rs, row) -> new QueueHead(
                        rs.getString("queue_status"), rs.getLong("row_version"),
                        rs.getObject("facility_id", UUID.class), rs.getObject("department_id", UUID.class),
                        rs.getObject("enqueued_by", UUID.class), rs.getObject("claimed_by", UUID.class)))
                .optional().orElseThrow(ClinicalTaskTeamQueueService::contextDenied);
    }

    private static void requireFacility(QueueHead head, UUID facilityId) {
        if (facilityId == null || !facilityId.equals(head.facilityId())) {
            throw new ClinicalTaskTeamQueueException(
                    "TEAM_QUEUE_FACILITY_MISMATCH", 403,
                    "The queue entry belongs to a different facility");
        }
    }

    private static void requireVersion(QueueHead head, Long expected) {
        if (expected == null || head.rowVersion() != expected) {
            throw new ClinicalTaskTeamQueueException(
                    "CLINICAL_TASK_TEAM_QUEUE_VERSION_CONFLICT", 409, "The queue entry changed; reload before retrying");
        }
    }

    private static void requireState(QueueHead head, String expected, String message) {
        if (!expected.equals(head.status())) {
            throw new ClinicalTaskTeamQueueException(
                    "CLINICAL_TASK_TEAM_QUEUE_STATE_INVALID", 409, message);
        }
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new ClinicalTaskTeamQueueException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new ClinicalTaskTeamQueueException("IDEMPOTENCY_REPLAY", 409,
                    "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID queueId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", queueId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, UUID queueId, String action, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + queueId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'CLINICAL_TASK_TEAM_QUEUE', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", queueId)
                .param("patient_hash", patientHashForQueue(identity.tenantId(), queueId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'CLINICAL_TASK_TEAM_QUEUE', :aggregate, 1, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", queueId).param("event_type", eventType).update();
    }

    private String patientHashForQueue(UUID tenantId, UUID queueId) {
        UUID patientId = jdbc.sql("""
                select task.patient_id
                from clinical_task_team_queue queue
                join clinical_task task on task.tenant_id = queue.tenant_id
                  and task.task_id = queue.clinical_task_id
                where queue.tenant_id = :tenant and queue.queue_id = :queue
                """).param("tenant", tenantId).param("queue", queueId)
                .query(UUID.class).single();
        return sha256(tenantId + "|" + patientId);
    }

    private static ClinicalTaskTeamQueueException invalid(String message) {
        return new ClinicalTaskTeamQueueException("CLINICAL_TASK_TEAM_QUEUE_REQUEST_INVALID", 400, message);
    }

    static ClinicalTaskTeamQueueException contextDenied() {
        return new ClinicalTaskTeamQueueException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested clinical task team queue context is not permitted");
    }

    private record QueueHead(
            String status, long rowVersion, UUID facilityId, UUID departmentId,
            UUID enqueuedBy, UUID claimedBy) {}
    private record TaskHead(String state, UUID facilityId) {}

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
