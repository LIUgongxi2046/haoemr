package org.haoemr.obstetrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ObstetricAntenatalExamCreateRequestWire;
import org.haoemr.contracts.ObstetricAntenatalExamWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class ObstetricAntenatalExamApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private ObstetricAntenatalExamService exams;

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
                values (cast(:tenant as uuid), :patient, '合成产检患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1990, 9, 9)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-ANTENATAL', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private ObstetricAntenatalExamWire record(
            Context context, int weeks, int systolic, int diastolic,
            ObstetricAntenatalExamCreateRequestWire.ProteinuriaValue proteinuria, boolean preeclampsia) {
        return exams.record(identity(), "antenatal-" + UUID.randomUUID(),
                new ObstetricAntenatalExamCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), weeks, 24.0, 140, systolic, diastolic, proteinuria, preeclampsia,
                        Instant.now()));
    }

    @Test
    void givenNormalExam_whenRecording_thenRecorded() {
        Context context = seedContext();
        ObstetricAntenatalExamWire recorded = record(context, 20, 120, 80,
                ObstetricAntenatalExamCreateRequestWire.ProteinuriaValue.NEGATIVE, false);
        assertThat(recorded.preeclampsiaRisk()).isFalse();
        assertThat(recorded.recordedBy()).isEqualTo(UUID.fromString(USER));

        List<ObstetricAntenatalExamWire> listed = exams.listRecords(identity(), context.patientId());
        assertThat(listed).extracting(ObstetricAntenatalExamWire::examId).contains(recorded.examId());
    }

    @Test
    void givenPreeclampsiaRiskWithCriteria_whenRecording_thenAccepted() {
        Context context = seedContext();
        ObstetricAntenatalExamWire recorded = record(context, 32, 150, 100,
                ObstetricAntenatalExamCreateRequestWire.ProteinuriaValue.POSITIVE, true);
        assertThat(recorded.preeclampsiaRisk()).isTrue();
    }

    @Test
    void givenPreeclampsiaRiskWithoutCriteria_whenRecording_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> record(context, 20, 120, 80,
                ObstetricAntenatalExamCreateRequestWire.ProteinuriaValue.NEGATIVE, true))
                .isInstanceOf(ObstetricAntenatalExamException.class)
                .satisfies(e -> assertThat(((ObstetricAntenatalExamException) e).code())
                        .isEqualTo("PREECLAMPSIA_RISK_CRITERIA_UNMET"));
    }

    @Test
    void givenOutOfRangeWeeks_whenRecording_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> record(context, 50, 120, 80,
                ObstetricAntenatalExamCreateRequestWire.ProteinuriaValue.NEGATIVE, false))
                .isInstanceOf(ObstetricAntenatalExamException.class)
                .satisfies(e -> assertThat(((ObstetricAntenatalExamException) e).code())
                        .isEqualTo("OBSTETRIC_ANTENATAL_REQUEST_INVALID"));
    }

    @Test
    void givenExam_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        ObstetricAntenatalExamWire recorded = record(context, 20, 120, 80,
                ObstetricAntenatalExamCreateRequestWire.ProteinuriaValue.NEGATIVE, false);
        assertThatThrownBy(() -> jdbc.sql("""
                update obstetric_antenatal_exam set systolic_bp = 200
                where tenant_id = cast(:tenant as uuid) and exam_id = :exam
                """).param("tenant", TENANT).param("exam", recorded.examId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
