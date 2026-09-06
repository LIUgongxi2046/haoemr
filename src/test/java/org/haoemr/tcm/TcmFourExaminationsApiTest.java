package org.haoemr.tcm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.TcmFourExaminationsCreateRequestWire;
import org.haoemr.contracts.TcmFourExaminationsWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class TcmFourExaminationsApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private TcmFourExaminationsService examinations;

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
                values (cast(:tenant as uuid), :patient, '合成四诊患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1975, 6, 6)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-TCM4', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        return new Context(patientId, encounterId);
    }

    private TcmFourExaminationsWire record(
            Context context, String inspection, String auscultation, String inquiry, String palpation) {
        return examinations.record(identity(), "tcm4-" + UUID.randomUUID(),
                new TcmFourExaminationsCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), inspection, auscultation, inquiry, palpation, Instant.now()));
    }

    @Test
    void givenFourExaminations_whenRecording_thenRecorded() {
        Context context = seedContext();
        TcmFourExaminationsWire recorded = record(context, "面色萎黄", "语声低微", "纳差便溏", "脉细弱");
        assertThat(recorded.inspection()).isEqualTo("面色萎黄");
        assertThat(recorded.recordedBy()).isEqualTo(UUID.fromString(USER));

        List<TcmFourExaminationsWire> listed = examinations.listRecords(identity(), context.patientId());
        assertThat(listed).extracting(TcmFourExaminationsWire::examId).contains(recorded.examId());
    }

    @Test
    void givenMissingInspection_whenRecording_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> record(context, null, "语声低微", "纳差便溏", "脉细弱"))
                .isInstanceOf(TcmFourExaminationsException.class)
                .satisfies(e -> assertThat(((TcmFourExaminationsException) e).code())
                        .isEqualTo("TCM_FOUR_EXAMINATIONS_REQUEST_INVALID"));
    }

    @Test
    void givenMissingPalpation_whenRecording_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> record(context, "面色萎黄", "语声低微", "纳差便溏", null))
                .isInstanceOf(TcmFourExaminationsException.class)
                .satisfies(e -> assertThat(((TcmFourExaminationsException) e).code())
                        .isEqualTo("TCM_FOUR_EXAMINATIONS_REQUEST_INVALID"));
    }

    @Test
    void givenFourExaminations_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        TcmFourExaminationsWire recorded = record(context, "面色萎黄", "语声低微", "纳差便溏", "脉细弱");
        assertThatThrownBy(() -> jdbc.sql("""
                update tcm_four_examinations set inquiry = '篡改'
                where tenant_id = cast(:tenant as uuid) and exam_id = :exam
                """).param("tenant", TENANT).param("exam", recorded.examId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId) {}
}
