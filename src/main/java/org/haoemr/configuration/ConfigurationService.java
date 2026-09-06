package org.haoemr.configuration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.haoemr.contracts.ConfigurationItemDefineRequestWire;
import org.haoemr.contracts.ConfigurationItemUpdateRequestWire;
import org.haoemr.contracts.ConfigurationItemWire;
import org.haoemr.contracts.ConfigurationLifecycleRequestWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
final class ConfigurationService {
    private static final List<String> MOCK_CONFIGURATION_ADMIN_ROLES =
            List.of("SYSTEM_ADMIN", "CLINICAL_ADMIN", "INTEGRATION_OPERATOR");
    private static final List<String> CONFIGURATION_AUTHOR_ROLES = List.of(
            "SYSTEM_ADMIN", "CLINICAL_ADMIN", "CONFIG_AUTHOR", "CONFIG_APPROVER",
            "AUTHORIZATION_ADMIN", "INTEGRATION_OPERATOR");
    private static final List<String> CONFIGURATION_APPROVER_ROLES = List.of(
            "SYSTEM_ADMIN", "CLINICAL_ADMIN", "CONFIG_APPROVER");
    private static final Map<String, List<String>> REQUIRED_FIELDS = Map.ofEntries(
            Map.entry("WORKFLOW", List.of("schema_version", "nodes", "edges", "protected_nodes", "timeout_policy")),
            Map.entry("FORM_TEMPLATE", List.of("schema_version", "fields", "groups", "terminology_mapping", "print_template")),
            Map.entry("RULE", List.of("schema_version", "rules", "conditions", "actions", "rule_layer", "sample_case")),
            Map.entry("SCOPE", List.of("schema_version", "permissions", "roles", "data_scopes", "separation_of_duties", "temporary_grant_hours")),
            Map.entry("CAPABILITY_PACK_COMPOSITION", List.of("schema_version", "module_catalog", "selected_modules", "dependencies", "scope_overrides", "rating_framework", "rating_impact")),
            Map.entry("AGENT_COMPOSITION", List.of("schema_version", "agents", "skills", "tools", "budget_tokens", "stop_conditions", "compensation")),
            Map.entry("AGENT_CONTEXT", List.of("schema_version", "data_sources", "allowed_fields", "time_window_hours", "redaction_policy", "freshness_minutes")),
            Map.entry("AGENT_EVAL", List.of("schema_version", "target_agent", "dataset_version", "case_count", "pass_threshold", "measured_score", "release_gate", "red_team_profile")),
            Map.entry("AI_ASSISTANT_POLICY", List.of("schema_version", "proactive_level", "allowed_sources", "model_policy", "rate_limit", "approval_required")),
            Map.entry("CONFIG_RELEASE", List.of("schema_version", "diff_summary", "validation_evidence", "rollout_scope", "rollback_plan")),
            Map.entry("CONFIG_UPGRADE", List.of("schema_version", "package_version", "compatibility", "conflicts", "recovery_point")),
            Map.entry("MASTER_DATA", List.of("schema_version", "code_system", "hierarchy", "effective_period", "import_policy")),
            Map.entry("ROLE_CATALOG", List.of("schema_version", "object_type", "parent_role_code", "permission_summary", "scope", "owner")),
            Map.entry("PARAMETER", List.of("schema_version", "value_type", "configured_value", "scope", "inheritance", "secret_reference", "effective_at")),
            Map.entry("JOB", List.of("schema_version", "job_kind", "schedule", "batch_size", "retry_policy", "reconciliation_rule")),
            Map.entry("BACKUP", List.of("schema_version", "repository", "retention_days", "rpo_minutes", "rto_minutes", "checksum_policy")),
            Map.entry("INSTALL", List.of("schema_version", "prerequisites", "database_profile", "identity_profile", "resume_step")),
            Map.entry("OPERATION", List.of("schema_version", "health_checks", "maintenance_window", "downtime_mode", "recovery_steps")),
            Map.entry("RELEASE_GATE", List.of("schema_version", "candidate_commit", "required_gates", "artifact_checksum", "rollback_entry")),
            Map.entry("MOCK_INTERFACE_PROFILE", List.of(
                    "schema_version", "workbench_id", "interface_code", "hospital_level", "organization",
                    "organization_code", "facility", "facility_code", "description", "default_entity",
                    "default_scenario", "owner_department", "operating_window", "timeout_ms", "retry_limit",
                    "manual_fallback", "production_adapter_state", "china_standard_profile", "agent_policy",
                    "contains_real_phi", "documentation_version")),
            Map.entry("INTEGRATION_CONNECTOR", List.of("schema_version", "system_type", "protocol", "capabilities", "endpoint", "secret_reference", "timeout_retry", "circuit_breaker", "connector_version")),
            Map.entry("DEVICE_CATALOG", List.of("schema_version", "device_type", "manufacturer_model", "department", "gateway", "standard_interface", "calibration_due", "clock_offset_seconds", "binding_policy")),
            Map.entry("RESEARCH_PROJECT", List.of("schema_version", "project_type", "principal_investigator", "registry_number", "ethics_approval", "approved_purpose", "data_scope", "member_count", "expires_at")),
            Map.entry("INTEGRATION_INCIDENT", List.of("schema_version", "trace_id", "direction", "event_type", "business_object", "result", "latency", "clinical_impact", "retry_policy")),
            Map.entry("CLINICAL_TASK_RULE", List.of("schema_version", "task_type", "risk_level", "due_minutes", "escalation_minutes", "assignment_strategy", "completion_source", "channels", "applies_to", "enabled")),
            Map.entry("CLINICAL_PATHWAY", List.of("schema_version", "pathway_code", "specialty_code", "diagnosis_code", "version_no", "admission_criteria", "stages", "publication_scope", "version_immutable_after_publish")),
            Map.entry("QUALITY_INITIATIVE", qualityOperationFields()),
            Map.entry("DEPARTMENT_QC_CASE", qualityOperationFields()),
            Map.entry("QUALITY_RATING_EVIDENCE", qualityOperationFields()),
            Map.entry("INFECTION_CONTROL_CASE", qualityOperationFields()),
            Map.entry("CLINICAL_CREDENTIAL_GRANT", qualityOperationFields()));

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final ClinicalPathwayConfigurationPublisher pathwayPublisher;

