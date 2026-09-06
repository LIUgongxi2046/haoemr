package org.haoemr.outpatient;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.OutpatientFollowupCancelRequestWire;
import org.junit.jupiter.api.Test;
import org.haoemr.contracts.OutpatientFollowupCompleteRequestWire;
import org.haoemr.contracts.OutpatientFollowupCreateRequestWire;
import org.haoemr.contracts.OutpatientFollowupWire;
import org.haoemr.contracts.OutpatientFollowupUpdateRequestWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class OutpatientFollowupApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private OutpatientFollowupService followups;

    private final UUID tenant = UUID.fromString(TENANT);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    @Test
    void givenPatient_whenCreatingAndCompleting_thenLifecycleRecorded() {
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        OutpatientFollowupWire created = followups.create(identity(), "fup-" + UUID.randomUUID(),
                new OutpatientFollowupCreateRequestWire(patientId, encounterId,
                        OutpatientFollowupCreateRequestWire.FollowupTypeValue.FOLLOWUP, "复查血压", null));
        assertThat(created.status()).isEqualTo(OutpatientFollowupWire.StatusValue.PENDING);

        OutpatientFollowupWire completed = followups.complete(identity(), created.followupId(),
                new OutpatientFollowupCompleteRequestWire("血压 130/80，继续监测", created.rowVersion()));
        assertThat(completed.status()).isEqualTo(OutpatientFollowupWire.StatusValue.COMPLETED);
        assertThat(completed.outcome()).isEqualTo("血压 130/80，继续监测");

        List<OutpatientFollowupWire> listed = followups.list(identity(), patientId);
        assertThat(listed).extracting(OutpatientFollowupWire::followupId).contains(created.followupId());
    }

    @Test
    void givenPendingFollowup_whenEditingAndCancelling_thenPendingFlowStopsWithAuditVersion() {
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        OutpatientFollowupWire created = followups.create(identity(), "fup-" + UUID.randomUUID(),
                new OutpatientFollowupCreateRequestWire(patientId, encounterId,
                        OutpatientFollowupCreateRequestWire.FollowupTypeValue.REVISIT, "一周后复诊", null));

        OutpatientFollowupWire edited = followups.update(identity(), patientId, encounterId,
                "fup-u-" + UUID.randomUUID(), created.followupId(),
                new OutpatientFollowupUpdateRequestWire(
                        OutpatientFollowupUpdateRequestWire.FollowupTypeValue.FOLLOWUP,
                        "三日内电话随访血压", null, created.rowVersion()));
        assertThat(edited.content()).isEqualTo("三日内电话随访血压");
        assertThat(edited.rowVersion()).isEqualTo(created.rowVersion() + 1);

        OutpatientFollowupWire cancelled = followups.cancel(identity(), patientId, encounterId,
                "fup-c-" + UUID.randomUUID(), edited.followupId(),
                new OutpatientFollowupCancelRequestWire("计划录入错误", edited.rowVersion()));
        assertThat(cancelled.status()).isEqualTo(OutpatientFollowupWire.StatusValue.CANCELLED);
        assertThat(cancelled.outcome()).contains("计划录入错误");
    }
}
