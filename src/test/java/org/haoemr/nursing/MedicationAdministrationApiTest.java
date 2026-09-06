package org.haoemr.nursing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.MedicationAdministrationRequestWire;
import org.haoemr.contracts.MedicationAdministrationWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class MedicationAdministrationApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";
    private static final String VERIFIER = "018f0000-0000-7000-8000-00000000aa06";

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
                values (cast(:tenant as uuid), :patient, '合成给药患者', 'U', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1975, 6, 9)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'INPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-ADMIN', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private ExecutionTask seedMedicationOrder(Context context) {
        UUID orderId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        jdbc.sql("""
                insert into clinical_order(
                  tenant_id, order_id, patient_id, encounter_id, facility_id, order_scope, status,
                  clinical_indication, author_user_id, signed_by, signed_at, rule_watermark)
                values (cast(:tenant as uuid), :order, :patient, :encounter, cast(:facility as uuid),
                  'TEMPORARY', 'ACTIVE', '合成抗感染治疗', cast(:user as uuid), cast(:user as uuid),
                  now(), 'RULESET-MEDICATION-6')
                """).param("tenant", TENANT).param("order", orderId).param("patient", context.patientId())
                .param("encounter", context.encounterId()).param("facility", FACILITY).param("user", USER).update();
        jdbc.sql("""
                insert into clinical_order_item(
                  tenant_id, order_item_id, order_id, item_type, catalog_code, display_name,
                  requested_quantity, quantity_unit, item_state, drug_code, dose_value, dose_unit, route_code)
                values (cast(:tenant as uuid), :item, :order, 'MEDICATION', 'MED-AMOX', '阿莫西林胶囊',
                  3, '粒', 'ACTIVE', 'DRUG-AMOX', 500, 'mg', 'PO')
                """).param("tenant", TENANT).param("item", itemId).param("order", orderId).update();
        jdbc.sql("""
                insert into order_execution_task(
                  tenant_id, execution_task_id, order_id, order_item_id, patient_id, encounter_id,
                  task_state, requested_quantity, performed_quantity, quantity_unit)
                values (cast(:tenant as uuid), :task, :order, :item, :patient, :encounter,
                  'IN_PROGRESS', 3, 0, '粒')
                """).param("tenant", TENANT).param("task", taskId).param("order", orderId)
                .param("item", itemId).param("patient", context.patientId())
                .param("encounter", context.encounterId()).update();
        return new ExecutionTask(taskId, "DRUG-AMOX", 500.0, "mg", "PO");
    }

    @Test
    void givenMatchingFiveRights_whenAdministering_thenRecordedWithDoubleCheck() {
        Context context = seedContext();
        ExecutionTask task = seedMedicationOrder(context);
        MedicationAdministrationWire administered = nursing.administerMedication(identity(),
                "admin-" + UUID.randomUUID(), new MedicationAdministrationRequestWire(
                        organization, facility, context.patientId(), context.encounterId(), task.taskId(),
                        task.drugCode(), task.doseValue(), task.doseUnit(), task.routeCode(),
                        Instant.now(), UUID.fromString(VERIFIER), "床旁双人核验通过"));
        assertThat(administered.drugCode()).isEqualTo("DRUG-AMOX");
        assertThat(administered.administeredBy()).isEqualTo(UUID.fromString(USER));
        assertThat(administered.verifiedBy()).isEqualTo(UUID.fromString(VERIFIER));

        List<MedicationAdministrationWire> listed = nursing.listMedicationAdministrations(
                identity(), organization, facility, context.patientId(), context.encounterId());
        assertThat(listed).extracting(MedicationAdministrationWire::administrationId)
                .contains(administered.administrationId());
    }

    @Test
    void givenMismatchedDrug_whenAdministering_thenFiveRightsRejected() {
        Context context = seedContext();
        ExecutionTask task = seedMedicationOrder(context);
        assertThatThrownBy(() -> nursing.administerMedication(identity(), "admin-" + UUID.randomUUID(),
                new MedicationAdministrationRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), task.taskId(), "DRUG-WRONG", task.doseValue(),
                        task.doseUnit(), task.routeCode(), Instant.now(), UUID.fromString(VERIFIER), null)))
                .isInstanceOf(NursingException.class)
                .satisfies(e -> assertThat(((NursingException) e).code()).isEqualTo("FIVE_RIGHTS_DRUG_MISMATCH"));
    }

    @Test
    void givenSelfVerification_whenAdministering_thenRejected() {
        Context context = seedContext();
        ExecutionTask task = seedMedicationOrder(context);
        assertThatThrownBy(() -> nursing.administerMedication(identity(), "admin-" + UUID.randomUUID(),
                new MedicationAdministrationRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), task.taskId(), task.drugCode(), task.doseValue(),
                        task.doseUnit(), task.routeCode(), Instant.now(), UUID.fromString(USER), null)))
                .isInstanceOf(NursingException.class)
                .satisfies(e -> assertThat(((NursingException) e).code())
                        .isEqualTo("VITAL_SIGN_REQUEST_INVALID"));
    }

    @Test
    void givenAdministrationRecord_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        ExecutionTask task = seedMedicationOrder(context);
        MedicationAdministrationWire administered = nursing.administerMedication(identity(),
                "admin-" + UUID.randomUUID(), new MedicationAdministrationRequestWire(
                        organization, facility, context.patientId(), context.encounterId(), task.taskId(),
                        task.drugCode(), task.doseValue(), task.doseUnit(), task.routeCode(),
                        Instant.now(), UUID.fromString(VERIFIER), null));
        assertThatThrownBy(() -> jdbc.sql("""
                update medication_administration set dose_value = 999
                where tenant_id = cast(:tenant as uuid) and administration_id = :administration
                """).param("tenant", TENANT).param("administration", administered.administrationId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId) {}
    private record ExecutionTask(UUID taskId, String drugCode, double doseValue, String doseUnit, String routeCode) {}
}
