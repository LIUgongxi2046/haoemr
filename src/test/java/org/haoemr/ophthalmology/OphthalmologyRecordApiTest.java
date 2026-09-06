package org.haoemr.ophthalmology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.OphthalmologyRecordCreateRequestWire;
import org.haoemr.contracts.OphthalmologyRecordWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class OphthalmologyRecordApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private OphthalmologyService records;

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
                values (cast(:tenant as uuid), :patient, '合成眼科患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1970, 1, 1)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-OPHTHALMOLOGY', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private OphthalmologyRecordCreateRequestWire command(
            Context context, String laterality, Double iopOd, Double iopOs, String surgicalEye) {
        return new OphthalmologyRecordCreateRequestWire(organization, facility, context.patientId(),
                context.encounterId(),
                OphthalmologyRecordCreateRequestWire.LateralityValue.valueOf(laterality),
                iopOd, iopOs,
                OphthalmologyRecordCreateRequestWire.SurgicalEyeValue.valueOf(surgicalEye));
    }

    @Test
    void givenPatient_whenCreatingAndListingRecord_thenLifecycleRecorded() {
        Context context = seedContext();
        OphthalmologyRecordWire created = records.createRecord(identity(), "oph-" + UUID.randomUUID(),
                command(context, "OU", 18.5, 19.0, "NONE"));
        assertThat(created.laterality()).isEqualTo(OphthalmologyRecordWire.LateralityValue.OU);
        assertThat(created.iopOdMmhg()).isEqualTo(18.5);
        assertThat(created.iopOsMmhg()).isEqualTo(19.0);
        assertThat(created.surgicalEye()).isEqualTo(OphthalmologyRecordWire.SurgicalEyeValue.NONE);
        assertThat(created.status()).isEqualTo(OphthalmologyRecordWire.StatusValue.ACTIVE);

        List<OphthalmologyRecordWire> listed = records.listRecords(identity(), context.patientId());
        assertThat(listed).extracting(OphthalmologyRecordWire::ophthalmologyRecordId)
                .contains(created.ophthalmologyRecordId());
    }

    @Test
    void givenSurgicalEyeMismatch_whenCreating_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> records.createRecord(identity(), "oph-" + UUID.randomUUID(),
                command(context, "OD", null, null, "OS")))
                .isInstanceOf(OphthalmologyException.class)
                .satisfies(e -> assertThat(((OphthalmologyException) e).code()).isEqualTo("OPHTHALMOLOGY_REQUEST_INVALID"));
    }

    @Test
    void givenOutOfRangeIop_whenCreating_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> records.createRecord(identity(), "oph-" + UUID.randomUUID(),
                command(context, "OU", 85.0, null, "NONE")))
                .isInstanceOf(OphthalmologyException.class)
                .satisfies(e -> assertThat(((OphthalmologyException) e).code()).isEqualTo("OPHTHALMOLOGY_REQUEST_INVALID"));
    }

    @Test
    void givenRecordIdentity_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        OphthalmologyRecordWire created = records.createRecord(identity(), "oph-" + UUID.randomUUID(),
                command(context, "OD", 21.0, null, "OD"));
        assertThatThrownBy(() -> jdbc.sql("""
                update ophthalmology_record set laterality = 'OS'
                where tenant_id = cast(:tenant as uuid) and ophthalmology_record_id = :record
                """).param("tenant", TENANT).param("record", created.ophthalmologyRecordId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
