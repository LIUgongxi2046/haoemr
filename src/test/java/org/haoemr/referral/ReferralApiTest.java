package org.haoemr.referral;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ReferralCreateRequestWire;
import org.haoemr.contracts.ReferralTransitionRequestWire;
import org.haoemr.contracts.ReferralUpdateRequestWire;
import org.haoemr.contracts.ReferralWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class ReferralApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";
    private static final String RECEIVER_USER = "018f0000-0000-7000-8000-00000000aa06";
    private static final String RECEIVER_ROLE = "018f0000-0000-7000-8000-00000000aa07";

    @Autowired
    private ReferralService referrals;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private ClinicalIdentity receiverIdentity() {
        return new ClinicalIdentity(
                tenant, UUID.fromString(RECEIVER_USER), List.of(UUID.fromString(RECEIVER_ROLE)));
    }

    private Context seedContext() {
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成转诊患者', 'M', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1968, 7, 7)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-REFERRAL', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private ReferralWire create(
            Context context, String type, String department, String targetOrganization) {
        return referrals.create(identity(), "ref-" + UUID.randomUUID(),
                new ReferralCreateRequestWire(organization, facility, context.patientId(), context.encounterId(),
                        ReferralCreateRequestWire.ReferralTypeValue.valueOf(type), department, targetOrganization,
                        "进一步明确诊断并制定治疗方案", "患者反复胸闷三月，常规检查未见明确异常，建议专科会诊"));
    }

    private ReferralWire transition(Context context, ReferralWire referral, String transition) {
        return transitionAs(identity(), context, referral, transition);
    }

    private ReferralWire transitionAs(
            ClinicalIdentity actor, Context context, ReferralWire referral, String transition) {
        return referrals.transition(actor, "ref-t-" + UUID.randomUUID(), referral.referralId(),
                new ReferralTransitionRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), referral.rowVersion(),
                        ReferralTransitionRequestWire.TransitionValue.valueOf(transition)));
    }

    @Test
    void givenInternalReferral_whenSendingAndAccepting_thenLifecycleRecorded() {
        Context context = seedContext();
        ReferralWire draft = create(context, "INTERNAL", "心血管内科", null);
        assertThat(draft.status()).isEqualTo(ReferralWire.StatusValue.DRAFT);
        assertThat(draft.targetDepartment()).isEqualTo("心血管内科");

        ReferralWire sent = transition(context, draft, "SEND");
        assertThat(sent.status()).isEqualTo(ReferralWire.StatusValue.SENT);
        assertThat(sent.sentAt()).isNotNull();
        assertThat(jdbc.sql("""
                select state || '|' || business_state from clinical_task
                where tenant_id=cast(:tenant as uuid) and source_type='CONSULTATION'
                  and source_id=:referral and task_type='OUTPATIENT_REFERRAL_RESPONSE'
                """).param("tenant", TENANT).param("referral", draft.referralId())
                .query(String.class).single()).isEqualTo("PENDING|SENT");

        ReferralWire accepted = transitionAs(receiverIdentity(), context, sent, "ACCEPT");
        assertThat(accepted.status()).isEqualTo(ReferralWire.StatusValue.ACCEPTED);
        assertThat(accepted.resolvedAt()).isNotNull();
        assertThat(jdbc.sql("""
                select state from clinical_task
                where tenant_id=cast(:tenant as uuid) and source_type='CONSULTATION'
                  and source_id=:referral and task_type='OUTPATIENT_REFERRAL_RESPONSE'
                """).param("tenant", TENANT).param("referral", draft.referralId())
                .query(String.class).single()).isEqualTo("COMPLETED");

        List<ReferralWire> listed = referrals.listReferrals(identity(), context.patientId());
        assertThat(listed).extracting(ReferralWire::referralId).contains(draft.referralId());
    }

    @Test
    void givenExternalReferral_whenSendingAndRejecting_thenRejected() {
        Context context = seedContext();
        ReferralWire draft = create(context, "EXTERNAL", null, "上级综合医院胸外科");
        ReferralWire sent = transition(context, draft, "SEND");
        ReferralWire rejected = transitionAs(receiverIdentity(), context, sent, "REJECT");
        assertThat(rejected.status()).isEqualTo(ReferralWire.StatusValue.REJECTED);
    }

    @Test
    void givenDraftReferral_whenEditingAndCancelling_thenDownstreamWorkIsNotCreated() {
        Context context = seedContext();
        ReferralWire draft = create(context, "INTERNAL", "心血管内科", null);
        ReferralWire edited = referrals.update(identity(), "ref-u-" + UUID.randomUUID(), draft.referralId(),
                new ReferralUpdateRequestWire(organization, facility, context.patientId(), context.encounterId(),
                        ReferralUpdateRequestWire.ReferralTypeValue.INTERNAL, "肾内科", null,
                        "肾功能异常需协同评估", "肌酐升高，已完成复查，申请肾内科评估后续方案", draft.rowVersion()));
        assertThat(edited.targetDepartment()).isEqualTo("肾内科");
        assertThat(edited.rowVersion()).isEqualTo(draft.rowVersion() + 1);

        ReferralWire cancelled = transition(context, edited, "CANCEL");
        assertThat(cancelled.status()).isEqualTo(ReferralWire.StatusValue.CANCELLED);
        assertThat(cancelled.sentAt()).isNull();
        assertThat(cancelled.resolvedAt()).isNotNull();
    }

    @Test
    void givenInternalWithoutTargetDepartment_whenCreating_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> create(context, "INTERNAL", null, null))
                .isInstanceOf(ReferralException.class)
                .satisfies(e -> assertThat(((ReferralException) e).code()).isEqualTo("REFERRAL_REQUEST_INVALID"));
    }

    @Test
    void givenInvalidTransition_whenAcceptingDraft_thenRejected() {
        Context context = seedContext();
        ReferralWire draft = create(context, "INTERNAL", "心血管内科", null);
        assertThatThrownBy(() -> transition(context, draft, "ACCEPT"))
                .isInstanceOf(ReferralException.class)
                .satisfies(e -> assertThat(((ReferralException) e).code()).isEqualTo("REFERRAL_STATE_INVALID"));
    }

    @Test
    void givenSentReferral_whenRequesterAttemptsSelfAcceptance_thenRejected() {
        Context context = seedContext();
        ReferralWire draft = create(context, "INTERNAL", "心血管内科", null);
        ReferralWire sent = transition(context, draft, "SEND");

        assertThatThrownBy(() -> transition(context, sent, "ACCEPT"))
                .isInstanceOf(ReferralException.class)
                .satisfies(e -> assertThat(((ReferralException) e).code())
                        .isEqualTo("REFERRAL_SELF_RESOLUTION_FORBIDDEN"));
    }

    @Test
    void givenReferralIdentity_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        ReferralWire draft = create(context, "EXTERNAL", null, "上级综合医院胸外科");
        assertThatThrownBy(() -> jdbc.sql("""
                update referral set reason = '篡改'
                where tenant_id = cast(:tenant as uuid) and referral_id = :referral
                """).param("tenant", TENANT).param("referral", draft.referralId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
