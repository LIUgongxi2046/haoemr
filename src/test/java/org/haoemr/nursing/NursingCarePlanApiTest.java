package org.haoemr.nursing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.NursingCarePlanCompleteRequestWire;
import org.haoemr.contracts.NursingCarePlanRequestWire;
import org.haoemr.contracts.NursingCarePlanWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class NursingCarePlanApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private NursingService nursing;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private Context seedContext() {
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成护理患者', 'U', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1968, 3, 2)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'INPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-CAREPLAN', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    @Test
    void givenActiveEncounter_whenCreatingAndCompletingCarePlan_thenLifecycleIsRecorded() {
        Context context = seedContext();
        NursingCarePlanWire created = nursing.createCarePlan(identity(), "plan-" + UUID.randomUUID(),
                new NursingCarePlanRequestWire(organization, facility, context.patientId(), context.encounterId(),
                        null, "有跌倒风险", "住院期间无跌倒事件", "每 2 小时巡视并协助如厕", null,
                        NursingCarePlanRequestWire.PriorityValue.HIGH));
        assertThat(created.status()).isEqualTo(NursingCarePlanWire.StatusValue.ACTIVE);
        assertThat(created.priority()).isEqualTo(NursingCarePlanWire.PriorityValue.HIGH);
        assertThat(created.createdBy()).isEqualTo(UUID.fromString(USER));

        List<NursingCarePlanWire> listed = nursing.listCarePlans(
                identity(), organization, facility, context.patientId(), context.encounterId());
        assertThat(listed).extracting(NursingCarePlanWire::carePlanId).contains(created.carePlanId());

        NursingCarePlanWire completed = nursing.completeCarePlan(identity(), "complete-" + UUID.randomUUID(),
                created.carePlanId(), new NursingCarePlanCompleteRequestWire(
                        organization, facility, context.patientId(), context.encounterId(),
                        created.rowVersion(), NursingCarePlanCompleteRequestWire.DispositionValue.COMPLETED,
                        "患者住院期间无跌倒事件，目标达成"));
        assertThat(completed.status()).isEqualTo(NursingCarePlanWire.StatusValue.COMPLETED);
        assertThat(completed.completedAt()).isNotNull();
        assertThat(completed.evaluation()).contains("目标达成");
    }

    @Test
    void givenCarePlanContent_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        NursingCarePlanWire created = nursing.createCarePlan(identity(), "plan-" + UUID.randomUUID(),
                new NursingCarePlanRequestWire(organization, facility, context.patientId(), context.encounterId(),
                        null, "压疮风险", "皮肤完整无破损", "定时翻身并评估皮肤", null,
                        NursingCarePlanRequestWire.PriorityValue.MEDIUM));
        assertThatThrownBy(() -> jdbc.sql("""
                update nursing_care_plan set nursing_problem = '篡改'
                where tenant_id = cast(:tenant as uuid) and care_plan_id = :plan
                """).param("tenant", TENANT).param("plan", created.carePlanId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void givenCompletedCarePlan_whenCompletedAgain_thenStateInvalid() {
        Context context = seedContext();
        NursingCarePlanWire created = nursing.createCarePlan(identity(), "plan-" + UUID.randomUUID(),
                new NursingCarePlanRequestWire(organization, facility, context.patientId(), context.encounterId(),
                        null, "营养失调风险", "维持体重稳定", "营养评估并记录摄入", null,
                        NursingCarePlanRequestWire.PriorityValue.LOW));
        nursing.completeCarePlan(identity(), "complete-" + UUID.randomUUID(), created.carePlanId(),
                new NursingCarePlanCompleteRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), created.rowVersion(),
                        NursingCarePlanCompleteRequestWire.DispositionValue.COMPLETED, "达成"));
        assertThatThrownBy(() -> nursing.completeCarePlan(identity(), "complete-" + UUID.randomUUID(),
                created.carePlanId(), new NursingCarePlanCompleteRequestWire(
                        organization, facility, context.patientId(), context.encounterId(), 2L,
                        NursingCarePlanCompleteRequestWire.DispositionValue.COMPLETED, "重复")))
                .isInstanceOf(NursingException.class)
                .satisfies(e -> assertThat(((NursingException) e).code())
                        .isEqualTo("NURSING_CARE_PLAN_STATE_INVALID"));
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
