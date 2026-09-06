package org.haoemr.nursing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.NursingDischargeClosureRequestWire;
import org.haoemr.contracts.NursingDischargeClosureWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class NursingDischargeClosureApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";
    private static final String COLLABORATOR = "018f0000-0000-7000-8000-00000000aa06";
    private static final String WARD = "018f0000-0000-7000-8000-00000000bb01";

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
                values (cast(:tenant as uuid), :patient, '合成出院患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1972, 2, 2)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, ended_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'INPATIENT', 'FINISHED', now(), now(), 'SYNTHETIC-DISCHARGE', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private void seedActiveCarePlan(Context context) {
        jdbc.sql("""
                insert into nursing_care_plan(
                  tenant_id, care_plan_id, patient_id, encounter_id, facility_id,
                  nursing_problem, goal, intervention, priority, status, created_by)
                values (cast(:tenant as uuid), gen_random_uuid(), :patient, :encounter, cast(:facility as uuid),
                  '压疮风险', '保持皮肤完整', '每两小时翻身一次', 'HIGH', 'ACTIVE', cast(:user as uuid))
                """).param("tenant", TENANT).param("patient", context.patientId())
                .param("encounter", context.encounterId()).param("facility", FACILITY).param("user", USER).update();
    }

    private void seedOpenMedicationTask(Context context) {
        UUID orderId = UUID.randomUUID();
        UUID orderItemId = UUID.randomUUID();
        jdbc.sql("""
                insert into clinical_order(
                  tenant_id, order_id, patient_id, encounter_id, facility_id,
                  order_scope, status, clinical_indication, author_user_id, signed_by, signed_at)
                values (cast(:tenant as uuid), :order, :patient, :encounter, cast(:facility as uuid),
                  'TEMPORARY', 'ACTIVE', '出院前给药', cast(:user as uuid), cast(:user as uuid), now())
                """).param("tenant", TENANT).param("order", orderId).param("patient", context.patientId())
                .param("encounter", context.encounterId()).param("facility", FACILITY).param("user", USER).update();
        jdbc.sql("""
                insert into clinical_order_item(
                  tenant_id, order_item_id, order_id, item_type, catalog_code, display_name,
                  requested_quantity, quantity_unit, item_state)
                values (cast(:tenant as uuid), :item, :order, 'MEDICATION', 'DRUG-001', '阿莫西林胶囊',
                  1, '粒', 'ACTIVE')
                """).param("tenant", TENANT).param("item", orderItemId).param("order", orderId).update();
        jdbc.sql("""
                insert into order_execution_task(
                  tenant_id, execution_task_id, order_id, order_item_id, patient_id, encounter_id,
                  task_state, requested_quantity, performed_quantity, quantity_unit)
                values (cast(:tenant as uuid), :task, :order, :item, :patient, :encounter,
                  'PENDING', 1, 0, '粒')
                """).param("tenant", TENANT).param("task", UUID.randomUUID()).param("order", orderId)
                .param("item", orderItemId).param("patient", context.patientId())
                .param("encounter", context.encounterId()).update();
    }

    private void seedDraftHandover(Context context) {
        UUID handoverId = UUID.randomUUID();
        jdbc.sql("""
                insert into shift_handover(
                  tenant_id, handover_id, ward_id, facility_id, shift_from, shift_to,
                  outgoing_user_id, incoming_user_id, handover_summary, status)
                values (cast(:tenant as uuid), :handover, cast(:ward as uuid), cast(:facility as uuid),
                  now() - interval '30 minutes', now(), cast(:user as uuid), cast(:collaborator as uuid),
                  '交接未完项', 'DRAFT')
                """).param("tenant", TENANT).param("handover", handoverId).param("ward", WARD)
                .param("facility", FACILITY).param("user", USER).param("collaborator", COLLABORATOR).update();
        jdbc.sql("""
                insert into shift_handover_patient(
                  tenant_id, shift_handover_patient_id, handover_id, patient_id, summary)
                values (cast(:tenant as uuid), gen_random_uuid(), :handover, :patient, '需持续观察')
                """).param("tenant", TENANT).param("handover", handoverId)
                .param("patient", context.patientId()).update();
    }

    @Test
    void givenNoOpenCarePlans_whenClosing_thenClosureRecorded() {
        Context context = seedContext();
        NursingDischargeClosureWire closure = nursing.closeNursingDischarge(identity(), "close-" + UUID.randomUUID(),
                new NursingDischargeClosureRequestWire(organization, facility, context.patientId(), context.encounterId()));
        assertThat(closure.closedBy()).isEqualTo(UUID.fromString(USER));
        assertThat(closure.closedAt()).isNotNull();

        List<NursingDischargeClosureWire> listed = nursing.listNursingDischargeClosures(identity(), context.patientId());
        assertThat(listed).extracting(NursingDischargeClosureWire::closureId).contains(closure.closureId());
    }

    @Test
    void givenOpenCarePlan_whenClosing_thenRejected() {
        Context context = seedContext();
        seedActiveCarePlan(context);
        assertThatThrownBy(() -> nursing.closeNursingDischarge(identity(), "close-" + UUID.randomUUID(),
                new NursingDischargeClosureRequestWire(organization, facility, context.patientId(), context.encounterId())))
                .isInstanceOf(NursingException.class)
                .satisfies(e -> assertThat(((NursingException) e).code()).isEqualTo("NURSING_CARE_PLANS_OPEN"));
    }

    @Test
    void givenOpenMedicationTask_whenClosing_thenRejected() {
        Context context = seedContext();
        seedOpenMedicationTask(context);
        assertThatThrownBy(() -> nursing.closeNursingDischarge(identity(), "close-" + UUID.randomUUID(),
                new NursingDischargeClosureRequestWire(organization, facility, context.patientId(), context.encounterId())))
                .isInstanceOf(NursingException.class)
                .satisfies(e -> assertThat(((NursingException) e).code()).isEqualTo("MEDICATION_TASKS_OPEN"));
    }

    @Test
    void givenDraftHandover_whenClosing_thenRejected() {
        Context context = seedContext();
        seedDraftHandover(context);
        assertThatThrownBy(() -> nursing.closeNursingDischarge(identity(), "close-" + UUID.randomUUID(),
                new NursingDischargeClosureRequestWire(organization, facility, context.patientId(), context.encounterId())))
                .isInstanceOf(NursingException.class)
                .satisfies(e -> assertThat(((NursingException) e).code()).isEqualTo("SHIFT_HANDOVERS_OPEN"));
    }

    @Test
    void givenClosure_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        NursingDischargeClosureWire closure = nursing.closeNursingDischarge(identity(), "close-" + UUID.randomUUID(),
                new NursingDischargeClosureRequestWire(organization, facility, context.patientId(), context.encounterId()));
        assertThatThrownBy(() -> jdbc.sql("""
                update nursing_discharge_closure set closed_by = gen_random_uuid()
                where tenant_id = cast(:tenant as uuid) and closure_id = :closure
                """).param("tenant", TENANT).param("closure", closure.closureId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
