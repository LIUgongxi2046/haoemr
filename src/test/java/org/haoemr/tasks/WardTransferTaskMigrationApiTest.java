package org.haoemr.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.WardTransferTaskMigrationRequestWire;
import org.haoemr.contracts.WardTransferTaskMigrationResultWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class WardTransferTaskMigrationApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private WardTransferTaskMigrationService migrations;

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
        UUID fromWardId = UUID.randomUUID();
        UUID toWardId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成转区患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1980, 1, 1)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'INPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-WARD', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        seedWard(fromWardId, "WARD-FROM");
        seedWard(toWardId, "WARD-TO");
        return new Context(patientId, encounterId, fromWardId, toWardId);
    }

    private void seedWard(UUID wardId, String code) {
        UUID departmentId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("""
                insert into clinical_department(
                  tenant_id, facility_id, department_id, department_code, display_name, status)
                values (cast(:tenant as uuid), cast(:facility as uuid), :department, :deptCode, '合成科室', 'ACTIVE')
                """).param("tenant", TENANT).param("facility", FACILITY).param("department", departmentId)
                .param("deptCode", code + "-DEPT-" + suffix).update();
        jdbc.sql("""
                insert into clinical_ward(
                  tenant_id, facility_id, department_id, ward_id, ward_code, display_name, status)
                values (cast(:tenant as uuid), cast(:facility as uuid), :department, :ward, :wardCode, '合成病区', 'ACTIVE')
                """).param("tenant", TENANT).param("facility", FACILITY).param("department", departmentId)
                .param("ward", wardId).param("wardCode", code + "-" + suffix).update();
    }

    private UUID seedTask(Context context, UUID wardId, String state) {
        UUID taskId = UUID.randomUUID();
        jdbc.sql("""
                insert into clinical_task(
                  tenant_id, task_id, patient_id, encounter_id, facility_id, ward_id,
                  source_type, source_id, task_type, title, risk_level, state, business_state, source_route)
                values (cast(:tenant as uuid), :task, :patient, :encounter, cast(:facility as uuid), :ward,
                  'DOCUMENT', :source, 'SIGN_DOCUMENT', '待签文书', 'ROUTINE', :state, 'OPEN', '#/record')
                """).param("tenant", TENANT).param("task", taskId).param("patient", context.patientId())
                .param("encounter", context.encounterId()).param("facility", FACILITY).param("ward", wardId)
                .param("source", UUID.randomUUID()).param("state", state).update();
        return taskId;
    }

    private UUID taskWard(UUID taskId) {
        return jdbc.sql("select ward_id from clinical_task where tenant_id = cast(:tenant as uuid) and task_id = :task")
                .param("tenant", TENANT).param("task", taskId).query(UUID.class).optional().orElse(null);
    }

    @Test
    void givenOpenTasks_whenMigrating_thenReassignedToTargetWard() {
        Context context = seedContext();
        UUID openFromWardTask = seedTask(context, context.fromWardId(), "PENDING");
        UUID openNullWardTask = seedTask(context, null, "ASSIGNED");
        UUID completedTask = seedTask(context, context.fromWardId(), "COMPLETED");

        WardTransferTaskMigrationResultWire result = migrations.migrateTasks(identity(), "migrate-" + UUID.randomUUID(),
                new WardTransferTaskMigrationRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), context.fromWardId(), context.toWardId()));

        assertThat(result.migratedCount()).isEqualTo(2);
        assertThat(taskWard(openFromWardTask)).isEqualTo(context.toWardId());
        assertThat(taskWard(openNullWardTask)).isEqualTo(context.toWardId());
        assertThat(taskWard(completedTask)).isEqualTo(context.fromWardId());
    }

    @Test
    void givenSameWard_whenMigrating_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> migrations.migrateTasks(identity(), "migrate-" + UUID.randomUUID(),
                new WardTransferTaskMigrationRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), context.fromWardId(), context.fromWardId())))
                .isInstanceOf(WardTransferTaskMigrationException.class)
                .satisfies(e -> assertThat(((WardTransferTaskMigrationException) e).code())
                        .isEqualTo("WARD_TRANSFER_SAME_WARD"));
    }

    @Test
    void givenAlreadyMigrated_whenMigratingAgain_thenZero() {
        Context context = seedContext();
        seedTask(context, context.fromWardId(), "PENDING");
        migrations.migrateTasks(identity(), "migrate-" + UUID.randomUUID(),
                new WardTransferTaskMigrationRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), context.fromWardId(), context.toWardId()));
        WardTransferTaskMigrationResultWire second = migrations.migrateTasks(identity(), "migrate-" + UUID.randomUUID(),
                new WardTransferTaskMigrationRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), context.fromWardId(), context.toWardId()));
        assertThat(second.migratedCount()).isZero();
    }

    private record Context(UUID patientId, UUID encounterId, UUID fromWardId, UUID toWardId) {}
}
