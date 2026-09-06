package org.haoemr.clinical;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.flyway.out-of-order=true")
@ActiveProfiles("dev-synthetic")
@Transactional
final class RecordCenterServiceTest {
    private static final UUID TENANT = UUID.fromString("018f0000-0000-7000-8000-00000000aa01");
    private static final UUID ORGANIZATION = UUID.fromString("018f0000-0000-7000-8000-00000000aa02");
    private static final UUID FACILITY = UUID.fromString("018f0000-0000-7000-8000-00000000aa03");
    private static final UUID CLINICIAN = UUID.fromString("018f0000-0000-7000-8000-00000000aa04");
    private static final UUID CLINICIAN_ROLE = UUID.fromString("018f0000-0000-7000-8000-00000000aa05");
    private static final UUID RECORDS_USER = UUID.fromString("018f0000-0000-7000-8000-00000000aa14");
    private static final UUID RECORDS_ROLE = UUID.fromString("018f0000-0000-7000-8000-00000000aa15");

    @Autowired
    private RecordCenterService records;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void hospitalWorklistUsesRoleScopeAndReviewCaseWritesDurableWorkflowEvidence() {
        ClinicalIdentity clinician = new ClinicalIdentity(TENANT, CLINICIAN, List.of(CLINICIAN_ROLE));
        ClinicalIdentity medicalRecords = new ClinicalIdentity(TENANT, RECORDS_USER, List.of(RECORDS_ROLE));

        List<RecordCenterService.WorklistItem> clinicianItems = records.worklist(
                clinician, ORGANIZATION, FACILITY, null, null);
        List<RecordCenterService.WorklistItem> hospitalItems = records.worklist(
                medicalRecords, ORGANIZATION, FACILITY, null, null);
        assertThat(clinicianItems).isNotEmpty();
        assertThat(hospitalItems.size()).isGreaterThanOrEqualTo(clinicianItems.size());
        assertThat(hospitalItems).extracting(RecordCenterService.WorklistItem::encounterType)
                .contains("OUTPATIENT", "INPATIENT");

        RecordCenterService.WorklistItem document = clinicianItems.stream()
                .filter(item -> item.reviewCaseId() == null && !"VOID".equals(item.status()))
                .findFirst().orElseThrow();
        RecordCenterService.ReviewCase created = records.createReviewCase(
                clinician, "record-review-create-" + UUID.randomUUID(), ORGANIZATION, FACILITY,
                new RecordCenterController.CreateReviewCase(
                        document.documentId(), document.documentVersionId(), "FOCUSED",
                        "针对病历完整性开展专项抽查", "HIGH", null, Instant.now().plusSeconds(7200)));

        assertThat(created.status()).isEqualTo("OPEN");
        assertThat(created.rowVersion()).isEqualTo(1L);
        assertThat(records.worklist(clinician, ORGANIZATION, FACILITY, null, null))
                .filteredOn(item -> item.documentId().equals(document.documentId()))
                .singleElement().satisfies(item -> {
                    assertThat(item.reviewCaseId()).isEqualTo(created.reviewCaseId());
                    assertThat(item.reviewStatus()).isEqualTo("OPEN");
                    assertThat(item.reviewPriority()).isEqualTo("HIGH");
                });
        assertThat(jdbc.sql("""
                select count(*) from record_review_case_event
                where tenant_id = :tenant and review_case_id = :case_id and event_type = 'CREATED'
                """).param("tenant", TENANT).param("case_id", created.reviewCaseId())
                .query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("""
                select count(*) from audit_event
                where tenant_id = :tenant and resource_id = :case_id
                  and resource_type = 'RECORD_REVIEW_CASE' and action_code = 'RECORD_REVIEW_CREATED'
                """).param("tenant", TENANT).param("case_id", created.reviewCaseId())
                .query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("""
                select count(*) from outbox_event
                where tenant_id = :tenant and aggregate_id = :case_id
                  and aggregate_type = 'RECORD_REVIEW_CASE'
                """).param("tenant", TENANT).param("case_id", created.reviewCaseId())
                .query(Long.class).single()).isEqualTo(1L);

        RecordCenterService.ReviewCase assigned = records.transitionReviewCase(
                clinician, "record-review-assign-" + UUID.randomUUID(), ORGANIZATION, FACILITY,
                created.reviewCaseId(), new RecordCenterController.TransitionReviewCase(
                        created.rowVersion(), "ASSIGNED", "分派给当前责任医师复核", CLINICIAN));
        assertThat(assigned.status()).isEqualTo("ASSIGNED");
        assertThat(assigned.rowVersion()).isEqualTo(2L);
        assertThat(assigned.assigneeUserId()).isEqualTo(CLINICIAN);

        RecordCenterService.ReviewCase reviewing = records.transitionReviewCase(
                clinician, "record-review-start-" + UUID.randomUUID(), ORGANIZATION, FACILITY,
                assigned.reviewCaseId(), new RecordCenterController.TransitionReviewCase(
                        assigned.rowVersion(), "IN_REVIEW", "开始逐项核对病历证据", null));
        assertThat(reviewing.status()).isEqualTo("IN_REVIEW");
        assertThat(reviewing.rowVersion()).isEqualTo(3L);
        assertThat(jdbc.sql("""
                select count(*) from record_review_case_event
                where tenant_id = :tenant and review_case_id = :case_id
                """).param("tenant", TENANT).param("case_id", created.reviewCaseId())
                .query(Long.class).single()).isEqualTo(3L);
    }
}
