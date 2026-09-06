package org.haoemr.mentalhealth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.MentalHealthRecordCreateRequestWire;
import org.haoemr.contracts.MentalHealthRecordWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class MentalHealthRecordApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private MentalHealthService records;

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
                values (cast(:tenant as uuid), :patient, '合成精神心理患者', 'M', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1985, 7, 7)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-MENTAL', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private MentalHealthRecordCreateRequestWire command(
            Context context, String suicide, String violence, String measures) {
        return new MentalHealthRecordCreateRequestWire(organization, facility, context.patientId(),
                context.encounterId(),
                MentalHealthRecordCreateRequestWire.SuicideRiskLevelValue.valueOf(suicide),
                MentalHealthRecordCreateRequestWire.ViolenceRiskLevelValue.valueOf(violence),
                Instant.now(), measures);
    }

    @Test
    void givenPatient_whenCreatingAndListingRecord_thenLifecycleRecorded() {
        Context context = seedContext();
        MentalHealthRecordWire created = records.createRecord(identity(), "mh-" + UUID.randomUUID(),
                command(context, "LOW", "NONE", null));
        assertThat(created.dataClassification()).isEqualTo(MentalHealthRecordWire.DataClassificationValue.RESTRICTED);
        assertThat(created.suicideRiskLevel()).isEqualTo(MentalHealthRecordWire.SuicideRiskLevelValue.LOW);
        assertThat(created.violenceRiskLevel()).isEqualTo(MentalHealthRecordWire.ViolenceRiskLevelValue.NONE);
        assertThat(created.status()).isEqualTo(MentalHealthRecordWire.StatusValue.ACTIVE);

        List<MentalHealthRecordWire> listed = records.listRecords(identity(), context.patientId());
        assertThat(listed).extracting(MentalHealthRecordWire::mentalHealthRecordId).contains(created.mentalHealthRecordId());
    }

    @Test
    void givenHighRiskWithoutMeasures_whenCreating_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> records.createRecord(identity(), "mh-" + UUID.randomUUID(),
                command(context, "HIGH", "NONE", null)))
                .isInstanceOf(MentalHealthException.class)
                .satisfies(e -> assertThat(((MentalHealthException) e).code()).isEqualTo("MENTAL_HEALTH_REQUEST_INVALID"));
    }

    @Test
    void givenHighRiskWithMeasures_whenCreating_thenAccepted() {
        Context context = seedContext();
        MentalHealthRecordWire created = records.createRecord(identity(), "mh-" + UUID.randomUUID(),
                command(context, "IMMINENT", "MODERATE", "24 小时一对一陪护并限制危险物品"));
        assertThat(created.suicideRiskLevel()).isEqualTo(MentalHealthRecordWire.SuicideRiskLevelValue.IMMINENT);
        assertThat(created.protectiveMeasures()).isEqualTo("24 小时一对一陪护并限制危险物品");
    }

    @Test
    void givenRecordIdentity_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        MentalHealthRecordWire created = records.createRecord(identity(), "mh-" + UUID.randomUUID(),
                command(context, "MODERATE", "LOW", null));
        assertThatThrownBy(() -> jdbc.sql("""
                update mental_health_record set suicide_risk_level = 'HIGH'
                where tenant_id = cast(:tenant as uuid) and mental_health_record_id = :record
                """).param("tenant", TENANT).param("record", created.mentalHealthRecordId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
