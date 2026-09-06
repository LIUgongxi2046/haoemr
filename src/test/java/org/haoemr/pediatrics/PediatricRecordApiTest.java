package org.haoemr.pediatrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.PediatricRecordCreateRequestWire;
import org.haoemr.contracts.PediatricRecordWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class PediatricRecordApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private PediatricService records;

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
                values (cast(:tenant as uuid), :patient, '合成儿科患者', 'M', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.now().minusMonths(8)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-PEDIATRIC', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private PediatricRecordCreateRequestWire command(
            Context context, double weightKg, int ageMonths, boolean critical) {
        return new PediatricRecordCreateRequestWire(organization, facility, context.patientId(),
                context.encounterId(), "王阿姨", PediatricRecordCreateRequestWire.GuardianRelationshipValue.MOTHER,
                "13800000000", ageMonths, weightKg, Instant.now(), critical);
    }

    @Test
    void givenPediatricPatient_whenCreatingAndListingRecord_thenLifecycleRecorded() {
        Context context = seedContext();
        PediatricRecordWire created = records.createRecord(identity(), "ped-" + UUID.randomUUID(),
                command(context, 8.40, 8, true));
        assertThat(created.guardianRelationship()).isEqualTo(PediatricRecordWire.GuardianRelationshipValue.MOTHER);
        assertThat(created.ageInMonths()).isEqualTo(8);
        assertThat(created.weightKg()).isEqualTo(8.40);
        assertThat(created.criticalFlag()).isTrue();
        assertThat(created.status()).isEqualTo(PediatricRecordWire.StatusValue.ACTIVE);

        List<PediatricRecordWire> listed = records.listRecords(identity(), context.patientId());
        assertThat(listed).extracting(PediatricRecordWire::pediatricRecordId).contains(created.pediatricRecordId());
    }

    @Test
    void givenOutOfRangeWeight_whenCreating_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> records.createRecord(identity(), "ped-" + UUID.randomUUID(),
                command(context, 300.0, 8, false)))
                .isInstanceOf(PediatricException.class)
                .satisfies(e -> assertThat(((PediatricException) e).code()).isEqualTo("PEDIATRIC_REQUEST_INVALID"));
    }

    @Test
    void givenRecordIdentity_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        PediatricRecordWire created = records.createRecord(identity(), "ped-" + UUID.randomUUID(),
                command(context, 6.20, 3, false));
        assertThatThrownBy(() -> jdbc.sql("""
                update pediatric_record set age_in_months = 99
                where tenant_id = cast(:tenant as uuid) and pediatric_record_id = :record
                """).param("tenant", TENANT).param("record", created.pediatricRecordId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
