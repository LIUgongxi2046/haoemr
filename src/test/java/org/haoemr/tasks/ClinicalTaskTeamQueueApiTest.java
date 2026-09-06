package org.haoemr.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ClinicalTaskTeamQueueEnqueueRequestWire;
import org.haoemr.contracts.ClinicalTaskTeamQueueTransitionRequestWire;
import org.haoemr.contracts.ClinicalTaskTeamQueueWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class ClinicalTaskTeamQueueApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";
    private static final String PEER_USER = "018f0000-0000-7000-8000-00000000aa16";
    private static final String PEER_ROLE = "018f0000-0000-7000-8000-00000000aa17";

    @Autowired
    private ClinicalTaskTeamQueueService queues;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private ClinicalIdentity peerIdentity() {
        return new ClinicalIdentity(tenant, UUID.fromString(PEER_USER), List.of(UUID.fromString(PEER_ROLE)));
    }

    private Context seedContext(String taskState) {
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成团队队列患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1978, 9, 9)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-TEAMQ', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        jdbc.sql("""
                insert into clinical_task(
                  tenant_id, task_id, patient_id, encounter_id, facility_id,
                  source_type, source_id, task_type, title, risk_level, state, business_state, source_route)
                values (cast(:tenant as uuid), :task, :patient, :encounter, cast(:facility as uuid),
                  'DOCUMENT', :source, 'SIGN_DOCUMENT', '待签文书', 'ROUTINE', :state, 'OPEN', '#/record')
                """).param("tenant", TENANT).param("task", taskId).param("patient", patientId)
                .param("encounter", encounterId).param("facility", FACILITY)
                .param("source", UUID.randomUUID()).param("state", taskState).update();
        jdbc.sql("""
                insert into clinical_department(
                  tenant_id, facility_id, department_id, department_code, display_name, status)
                values (cast(:tenant as uuid), cast(:facility as uuid), :department, :code, '合成科室', 'ACTIVE')
                """).param("tenant", TENANT).param("facility", FACILITY).param("department", departmentId)
                .param("code", "DEP-" + UUID.randomUUID().toString().substring(0, 8)).update();
        jdbc.sql("""
                update workforce_assignment
                set facility_id = cast(:facility as uuid), department_id = :department, ward_id = null,
                    status = 'ACTIVE', valid_from = least(valid_from, now()), valid_until = null
                where tenant_id = cast(:tenant as uuid)
                  and source_role_assignment_id = cast(:role as uuid)
                """).param("tenant", TENANT).param("facility", FACILITY)
                .param("department", departmentId).param("role", ROLE).update();
        return new Context(taskId, departmentId);
    }

    private void seedPeer(UUID departmentId) {
        jdbc.sql("""
                insert into app_user(tenant_id, user_id, external_subject, display_name, status)
                values (cast(:tenant as uuid), cast(:user as uuid), :subject, '合成队列同科室人员', 'ACTIVE')
                on conflict (tenant_id, user_id) do update set status = 'ACTIVE'
                """).param("tenant", TENANT).param("user", PEER_USER)
                .param("subject", "synthetic-team-peer-" + PEER_USER).update();
        jdbc.sql("""
                insert into role_assignment(tenant_id, role_assignment_id, user_id, organization_id,
                  facility_id, role_code, valid_from, status)
                values (cast(:tenant as uuid), cast(:role as uuid), cast(:user as uuid),
                  cast(:organization as uuid), cast(:facility as uuid), 'CLINICIAN', now(), 'ACTIVE')
                on conflict (tenant_id, role_assignment_id) do update
                  set status = 'ACTIVE', valid_from = least(role_assignment.valid_from, now()), valid_until = null
                """).param("tenant", TENANT).param("role", PEER_ROLE).param("user", PEER_USER)
                .param("organization", ORGANIZATION).param("facility", FACILITY).update();
        jdbc.sql("""
                update workforce_assignment
                set department_id = :department, status = 'ACTIVE', valid_from = least(valid_from, now()),
                    valid_until = null
                where tenant_id = cast(:tenant as uuid)
                  and source_role_assignment_id = cast(:role as uuid)
                """).param("tenant", TENANT).param("role", PEER_ROLE)
                .param("department", departmentId).update();
    }

    private ClinicalTaskTeamQueueWire enqueue(Context context) {
        return queues.enqueue(identity(), "enq-" + UUID.randomUUID(),
                new ClinicalTaskTeamQueueEnqueueRequestWire(organization, facility, context.departmentId(),
                        context.taskId(), Instant.now()));
    }

    private ClinicalTaskTeamQueueWire claim(UUID queueId, long expectedRowVersion) {
        return queues.claim(identity(), "claim-" + UUID.randomUUID(), queueId,
                new ClinicalTaskTeamQueueTransitionRequestWire(organization, facility, expectedRowVersion));
    }

    private ClinicalTaskTeamQueueWire complete(UUID queueId, long expectedRowVersion) {
        return queues.complete(identity(), "complete-" + UUID.randomUUID(), queueId,
                new ClinicalTaskTeamQueueTransitionRequestWire(organization, facility, expectedRowVersion));
    }

    private ClinicalTaskTeamQueueWire withdraw(UUID queueId, long expectedRowVersion) {
        return queues.withdraw(identity(), "withdraw-" + UUID.randomUUID(), queueId,
                new ClinicalTaskTeamQueueTransitionRequestWire(organization, facility, expectedRowVersion));
    }

    @Test
    void givenTask_whenEnqueuing_thenEnqueued() {
        Context context = seedContext("PENDING");
        ClinicalTaskTeamQueueWire enqueued = enqueue(context);
        assertThat(enqueued.queueStatus()).isEqualTo(ClinicalTaskTeamQueueWire.QueueStatusValue.ENQUEUED);
        assertThat(enqueued.enqueuedBy()).isEqualTo(UUID.fromString(USER));

        List<ClinicalTaskTeamQueueWire> listed = queues.list(
                identity(), organization, facility, context.departmentId());
        assertThat(listed).extracting(ClinicalTaskTeamQueueWire::queueId).contains(enqueued.queueId());
    }

    @Test
    void givenEnqueued_whenClaiming_thenClaimed() {
        Context context = seedContext("PENDING");
        ClinicalTaskTeamQueueWire enqueued = enqueue(context);
        ClinicalTaskTeamQueueWire claimed = claim(enqueued.queueId(), enqueued.rowVersion());
        assertThat(claimed.queueStatus()).isEqualTo(ClinicalTaskTeamQueueWire.QueueStatusValue.CLAIMED);
        assertThat(claimed.claimedBy()).isEqualTo(UUID.fromString(USER));
    }

    @Test
    void givenDifferentDepartmentAssignment_whenClaiming_thenRejected() {
        Context context = seedContext("PENDING");
        ClinicalTaskTeamQueueWire enqueued = enqueue(context);
        jdbc.sql("""
                update workforce_assignment set department_id = null
                where tenant_id = cast(:tenant as uuid)
                  and source_role_assignment_id = cast(:role as uuid)
                """).param("tenant", TENANT).param("role", ROLE).update();

        assertThatThrownBy(() -> claim(enqueued.queueId(), enqueued.rowVersion()))
                .isInstanceOf(ClinicalTaskTeamQueueException.class)
                .satisfies(e -> assertThat(((ClinicalTaskTeamQueueException) e).code())
                        .isEqualTo("TEAM_MEMBERSHIP_REQUIRED"));
    }

    @Test
    void givenClaimed_whenCompleting_thenCompleted() {
        Context context = seedContext("PENDING");
        ClinicalTaskTeamQueueWire enqueued = enqueue(context);
        ClinicalTaskTeamQueueWire claimed = claim(enqueued.queueId(), enqueued.rowVersion());
        ClinicalTaskTeamQueueWire completed = complete(claimed.queueId(), claimed.rowVersion());
        assertThat(completed.queueStatus()).isEqualTo(ClinicalTaskTeamQueueWire.QueueStatusValue.COMPLETED);
    }

    @Test
    void givenClaimedByAnotherClinician_whenCompleting_thenRejected() {
        Context context = seedContext("PENDING");
        seedPeer(context.departmentId());
        ClinicalTaskTeamQueueWire claimed = claim(enqueue(context).queueId(), 1L);

        assertThatThrownBy(() -> queues.complete(peerIdentity(), "peer-complete-" + UUID.randomUUID(),
                claimed.queueId(), new ClinicalTaskTeamQueueTransitionRequestWire(
                        organization, facility, claimed.rowVersion())))
                .isInstanceOf(ClinicalTaskTeamQueueException.class)
                .satisfies(e -> assertThat(((ClinicalTaskTeamQueueException) e).code())
                        .isEqualTo("TEAM_QUEUE_CLAIMANT_REQUIRED"));
    }

    @Test
    void givenEnqueued_whenWithdrawing_thenWithdrawn() {
        Context context = seedContext("PENDING");
        ClinicalTaskTeamQueueWire enqueued = enqueue(context);
        ClinicalTaskTeamQueueWire withdrawn = withdraw(enqueued.queueId(), enqueued.rowVersion());
        assertThat(withdrawn.queueStatus()).isEqualTo(ClinicalTaskTeamQueueWire.QueueStatusValue.WITHDRAWN);
        assertThat(withdrawn.claimedBy()).isNull();
    }

    @Test
    void givenEnqueuedByAnotherClinician_whenWithdrawing_thenRejected() {
        Context context = seedContext("PENDING");
        seedPeer(context.departmentId());
        ClinicalTaskTeamQueueWire enqueued = enqueue(context);

        assertThatThrownBy(() -> queues.withdraw(peerIdentity(), "peer-withdraw-" + UUID.randomUUID(),
                enqueued.queueId(), new ClinicalTaskTeamQueueTransitionRequestWire(
                        organization, facility, enqueued.rowVersion())))
                .isInstanceOf(ClinicalTaskTeamQueueException.class)
                .satisfies(e -> assertThat(((ClinicalTaskTeamQueueException) e).code())
                        .isEqualTo("TEAM_QUEUE_ENQUEUER_REQUIRED"));
    }

    @Test
    void givenTerminalTask_whenEnqueuing_thenRejected() {
        Context context = seedContext("COMPLETED");
        assertThatThrownBy(() -> enqueue(context))
                .isInstanceOf(ClinicalTaskTeamQueueException.class)
                .satisfies(e -> assertThat(((ClinicalTaskTeamQueueException) e).code())
                        .isEqualTo("TASK_TERMINAL"));
    }

    @Test
    void givenStaleVersion_whenClaiming_thenRejected() {
        Context context = seedContext("PENDING");
        ClinicalTaskTeamQueueWire enqueued = enqueue(context);
        assertThatThrownBy(() -> claim(enqueued.queueId(), 99L))
                .isInstanceOf(ClinicalTaskTeamQueueException.class)
                .satisfies(e -> assertThat(((ClinicalTaskTeamQueueException) e).code())
                        .isEqualTo("CLINICAL_TASK_TEAM_QUEUE_VERSION_CONFLICT"));
    }

    @Test
    void givenQueue_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext("PENDING");
        ClinicalTaskTeamQueueWire enqueued = enqueue(context);
        assertThatThrownBy(() -> jdbc.sql("""
                update clinical_task_team_queue set clinical_task_id = cast(:other as uuid)
                where tenant_id = cast(:tenant as uuid) and queue_id = :queue
                """).param("tenant", TENANT).param("queue", enqueued.queueId())
                .param("other", UUID.randomUUID()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID taskId, UUID departmentId) {}
}
