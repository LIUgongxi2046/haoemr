package org.haoemr.mentalhealth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.MentalHealthQcReviewCreateRequestWire;
import org.haoemr.contracts.MentalHealthQcReviewWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class MentalHealthQcReviewApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private MentalHealthQcReviewService reviews;

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
                values (cast(:tenant as uuid), :patient, '合成mental质控患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1991, 1, 1)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-MENTAL_HEALTH_QC-QC', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        UUID reviewedRecordId = UUID.randomUUID();
        jdbc.sql("""
                insert into mental_health_crisis_handover(
                  tenant_id, crisis_handover_id, patient_id, encounter_id, from_provider_id, to_provider_id,
                  crisis_reason, risk_level, protective_measures, handed_over_at)
                values (cast(:tenant as uuid), :record, :patient, :encounter, cast(:user as uuid),
                  '018f0000-0000-7000-8000-00000000aa06'::uuid, '自伤风险交接', 'HIGH', '持续陪护与限制危险物品', now())
                """).param("tenant", TENANT).param("record", reviewedRecordId).param("patient", patientId)
                .param("encounter", encounterId).param("user", USER).update();
        return new Context(patientId, encounterId, reviewedRecordId);
    }

    private MentalHealthQcReviewWire record(
            Context context, MentalHealthQcReviewCreateRequestWire.ReviewConclusionValue conclusion, String defect) {
        return reviews.record(identity(), "qc-" + UUID.randomUUID(),
                new MentalHealthQcReviewCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), MentalHealthQcReviewCreateRequestWire.ReviewedRecordTypeValue.CRISIS_HANDOVER,
                        context.reviewedRecordId(), conclusion, defect, Instant.now()));
    }

    @Test
    void givenPassReview_whenRecording_thenRecorded() {
        Context context = seedContext();
        MentalHealthQcReviewWire recorded = record(context, MentalHealthQcReviewCreateRequestWire.ReviewConclusionValue.PASS, null);
        assertThat(recorded.reviewConclusion()).isEqualTo(MentalHealthQcReviewWire.ReviewConclusionValue.PASS);
        assertThat(recorded.reviewedBy()).isEqualTo(UUID.fromString(USER));

        List<MentalHealthQcReviewWire> listed = reviews.listRecords(identity(), context.patientId());
        assertThat(listed).extracting(MentalHealthQcReviewWire::reviewId).contains(recorded.reviewId());
    }

    @Test
    void givenFailReviewWithDefect_whenRecording_thenAccepted() {
        Context context = seedContext();
        MentalHealthQcReviewWire recorded = record(context, MentalHealthQcReviewCreateRequestWire.ReviewConclusionValue.FAIL,
                "危机保护措施缺失");
        assertThat(recorded.reviewConclusion()).isEqualTo(MentalHealthQcReviewWire.ReviewConclusionValue.FAIL);
        assertThat(recorded.defectDescription()).isEqualTo("危机保护措施缺失");
    }

    @Test
    void givenFailReviewWithoutDefect_whenRecording_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> record(context, MentalHealthQcReviewCreateRequestWire.ReviewConclusionValue.FAIL, null))
                .isInstanceOf(MentalHealthQcReviewException.class)
                .satisfies(e -> assertThat(((MentalHealthQcReviewException) e).code())
                        .isEqualTo("MENTAL_HEALTH_QC_DEFECT_REQUIRED"));
    }

    @Test
    void givenReview_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        MentalHealthQcReviewWire recorded = record(context, MentalHealthQcReviewCreateRequestWire.ReviewConclusionValue.PASS, null);
        assertThatThrownBy(() -> jdbc.sql("""
                update mental_health_qc_review set review_conclusion = 'FAIL'
                where tenant_id = cast(:tenant as uuid) and review_id = :review
                """).param("tenant", TENANT).param("review", recorded.reviewId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId, UUID reviewedRecordId) {}
}
