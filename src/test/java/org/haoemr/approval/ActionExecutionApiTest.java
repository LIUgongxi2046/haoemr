package org.haoemr.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ActionExecutionCreateRequestWire;
import org.haoemr.contracts.ActionExecutionTransitionRequestWire;
import org.haoemr.contracts.ActionExecutionWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class ActionExecutionApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";
    private static final String COLLABORATOR = "018f0000-0000-7000-8000-00000000aa06";

    @Autowired
    private ActionExecutionService executions;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private Context seedContext(String approvalStatus) {
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成动作执行患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1980, 2, 2)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-EXEC', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        jdbc.sql("""
                insert into action_approval(
                  tenant_id, action_approval_id, patient_id, encounter_id, facility_id,
                  action_type, proposed_action_summary, proposed_by, proposed_at, status, decided_by, decided_at)
                values (cast(:tenant as uuid), :approval, :patient, :encounter, cast(:facility as uuid),
                  'CREATE_DOCUMENT', '拟创建文书', cast(:proposer as uuid), now() - interval '1 minute',
                  :status, cast(:decided_by as uuid), cast(:decided_at as timestamptz))
                """).param("tenant", TENANT).param("approval", approvalId).param("patient", patientId)
                .param("encounter", encounterId).param("facility", FACILITY).param("proposer", USER)
                .param("status", approvalStatus)
                .param("decided_by", "APPROVED".equals(approvalStatus) ? UUID.fromString(COLLABORATOR) : null)
                .param("decided_at", "APPROVED".equals(approvalStatus)
                        ? Instant.now().atOffset(ZoneOffset.UTC) : null).update();
        return new Context(patientId, approvalId);
    }

    private ActionExecutionWire create(Context context) {
        return executions.create(identity(), "exec-" + UUID.randomUUID(),
                new ActionExecutionCreateRequestWire(organization, facility, context.patientId(),
                        context.approvalId()));
    }

    private ActionExecutionWire succeed(Context context, UUID executionId, long expectedRowVersion, String note) {
        return executions.succeed(identity(), "succeed-" + UUID.randomUUID(), executionId,
                new ActionExecutionTransitionRequestWire(organization, facility, context.patientId(),
                        expectedRowVersion, note));
    }

    private ActionExecutionWire fail(Context context, UUID executionId, long expectedRowVersion, String note) {
        return executions.fail(identity(), "fail-" + UUID.randomUUID(), executionId,
                new ActionExecutionTransitionRequestWire(organization, facility, context.patientId(),
                        expectedRowVersion, note));
    }

    @Test
    void givenApprovedApproval_whenCreatingExecution_thenPending() {
        Context context = seedContext("APPROVED");
        ActionExecutionWire created = create(context);
        assertThat(created.executionStatus()).isEqualTo(ActionExecutionWire.ExecutionStatusValue.PENDING);
        assertThat(created.executedBy()).isNull();
    }

    @Test
    void givenPendingExecution_whenSucceeding_thenSucceeded() {
        Context context = seedContext("APPROVED");
        ActionExecutionWire created = create(context);
        ActionExecutionWire succeeded = succeed(context, created.executionId(), created.rowVersion(), "已创建文书");
        assertThat(succeeded.executionStatus()).isEqualTo(ActionExecutionWire.ExecutionStatusValue.SUCCEEDED);
        assertThat(succeeded.executedBy()).isEqualTo(UUID.fromString(USER));
    }

    @Test
    void givenPendingExecution_whenFailing_thenFailed() {
        Context context = seedContext("APPROVED");
        ActionExecutionWire created = create(context);
        ActionExecutionWire failed = fail(context, created.executionId(), created.rowVersion(), "下游系统不可用");
        assertThat(failed.executionStatus()).isEqualTo(ActionExecutionWire.ExecutionStatusValue.FAILED);
        assertThat(failed.resultNote()).isEqualTo("下游系统不可用");
    }

    @Test
    void givenPendingExecution_whenFailingWithoutReason_thenRejected() {
        Context context = seedContext("APPROVED");
        ActionExecutionWire created = create(context);
        assertThatThrownBy(() -> fail(context, created.executionId(), created.rowVersion(), null))
                .isInstanceOf(ActionExecutionException.class)
                .satisfies(e -> assertThat(((ActionExecutionException) e).code())
                        .isEqualTo("ACTION_EXECUTION_FAILURE_REASON_REQUIRED"));
    }

    @Test
    void givenProposedApproval_whenCreatingExecution_thenRejected() {
        Context context = seedContext("PROPOSED");
        assertThatThrownBy(() -> create(context))
                .isInstanceOf(ActionExecutionException.class)
                .satisfies(e -> assertThat(((ActionExecutionException) e).code())
                        .isEqualTo("ACTION_NOT_APPROVED"));
    }

    @Test
    void givenExecution_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext("APPROVED");
        ActionExecutionWire created = create(context);
        assertThatThrownBy(() -> jdbc.sql("""
                update action_execution set action_approval_id = cast(:other as uuid)
                where tenant_id = cast(:tenant as uuid) and execution_id = :execution
                """).param("tenant", TENANT).param("execution", created.executionId())
                .param("other", UUID.randomUUID()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID approvalId) {}
}
