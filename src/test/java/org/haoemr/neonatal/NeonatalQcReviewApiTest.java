package org.haoemr.neonatal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.NeonatalQcReviewCreateRequestWire;
import org.haoemr.contracts.NeonatalQcReviewWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class NeonatalQcReviewApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private NeonatalQcReviewService reviews;

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
                values (cast(:tenant as uuid), :patient, '合成neonatal质控患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1991, 1, 1)).update();
        jdbc.sql("""
                insert into encounter(tenant_id, encounter_id, patient_id, organization_id, facility_id,
                  encounter_type, status, started_at, source_system, source_key)
                values (cast(:tenant as uuid), :encounter, :patient, cast(:organization as uuid),
                  cast(:facility as uuid), 'OUTPATIENT', 'IN_PROGRESS', now(), 'SYNTHETIC-NEONATAL_QC-QC', :source_key)
                """).param("tenant", TENANT).param("encounter", encounterId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("source_key", UUID.randomUUID().toString()).update();
        UUID motherPatientId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成新生儿母亲', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", motherPatientId)
                .param("birth", LocalDate.of(1991, 1, 1)).update();
        UUID reviewedRecordId = UUID.randomUUID();
        jdbc.sql("""
                insert into neonatal_wristband_verification(
                  tenant_id, verification_id, patient_id, mother_patient_id, wristband_code, specimen_code,
                  verified_by, witnessed_by, verified_at)
                values (cast(:tenant as uuid), :record, :patient, :mother,
                  'NB-001', 'SP-001', cast(:user as uuid), '018f0000-0000-7000-8000-00000000aa06'::uuid, now())
                """).param("tenant", TENANT).param("record", reviewedRecordId).param("patient", patientId)
                .param("mother", motherPatientId)
                .param("user", USER).update();
        return new Context(patientId, encounterId, reviewedRecordId);
    }

    private NeonatalQcReviewWire record(
            Context context, NeonatalQcReviewCreateRequestWire.ReviewConclusionValue conclusion, String defect) {
        return reviews.record(identity(), "qc-" + UUID.randomUUID(),
                new NeonatalQcReviewCreateRequestWire(organization, facility, context.patientId(),
                        context.encounterId(), NeonatalQcReviewCreateRequestWire.ReviewedRecordTypeValue.WRISTBAND_VERIFICATION,
                        context.reviewedRecordId(), conclusion, defect, Instant.now()));
    }

    @Test
    void givenPassReview_whenRecording_thenRecorded() {
        Context context = seedContext();
        NeonatalQcReviewWire recorded = record(context, NeonatalQcReviewCreateRequestWire.ReviewConclusionValue.PASS, null);
        assertThat(recorded.reviewConclusion()).isEqualTo(NeonatalQcReviewWire.ReviewConclusionValue.PASS);
        assertThat(recorded.reviewedBy()).isEqualTo(UUID.fromString(USER));

        List<NeonatalQcReviewWire> listed = reviews.listRecords(identity(), context.patientId());
        assertThat(listed).extracting(NeonatalQcReviewWire::reviewId).contains(recorded.reviewId());
    }

    @Test
    void givenFailReviewWithDefect_whenRecording_thenAccepted() {
        Context context = seedContext();
        NeonatalQcReviewWire recorded = record(context, NeonatalQcReviewCreateRequestWire.ReviewConclusionValue.FAIL,
                "腕带标本核对缺失");
        assertThat(recorded.reviewConclusion()).isEqualTo(NeonatalQcReviewWire.ReviewConclusionValue.FAIL);
        assertThat(recorded.defectDescription()).isEqualTo("腕带标本核对缺失");
    }

    @Test
    void givenFailReviewWithoutDefect_whenRecording_thenRejected() {
        Context context = seedContext();
        assertThatThrownBy(() -> record(context, NeonatalQcReviewCreateRequestWire.ReviewConclusionValue.FAIL, null))
                .isInstanceOf(NeonatalQcReviewException.class)
                .satisfies(e -> assertThat(((NeonatalQcReviewException) e).code())
                        .isEqualTo("NEONATAL_QC_DEFECT_REQUIRED"));
    }

    @Test
    void givenReview_whenTampered_thenDatabaseRejectsMutation() {
        Context context = seedContext();
        NeonatalQcReviewWire recorded = record(context, NeonatalQcReviewCreateRequestWire.ReviewConclusionValue.PASS, null);
        assertThatThrownBy(() -> jdbc.sql("""
                update neonatal_qc_review set review_conclusion = 'FAIL'
                where tenant_id = cast(:tenant as uuid) and review_id = :review
                """).param("tenant", TENANT).param("review", recorded.reviewId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    private record Context(UUID patientId, UUID encounterId, UUID reviewedRecordId) {}
}
