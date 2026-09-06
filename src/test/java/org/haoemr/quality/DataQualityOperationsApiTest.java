package org.haoemr.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.DataQualityFindingTransitionRequestWire;
import org.haoemr.contracts.DataQualityFindingWire;
import org.haoemr.contracts.DataQualityScanRunWire;
import org.haoemr.contracts.DataQualityTriageAdviceWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "haoemr.data-quality.scheduler-enabled=false")
@ActiveProfiles("dev-synthetic")
@Transactional
final class DataQualityOperationsApiTest {
    private static final UUID TENANT = UUID.fromString("018f0000-0000-7000-8000-00000000aa01");
    private static final UUID ORGANIZATION = UUID.fromString("018f0000-0000-7000-8000-00000000aa02");
    private static final UUID FACILITY = UUID.fromString("018f0000-0000-7000-8000-00000000aa03");
    private static final UUID USER = UUID.fromString("018f0000-0000-7000-8000-00000000aa04");
    private static final UUID ROLE = UUID.fromString("018f0000-0000-7000-8000-00000000aa05");

    @Autowired private DataQualityOperationsService operations;
    @Autowired private JdbcClient jdbc;

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(TENANT, USER, List.of(ROLE));
    }

    @Test
    void givenMismatchedOrderContext_whenScanning_thenFindingDrivesAuditedLifecycleAndTriage() {
        UUID ruleId = jdbc.sql("""
                select data_quality_rule_id from data_quality_rule
                where tenant_id = :tenant and rule_code = 'DQ-ORDER-PATIENT'
                """).param("tenant", TENANT).query(UUID.class).single();
        UUID encounterId = jdbc.sql("""
                select encounter_id from encounter
                where tenant_id = :tenant and facility_id = :facility limit 1
                """).param("tenant", TENANT).param("facility", FACILITY).query(UUID.class).single();
        UUID encounterPatient = jdbc.sql("""
                select patient_id from encounter where tenant_id = :tenant and encounter_id = :encounter
                """).param("tenant", TENANT).param("encounter", encounterId).query(UUID.class).single();
        UUID differentPatient = jdbc.sql("""
                select patient_id from patient where tenant_id = :tenant and patient_id <> :patient limit 1
                """).param("tenant", TENANT).param("patient", encounterPatient).query(UUID.class).single();
        UUID orderId = UUID.randomUUID();
        jdbc.sql("""
                insert into clinical_order(
                  tenant_id, order_id, patient_id, encounter_id, facility_id, order_scope,
                  status, clinical_indication, author_user_id)
                values (:tenant, :order, :patient, :encounter, :facility, 'TEMPORARY',
                  'DRAFT', '数据质量回归不一致医嘱', :actor)
                """).param("tenant", TENANT).param("order", orderId).param("patient", differentPatient)
                .param("encounter", encounterId).param("facility", FACILITY).param("actor", USER).update();

        DataQualityScanRunWire scan = operations.scan(
                identity(), "scan-" + UUID.randomUUID(), FACILITY, ruleId);

        assertThat(scan.status()).isEqualTo(DataQualityScanRunWire.StatusValue.COMPLETED);
        assertThat(scan.initiatorKind()).isEqualTo(DataQualityScanRunWire.InitiatorKindValue.MANUAL);
        assertThat(scan.startedBy()).isEqualTo(USER);
        assertThat(scan.totalCount()).isPositive();
        assertThat(scan.failedCount()).isPositive();
        assertThat(scan.score()).isBetween(0.0, 1.0);
        List<DataQualityFindingWire> findings = operations.listFindings(identity(), FACILITY, scan.dataQualityScanId());
        DataQualityFindingWire finding = findings.stream()
                .filter(item -> item.targetEntityId().equals(orderId)).findFirst().orElseThrow();
        assertThat(operations.listFindings(identity(), FACILITY, null))
                .extracting(DataQualityFindingWire::dataQualityFindingId)
                .contains(finding.dataQualityFindingId());
        assertThat(finding.reasonCode()).isEqualTo("ORDER_CONTEXT_MISMATCH");
        assertThat(finding.reasonDetail()).doesNotContain("数据质量回归不一致医嘱");

        finding = transition(finding, DataQualityFindingTransitionRequestWire.ActionValue.ASSIGN, "分派数据治理人员核对");
        assertThat(finding.status()).isEqualTo(DataQualityFindingWire.StatusValue.ASSIGNED);
        finding = transition(finding, DataQualityFindingTransitionRequestWire.ActionValue.REMEDIATE,
                "已校正医嘱与就诊患者绑定");
        DataQualityFindingWire remediated = finding;
        assertThatThrownBy(() -> transition(remediated,
                DataQualityFindingTransitionRequestWire.ActionValue.VERIFY, "同一整改人员尝试自行复核"))
                .isInstanceOf(DataQualityOperationsException.class)
                .satisfies(error -> assertThat(((DataQualityOperationsException) error).code())
                        .isEqualTo("DATA_QUALITY_FINDING_SOD_VIOLATION"));
        UUID verifierUser = jdbc.sql("""
                select app.user_id from app_user app join role_assignment role
                  on role.tenant_id = app.tenant_id and role.user_id = app.user_id
                where app.tenant_id = :tenant and app.user_id <> :actor and app.status = 'ACTIVE'
                  and role.organization_id = :organization and role.status = 'ACTIVE'
                  and (role.facility_id is null or role.facility_id = :facility)
                limit 1
                """).param("tenant", TENANT).param("actor", USER).param("organization", ORGANIZATION)
                .param("facility", FACILITY).query(UUID.class).single();
        ClinicalIdentity verifier = new ClinicalIdentity(TENANT, verifierUser, List.of(ROLE));
        finding = transition(verifier, finding, DataQualityFindingTransitionRequestWire.ActionValue.VERIFY,
                "由第二名治理人员复核通过");
        finding = transition(verifier, finding, DataQualityFindingTransitionRequestWire.ActionValue.CLOSE,
                "证据完整，关闭问题工单");
        assertThat(finding.status()).isEqualTo(DataQualityFindingWire.StatusValue.CLOSED);

        DataQualityTriageAdviceWire advice = operations.createTriageAdvice(
                identity(), "triage-" + UUID.randomUUID(), scan.dataQualityScanId());
        assertThat(advice.engineKind())
                .isEqualTo(DataQualityTriageAdviceWire.EngineKindValue.DETERMINISTIC_RULE_BASED);
        assertThat(advice.findingCount()).isEqualTo(scan.failedCount());
        assertThat(advice.prioritizedActions()).isNotEmpty();
        assertThat(advice.evidenceHash()).matches("[0-9a-f]{64}");
    }

    @Test
    void givenUnregisteredScanner_whenStartingScan_thenFailClosedWithoutInventedScore() {
        UUID ruleId = UUID.randomUUID();
        jdbc.sql("""
                insert into data_quality_rule(
                  tenant_id, data_quality_rule_id, rule_code, rule_name, dimension,
                  target_entity, threshold, severity, status)
                values (:tenant, :rule, :code, '未注册扫描器规则', 'VALIDITY',
                  'UnsupportedEntity', 1.0, 'BLOCKING', 'ACTIVE')
                """).param("tenant", TENANT).param("rule", ruleId)
                .param("code", "DQ-UNSUPPORTED-" + UUID.randomUUID()).update();

        assertThatThrownBy(() -> operations.scan(
                identity(), "scan-" + UUID.randomUUID(), FACILITY, ruleId))
                .isInstanceOf(DataQualityOperationsException.class)
                .satisfies(error -> assertThat(((DataQualityOperationsException) error).code())
                        .isEqualTo("DATA_QUALITY_RULE_SCANNER_UNAVAILABLE"));
        Integer scans = jdbc.sql("""
                select count(*) from data_quality_scan_run
                where tenant_id = :tenant and data_quality_rule_id = :rule
                """).param("tenant", TENANT).param("rule", ruleId).query(Integer.class).single();
        assertThat(scans).isZero();
    }

    @Test
    void givenActiveAllowlistedRules_whenScheduledSweepRuns_thenSystemScansAreIdempotentAndNotHumanAttributed() {
        int completed = operations.sweepScheduledScans();
        assertThat(completed).isPositive();

        UUID orderRule = jdbc.sql("""
                select data_quality_rule_id from data_quality_rule
                where tenant_id = :tenant and rule_code = 'DQ-ORDER-PATIENT'
                """).param("tenant", TENANT).query(UUID.class).single();
        DataQualityScanRunWire scheduledScan = operations.listScans(identity(), orderRule).stream()
                .filter(scan -> scan.initiatorKind() == DataQualityScanRunWire.InitiatorKindValue.SCHEDULED)
                .findFirst().orElseThrow();

        assertThat(scheduledScan.startedBy()).isNull();
        Integer systemAuditEvents = jdbc.sql("""
                select count(*) from audit_event
                where tenant_id = :tenant and resource_id = :scan
                  and action_code = 'DATA_QUALITY_SCAN_COMPLETED' and actor_user_id is null
                """).param("tenant", TENANT).param("scan", scheduledScan.dataQualityScanId())
                .query(Integer.class).single();
        assertThat(systemAuditEvents).isEqualTo(1);
        assertThat(operations.sweepScheduledScans()).isZero();
    }

    private DataQualityFindingWire transition(
            DataQualityFindingWire finding,
            DataQualityFindingTransitionRequestWire.ActionValue action,
            String note) {
        return transition(identity(), finding, action, note);
    }

    private DataQualityFindingWire transition(
            ClinicalIdentity actor,
            DataQualityFindingWire finding,
            DataQualityFindingTransitionRequestWire.ActionValue action,
            String note) {
        return operations.transition(actor, "finding-" + UUID.randomUUID(), finding.dataQualityFindingId(),
                new DataQualityFindingTransitionRequestWire(
                        ORGANIZATION, FACILITY, action, null, note, finding.rowVersion()));
    }
}
