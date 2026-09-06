package org.haoemr.pediatrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.PediatricGrowthRecordCreateRequestWire;
import org.haoemr.contracts.PediatricGrowthRecordWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class PediatricGrowthApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private PediatricGrowthService growth;

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
                values (cast(:tenant as uuid), :patient, '合成儿科患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(2018, 12, 12)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-GROWTH', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private PediatricGrowthRecordWire record(Context context, double height, double weight, Double head) {
        return growth.record(identity(), "growth-" + UUID.randomUUID(),
                new PediatricGrowthRecordCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), height, weight, head, Instant.now()));
    }

    @Test
    void givenGrowth_whenRecording_thenRecorded() {
        Context context = seedContext();
        PediatricGrowthRecordWire recorded = record(context, 120.0, 22.0, 51.0);
        assertThat(recorded.heightCm()).isEqualTo(120.0);
        assertThat(recorded.weightKg()).isEqualTo(22.0);
        assertThat(recorded.headCircumferenceCm()).isEqualTo(51.0);
        assertThat(recorded.recordedBy()).isEqualTo(UUID.fromString(USER));

        List<PediatricGrowthRecordWire> listed = growth.listRecords(identity(), context.patientId());
        assertThat(listed).extracting(PediatricGrowthRecordWire::growthRecordId)
                .contains(recorded.growthRecordId());
    }

    @Test
    void givenOutOfRangeHeight_whenRecording_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> record(context, 300.0, 22.0, 51.0))
                .isInstanceOf(PediatricGrowthException.class)
                .satisfies(e -> assertThat(((PediatricGrowthException) e).code())
                        .isEqualTo("PEDIATRIC_GROWTH_REQUEST_INVALID"));
    }

    @Test
    void givenOutOfRangeWeight_whenRecording_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> record(context, 120.0, 300.0, 51.0))
                .isInstanceOf(PediatricGrowthException.class)
                .satisfies(e -> assertThat(((PediatricGrowthException) e).code())
                        .isEqualTo("PEDIATRIC_GROWTH_REQUEST_INVALID"));
    }

    @Test
    void givenOutOfRangeHead_whenRecording_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> record(context, 120.0, 22.0, 100.0))
                .isInstanceOf(PediatricGrowthException.class)
                .satisfies(e -> assertThat(((PediatricGrowthException) e).code())
                        .isEqualTo("PEDIATRIC_GROWTH_REQUEST_INVALID"));
    }

    @Test
    void givenGrowth_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        PediatricGrowthRecordWire recorded = record(context, 120.0, 22.0, 51.0);
        assertThatThrownBy(() -> jdbc.sql("""
                update pediatric_growth_record set height_cm = 150
                where tenant_id = cast(:tenant as uuid) and growth_record_id = :record
                """).param("tenant", TENANT).param("record", recorded.growthRecordId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
