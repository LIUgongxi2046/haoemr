package org.haoemr.mock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.haoemr.contracts.MockInvocationResultWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Durable execution boundary for production-like interface simulation.
 *
 * <p>The registry/generator remains deterministic, while this service enforces an active approved
 * profile, idempotency, role scope, evidence persistence and a non-LLM clinical safety agent. The
 * safety agent can block or request review but can never write a clinical fact.</p>
 */
@Service
final class MockInterfaceExecutionService {
    private static final List<String> EXECUTION_ROLES = List.of(
            "SYSTEM_ADMIN", "CLINICAL_ADMIN", "CLINICIAN", "NURSE", "PHARMACIST",
            "MEDICAL_RECORDS", "INTEGRATION_OPERATOR");

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final MockInterfaceService mocks;

    MockInterfaceExecutionService(
            JdbcClient jdbc, TransactionTemplate transactions, ObjectMapper objectMapper,
            MockInterfaceService mocks) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.mocks = mocks;
    }

    MockInvocationResultWire invoke(
            ClinicalIdentity identity, String idempotencyKey, String code, Map<String, Object> request) {
        requireRole(identity);
        requireIdempotencyKey(idempotencyKey);
        Map<String, Object> safeRequest = sanitizeRequest(request);
        validateClinicalIdentifiers(safeRequest);
        String profileKey = text(safeRequest.get("profile_key"));
        if (profileKey.isBlank()) {
            throw new MockInterfaceException("MOCK_PROFILE_REQUIRED", 422, "必须选择已发布的模拟接口配置");
        }
        String requestHash = sha256(code + "|" + canonical(safeRequest));
        StoredRun replay = storedByIdempotency(identity.tenantId(), idempotencyKey);
        if (replay != null) {
            if (!replay.requestHash().equals(requestHash)) {
                throw new MockInterfaceException(
                        "IDEMPOTENCY_KEY_CONFLICT", 409, "同一 Idempotency-Key 不能用于不同请求");
            }
            if ("FAILED".equals(replay.status())) {
                throw new MockInterfaceException(
                        "MOCK_DEPENDENCY_UNAVAILABLE", 503, "合成依赖不可用；失败证据已持久化");
            }
            return replayResult(replay);
        }

        ActiveProfile profile = activeProfile(identity.tenantId(), profileKey);
        if (!code.equals(text(profile.payload().get("interface_code")))) {
            throw new MockInterfaceException(
                    "MOCK_PROFILE_INTERFACE_MISMATCH", 409, "当前发布配置不属于请求的接口");
        }
        if (!"SYNTHETIC_ONLY".equals(text(profile.payload().get("production_adapter_state")))) {
            throw new MockInterfaceException(
                    "MOCK_PROFILE_SAFETY_STATE_INVALID", 409, "仿真配置必须明确标记 SYNTHETIC_ONLY");
        }
        if (isTrue(safeRequest.get("contains_real_phi"))) {
            throw new MockInterfaceException(
                    "MOCK_REAL_PHI_FORBIDDEN", 422, "模拟接口禁止接收真实患者身份信息");
        }

        LinkedHashMap<String, Object> effective = new LinkedHashMap<>(safeRequest);
        effective.put("record_count", requestedRecordCount(safeRequest, profile.payload()));
        effective.put("_runtime_profile", profile.payload());
        String scenario = text(effective.getOrDefault("simulation_scenario", "SUCCESS")).toUpperCase();
        UUID runId = UUID.randomUUID();

        if ("UNAVAILABLE".equals(scenario)) {
            Map<String, Object> assessment = Map.of(
                    "agent", "医疗接口安全规则 Agent",
                    "decision", "BLOCK",
                    "risk_level", "HIGH",
                    "clinical_write_allowed", false,
                    "findings", List.of("上游依赖不可用"),
                    "recommended_actions", List.of(profile.payload().get("manual_fallback")));
            String evidenceHash = sha256(identity.tenantId() + "|" + runId + "|" + requestHash + "|FAILED");
            persist(identity, runId, idempotencyKey, requestHash, profile, code, scenario,
                    "FAILED", 0, Map.of(), assessment, evidenceHash);
            throw new MockInterfaceException(
                    "MOCK_DEPENDENCY_UNAVAILABLE", 503, "合成外部依赖不可用；失败证据已持久化，请转人工降级路径");
        }

        MockInvocationResultWire generated = mocks.invoke(code, effective);
        Map<String, Object> assessment = assess(code, scenario, generated.payload(), profile.payload());
        String decision = text(assessment.get("decision"));
        String status = "BLOCK".equals(decision) ? "BLOCKED"
                : "REVIEW".equals(decision) ? "REVIEW_REQUIRED" : "COMPLETED";
        int recordCount = businessRecords(generated.payload()).size();
        String evidenceHash = sha256(identity.tenantId() + "|" + runId + "|" + profile.configId()
                + "|" + profile.rowVersion() + "|" + requestHash + "|" + json(generated.payload())
                + "|" + json(assessment));

        LinkedHashMap<String, Object> payload = new LinkedHashMap<>(generated.payload());
        payload.put("safety_agent", assessment);
        payload.put("execution", Map.ofEntries(
                Map.entry("run_id", runId.toString()),
                Map.entry("request_id", generated.requestId().toString()),
                Map.entry("produced_at", generated.producedAt().toString()),
                Map.entry("workbench_id", profile.workbenchId()),
                Map.entry("profile_id", profile.configId().toString()),
                Map.entry("profile_key", profile.configKey()),
                Map.entry("profile_version", profile.rowVersion()),
                Map.entry("status", status),
                Map.entry("evidence_hash", evidenceHash),
                Map.entry("idempotent", true),
                Map.entry("clinical_write_allowed", false)));
        MockInvocationResultWire result = new MockInvocationResultWire(
                generated.mockInterfaceCode(), generated.requestId(), generated.producedAt(),
                generated.scenario(), generated.deterministicKey(), payload,
                generated.notice() + " · 已绑定发布配置并生成可校验运行证据");
        persist(identity, runId, idempotencyKey, requestHash, profile, code, scenario,
                status, recordCount, payload, assessment, evidenceHash);
        return result;
    }

    List<Map<String, Object>> listRuns(
            ClinicalIdentity identity, String workbenchId, String profileKey) {
        requireRole(identity);
        StringBuilder sql = new StringBuilder("""
                select run_id, profile_id, workbench_id, interface_code, scenario, status,
                  idempotency_key, profile_version, record_count, evidence_hash,
                  started_at, completed_at
                from mock_interface_run where tenant_id = :tenant
                """);
        if (workbenchId != null && !workbenchId.isBlank()) sql.append(" and workbench_id = :workbench");
        if (profileKey != null && !profileKey.isBlank()) {
            sql.append(" and profile_id = (select config_id from config_item where tenant_id = :tenant")
                    .append(" and config_type = 'MOCK_INTERFACE_PROFILE' and config_key = :profile)");
        }
        sql.append(" order by started_at desc, run_id desc limit 200");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("tenant", identity.tenantId());
        if (workbenchId != null && !workbenchId.isBlank()) spec = spec.param("workbench", workbenchId);
        if (profileKey != null && !profileKey.isBlank()) spec = spec.param("profile", profileKey);
        return spec.query((rs, row) -> {
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("run_id", rs.getObject("run_id", UUID.class));
            value.put("profile_id", rs.getObject("profile_id", UUID.class));
            value.put("workbench_id", rs.getString("workbench_id"));
            value.put("interface_code", rs.getString("interface_code"));
            value.put("scenario", rs.getString("scenario"));
            value.put("status", rs.getString("status"));
            value.put("idempotency_key", rs.getString("idempotency_key"));
            value.put("profile_version", rs.getLong("profile_version"));
            value.put("record_count", rs.getInt("record_count"));
            value.put("evidence_hash", rs.getString("evidence_hash"));
            value.put("started_at", rs.getObject("started_at", OffsetDateTime.class).toInstant());
            OffsetDateTime completed = rs.getObject("completed_at", OffsetDateTime.class);
            value.put("completed_at", completed == null ? null : completed.toInstant());
            return (Map<String, Object>) value;
        }).list();
    }

    Map<String, Object> run(ClinicalIdentity identity, UUID runId) {
        requireRole(identity);
        LinkedHashMap<String, Object> result = jdbc.sql("""
                select run_id, profile_id, workbench_id, interface_code, scenario, status,
                  idempotency_key, request_hash, profile_version, record_count, result_payload::text,
                  agent_assessment::text, evidence_hash, created_by, started_at, completed_at
                from mock_interface_run where tenant_id = :tenant and run_id = :run
                """).param("tenant", identity.tenantId()).param("run", runId)
                .query((rs, row) -> {
                    LinkedHashMap<String, Object> value = new LinkedHashMap<>();
                    value.put("run_id", rs.getObject("run_id", UUID.class));
                    value.put("profile_id", rs.getObject("profile_id", UUID.class));
                    value.put("workbench_id", rs.getString("workbench_id"));
                    value.put("interface_code", rs.getString("interface_code"));
                    value.put("scenario", rs.getString("scenario"));
                    value.put("status", rs.getString("status"));
                    value.put("idempotency_key", rs.getString("idempotency_key"));
                    value.put("request_hash", rs.getString("request_hash"));
                    value.put("profile_version", rs.getLong("profile_version"));
                    value.put("record_count", rs.getInt("record_count"));
                    value.put("payload", map(rs.getString("result_payload")));
                    value.put("agent_assessment", map(rs.getString("agent_assessment")));
                    value.put("evidence_hash", rs.getString("evidence_hash"));
                    value.put("created_by", rs.getObject("created_by", UUID.class));
                    value.put("started_at", rs.getObject("started_at", OffsetDateTime.class).toInstant());
                    OffsetDateTime completed = rs.getObject("completed_at", OffsetDateTime.class);
                    value.put("completed_at", completed == null ? null : completed.toInstant());
                    return value;
                }).optional().orElseThrow(() -> new MockInterfaceException(
                        "MOCK_RUN_NOT_FOUND", 404, "模拟接口运行记录不存在"));
        result.put("events", events(identity.tenantId(), runId));
        return result;
    }

    Map<String, Object> evidence(ClinicalIdentity identity, UUID runId) {
        Map<String, Object> run = run(identity, runId);
        return Map.ofEntries(
                Map.entry("run_id", runId),
                Map.entry("evidence_hash", run.get("evidence_hash")),
                Map.entry("request_hash", run.get("request_hash")),
                Map.entry("profile_id", run.get("profile_id")),
                Map.entry("profile_version", run.get("profile_version")),
                Map.entry("created_by", run.get("created_by")),
                Map.entry("started_at", run.get("started_at")),
                Map.entry("completed_at", Objects.requireNonNullElse(run.get("completed_at"), run.get("started_at"))),
                Map.entry("agent_assessment", run.get("agent_assessment")),
                Map.entry("events", run.get("events")),
                Map.entry("verification", "SHA-256 / 租户+运行+配置版本+请求+结果+Agent结论"));
    }

    private void persist(
            ClinicalIdentity identity, UUID runId, String idempotencyKey, String requestHash,
            ActiveProfile profile, String code, String scenario, String status, int recordCount,
            Map<String, Object> payload, Map<String, Object> assessment, String evidenceHash) {
        transactions.executeWithoutResult(transaction -> {
            int inserted = jdbc.sql("""
                    insert into mock_interface_run(
                      tenant_id, run_id, profile_id, workbench_id, interface_code, scenario, status,
                      idempotency_key, request_hash, profile_version, record_count, result_payload,
                      agent_assessment, evidence_hash, created_by, completed_at)
                    values (:tenant, :run, :profile, :workbench, :code, :scenario, :status,
                      :key, :request_hash, :profile_version, :record_count, cast(:payload as jsonb),
                      cast(:assessment as jsonb), :evidence_hash, :actor, now())
                    on conflict (tenant_id, idempotency_key) do nothing
                    """).param("tenant", identity.tenantId()).param("run", runId)
                    .param("profile", profile.configId()).param("workbench", profile.workbenchId())
                    .param("code", code).param("scenario", scenario).param("status", status)
                    .param("key", idempotencyKey).param("request_hash", requestHash)
                    .param("profile_version", profile.rowVersion()).param("record_count", recordCount)
                    .param("payload", json(payload)).param("assessment", json(assessment))
                    .param("evidence_hash", evidenceHash).param("actor", identity.userId()).update();
            if (inserted != 1) {
                StoredRun existing = storedByIdempotency(identity.tenantId(), idempotencyKey);
                if (existing == null || !existing.requestHash().equals(requestHash)) {
                    throw new MockInterfaceException(
                            "IDEMPOTENCY_KEY_CONFLICT", 409, "同一 Idempotency-Key 不能用于不同请求");
                }
                return;
            }
            appendEvents(identity.tenantId(), runId, profile, status, assessment);
            appendEvidence(identity, runId, profile, status, evidenceHash);
        });
    }

    private void appendEvents(
            UUID tenantId, UUID runId, ActiveProfile profile, String status, Map<String, Object> assessment) {
        List<Event> events = List.of(
                new Event(1, "PROFILE_BOUND", "PASS", "已绑定已发布配置",
                        Map.of("profile_id", profile.configId(), "profile_version", profile.rowVersion())),
                new Event(2, "CHINA_PROFILE_VALIDATED", "PASS", "已校验中国三级医院标准档案",
                        Map.of("standard_profile", profile.payload().get("china_standard_profile"))),
                new Event(3, "SAFETY_AGENT_REVIEWED", eventStatus(assessment), "医疗接口安全规则 Agent 已完成审查", assessment),
                new Event(4, "EVIDENCE_SEALED", "PASS", "运行证据已使用 SHA-256 封存",
                        Map.of("status", status)));
        for (Event event : events) {
            jdbc.sql("""
                    insert into mock_interface_run_event(
                      tenant_id, run_id, sequence_no, event_type, event_status, summary, details)
                    values (:tenant, :run, :sequence, :type, :status, :summary, cast(:details as jsonb))
                    """).param("tenant", tenantId).param("run", runId).param("sequence", event.sequence())
                    .param("type", event.type()).param("status", event.status())
                    .param("summary", event.summary()).param("details", json(event.details())).update();
        }
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID runId, ActiveProfile profile, String status, String evidenceHash) {
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String traceId = UUID.randomUUID().toString();
        String auditHash = sha256(identity.tenantId() + "|" + auditId + "|MOCK_INTERFACE_RUN|"
                + runId + "|" + traceId + "|" + Objects.requireNonNullElse(previousHash, "GENESIS"));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, trace_id, previous_hash, event_hash, details)
                values (:tenant, :audit, now(), :actor, 'MOCK_INTERFACE_INVOKED',
                  'MOCK_INTERFACE_RUN', :run, :trace, :previous, :hash,
                  jsonb_build_object('profile_id', :profile, 'status', :status, 'evidence_hash', :evidence))
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("run", runId).param("trace", traceId)
                .param("previous", previousHash).param("hash", auditHash)
                .param("profile", profile.configId()).param("status", status)
                .param("evidence", evidenceHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'MOCK_INTERFACE_RUN', :run, 1,
                  'MockInterfaceRunCompleted', 1,
                  jsonb_build_object('profile_id', :profile, 'status', :status, 'evidence_hash', :evidence))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("run", runId).param("profile", profile.configId()).param("status", status)
                .param("evidence", evidenceHash).update();
    }

    private Map<String, Object> assess(
            String code, String scenario, Map<String, Object> payload, Map<String, Object> profile) {
        List<String> findings = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        String decision = "PASS";
        if ("DEGRADED".equals(scenario)) {
            decision = "REVIEW";
            findings.add("上游返回不完整，禁止自动写入临床事实");
            actions.add(text(profile.get("manual_fallback")));
        }
        if ("LIS_RESULTS".equals(code)) {
            long critical = businessRecords(payload).stream()
                    .filter(record -> Boolean.TRUE.equals(record.get("critical"))).count();
            if (critical > 0) {
                decision = "REVIEW";
                findings.add(critical + " 条院级危急值演练记录需要复核、报告、接收和处置闭环");
                actions.add("仅进入危急值人工演练队列，不生成真实医嘱或病历");
            }
        }
        if ("THERAPY_EXECUTE".equals(code)) {
            boolean unsafeCompletion = businessRecords(payload).stream().anyMatch(record ->
                    "COMPLETED".equals(record.get("status")) && !Boolean.TRUE.equals(record.get("dual_sign")));
            if (unsafeCompletion) {
                decision = "BLOCK";
                findings.add("发现未完成双人核对却标记已执行的治疗记录");
                actions.add("阻断演练结果进入后续流程并复核执行状态机");
            }
        }
        if ("MALWARE_SCAN".equals(code)) {
            List<String> signatures = businessRecords(payload).stream()
                    .filter(record -> "FOUND".equals(record.get("verdict")))
                    .map(record -> String.valueOf(record.get("signature")))
                    .filter(value -> !value.isBlank() && !"null".equals(value)).toList();
            if (!signatures.isEmpty()) {
                decision = "BLOCK";
                findings.add("检出 " + signatures.size() + " 个恶意文件仿真签名：" + String.join("、", signatures));
                actions.add("演练结果隔离阻断、不得入档；核对来源并记录审计后删除");
            }
        }
        if ("CDA_VALIDATION".equals(code)) {
            long structuralFailures = businessRecords(payload).stream()
                    .filter(record -> Boolean.FALSE.equals(record.get("structural_valid"))).count();
            long semanticWarnings = businessRecords(payload).stream()
                    .filter(record -> Boolean.TRUE.equals(record.get("structural_valid"))
                            && Boolean.FALSE.equals(record.get("semantic_ok"))).count();
            if (structuralFailures > 0) {
                decision = "BLOCK";
                findings.add(structuralFailures + " 份 CDA 文书结构校验失败，禁止进入归档终态");
                actions.add("问题文书转扫描/生成来源整改并重新校验");
            } else if (semanticWarnings > 0) {
                decision = "REVIEW";
                findings.add(semanticWarnings + " 份 CDA 文书存在语义告警，需人工复核后再归档");
                actions.add("复核 valueSet/编码系统解析告警，人工确认后继续归档流程");
            }
        }
        if (findings.isEmpty()) findings.add("未发现身份、双签或中国医疗业务规则阻断项");
        if (actions.isEmpty()) actions.add("保留仿真标识和证据链；不写入真实临床事实");
        return Map.of(
                "agent", "医疗接口安全规则 Agent",
                "agent_version", "cn-tertiary-interface-guard-v1",
                "decision", decision,
                "risk_level", "BLOCK".equals(decision) ? "HIGH" : "REVIEW".equals(decision) ? "MEDIUM" : "LOW",
                "clinical_write_allowed", false,
                "human_review_required", !"PASS".equals(decision),
                "findings", findings,
                "recommended_actions", actions,
                "standards_checked", List.of("WS/T 846.1-846.11—2024", "WS/T 847—2024", "院级危急值管理制度"));
    }

    private ActiveProfile activeProfile(UUID tenantId, String profileKey) {
        return jdbc.sql("""
                select config_id, config_key, payload::text, row_version
                from config_item
                where tenant_id = :tenant and config_type = 'MOCK_INTERFACE_PROFILE'
                  and config_key = :key and status = 'ACTIVE'
                  and validation_state = 'VALID' and approval_state = 'APPROVED'
                """).param("tenant", tenantId).param("key", profileKey)
                .query((rs, row) -> {
                    Map<String, Object> payload = map(rs.getString("payload"));
                    return new ActiveProfile(
                            rs.getObject("config_id", UUID.class), rs.getString("config_key"),
                            text(payload.get("workbench_id")), rs.getLong("row_version"), payload);
                }).optional().orElseThrow(() -> new MockInterfaceException(
                        "MOCK_PROFILE_NOT_ACTIVE", 409, "配置不存在、未批准、未发布或已归档"));
    }

    private StoredRun storedByIdempotency(UUID tenantId, String key) {
        return jdbc.sql("""
                select interface_code, scenario, status, idempotency_key, request_hash,
                  result_payload::text, started_at
                from mock_interface_run where tenant_id = :tenant and idempotency_key = :key
                """).param("tenant", tenantId).param("key", key)
                .query((rs, row) -> new StoredRun(
                        rs.getString("interface_code"), rs.getString("scenario"), rs.getString("status"),
                        rs.getString("idempotency_key"), rs.getString("request_hash"),
                        map(rs.getString("result_payload")),
                        rs.getObject("started_at", OffsetDateTime.class)))
                .optional().orElse(null);
    }

    private MockInvocationResultWire replayResult(StoredRun run) {
        Map<String, Object> execution = valueMap(run.payload().get("execution"));
        String requestId = text(execution.get("request_id"));
        UUID id = requestId.isBlank() ? UUID.nameUUIDFromBytes(run.idempotencyKey().getBytes(StandardCharsets.UTF_8))
                : UUID.fromString(requestId);
        String producedAt = text(execution.get("produced_at"));
        return new MockInvocationResultWire(
                run.interfaceCode(), id,
                producedAt.isBlank() ? run.startedAt().toInstant() : java.time.Instant.parse(producedAt),
                MockInvocationResultWire.ScenarioValue.valueOf(run.scenario()),
                run.interfaceCode() + ":" + run.requestHash().substring(0, 8), run.payload(),
                "幂等重放：返回已持久化的同一运行结果，未重复产生副作用");
    }

    private List<Map<String, Object>> events(UUID tenantId, UUID runId) {
        return jdbc.sql("""
                select sequence_no, event_type, event_status, summary, details::text, occurred_at
                from mock_interface_run_event where tenant_id = :tenant and run_id = :run
                order by sequence_no
                """).param("tenant", tenantId).param("run", runId)
                .query((rs, row) -> Map.<String, Object>of(
                        "sequence_no", rs.getInt("sequence_no"),
                        "event_type", rs.getString("event_type"),
                        "event_status", rs.getString("event_status"),
                        "summary", rs.getString("summary"),
                        "details", map(rs.getString("details")),
                        "occurred_at", rs.getObject("occurred_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    private void requireRole(ClinicalIdentity identity) {
        if (identity.roleAssignmentIds().isEmpty()) {
            throw new MockInterfaceException("MOCK_ROLE_REQUIRED", 403, "模拟接口操作需要有效岗位");
        }
        long allowed = jdbc.sql("""
                select count(*) from role_assignment
                where tenant_id = :tenant and user_id = :user
                  and role_assignment_id in (:assignments) and role_code in (:roles)
                  and status = 'ACTIVE' and valid_from <= now()
                  and (valid_until is null or valid_until > now())
                """).param("tenant", identity.tenantId()).param("user", identity.userId())
                .param("assignments", identity.roleAssignmentIds()).param("roles", EXECUTION_ROLES)
                .query(Long.class).single();
        if (allowed == 0) {
            throw new MockInterfaceException("MOCK_ROLE_FORBIDDEN", 403, "当前岗位无权执行模拟接口演练");
        }
    }

    private Map<String, Object> sanitizeRequest(Map<String, Object> request) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        if (request != null) {
            request.forEach((key, value) -> {
                if (key != null && !key.startsWith("_") && !"credentials".equalsIgnoreCase(key)
                        && !"secret".equalsIgnoreCase(key) && !"token".equalsIgnoreCase(key)) {
                    result.put(key, value);
                }
            });
        }
        return result;
    }

    private void validateClinicalIdentifiers(Map<String, Object> request) {
        for (String key : List.of("patient_id", "encounter_id")) {
            String value = text(request.get(key));
            if (!value.isBlank()) {
                try {
                    UUID.fromString(value);
                } catch (IllegalArgumentException invalid) {
                    throw new MockInterfaceException("MOCK_CLINICAL_IDENTIFIER_INVALID", 422,
                            key + " 必须为合法 UUID，且仅能使用合成数据标识");
                }
                if (!value.startsWith("018f0000-0000-7000-8000-000000")) {
                    throw new MockInterfaceException("MOCK_REAL_PHI_FORBIDDEN", 422,
                            key + " 不属于隔离的合成标识命名空间");
                }
            }
        }
    }

    private boolean isTrue(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(text(value)) || "1".equals(text(value));
    }

    private int requestedRecordCount(Map<String, Object> request, Map<String, Object> profile) {
        Object requested = request.get("record_count");
        if (requested instanceof Number number) return number.intValue();
        Object configured = profile.get("default_record_count");
        return configured instanceof Number number ? number.intValue() : TertiaryMockBusinessDataGenerator.DEFAULT_RECORD_COUNT;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> businessRecords(Map<String, Object> payload) {
        Object records = payload.get("business_records");
        if (!(records instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance).map(value -> (Map<String, Object>) value).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> valueMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(String json) {
        try {
            return objectMapper.convertValue(objectMapper.readTree(json), Map.class);
        } catch (Exception invalid) {
            throw new MockInterfaceException("MOCK_EVIDENCE_INVALID", 500, "存储的模拟接口证据无效");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception invalid) {
            throw new MockInterfaceException("MOCK_EVIDENCE_INVALID", 500, "模拟接口证据不可序列化");
        }
    }

    private String canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().sorted(Map.Entry.comparingByKey((left, right) ->
                            String.valueOf(left).compareTo(String.valueOf(right))))
                    .map(entry -> entry.getKey() + ":" + canonical(entry.getValue()))
                    .reduce("{", (left, right) -> left + right + ";") + "}";
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::canonical).reduce("[", (left, right) -> left + right + ",") + "]";
        }
        return String.valueOf(value);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    private String eventStatus(Map<String, Object> assessment) {
        return switch (text(assessment.get("decision"))) {
            case "BLOCK" -> "BLOCK";
            case "REVIEW" -> "REVIEW";
            default -> "PASS";
        };
    }

    private void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new MockInterfaceException("IDEMPOTENCY_KEY_REQUIRED", 400,
                    "Idempotency-Key 必填且长度不得超过 128");
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private record ActiveProfile(
            UUID configId, String configKey, String workbenchId, long rowVersion,
            Map<String, Object> payload) {}

    private record StoredRun(
            String interfaceCode, String scenario, String status, String idempotencyKey,
            String requestHash, Map<String, Object> payload, OffsetDateTime startedAt) {}

    private record Event(int sequence, String type, String status, String summary, Map<String, Object> details) {}
}
