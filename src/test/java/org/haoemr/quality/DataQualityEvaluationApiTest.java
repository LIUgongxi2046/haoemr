package org.haoemr.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.DataQualityEvaluationRecordRequestWire;
import org.haoemr.contracts.DataQualityEvaluationWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
@Transactional
final class DataQualityEvaluationApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private DataQualityEvaluationService evaluations;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private UUID seedRule(double threshold, String status) {
        UUID ruleId = UUID.randomUUID();
        jdbc.sql("""
                insert into data_quality_rule(
                  tenant_id, data_quality_rule_id, rule_code, rule_name, dimension,
                  target_entity, threshold, severity, status)
                values (cast(:tenant as uuid), :rule, :code, '完整性规则', 'COMPLETENESS',
                  'clinical_document', :threshold, 'WARNING', :status)
                """).param("tenant", TENANT).param("rule", ruleId)
                .param("code", "DQ-" + UUID.randomUUID().toString().substring(0, 8))
                .param("threshold", threshold).param("status", status).update();
        return ruleId;
    }

    private DataQualityEvaluationWire record(UUID ruleId, double measuredValue) {
        return evaluations.record(identity(), "dqe-" + UUID.randomUUID(),
                new DataQualityEvaluationRecordRequestWire(organization, facility, ruleId,
                        UUID.randomUUID(), measuredValue, Instant.now()));
    }

    @Test
    void givenMeasuredAboveThreshold_whenRecording_thenPassedWithRuleThreshold() {
        UUID ruleId = seedRule(0.95, "ACTIVE");
        DataQualityEvaluationWire recorded = record(ruleId, 0.98);
        assertThat(recorded.status()).isEqualTo(DataQualityEvaluationWire.StatusValue.PASSED);
        assertThat(recorded.threshold()).isEqualTo(0.95);
        assertThat(recorded.evaluatedBy()).isEqualTo(UUID.fromString(USER));

        List<DataQualityEvaluationWire> listed = evaluations.listEvaluations(identity(), ruleId);
        assertThat(listed).extracting(DataQualityEvaluationWire::dataQualityEvaluationId)
                .contains(recorded.dataQualityEvaluationId());
    }

    @Test
    void givenMeasuredBelowThreshold_whenRecording_thenFailed() {
        UUID ruleId = seedRule(0.95, "ACTIVE");
        DataQualityEvaluationWire recorded = record(ruleId, 0.80);
        assertThat(recorded.status()).isEqualTo(DataQualityEvaluationWire.StatusValue.FAILED);
    }

    @Test
    void givenInactiveRule_whenRecording_thenRejected() {
        UUID ruleId = seedRule(0.95, "INACTIVE");
        assertThatThrownBy(() -> record(ruleId, 0.98))
                .isInstanceOf(DataQualityEvaluationException.class)
                .satisfies(e -> assertThat(((DataQualityEvaluationException) e).code())
                        .isEqualTo("DATA_QUALITY_RULE_INACTIVE"));
    }

    @Test
    void givenOutOfRangeMeasured_whenRecording_thenRejected() {
        UUID ruleId = seedRule(0.95, "ACTIVE");
        assertThatThrownBy(() -> record(ruleId, 1.5))
                .isInstanceOf(DataQualityEvaluationException.class)
                .satisfies(e -> assertThat(((DataQualityEvaluationException) e).code())
                        .isEqualTo("DATA_QUALITY_EVALUATION_REQUEST_INVALID"));
    }

    @Test
    void givenInconsistentPassed_whenBypassingService_thenDatabaseRejects() {
        UUID ruleId = seedRule(0.95, "ACTIVE");
        assertThatThrownBy(() -> jdbc.sql("""
                insert into data_quality_evaluation(
                  tenant_id, data_quality_evaluation_id, data_quality_rule_id, target_entity_id,
                  measured_value, threshold, status, evaluated_at, evaluated_by)
                values (cast(:tenant as uuid), :evaluation, :rule, :target,
                  0.80, 0.95, 'PASSED', now(), cast(:user as uuid))
                """).param("tenant", TENANT).param("evaluation", UUID.randomUUID())
                .param("rule", ruleId).param("target", UUID.randomUUID()).param("user", USER).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void givenEvaluation_whenTampered_thenDatabaseRejectsMutation() {
        UUID ruleId = seedRule(0.95, "ACTIVE");
        DataQualityEvaluationWire recorded = record(ruleId, 0.98);
        assertThatThrownBy(() -> jdbc.sql("""
                update data_quality_evaluation set measured_value = 0.10
                where tenant_id = cast(:tenant as uuid) and data_quality_evaluation_id = :evaluation
                """).param("tenant", TENANT).param("evaluation", recorded.dataQualityEvaluationId()).update())
                .isInstanceOf(DataAccessException.class);
    }
}
