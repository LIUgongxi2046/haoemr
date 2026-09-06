package org.haoemr.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.haoemr.contracts.ConfigurationItemDefineRequestWire;
import org.haoemr.contracts.ConfigurationItemUpdateRequestWire;
import org.haoemr.contracts.ConfigurationItemWire;
import org.haoemr.contracts.ConfigurationLifecycleRequestWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
@Transactional
final class ConfigurationApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa18";
    private static final String ADMIN_ROLE = "018f0000-0000-7000-8000-00000000aa09";
    private static final String APPROVER = "018f0000-0000-7000-8000-00000000aa06";
    private static final String APPROVER_ROLE = "018f0000-0000-7000-8000-00000000aa19";

    @Autowired
    private ConfigurationService configurations;

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(UUID.fromString(TENANT), UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private ClinicalIdentity approver() {
        return new ClinicalIdentity(
                UUID.fromString(TENANT), UUID.fromString(APPROVER), List.of(UUID.fromString(APPROVER_ROLE)));
    }

    private ClinicalIdentity administrator() {
        return new ClinicalIdentity(
                UUID.fromString(TENANT), UUID.fromString(USER), List.of(UUID.fromString(ADMIN_ROLE)));
    }

    @Test
    void givenConfig_whenDefiningAndListing_thenDraftRecorded() {
        String type = "WORKFLOW";
        String key = "WF-" + UUID.randomUUID().toString().substring(0, 8);
        ConfigurationItemWire defined = configurations.define(identity(), "cfg-" + UUID.randomUUID(),
                new ConfigurationItemDefineRequestWire(type, key, "流程-" + key,
                        Map.of("nodes", List.of(Map.of("id", "start", "type", "Start")))));
        assertThat(defined.status()).isEqualTo(ConfigurationItemWire.StatusValue.DRAFT);
        assertThat(defined.configKey()).isEqualTo(key);

        List<ConfigurationItemWire> listed = configurations.list(identity(), type);
        assertThat(listed).extracting(ConfigurationItemWire::configId).contains(defined.configId());
    }

    @Test
    void givenDuplicateKey_whenDefining_thenRejected() {
        String type = "RULE";
        String key = "R-" + UUID.randomUUID().toString().substring(0, 8);
        configurations.define(identity(), "cfg-" + UUID.randomUUID(),
                new ConfigurationItemDefineRequestWire(type, key, "规则-" + key, Map.of("condition", "all")));
        assertThatThrownBy(() -> configurations.define(identity(), "cfg-" + UUID.randomUUID(),
                new ConfigurationItemDefineRequestWire(type, key, "重复规则", Map.of())))
                .isInstanceOf(ConfigurationException.class)
                .satisfies(e -> assertThat(((ConfigurationException) e).code()).isEqualTo("CONFIG_KEY_CONFLICT"));
    }

    @Test
    void givenStructuredWorkflow_whenUpdatingApprovingPublishingAndRollingBack_thenHistoryIsControlled() {
        String key = "WF-LIFE-" + UUID.randomUUID().toString().substring(0, 8);
        ConfigurationItemWire draft = configurations.define(identity(), "cfg-" + UUID.randomUUID(),
                new ConfigurationItemDefineRequestWire("WORKFLOW", key, "住院会诊流程",
                        workflowPayload("专家意见")));
        ConfigurationItemWire updated = configurations.update(identity(), draft.configId(), "cfg-" + UUID.randomUUID(),
                new ConfigurationItemUpdateRequestWire("住院会诊流程 v2", workflowPayload("专家复核"), draft.rowVersion()));
        ConfigurationItemWire validated = transition(identity(), updated,
                ConfigurationLifecycleRequestWire.ActionValue.VALIDATE, "运行流程静态校验");
        assertThat(validated.validationState()).isEqualTo(ConfigurationItemWire.ValidationStateValue.VALID);
        ConfigurationItemWire pending = transition(identity(), validated,
                ConfigurationLifecycleRequestWire.ActionValue.SUBMIT, "提交双人审批和发布");
        assertThat(pending.status()).isEqualTo(ConfigurationItemWire.StatusValue.PENDING_APPROVAL);
        assertThatThrownBy(() -> transition(identity(), pending,
                ConfigurationLifecycleRequestWire.ActionValue.APPROVE, "作者尝试批准自己配置"))
                .isInstanceOf(ConfigurationException.class)
                .satisfies(e -> assertThat(((ConfigurationException) e).code())
                        .isEqualTo("CONFIG_APPROVER_REQUIRED"));
        ConfigurationItemWire approved = transition(approver(), pending,
                ConfigurationLifecycleRequestWire.ActionValue.APPROVE, "独立审批人核对差异和证据");
        ConfigurationItemWire active = transition(approver(), approved,
                ConfigurationLifecycleRequestWire.ActionValue.PUBLISH, "校验通过后发布候选配置");
        assertThat(active.status()).isEqualTo(ConfigurationItemWire.StatusValue.ACTIVE);
        assertThat(active.publishedAt()).isNotNull();
        ConfigurationItemWire rolledBack = transition(approver(), active,
                ConfigurationLifecycleRequestWire.ActionValue.ROLLBACK, "演练回退到上一个有效载荷");
        assertThat(rolledBack.payload().get("nodes").toString()).contains("专家意见");
        assertThat(rolledBack.status()).isEqualTo(ConfigurationItemWire.StatusValue.ACTIVE);
    }

    @Test
    void givenActiveConfiguration_whenPublishingParallelDraft_thenOldVersionRemainsLiveUntilCutover() {
        String key = "WF-VERSION-" + UUID.randomUUID().toString().substring(0, 8);
        ConfigurationItemWire firstDraft = configurations.define(identity(), "cfg-" + UUID.randomUUID(),
                new ConfigurationItemDefineRequestWire("WORKFLOW", key, "设备接入流程 v1",
                        workflowPayload("首版复核")));
        ConfigurationItemWire firstActive = publish(firstDraft, "发布首版设备接入流程");

        ConfigurationItemWire secondDraft = configurations.define(identity(), "cfg-" + UUID.randomUUID(),
                new ConfigurationItemDefineRequestWire("WORKFLOW", key, "设备接入流程 v2",
                        workflowPayload("新版复核")));
        assertThat(secondDraft.status()).isEqualTo(ConfigurationItemWire.StatusValue.DRAFT);
        assertThat(configurations.list(identity(), "WORKFLOW").stream()
                .filter(item -> item.configKey().equals(key) && item.status() == ConfigurationItemWire.StatusValue.ACTIVE))
                .extracting(ConfigurationItemWire::configId).containsExactly(firstActive.configId());

        ConfigurationItemWire secondActive = publish(secondDraft, "发布新版设备接入流程");
        List<ConfigurationItemWire> versions = configurations.list(identity(), "WORKFLOW").stream()
                .filter(item -> item.configKey().equals(key)).toList();
        assertThat(versions).filteredOn(item -> item.status() == ConfigurationItemWire.StatusValue.ACTIVE)
                .extracting(ConfigurationItemWire::configId).containsExactly(secondActive.configId());
        assertThat(configurations.get(identity(), firstActive.configId()).status())
                .isEqualTo(ConfigurationItemWire.StatusValue.ARCHIVED);
    }

    @Test
    void givenIncompleteStructuredPayload_whenSubmitting_thenValidationFailsClosed() {
        String key = "WF-INVALID-" + UUID.randomUUID().toString().substring(0, 8);
        ConfigurationItemWire draft = configurations.define(identity(), "cfg-" + UUID.randomUUID(),
                new ConfigurationItemDefineRequestWire("WORKFLOW", key, "不完整流程", Map.of("schema_version", 1)));
        ConfigurationItemWire invalid = transition(identity(), draft,
                ConfigurationLifecycleRequestWire.ActionValue.VALIDATE, "检查不完整配置的错误列表");
        assertThat(invalid.validationState()).isEqualTo(ConfigurationItemWire.ValidationStateValue.INVALID);
        assertThat(invalid.validationErrors()).isNotEmpty();
        assertThatThrownBy(() -> transition(identity(), invalid,
                ConfigurationLifecycleRequestWire.ActionValue.SUBMIT, "不完整配置不得进入审批"))
                .isInstanceOf(ConfigurationException.class)
                .satisfies(e -> assertThat(((ConfigurationException) e).code())
                        .isEqualTo("CONFIG_VALIDATION_FAILED"));
    }

    @Test
    void givenPassedAgentEvaluation_whenIndependentlyApprovedAndPublished_thenItBecomesActive() {
        String key = "AGENT-EVAL-" + UUID.randomUUID().toString().substring(0, 8);
        ConfigurationItemWire draft = configurations.define(identity(), "cfg-" + UUID.randomUUID(),
                new ConfigurationItemDefineRequestWire("AGENT_EVAL", key, "医助发布评测",
                        agentEvaluationPayload("E2E_AGENT", 0.97, 0.95, "PASSED")));

        ConfigurationItemWire validated = transition(identity(), draft,
                ConfigurationLifecycleRequestWire.ActionValue.VALIDATE, "核验医助评测分值和发布门禁");
        assertThat(validated.validationState()).isEqualTo(ConfigurationItemWire.ValidationStateValue.VALID);
        ConfigurationItemWire pending = transition(identity(), validated,
                ConfigurationLifecycleRequestWire.ActionValue.SUBMIT, "提交医助发布的独立审批");
        ConfigurationItemWire approved = transition(approver(), pending,
                ConfigurationLifecycleRequestWire.ActionValue.APPROVE, "独立审批人核验评测证据");
        ConfigurationItemWire active = transition(approver(), approved,
                ConfigurationLifecycleRequestWire.ActionValue.PUBLISH, "评测通过后发布医助评测记录");

        assertThat(active.status()).isEqualTo(ConfigurationItemWire.StatusValue.ACTIVE);
        assertThat(active.validationState()).isEqualTo(ConfigurationItemWire.ValidationStateValue.VALID);
        assertThat(active.payload()).containsEntry("release_gate", "PASSED")
                .containsEntry("target_agent", "E2E_AGENT");
    }

    @Test
    void givenAgentEvaluationWithInconsistentGate_whenValidated_thenItFailsClosed() {
        ConfigurationItemWire draft = configurations.define(identity(), "cfg-" + UUID.randomUUID(),
                new ConfigurationItemDefineRequestWire("AGENT_EVAL", "AGENT-EVAL-" + UUID.randomUUID(), "错误发布门禁",
                        agentEvaluationPayload("E2E_AGENT", 0.91, 0.95, "PASSED")));

        ConfigurationItemWire invalid = transition(identity(), draft,
                ConfigurationLifecycleRequestWire.ActionValue.VALIDATE, "拒绝分值与发布门禁不一致的评测");

        assertThat(invalid.validationState()).isEqualTo(ConfigurationItemWire.ValidationStateValue.INVALID);
        assertThat(invalid.validationErrors()).anyMatch(error -> error.contains("release_gate"));
        assertThatThrownBy(() -> transition(identity(), invalid,
                ConfigurationLifecycleRequestWire.ActionValue.SUBMIT, "不一致评测不得进入审批和发布"))
                .isInstanceOf(ConfigurationException.class)
                .satisfies(error -> assertThat(((ConfigurationException) error).code())
                        .isEqualTo("CONFIG_VALIDATION_FAILED"));
    }

    @Test
    void givenActiveConfiguration_whenArchiving_thenItIsRemovedFromTheEffectiveFlow() {
        String key = "WF-ARCHIVE-" + UUID.randomUUID().toString().substring(0, 8);
        ConfigurationItemWire draft = configurations.define(identity(), "cfg-" + UUID.randomUUID(),
                new ConfigurationItemDefineRequestWire("WORKFLOW", key, "待归档流程", workflowPayload("复核")));
        ConfigurationItemWire validated = transition(identity(), draft,
                ConfigurationLifecycleRequestWire.ActionValue.VALIDATE, "归档前完成流程静态校验");
        ConfigurationItemWire pending = transition(identity(), validated,
                ConfigurationLifecycleRequestWire.ActionValue.SUBMIT, "归档前提交独立审批流程");
        ConfigurationItemWire approved = transition(approver(), pending,
                ConfigurationLifecycleRequestWire.ActionValue.APPROVE, "独立审批人完成发布前核对");
        ConfigurationItemWire active = transition(approver(), approved,
                ConfigurationLifecycleRequestWire.ActionValue.PUBLISH, "发布后验证安全归档停用能力");
        ConfigurationItemWire archived = transition(approver(), active,
                ConfigurationLifecycleRequestWire.ActionValue.ARCHIVE, "业务停用并保留完整版本和审计记录");
        assertThat(archived.status()).isEqualTo(ConfigurationItemWire.StatusValue.ARCHIVED);
        assertThat(archived.publishedAt()).isNull();
        assertThat(configurations.list(identity(), "WORKFLOW"))
                .extracting(ConfigurationItemWire::configId).doesNotContain(archived.configId());
    }

    @Test
    void givenUnsafeDomainModels_whenValidating_thenProtectedBusinessGatesFailClosed() {
        ConfigurationItemWire workflow = configurations.define(identity(), "cfg-" + UUID.randomUUID(),
                new ConfigurationItemDefineRequestWire("WORKFLOW", "WF-UNSAFE-" + UUID.randomUUID(), "无终态流程",
                        Map.of("schema_version", 2,
                                "nodes", List.of(
                                        Map.of("id", "start", "name", "开始", "type", "START", "owner", "医生"),
                                        Map.of("id", "task", "name", "无人任务", "type", "TASK", "owner", "")),
                                "edges", List.of(Map.of("from", "start", "to", "task", "condition", "已提交")),
                                "protected_nodes", List.of("audit"), "timeout_policy", "30 分钟提醒")));
        ConfigurationItemWire workflowResult = transition(identity(), workflow,
                ConfigurationLifecycleRequestWire.ActionValue.VALIDATE, "验证无终态和保护节点硬门");
        assertThat(workflowResult.validationErrors()).anyMatch(error -> error.contains("终态"));
        assertThat(workflowResult.validationErrors()).anyMatch(error -> error.contains("签署"));

        ConfigurationItemWire rule = configurations.define(identity(), "cfg-" + UUID.randomUUID(),
                new ConfigurationItemDefineRequestWire("RULE", "RULE-UNSAFE-" + UUID.randomUUID(), "AI 越权规则",
                        Map.of("schema_version", 2, "conditions", List.of("病情变化"), "actions", List.of("阻断医嘱"),
                                "rule_layer", "AI_ADVICE", "sample_case", Map.of("case_id", "SYN-1"),
                                "rules", List.of(Map.of("id", "ai-block", "name", "AI 自动阻断", "layer", "AI_ADVICE",
                                        "condition", "模型认为高风险", "action", "阻断处方", "evidence", "eval-v1")))));
        ConfigurationItemWire ruleResult = transition(identity(), rule,
                ConfigurationLifecycleRequestWire.ActionValue.VALIDATE, "验证 AI 不得直接阻断临床动作");
        assertThat(ruleResult.validationErrors()).anyMatch(error -> error.contains("AI 建议不得直接阻断"));

        ConfigurationItemWire scope = configurations.define(identity(), "cfg-" + UUID.randomUUID(),
                new ConfigurationItemDefineRequestWire("SCOPE", "SCOPE-UNSAFE-" + UUID.randomUUID(), "无范围高权",
                        Map.of("schema_version", 2, "roles", List.of("临时医生"), "data_scopes", List.of("全部患者"),
                                "separation_of_duties", "作者!=审批人", "temporary_grant_hours", 4,
                                "permissions", List.of(Map.of("role", "临时医生", "resource", "全部病历", "action", "导出",
                                        "scope", "全部患者", "effect", "ALLOW", "temporary_hours", 0)))));
        ConfigurationItemWire scopeResult = transition(identity(), scope,
                ConfigurationLifecycleRequestWire.ActionValue.VALIDATE, "验证高权限必须绑定范围和到期");
        assertThat(scopeResult.validationErrors()).anyMatch(error -> error.contains("无范围高权限"));
    }

    @Test
    void givenRoleCatalogDefinition_whenValidating_thenRoleAndWorkgroupSchemaIsSupported() {
        String key = "ROLE-" + UUID.randomUUID().toString().substring(0, 8);
        ConfigurationItemWire draft = configurations.define(identity(), "cfg-" + UUID.randomUUID(),
                new ConfigurationItemDefineRequestWire("ROLE_CATALOG", key, "心内科住院医生",
                        Map.of("schema_version", 1, "object_type", "ROLE", "parent_role_code", "ROLE-DOCTOR",
                                "permission_summary", "住院病历查看与书写", "scope", "心内科病区",
                                "owner", "医务处")));
        ConfigurationItemWire validated = transition(identity(), draft,
                ConfigurationLifecycleRequestWire.ActionValue.VALIDATE, "校验角色目录结构和职责分离字段");
        assertThat(validated.validationState()).isEqualTo(ConfigurationItemWire.ValidationStateValue.VALID);
    }

    @Test
    void givenTertiaryDataCenterCatalogs_whenValidating_thenCompleteSchemasAreAccepted() {
        Map<String, Map<String, Object>> payloads = Map.of(
                "INTEGRATION_CONNECTOR", Map.ofEntries(
                        Map.entry("schema_version", 1), Map.entry("system_type", "HIE"),
                        Map.entry("protocol", "CDA R2 / FHIR R4"),
                        Map.entry("capabilities", List.of("文档上传", "回执")),
                        Map.entry("endpoint", "政务专网 HIE-GW-01"),
                        Map.entry("secret_reference", "file://secrets/integration/hie-prod"),
                        Map.entry("timeout_retry", "15s / 5 次 / 幂等业务键"),
                        Map.entry("circuit_breaker", "300s / 院内流程继续"),
                        Map.entry("connector_version", "v3.1.7")),
                "DEVICE_CATALOG", Map.ofEntries(
                        Map.entry("schema_version", 1), Map.entry("device_type", "MONITOR"),
                        Map.entry("manufacturer_model", "迈瑞 BeneVision N15"),
                        Map.entry("department", "心血管内科一病区"),
                        Map.entry("gateway", "GW-BEDSIDE-01"),
                        Map.entry("standard_interface", "IEEE 11073 / HL7 ORU"),
                        Map.entry("calibration_due", "2027-02-28"),
                        Map.entry("clock_offset_seconds", 2),
                        Map.entry("binding_policy", "腕带 + 床位双标识")),
                "RESEARCH_PROJECT", Map.ofEntries(
                        Map.entry("schema_version", 1), Map.entry("project_type", "OBSERVATIONAL"),
                        Map.entry("principal_investigator", "周教授"),
                        Map.entry("registry_number", "MRR-2026-001842"),
                        Map.entry("ethics_approval", "IRB-2026-119"),
                        Map.entry("approved_purpose", "高血压真实世界治疗结局分析"),
                        Map.entry("data_scope", List.of("门诊病历", "处方", "检验")),
                        Map.entry("member_count", 8), Map.entry("expires_at", "2027-07-31")),
                "INTEGRATION_INCIDENT", Map.ofEntries(
                        Map.entry("schema_version", 1), Map.entry("trace_id", "TR-882151"),
                        Map.entry("direction", "EMR_TO_HIE"), Map.entry("event_type", "CDA_UPLOAD"),
                        Map.entry("business_object", "CDA-21018"), Map.entry("result", "PENDING_ACK"),
                        Map.entry("latency", "12m"),
                        Map.entry("clinical_impact", "院内签署不受影响"),
                        Map.entry("retry_policy", "文档哈希幂等，回执查询只读重试")));

        payloads.forEach((type, payload) -> {
            ConfigurationItemWire draft = configurations.define(identity(), "cfg-" + UUID.randomUUID(),
                    new ConfigurationItemDefineRequestWire(type, "SYN-" + UUID.randomUUID(),
                            type + " 三级医院基线", payload));
            ConfigurationItemWire validated = transition(identity(), draft,
                    ConfigurationLifecycleRequestWire.ActionValue.VALIDATE, "校验三级医院数据中心完整配置");
            assertThat(validated.validationState())
                    .as(type + " should pass its complete catalog schema")
                    .isEqualTo(ConfigurationItemWire.ValidationStateValue.VALID);
        });
    }

    @Test
    void givenConnectorWithUnsupportedSecretReference_whenValidating_thenItFailsClosed() {
        ConfigurationItemWire draft = configurations.define(identity(), "cfg-" + UUID.randomUUID(),
                new ConfigurationItemDefineRequestWire("INTEGRATION_CONNECTOR", "UNSAFE-" + UUID.randomUUID(),
                        "不合规连接器", Map.ofEntries(
                                Map.entry("schema_version", 1), Map.entry("system_type", "LIS"),
                                Map.entry("protocol", "HL7 v2.5.1"), Map.entry("capabilities", List.of("ORU")),
                                Map.entry("endpoint", "10.20.4.18"),
                                Map.entry("secret_reference", "vault://integration/lis"),
                                Map.entry("timeout_retry", "5s / 3 次"),
                                Map.entry("circuit_breaker", "60s / 人工降级"),
                                Map.entry("connector_version", "v1"))));

        ConfigurationItemWire invalid = transition(identity(), draft,
                ConfigurationLifecycleRequestWire.ActionValue.VALIDATE, "拒绝不受支持的秘密引用方案");

        assertThat(invalid.validationState()).isEqualTo(ConfigurationItemWire.ValidationStateValue.INVALID);
        assertThat(invalid.validationErrors()).anyMatch(error -> error.contains("env:// 或 file://"));
    }

    @Test
    void givenCompleteMockInterfaceProfile_whenValidating_thenItCanEnterGovernedFlow() {
        ConfigurationItemWire draft = configurations.define(administrator(), "cfg-" + UUID.randomUUID(),
                new ConfigurationItemDefineRequestWire("MOCK_INTERFACE_PROFILE",
                        "MOCK-" + UUID.randomUUID(), "LIS 三级医院仿真配置", Map.ofEntries(
                        Map.entry("schema_version", 1), Map.entry("workbench_id", "integration-connectors"),
                        Map.entry("interface_code", "LIS_RESULTS"), Map.entry("hospital_level", "三级甲等"),
                        Map.entry("organization", "江城大学附属医院"), Map.entry("organization_code", TENANT),
                        Map.entry("facility", "本部院区"), Map.entry("facility_code", "JC-BENBU"),
                        Map.entry("description", "检验结果与危急值回传"), Map.entry("default_entity", "SYN-001"),
                        Map.entry("default_scenario", "SUCCESS"), Map.entry("owner_department", "信息中心"),
                        Map.entry("operating_window", "7×24"), Map.entry("timeout_ms", 5000),
                        Map.entry("retry_limit", 3), Map.entry("manual_fallback", "转人工检验结果队列"),
                        Map.entry("default_record_count", 36), Map.entry("contains_real_phi", false),
                        Map.entry("production_adapter_state", "SYNTHETIC_ONLY"),
                        Map.entry("china_standard_profile", Map.of(
                                "hospital_platform", "WS/T 846.1-846.11—2024",
                                "hospital_platform_function", "WS/T 847—2024",
                                "cross_border_allowed", false)),
                        Map.entry("agent_policy", Map.of("clinical_write_allowed", false)),
                        Map.entry("critical_value_policy", Map.of(
                                "policy_code", "JC-LAB-CRITICAL-2026-01",
                                "requires_reporter_receiver_ack", true,
                                "requires_closed_loop", true)),
                        Map.entry("documentation_version", "v2.0 / 2026-08-31"))));

        ConfigurationItemWire validated = transition(administrator(), draft,
                ConfigurationLifecycleRequestWire.ActionValue.VALIDATE, "校验模拟接口配置完整性");

        assertThat(validated.validationState()).isEqualTo(ConfigurationItemWire.ValidationStateValue.VALID);
        assertThat(validated.validationErrors()).isEmpty();
    }

    @Test
    void givenClinicianRole_whenChangingMockProfile_thenAdministrationBoundaryDeniesWrite() {
        assertThatThrownBy(() -> configurations.define(identity(), "cfg-" + UUID.randomUUID(),
                new ConfigurationItemDefineRequestWire("MOCK_INTERFACE_PROFILE",
                        "MOCK-DENIED-" + UUID.randomUUID(), "越权配置", Map.of())))
                .isInstanceOf(ConfigurationException.class)
                .satisfies(error -> assertThat(((ConfigurationException) error).code())
                        .isEqualTo("MOCK_CONFIG_ADMIN_REQUIRED"));
    }

    @Test
    void givenAllMedicalQualitySubmenus_whenCreatingEditingAndDeleting_thenFlowFactsAreAudited() {
        Map<String, List<String>> statuses = Map.of(
                "QUALITY_INITIATIVE", List.of("MONITORING", "CLOSED"),
                "DEPARTMENT_QC_CASE", List.of("OPEN", "CLOSED"),
                "QUALITY_RATING_EVIDENCE", List.of("GAP", "VERIFIED"),
                "INFECTION_CONTROL_CASE", List.of("REPORTED", "CLOSED"),
                "CLINICAL_CREDENTIAL_GRANT", List.of("PENDING", "REVOKED"));

        statuses.forEach((type, workflowStatuses) -> {
            String key = type.substring(0, Math.min(type.length(), 12)) + "-" + UUID.randomUUID();
            ConfigurationItemWire created = configurations.define(identity(), "cfg-" + UUID.randomUUID(),
                    new ConfigurationItemDefineRequestWire(type, key, type + " 新建工作项",
                            qualityOperationPayload(type, workflowStatuses.getFirst(), 72)));
            ConfigurationItemWire edited = configurations.update(identity(), created.configId(),
                    "cfg-" + UUID.randomUUID(), new ConfigurationItemUpdateRequestWire(
                            type + " 已编辑工作项",
                            qualityOperationPayload(type, workflowStatuses.get(1), 96), created.rowVersion()));
            assertThat(edited.payload()).containsEntry("workflow_status", workflowStatuses.get(1));
            assertThat(edited.payload()).containsEntry("score", 96);

            ConfigurationItemWire validated = transition(identity(), edited,
                    ConfigurationLifecycleRequestWire.ActionValue.VALIDATE, "校验医疗质量工作项流程字段");
            assertThat(validated.validationState()).isEqualTo(ConfigurationItemWire.ValidationStateValue.VALID);
            ConfigurationItemWire archived = transition(approver(), validated,
                    ConfigurationLifecycleRequestWire.ActionValue.ARCHIVE, "逻辑删除并保留医疗质量审计证据");
            assertThat(archived.status()).isEqualTo(ConfigurationItemWire.StatusValue.ARCHIVED);
            assertThat(configurations.list(identity(), type)).extracting(ConfigurationItemWire::configId)
                    .doesNotContain(created.configId());
        });
    }

    @Test
    void givenTertiaryClinicalTaskRuleAndPathway_whenValidating_thenOperationalGatesAreEnforced() {
        ConfigurationItemWire taskRule = configurations.define(identity(), "cfg-" + UUID.randomUUID(),
                new ConfigurationItemDefineRequestWire("CLINICAL_TASK_RULE", "TASK-" + UUID.randomUUID(),
                        "危急值 15 分钟闭环", Map.ofEntries(
                        Map.entry("schema_version", 1), Map.entry("task_type", "CRITICAL_VALUE"),
                        Map.entry("risk_level", "CRITICAL"), Map.entry("due_minutes", 15),
                        Map.entry("escalation_minutes", 5), Map.entry("assignment_strategy", "当班医生 + 上级医师"),
                        Map.entry("completion_source", "权威业务对象终态"), Map.entry("channels", List.of("站内", "消息总线")),
                        Map.entry("applies_to", "全院危急值"), Map.entry("enabled", true))));
        ConfigurationItemWire validRule = transition(identity(), taskRule,
                ConfigurationLifecycleRequestWire.ActionValue.VALIDATE, "校验三级医院任务规则");
        assertThat(validRule.validationState()).isEqualTo(ConfigurationItemWire.ValidationStateValue.VALID);

        ConfigurationItemWire pathway = configurations.define(identity(), "cfg-" + UUID.randomUUID(),
                new ConfigurationItemDefineRequestWire("CLINICAL_PATHWAY", "PATH-" + UUID.randomUUID(),
                        "急性心肌梗死 PCI 路径", Map.ofEntries(
                        Map.entry("schema_version", 1), Map.entry("pathway_code", "AMI-PCI"),
                        Map.entry("specialty_code", "CARDIOLOGY"), Map.entry("diagnosis_code", "I21.9"),
                        Map.entry("version_no", 3), Map.entry("admission_criteria", "STEMI 且拟行急诊 PCI"),
                        Map.entry("stages", List.of(Map.of("code", "EMERGENCY", "name", "急诊再灌注"),
                                Map.of("code", "WARD", "name", "术后管理"))),
                        Map.entry("publication_scope", "本部院区心内科"),
                        Map.entry("version_immutable_after_publish", true))));
        ConfigurationItemWire validPathway = transition(identity(), pathway,
                ConfigurationLifecycleRequestWire.ActionValue.VALIDATE, "校验三级医院临床路径");
        assertThat(validPathway.validationState()).isEqualTo(ConfigurationItemWire.ValidationStateValue.VALID);
    }

    @Test
    void givenUnsafeTaskRuleAndMutablePathway_whenValidating_thenTheyFailClosed() {
        ConfigurationItemWire taskRule = configurations.define(identity(), "cfg-" + UUID.randomUUID(),
                new ConfigurationItemDefineRequestWire("CLINICAL_TASK_RULE", "TASK-BAD-" + UUID.randomUUID(),
                        "无效任务规则", Map.ofEntries(
                        Map.entry("schema_version", 1), Map.entry("task_type", "CRITICAL_VALUE"),
                        Map.entry("risk_level", "UNKNOWN"), Map.entry("due_minutes", 10),
                        Map.entry("escalation_minutes", 10), Map.entry("assignment_strategy", "当班医生"),
                        Map.entry("completion_source", "手工勾选"), Map.entry("channels", List.of("站内")),
                        Map.entry("applies_to", "全院"), Map.entry("enabled", true))));
        ConfigurationItemWire invalidRule = transition(identity(), taskRule,
                ConfigurationLifecycleRequestWire.ActionValue.VALIDATE, "拒绝无效任务规则");
        assertThat(invalidRule.validationErrors()).anyMatch(error -> error.contains("升级提前量"));
        assertThat(invalidRule.validationErrors()).anyMatch(error -> error.contains("权威业务对象终态"));

        ConfigurationItemWire pathway = configurations.define(identity(), "cfg-" + UUID.randomUUID(),
                new ConfigurationItemDefineRequestWire("CLINICAL_PATHWAY", "PATH-BAD-" + UUID.randomUUID(),
                        "可变临床路径", Map.ofEntries(
                        Map.entry("schema_version", 1), Map.entry("pathway_code", "BAD"),
                        Map.entry("specialty_code", "CARDIOLOGY"), Map.entry("diagnosis_code", "I50.9"),
                        Map.entry("version_no", 1), Map.entry("admission_criteria", "符合入径标准"),
                        Map.entry("stages", List.of(Map.of("code", "WARD"), Map.of("code", "WARD"))),
                        Map.entry("publication_scope", "本院"), Map.entry("version_immutable_after_publish", false))));
        ConfigurationItemWire invalidPathway = transition(identity(), pathway,
                ConfigurationLifecycleRequestWire.ActionValue.VALIDATE, "拒绝可变的临床路径配置");
        assertThat(invalidPathway.validationErrors()).anyMatch(error -> error.contains("阶段编码"));
        assertThat(invalidPathway.validationErrors()).anyMatch(error -> error.contains("版本不可变"));
    }

    private ConfigurationItemWire transition(
            ClinicalIdentity actor, ConfigurationItemWire item,
            ConfigurationLifecycleRequestWire.ActionValue action, String reason) {
        return configurations.transition(actor, item.configId(), "cfg-" + UUID.randomUUID(),
                new ConfigurationLifecycleRequestWire(action, item.rowVersion(), reason));
    }

    private ConfigurationItemWire publish(ConfigurationItemWire draft, String reason) {
        ConfigurationItemWire validated = transition(identity(), draft,
                ConfigurationLifecycleRequestWire.ActionValue.VALIDATE, reason + "并完成校验");
        ConfigurationItemWire pending = transition(identity(), validated,
                ConfigurationLifecycleRequestWire.ActionValue.SUBMIT, reason + "并提交审批");
        ConfigurationItemWire approved = transition(approver(), pending,
                ConfigurationLifecycleRequestWire.ActionValue.APPROVE, reason + "并独立审批");
        return transition(approver(), approved, ConfigurationLifecycleRequestWire.ActionValue.PUBLISH, reason);
    }

    private Map<String, Object> agentEvaluationPayload(
            String targetAgent, double measuredScore, double passThreshold, String releaseGate) {
        return Map.ofEntries(
                Map.entry("schema_version", 1),
                Map.entry("target_agent", targetAgent),
                Map.entry("dataset_version", "clinical-evaluation-fixture-v1"),
                Map.entry("case_count", 120),
                Map.entry("pass_threshold", passThreshold),
                Map.entry("measured_score", measuredScore),
                Map.entry("release_gate", releaseGate),
                Map.entry("red_team_profile", "越权诱导、提示词注入、未确认临床操作与敏感数据泄露"));
    }

    private Map<String, Object> workflowPayload(String reviewNode) {
        return Map.ofEntries(
                Map.entry("schema_version", 3),
                Map.entry("china_compliance", chinaCompliance()),
                Map.entry("nodes", List.of(
                        Map.of("id", "start", "name", "发起", "type", "START", "owner", "经治医生"),
                        Map.of("id", "review", "name", reviewNode, "type", "TASK", "owner", "会诊医生"),
                        Map.of("id", "sign", "name", "签署", "type", "SIGN", "owner", "会诊医生", "protected", true),
                        Map.of("id", "audit", "name", "审计", "type", "AUDIT", "owner", "病案管理员", "protected", true),
                        Map.of("id", "end", "name", "完成", "type", "END", "owner", "系统", "terminal", true))),
                Map.entry("edges", List.of(
                        edge("start", "review", "START_REVIEW"), edge("review", "sign", "REVIEW_SIGNED"),
                        edge("sign", "audit", "SIGNATURE_VERIFIED"), edge("audit", "end", "AUDIT_PASSED"))),
                Map.entry("protected_nodes", List.of("sign", "audit")),
                Map.entry("timeout_policy", "2 小时提醒，4 小时升级"));
    }

    private Map<String, Object> edge(String from, String to, String eventCode) {
        return Map.of("from", from, "to", to, "condition", eventCode,
                "event_code", eventCode, "guard", Map.of(
                        "fact_path", "events." + from + ".completed", "operator", "EQ", "expected", true));
    }

    private Map<String, Object> chinaCompliance() {
        return Map.ofEntries(
                Map.entry("profile", "CN_MEDICAL_PRODUCTION_2026"),
                Map.entry("data_element_standard", "WS/T 363.1-2023"),
                Map.entry("electronic_record_dataset", "WS 445.1-2014~WS 445.17-2014"),
                Map.entry("diagnosis_code_system", "国家临床版 ICD-10"),
                Map.entry("procedure_code_system", "国家临床版 ICD-9-CM-3"),
                Map.entry("signature_policy", "可靠电子签名 + CA + 可信时间戳 + 验真"),
                Map.entry("retention_policy", "门急诊15年；住院30年；归档更正留痕"),
                Map.entry("minimum_necessary", true),
                Map.entry("effective_from", "2026-09-01T00:00:00+08:00"),
                Map.entry("review_due", "2027-03-01T00:00:00+08:00"));
    }

    private Map<String, Object> qualityOperationPayload(String type, String status, int score) {
        return Map.ofEntries(
                Map.entry("schema_version", 2),
                Map.entry("module_id", type.toLowerCase()),
                Map.entry("owner", "医务处质量管理科"),
                Map.entry("scope", "本部院区全院"),
                Map.entry("severity", score < 80 ? "BLOCKING" : "WARNING"),
                Map.entry("workflow_status", status),
                Map.entry("due_at", "2026-09-30T18:00:00+08:00"),
                Map.entry("score", score),
                Map.entry("description", "医疗质量工作项需要形成闭环证据"),
                Map.entry("flow_impact", "开放工作项进入责任队列并影响质量指标"),
                Map.entry("china_policy_basis", "医疗质量管理办法与院级制度"),
                Map.entry("source_reference", "config://quality-test/source"),
                Map.entry("indicator_code", "MQI-TEST-001"),
                Map.entry("core_system_code", "CORE-MR-01"),
                Map.entry("sample_method", "人工分层随机抽样"),
                Map.entry("sample_size", 30),
                Map.entry("evaluation_framework", "EMR_LEVEL_0_8_39_ITEMS"),
                Map.entry("evaluation_project_code", "EMR39-P01"),
                Map.entry("evaluation_dimension", "DATA_QUALITY"),
                Map.entry("event_category", "HAI_CASE"),
                Map.entry("reporting_window_hours", 24),
                Map.entry("authorization_category", "PROCEDURE"),
                Map.entry("clinical_action_code", "CLINICAL_PROCEDURE_EXECUTE"),
                Map.entry("human_review_required", true));
    }
}
