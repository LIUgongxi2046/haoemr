package org.haoemr.tasks;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Applies time-based task transitions without impersonating a clinical user. */
@Service
final class ClinicalTaskLifecycleService {
    private static final int BATCH_SIZE = 100;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    ClinicalTaskLifecycleService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    TaskLifecycleSweepResult sweepDueTransitions() {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC);
        List<TaskRef> candidates = jdbc.sql("""
                select task.tenant_id, task.task_id
                from clinical_task task
                where task.state not in ('COMPLETED', 'WITHDRAWN', 'EXPIRED')
                  and (
                    (task.due_at is not null and task.due_at <= :cutoff)
                    or (task.escalation_at is not null and task.escalation_at <= :cutoff
                      and not exists (
                        select 1 from clinical_task_event event
                        where event.tenant_id = task.tenant_id and event.task_id = task.task_id
                          and event.event_type = 'AUTO_ESCALATED'))
                    or exists (
                      select 1 from clinical_task_delegation delegation
                      where delegation.tenant_id = task.tenant_id and delegation.task_id = task.task_id
                        and delegation.valid_until <= :cutoff
                        and task.assigned_user_id = delegation.delegated_to
                        and ((task.state = 'ASSIGNED' and task.claimed_by is null)
                          or (task.state in ('CLAIMED', 'IN_PROGRESS', 'ESCALATED')
                            and task.claimed_by = delegation.delegated_to))
                        and not exists (
                          select 1 from clinical_task_delegation newer
                          where newer.tenant_id = delegation.tenant_id and newer.task_id = delegation.task_id
                            and (newer.created_at > delegation.created_at
                              or (newer.created_at = delegation.created_at
                                and newer.delegation_id > delegation.delegation_id)))
                    )
                  )
                order by task.due_at nulls last, task.escalation_at nulls last, task.task_id
                limit :limit
                """).param("cutoff", cutoff).param("limit", BATCH_SIZE)
                .query((rs, row) -> new TaskRef(
                        rs.getObject("tenant_id", UUID.class), rs.getObject("task_id", UUID.class)))
                .list();
        int expired = 0;
        int escalated = 0;
        int delegationExpired = 0;
        for (TaskRef candidate : candidates) {
            SweepOutcome outcome = transactions.execute(status -> applyDueTransition(candidate, cutoff));
            if (outcome == SweepOutcome.EXPIRED) expired++;
            if (outcome == SweepOutcome.AUTO_ESCALATED) escalated++;
            if (outcome == SweepOutcome.DELEGATION_EXPIRED) delegationExpired++;
        }
        return new TaskLifecycleSweepResult(expired, escalated, delegationExpired, cutoff.toInstant());
    }

    private SweepOutcome applyDueTransition(TaskRef reference, OffsetDateTime cutoff) {
        TaskHead task = jdbc.sql("""
                select task_id, patient_id, encounter_id, facility_id, source_id, state, assigned_user_id, claimed_by,
                  due_at, escalation_at, row_version
                from clinical_task
                where tenant_id = :tenant and task_id = :task
                  and state not in ('COMPLETED', 'WITHDRAWN', 'EXPIRED')
                for update skip locked
                """).param("tenant", reference.tenantId()).param("task", reference.taskId())
                .query((rs, row) -> new TaskHead(
                        rs.getObject("task_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class), rs.getObject("facility_id", UUID.class),
                        rs.getObject("source_id", UUID.class), rs.getString("state"),
                        rs.getObject("assigned_user_id", UUID.class), rs.getObject("claimed_by", UUID.class),
                        rs.getObject("due_at", OffsetDateTime.class),
                        rs.getObject("escalation_at", OffsetDateTime.class), rs.getLong("row_version")))
                .optional().orElse(null);
        if (task == null) return SweepOutcome.NONE;
        if (task.dueAt() != null && !task.dueAt().isAfter(cutoff)) return expire(reference.tenantId(), task);

        ExpiredDelegation delegation = hasDelegatedResponsibility(task)
                ? expiredActiveDelegation(reference.tenantId(), task, cutoff) : null;
        if (delegation != null) return settleExpiredDelegation(reference.tenantId(), task, delegation);

        if (task.escalationAt() != null && !task.escalationAt().isAfter(cutoff)
                && !hasAutoEscalation(reference.tenantId(), task.taskId())) {
            return autoEscalate(reference.tenantId(), task);
        }
        return SweepOutcome.NONE;
    }

    private SweepOutcome expire(UUID tenantId, TaskHead task) {
        Long nextVersion = jdbc.sql("""
                update clinical_task set state = 'EXPIRED', row_version = row_version + 1, updated_at = now()
                where tenant_id = :tenant and task_id = :task and row_version = :expected
                  and due_at <= now() and state not in ('COMPLETED', 'WITHDRAWN', 'EXPIRED')
                returning row_version
                """).param("tenant", tenantId).param("task", task.taskId())
                .param("expected", task.rowVersion()).query(Long.class).optional().orElse(null);
        if (nextVersion == null) return SweepOutcome.NONE;
        appendTaskEvent(tenantId, task.taskId(), "EXPIRED", task.state(), "EXPIRED", null,
                null, "TASK_DEADLINE_EXCEEDED", null);
        appendSystemEvidence(tenantId, task.patientId(), task.taskId(), task.sourceId(), nextVersion,
                "EXPIRED", null, "TASK_DEADLINE_EXCEEDED");
        createOverdueTaskReminder(tenantId, task);
        return SweepOutcome.EXPIRED;
    }

    /** Creates one operational reminder for an expired task; it is not a clinical conclusion. */
    private void createOverdueTaskReminder(UUID tenantId, TaskHead task) {
        boolean exists = jdbc.sql("""
                select exists(
                  select 1 from clinical_reminder
                  where tenant_id = :tenant and source_task_id = :task
                )
                """).param("tenant", tenantId).param("task", task.taskId()).query(Boolean.class).single();
        if (exists) return;
        UUID reminderId = UUID.randomUUID();
        jdbc.sql("""
                insert into clinical_reminder(
                  tenant_id, reminder_id, patient_id, encounter_id, facility_id,
                  reminder_type, message, severity, status, source_task_id)
                values (:tenant, :reminder, :patient, :encounter, :facility,
                  'OVERDUE_TASK', :message, 'WARNING', 'PENDING', :task)
                """).param("tenant", tenantId).param("reminder", reminderId).param("patient", task.patientId())
                .param("encounter", task.encounterId()).param("facility", task.facilityId())
                .param("message", "任务已超过到期时间，请人工核查并按来源处置。")
                .param("task", task.taskId()).update();
        appendSystemReminderEvidence(tenantId, task, reminderId);
    }

    private void appendSystemReminderEvidence(UUID tenantId, TaskHead task, UUID reminderId) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", tenantId).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", tenantId).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String action = "CLINICAL_REMINDER_CREATED_BY_TASK_LIFECYCLE";
        String eventHash = sha256(tenantId + "|" + auditId + "|" + action + "|" + reminderId + "|1|"
                + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash, details)
                values (:tenant, :audit, now(), null, :action, 'CLINICAL_REMINDER', :reminder,
                  :patient_hash, :trace, :previous, :event_hash,
                  jsonb_build_object('automation', true, 'source_task_id', :task,
                    'reason', 'TASK_DEADLINE_EXCEEDED'))
                """).param("tenant", tenantId).param("audit", auditId).param("action", action)
                .param("reminder", reminderId).param("patient_hash", sha256(tenantId + "|" + task.patientId()))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash)
                .param("task", task.taskId()).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, gen_random_uuid(), 'CLINICAL_REMINDER', :reminder, 1,
                  'ClinicalReminderCreated', 1, jsonb_build_object('reminder_id', :reminder,
                    'source_task_id', :task, 'automated', true,
                    'reason', 'TASK_DEADLINE_EXCEEDED'))
                """).param("tenant", tenantId).param("reminder", reminderId).param("task", task.taskId()).update();
    }

    private SweepOutcome autoEscalate(UUID tenantId, TaskHead task) {
        Long nextVersion = jdbc.sql("""
                update clinical_task set state = 'ESCALATED', row_version = row_version + 1, updated_at = now()
                where tenant_id = :tenant and task_id = :task and row_version = :expected
                  and escalation_at <= now() and state not in ('COMPLETED', 'WITHDRAWN', 'EXPIRED')
                returning row_version
                """).param("tenant", tenantId).param("task", task.taskId())
                .param("expected", task.rowVersion()).query(Long.class).optional().orElse(null);
        if (nextVersion == null) return SweepOutcome.NONE;
        appendTaskEvent(tenantId, task.taskId(), "AUTO_ESCALATED", task.state(), "ESCALATED", null,
                null, "TASK_ESCALATION_THRESHOLD_REACHED", null);
        appendSystemEvidence(tenantId, task.patientId(), task.taskId(), task.sourceId(), nextVersion,
                "AUTO_ESCALATED", null, "TASK_ESCALATION_THRESHOLD_REACHED");
        return SweepOutcome.AUTO_ESCALATED;
    }

    private ExpiredDelegation expiredActiveDelegation(UUID tenantId, TaskHead task, OffsetDateTime cutoff) {
        return jdbc.sql("""
                select delegation.delegated_by, delegation.delegated_to, delegation.valid_until
                from clinical_task_delegation delegation
                where delegation.tenant_id = :tenant and delegation.task_id = :task
                  and delegation.valid_until <= :cutoff and :assigned = delegation.delegated_to
                  and not exists (
                    select 1 from clinical_task_delegation newer
                    where newer.tenant_id = delegation.tenant_id and newer.task_id = delegation.task_id
                      and (newer.created_at > delegation.created_at
                        or (newer.created_at = delegation.created_at
                          and newer.delegation_id > delegation.delegation_id)))
                """).param("tenant", tenantId).param("task", task.taskId()).param("cutoff", cutoff)
                .param("assigned", task.assignedUserId())
                .query((rs, row) -> new ExpiredDelegation(
                        rs.getObject("delegated_by", UUID.class), rs.getObject("delegated_to", UUID.class),
                        rs.getObject("valid_until", OffsetDateTime.class)))
                .optional().orElse(null);
    }

    private static boolean hasDelegatedResponsibility(TaskHead task) {
        if (task.assignedUserId() == null) return false;
        if ("ASSIGNED".equals(task.state())) return task.claimedBy() == null;
        return List.of("CLAIMED", "IN_PROGRESS", "ESCALATED").contains(task.state())
                && task.assignedUserId().equals(task.claimedBy());
    }

    private SweepOutcome settleExpiredDelegation(UUID tenantId, TaskHead task, ExpiredDelegation delegation) {
        boolean unclaimed = "ASSIGNED".equals(task.state()) && task.claimedBy() == null;
        String nextState = unclaimed ? "CLAIMED" : "ESCALATED";
        UUID nextClaimedBy = unclaimed ? delegation.delegatedBy() : delegation.delegatedTo();
        Long nextVersion = jdbc.sql("""
                update clinical_task set state = :state, assigned_user_id = :assigned,
                  claimed_by = :claimed, row_version = row_version + 1, updated_at = now()
                where tenant_id = :tenant and task_id = :task and row_version = :expected
                  and assigned_user_id = :delegated_to
                  and state not in ('COMPLETED', 'WITHDRAWN', 'EXPIRED')
                returning row_version
                """).param("state", nextState).param("assigned", delegation.delegatedBy())
                .param("claimed", nextClaimedBy).param("tenant", tenantId).param("task", task.taskId())
                .param("expected", task.rowVersion()).param("delegated_to", delegation.delegatedTo())
                .query(Long.class).optional().orElse(null);
        if (nextVersion == null) return SweepOutcome.NONE;
        String reason = unclaimed ? "DELEGATION_VALIDITY_EXPIRED_RETURNED_TO_DELEGATOR"
                : "DELEGATION_VALIDITY_EXPIRED_ESCALATED_TO_DELEGATOR";
        appendTaskEvent(tenantId, task.taskId(), "DELEGATION_EXPIRED", task.state(), nextState, null,
                delegation.delegatedBy(), reason, delegation.validUntil());
        appendSystemEvidence(tenantId, task.patientId(), task.taskId(), task.sourceId(), nextVersion,
                "DELEGATION_EXPIRED", delegation.delegatedBy(), reason);
        return SweepOutcome.DELEGATION_EXPIRED;
    }

    private boolean hasAutoEscalation(UUID tenantId, UUID taskId) {
        return jdbc.sql("""
                select exists(
                  select 1 from clinical_task_event
                  where tenant_id = :tenant and task_id = :task and event_type = 'AUTO_ESCALATED')
                """).param("tenant", tenantId).param("task", taskId).query(Boolean.class).single();
    }

    private void appendTaskEvent(
            UUID tenantId, UUID taskId, String eventType, String previousState, String resultingState,
            UUID actorUserId, UUID targetUserId, String reason, OffsetDateTime validUntil) {
        jdbc.sql("""
                insert into clinical_task_event(
                  tenant_id, task_event_id, task_id, event_type, previous_state, resulting_state,
                  actor_user_id, target_user_id, reason, valid_until)
                values (:tenant, gen_random_uuid(), :task, :event_type, :previous, :resulting,
                  cast(:actor as uuid), cast(:target as uuid), :reason, cast(:valid_until as timestamptz))
                """).param("tenant", tenantId).param("task", taskId).param("event_type", eventType)
                .param("previous", previousState).param("resulting", resultingState).param("actor", actorUserId)
                .param("target", targetUserId).param("reason", reason).param("valid_until", validUntil).update();
    }

    private void appendSystemEvidence(
            UUID tenantId, UUID patientId, UUID taskId, UUID sourceId, long taskVersion,
            String eventType, UUID targetUserId, String reason) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", tenantId).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", tenantId).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String action = "CLINICAL_TASK_" + eventType;
        String eventHash = sha256(tenantId + "|" + auditId + "|" + action + "|" + taskId + "|"
                + taskVersion + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash, details)
                values (:tenant, :audit, now(), null, :action, 'CLINICAL_TASK', :task,
                  :patient_hash, :trace, :previous, :event_hash,
                  jsonb_build_object('automation', true, 'source_id', :source,
                    'target_user_id', cast(:target as uuid), 'reason', cast(:reason as text)))
                """).param("tenant", tenantId).param("audit", auditId).param("action", action)
                .param("task", taskId).param("patient_hash", sha256(tenantId + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash)
                .param("source", sourceId).param("target", targetUserId).param("reason", reason).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, gen_random_uuid(), 'CLINICAL_TASK', :task, :version,
                  :event_type, 1, jsonb_build_object('task_id', :task, 'source_id', :source,
                    'automated', true, 'target_user_id', cast(:target as uuid),
                    'reason', cast(:reason as text)))
                """).param("tenant", tenantId).param("task", taskId).param("version", taskVersion)
                .param("event_type", "ClinicalTask" + title(eventType)).param("source", sourceId)
                .param("target", targetUserId).param("reason", reason).update();
    }

    private static String title(String value) {
        return value.substring(0, 1) + value.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    record TaskLifecycleSweepResult(int expiredCount, int autoEscalatedCount, int delegationExpiredCount,
                                    java.time.Instant sweptAt) {}

    private record TaskRef(UUID tenantId, UUID taskId) {}
    private record TaskHead(
            UUID taskId, UUID patientId, UUID encounterId, UUID facilityId, UUID sourceId,
            String state, UUID assignedUserId, UUID claimedBy,
            OffsetDateTime dueAt, OffsetDateTime escalationAt, long rowVersion) {}
    private record ExpiredDelegation(UUID delegatedBy, UUID delegatedTo, OffsetDateTime validUntil) {}
    private enum SweepOutcome { NONE, EXPIRED, AUTO_ESCALATED, DELEGATION_EXPIRED }
}
