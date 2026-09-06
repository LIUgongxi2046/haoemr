package org.haoemr.obstetrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ObstetricPostpartumFollowupCreateRequestWire;
import org.haoemr.contracts.ObstetricPostpartumFollowupWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class ObstetricPostpartumFollowupApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private ObstetricPostpartumFollowupService followups;

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
                values (cast(:tenant as uuid), :patient, '合成产后患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1991, 2, 2)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-PP', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private ObstetricPostpartumFollowupWire record(
            Context context, ObstetricPostpartumFollowupCreateRequestWire.LochiaStatusValue lochia,
            ObstetricPostpartumFollowupCreateRequestWire.WoundHealingValue wound, String complications) {
        return followups.record(identity(), "pp-" + UUID.randomUUID(),
                new ObstetricPostpartumFollowupCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), Instant.now(), lochia, wound, complications, Instant.now()));
    }

    @Test
    void givenNormalFollowup_whenRecording_thenRecorded() {
        Context context = seedContext();
        ObstetricPostpartumFollowupWire recorded = record(context,
                ObstetricPostpartumFollowupCreateRequestWire.LochiaStatusValue.NORMAL,
                ObstetricPostpartumFollowupCreateRequestWire.WoundHealingValue.GOOD, null);
        assertThat(recorded.lochiaStatus()).isEqualTo(ObstetricPostpartumFollowupWire.LochiaStatusValue.NORMAL);
        assertThat(recorded.recordedBy()).isEqualTo(UUID.fromString(USER));

        List<ObstetricPostpartumFollowupWire> listed = followups.listRecords(identity(), context.patientId());
        assertThat(listed).extracting(ObstetricPostpartumFollowupWire::followupId).contains(recorded.followupId());
    }

    @Test
    void givenAbnormalFollowupWithComplications_whenRecording_thenAccepted() {
        Context context = seedContext();
        ObstetricPostpartumFollowupWire recorded = record(context,
                ObstetricPostpartumFollowupCreateRequestWire.LochiaStatusValue.ABNORMAL,
                ObstetricPostpartumFollowupCreateRequestWire.WoundHealingValue.COMPLICATED, "恶露异常并伤口感染");
        assertThat(recorded.complications()).isEqualTo("恶露异常并伤口感染");
    }

    @Test
    void givenAbnormalFollowupWithoutComplications_whenRecording_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> record(context,
                ObstetricPostpartumFollowupCreateRequestWire.LochiaStatusValue.ABNORMAL,
                ObstetricPostpartumFollowupCreateRequestWire.WoundHealingValue.GOOD, null))
                .isInstanceOf(ObstetricPostpartumFollowupException.class)
                .satisfies(e -> assertThat(((ObstetricPostpartumFollowupException) e).code())
                        .isEqualTo("OBSTETRIC_POSTPARTUM_COMPLICATION_REQUIRED"));
    }

    @Test
    void givenFollowup_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        ObstetricPostpartumFollowupWire recorded = record(context,
                ObstetricPostpartumFollowupCreateRequestWire.LochiaStatusValue.NORMAL,
                ObstetricPostpartumFollowupCreateRequestWire.WoundHealingValue.GOOD, null);
        assertThatThrownBy(() -> jdbc.sql("""
                update obstetric_postpartum_followup set lochia_status = 'ABNORMAL'
                where tenant_id = cast(:tenant as uuid) and followup_id = :followup
                """).param("tenant", TENANT).param("followup", recorded.followupId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
