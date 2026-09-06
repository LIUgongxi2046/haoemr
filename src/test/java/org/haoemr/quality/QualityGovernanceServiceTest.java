package org.haoemr.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.haoemr.contracts.QualityGovernanceAgentProposalRequestWire;
import org.haoemr.contracts.QualityGovernanceAgentProposalWire;
import org.haoemr.contracts.QualityGovernanceRecordCreateRequestWire;
import org.haoemr.contracts.QualityGovernanceRecordUpdateRequestWire;
import org.haoemr.contracts.QualityGovernanceRecordVoidRequestWire;
import org.haoemr.contracts.QualityGovernanceRecordWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("dev-synthetic")
@Transactional
final class QualityGovernanceServiceTest {
    private static final UUID TENANT = UUID.fromString("018f0000-0000-7000-8000-00000000aa01");
    private static final UUID ORGANIZATION = UUID.fromString("018f0000-0000-7000-8000-00000000aa02");
    private static final UUID FACILITY = UUID.fromString("018f0000-0000-7000-8000-00000000aa03");
    private static final UUID USER = UUID.fromString("018f0000-0000-7000-8000-00000000aa04");
    private static final UUID ROLE = UUID.fromString("018f0000-0000-7000-8000-00000000aa09");
    private static final UUID RECORDS_USER = UUID.fromString("018f0000-0000-7000-8000-00000000aa14");
    private static final UUID RECORDS_ROLE = UUID.fromString("018f0000-0000-7000-8000-00000000aa15");

    @Autowired private QualityGovernanceService governance;
    @Autowired private JdbcClient jdbc;

    private ClinicalIdentity identity() { return new ClinicalIdentity(TENANT, USER, List.of(ROLE)); }
    private ClinicalIdentity recordsIdentity() { return new ClinicalIdentity(TENANT, RECORDS_USER, List.of(RECORDS_ROLE)); }

