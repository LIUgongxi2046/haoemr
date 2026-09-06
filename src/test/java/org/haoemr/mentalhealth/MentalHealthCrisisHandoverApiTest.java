package org.haoemr.mentalhealth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.MentalHealthCrisisHandoverCreateRequestWire;
import org.haoemr.contracts.MentalHealthCrisisHandoverWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class MentalHealthCrisisHandoverApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";
    private static final String COLLABORATOR = "018f0000-0000-7000-8000-00000000aa06";

    @Autowired
    private MentalHealthCrisisHandoverService handovers;

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
                values (cast(:tenant as uuid), :patient, '合成危机患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1988, 9, 9)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'INPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-CRISIS', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private MentalHealthCrisisHandoverWire record(
            Context context, UUID toProviderId,
            MentalHealthCrisisHandoverCreateRequestWire.RiskLevelValue riskLevel, String measures) {
        return handovers.record(identity(), "crisis-" + UUID.randomUUID(),
                new MentalHealthCrisisHandoverCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), toProviderId, "自杀风险升级", riskLevel, measures, Instant.now()));
    }

    @Test
    void givenCrisisHandover_whenRecording_thenRecorded() {
        Context context = seedContext();
        MentalHealthCrisisHandoverWire recorded = record(context, UUID.fromString(COLLABORATOR),
                MentalHealthCrisisHandoverCreateRequestWire.RiskLevelValue.LOW, null);
        assertThat(recorded.fromProviderId()).isEqualTo(UUID.fromString(USER));
        assertThat(recorded.toProviderId()).isEqualTo(UUID.fromString(COLLABORATOR));
        assertThat(recorded.dataClassification())
                .isEqualTo(MentalHealthCrisisHandoverWire.DataClassificationValue.RESTRICTED);

        List<MentalHealthCrisisHandoverWire> listed = handovers.listRecords(identity(), context.patientId());
        assertThat(listed).extracting(MentalHealthCrisisHandoverWire::crisisHandoverId)
                .contains(recorded.crisisHandoverId());
    }

    @Test
    void givenHighRiskWithoutMeasures_whenRecording_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> record(context, UUID.fromString(COLLABORATOR),
                MentalHealthCrisisHandoverCreateRequestWire.RiskLevelValue.HIGH, null))
                .isInstanceOf(MentalHealthCrisisHandoverException.class)
                .satisfies(e -> assertThat(((MentalHealthCrisisHandoverException) e).code())
                        .isEqualTo("CRISIS_PROTECTIVE_MEASURES_REQUIRED"));
    }

    @Test
    void givenHighRiskWithMeasures_whenRecording_thenAccepted() {
        Context context = seedContext();
        MentalHealthCrisisHandoverWire recorded = record(context, UUID.fromString(COLLABORATOR),
                MentalHealthCrisisHandoverCreateRequestWire.RiskLevelValue.HIGH, "持续观察并限制危险物品");
        assertThat(recorded.protectiveMeasures()).isEqualTo("持续观察并限制危险物品");
    }

    @Test
    void givenSelfHandover_whenRecording_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> record(context, UUID.fromString(USER),
                MentalHealthCrisisHandoverCreateRequestWire.RiskLevelValue.LOW, null))
                .isInstanceOf(MentalHealthCrisisHandoverException.class)
                .satisfies(e -> assertThat(((MentalHealthCrisisHandoverException) e).code())
                        .isEqualTo("SELF_HANDOVER_FORBIDDEN"));
    }

    @Test
    void givenHandover_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        MentalHealthCrisisHandoverWire recorded = record(context, UUID.fromString(COLLABORATOR),
                MentalHealthCrisisHandoverCreateRequestWire.RiskLevelValue.LOW, null);
        assertThatThrownBy(() -> jdbc.sql("""
                update mental_health_crisis_handover set risk_level = 'IMMINENT'
                where tenant_id = cast(:tenant as uuid) and crisis_handover_id = :handover
                """).param("tenant", TENANT).param("handover", recorded.crisisHandoverId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
