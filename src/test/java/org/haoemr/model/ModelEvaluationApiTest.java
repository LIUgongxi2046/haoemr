package org.haoemr.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ModelEvaluationRecordRequestWire;
import org.haoemr.contracts.ModelEvaluationWire;
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
final class ModelEvaluationApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private ModelEvaluationService evaluations;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private UUID seedModelDeployment() {
        UUID deploymentId = UUID.randomUUID();
        jdbc.sql("""
                insert into model_deployment(
                  tenant_id, model_deployment_id, model_code, provider_code, display_name,
                  residency_policy, status, evaluation_status)
                values (cast(:tenant as uuid), :deployment, :code, 'DEEPSEEK', '合成评估模型',
                  'ON_PREM_ONLY', 'ACTIVE', 'APPROVED')
                """).param("tenant", TENANT).param("deployment", deploymentId)
                .param("code", "MODEL-" + UUID.randomUUID().toString().substring(0, 8)).update();
        return deploymentId;
    }

    private ModelEvaluationWire record(UUID deploymentId, double score, double threshold) {
        return evaluations.record(identity(), "eval-" + UUID.randomUUID(),
                new ModelEvaluationRecordRequestWire(organization, facility, deploymentId,
                        "临床摘要准确率", score, threshold, Instant.now()));
    }

    @Test
    void givenPassingScore_whenRecording_thenPassed() {
        UUID deploymentId = seedModelDeployment();
        ModelEvaluationWire recorded = record(deploymentId, 0.9, 0.8);
        assertThat(recorded.status()).isEqualTo(ModelEvaluationWire.StatusValue.PASSED);
        assertThat(evaluationStatus(deploymentId)).isEqualTo("EVALUATING");

        List<ModelEvaluationWire> listed = evaluations.listEvaluations(identity(), deploymentId);
        assertThat(listed).extracting(ModelEvaluationWire::modelEvaluationId).contains(recorded.modelEvaluationId());
    }

    @Test
    void givenPassingManualScore_whenRecording_thenDoesNotAutoApproveDeployment() {
        UUID deploymentId = seedModelDeployment();

        record(deploymentId, 0.99, 0.80);

        assertThat(evaluationStatus(deploymentId))
                .as("A manually entered score is evidence only and must never publish a model by itself")
                .isEqualTo("EVALUATING");
    }

    @Test
    void givenFailingScore_whenRecording_thenFailed() {
        UUID deploymentId = seedModelDeployment();
        ModelEvaluationWire recorded = record(deploymentId, 0.5, 0.8);
        assertThat(recorded.status()).isEqualTo(ModelEvaluationWire.StatusValue.FAILED);
        assertThat(evaluationStatus(deploymentId)).isEqualTo("REJECTED");
    }

    private String evaluationStatus(UUID deploymentId) {
        return jdbc.sql("""
                select evaluation_status from model_deployment
                where tenant_id = cast(:tenant as uuid) and model_deployment_id = :deployment
                """).param("tenant", TENANT).param("deployment", deploymentId).query(String.class).single();
    }

    @Test
    void givenOutOfRangeScore_whenRecording_thenRejected() {
        UUID deploymentId = seedModelDeployment();
        assertThatThrownBy(() -> record(deploymentId, 1.5, 0.8))
                .isInstanceOf(ModelEvaluationException.class)
                .satisfies(e -> assertThat(((ModelEvaluationException) e).code())
                        .isEqualTo("MODEL_EVALUATION_REQUEST_INVALID"));
    }

    @Test
    void givenEvaluation_whenTampered_thenDatabaseRejectsMutation() {
        UUID deploymentId = seedModelDeployment();
        ModelEvaluationWire recorded = record(deploymentId, 0.9, 0.8);
        assertThatThrownBy(() -> jdbc.sql("""
                update model_evaluation set score = 0.1
                where tenant_id = cast(:tenant as uuid) and model_evaluation_id = :evaluation
                """).param("tenant", TENANT).param("evaluation", recorded.modelEvaluationId()).update())
                .isInstanceOf(DataAccessException.class);
    }
}