    @Test
    void archiveAssetHasLevelsFiveToSevenAndMedicalRecordsOnlyCandidateAgent() {
        UUID patientId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (:tenant, :patient, '病案治理合成患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1970, 1, 1)).update();
        jdbc.sql("""
                insert into medical_record_asset(
                  tenant_id, medical_record_asset_id, organization_id, facility_id, patient_id,
                  asset_type, location, custody_location, content_hash, status, retention_years)
                values (:tenant, :asset, :organization, :facility, :patient,
                  'PAPER', '病案库-A01', '病案库-A01', :hash, 'ARCHIVED', 30)
                """).param("tenant", TENANT).param("asset", assetId).param("patient", patientId)
                .param("organization", ORGANIZATION).param("facility", FACILITY)
                .param("hash", "b".repeat(64)).update();

        QualityGovernanceRecordWire action = governance.createRecord(
                recordsIdentity(), "ARCHIVE_ASSET", assetId, "archive-action-" + UUID.randomUUID(),
                new QualityGovernanceRecordCreateRequestWire(
                        ORGANIZATION, FACILITY, QualityGovernanceRecordCreateRequestWire.RecordKindValue.ACTION,
                        "ARCHIVE-ACTION-001", "补齐病案原件验真证据", "病案科",
                        QualityGovernanceRecordCreateRequestWire.StatusValue.OPEN,
                        Instant.now().plus(1, ChronoUnit.DAYS), "重读对象存储原件并核对 SHA-256", null, null,
                        payload("医疗机构病历管理规定（2013年版）")));
        QualityGovernanceRecordWire evidence = governance.createRecord(
                recordsIdentity(), "ARCHIVE_ASSET", assetId, "archive-evidence-" + UUID.randomUUID(),
                new QualityGovernanceRecordCreateRequestWire(
                        ORGANIZATION, FACILITY, QualityGovernanceRecordCreateRequestWire.RecordKindValue.EVIDENCE,
                        "ARCHIVE-EVIDENCE-001", "原件完整性证据", "病案科复核岗",
                        QualityGovernanceRecordCreateRequestWire.StatusValue.READY,
                        null, "原件哈希与证据链节点", "archive://medical-record-assets/" + assetId,
                        "b".repeat(64), payload("医疗机构病历管理规定（2013年版）")));
        QualityGovernanceRecordWire review = governance.createRecord(
                recordsIdentity(), "ARCHIVE_ASSET", assetId, "archive-review-" + UUID.randomUUID(),
                new QualityGovernanceRecordCreateRequestWire(
                        ORGANIZATION, FACILITY, QualityGovernanceRecordCreateRequestWire.RecordKindValue.REVIEW,
                        "ARCHIVE-REVIEW-001", "病案归档独立复核", "病案管理组长",
                        QualityGovernanceRecordCreateRequestWire.StatusValue.READY,
                        null, "核对原件、保存期、调阅权限与 WORM 状态", null, null,
                        Map.of("schema_version", 1, "china_policy_basis", "院级病案归档制度",
                                "source_reference", "archive://medical-record-assets/" + assetId,
                                "decision_basis", "待原件验真证据通过后独立复核",
                                "human_confirmation_required", true, "agent_write_allowed", false)));

        assertThat(List.of(action.hierarchyLevel(), evidence.hierarchyLevel(), review.hierarchyLevel()))
                .containsExactly(5, 6, 7);
        QualityGovernanceAgentProposalWire proposal = governance.createAgentProposal(
                recordsIdentity(), "ARCHIVE_ASSET", assetId, "archive-agent-" + UUID.randomUUID(),
                new QualityGovernanceAgentProposalRequestWire(ORGANIZATION, FACILITY));
        assertThat(proposal.moduleCode()).isEqualTo(QualityGovernanceAgentProposalWire.ModuleCodeValue.ARCHIVE_ASSET);
        assertThat(proposal.prioritizedActions()).anyMatch(value -> value.contains("病案保存年限"));
        assertThat(proposal.humanReviewState())
                .isEqualTo(QualityGovernanceAgentProposalWire.HumanReviewStateValue.PENDING);

        assertThatThrownBy(() -> governance.createRecord(
                identity(), "ARCHIVE_ASSET", assetId, "archive-admin-denied-" + UUID.randomUUID(),
                new QualityGovernanceRecordCreateRequestWire(
                        ORGANIZATION, FACILITY, QualityGovernanceRecordCreateRequestWire.RecordKindValue.ACTION,
                        "ARCHIVE-ACTION-002", "无病案角色写入", "系统管理员",
                        QualityGovernanceRecordCreateRequestWire.StatusValue.OPEN, null,
                        "通用管理员不得代替病案人员建立治理事实", null, null,
                        payload("院级病案归档制度"))))
                .isInstanceOf(QualityGovernanceException.class)
                .satisfies(error -> assertThat(((QualityGovernanceException) error).code())
                        .isEqualTo("QUALITY_GOVERNANCE_AUTHOR_REQUIRED"));
    }

    @Test
    void qualityDepthRecordsHaveCrudVersionAuditOutboxAndCandidateOnlyAgent() {
        UUID parentId = seedParent("QUALITY_INITIATIVE");
        QualityGovernanceRecordWire action = governance.createRecord(
                identity(), "QUALITY_CENTER", parentId, "quality-action-" + UUID.randomUUID(),
                new QualityGovernanceRecordCreateRequestWire(
                        ORGANIZATION, FACILITY,
                        QualityGovernanceRecordCreateRequestWire.RecordKindValue.ACTION,
                        "QI-ACTION-001", "整改危急值闭环逾期", "医务处 / 心内科",
                        QualityGovernanceRecordCreateRequestWire.StatusValue.OPEN,
                        Instant.now().minus(1, ChronoUnit.HOURS), "核对危急值通知、接收与处置时间链",
                        null, null, payload("危急值报告制度")));

        assertThat(action.hierarchyLevel()).isEqualTo(5);
        assertThat(action.rowVersion()).isEqualTo(1);
        assertThat(governance.listRecords(identity(), ORGANIZATION, FACILITY,
                "QUALITY_CENTER", parentId, "ACTION")).hasSize(1);

        QualityGovernanceRecordWire updated = governance.updateRecord(
                identity(), "QUALITY_CENTER", parentId, action.qualityGovernanceRecordId(),
                "quality-update-" + UUID.randomUUID(),
                new QualityGovernanceRecordUpdateRequestWire(
                        ORGANIZATION, FACILITY, action.title(), action.owner(),
                        QualityGovernanceRecordUpdateRequestWire.StatusValue.IN_PROGRESS,
                        Instant.now().plus(1, ChronoUnit.DAYS), action.description(), null, null,
                        payload("危急值报告制度"), action.rowVersion()));
        assertThat(updated.status()).isEqualTo(QualityGovernanceRecordWire.StatusValue.IN_PROGRESS);
        assertThat(updated.rowVersion()).isEqualTo(2);

        assertThatThrownBy(() -> governance.updateRecord(
                identity(), "QUALITY_CENTER", parentId, action.qualityGovernanceRecordId(),
                "quality-stale-" + UUID.randomUUID(),
                new QualityGovernanceRecordUpdateRequestWire(
                        ORGANIZATION, FACILITY, action.title(), action.owner(),
                        QualityGovernanceRecordUpdateRequestWire.StatusValue.READY,
                        null, action.description(), null, null, payload("危急值报告制度"), 1L)))
                .isInstanceOf(QualityGovernanceException.class)
                .satisfies(error -> assertThat(((QualityGovernanceException) error).code())
                        .isEqualTo("QUALITY_GOVERNANCE_VERSION_CONFLICT"));

        assertThatThrownBy(() -> governance.createRecord(
                identity(), "QUALITY_CENTER", parentId, "quality-invalid-evidence-" + UUID.randomUUID(),
                new QualityGovernanceRecordCreateRequestWire(
                        ORGANIZATION, FACILITY,
                        QualityGovernanceRecordCreateRequestWire.RecordKindValue.EVIDENCE,
                        "QI-EVIDENCE-INVALID", "无指纹证据", "质控办",
                        QualityGovernanceRecordCreateRequestWire.StatusValue.READY,
                        null, "不应接受无法验证的证据声称", null, null,
                        payload("医疗质量管理办法"))))
                .isInstanceOf(QualityGovernanceException.class)
                .satisfies(error -> assertThat(((QualityGovernanceException) error).code())
                        .isEqualTo("QUALITY_GOVERNANCE_REQUEST_INVALID"));

        QualityGovernanceRecordWire evidence = governance.createRecord(
                identity(), "QUALITY_CENTER", parentId, "quality-evidence-" + UUID.randomUUID(),
                new QualityGovernanceRecordCreateRequestWire(
                        ORGANIZATION, FACILITY,
                        QualityGovernanceRecordCreateRequestWire.RecordKindValue.EVIDENCE,
                        "QI-EVIDENCE-001", "危急值闭环抽查证据", "质控办",
                        QualityGovernanceRecordCreateRequestWire.StatusValue.READY,
                        Instant.now().minus(1, ChronoUnit.HOURS), "待独立复核抽查结果与时间链", "document://quality/sample/001",
                        "a".repeat(64), payload("危急值报告制度")));
        assertThat(evidence.hierarchyLevel()).isEqualTo(6);

        QualityGovernanceAgentProposalWire proposal = governance.createAgentProposal(
                identity(), "QUALITY_CENTER", parentId, "quality-agent-" + UUID.randomUUID(),
                new QualityGovernanceAgentProposalRequestWire(ORGANIZATION, FACILITY));
        assertThat(proposal.modelPolicy()).isEqualTo("DETERMINISTIC_QUALITY_RULES_V1");
        assertThat(proposal.humanReviewState())
                .isEqualTo(QualityGovernanceAgentProposalWire.HumanReviewStateValue.PENDING);
        assertThat(proposal.evidenceWatermark()).matches("[0-9a-f]{64}");
        assertThat(proposal.prioritizedActions()).anyMatch(value -> value.contains("人工复核"));
        assertThat(jdbc.sql("""
                select count(*) from quality_governance_record
                where tenant_id = :tenant and parent_resource_id = :parent
                """).param("tenant", TENANT).param("parent", parentId).query(Long.class).single()).isEqualTo(2);

        QualityGovernanceRecordWire voided = governance.voidRecord(
                identity(), "QUALITY_CENTER", parentId, updated.qualityGovernanceRecordId(),
                "quality-void-" + UUID.randomUUID(),
                new QualityGovernanceRecordVoidRequestWire(
                        ORGANIZATION, FACILITY, updated.rowVersion(), "整改项已由新的院级工单替代，保留原始证据"));
        assertThat(voided.voidedAt()).isNotNull();
        assertThat(governance.listRecords(identity(), ORGANIZATION, FACILITY,
                "QUALITY_CENTER", parentId, "ACTION")).isEmpty();
        assertThat(jdbc.sql("""
                select count(*) from audit_event where tenant_id = :tenant
                  and resource_type = 'QUALITY_GOVERNANCE'
                  and resource_id in (:action, :evidence, :proposal)
                """).param("tenant", TENANT).param("action", action.qualityGovernanceRecordId())
                .param("evidence", evidence.qualityGovernanceRecordId())
                .param("proposal", proposal.qualityGovernanceAgentProposalId())
                .query(Long.class).single()).isEqualTo(5);
        assertThat(jdbc.sql("""
                select count(*) from outbox_event where tenant_id = :tenant
                  and aggregate_type = 'QUALITY_GOVERNANCE'
                  and aggregate_id in (:action, :evidence, :proposal)
                """).param("tenant", TENANT).param("action", action.qualityGovernanceRecordId())
                .param("evidence", evidence.qualityGovernanceRecordId())
                .param("proposal", proposal.qualityGovernanceAgentProposalId())
                .query(Long.class).single()).isEqualTo(5);
    }

    @Test
    void crossParentAndUnauthorizedWritesFailClosed() {
        UUID parentId = seedParent("DEPARTMENT_QC_CASE");
        assertThatThrownBy(() -> governance.listRecords(
                identity(), ORGANIZATION, FACILITY, "DEPARTMENT_QC", UUID.randomUUID(), null))
                .isInstanceOf(QualityGovernanceException.class)
                .satisfies(error -> assertThat(((QualityGovernanceException) error).code())
                        .isEqualTo("CONTEXT_NOT_PERMITTED"));

        ClinicalIdentity noRole = new ClinicalIdentity(TENANT, USER, List.of(UUID.randomUUID()));
        assertThatThrownBy(() -> governance.createRecord(
                noRole, "DEPARTMENT_QC", parentId, "quality-denied-" + UUID.randomUUID(),
                new QualityGovernanceRecordCreateRequestWire(
                        ORGANIZATION, FACILITY,
                        QualityGovernanceRecordCreateRequestWire.RecordKindValue.ACTION,
                        "DQC-ACTION-001", "科室整改", "心内科",
                        QualityGovernanceRecordCreateRequestWire.StatusValue.OPEN,
                        Instant.now().plus(1, ChronoUnit.DAYS), "无权用户不应能创建整改动作", null, null,
                        payload("病历管理制度"))))
                .isInstanceOf(QualityGovernanceException.class)
                .satisfies(error -> assertThat(((QualityGovernanceException) error).code())
                        .isEqualTo("QUALITY_GOVERNANCE_AUTHOR_REQUIRED"));
    }

    private UUID seedParent(String configType) {
        UUID parentId = UUID.randomUUID();
        jdbc.sql("""
                insert into config_item(
                  tenant_id, config_id, config_type, config_key, display_name, payload,
                  status, created_by)
                values (:tenant, :parent, :type, :key, '质量治理回归父项',
                  '{"schema_version":1}', 'DRAFT', :actor)
                """).param("tenant", TENANT).param("parent", parentId).param("type", configType)
                .param("key", "TEST-" + parentId).param("actor", USER).update();
        return parentId;
    }

    private Map<String, Object> payload(String policy) {
        return Map.of(
                "schema_version", 1,
                "china_policy_basis", policy,
                "source_reference", "config://quality-test",
                "human_confirmation_required", true,
                "agent_write_allowed", false);
    }
}