    ConfigurationService(JdbcClient jdbc, TransactionTemplate transactions, ObjectMapper objectMapper,
            ClinicalPathwayConfigurationPublisher pathwayPublisher) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.pathwayPublisher = pathwayPublisher;
    }

    List<ConfigurationItemWire> list(ClinicalIdentity identity, String configType) {
        StringBuilder sql = new StringBuilder("""
                select config_id, config_type, config_key, display_name, payload::text, status,
                       schema_version, validation_state, validation_errors::text, approval_state,
                       approved_by, published_at, row_version, created_at, updated_at
                from config_item where tenant_id = :tenant and status <> 'ARCHIVED'
                """);
        if (configType != null && !configType.isBlank()) sql.append(" and config_type = :type");
        sql.append(" order by updated_at desc, config_id desc limit 500");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("tenant", identity.tenantId());
        if (configType != null && !configType.isBlank()) spec = spec.param("type", configType);
        return spec.query((rs, row) -> wire(
                rs.getObject("config_id", UUID.class), rs.getString("config_type"), rs.getString("config_key"),
                rs.getString("display_name"), rs.getString("payload"), rs.getString("status"),
                rs.getInt("schema_version"), rs.getString("validation_state"), rs.getString("validation_errors"),
                rs.getString("approval_state"), rs.getObject("approved_by", UUID.class),
                rs.getObject("published_at", OffsetDateTime.class), rs.getLong("row_version"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class)))
                .list();
    }

    ConfigurationItemWire get(ClinicalIdentity identity, UUID configId) {
        return item(identity.tenantId(), configId);
    }

    List<ConfigurationRevisionWire> revisions(ClinicalIdentity identity, UUID configId) {
        item(identity.tenantId(), configId);
        return jdbc.sql("""
                select revision_no, display_name, payload::text, schema_version, status,
                  validation_state, validation_errors::text, approval_state, changed_by,
                  change_reason, created_at
                from config_item_revision
                where tenant_id = :tenant and config_id = :config
                order by revision_no desc limit 200
                """).param("tenant", identity.tenantId()).param("config", configId)
                .query((rs, row) -> new ConfigurationRevisionWire(
                        rs.getLong("revision_no"), rs.getString("display_name"),
                        payload(rs.getString("payload")), rs.getInt("schema_version"),
                        rs.getString("status"), rs.getString("validation_state"),
                        validationErrors(rs.getString("validation_errors")), rs.getString("approval_state"),
                        rs.getObject("changed_by", UUID.class), rs.getString("change_reason"),
                        rs.getObject("created_at", OffsetDateTime.class))).list();
    }

    ConfigurationItemWire define(
            ClinicalIdentity identity, String idempotencyKey, ConfigurationItemDefineRequestWire request) {
        requireConfigurationRole(identity, CONFIGURATION_AUTHOR_ROLES, "CONFIG_AUTHOR_REQUIRED");
        requireMockConfigurationAdmin(identity, request.configType());
        String requestHash = sha256(idempotencyKey + "|" + request.configType() + "|" + request.configKey()
                + "|" + json(request.payload()));
        return transactions.execute(status -> {
            beginCommand(identity, "CONFIG_DEFINE", idempotencyKey, requestHash);
            UUID configId = UUID.randomUUID();
            int inserted = jdbc.sql("""
                    insert into config_item(
                      tenant_id, config_id, config_type, config_key, display_name, payload, status, created_by)
                    values (:tenant, :config, :type, :key, :name, cast(:payload as jsonb), 'DRAFT', :actor)
                    on conflict do nothing
                    """).param("tenant", identity.tenantId()).param("config", configId)
                    .param("type", request.configType()).param("key", request.configKey())
                    .param("name", request.displayName()).param("payload", json(request.payload()))
                    .param("actor", identity.userId()).update();
            if (inserted != 1) {
                throw new ConfigurationException(
                        "CONFIG_KEY_CONFLICT", 409, "同类型下配置键已存在：" + request.configKey());
            }
            appendEvidence(identity, "CONFIG_ITEM_DEFINED", request.configType(), configId, 1);
            saveRevision(identity, configId, "configuration defined");
            completeCommand(identity, "CONFIG_DEFINE", idempotencyKey, configId, 201);
            return item(identity.tenantId(), configId);
        });
    }

    ConfigurationItemWire update(
            ClinicalIdentity identity, UUID configId, String idempotencyKey,
            ConfigurationItemUpdateRequestWire request) {
        requireConfigurationRole(identity, CONFIGURATION_AUTHOR_ROLES, "CONFIG_AUTHOR_REQUIRED");
        String requestHash = sha256(idempotencyKey + "|" + configId + "|" + request.expectedVersion()
                + "|" + request.displayName() + "|" + json(request.payload()));
        return transactions.execute(status -> {
            beginCommand(identity, "CONFIG_UPDATE", idempotencyKey, requestHash);
            ConfigState current = state(identity.tenantId(), configId, true);
            requireMockConfigurationAdmin(identity, current.configType());
            requireVersion(current, request.expectedVersion());
            requireState(current, "DRAFT");
            int updated = jdbc.sql("""
                    update config_item set display_name = :name, payload = cast(:payload as jsonb),
                      validation_state = 'NOT_VALIDATED', validation_errors = '[]'::jsonb,
                      approval_state = 'DRAFT', approved_by = null, row_version = row_version + 1,
                      updated_at = now()
                    where tenant_id = :tenant and config_id = :config and row_version = :version
                    """).param("name", request.displayName()).param("payload", json(request.payload()))
                    .param("tenant", identity.tenantId()).param("config", configId)
                    .param("version", request.expectedVersion()).update();
            if (updated != 1) throw versionConflict();
            ConfigurationItemWire result = item(identity.tenantId(), configId);
            appendEvidence(identity, "CONFIG_ITEM_UPDATED", current.configType(), configId, result.rowVersion());
            saveRevision(identity, configId, "draft updated");
            completeCommand(identity, "CONFIG_UPDATE", idempotencyKey, configId, 200);
            return result;
        });
    }

    ConfigurationItemWire transition(
            ClinicalIdentity identity, UUID configId, String idempotencyKey,
            ConfigurationLifecycleRequestWire request) {
        List<String> allowedRoles = switch (request.action()) {
            case APPROVE, PUBLISH, ROLLBACK, ARCHIVE -> CONFIGURATION_APPROVER_ROLES;
            case VALIDATE, SUBMIT -> CONFIGURATION_AUTHOR_ROLES;
        };
        requireConfigurationRole(identity, allowedRoles,
                allowedRoles == CONFIGURATION_APPROVER_ROLES ? "CONFIG_APPROVER_REQUIRED" : "CONFIG_AUTHOR_REQUIRED");
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.length() < 8 || reason.length() > 500) {
            throw new ConfigurationException("CONFIG_REASON_REQUIRED", 422, "生命周期操作原因需为 8 至 500 个字符");
        }
        String requestHash = sha256(idempotencyKey + "|" + configId + "|" + request.action()
                + "|" + request.expectedVersion() + "|" + reason);
        return transactions.execute(status -> {
            beginCommand(identity, "CONFIG_LIFECYCLE", idempotencyKey, requestHash);
            ConfigState current = state(identity.tenantId(), configId, true);
            requireMockConfigurationAdmin(identity, current.configType());
            requireVersion(current, request.expectedVersion());
            List<String> errors = validate(current.configType(), current.payload());
            String action = request.action().name();
            switch (request.action()) {
                case VALIDATE -> validateTransition(identity, current, errors);
                case SUBMIT -> submitTransition(identity, current, errors);
                case APPROVE -> approveTransition(identity, current);
                case PUBLISH -> publishTransition(identity, current);
                case ROLLBACK -> rollbackTransition(identity, current);
                case ARCHIVE -> archiveTransition(identity, current);
            }
            ConfigurationItemWire result = item(identity.tenantId(), configId);
            appendEvidence(identity, "CONFIG_" + action, current.configType(), configId, result.rowVersion());
            saveRevision(identity, configId, reason);
            completeCommand(identity, "CONFIG_LIFECYCLE", idempotencyKey, configId, 200);
            return result;
        });
    }

    private void validateTransition(ClinicalIdentity identity, ConfigState current, List<String> errors) {
        jdbc.sql("""
                update config_item set validation_state = :validation, validation_errors = cast(:errors as jsonb),
                  row_version = row_version + 1, updated_at = now()
                where tenant_id = :tenant and config_id = :config and row_version = :version
                """).param("validation", errors.isEmpty() ? "VALID" : "INVALID").param("errors", jsonList(errors))
                .param("tenant", identity.tenantId()).param("config", current.configId())
                .param("version", current.rowVersion()).update();
    }

    private void submitTransition(ClinicalIdentity identity, ConfigState current, List<String> errors) {
        requireState(current, "DRAFT");
        if (!errors.isEmpty()) throw invalidPayload(errors);
        jdbc.sql("""
                update config_item set status = 'PENDING_APPROVAL', validation_state = 'VALID',
                  validation_errors = '[]'::jsonb, approval_state = 'PENDING', approved_by = null,
                  row_version = row_version + 1, updated_at = now()
                where tenant_id = :tenant and config_id = :config and row_version = :version
                """).param("tenant", identity.tenantId()).param("config", current.configId())
                .param("version", current.rowVersion()).update();
    }

    private void approveTransition(ClinicalIdentity identity, ConfigState current) {
        requireState(current, "PENDING_APPROVAL");
        if (identity.userId().equals(current.createdBy())) {
            throw new ConfigurationException("CONFIG_SEPARATION_OF_DUTIES", 403, "配置作者不能批准自己的配置");
        }
        jdbc.sql("""
                update config_item set status = 'APPROVED', approval_state = 'APPROVED',
                  approved_by = :actor, row_version = row_version + 1, updated_at = now()
                where tenant_id = :tenant and config_id = :config and row_version = :version
                """).param("actor", identity.userId()).param("tenant", identity.tenantId())
                .param("config", current.configId()).param("version", current.rowVersion()).update();
    }

    private void publishTransition(ClinicalIdentity identity, ConfigState current) {
        requireState(current, "APPROVED");
        if ("MOCK_INTERFACE_PROFILE".equals(current.configType())) {
            String workbenchId = text(current.payload().get("workbench_id"));
            if (workbenchId.isBlank()) {
                throw new ConfigurationException(
                        "CONFIG_VALIDATION_FAILED", 422, "模拟接口配置缺少 workbench_id");
            }
            jdbc.sql("""
                    update config_item set status = 'ARCHIVED', published_at = null,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and config_type = 'MOCK_INTERFACE_PROFILE'
                      and status = 'ACTIVE' and config_id <> :config
                      and payload->>'workbench_id' = :workbench
                    """).param("tenant", identity.tenantId()).param("config", current.configId())
                    .param("workbench", workbenchId).update();
        }
        jdbc.sql("""
                update config_item set status = 'ARCHIVED', published_at = null,
                  row_version = row_version + 1, updated_at = now()
                where tenant_id = :tenant and config_type = :type and config_key = :key
                  and status = 'ACTIVE' and config_id <> :config
                """).param("tenant", identity.tenantId()).param("type", current.configType())
                .param("key", current.configKey()).param("config", current.configId()).update();
        jdbc.sql("""
                update config_item set status = 'ACTIVE', published_at = now(),
                  row_version = row_version + 1, updated_at = now()
                where tenant_id = :tenant and config_id = :config and row_version = :version
                """).param("tenant", identity.tenantId()).param("config", current.configId())
                .param("version", current.rowVersion()).update();
        if ("CLINICAL_PATHWAY".equals(current.configType())) {
            pathwayPublisher.publish(identity, current.configId(), current.rowVersion() + 1,
                    current.displayName(), current.payload());
        }
    }

    private void rollbackTransition(ClinicalIdentity identity, ConfigState current) {
        requireState(current, "ACTIVE");
        Revision previous = jdbc.sql("""
                select display_name, payload::text, schema_version
                from config_item_revision
                where tenant_id = :tenant and config_id = :config and revision_no < :version
                  and payload <> cast(:payload as jsonb)
                order by revision_no desc limit 1
                """).param("tenant", identity.tenantId()).param("config", current.configId())
                .param("version", current.rowVersion()).param("payload", json(current.payload()))
                .query((rs, row) -> new Revision(rs.getString("display_name"),
                        payload(rs.getString("payload")), rs.getInt("schema_version")))
                .optional().orElseThrow(() -> new ConfigurationException(
                        "CONFIG_ROLLBACK_VERSION_MISSING", 409, "没有可回退的上一配置版本"));
        jdbc.sql("""
                update config_item set display_name = :name, payload = cast(:payload as jsonb),
                  schema_version = :schema_version, validation_state = 'VALID', validation_errors = '[]'::jsonb,
                  approval_state = 'APPROVED', published_at = now(), row_version = row_version + 1,
                  updated_at = now()
                where tenant_id = :tenant and config_id = :config and row_version = :version
                """).param("name", previous.displayName()).param("payload", json(previous.payload()))
                .param("schema_version", previous.schemaVersion()).param("tenant", identity.tenantId())
                .param("config", current.configId()).param("version", current.rowVersion()).update();
    }

    private void archiveTransition(ClinicalIdentity identity, ConfigState current) {
        if ("ARCHIVED".equals(current.status())) {
            throw new ConfigurationException("CONFIG_STATE_INVALID", 409, "配置已经归档停用");
        }
        jdbc.sql("""
                update config_item set status = 'ARCHIVED', published_at = null,
                  row_version = row_version + 1, updated_at = now()
                where tenant_id = :tenant and config_id = :config and row_version = :version
                """).param("tenant", identity.tenantId()).param("config", current.configId())
                .param("version", current.rowVersion()).update();
    }

    private List<String> validate(String configType, Map<String, Object> payload) {
        List<String> errors = new ArrayList<>();
        List<String> required = REQUIRED_FIELDS.get(configType);
        if (required == null) {
            errors.add("不支持的配置类型：" + configType);
            return errors;
        }
        for (String field : required) {
            Object value = payload.get(field);
            if (value == null || value instanceof String text && text.isBlank()
                    || value instanceof List<?> list && list.isEmpty()) {
                errors.add(field + " 为必填字段");
            }
        }
        Object schemaVersion = payload.get("schema_version");
        if (!(schemaVersion instanceof Number number) || number.intValue() < 1) {
            errors.add("schema_version 必须为正整数");
        }
        if (List.of("WORKFLOW", "FORM_TEMPLATE", "RULE", "SCOPE").contains(configType)) {
            if (!(schemaVersion instanceof Number number) || number.intValue() < 3) {
                errors.add("中国医疗生产运行配置必须升级到 schema_version 3");
            } else {
                validateChinaCompliance(payload, errors);
            }
        }
        Object secret = payload.get("secret_reference");
        if (secret instanceof String value && !value.isBlank()
                && !(value.startsWith("env://") || value.startsWith("file://"))) {
            errors.add("secret_reference 只能使用 env:// 或 file:// 引用");
        }
        Object threshold = payload.get("pass_threshold");
        if (threshold instanceof Number value && (value.doubleValue() < 0 || value.doubleValue() > 1)) {
            errors.add("pass_threshold 必须位于 0 到 1");
        }
        if ("AGENT_EVAL".equals(configType)) {
            String agent = text(payload.get("target_agent"));
            String gate = text(payload.get("release_gate"));
            Object measured = payload.get("measured_score");
            if (agent.length() < 2) errors.add("AGENT_EVAL 必须指定 target_agent");
            if (!(measured instanceof Number score) || score.doubleValue() < 0 || score.doubleValue() > 1) {
                errors.add("AGENT_EVAL 的 measured_score 必须位于 0 到 1");
            }
            if (!List.of("PASSED", "BLOCKED").contains(gate)) errors.add("AGENT_EVAL 的 release_gate 必须为 PASSED 或 BLOCKED");
            if (measured instanceof Number score && threshold instanceof Number limit
                    && (score.doubleValue() >= limit.doubleValue()) != "PASSED".equals(gate)) {
                errors.add("AGENT_EVAL 的 release_gate 必须与 measured_score 和 pass_threshold 一致");
            }
        }
        switch (configType) {
            case "WORKFLOW" -> validateWorkflow(payload, errors);
            case "FORM_TEMPLATE" -> validateFormTemplate(payload, errors);
            case "RULE" -> validateRules(payload, errors);
            case "SCOPE" -> validateScope(payload, errors);
            case "CAPABILITY_PACK_COMPOSITION" -> validateCapabilityComposition(payload, errors);
            case "CLINICAL_TASK_RULE" -> validateClinicalTaskRule(payload, errors);
            case "CLINICAL_PATHWAY" -> validateClinicalPathway(payload, errors);
            case "MOCK_INTERFACE_PROFILE" -> validateMockInterfaceProfile(payload, errors);
            case "QUALITY_INITIATIVE", "DEPARTMENT_QC_CASE", "QUALITY_RATING_EVIDENCE",
                    "INFECTION_CONTROL_CASE", "CLINICAL_CREDENTIAL_GRANT" ->
                    validateQualityOperation(configType, payload, errors);
            default -> { }
        }
        return List.copyOf(errors);
    }

    private void validateMockInterfaceProfile(Map<String, Object> payload, List<String> errors) {
        if (!"SYNTHETIC_ONLY".equals(text(payload.get("production_adapter_state")))) {
            errors.add("模拟接口配置必须明确标记 production_adapter_state=SYNTHETIC_ONLY");
        }
        if (Boolean.TRUE.equals(payload.get("contains_real_phi"))) {
            errors.add("模拟接口禁止配置为包含真实医疗健康数据");
        }
        if (!List.of("SUCCESS", "DEGRADED", "UNAVAILABLE").contains(text(payload.get("default_scenario")))) {
            errors.add("模拟接口默认场景无效");
        }
        long timeout = number(payload.get("timeout_ms"));
        long retry = number(payload.get("retry_limit"));
        long count = number(payload.getOrDefault("default_record_count", 36));
        if (timeout < 100 || timeout > 120_000) errors.add("超时必须为 100 至 120000 毫秒");
        if (retry < 0 || retry > 10) errors.add("重试次数必须为 0 至 10");
        if (count < 12 || count > 200) errors.add("每批记录数必须为 12 至 200");
        Map<String, Object> standards = objectValue(payload.get("china_standard_profile"));
        if (!"WS/T 846.1-846.11—2024".equals(text(standards.get("hospital_platform")))
                || !"WS/T 847—2024".equals(text(standards.get("hospital_platform_function")))) {
            errors.add("中国医院信息平台仿真必须绑定 WS/T 846/847—2024 配置");
        }
        if (Boolean.TRUE.equals(standards.get("cross_border_allowed"))) {
            errors.add("模拟接口默认禁止医疗健康数据跨境");
        }
        Map<String, Object> agent = objectValue(payload.get("agent_policy"));
        if (!Boolean.FALSE.equals(agent.get("clinical_write_allowed"))) {
            errors.add("模拟接口 Agent 必须明确禁止临床事实写入");
        }
        if ("LIS_RESULTS".equals(text(payload.get("interface_code")))) {
            Map<String, Object> critical = objectValue(payload.get("critical_value_policy"));
            if (text(critical.get("policy_code")).isBlank()
                    || !Boolean.TRUE.equals(critical.get("requires_reporter_receiver_ack"))
                    || !Boolean.TRUE.equals(critical.get("requires_closed_loop"))) {
                errors.add("LIS 仿真配置必须包含院级危急值复核、报告接收和闭环策略");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static List<String> qualityOperationFields() {
        return List.of("schema_version", "module_id", "owner", "scope", "severity", "workflow_status",
                "due_at", "score", "description", "flow_impact");
    }

    private void validateQualityOperation(String configType, Map<String, Object> payload, List<String> errors) {
        if (number(payload.get("schema_version")) < 2) {
            errors.add("中国医疗生产质量工作项必须使用 schema_version 2");
        }
        if (text(payload.get("china_policy_basis")).length() < 4) errors.add("必须关联中国医疗制度或院级制度依据");
        if (text(payload.get("source_reference")).length() < 4) errors.add("必须关联可追溯的权威来源引用");
        String severity = text(payload.get("severity"));
        if (!List.of("INFO", "WARNING", "BLOCKING").contains(severity)) {
            errors.add("质量工作项风险等级无效");
        }
        long score = number(payload.get("score"));
        if (score < 0 || score > 100) errors.add("质量评分必须位于 0 到 100");
        if (text(payload.get("description")).length() < 4) errors.add("质量工作项说明至少 4 个字符");
        if (text(payload.get("flow_impact")).length() < 4) errors.add("必须说明对业务流程的影响");
        try {
            OffsetDateTime.parse(text(payload.get("due_at")));
        } catch (RuntimeException invalid) {
            errors.add("质量工作项完成时限必须为带时区的 ISO-8601 时间");
        }
        List<String> allowedStatuses = switch (configType) {
            case "QUALITY_INITIATIVE" -> List.of("MONITORING", "IMPROVING", "REVIEW", "CLOSED");
            case "DEPARTMENT_QC_CASE" -> List.of("OPEN", "REMEDIATING", "REVIEW", "CLOSED");
            case "QUALITY_RATING_EVIDENCE" -> List.of("GAP", "COLLECTING", "READY", "VERIFIED");
            case "INFECTION_CONTROL_CASE" -> List.of("REPORTED", "INVESTIGATING", "CONTROLLED", "CLOSED");
            case "CLINICAL_CREDENTIAL_GRANT" -> List.of("PENDING", "ACTIVE", "EXPIRING", "REVOKED");
            default -> List.of();
        };
        if (!allowedStatuses.contains(text(payload.get("workflow_status")))) {
            errors.add("质量工作项流程状态与子菜单不匹配");
        }
        switch (configType) {
            case "QUALITY_INITIATIVE" -> {
                if (text(payload.get("indicator_code")).length() < 2) errors.add("院级质量改进项目必须关联质量指标编码");
            }
            case "DEPARTMENT_QC_CASE" -> {
                if (text(payload.get("core_system_code")).length() < 2) errors.add("院科质控必须关联核心制度编码");
                if (text(payload.get("sample_method")).length() < 4) errors.add("院科质控必须说明抽样方法");
                long sampleSize = number(payload.get("sample_size"));
                if (sampleSize < 1 || sampleSize > 100_000) errors.add("抽样样本量必须位于 1 至 100000");
            }
            case "QUALITY_RATING_EVIDENCE" -> {
                if (!"EMR_LEVEL_0_8_39_ITEMS".equals(text(payload.get("evaluation_framework")))) {
                    errors.add("评级取证必须明确电子病历 0至8 级 39 项评价框架");
                }
                if (text(payload.get("evaluation_project_code")).length() < 2) errors.add("评级取证必须关联评价项目编码");
                if (!List.of("FUNCTION", "SCOPE", "DATA_QUALITY", "EFFECTIVENESS")
                        .contains(text(payload.get("evaluation_dimension")))) errors.add("评级维度必须为功能、应用范围、数据质量或实效");
            }
            case "INFECTION_CONTROL_CASE" -> {
                if (!List.of("HAI_CASE", "HAI_OUTBREAK", "NOTIFIABLE_DISEASE")
                        .contains(text(payload.get("event_category")))) errors.add("院感工作项事件类别无效");
                if (!List.of(2L, 24L).contains(number(payload.get("reporting_window_hours")))) {
                    errors.add("院感/传染病上报时限必须明确为 2 小时或 24 小时");
                }
            }
            case "CLINICAL_CREDENTIAL_GRANT" -> {
                if (!List.of("PRESCRIPTION", "ANTIMICROBIAL", "CONTROLLED_DRUG", "SURGERY", "PROCEDURE", "TEMPORARY")
                        .contains(text(payload.get("authorization_category")))) errors.add("临床资质授权类别无效");
                if (text(payload.get("clinical_action_code")).length() < 2) errors.add("临床资质必须关联实时鉴权动作编码");
                if (!Boolean.TRUE.equals(payload.get("human_review_required"))) errors.add("临床资质授权必须经人工审批");
            }
            default -> { }
        }
    }

    private void validateClinicalTaskRule(Map<String, Object> payload, List<String> errors) {
        long dueMinutes = number(payload.get("due_minutes"));
        long escalationMinutes = number(payload.get("escalation_minutes"));
        if (dueMinutes < 1 || dueMinutes > 43_200) errors.add("任务处理时限必须为 1 至 43200 分钟");
        if (escalationMinutes < 1 || escalationMinutes >= dueMinutes) {
            errors.add("升级提前量必须大于 0 且小于任务处理时限");
        }
        String riskLevel = text(payload.get("risk_level"));
        if (!List.of("ROUTINE", "HIGH", "CRITICAL").contains(riskLevel)) errors.add("任务风险等级无效");
        if (!"权威业务对象终态".equals(text(payload.get("completion_source")))) {
            errors.add("任务完成依据必须为权威业务对象终态");
        }
    }

    private void validateClinicalPathway(Map<String, Object> payload, List<String> errors) {
        if (number(payload.get("version_no")) < 1) errors.add("临床路径版本号必须为正整数");
        if (text(payload.get("admission_criteria")).length() < 4) errors.add("临床路径必须配置完整入径标准");
        List<Map<String, Object>> stages = objectRows(payload.get("stages"));
        if (stages.isEmpty()) errors.add("临床路径至少包含一个执行阶段");
        List<String> codes = stages.stream().map(stage -> text(stage.get("code"))).toList();
        if (codes.stream().anyMatch(String::isBlank) || codes.stream().distinct().count() != codes.size()) {
            errors.add("临床路径阶段编码必须非空且唯一");
        }
        if (!Boolean.TRUE.equals(payload.get("version_immutable_after_publish"))) {
            errors.add("临床路径发布后必须保持版本不可变");
        }
    }

    private void validateWorkflow(Map<String, Object> payload, List<String> errors) {
        List<Map<String, Object>> nodes = objectRows(payload.get("nodes"));
        List<Map<String, Object>> edges = objectRows(payload.get("edges"));
        if (nodes.isEmpty()) {
            errors.add("Schema v3 流程节点必须使用结构化对象，禁止字符串列表兼容回退");
            return;
        }
        List<String> ids = nodes.stream().map(node -> text(node.get("id"))).filter(value -> !value.isBlank()).toList();
        if (ids.size() != nodes.size()) errors.add("每个流程节点都必须配置唯一 id");
        if (ids.stream().distinct().count() != ids.size()) errors.add("流程节点 id 不能重复");
        if (nodes.stream().noneMatch(node -> "START".equals(text(node.get("type"))))) errors.add("流程必须包含 START 节点");
        if (nodes.stream().noneMatch(node -> Boolean.TRUE.equals(node.get("terminal")) || "END".equals(text(node.get("type"))))) {
            errors.add("流程必须包含至少一个可达终态");
        }
        for (Map<String, Object> node : nodes) {
            if (!"START".equals(text(node.get("type"))) && text(node.get("owner")).isBlank()) {
                errors.add("节点 " + text(node.get("name")) + " 缺少责任角色");
            }
        }
        for (Map<String, Object> edge : edges) {
            String from = text(edge.get("from")); String to = text(edge.get("to"));
            if (!ids.contains(from) || !ids.contains(to)) errors.add("流程连线引用不存在节点：" + from + " -> " + to);
            if (from.equals(to)) errors.add("流程节点不得自循环：" + from);
            if (text(edge.get("condition")).isBlank()) errors.add("流程连线必须配置条件：" + from + " -> " + to);
            if (text(edge.get("event_code")).isBlank()) errors.add("流程连线必须配置可执行事件编码：" + from + " -> " + to);
            if (!(edge.get("guard") instanceof Map<?, ?>)) errors.add("流程连线必须配置结构化 guard：" + from + " -> " + to);
        }
        List<String> eventCodes = edges.stream().map(edge -> text(edge.get("event_code"))).filter(code -> !code.isBlank()).toList();
        if (eventCodes.stream().distinct().count() != eventCodes.size()) errors.add("流程事件编码不能重复");
        if (nodes.stream().noneMatch(node -> "SIGN".equals(text(node.get("type"))) && Boolean.TRUE.equals(node.get("protected")))) {
            errors.add("流程必须保留受保护的签署节点");
        }
        if (nodes.stream().noneMatch(node -> "AUDIT".equals(text(node.get("type"))) && Boolean.TRUE.equals(node.get("protected")))) {
            errors.add("流程必须保留受保护的审计节点");
        }
    }

    private void validateFormTemplate(Map<String, Object> payload, List<String> errors) {
        List<Map<String, Object>> fields = objectRows(payload.get("fields"));
        if (fields.isEmpty()) {
            errors.add("Schema v3 表单字段必须使用结构化对象，禁止字符串列表兼容回退");
            return;
        }
        List<String> ids = fields.stream().map(field -> text(field.get("id"))).filter(value -> !value.isBlank()).toList();
        if (ids.size() != fields.size()) errors.add("每个表单字段都必须配置唯一 id");
        if (ids.stream().distinct().count() != ids.size()) errors.add("表单字段 id 不能重复");
        for (Map<String, Object> field : fields) {
            String label = text(field.get("label"));
            if (label.isBlank() || text(field.get("group")).isBlank()) errors.add("表单字段必须配置名称和分组：" + text(field.get("id")));
            if ("CALCULATED".equals(text(field.get("type"))) && text(field.get("calculation")).isBlank()) {
                errors.add("计算字段缺少表达式：" + label);
            }
            if (Boolean.TRUE.equals(field.get("required")) && "NEVER".equals(text(field.get("visibility")))) {
                errors.add("必填字段不可设置为永不显示：" + label);
            }
            if ("CODE".equals(text(field.get("type"))) && text(field.get("terminology")).isBlank()) {
                errors.add("编码字段必须绑定受控术语和值集：" + label);
            }
            if (!text(field.get("terminology")).isBlank()
                    && !(text(field.get("terminology")).startsWith("WS/T-363-2023")
                    || text(field.get("terminology")).startsWith("ICD-10-NATIONAL-CLINICAL")
                    || text(field.get("terminology")).startsWith("ICD-9-CM-3-NATIONAL-CLINICAL")
                    || text(field.get("terminology")).startsWith("NMPA-DRUG-CODE")
                    || text(field.get("terminology")).startsWith("LOCAL-GOVERNED:"))) {
                errors.add("术语系统未绑定中国医疗受控版本：" + label);
            }
        }
        if (fields.stream().noneMatch(field -> "SIGNATURE".equals(text(field.get("type")))
                && Boolean.TRUE.equals(field.get("required")) && Boolean.TRUE.equals(field.get("protected")))) {
            errors.add("生产病历模板必须包含受保护的可靠电子签名字段");
        }
    }

    private void validateRules(Map<String, Object> payload, List<String> errors) {
        List<Map<String, Object>> rules = objectRows(payload.get("rules"));
        if (rules.isEmpty()) errors.add("Schema v3 规则集至少包含一条结构化规则");
        for (Map<String, Object> rule : rules) {
            String layer = text(rule.get("layer")); String name = text(rule.get("name"));
            if (layer.contains("HARD") && text(rule.get("evidence")).isBlank()) errors.add("硬规则缺少证据来源：" + name);
            if ("AI_ADVICE".equals(layer) && text(rule.get("action")).contains("阻断")) errors.add("AI 建议不得直接阻断临床动作：" + name);
            if (text(rule.get("condition")).isBlank() || text(rule.get("action")).isBlank()) errors.add("规则必须配置条件和处置：" + name);
            if (text(rule.get("fact_path")).isBlank() || text(rule.get("operator")).isBlank()) errors.add("规则缺少可执行事实路径或运算符：" + name);
            if (text(rule.get("action_code")).isBlank()) errors.add("规则缺少可执行动作编码：" + name);
            if (layer.contains("HARD") && !(rule.get("evidence_meta") instanceof Map<?, ?> evidence
                    && !text(evidence.get("authority")).isBlank() && !text(evidence.get("version")).isBlank()
                    && !text(evidence.get("review_due")).isBlank())) {
                errors.add("硬规则证据必须包含权威来源、版本和复核期限：" + name);
            }
        }
    }

    private void validateScope(Map<String, Object> payload, List<String> errors) {
        List<Map<String, Object>> permissions = objectRows(payload.get("permissions"));
        if (permissions.isEmpty()) errors.add("Schema v3 职责范围至少包含一条结构化权限");
        for (Map<String, Object> permission : permissions) {
            String role = text(permission.get("role"));
            if ("ALLOW".equals(text(permission.get("effect"))) && "全部患者".equals(text(permission.get("scope")))
                    && number(permission.get("temporary_hours")) <= 0) errors.add("无范围高权限必须设置临时到期：" + role);
            if (number(permission.get("temporary_hours")) > 24) errors.add("临时授权不能超过 24 小时：" + role);
            if (text(permission.get("resource")).isBlank() || text(permission.get("action")).isBlank()) errors.add("授权必须配置资源和动作：" + role);
            if (text(permission.get("role_code")).isBlank() || text(permission.get("resource_code")).isBlank()
                    || text(permission.get("action_code")).isBlank() || text(permission.get("scope_code")).isBlank()) {
                errors.add("授权必须绑定角色、资源、动作和范围主数据编码：" + role);
            }
        }
        for (Map<String, Object> binding : objectRows(payload.get("authorization_bindings"))) {
            String resourceCode = text(binding.get("resource_code"));
            String actionCode = text(binding.get("action_code"));
            String scopeCode = text(binding.get("scope_code"));
            if (resourceCode.isBlank() || actionCode.isBlank() || scopeCode.isBlank()) {
                errors.add("授权引擎绑定必须提供资源、动作和范围编码");
                continue;
            }
            boolean boundPermission = permissions.stream()
                    .anyMatch(permission -> resourceCode.equals(text(permission.get("resource_code")))
                            && actionCode.equals(text(permission.get("action_code")))
                            && scopeCode.equals(text(permission.get("scope_code"))));
            if (!boundPermission) {
                errors.add("授权引擎绑定未对应到范围权限：" + resourceCode + "/" + actionCode + "/" + scopeCode);
            }
        }
        if (!Boolean.TRUE.equals(payload.get("deny_overrides_allow"))) errors.add("职责范围必须启用 DENY 优先策略");
    }

    @SuppressWarnings("unchecked")
    private void validateChinaCompliance(Map<String, Object> payload, List<String> errors) {
        Object value = payload.get("china_compliance");
        if (!(value instanceof Map<?, ?> raw)) {
            errors.add("缺少中国医疗生产合规配置 china_compliance");
            return;
        }
        Map<String, Object> compliance = (Map<String, Object>) raw;
        if (!"CN_MEDICAL_PRODUCTION_2026".equals(text(compliance.get("profile")))) errors.add("中国医疗生产配置 profile 无效");
        if (!text(compliance.get("data_element_standard")).contains("WS/T 363")) errors.add("必须绑定 WS/T 363 数据元标准");
        if (!text(compliance.get("electronic_record_dataset")).contains("WS 445")) errors.add("必须绑定 WS 445 电子病历基本数据集");
        if (!text(compliance.get("diagnosis_code_system")).contains("国家临床版")) errors.add("诊断编码必须绑定国家临床版");
        if (!text(compliance.get("procedure_code_system")).contains("国家临床版")) errors.add("手术操作编码必须绑定国家临床版");
        String signature = text(compliance.get("signature_policy"));
        if (!(signature.contains("CA") && signature.contains("时间戳") && signature.contains("验真"))) errors.add("签名策略必须包含 CA、可信时间戳和验真");
        String retention = text(compliance.get("retention_policy"));
        if (!(retention.contains("15年") && retention.contains("30年") && retention.contains("留痕"))) errors.add("保存策略必须覆盖门急诊15年、住院30年和归档更正留痕");
        if (!Boolean.TRUE.equals(compliance.get("minimum_necessary"))) errors.add("必须启用最小必要数据原则");
        try {
            OffsetDateTime effective = OffsetDateTime.parse(text(compliance.get("effective_from")));
            OffsetDateTime review = OffsetDateTime.parse(text(compliance.get("review_due")));
            if (!review.isAfter(effective)) errors.add("合规配置复核日期必须晚于生效日期");
        } catch (RuntimeException invalid) {
            errors.add("合规配置生效和复核时间必须为带时区 ISO-8601 时间");
        }
    }

    private void validateCapabilityComposition(Map<String, Object> payload, List<String> errors) {
        if (number(payload.get("schema_version")) < 3) errors.add("能力组合必须升级到 schema_version 3");
        List<Map<String, Object>> catalog = objectRows(payload.get("module_catalog"));
        List<String> catalogCodes = catalog.stream().map(item -> text(item.get("code"))).filter(code -> !code.isBlank()).toList();
        if (catalogCodes.size() != catalog.size() || catalogCodes.stream().distinct().count() != catalogCodes.size()) {
            errors.add("能力模块目录编码必须完整且唯一");
        }
        List<String> modules = stringRows(payload.get("selected_modules"));
        for (String module : modules) if (!catalogCodes.contains(module)) errors.add("能力模块未在数据库目录登记：" + module);
        for (Map<String, Object> dependency : objectRows(payload.get("dependencies"))) {
            String module = text(dependency.get("module")); String requires = text(dependency.get("requires"));
            if (modules.contains(module) && !modules.contains(requires)) errors.add("能力模块依赖缺失：" + module + " 需要 " + requires);
        }
        for (Map<String, Object> conflict : objectRows(payload.get("conflicts"))) {
            String left = text(conflict.get("left")); String right = text(conflict.get("right"));
            if (modules.contains(left) && modules.contains(right)) errors.add("能力模块互斥：" + left + " 与 " + right);
        }
        List<String> protectedModules = stringRows(payload.get("protected_modules"));
        for (String module : protectedModules) if (!modules.contains(module)) errors.add("受保护能力模块不能停用：" + module);
        Object rawFramework = payload.get("rating_framework");
        if (!(rawFramework instanceof Map<?, ?> framework)
                || !"INTERNAL_OPERATIONAL_READINESS_V1".equals(text(framework.get("code")))
                || !Boolean.FALSE.equals(framework.get("official_hospital_grade"))) {
            errors.add("能力评级必须声明为内部运行就绪度，禁止冒充医院等级评审结论");
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> objectRows(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty() || !(list.getFirst() instanceof Map<?, ?>)) return List.of();
        return list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList();
    }

    private List<String> stringRows(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Objects::nonNull).map(String::valueOf).toList();
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private long number(Object value) { return value instanceof Number number ? number.longValue() : 0; }

    private ConfigState state(UUID tenantId, UUID configId, boolean lock) {
        String sql = """
                select config_id, config_type, config_key, display_name, payload::text, status, row_version, created_by
                from config_item where tenant_id = :tenant and config_id = :config
                """ + (lock ? " for update" : "");
        return jdbc.sql(sql).param("tenant", tenantId).param("config", configId)
                .query((rs, row) -> new ConfigState(
                        rs.getObject("config_id", UUID.class), rs.getString("config_type"),
                        rs.getString("config_key"), rs.getString("display_name"), payload(rs.getString("payload")),
                        rs.getString("status"), rs.getLong("row_version"),
                        rs.getObject("created_by", UUID.class)))
                .optional().orElseThrow(() -> new ConfigurationException("CONFIG_NOT_FOUND", 404, "配置项不存在"));
    }

    private ConfigurationItemWire item(UUID tenantId, UUID configId) {
        return jdbc.sql("""
                select config_id, config_type, config_key, display_name, payload::text, status,
                       schema_version, validation_state, validation_errors::text, approval_state,
                       approved_by, published_at, row_version, created_at, updated_at
                from config_item where tenant_id = :tenant and config_id = :config
                """).param("tenant", tenantId).param("config", configId)
                .query((rs, row) -> wire(
                        rs.getObject("config_id", UUID.class), rs.getString("config_type"),
                        rs.getString("config_key"), rs.getString("display_name"), rs.getString("payload"),
                        rs.getString("status"), rs.getInt("schema_version"), rs.getString("validation_state"),
                        rs.getString("validation_errors"), rs.getString("approval_state"),
                        rs.getObject("approved_by", UUID.class), rs.getObject("published_at", OffsetDateTime.class),
                        rs.getLong("row_version"), rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)))
                .optional().orElseThrow(() -> new ConfigurationException("CONFIG_NOT_FOUND", 404, "配置项不存在"));
    }

    private ConfigurationItemWire wire(
            UUID configId, String configType, String configKey, String displayName, String payloadJson,
            String status, int schemaVersion, String validationState, String validationErrorsJson,
            String approvalState, UUID approvedBy, OffsetDateTime publishedAt, long rowVersion,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        return new ConfigurationItemWire(
                configId, configType, configKey, displayName, payload(payloadJson),
                ConfigurationItemWire.StatusValue.valueOf(status), schemaVersion,
                ConfigurationItemWire.ValidationStateValue.valueOf(validationState),
                validationErrors(validationErrorsJson),
                ConfigurationItemWire.ApprovalStateValue.valueOf(approvalState), approvedBy,
                publishedAt == null ? null : publishedAt.toInstant(), rowVersion,
                createdAt.toInstant(), updatedAt.toInstant());
    }

    private void saveRevision(ClinicalIdentity identity, UUID configId, String reason) {
        jdbc.sql("""
                insert into config_item_revision(
                  tenant_id, config_id, revision_no, display_name, payload, schema_version,
                  status, validation_state, validation_errors, approval_state, changed_by, change_reason)
                select tenant_id, config_id, row_version, display_name, payload, schema_version,
                       status, validation_state, validation_errors, approval_state, :actor, :reason
                from config_item where tenant_id = :tenant and config_id = :config
                """).param("actor", identity.userId()).param("reason", reason)
                .param("tenant", identity.tenantId()).param("config", configId).update();
    }

    private void requireVersion(ConfigState current, Long expectedVersion) {
        if (expectedVersion == null || current.rowVersion() != expectedVersion) throw versionConflict();
    }

    private void requireMockConfigurationAdmin(ClinicalIdentity identity, String configType) {
        if (!"MOCK_INTERFACE_PROFILE".equals(configType)) return;
        if (identity.roleAssignmentIds().isEmpty()) {
            throw new ConfigurationException("MOCK_CONFIG_ADMIN_REQUIRED", 403,
                    "模拟接口配置只允许集成或系统管理岗位变更");
        }
        long allowed = jdbc.sql("""
                select count(*) from role_assignment
                where tenant_id = :tenant and user_id = :user
                  and role_assignment_id in (:assignments) and role_code in (:roles)
                  and status = 'ACTIVE' and valid_from <= now()
                  and (valid_until is null or valid_until > now())
                """).param("tenant", identity.tenantId()).param("user", identity.userId())
                .param("assignments", identity.roleAssignmentIds())
                .param("roles", MOCK_CONFIGURATION_ADMIN_ROLES).query(Long.class).single();
        if (allowed == 0) {
            throw new ConfigurationException("MOCK_CONFIG_ADMIN_REQUIRED", 403,
                    "当前岗位无权变更模拟接口配置");
        }
    }

    private void requireConfigurationRole(ClinicalIdentity identity, List<String> allowedRoles, String code) {
        if (identity.roleAssignmentIds().isEmpty()) {
            throw new ConfigurationException(code, 403, "当前岗位无权执行配置变更");
        }
        long allowed = jdbc.sql("""
                select count(*) from role_assignment
                where tenant_id = :tenant and user_id = :user
                  and role_assignment_id in (:assignments) and role_code in (:roles)
                  and status = 'ACTIVE' and valid_from <= now()
                  and (valid_until is null or valid_until > now())
                """).param("tenant", identity.tenantId()).param("user", identity.userId())
                .param("assignments", identity.roleAssignmentIds()).param("roles", allowedRoles)
                .query(Long.class).single();
        if (allowed == 0) {
            throw new ConfigurationException(code, 403, "当前岗位无权执行配置变更");
        }
    }

    private void requireState(ConfigState current, String expected) {
        if (!current.status().equals(expected)) {
            throw new ConfigurationException("CONFIG_STATE_CONFLICT", 409,
                    "配置当前状态为 " + current.status() + "，要求 " + expected);
        }
    }

    private ConfigurationException versionConflict() {
        return new ConfigurationException("CONFIG_VERSION_CONFLICT", 409, "配置已被其他用户更新，请刷新后重试");
    }

    private ConfigurationException invalidPayload(List<String> errors) {
        return new ConfigurationException("CONFIG_VALIDATION_FAILED", 422, String.join("；", errors));
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new ConfigurationException("IDEMPOTENCY_REPLAY", 409, "该配置命令已提交");
        }
    }

    private void completeCommand(
            ClinicalIdentity identity, String scope, String key, UUID configId, int responseStatus) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = :status,
                  response_ref = jsonb_build_object('config_id', :config)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("status", responseStatus).param("config", configId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, String action, String configType, UUID configId, long version) {
        String previousHash = jdbc.sql(
                "select event_hash from audit_event where tenant_id = :tenant order by occurred_at desc, audit_event_id desc limit 1")
                .param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|" + configId
                + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, trace_id, previous_hash, event_hash, details)
                values (:tenant, :audit, now(), :actor, :action, 'CONFIG_ITEM', :resource,
                  :trace, :previous, :hash, jsonb_build_object('config_type', :config_type))
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", configId)
                .param("trace", trace).param("previous", previousHash).param("hash", eventHash)
                .param("config_type", configType).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'CONFIG_ITEM', :resource, :version,
                  :event_type, 1, jsonb_build_object('config_type', :config_type))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("resource", configId).param("version", version).param("event_type", action)
                .param("config_type", configType).update();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.convertValue(objectMapper.readTree(json), Map.class);
        } catch (Exception invalid) {
            throw new ConfigurationException("CONFIG_PAYLOAD_INVALID", 500, "存储的配置载荷无效");
        }
    }

    private List<String> validationErrors(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            Object value = objectMapper.convertValue(objectMapper.readTree(json), Object.class);
            if (!(value instanceof List<?> list)) return List.of("validation_errors 存储格式无效");
            return list.stream().map(Objects::toString).toList();
        } catch (Exception invalid) {
            throw new ConfigurationException("CONFIG_PAYLOAD_INVALID", 500, "校验结果载荷无效");
        }
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception invalid) {
            throw new ConfigurationException("CONFIG_PAYLOAD_INVALID", 400, "配置载荷不可序列化");
        }
    }

    private String jsonList(List<String> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception invalid) {
            throw new ConfigurationException("CONFIG_PAYLOAD_INVALID", 400, "配置校验结果不可序列化");
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private record ConfigState(
            UUID configId, String configType, String configKey, String displayName, Map<String, Object> payload,
            String status, long rowVersion, UUID createdBy) {}

    private record Revision(String displayName, Map<String, Object> payload, int schemaVersion) {}
    record ConfigurationRevisionWire(
            long revisionNo, String displayName, Map<String, Object> payload, int schemaVersion,
            String status, String validationState, List<String> validationErrors, String approvalState,
            UUID changedBy, String changeReason, OffsetDateTime createdAt) {}
}
