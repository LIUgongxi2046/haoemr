package org.haoemr.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ActionApprovalDecideRequestWire;
import org.haoemr.contracts.ActionApprovalProposeRequestWire;
import org.haoemr.contracts.ActionApprovalWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class ActionApprovalApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";
    private static final String COLLABORATOR = "018f0000-0000-7000-8000-00000000aa06";
    private static final String COLLABORATOR_ROLE = "018f0000-0000-7000-8000-00000000aa07";

    @Autowired
    private ActionApprovalService approvals;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity proposer() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private ClinicalIdentity approver() {
        return new ClinicalIdentity(tenant, UUID.fromString(COLLABORATOR), List.of(UUID.fromString(COLLABORATOR_ROLE)));
    }

    private Context seedContext() {
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成审批患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1987, 1, 1)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-APPROVAL', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private ActionApprovalWire propose(Context context) {
        return approvals.propose(proposer(), "ap-" + UUID.randomUUID(),
                new ActionApprovalProposeRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), ActionApprovalProposeRequestWire.ActionTypeValue.ORDER_MEDICATION,
                        "AI 建议开具阿莫西林胶囊 500mg 口服", Instant.now()));
    }

    private ActionApprovalWire decide(
            ClinicalIdentity actor, Context context, ActionApprovalWire approval, String decision) {
        return approvals.decide(actor, "ap-d-" + UUID.randomUUID(), approval.actionApprovalId(),
                new ActionApprovalDecideRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), approval.rowVersion(),
                        ActionApprovalDecideRequestWire.DecisionValue.valueOf(decision)));
    }

    @Test
    void givenProposal_whenApprovingByDifferentUser_thenLifecycleRecorded() {
        Context context = seedContext();
        ActionApprovalWire proposed = propose(context);
        assertThat(proposed.status()).isEqualTo(ActionApprovalWire.StatusValue.PROPOSED);
        assertThat(proposed.proposedBy()).isEqualTo(UUID.fromString(USER));

        ActionApprovalWire approved = decide(approver(), context, proposed, "APPROVE");
        assertThat(approved.status()).isEqualTo(ActionApprovalWire.StatusValue.APPROVED);
        assertThat(approved.decidedBy()).isEqualTo(UUID.fromString(COLLABORATOR));
        assertThat(approved.decidedAt()).isNotNull();

        List<ActionApprovalWire> listed = approvals.listApprovals(proposer(), context.patientId());
        assertThat(listed).extracting(ActionApprovalWire::actionApprovalId).contains(proposed.actionApprovalId());
    }

    @Test
    void givenSameUser_whenDeciding_thenRejected() {
        Context context = seedContext();
        ActionApprovalWire proposed = propose(context);
        assertThatThrownBy(() -> decide(proposer(), context, proposed, "APPROVE"))
                .isInstanceOf(ActionApprovalException.class)
                .satisfies(e -> assertThat(((ActionApprovalException) e).code())
                        .isEqualTo("ACTION_SELF_APPROVAL_FORBIDDEN"));
    }

    @Test
    void givenDecidedProposal_whenDecidingAgain_thenRejected() {
        Context context = seedContext();
        ActionApprovalWire proposed = propose(context);
        ActionApprovalWire approved = decide(approver(), context, proposed, "APPROVE");
        assertThatThrownBy(() -> decide(approver(), context, approved, "REJECT"))
                .isInstanceOf(ActionApprovalException.class)
                .satisfies(e -> assertThat(((ActionApprovalException) e).code())
                        .isEqualTo("ACTION_APPROVAL_STATE_INVALID"));
    }

    @Test
    void givenProposalIdentity_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        ActionApprovalWire proposed = propose(context);
        assertThatThrownBy(() -> jdbc.sql("""
                update action_approval set proposed_action_summary = '篡改'
                where tenant_id = cast(:tenant as uuid) and action_approval_id = :approval
                """).param("tenant", TENANT).param("approval", proposed.actionApprovalId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
