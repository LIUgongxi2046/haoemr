package org.haoemr.configuration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Executes published business configuration. Every decision is version-bound and auditable. */
@Service
public final class ConfigurationRuntimeService {
    private static final int MAX_FACTS = 128;
    private static final int MAX_TEXT = 4_000;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    public ConfigurationRuntimeService(JdbcClient jdbc, TransactionTemplate transactions, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    RuntimeExecutionWire startWorkflow(
            ClinicalIdentity identity, RuntimeContext context, String idempotencyKey,
            String configKey, RuntimeCommandRequest request) {
        validateCommand(request);
        ActiveConfiguration config = active(identity.tenantId(), "WORKFLOW", configKey);
        List<Map<String, Object>> nodes = objectRows(config.payload().get("nodes"));
        Map<String, Object> start = nodes.stream()
                .filter(node -> "START".equals(text(node.get("type"))))
                .findFirst().orElseThrow(() -> notExecutable("已发布流程缺少 START 节点"));
        String nodeId = text(start.get("id"));
        if (nodeId.isBlank()) throw notExecutable("已发布流程 START 节点缺少 id");
        Map<String, Object> output = map(
                "current_node", nodeId,
                "current_node_name", text(start.get("name")),
                "available_events", availableEvents(config.payload(), nodeId),
                "terminal", false);
        return createExecution(identity, context, idempotencyKey, config, "WORKFLOW_START", request,
                "ACTIVE", nodeId, output);
    }

    /**
     * Starts an optional, published workflow projection for a clinical domain. Domain state
     * remains authoritative: a missing or retired configuration cannot prevent clinical care.
     */
    public Optional<RuntimeExecutionWire> startWorkflowIfActive(
            ClinicalIdentity identity, RuntimeContext context, String idempotencyKey,
            String configKey, RuntimeCommandRequest request) {
        return isActive(identity.tenantId(), "WORKFLOW", configKey)
                ? Optional.of(startWorkflow(identity, context, idempotencyKey, configKey, request))
                : Optional.empty();
    }

    /**
     * Advances the current optional workflow projection by its only available event. This is for
     * a domain that has already completed a deterministic business transition; it never creates
     * the business transition itself or treats configuration state as a clinical fact.
     */
    public Optional<RuntimeExecutionWire> advanceWorkflowIfActive(
            ClinicalIdentity identity, RuntimeContext context, String idempotencyKey,
            String configKey, String subjectType, UUID subjectId, Map<String, Object> facts) {
        UUID executionId = jdbc.sql("""
                select execution.execution_id
                from configuration_runtime_execution execution
                join config_item config on config.tenant_id = execution.tenant_id
                  and config.config_id = execution.config_id
                where execution.tenant_id = :tenant and execution.organization_id = :organization
                  and execution.facility_id = :facility
                  and execution.patient_id is not distinct from :patient
                  and execution.encounter_id is not distinct from :encounter
                  and execution.config_type = 'WORKFLOW' and execution.config_key = :key
                  and execution.subject_type = :subject_type and execution.subject_id = :subject
                  and execution.state = 'ACTIVE' and config.status = 'ACTIVE'
                  and config.validation_state = 'VALID' and config.approval_state = 'APPROVED'
                order by execution.created_at desc, execution.execution_id desc limit 1
                """).param("tenant", identity.tenantId()).param("organization", context.organizationId())
                .param("facility", context.facilityId()).param("patient", context.patientId())
                .param("encounter", context.encounterId()).param("key", configKey)
                .param("subject_type", subjectType).param("subject", subjectId)
                .query(UUID.class).optional().orElse(null);
        if (executionId == null) return Optional.empty();

        RuntimeExecutionWire current = execution(identity.tenantId(), context, executionId);
        List<Map<String, Object>> events = objectRows(current.outputPayload().get("available_events"));
        if (events.size() != 1) {
            throw notExecutable("临床流程投影当前节点必须恰有一个可用事件");
        }
        String eventCode = text(events.getFirst().get("event_code"));
        if (eventCode.isBlank()) throw notExecutable("临床流程投影当前节点缺少事件编码");
        Map<String, Object> effectiveFacts = new LinkedHashMap<>(safeMap(facts));
        effectiveFacts.put("events", map(current.currentNode(), map("completed", true)));
        return Optional.of(transitionWorkflow(identity, context, idempotencyKey, executionId,
                new RuntimeTransitionRequest(current.rowVersion(), eventCode, effectiveFacts)));
    }

    RuntimeExecutionWire transitionWorkflow(
            ClinicalIdentity identity, RuntimeContext context, String idempotencyKey,
            UUID executionId, RuntimeTransitionRequest request) {
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 1
                || request.eventCode() == null || request.eventCode().isBlank()
                || request.eventCode().length() > 128) {
            throw invalid("流程迁移必须提供当前版本和事件编码");
        }
        validateFacts(request.facts());
        return transactions.execute(status -> {
            RuntimeState current = runtimeState(identity.tenantId(), context, executionId, true);
            if (!"WORKFLOW".equals(current.configType()) || !"ACTIVE".equals(current.state())) {
                throw new ConfigurationException("CONFIG_RUNTIME_STATE_CONFLICT", 409, "只有活动流程实例可以迁移");
            }
            if (current.rowVersion() != request.expectedVersion()) {
                throw new ConfigurationException("CONFIG_RUNTIME_VERSION_CONFLICT", 409, "流程实例已变化，请刷新后重试");
            }
            ActiveConfiguration config = activeById(identity.tenantId(), current.configId());
            if (config.rowVersion() != current.configRowVersion()) {
                throw new ConfigurationException("CONFIG_RUNTIME_WATERMARK_STALE", 409, "流程实例绑定的配置版本已失效，需重新发起");
            }
            Map<String, Object> edge = objectRows(config.payload().get("edges")).stream()
                    .filter(item -> current.currentNode().equals(text(item.get("from"))))
                    .filter(item -> request.eventCode().equals(eventCode(item)))
                    .findFirst().orElseThrow(() -> new ConfigurationException(
                            "CONFIG_RUNTIME_EVENT_DENIED", 409, "当前节点不允许事件：" + request.eventCode()));
            if (!guardMatches(edge.get("guard"), request.facts())) {
                throw new ConfigurationException("CONFIG_RUNTIME_GUARD_DENIED", 409, "流程迁移条件未满足");
            }
            String target = text(edge.get("to"));
            Map<String, Object> node = objectRows(config.payload().get("nodes")).stream()
                    .filter(item -> target.equals(text(item.get("id"))))
                    .findFirst().orElseThrow(() -> notExecutable("流程迁移目标节点不存在"));
            boolean terminal = Boolean.TRUE.equals(node.get("terminal")) || "END".equals(text(node.get("type")));
            Map<String, Object> output = map(
                    "previous_node", current.currentNode(), "event_code", request.eventCode(),
                    "current_node", target, "current_node_name", text(node.get("name")),
                    "available_events", terminal ? List.of() : availableEvents(config.payload(), target),
                    "terminal", terminal);
            String requestHash = sha256(executionId + "|" + request.expectedVersion() + "|" + request.eventCode()
                    + "|" + json(request.facts()));
            beginCommand(identity, "CONFIG_RUNTIME_TRANSITION", idempotencyKey, requestHash);
            int updated = jdbc.sql("""
                    update configuration_runtime_execution set state = :state, current_node = :node,
                      input_payload = cast(:input as jsonb), output_payload = cast(:output as jsonb),
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and execution_id = :execution and row_version = :version
                    """).param("state", terminal ? "COMPLETED" : "ACTIVE").param("node", target)
                    .param("input", json(map("event_code", request.eventCode(), "facts", safeMap(request.facts()))))
                    .param("output", json(output)).param("tenant", identity.tenantId())
                    .param("execution", executionId).param("version", request.expectedVersion()).update();
            if (updated != 1) throw new ConfigurationException(
                    "CONFIG_RUNTIME_VERSION_CONFLICT", 409, "流程实例已变化，请刷新后重试");
            completeCommand(identity, "CONFIG_RUNTIME_TRANSITION", idempotencyKey, executionId, 200);
            appendEvidence(identity, config, executionId, terminal ? "CONFIG_WORKFLOW_COMPLETED" : "CONFIG_WORKFLOW_TRANSITIONED");
            return execution(identity.tenantId(), context, executionId);
        });
    }

