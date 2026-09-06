package org.haoemr.ent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.EntRecordCreateRequestWire;
import org.haoemr.contracts.EntRecordWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class EntRecordApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private EntService records;

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
                values (cast(:tenant as uuid), :patient, '合成耳鼻喉患者', 'M', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1978, 9, 9)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-ENT', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private EntRecordCreateRequestWire command(
            Context context, String laterality, String region, String airwayRisk, String precautions) {
        return new EntRecordCreateRequestWire(organization, facility, context.patientId(),
                context.encounterId(),
                EntRecordCreateRequestWire.LateralityValue.valueOf(laterality),
                EntRecordCreateRequestWire.RegionValue.valueOf(region),
                EntRecordCreateRequestWire.AirwayRiskLevelValue.valueOf(airwayRisk),
                precautions);
    }

    @Test
    void givenPatient_whenCreatingAndListingRecord_thenLifecycleRecorded() {
        Context context = seedContext();
        EntRecordWire created = records.createRecord(identity(), "ent-" + UUID.randomUUID(),
                command(context, "BILATERAL", "THROAT", "LOW", null));
        assertThat(created.laterality()).isEqualTo(EntRecordWire.LateralityValue.BILATERAL);
        assertThat(created.region()).isEqualTo(EntRecordWire.RegionValue.THROAT);
        assertThat(created.airwayRiskLevel()).isEqualTo(EntRecordWire.AirwayRiskLevelValue.LOW);
        assertThat(created.status()).isEqualTo(EntRecordWire.StatusValue.ACTIVE);

        List<EntRecordWire> listed = records.listRecords(identity(), context.patientId());
        assertThat(listed).extracting(EntRecordWire::entRecordId).contains(created.entRecordId());
    }

    @Test
    void givenHighAirwayRiskWithoutPrecautions_whenCreating_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> records.createRecord(identity(), "ent-" + UUID.randomUUID(),
                command(context, "BILATERAL", "THROAT", "HIGH", null)))
                .isInstanceOf(EntException.class)
                .satisfies(e -> assertThat(((EntException) e).code()).isEqualTo("ENT_REQUEST_INVALID"));
    }

    @Test
    void givenHighAirwayRiskWithPrecautions_whenCreating_thenAccepted() {
        Context context = seedContext();
        EntRecordWire created = records.createRecord(identity(), "ent-" + UUID.randomUUID(),
                command(context, "LEFT", "THROAT", "HIGH", "床旁备气切包并维持气道通畅"));
        assertThat(created.airwayRiskLevel()).isEqualTo(EntRecordWire.AirwayRiskLevelValue.HIGH);
        assertThat(created.airwayPrecautions()).isEqualTo("床旁备气切包并维持气道通畅");
    }

    @Test
    void givenRecordIdentity_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        EntRecordWire created = records.createRecord(identity(), "ent-" + UUID.randomUUID(),
                command(context, "RIGHT", "EAR", "NONE", null));
        assertThatThrownBy(() -> jdbc.sql("""
                update ent_record set region = 'THROAT'
                where tenant_id = cast(:tenant as uuid) and ent_record_id = :record
                """).param("tenant", TENANT).param("record", created.entRecordId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
