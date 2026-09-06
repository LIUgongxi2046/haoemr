package org.haoemr.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class ClinicalTaskLifecycleServiceTest {
    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String OWNER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String DELEGATE = "d1281807-abb4-3065-90e2-861ec72869fe";

    @Autowired
    private ClinicalTaskLifecycleService lifecycle;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void overdueTaskExpiresAsASystemActionWithEventAuditAndOutboxEvidence() {
        Context context = seedContext();
        UUID taskId = seedTask(context, "PENDING", null, null, "now() - interval '1 minute'", null);

        ClinicalTaskLifecycleService.TaskLifecycleSweepResult result = lifecycle.sweepDueTransitions();

        assertThat(result.expiredCount()).isGreaterThanOrEqualTo(1);
        assertThat(taskState(taskId)).isEqualTo("EXPIRED");
        assertThat(jdbc.sql("""
                select actor_user_id from clinical_task_event
                where tenant_id = cast(:tenant as uuid) and task_id = :task and event_type = 'EXPIRED'
                """).param("tenant", TENANT).param("task", taskId).query(UUID.class).optional()).isEmpty();
        assertThat(jdbc.sql("""
                select count(*) from audit_event
                where tenant_id = cast(:tenant as uuid) and resource_type = 'CLINICAL_TASK'
                  and resource_id = :task and action_code = 'CLINICAL_TASK_EXPIRED' and actor_user_id is null
                """).param("tenant", TENANT).param("task", taskId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                select count(*) from outbox_event
                where tenant_id = cast(:tenant as uuid) and aggregate_type = 'CLINICAL_TASK'
                  and aggregate_id = :task and event_type = 'ClinicalTaskExpired'
                """).param("tenant", TENANT).param("task", taskId).query(Long.class).single()).isEqualTo(1);
        UUID reminderId = jdbc.sql("""
                select reminder_id from clinical_reminder
                where tenant_id = cast(:tenant as uuid) and source_task_id = :task
                  and reminder_type = 'OVERDUE_TASK' and severity = 'WARNING' and status = 'PENDING'
                """).param("tenant", TENANT).param("task", taskId).query(UUID.class).single();
        assertThat(jdbc.sql("""
                select count(*) from audit_event
                where tenant_id = cast(:tenant as uuid) and resource_type = 'CLINICAL_REMINDER'
                  and resource_id = :reminder and action_code = 'CLINICAL_REMINDER_CREATED_BY_TASK_LIFECYCLE'
                  and actor_user_id is null
                """).param("tenant", TENANT).param("reminder", reminderId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                select count(*) from outbox_event
                where tenant_id = cast(:tenant as uuid) and aggregate_type = 'CLINICAL_REMINDER'
                  and aggregate_id = :reminder and event_type = 'ClinicalReminderCreated'
                """).param("tenant", TENANT).param("reminder", reminderId).query(Long.class).single()).isEqualTo(1);
        lifecycle.sweepDueTransitions();
        assertThat(eventCount(taskId, "EXPIRED")).isEqualTo(1);
        assertThat(jdbc.sql("""
                select count(*) from clinical_reminder
                where tenant_id = cast(:tenant as uuid) and source_task_id = :task
                """).param("tenant", TENANT).param("task", taskId).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void escalationDeadlineMovesOpenTaskToEscalatedOnceWithoutChangingItsClinicalBusinessState() {
        Context context = seedContext();
        UUID taskId = seedTask(context, "PENDING", null, null,
                "now() + interval '1 hour'", "now() - interval '1 minute'");

        ClinicalTaskLifecycleService.TaskLifecycleSweepResult result = lifecycle.sweepDueTransitions();

        assertThat(result.autoEscalatedCount()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("""
                select state || '|' || business_state from clinical_task
                where tenant_id = cast(:tenant as uuid) and task_id = :task
                """).param("tenant", TENANT).param("task", taskId).query(String.class).single())
                .isEqualTo("ESCALATED|OPEN");
        assertThat(eventCount(taskId, "AUTO_ESCALATED")).isEqualTo(1);
        lifecycle.sweepDueTransitions();
        assertThat(eventCount(taskId, "AUTO_ESCALATED")).isEqualTo(1);
    }

    @Test
    void expiredUnclaimedDelegationReturnsResponsibilityAndExpiredClaimedDelegationEscalatesIt() {
        Context context = seedContext();
        UUID unclaimed = seedTask(context, "ASSIGNED", UUID.fromString(DELEGATE), null,
                "now() + interval '1 hour'", null);
        UUID claimed = seedTask(context, "CLAIMED", UUID.fromString(DELEGATE), UUID.fromString(DELEGATE),
                "now() + interval '1 hour'", null);
        seedExpiredDelegation(unclaimed);
        seedExpiredDelegation(claimed);

        ClinicalTaskLifecycleService.TaskLifecycleSweepResult result = lifecycle.sweepDueTransitions();

        assertThat(result.delegationExpiredCount()).isGreaterThanOrEqualTo(2);
        assertThat(taskResponsibility(unclaimed)).isEqualTo("CLAIMED|" + OWNER + "|" + OWNER);
        assertThat(taskResponsibility(claimed)).isEqualTo("ESCALATED|" + OWNER + "|" + DELEGATE);
        assertThat(eventCount(unclaimed, "DELEGATION_EXPIRED")).isEqualTo(1);
        assertThat(eventCount(claimed, "DELEGATION_EXPIRED")).isEqualTo(1);
        lifecycle.sweepDueTransitions();
        assertThat(eventCount(unclaimed, "DELEGATION_EXPIRED")).isEqualTo(1);
        assertThat(eventCount(claimed, "DELEGATION_EXPIRED")).isEqualTo(1);
    }

    private Context seedContext() {
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '任务生命周期患者', 'U', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1980, 1, 1)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-LIFECYCLE', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private UUID seedTask(
            Context context, String state, UUID assignedUserId, UUID claimedBy, String dueAt, String escalationAt) {
        UUID taskId = UUID.randomUUID();
        jdbc.sql("""
                insert into clinical_task(
                  tenant_id, task_id, patient_id, encounter_id, facility_id,
                  source_type, source_id, task_type, title, risk_level, state, business_state,
                  assigned_user_id, claimed_by, due_at, escalation_at, source_route)
                values (cast(:tenant as uuid), :task, :patient, :encounter, cast(:facility as uuid),
                  'DOCUMENT', :source, 'SIGN_DOCUMENT', '自动生命周期验证', 'HIGH', :state, 'OPEN',
                  cast(:assigned as uuid), cast(:claimed as uuid), cast(:due_at as timestamptz),
                  cast(:escalation_at as timestamptz), '#/record')
                """.replace(":due_at", dueAt == null ? "null" : dueAt)
                .replace(":escalation_at", escalationAt == null ? "null" : escalationAt))
                .param("tenant", TENANT).param("task", taskId).param("patient", context.patientId())
                .param("encounter", context.encounterId()).param("facility", FACILITY).param("source", UUID.randomUUID())
                .param("state", state).param("assigned", assignedUserId).param("claimed", claimedBy).update();
        jdbc.sql("""
                insert into clinical_task_event(
                  tenant_id, task_event_id, task_id, event_type, previous_state, resulting_state, actor_user_id)
                values (cast(:tenant as uuid), gen_random_uuid(), :task, 'CREATED', null, :state,
                  cast(:actor as uuid))
                """).param("tenant", TENANT).param("task", taskId).param("state", state)
                .param("actor", OWNER).update();
        return taskId;
    }

    private void seedExpiredDelegation(UUID taskId) {
        jdbc.sql("""
                insert into clinical_task_delegation(
                  tenant_id, delegation_id, task_id, delegated_by, delegated_to, reason, created_at, valid_until)
                values (cast(:tenant as uuid), gen_random_uuid(), :task, cast(:owner as uuid),
                  cast(:delegate as uuid), '临时委派，等待自动回收', now() - interval '2 minutes',
                  now() - interval '1 minute')
                """).param("tenant", TENANT).param("task", taskId).param("owner", OWNER)
                .param("delegate", DELEGATE).update();
    }

    private String taskState(UUID taskId) {
        return jdbc.sql("select state from clinical_task where tenant_id = cast(:tenant as uuid) and task_id = :task")
                .param("tenant", TENANT).param("task", taskId).query(String.class).single();
    }

    private String taskResponsibility(UUID taskId) {
        return jdbc.sql("""
                select state || '|' || assigned_user_id || '|' || claimed_by from clinical_task
                where tenant_id = cast(:tenant as uuid) and task_id = :task
                """).param("tenant", TENANT).param("task", taskId).query(String.class).single();
    }

    private long eventCount(UUID taskId, String eventType) {
        return jdbc.sql("""
                select count(*) from clinical_task_event
                where tenant_id = cast(:tenant as uuid) and task_id = :task and event_type = :event_type
                """).param("tenant", TENANT).param("task", taskId).param("event_type", eventType)
                .query(Long.class).single();
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