    RuntimeExecutionWire validateForm(
            ClinicalIdentity identity, RuntimeContext context, String idempotencyKey,
            String configKey, RuntimeCommandRequest request) {
        validateCommand(request);
        ActiveConfiguration config = active(identity.tenantId(), "FORM_TEMPLATE", configKey);
        Map<String, Object> values = safeMap(request.facts());
        List<String> errors = new ArrayList<>();
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> field : objectRows(config.payload().get("fields"))) {
            String id = text(field.get("id"));
            Object value = values.get(id);
            if (Boolean.TRUE.equals(field.get("required")) && blank(value)) errors.add(text(field.get("label")) + " 为必填项");
            if (!blank(value) && !valueMatches(text(field.get("type")), value)) errors.add(text(field.get("label")) + " 类型不匹配");
            normalized.add(map("field_id", id, "label", text(field.get("label")), "value", value,
                    "terminology", text(field.get("terminology")), "protected", Boolean.TRUE.equals(field.get("protected"))));
        }
        Map<String, Object> output = map(
                "valid", errors.isEmpty(), "errors", List.copyOf(errors), "normalized_fields", normalized,
                "template_version", config.rowVersion(), "print_template", config.payload().get("print_template"));
        return createExecution(identity, context, idempotencyKey, config, "FORM_VALIDATE", request,
                errors.isEmpty() ? "PASSED" : "BLOCKED", null, output);
    }

    public RuntimeExecutionWire evaluateRules(
            ClinicalIdentity identity, RuntimeContext context, String idempotencyKey,
            String configKey, RuntimeCommandRequest request) {
        validateCommand(request);
        ActiveConfiguration config = active(identity.tenantId(), "RULE", configKey);
        Map<String, Object> facts = safeMap(request.facts());
        List<Map<String, Object>> decisions = objectRows(config.payload().get("rules")).stream()
                .filter(rule -> !Boolean.FALSE.equals(rule.get("enabled")))
                .sorted(Comparator.comparingLong((Map<String, Object> rule) -> number(rule.get("priority"))).reversed())
                .filter(rule -> ruleMatches(rule, facts))
                .map(rule -> {
                    String layer = text(rule.get("layer"));
                    boolean blocking = layer.contains("HARD") && !"AI_ADVICE".equals(layer);
                    return map("rule_id", text(rule.get("id")), "name", text(rule.get("name")),
                            "layer", layer, "decision", blocking ? "BLOCK" : "ADVISE",
                            "action_code", textOr(rule.get("action_code"), "CREATE_REVIEW_TASK"),
                            "action", text(rule.get("action")), "evidence", rule.get("evidence_meta") == null
                                    ? text(rule.get("evidence")) : rule.get("evidence_meta"),
                            "human_approval_required", blocking || "AI_ADVICE".equals(layer));
                }).toList();
        boolean blocked = decisions.stream().anyMatch(decision -> "BLOCK".equals(decision.get("decision")));
        Map<String, Object> output = map("blocked", blocked, "decision_count", decisions.size(),
                "decisions", decisions, "human_final_decision_required", decisions.stream()
                        .anyMatch(decision -> Boolean.TRUE.equals(decision.get("human_approval_required"))));
        return createExecution(identity, context, idempotencyKey, config, "RULE_EVALUATE", request,
                blocked ? "BLOCKED" : "PASSED", null, output);
    }

    /**
     * Lets a clinical domain consume an explicitly bound, published rule set without making an
     * absent optional configuration disable that domain's own deterministic safety rules.
     */
    public Optional<RuntimeExecutionWire> evaluateRulesIfActive(
            ClinicalIdentity identity, RuntimeContext context, String idempotencyKey,
            String configKey, RuntimeCommandRequest request) {
        boolean active = jdbc.sql("""
                select exists(select 1 from config_item
                  where tenant_id = :tenant and config_type = 'RULE' and config_key = :key and status = 'ACTIVE')
                """).param("tenant", identity.tenantId()).param("key", configKey)
                .query(Boolean.class).single();
        return active ? Optional.of(evaluateRules(identity, context, idempotencyKey, configKey, request))
                : Optional.empty();
    }

    RuntimeExecutionWire authorizeScope(
            ClinicalIdentity identity, RuntimeContext context, String idempotencyKey,
            String configKey, RuntimeCommandRequest request) {
        validateCommand(request);
        ActiveConfiguration config = active(identity.tenantId(), "SCOPE", configKey);
        Map<String, Object> facts = safeMap(request.facts());
        String role = requiredFact(facts, "role");
        String resource = requiredFact(facts, "resource");
        String action = requiredFact(facts, "action");
        String scope = requiredFact(facts, "scope");
        List<Map<String, Object>> matching = objectRows(config.payload().get("permissions")).stream()
                .filter(permission -> equalsCode(permission, "role", "role_code", role))
                .filter(permission -> equalsCode(permission, "resource", "resource_code", resource))
                .filter(permission -> equalsCode(permission, "action", "action_code", action))
                .filter(permission -> equalsCode(permission, "scope", "scope_code", scope))
                .toList();
        boolean denied = matching.stream().anyMatch(item -> "DENY".equals(text(item.get("effect"))));
        boolean allowed = !denied && matching.stream().anyMatch(item -> "ALLOW".equals(text(item.get("effect"))));
        if (allowed && matching.stream().anyMatch(item -> Boolean.TRUE.equals(item.get("relationship_required")))
                && !Boolean.TRUE.equals(facts.get("patient_relationship_verified"))) allowed = false;
        if (allowed && matching.stream().anyMatch(item -> Boolean.TRUE.equals(item.get("shift_required")))
                && !Boolean.TRUE.equals(facts.get("active_shift_verified"))) allowed = false;
        Map<String, Object> output = map("authorized", allowed, "decision", allowed ? "ALLOW" : "DENY",
                "deny_overrides_allow", true, "matched_permissions", matching,
                "minimum_necessary", config.payload().getOrDefault("minimum_necessary", true));
        return createExecution(identity, context, idempotencyKey, config, "SCOPE_AUTHORIZE", request,
                allowed ? "PASSED" : "DENIED", null, output);
    }

    /**
     * Applies active scope configurations explicitly bound to an ABAC resource/action/scope tuple.
     * Scope configurations can only further restrict an already-authorized decision; callers must
     * never use this result to grant a denied request.
     */
    public ScopeAuthorizationOutcome authorizeScopesForAuthorizationIfBound(
            ClinicalIdentity identity, RuntimeContext context, String resourceCode, String actionCode,
            String scopeCode, List<String> roleCodes, boolean patientRelationshipVerified,
            boolean activeShiftVerified) {
        String binding = json(map("authorization_bindings", List.of(map(
                "resource_code", resourceCode, "action_code", actionCode, "scope_code", scopeCode))));
        List<String> configKeys = jdbc.sql("""
                select config_key from config_item
                where tenant_id = :tenant and config_type = 'SCOPE' and status = 'ACTIVE'
                  and validation_state = 'VALID' and approval_state = 'APPROVED'
                  and payload @> cast(:binding as jsonb)
                order by config_key
                """).param("tenant", identity.tenantId()).param("binding", binding)
                .query(String.class).list();
        if (configKeys.isEmpty()) return new ScopeAuthorizationOutcome(true, List.of());

        List<RuntimeExecutionWire> executions = new ArrayList<>();
        UUID subjectId = context.encounterId() != null ? context.encounterId() : context.patientId();
        for (String configKey : configKeys) {
            boolean anyAllowed = false;
            boolean anyDenied = false;
            for (String roleCode : roleCodes.stream().filter(role -> role != null && !role.isBlank()).distinct().toList()) {
                RuntimeExecutionWire execution = authorizeScope(identity, context,
                        "authorization-scope-" + UUID.randomUUID(), configKey,
                        new RuntimeCommandRequest("CLINICAL_CONTEXT", subjectId, map(
                                "role", roleCode, "resource", resourceCode, "action", actionCode,
                                "scope", scopeCode,
                                "patient_relationship_verified", patientRelationshipVerified,
                                "active_shift_verified", activeShiftVerified)));
                executions.add(execution);
                List<Map<String, Object>> matching = objectRows(execution.outputPayload().get("matched_permissions"));
                anyDenied |= matching.stream().anyMatch(item -> "DENY".equals(text(item.get("effect"))));
                anyAllowed |= Boolean.TRUE.equals(execution.outputPayload().get("authorized"));
            }
            if (anyDenied || !anyAllowed) return new ScopeAuthorizationOutcome(false, List.copyOf(executions));
        }
        return new ScopeAuthorizationOutcome(true, List.copyOf(executions));
    }

    List<RuntimeExecutionWire> listExecutions(
            ClinicalIdentity identity, RuntimeContext context, String configType, String configKey) {
        StringBuilder sql = new StringBuilder("""
                select execution_id from configuration_runtime_execution
                where tenant_id = :tenant and organization_id = :organization and facility_id = :facility
                  and patient_id is not distinct from :patient
                  and encounter_id is not distinct from :encounter
                """);
        if (configType != null && !configType.isBlank()) sql.append(" and config_type = :type");
        if (configKey != null && !configKey.isBlank()) sql.append(" and config_key = :key");
        sql.append(" order by created_at desc, execution_id desc limit 100");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("tenant", identity.tenantId())
                .param("organization", context.organizationId()).param("facility", context.facilityId())
                .param("patient", context.patientId()).param("encounter", context.encounterId());
        if (configType != null && !configType.isBlank()) spec = spec.param("type", configType);
        if (configKey != null && !configKey.isBlank()) spec = spec.param("key", configKey);
        return spec.query(UUID.class).list().stream()
                .map(id -> execution(identity.tenantId(), context, id)).toList();
    }

    RuntimeExecutionWire getExecution(ClinicalIdentity identity, RuntimeContext context, UUID executionId) {
        return execution(identity.tenantId(), context, executionId);
    }

    List<RuntimeEvidenceWire> evidence(ClinicalIdentity identity, RuntimeContext context, UUID executionId) {
        execution(identity.tenantId(), context, executionId);
        return jdbc.sql("""
                select audit_event_id, occurred_at, actor_user_id, action_code, trace_id,
                  previous_hash, event_hash, details::text
                from audit_event where tenant_id = :tenant
                  and resource_type = 'CONFIG_RUNTIME_EXECUTION' and resource_id = :execution
                order by occurred_at desc, audit_event_id desc limit 200
                """).param("tenant", identity.tenantId()).param("execution", executionId)
                .query((rs, row) -> new RuntimeEvidenceWire(
                        rs.getObject("audit_event_id", UUID.class),
                        rs.getObject("occurred_at", OffsetDateTime.class),
                        rs.getObject("actor_user_id", UUID.class), rs.getString("action_code"),
                        rs.getString("trace_id"), rs.getString("previous_hash"), rs.getString("event_hash"),
                        payload(rs.getString("details")))).list();
    }

    public List<Map<String, Object>> activeConfigurationsForAgent(UUID tenantId) {
        return jdbc.sql("""
                select config_id, config_type, config_key, display_name, row_version, published_at,
                  payload - 'secret_reference' - 'credentials' - 'api_key' as safe_payload
                from config_item where tenant_id = :tenant and status = 'ACTIVE'
                  and config_type in ('WORKFLOW','FORM_TEMPLATE','RULE','SCOPE','CAPABILITY_PACK_COMPOSITION')
                order by config_type, config_key limit 100
                """).param("tenant", tenantId).query((rs, row) -> map(
                        "config_id", rs.getObject("config_id", UUID.class),
                        "config_type", rs.getString("config_type"), "config_key", rs.getString("config_key"),
                "display_name", rs.getString("display_name"), "row_version", rs.getLong("row_version"),
                        "source_type", "CONFIGURATION", "source_id", rs.getObject("config_id", UUID.class),
                        "version", rs.getLong("row_version"),
                        "published_at", rs.getObject("published_at", OffsetDateTime.class),
                        "payload", redact(payload(rs.getString("safe_payload"))))).list();
    }

    private RuntimeExecutionWire createExecution(
            ClinicalIdentity identity, RuntimeContext context, String idempotencyKey,
            ActiveConfiguration config, String operation, RuntimeCommandRequest request,
            String state, String currentNode, Map<String, Object> output) {
        return transactions.execute(status -> {
            UUID executionId = UUID.randomUUID();
            Map<String, Object> input = map("facts", safeMap(request.facts()));
            String requestHash = sha256(config.configId() + "|" + config.rowVersion() + "|" + operation + "|"
                    + request.subjectType() + "|" + request.subjectId() + "|" + json(input));
            beginCommand(identity, "CONFIG_RUNTIME_" + operation, idempotencyKey, requestHash);
            jdbc.sql("""
                    insert into configuration_runtime_execution(
                      tenant_id, execution_id, organization_id, facility_id, patient_id, encounter_id,
                      config_id, config_type, config_key, config_row_version,
                      operation, subject_type, subject_id, state, current_node, input_payload,
                      output_payload, configuration_watermark, executed_by)
                    values (:tenant, :execution, :organization, :facility, :patient, :encounter,
                      :config, :type, :key, :config_version,
                      :operation, :subject_type, :subject_id, :state, :node, cast(:input as jsonb),
                      cast(:output as jsonb), :watermark, :actor)
                    """).param("tenant", identity.tenantId()).param("execution", executionId)
                    .param("organization", context.organizationId()).param("facility", context.facilityId())
                    .param("patient", context.patientId()).param("encounter", context.encounterId())
                    .param("config", config.configId()).param("type", config.configType()).param("key", config.configKey())
                    .param("config_version", config.rowVersion()).param("operation", operation)
                    .param("subject_type", blankToNull(request.subjectType())).param("subject_id", request.subjectId())
                    .param("state", state).param("node", currentNode).param("input", json(input))
                    .param("output", json(output)).param("watermark", config.watermark())
                    .param("actor", identity.userId()).update();
            completeCommand(identity, "CONFIG_RUNTIME_" + operation, idempotencyKey, executionId, 201);
            appendEvidence(identity, config, executionId, "CONFIG_" + operation + "_EXECUTED");
            return execution(identity.tenantId(), context, executionId);
        });
    }

    private ActiveConfiguration active(UUID tenantId, String type, String key) {
        return jdbc.sql("""
                select config_id, config_type, config_key, payload::text, row_version, published_at
                from config_item where tenant_id = :tenant and config_type = :type and config_key = :key
                  and status = 'ACTIVE' and validation_state = 'VALID' and approval_state = 'APPROVED'
                """).param("tenant", tenantId).param("type", type).param("key", key)
                .query((rs, row) -> activeRow(rs.getObject("config_id", UUID.class), rs.getString("config_type"),
                        rs.getString("config_key"), rs.getString("payload"), rs.getLong("row_version"),
                        rs.getObject("published_at", OffsetDateTime.class)))
                .optional().orElseThrow(() -> new ConfigurationException(
                "CONFIG_RUNTIME_ACTIVE_NOT_FOUND", 404, "未找到已校验、已审批且已发布的运行时配置：" + key));
    }

    private boolean isActive(UUID tenantId, String type, String key) {
        return jdbc.sql("""
                select exists(select 1 from config_item
                  where tenant_id = :tenant and config_type = :type and config_key = :key
                    and status = 'ACTIVE' and validation_state = 'VALID' and approval_state = 'APPROVED')
                """).param("tenant", tenantId).param("type", type).param("key", key)
                .query(Boolean.class).single();
    }

    private ActiveConfiguration activeById(UUID tenantId, UUID configId) {
        return jdbc.sql("""
                select config_id, config_type, config_key, payload::text, row_version, published_at
                from config_item where tenant_id = :tenant and config_id = :config and status = 'ACTIVE'
                  and validation_state = 'VALID' and approval_state = 'APPROVED'
                """).param("tenant", tenantId).param("config", configId)
                .query((rs, row) -> activeRow(rs.getObject("config_id", UUID.class), rs.getString("config_type"),
                        rs.getString("config_key"), rs.getString("payload"), rs.getLong("row_version"),
                        rs.getObject("published_at", OffsetDateTime.class)))
                .optional().orElseThrow(() -> new ConfigurationException(
                        "CONFIG_RUNTIME_WATERMARK_STALE", 409, "执行绑定的配置已经停用或被替换"));
    }

    private ActiveConfiguration activeRow(
            UUID id, String type, String key, String payloadJson, long rowVersion, OffsetDateTime publishedAt) {
        String watermark = "CONFIG:" + type + ":" + key + ":v" + rowVersion + ":" + sha256(payloadJson).substring(0, 16);
        return new ActiveConfiguration(id, type, key, payload(payloadJson), rowVersion, publishedAt, watermark);
    }

    private RuntimeState runtimeState(UUID tenantId, RuntimeContext context, UUID executionId, boolean lock) {
        String sql = """
                select execution_id, config_id, config_type, config_key, config_row_version,
                  state, current_node, row_version
                from configuration_runtime_execution where tenant_id = :tenant and execution_id = :execution
                  and organization_id = :organization and facility_id = :facility
                  and patient_id is not distinct from :patient
                  and encounter_id is not distinct from :encounter
                """ + (lock ? " for update" : "");
        return jdbc.sql(sql).param("tenant", tenantId).param("execution", executionId)
                .param("organization", context.organizationId()).param("facility", context.facilityId())
                .param("patient", context.patientId()).param("encounter", context.encounterId())
                .query((rs, row) -> new RuntimeState(rs.getObject("execution_id", UUID.class),
                        rs.getObject("config_id", UUID.class), rs.getString("config_type"), rs.getString("config_key"),
                        rs.getLong("config_row_version"), rs.getString("state"), rs.getString("current_node"),
                        rs.getLong("row_version"))).optional().orElseThrow(() -> new ConfigurationException(
                                "CONFIG_RUNTIME_EXECUTION_NOT_FOUND", 404, "配置运行实例不存在"));
    }

    private RuntimeExecutionWire execution(UUID tenantId, RuntimeContext context, UUID executionId) {
        return jdbc.sql("""
                select execution_id, organization_id, facility_id, patient_id, encounter_id,
                  config_id, config_type, config_key, config_row_version, operation,
                  subject_type, subject_id, state, current_node, input_payload::text, output_payload::text,
                  configuration_watermark, executed_by, row_version, created_at, updated_at
                from configuration_runtime_execution where tenant_id = :tenant and execution_id = :execution
                  and organization_id = :organization and facility_id = :facility
                  and patient_id is not distinct from :patient
                  and encounter_id is not distinct from :encounter
                """).param("tenant", tenantId).param("execution", executionId)
                .param("organization", context.organizationId()).param("facility", context.facilityId())
                .param("patient", context.patientId()).param("encounter", context.encounterId())
                .query((rs, row) -> new RuntimeExecutionWire(
                        rs.getObject("execution_id", UUID.class), rs.getObject("organization_id", UUID.class),
                        rs.getObject("facility_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class), rs.getObject("config_id", UUID.class),
                        rs.getString("config_type"), rs.getString("config_key"), rs.getLong("config_row_version"),
                        rs.getString("operation"), rs.getString("subject_type"), rs.getObject("subject_id", UUID.class),
                        rs.getString("state"), rs.getString("current_node"), payload(rs.getString("input_payload")),
                        payload(rs.getString("output_payload")), rs.getString("configuration_watermark"),
                        rs.getObject("executed_by", UUID.class), rs.getLong("row_version"),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class)))
                .optional().orElseThrow(() -> new ConfigurationException(
                        "CONFIG_RUNTIME_EXECUTION_NOT_FOUND", 404, "配置运行实例不存在"));
    }

    private void validateCommand(RuntimeCommandRequest request) {
        if (request == null) throw invalid("运行请求不能为空");
        if ((request.subjectType() == null) != (request.subjectId() == null)) throw invalid("业务主体类型与 ID 必须同时提供");
        if (request.subjectType() != null && (request.subjectType().isBlank() || request.subjectType().length() > 64)) {
            throw invalid("业务主体类型无效");
        }
        validateFacts(request.facts());
    }

    private void validateFacts(Map<String, Object> facts) {
        if (facts == null) return;
        if (facts.size() > MAX_FACTS) throw invalid("运行事实不能超过 " + MAX_FACTS + " 项");
        for (Map.Entry<String, Object> entry : facts.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getKey().length() > 128) throw invalid("运行事实键无效");
            validateFactValue(entry.getValue(), 0);
        }
    }

    private void validateFactValue(Object value, int depth) {
        if (depth > 4) throw invalid("运行事实嵌套不能超过 4 层");
        if (value == null || value instanceof Boolean || value instanceof Number) return;
        if (value instanceof String text && text.length() <= MAX_TEXT) return;
        if (value instanceof List<?> list && list.size() <= 128) {
            list.forEach(item -> validateFactValue(item, depth + 1)); return;
        }
        if (value instanceof Map<?, ?> map && map.size() <= 128) {
            map.forEach((key, item) -> { if (String.valueOf(key).length() > 128) throw invalid("运行事实键过长"); validateFactValue(item, depth + 1); });
            return;
        }
        throw invalid("运行事实包含不受支持或过大的值");
    }

    private boolean ruleMatches(Map<String, Object> rule, Map<String, Object> facts) {
        String factPath = text(rule.get("fact_path"));
        if (factPath.isBlank()) return Boolean.TRUE.equals(facts.get(text(rule.get("id"))));
        Object actual = path(facts, factPath);
        Object expected = rule.get("expected");
        return switch (textOr(rule.get("operator"), "EQ")) {
            case "EQ" -> Objects.equals(String.valueOf(actual), String.valueOf(expected));
            case "NE" -> !Objects.equals(String.valueOf(actual), String.valueOf(expected));
            case "GT" -> decimal(actual) > decimal(expected);
            case "GTE" -> decimal(actual) >= decimal(expected);
            case "LT" -> decimal(actual) < decimal(expected);
            case "LTE" -> decimal(actual) <= decimal(expected);
            case "CONTAINS" -> actual instanceof List<?> list && list.stream().anyMatch(item -> Objects.equals(String.valueOf(item), String.valueOf(expected)));
            case "PRESENT" -> !blank(actual);
            default -> false;
        };
    }

    private boolean guardMatches(Object guard, Map<String, Object> facts) {
        if (!(guard instanceof Map<?, ?> raw) || raw.isEmpty()) return true;
        @SuppressWarnings("unchecked") Map<String, Object> value = (Map<String, Object>) raw;
        return ruleMatches(value, facts == null ? Map.of() : facts);
    }

    private List<Map<String, Object>> availableEvents(Map<String, Object> payload, String nodeId) {
        return objectRows(payload.get("edges")).stream().filter(edge -> nodeId.equals(text(edge.get("from"))))
                .map(edge -> map("event_code", eventCode(edge), "label", text(edge.get("condition")),
                        "target_node", text(edge.get("to")), "guarded", edge.get("guard") instanceof Map<?, ?>))
                .toList();
    }

    private String eventCode(Map<String, Object> edge) {
        String code = text(edge.get("event_code"));
        return code.isBlank() ? text(edge.get("condition")) : code;
    }

    private boolean valueMatches(String type, Object value) {
        return switch (type) {
            case "NUMBER" -> value instanceof Number;
            case "CHECKBOX" -> value instanceof Boolean;
            case "DATE", "DATETIME", "TEXT", "TEXTAREA", "SELECT", "CODE", "SIGNATURE" -> value instanceof String;
            default -> true;
        };
    }

    private boolean equalsCode(Map<String, Object> source, String legacyKey, String codeKey, String expected) {
        return expected.equals(text(source.get(legacyKey))) || expected.equals(text(source.get(codeKey)));
    }

    private Object path(Map<String, Object> facts, String path) {
        Object value = facts;
        for (String part : path.split("\\.")) {
            if (!(value instanceof Map<?, ?> map)) return null;
            value = map.get(part);
        }
        return value;
    }

    private String requiredFact(Map<String, Object> facts, String key) {
        String value = text(facts.get(key));
        if (value.isBlank()) throw invalid("职责判定缺少事实：" + key);
        return value;
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) throw invalid("必须提供有效 Idempotency-Key");
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) throw new ConfigurationException("IDEMPOTENCY_REPLAY", 409, "该运行命令已提交");
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID executionId, int status) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = :status,
                  response_ref = jsonb_build_object('execution_id', :execution)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("status", status).param("execution", executionId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, ActiveConfiguration config, UUID executionId, String action) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        long executionVersion = jdbc.sql("""
                select row_version from configuration_runtime_execution
                where tenant_id = :tenant and execution_id = :execution
                """).param("tenant", identity.tenantId()).param("execution", executionId)
                .query(Long.class).single();
        String previousHash = jdbc.sql("select event_hash from audit_event where tenant_id = :tenant order by occurred_at desc, audit_event_id desc limit 1")
                .param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID(); String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|" + executionId
                + "|" + config.watermark() + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, trace_id, previous_hash, event_hash, details)
                values (:tenant, :audit, now(), :actor, :action, 'CONFIG_RUNTIME_EXECUTION', :resource,
                  :trace, :previous, :hash, jsonb_build_object('config_id', :config, 'watermark', :watermark))
                """).param("tenant", identity.tenantId()).param("audit", auditId).param("actor", identity.userId())
                .param("action", action).param("resource", executionId).param("trace", trace)
                .param("previous", previousHash).param("hash", eventHash).param("config", config.configId())
                .param("watermark", config.watermark()).update();
        jdbc.sql("""
                insert into outbox_event(tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'CONFIG_RUNTIME_EXECUTION', :execution, :version,
                  :event_type, 1, jsonb_build_object('config_id', :config, 'watermark', :watermark))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("execution", executionId).param("version", executionVersion).param("event_type", action)
                .param("config", config.configId()).param("watermark", config.watermark()).update();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> objectRows(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList();
    }

    private Map<String, Object> safeMap(Map<String, Object> value) {
        return value == null ? Map.of() : new LinkedHashMap<>(value);
    }

    private Map<String, Object> redact(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String normalized = key.toLowerCase();
            if (normalized.contains("secret") || normalized.contains("credential")
                    || normalized.contains("password") || normalized.contains("token")
                    || normalized.contains("api_key") || normalized.contains("private_key")) {
                result.put(key, "[REDACTED]");
            } else if (value instanceof Map<?, ?> nested) {
                Map<String, Object> converted = new LinkedHashMap<>();
                nested.forEach((nestedKey, nestedValue) -> converted.put(String.valueOf(nestedKey), nestedValue));
                result.put(key, redact(converted));
            } else if (value instanceof List<?> list) {
                result.put(key, list.stream().map(item -> {
                    if (!(item instanceof Map<?, ?> nested)) return item;
                    Map<String, Object> converted = new LinkedHashMap<>();
                    nested.forEach((nestedKey, nestedValue) -> converted.put(String.valueOf(nestedKey), nestedValue));
                    return redact(converted);
                }).toList());
            } else {
                result.put(key, value);
            }
        });
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(String json) {
        try { return objectMapper.convertValue(objectMapper.readTree(json), Map.class); }
        catch (Exception invalid) { throw new ConfigurationException("CONFIG_PAYLOAD_INVALID", 500, "配置载荷无法读取"); }
    }

    private String json(Map<String, Object> value) {
        try { return objectMapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception invalid) { throw new ConfigurationException("CONFIG_RUNTIME_INPUT_INVALID", 400, "运行载荷不可序列化"); }
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) result.put(String.valueOf(pairs[index]), pairs[index + 1]);
        return result;
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private String textOr(Object value, String fallback) { String text = text(value); return text.isBlank() ? fallback : text; }
    private List<String> stringRows(Object value) {
        if (!(value instanceof List<?> rows)) return List.of();
        return rows.stream().map(this::text).filter(item -> !item.isBlank()).toList();
    }
    private long number(Object value) { return value instanceof Number number ? number.longValue() : 0; }
    private double decimal(Object value) { try { return Double.parseDouble(String.valueOf(value)); } catch (RuntimeException ignored) { return Double.NaN; } }
    private boolean blank(Object value) { return value == null || value instanceof String text && text.isBlank(); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private ConfigurationException invalid(String message) { return new ConfigurationException("CONFIG_RUNTIME_INPUT_INVALID", 400, message); }
    private ConfigurationException notExecutable(String message) { return new ConfigurationException("CONFIG_RUNTIME_NOT_EXECUTABLE", 409, message); }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte current : digest) hex.append(String.format("%02x", current));
            return hex.toString();
        } catch (Exception impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }

    public record RuntimeCommandRequest(String subjectType, UUID subjectId, Map<String, Object> facts) { }
    record RuntimeTransitionRequest(Long expectedVersion, String eventCode, Map<String, Object> facts) { }
    public record RuntimeContext(UUID organizationId, UUID facilityId, UUID patientId, UUID encounterId) {
        public RuntimeContext {
            Objects.requireNonNull(organizationId, "organizationId");
            Objects.requireNonNull(facilityId, "facilityId");
        }
    }
    public record RuntimeExecutionWire(
            UUID executionId, UUID organizationId, UUID facilityId, UUID patientId, UUID encounterId,
            UUID configId, String configType, String configKey, long configRowVersion,
            String operation, String subjectType, UUID subjectId, String state, String currentNode,
            Map<String, Object> inputPayload, Map<String, Object> outputPayload, String configurationWatermark,
            UUID executedBy, long rowVersion, OffsetDateTime createdAt, OffsetDateTime updatedAt) { }
    public record ScopeAuthorizationOutcome(boolean authorized, List<RuntimeExecutionWire> executions) { }
    record RuntimeEvidenceWire(
            UUID auditEventId, OffsetDateTime occurredAt, UUID actorUserId, String actionCode,
            String traceId, String previousHash, String eventHash, Map<String, Object> details) { }
    private record ActiveConfiguration(
            UUID configId, String configType, String configKey, Map<String, Object> payload,
            long rowVersion, OffsetDateTime publishedAt, String watermark) { }
    private record RuntimeState(
            UUID executionId, UUID configId, String configType, String configKey,
            long configRowVersion, String state, String currentNode, long rowVersion) { }
}
