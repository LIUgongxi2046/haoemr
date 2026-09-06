package org.haoemr.dermatology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.DermatologyBiologicScreeningCreateRequestWire;
import org.haoemr.contracts.DermatologyBiologicScreeningWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class DermatologyBiologicScreeningApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private DermatologyBiologicScreeningService screenings;

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
                values (cast(:tenant as uuid), :patient, '合成皮肤患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1986, 4, 4)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-DERM', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private DermatologyBiologicScreeningWire record(
            Context context,
            DermatologyBiologicScreeningCreateRequestWire.TbScreeningResultValue tb,
            DermatologyBiologicScreeningCreateRequestWire.HepatitisScreeningResultValue hepatitis) {
        return screenings.record(identity(), "screening-" + UUID.randomUUID(),
                new DermatologyBiologicScreeningCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), "阿达木单抗", tb, hepatitis, Instant.now()));
    }

    @Test
    void givenNegativeScreenings_whenRecording_thenCleared() {
        Context context = seedContext();
        DermatologyBiologicScreeningWire recorded = record(context,
                DermatologyBiologicScreeningCreateRequestWire.TbScreeningResultValue.NEGATIVE,
                DermatologyBiologicScreeningCreateRequestWire.HepatitisScreeningResultValue.NEGATIVE);
        assertThat(recorded.clearedForBiologic()).isTrue();
        assertThat(recorded.screenedBy()).isEqualTo(UUID.fromString(USER));

        List<DermatologyBiologicScreeningWire> listed = screenings.listRecords(identity(), context.patientId());
        assertThat(listed).extracting(DermatologyBiologicScreeningWire::screeningId)
                .contains(recorded.screeningId());
    }

    @Test
    void givenPositiveTb_whenRecording_thenNotCleared() {
        Context context = seedContext();
        DermatologyBiologicScreeningWire recorded = record(context,
                DermatologyBiologicScreeningCreateRequestWire.TbScreeningResultValue.POSITIVE,
                DermatologyBiologicScreeningCreateRequestWire.HepatitisScreeningResultValue.NEGATIVE);
        assertThat(recorded.clearedForBiologic()).isFalse();
    }

    @Test
    void givenClearedWithPositiveTbBypass_whenInserting_thenDatabaseRejects() {
        Context context = seedContext();
        assertThatThrownBy(() -> jdbc.sql("""
                insert into dermatology_biologic_screening(
                  tenant_id, screening_id, patient_id, encounter_id, facility_id, biologic_name,
                  tb_screening_result, hepatitis_screening_result, cleared_for_biologic, screened_at, screened_by)
                values (cast(:tenant as uuid), :screening, :patient, :encounter, cast(:facility as uuid),
                  '阿达木单抗', 'POSITIVE', 'NEGATIVE', true, now(), cast(:user as uuid))
                """).param("tenant", TENANT).param("screening", UUID.randomUUID())
                .param("patient", context.patientId()).param("encounter", context.encounterId())
                .param("facility", FACILITY).param("user", USER).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void givenScreening_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        DermatologyBiologicScreeningWire recorded = record(context,
                DermatologyBiologicScreeningCreateRequestWire.TbScreeningResultValue.NEGATIVE,
                DermatologyBiologicScreeningCreateRequestWire.HepatitisScreeningResultValue.NEGATIVE);
        assertThatThrownBy(() -> jdbc.sql("""
                update dermatology_biologic_screening set cleared_for_biologic = false
                where tenant_id = cast(:tenant as uuid) and screening_id = :screening
                """).param("tenant", TENANT).param("screening", recorded.screeningId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
