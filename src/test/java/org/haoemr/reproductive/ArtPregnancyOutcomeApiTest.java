package org.haoemr.reproductive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ArtPregnancyOutcomeCreateRequestWire;
import org.haoemr.contracts.ArtPregnancyOutcomeWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class ArtPregnancyOutcomeApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private ArtPregnancyOutcomeService outcomes;

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
        UUID cycleId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成ART随访患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1992, 3, 3)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-ARTOUT', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        jdbc.sql("""
                insert into art_cycle_record(
                  tenant_id, cycle_id, patient_id, encounter_id, facility_id,
                  cycle_type, cycle_number, ethics_consent_date, status)
                values (cast(:tenant as uuid), :cycle, :patient, :encounter, cast(:facility as uuid),
                  'IVF', 1, :consent, 'ACTIVE')
                """).param("tenant", TENANT).param("cycle", cycleId).param("patient", patientId)
                .param("encounter", encounterId).param("facility", FACILITY)
                .param("consent", LocalDate.now().minusDays(30)).update();
        return new Context(patientId, encounterId, cycleId);
    }

    private ArtPregnancyOutcomeWire record(
            Context context, ArtPregnancyOutcomeCreateRequestWire.PregnancyResultValue result,
            int liveBirthCount, String complications) {
        return outcomes.record(identity(), "outcome-" + UUID.randomUUID(),
                new ArtPregnancyOutcomeCreateRequestWire(organization, facility, context.patientId(),
                        context.cycleId(), context.encounterId(), result, Instant.now(), liveBirthCount,
                        complications, Instant.now()));
    }

    @Test
    void givenPregnantOutcome_whenRecording_thenRecorded() {
        Context context = seedContext();
        ArtPregnancyOutcomeWire recorded = record(context,
                ArtPregnancyOutcomeCreateRequestWire.PregnancyResultValue.PREGNANT, 1, null);
        assertThat(recorded.pregnancyResult()).isEqualTo(ArtPregnancyOutcomeWire.PregnancyResultValue.PREGNANT);
        assertThat(recorded.recordedBy()).isEqualTo(UUID.fromString(USER));

        List<ArtPregnancyOutcomeWire> listed = outcomes.listRecords(identity(), context.patientId());
        assertThat(listed).extracting(ArtPregnancyOutcomeWire::outcomeId).contains(recorded.outcomeId());
    }

    @Test
    void givenMiscarriageWithComplications_whenRecording_thenAccepted() {
        Context context = seedContext();
        ArtPregnancyOutcomeWire recorded = record(context,
                ArtPregnancyOutcomeCreateRequestWire.PregnancyResultValue.MISCARRIAGE, 0, "早期流产");
        assertThat(recorded.complications()).isEqualTo("早期流产");
    }

    @Test
    void givenMiscarriageWithoutComplications_whenRecording_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> record(context,
                ArtPregnancyOutcomeCreateRequestWire.PregnancyResultValue.MISCARRIAGE, 0, null))
                .isInstanceOf(ArtPregnancyOutcomeException.class)
                .satisfies(e -> assertThat(((ArtPregnancyOutcomeException) e).code())
                        .isEqualTo("ART_MISCARRIAGE_COMPLICATION_REQUIRED"));
    }

    @Test
    void givenNegativeLiveBirth_whenRecording_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> record(context,
                ArtPregnancyOutcomeCreateRequestWire.PregnancyResultValue.PREGNANT, -1, null))
                .isInstanceOf(ArtPregnancyOutcomeException.class)
                .satisfies(e -> assertThat(((ArtPregnancyOutcomeException) e).code())
                        .isEqualTo("ART_OUTCOME_REQUEST_INVALID"));
    }

    @Test
    void givenOutcome_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        ArtPregnancyOutcomeWire recorded = record(context,
                ArtPregnancyOutcomeCreateRequestWire.PregnancyResultValue.PREGNANT, 1, null);
        assertThatThrownBy(() -> jdbc.sql("""
                update art_pregnancy_outcome set pregnancy_result = 'MISCARRIAGE'
                where tenant_id = cast(:tenant as uuid) and outcome_id = :outcome
                """).param("tenant", TENANT).param("outcome", recorded.outcomeId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId, UUID cycleId) {}
}
