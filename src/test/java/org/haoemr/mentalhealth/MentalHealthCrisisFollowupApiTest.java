package org.haoemr.mentalhealth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.MentalHealthCrisisFollowupCreateRequestWire;
import org.haoemr.contracts.MentalHealthCrisisFollowupWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class MentalHealthCrisisFollowupApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private MentalHealthCrisisFollowupService followups;

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
                values (cast(:tenant as uuid), :patient, '合成危机随访患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1988, 8, 8)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'INPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-CRIFUP', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        UUID crisisHandoverId = UUID.randomUUID();
        jdbc.sql("""
                insert into mental_health_crisis_handover(
                  tenant_id, crisis_handover_id, patient_id, encounter_id, from_provider_id, to_provider_id,
                  crisis_reason, risk_level, protective_measures, handed_over_at)
                values (cast(:tenant as uuid), :handover, :patient, :encounter, cast(:user as uuid),
                  '018f0000-0000-7000-8000-00000000aa06'::uuid, '危机风险持续评估', 'HIGH', '限制危险物品', now())
                """).param("tenant", TENANT).param("handover", crisisHandoverId).param("patient", patientId)
                .param("encounter", encounterId).param("user", USER).update();
        return new Context(patientId, encounterId, crisisHandoverId);
    }

    private MentalHealthCrisisFollowupWire record(
            Context context, MentalHealthCrisisFollowupCreateRequestWire.RiskLevelValue riskLevel, String measures) {
        return followups.record(identity(), "crifup-" + UUID.randomUUID(),
                new MentalHealthCrisisFollowupCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), context.crisisHandoverId(), Instant.now(), riskLevel, measures, Instant.now()));
    }

    @Test
    void givenLowRiskFollowup_whenRecording_thenRecorded() {
        Context context = seedContext();
        MentalHealthCrisisFollowupWire recorded = record(context,
                MentalHealthCrisisFollowupCreateRequestWire.RiskLevelValue.LOW, null);
        assertThat(recorded.dataClassification())
                .isEqualTo(MentalHealthCrisisFollowupWire.DataClassificationValue.RESTRICTED);
        assertThat(recorded.recordedBy()).isEqualTo(UUID.fromString(USER));

        List<MentalHealthCrisisFollowupWire> listed = followups.listRecords(identity(), context.patientId());
        assertThat(listed).extracting(MentalHealthCrisisFollowupWire::followupId).contains(recorded.followupId());
    }

    @Test
    void givenHighRiskWithMeasures_whenRecording_thenAccepted() {
        Context context = seedContext();
        MentalHealthCrisisFollowupWire recorded = record(context,
                MentalHealthCrisisFollowupCreateRequestWire.RiskLevelValue.HIGH, "持续观察并限制危险物品");
        assertThat(recorded.protectiveMeasures()).isEqualTo("持续观察并限制危险物品");
    }

    @Test
    void givenHighRiskWithoutMeasures_whenRecording_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> record(context,
                MentalHealthCrisisFollowupCreateRequestWire.RiskLevelValue.HIGH, null))
                .isInstanceOf(MentalHealthCrisisFollowupException.class)
                .satisfies(e -> assertThat(((MentalHealthCrisisFollowupException) e).code())
                        .isEqualTo("MENTAL_HEALTH_CRISIS_FOLLOWUP_PROTECTION_REQUIRED"));
    }

    @Test
    void givenFollowup_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        MentalHealthCrisisFollowupWire recorded = record(context,
                MentalHealthCrisisFollowupCreateRequestWire.RiskLevelValue.LOW, null);
        assertThatThrownBy(() -> jdbc.sql("""
                update mental_health_crisis_followup set risk_level = 'IMMINENT'
                where tenant_id = cast(:tenant as uuid) and followup_id = :followup
                """).param("tenant", TENANT).param("followup", recorded.followupId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId, UUID crisisHandoverId) {}
}
