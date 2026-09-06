package org.haoemr.ophthalmology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.OphthalmologyQcReviewCreateRequestWire;
import org.haoemr.contracts.OphthalmologyQcReviewWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class OphthalmologyQcReviewApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private OphthalmologyQcReviewService reviews;

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
                values (cast(:tenant as uuid), :patient, '合成ophthalmology质控患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1991, 1, 1)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-OPHTHALMOLOGY_QC-QC', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        UUID reviewedRecordId = UUID.randomUUID();
        jdbc.sql("""
                insert into ophthalmology_preop_verification(
                  tenant_id, verification_id, patient_id, encounter_id, facility_id,
                  surgical_eye, verified_by, witnessed_by, verified_at)
                values (cast(:tenant as uuid), :record, :patient, :encounter, cast(:facility as uuid),
                  'OD', cast(:user as uuid), '018f0000-0000-7000-8000-00000000aa06'::uuid, now())
                """).param("tenant", TENANT).param("record", reviewedRecordId).param("patient", patientId)
                .param("encounter", encounterId).param("facility", FACILITY).param("user", USER).update();
        return new Context(patientId, encounterId, reviewedRecordId);
    }

    private OphthalmologyQcReviewWire record(
            Context context, OphthalmologyQcReviewCreateRequestWire.ReviewConclusionValue conclusion, String defect) {
        return reviews.record(identity(), "qc-" + UUID.randomUUID(),
                new OphthalmologyQcReviewCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), OphthalmologyQcReviewCreateRequestWire.ReviewedRecordTypeValue.PREOP_VERIFICATION,
                        context.reviewedRecordId(), conclusion, defect, Instant.now()));
    }

    @Test
    void givenPassReview_whenRecording_thenRecorded() {
        Context context = seedContext();
        OphthalmologyQcReviewWire recorded = record(context, OphthalmologyQcReviewCreateRequestWire.ReviewConclusionValue.PASS, null);
        assertThat(recorded.reviewConclusion()).isEqualTo(OphthalmologyQcReviewWire.ReviewConclusionValue.PASS);
        assertThat(recorded.reviewedBy()).isEqualTo(UUID.fromString(USER));

        List<OphthalmologyQcReviewWire> listed = reviews.listRecords(identity(), context.patientId());
        assertThat(listed).extracting(OphthalmologyQcReviewWire::reviewId).contains(recorded.reviewId());
    }

    @Test
    void givenFailReviewWithDefect_whenRecording_thenAccepted() {
        Context context = seedContext();
        OphthalmologyQcReviewWire recorded = record(context, OphthalmologyQcReviewCreateRequestWire.ReviewConclusionValue.FAIL,
                "术眼核对缺失");
        assertThat(recorded.reviewConclusion()).isEqualTo(OphthalmologyQcReviewWire.ReviewConclusionValue.FAIL);
        assertThat(recorded.defectDescription()).isEqualTo("术眼核对缺失");
        assertThat(jdbc.sql("""
                select count(*) from clinical_task
                where tenant_id = cast(:tenant as uuid) and source_type = 'SPECIALTY_QC_REVIEW'
                  and source_id = :review and task_type = 'QC_REMEDIATION' and state = 'PENDING'
                """).param("tenant", TENANT).param("review", recorded.reviewId()).query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void givenFailReviewWithoutDefect_whenRecording_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> record(context, OphthalmologyQcReviewCreateRequestWire.ReviewConclusionValue.FAIL, null))
                .isInstanceOf(OphthalmologyQcReviewException.class)
                .satisfies(e -> assertThat(((OphthalmologyQcReviewException) e).code())
                        .isEqualTo("OPHTHALMOLOGY_QC_DEFECT_REQUIRED"));
    }

    @Test
    void givenARecordOutsideThePatientContext_whenRecording_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> reviews.record(identity(), "qc-" + UUID.randomUUID(),
                new OphthalmologyQcReviewCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(),
                        OphthalmologyQcReviewCreateRequestWire.ReviewedRecordTypeValue.PREOP_VERIFICATION,
                        UUID.randomUUID(), OphthalmologyQcReviewCreateRequestWire.ReviewConclusionValue.PASS,
                        null, Instant.now())))
                .isInstanceOf(OphthalmologyQcReviewException.class)
                .satisfies(error -> assertThat(((OphthalmologyQcReviewException) error).code())
                        .isEqualTo("OPHTHALMOLOGY_QC_REVIEWED_RECORD_NOT_FOUND"));
    }

    @Test
    void givenReview_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        OphthalmologyQcReviewWire recorded = record(context, OphthalmologyQcReviewCreateRequestWire.ReviewConclusionValue.PASS, null);
        assertThatThrownBy(() -> jdbc.sql("""
                update ophthalmology_qc_review set review_conclusion = 'FAIL'
                where tenant_id = cast(:tenant as uuid) and review_id = :review
                """).param("tenant", TENANT).param("review", recorded.reviewId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId, UUID reviewedRecordId) {}
}
