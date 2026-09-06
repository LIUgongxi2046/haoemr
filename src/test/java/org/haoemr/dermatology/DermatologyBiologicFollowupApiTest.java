package org.haoemr.dermatology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.DermatologyBiologicFollowupCreateRequestWire;
import org.haoemr.contracts.DermatologyBiologicFollowupWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class DermatologyBiologicFollowupApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private DermatologyBiologicFollowupService followups;

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
                values (cast(:tenant as uuid), :patient, '合成生物制剂随访患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1986, 5, 5)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-BIOFUP', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private DermatologyBiologicFollowupWire record(Context context, double pasi, boolean adverseEvent, String description) {
        return followups.record(identity(), "biofup-" + UUID.randomUUID(),
                new DermatologyBiologicFollowupCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), "阿达木单抗", Instant.now(), pasi, adverseEvent, description, Instant.now()));
    }

    @Test
    void givenFollowup_whenRecording_thenRecorded() {
        Context context = seedContext();
        DermatologyBiologicFollowupWire recorded = record(context, 10.0, false, null);
        assertThat(recorded.pasiScore()).isEqualTo(10.0);
        assertThat(recorded.recordedBy()).isEqualTo(UUID.fromString(USER));

        List<DermatologyBiologicFollowupWire> listed = followups.listRecords(identity(), context.patientId());
        assertThat(listed).extracting(DermatologyBiologicFollowupWire::followupId).contains(recorded.followupId());
    }

    @Test
    void givenAdverseEventWithDescription_whenRecording_thenAccepted() {
        Context context = seedContext();
        DermatologyBiologicFollowupWire recorded = record(context, 18.0, true, "注射部位红肿");
        assertThat(recorded.adverseEventDescription()).isEqualTo("注射部位红肿");
    }

    @Test
    void givenAdverseEventWithoutDescription_whenRecording_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> record(context, 10.0, true, null))
                .isInstanceOf(DermatologyBiologicFollowupException.class)
                .satisfies(e -> assertThat(((DermatologyBiologicFollowupException) e).code())
                        .isEqualTo("DERMATOLOGY_BIOLOGIC_ADVERSE_EVENT_DESCRIPTION_REQUIRED"));
    }

    @Test
    void givenOutOfRangePasi_whenRecording_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> record(context, 80.0, false, null))
                .isInstanceOf(DermatologyBiologicFollowupException.class)
                .satisfies(e -> assertThat(((DermatologyBiologicFollowupException) e).code())
                        .isEqualTo("DERMATOLOGY_BIOLOGIC_FOLLOWUP_REQUEST_INVALID"));
    }

    @Test
    void givenFollowup_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        DermatologyBiologicFollowupWire recorded = record(context, 10.0, false, null);
        assertThatThrownBy(() -> jdbc.sql("""
                update dermatology_biologic_followup set pasi_score = 50
                where tenant_id = cast(:tenant as uuid) and followup_id = :followup
                """).param("tenant", TENANT).param("followup", recorded.followupId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
