package org.haoemr.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.haoemr.model.ModelDataProcessingApprovalService.ApprovalCommand;
import org.haoemr.model.ModelDataProcessingApprovalService.ApprovalView;
import org.haoemr.contracts.ModelDeploymentDeactivateRequestWire;
import org.haoemr.contracts.ModelDeploymentConnectionTestRequestWire;
import org.haoemr.contracts.ModelDeploymentPublishRequestWire;
import org.haoemr.contracts.ModelDeploymentRegisterRequestWire;
import org.haoemr.contracts.ModelDeploymentUpdateRequestWire;
import org.haoemr.contracts.ModelDeploymentWire;
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
final class ModelDeploymentApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private ModelDeploymentService models;

    @Autowired
    private ModelDataProcessingApprovalService processingApprovals;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    @Test
    void givenModel_whenRegisteringListingAndDeactivating_thenLifecycleRecorded() {
        String modelCode = "MODEL-" + UUID.randomUUID();
        ModelDeploymentWire registered = models.register(identity(), "model-" + UUID.randomUUID(),
                new ModelDeploymentRegisterRequestWire(organization, facility, modelCode,
                        "LOCAL-INFER", "本地推理模型", ModelDeploymentRegisterRequestWire.ResidencyPolicyValue.ON_PREM_ONLY,
                        null, null, null));
        assertThat(registered.status()).isEqualTo(ModelDeploymentWire.StatusValue.ACTIVE);
        assertThat(registered.residencyPolicy()).isEqualTo(ModelDeploymentWire.ResidencyPolicyValue.ON_PREM_ONLY);

        List<ModelDeploymentWire> listed = models.list(identity());
        assertThat(listed).extracting(ModelDeploymentWire::modelCode).contains(modelCode);

        ModelDeploymentWire deactivated = models.deactivate(identity(), "deact-" + UUID.randomUUID(),
                registered.modelDeploymentId(), new ModelDeploymentDeactivateRequestWire(
                        organization, facility, registered.rowVersion()));
        assertThat(deactivated.status()).isEqualTo(ModelDeploymentWire.StatusValue.INACTIVE);
    }

    @Test
    void givenInactiveModel_whenPurging_thenPermanentlyRemoved() {
        ModelDeploymentWire registered = models.register(identity(), "model-" + UUID.randomUUID(),
                new ModelDeploymentRegisterRequestWire(organization, facility, "MODEL-" + UUID.randomUUID(),
                        "LOCAL-INFER", "待移除模型", ModelDeploymentRegisterRequestWire.ResidencyPolicyValue.ON_PREM_ONLY,
                        null, null, null));
        ModelDeploymentWire deactivated = models.deactivate(identity(), "deact-" + UUID.randomUUID(),
                registered.modelDeploymentId(), new ModelDeploymentDeactivateRequestWire(
                        organization, facility, registered.rowVersion()));
        assertThat(deactivated.status()).isEqualTo(ModelDeploymentWire.StatusValue.INACTIVE);

        models.purge(identity(), "purge-" + UUID.randomUUID(), deactivated.modelDeploymentId(),
                new ModelDeploymentDeactivateRequestWire(organization, facility, deactivated.rowVersion()));

        assertThat(models.list(identity())).extracting(ModelDeploymentWire::modelDeploymentId)
                .doesNotContain(deactivated.modelDeploymentId());
    }

    @Test
    void givenNullModelCode_whenRegistering_thenAutoGeneratesUniqueCode() {
        ModelDeploymentWire registered = models.register(identity(), "model-" + UUID.randomUUID(),
                new ModelDeploymentRegisterRequestWire(organization, facility, null,
                        "LOCAL-INFER", "自动编号模型", ModelDeploymentRegisterRequestWire.ResidencyPolicyValue.ON_PREM_ONLY,
                        null, null, null));
        assertThat(registered.modelCode()).startsWith("model-");
        assertThat(registered.modelCode()).hasSize(18);
    }

    @Test
    void givenReadyConnection_whenPublishing_thenApprovedForEvaRouting() {
        String modelCode = "MODEL-" + UUID.randomUUID();
        ModelDeploymentWire registered = models.register(identity(), "model-" + UUID.randomUUID(),
                new ModelDeploymentRegisterRequestWire(organization, facility, modelCode,
                        "LOCAL-INFER", "发布测试模型", ModelDeploymentRegisterRequestWire.ResidencyPolicyValue.ON_PREM_ONLY,
                        "https://gateway.test/v1", "env://TEST_MODEL_API_KEY", null));
        assertThat(registered.evaluationStatus()).isEqualTo(ModelDeploymentWire.EvaluationStatusValue.EVALUATING);

        jdbc.sql("""
                update model_deployment set connection_status = 'READY'
                where tenant_id = cast(:tenant as uuid) and model_deployment_id = :deployment
                """).param("tenant", TENANT).param("deployment", registered.modelDeploymentId()).update();
        jdbc.sql("""
                insert into model_evaluation(tenant_id, model_evaluation_id, model_deployment_id, eval_name,
                  score, threshold, status, evaluated_at, evaluated_by)
                values (:tenant, :evaluation, :deployment, '独立发布评测', 0.95, 0.80, 'PASSED', now(), :reviewer)
                """).param("tenant", tenant).param("evaluation", UUID.randomUUID())
                .param("deployment", registered.modelDeploymentId())
                .param("reviewer", UUID.fromString("018f0000-0000-7000-8000-00000000aa06")).update();

        ModelDeploymentWire published = models.publish(identity(), "publish-" + UUID.randomUUID(),
                registered.modelDeploymentId(), new ModelDeploymentPublishRequestWire(
                        organization, facility, registered.rowVersion()));
        assertThat(published.evaluationStatus()).isEqualTo(ModelDeploymentWire.EvaluationStatusValue.APPROVED);
    }

    @Test
    void givenReadyConnectionWithoutEvaluation_whenPublishing_thenApproved() {
        ModelDeploymentWire registered = models.register(identity(), "model-" + UUID.randomUUID(),
                new ModelDeploymentRegisterRequestWire(organization, facility, "MODEL-" + UUID.randomUUID(),
                        "LOCAL-INFER", "连接通过即可发布模型", ModelDeploymentRegisterRequestWire.ResidencyPolicyValue.ON_PREM_ONLY,
                        "https://gateway.test/v1", "env://TEST_MODEL_API_KEY", null));
        jdbc.sql("update model_deployment set connection_status = 'READY' where tenant_id = :tenant and model_deployment_id = :deployment")
                .param("tenant", tenant).param("deployment", registered.modelDeploymentId()).update();

        ModelDeploymentWire published = models.publish(identity(), "publish-" + UUID.randomUUID(),
                registered.modelDeploymentId(), new ModelDeploymentPublishRequestWire(
                        organization, facility, registered.rowVersion()));
        assertThat(published.evaluationStatus()).isEqualTo(ModelDeploymentWire.EvaluationStatusValue.APPROVED);
    }

    @Test
    void givenTestOnlyDeployment_whenPublishing_thenRuntimeReleaseIsRejected() {
        ModelDeploymentWire registered = models.register(identity(), "model-" + UUID.randomUUID(),
                new ModelDeploymentRegisterRequestWire(organization, facility, "MODEL-" + UUID.randomUUID(),
                        "DEEPSEEK", "仅连接测试模型", ModelDeploymentRegisterRequestWire.ResidencyPolicyValue.LOCAL_PREFERRED,
                        "https://api.deepseek.com", "env://TEST_MODEL_API_KEY", null));
        jdbc.sql("""
                update model_deployment set test_only = true, connection_status = 'READY'
                where tenant_id = :tenant and model_deployment_id = :deployment
                """).param("tenant", tenant).param("deployment", registered.modelDeploymentId()).update();
        assertThatThrownBy(() -> models.publish(identity(), "publish-" + UUID.randomUUID(),
                registered.modelDeploymentId(), new ModelDeploymentPublishRequestWire(
                        organization, facility, registered.rowVersion())))
                .isInstanceOf(ModelDeploymentException.class)
                .satisfies(error -> assertThat(((ModelDeploymentException) error).code())
                        .isEqualTo("MODEL_DEPLOYMENT_TEST_ONLY"));
    }

    @Test
    void givenDuplicateModelCode_whenRegistering_thenCodeExistsConflict() {
        String modelCode = "MODEL-" + UUID.randomUUID();
        models.register(identity(), "model-" + UUID.randomUUID(), new ModelDeploymentRegisterRequestWire(
                organization, facility, modelCode, "PROV-A", "模型A",
                ModelDeploymentRegisterRequestWire.ResidencyPolicyValue.ON_PREM_ONLY, null, null, null));
        assertThatThrownBy(() -> models.register(identity(), "model-" + UUID.randomUUID(),
                new ModelDeploymentRegisterRequestWire(organization, facility, modelCode, "PROV-B", "模型B",
                        ModelDeploymentRegisterRequestWire.ResidencyPolicyValue.ON_PREM_ONLY, null, null, null)))
                .isInstanceOf(ModelDeploymentException.class)
                .satisfies(error -> assertThat(((ModelDeploymentException) error).code())
                        .isEqualTo("MODEL_DEPLOYMENT_CODE_EXISTS"));
    }

    @Test
    void givenModelCode_whenTampered_thenDatabaseRejectsMutation() {
        String modelCode = "MODEL-" + UUID.randomUUID();
        ModelDeploymentWire registered = models.register(identity(), "model-" + UUID.randomUUID(),
                new ModelDeploymentRegisterRequestWire(organization, facility, modelCode, "PROV-A", "模型A",
                        ModelDeploymentRegisterRequestWire.ResidencyPolicyValue.ON_PREM_ONLY, null, null, null));
        assertThatThrownBy(() -> jdbc.sql("""
                update model_deployment set model_code = 'TAMPERED'
                where tenant_id = cast(:tenant as uuid) and model_deployment_id = :deployment
                """).param("tenant", TENANT).param("deployment", registered.modelDeploymentId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void givenApiConfiguration_whenRegistering_thenOnlyAMaskedCredentialHintIsReturned() {
        ModelDeploymentWire registered = models.register(identity(), "model-api-" + UUID.randomUUID(),
                new ModelDeploymentRegisterRequestWire(organization, facility, "DEEPSEEK-" + UUID.randomUUID(), "DEEPSEEK",
                        "DeepSeek 医疗模型", ModelDeploymentRegisterRequestWire.ResidencyPolicyValue.LOCAL_PREFERRED,
                        "https://api.deepseek.com/v1", "env://TEST_DEEPSEEK_API_KEY", null));

        assertThat(registered.connectionStatus()).isEqualTo(ModelDeploymentWire.ConnectionStatusValue.UNVERIFIED);
        assertThat(registered.credentialConfigured()).isTrue();
        assertThat(registered.credentialHint()).isEqualTo("环境变量 · TEST_DEEPSEEK_API_KEY");
        assertThat(registered.lastConnectionTestedAt()).isNull();
        assertThat(registered.toString()).doesNotContain("sk-");
    }

    @Test
    void givenPlainApiKey_whenRegistering_thenSecretIsManagedAndOnlySuffixIsReturned() {
        String rawApiKey = "test-managed-secret-ABCD";
        ModelDeploymentWire registered = models.register(identity(), "model-managed-" + UUID.randomUUID(),
                new ModelDeploymentRegisterRequestWire(organization, facility, "MODEL-" + UUID.randomUUID(),
                        "DEEPSEEK", "DeepSeek 托管密钥模型",
                        ModelDeploymentRegisterRequestWire.ResidencyPolicyValue.CLOUD_ALLOWED,
                        "https://api.deepseek.com", null, rawApiKey));

        String storedReference = jdbc.sql("""
                select api_key_ref from model_deployment
                where tenant_id = :tenant and model_deployment_id = :deployment
                """).param("tenant", tenant).param("deployment", registered.modelDeploymentId())
                .query(String.class).single();
        assertThat(storedReference).startsWith("file://").doesNotContain(rawApiKey);
        assertThat(registered.credentialHint()).isEqualTo("已配置 · ••••ABCD");
        assertThat(registered.toString()).doesNotContain(rawApiKey);
    }

    @Test
    void givenAnUnavailableSecret_whenTestingConnection_thenFailureIsRecordedInsteadOfClaimingReady() {
        ModelDeploymentWire registered = models.register(identity(), "model-api-" + UUID.randomUUID(),
                new ModelDeploymentRegisterRequestWire(organization, facility, "MODEL-" + UUID.randomUUID(),
                        "DEEPSEEK", "DeepSeek 连接验证模型",
                        ModelDeploymentRegisterRequestWire.ResidencyPolicyValue.LOCAL_PREFERRED,
                        "https://api.deepseek.com/v1", "env://HAOEMR_TEST_SECRET_THAT_MUST_NOT_EXIST", null));

        ModelDeploymentWire tested = models.testConnection(identity(), "model-test-" + UUID.randomUUID(),
                registered.modelDeploymentId(), new ModelDeploymentConnectionTestRequestWire(
                        organization, facility, registered.rowVersion()));

        assertThat(tested.connectionStatus()).isEqualTo(ModelDeploymentWire.ConnectionStatusValue.FAILED);
        assertThat(tested.lastConnectionTestedAt()).isNotNull();
        assertThat(tested.lastConnectionLatencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(tested.lastConnectionErrorCode()).isNotBlank();
    }

    @Test
    void givenActiveModel_whenUpdating_thenConfigurationChangesWithoutExposingCredential() {
        ModelDeploymentWire registered = models.register(identity(), "model-api-" + UUID.randomUUID(),
                new ModelDeploymentRegisterRequestWire(organization, facility, "MODEL-" + UUID.randomUUID(),
                        "DEEPSEEK", "DeepSeek 医疗模型",
                        ModelDeploymentRegisterRequestWire.ResidencyPolicyValue.LOCAL_PREFERRED,
                        "https://api.deepseek.com/v1", "env://TEST_DEEPSEEK_API_KEY", null));
        ModelDeploymentWire updated = models.update(identity(), "model-update-" + UUID.randomUUID(),
                registered.modelDeploymentId(), new ModelDeploymentUpdateRequestWire(
                        organization, facility, "DeepSeek 临床模型",
                        ModelDeploymentUpdateRequestWire.ResidencyPolicyValue.CLOUD_ALLOWED,
                        "https://api.deepseek.com/v1", null, null,
                        ModelDeploymentUpdateRequestWire.CredentialActionValue.KEEP, registered.rowVersion()));
        assertThat(updated.displayName()).isEqualTo("DeepSeek 临床模型");
        assertThat(updated.credentialConfigured()).isTrue();
        assertThat(updated.rowVersion()).isEqualTo(registered.rowVersion() + 1);
    }

    @Test
    void givenPlaintextCredentialOrHttpEndpoint_whenRegistering_thenConfigurationIsRejected() {
        assertThatThrownBy(() -> models.register(identity(), "model-secret-" + UUID.randomUUID(),
                new ModelDeploymentRegisterRequestWire(organization, facility, "deepseek-chat", "DEEPSEEK",
                        "不安全模型", ModelDeploymentRegisterRequestWire.ResidencyPolicyValue.LOCAL_PREFERRED,
                        "https://api.deepseek.com/v1", "sk-plaintext", null)))
                .isInstanceOf(ModelDeploymentException.class);
        assertThatThrownBy(() -> models.register(identity(), "model-http-" + UUID.randomUUID(),
                new ModelDeploymentRegisterRequestWire(organization, facility, "deepseek-chat", "DEEPSEEK",
                        "不安全地址", ModelDeploymentRegisterRequestWire.ResidencyPolicyValue.LOCAL_PREFERRED,
                        "http://api.example.test/v1", "env://TEST_DEEPSEEK_API_KEY", null)))
                .isInstanceOf(ModelDeploymentException.class);
    }

    @Test
    void givenCloudModel_whenApprovingAndRevokingExternalProcessing_thenLifecycleIsAudited() {
        ModelDeploymentWire registered = models.register(identity(), "model-cloud-" + UUID.randomUUID(),
                new ModelDeploymentRegisterRequestWire(organization, facility, "MODEL-" + UUID.randomUUID(),
                        "DEEPSEEK", "DeepSeek 云端诊疗模型",
                        ModelDeploymentRegisterRequestWire.ResidencyPolicyValue.CLOUD_ALLOWED,
                        "https://api.deepseek.com", null, "test-managed-secret-EFGH"));

        ApprovalView approved = processingApprovals.approve(identity(), "processing-" + UUID.randomUUID(),
                registered.modelDeploymentId(), new ApprovalCommand(
                        "医疗服务合同履行与院内诊疗辅助", "PIA-AI-TEST-001", "DPA-AI-TEST-001",
                        "中国境内合成测试端点", 0, List.of("RECORDS", "RESULTS"),
                        OffsetDateTime.now().plusDays(30)));

        assertThat(approved.status()).isEqualTo("ACTIVE");
        assertThat(approved.allowedContextScopes()).containsExactly("RECORDS", "RESULTS");
        assertThat(processingApprovals.list(identity(), registered.modelDeploymentId())).hasSize(1);

        ApprovalView revoked = processingApprovals.revoke(identity(), "processing-revoke-" + UUID.randomUUID(),
                registered.modelDeploymentId(), approved.approvalId(), approved.rowVersion(), "合成测试结束");
        assertThat(revoked.status()).isEqualTo("REVOKED");
        assertThat(revoked.revocationReason()).isEqualTo("合成测试结束");
        assertThat(jdbc.sql("""
                select count(*) from audit_event where tenant_id = :tenant
                  and resource_type = 'MODEL_PROCESSING_APPROVAL' and resource_id = :approval
                """).param("tenant", tenant).param("approval", approved.approvalId())
                .query(Long.class).single()).isEqualTo(2);
    }
}
