package org.haoemr.dental;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.DentalRecordCreateRequestWire;
import org.haoemr.contracts.DentalRecordWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class DentalRecordApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private DentalService records;

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
                values (cast(:tenant as uuid), :patient, '合成口腔患者', 'M', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1980, 2, 2)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-DENTAL', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private DentalRecordCreateRequestWire command(Context context, String tooth, String procedure) {
        return new DentalRecordCreateRequestWire(organization, facility, context.patientId(),
                context.encounterId(), tooth, procedure);
    }

    @Test
    void givenPatient_whenCreatingAndListingRecord_thenLifecycleRecorded() {
        Context context = seedContext();
        DentalRecordWire created = records.createRecord(identity(), "dental-" + UUID.randomUUID(),
                command(context, "36", "36"));
        assertThat(created.toothNotation()).isEqualTo("36");
        assertThat(created.procedureTooth()).isEqualTo("36");
        assertThat(created.status()).isEqualTo(DentalRecordWire.StatusValue.ACTIVE);

        List<DentalRecordWire> listed = records.listRecords(identity(), context.patientId());
        assertThat(listed).extracting(DentalRecordWire::dentalRecordId).contains(created.dentalRecordId());
    }

    @Test
    void givenInvalidFdiNotation_whenCreating_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> records.createRecord(identity(), "dental-" + UUID.randomUUID(),
                command(context, "19", null)))
                .isInstanceOf(DentalException.class)
                .satisfies(e -> assertThat(((DentalException) e).code()).isEqualTo("DENTAL_REQUEST_INVALID"));
    }

    @Test
    void givenProcedureToothMismatch_whenCreating_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> records.createRecord(identity(), "dental-" + UUID.randomUUID(),
                command(context, "11", "12")))
                .isInstanceOf(DentalException.class)
                .satisfies(e -> assertThat(((DentalException) e).code()).isEqualTo("DENTAL_REQUEST_INVALID"));
    }

    @Test
    void givenRecordIdentity_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        DentalRecordWire created = records.createRecord(identity(), "dental-" + UUID.randomUUID(),
                command(context, "85", null));
        assertThatThrownBy(() -> jdbc.sql("""
                update dental_record set tooth_notation = '86'
                where tenant_id = cast(:tenant as uuid) and dental_record_id = :record
                """).param("tenant", TENANT).param("record", created.dentalRecordId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
