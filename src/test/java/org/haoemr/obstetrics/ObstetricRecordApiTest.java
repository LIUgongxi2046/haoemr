package org.haoemr.obstetrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ObstetricRecordCreateRequestWire;
import org.haoemr.contracts.ObstetricRecordWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class ObstetricRecordApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private ObstetricService obstetrics;

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
                values (cast(:tenant as uuid), :patient, '合成产科患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1992, 5, 5)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-OB', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    @Test
    void givenObstetricPatient_whenCreatingAndListingRecord_thenLifecycleRecorded() {
        Context context = seedContext();
        ObstetricRecordWire created = obstetrics.createRecord(identity(), "ob-" + UUID.randomUUID(),
                new ObstetricRecordCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), 2, 1, 34, LocalDate.now().plusDays(42),
                        ObstetricRecordCreateRequestWire.BloodGroupValue.O_POS,
                        ObstetricRecordCreateRequestWire.RhFactorValue.POSITIVE,
                        "妊娠期高血压，需监测"));
        assertThat(created.gestationalWeeks()).isEqualTo(34);
        assertThat(created.bloodGroup()).isEqualTo(ObstetricRecordWire.BloodGroupValue.O_POS);
        assertThat(created.highRiskFactors()).contains("高血压");

        List<ObstetricRecordWire> listed = obstetrics.listRecords(identity(), context.patientId());
        assertThat(listed).extracting(ObstetricRecordWire::obstetricRecordId).contains(created.obstetricRecordId());
    }

    @Test
    void givenParityExceedingGravidity_whenCreating_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> obstetrics.createRecord(identity(), "ob-" + UUID.randomUUID(),
                new ObstetricRecordCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), 1, 2, 20, null,
                        ObstetricRecordCreateRequestWire.BloodGroupValue.A_POS,
                        ObstetricRecordCreateRequestWire.RhFactorValue.POSITIVE, "无高危")))
                .isInstanceOf(ObstetricException.class)
                .satisfies(e -> assertThat(((ObstetricException) e).code())
                        .isEqualTo("OBSTETRIC_RECORD_REQUEST_INVALID"));
    }

    @Test
    void givenObstetricRecordIdentity_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        ObstetricRecordWire created = obstetrics.createRecord(identity(), "ob-" + UUID.randomUUID(),
                new ObstetricRecordCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), 1, 0, 12, null,
                        ObstetricRecordCreateRequestWire.BloodGroupValue.B_POS,
                        ObstetricRecordCreateRequestWire.RhFactorValue.POSITIVE, "无高危"));
        assertThatThrownBy(() -> jdbc.sql("""
                update obstetric_record set gestational_weeks = 40
                where tenant_id = cast(:tenant as uuid) and obstetric_record_id = :record
                """).param("tenant", TENANT).param("record", created.obstetricRecordId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
