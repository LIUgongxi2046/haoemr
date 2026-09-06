package org.haoemr.mentalhealth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.MentalHealthEvidenceCreateRequestWire;
import org.haoemr.contracts.MentalHealthEvidenceWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class MentalHealthEvidenceApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private MentalHealthEvidenceService notes;

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
                values (cast(:tenant as uuid), :patient, '合成mental临床患者', 'M', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1980, 10, 10)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'EMERGENCY', 'IN_PROGRESS', now(), 'SYNTHETIC-ENNOTE', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private MentalHealthEvidenceWire create(Context context, String assessment, String intervention, boolean risk) {
        return notes.create(identity(), "enote-" + UUID.randomUUID(),
                new MentalHealthEvidenceCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), assessment, intervention, risk, Instant.now()));
    }

    @Test
    void givenNote_whenCreatingAndListing_thenRecorded() {
        Context context = seedContext();
        MentalHealthEvidenceWire created = create(context, "患者神志清楚，生命体征平稳", "持续心电监护", false);
        assertThat(created.riskFlag()).isFalse();
        assertThat(created.assessment()).isEqualTo("患者神志清楚，生命体征平稳");

        List<MentalHealthEvidenceWire> listed = notes.listNotes(identity(), context.patientId());
        assertThat(listed).extracting(MentalHealthEvidenceWire::noteId).contains(created.noteId());
    }

    @Test
    void givenRiskNoteWithDetailedAssessment_whenCreating_thenAccepted() {
        Context context = seedContext();
        MentalHealthEvidenceWire created = create(context,
                "患者意识模糊，血氧持续下降，需密切监护并随时准备抢救", "吸氧并开放静脉通路", true);
        assertThat(created.riskFlag()).isTrue();
    }

    @Test
    void givenRiskNoteWithShortAssessment_whenCreating_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> create(context, "危重", "吸氧", true))
                .isInstanceOf(MentalHealthEvidenceException.class)
                .satisfies(e -> assertThat(((MentalHealthEvidenceException) e).code())
                        .isEqualTo("MENTAL_HEALTH_EVIDENCE_REQUEST_INVALID"));
    }

    @Test
    void givenNoteIdentity_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        MentalHealthEvidenceWire created = create(context, "患者神志清楚", "持续监护", false);
        assertThatThrownBy(() -> jdbc.sql("""
                update mental_health_evidence_record set assessment = '篡改'
                where tenant_id = cast(:tenant as uuid) and note_id = :note
                """).param("tenant", TENANT).param("note", created.noteId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
