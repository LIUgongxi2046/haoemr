package org.haoemr.neonatal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.NeonatalRecordCreateRequestWire;
import org.haoemr.contracts.NeonatalRecordWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class NeonatalRecordApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private NeonatalService records;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private UUID seedPatient(String name, String sexCode, LocalDate birth) {
        UUID patientId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, :name, :sex, :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("name", name).param("sex", sexCode).param("birth", birth).update();
        return patientId;
    }

    private Context seedContext() {
        UUID motherId = seedPatient("合成产妇", "F", LocalDate.of(1992, 3, 3));
        UUID newbornId = seedPatient("合成新生儿", "M", LocalDate.now());
        UUID encounterId = UUID.randomUUID();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'INPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-NEONATAL', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", newbornId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(newbornId, motherId, encounterId);
    }

    private NeonatalRecordCreateRequestWire command(
            Context context, UUID motherId, int weeks, int apgar1, int apgar5, int weightG) {
        return new NeonatalRecordCreateRequestWire(organization, facility, context.patientId(),
                motherId, context.encounterId(), Instant.now(), weeks, apgar1, apgar5, weightG,
                NeonatalRecordCreateRequestWire.SexAtBirthValue.MALE);
    }

    @Test
    void givenNeonate_whenCreatingAndListingRecord_thenLifecycleRecorded() {
        Context context = seedContext();
        NeonatalRecordWire created = records.createRecord(identity(), "neo-" + UUID.randomUUID(),
                command(context, context.motherId(), 39, 8, 9, 3350));
        assertThat(created.motherPatientId()).isEqualTo(context.motherId());
        assertThat(created.gestationalAgeWeeks()).isEqualTo(39);
        assertThat(created.apgar1min()).isEqualTo(8);
        assertThat(created.apgar5min()).isEqualTo(9);
        assertThat(created.birthWeightG()).isEqualTo(3350);
        assertThat(created.status()).isEqualTo(NeonatalRecordWire.StatusValue.ACTIVE);

        List<NeonatalRecordWire> listed = records.listRecords(identity(), context.patientId());
        assertThat(listed).extracting(NeonatalRecordWire::neonatalRecordId).contains(created.neonatalRecordId());
    }

    @Test
    void givenSelfAsMother_whenCreating_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> records.createRecord(identity(), "neo-" + UUID.randomUUID(),
                command(context, context.patientId(), 39, 8, 9, 3300)))
                .isInstanceOf(NeonatalException.class)
                .satisfies(e -> assertThat(((NeonatalException) e).code()).isEqualTo("NEONATAL_REQUEST_INVALID"));
    }

    @Test
    void givenMaleMother_whenCreating_thenRejected() {
        Context context = seedContext();
        UUID maleId = seedPatient("合成男性", "M", LocalDate.of(1990, 1, 1));
        assertThatThrownBy(() -> records.createRecord(identity(), "neo-" + UUID.randomUUID(),
                command(context, maleId, 39, 8, 9, 3300)))
                .isInstanceOf(NeonatalException.class)
                .satisfies(e -> assertThat(((NeonatalException) e).code()).isEqualTo("NEONATAL_REQUEST_INVALID"));
    }

    @Test
    void givenOutOfRangeApgar_whenCreating_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> records.createRecord(identity(), "neo-" + UUID.randomUUID(),
                command(context, context.motherId(), 39, 11, 9, 3300)))
                .isInstanceOf(NeonatalException.class)
                .satisfies(e -> assertThat(((NeonatalException) e).code()).isEqualTo("NEONATAL_REQUEST_INVALID"));
    }

    @Test
    void givenRecordIdentity_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        NeonatalRecordWire created = records.createRecord(identity(), "neo-" + UUID.randomUUID(),
                command(context, context.motherId(), 38, 7, 8, 2900));
        assertThatThrownBy(() -> jdbc.sql("""
                update neonatal_record set gestational_age_weeks = 45
                where tenant_id = cast(:tenant as uuid) and neonatal_record_id = :record
                """).param("tenant", TENANT).param("record", created.neonatalRecordId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID motherId, UUID encounterId) {}
}
