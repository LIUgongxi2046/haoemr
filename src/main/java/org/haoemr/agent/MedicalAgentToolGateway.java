package org.haoemr.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.haoemr.configuration.ConfigurationRuntimeService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Component
final class MedicalAgentToolGateway {

    private static final Map<String, ToolDefinition> SCOPED_TOOLS = Map.of(
            "RECORDS", new ToolDefinition("CLINICAL_DOCUMENT_READ", "当前就诊文书版本读取"),
            "ORDERS", new ToolDefinition("CLINICAL_ORDER_READ", "当前就诊医嘱读取"),
            "RESULTS", new ToolDefinition("CLINICAL_RESULT_READ", "当前就诊结果读取"),
            "TASKS", new ToolDefinition("CLINICAL_TASK_READ", "当前就诊任务读取"),
            "ATTACHMENTS", new ToolDefinition("CLINICAL_ATTACHMENT_READ", "当前就诊附件元数据读取"),
            "CONFIGURATION", new ToolDefinition("BUSINESS_CONFIGURATION_READ", "已发布业务配置与运行时证据读取"));
    private static final Map<String, ToolDefinition> DEPENDENCY_TOOLS = Map.ofEntries(
            Map.entry("PATIENT_CONTEXT_READ", new ToolDefinition("PATIENT_CONTEXT_READ", "患者基本信息与当前诊断读取")),
            Map.entry("BILLING_CONTEXT_READ", new ToolDefinition("BILLING_CONTEXT_READ", "当前就诊收费明细读取")),
            Map.entry("SCHEDULING_CONTEXT_READ", new ToolDefinition("SCHEDULING_CONTEXT_READ", "当前就诊医技安排读取")),
            Map.entry("RESEARCH_CONTEXT_READ", new ToolDefinition("RESEARCH_CONTEXT_READ", "研究纳排条件读取")),
            Map.entry("TCM_CONTEXT_READ", new ToolDefinition("TCM_CONTEXT_READ", "四诊与方药处方读取")),
            Map.entry("VITAL_SIGN_READ", new ToolDefinition("VITAL_SIGN_READ", "生命体征趋势读取")),
            Map.entry("ALLERGY_READ", new ToolDefinition("ALLERGY_READ", "患者过敏史读取")),
            Map.entry("CARE_PLAN_READ", new ToolDefinition("CARE_PLAN_READ", "护理诊疗计划读取")),
            Map.entry("BLOOD_TRANSFUSION_READ", new ToolDefinition("BLOOD_TRANSFUSION_READ", "输血记录读取")),
            Map.entry("AGENT_EVIDENCE_APPEND", new ToolDefinition("AGENT_EVIDENCE_APPEND", "医助运行证据追加")),
            Map.entry("LAB_TREND_READ", new ToolDefinition("LAB_TREND_READ", "检验结果趋势读取")),
            Map.entry("SURGERY_SCHEDULE_READ", new ToolDefinition("SURGERY_SCHEDULE_READ", "手术安排读取")),
            Map.entry("ANESTHESIA_RECORD_READ", new ToolDefinition("ANESTHESIA_RECORD_READ", "麻醉记录读取")),
            Map.entry("INFECTION_EVENT_READ", new ToolDefinition("INFECTION_EVENT_READ", "院感事件读取")),
            Map.entry("NURSING_RECORD_READ", new ToolDefinition("NURSING_RECORD_READ", "护理记录读取")),
            Map.entry("PATHOLOGY_REPORT_READ", new ToolDefinition("PATHOLOGY_REPORT_READ", "病理报告读取")),
            Map.entry("IMAGING_REPORT_READ", new ToolDefinition("IMAGING_REPORT_READ", "影像报告读取")),
            Map.entry("MDT_RECORD_READ", new ToolDefinition("MDT_RECORD_READ", "多学科会诊记录读取")),
            Map.entry("MEDICATION_ADMIN_READ", new ToolDefinition("MEDICATION_ADMIN_READ", "用药执行记录读取")),
            Map.entry("ENCOUNTER_TIMELINE_READ", new ToolDefinition("ENCOUNTER_TIMELINE_READ", "当前就诊时间线读取")),
            Map.entry("DOCUMENT_TEMPLATE_READ", new ToolDefinition("DOCUMENT_TEMPLATE_READ", "已发布文书模板读取")),
            Map.entry("CLINICAL_RULE_EVALUATE", new ToolDefinition("CLINICAL_RULE_EVALUATE", "临床规则结果读取")),
            Map.entry("CRITICAL_VALUE_READ", new ToolDefinition("CRITICAL_VALUE_READ", "危急值闭环读取")),
            Map.entry("CONSULTATION_READ", new ToolDefinition("CONSULTATION_READ", "会诊协同读取")),
            Map.entry("KNOWLEDGE_SEARCH", new ToolDefinition("KNOWLEDGE_SEARCH", "知识库混合检索")),
            Map.entry("KNOWLEDGE_LOOKUP", new ToolDefinition("KNOWLEDGE_LOOKUP", "知识精确查询")),
            Map.entry("KNOWLEDGE_GRAPH", new ToolDefinition("KNOWLEDGE_GRAPH", "知识图谱邻接读取")),
            Map.entry("PATHWAY_KNOWLEDGE_SEARCH", new ToolDefinition("PATHWAY_KNOWLEDGE_SEARCH", "临床路径知识检索")));
    private static final Map<String, String> DEPENDENCY_SCOPE = Map.ofEntries(
            Map.entry("PATIENT_CONTEXT_READ", "RECORDS"), Map.entry("BILLING_CONTEXT_READ", "ORDERS"),
            Map.entry("SCHEDULING_CONTEXT_READ", "TASKS"), Map.entry("RESEARCH_CONTEXT_READ", "CONFIGURATION"),
            Map.entry("TCM_CONTEXT_READ", "RECORDS"),
            Map.entry("VITAL_SIGN_READ", "RECORDS"), Map.entry("LAB_TREND_READ", "RESULTS"),
            Map.entry("ALLERGY_READ", "RECORDS"), Map.entry("CARE_PLAN_READ", "RECORDS"),
            Map.entry("BLOOD_TRANSFUSION_READ", "RECORDS"),
            Map.entry("AGENT_EVIDENCE_APPEND", "RECORDS"),
            Map.entry("SURGERY_SCHEDULE_READ", "RECORDS"), Map.entry("ANESTHESIA_RECORD_READ", "RECORDS"),
            Map.entry("INFECTION_EVENT_READ", "RECORDS"), Map.entry("NURSING_RECORD_READ", "RECORDS"),
            Map.entry("PATHOLOGY_REPORT_READ", "RESULTS"), Map.entry("IMAGING_REPORT_READ", "RESULTS"),
            Map.entry("MDT_RECORD_READ", "TASKS"), Map.entry("MEDICATION_ADMIN_READ", "ORDERS"),
            Map.entry("ENCOUNTER_TIMELINE_READ", "RECORDS"), Map.entry("DOCUMENT_TEMPLATE_READ", "CONFIGURATION"),
            Map.entry("CLINICAL_RULE_EVALUATE", "CONFIGURATION"), Map.entry("CRITICAL_VALUE_READ", "RESULTS"),
            Map.entry("CONSULTATION_READ", "RECORDS"),
            Map.entry("KNOWLEDGE_SEARCH", "CONFIGURATION"), Map.entry("KNOWLEDGE_LOOKUP", "CONFIGURATION"),
            Map.entry("KNOWLEDGE_GRAPH", "CONFIGURATION"),
            Map.entry("PATHWAY_KNOWLEDGE_SEARCH", "CONFIGURATION"));

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final ConfigurationRuntimeService configurationRuntime;

    MedicalAgentToolGateway(
            JdbcClient jdbc, TransactionTemplate transactions, ObjectMapper objectMapper,
            ConfigurationRuntimeService configurationRuntime) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.configurationRuntime = configurationRuntime;
    }

    List<ToolResult> execute(
            UUID tenantId, UUID runId, UUID childRunId, UUID leaseId, UUID patientId, UUID encounterId,
            String authorizationWatermark, String rootAgentCode, Set<String> scopes, String objective) {
        List<ToolResult> results = new ArrayList<>();
        for (String scope : List.of("RECORDS", "ORDERS", "RESULTS", "TASKS", "ATTACHMENTS", "CONFIGURATION")) {
            if (scopes.contains(scope)) {
                results.add(executeOne(tenantId, runId, childRunId, leaseId, patientId, encounterId,
                        authorizationWatermark, SCOPED_TOOLS.get(scope), objective));
            }
        }
        for (ToolDefinition definition : dependencyTools(tenantId, rootAgentCode, scopes)) {
            results.add(executeOne(tenantId, runId, childRunId, leaseId, patientId, encounterId,
                    authorizationWatermark, definition, objective));
        }
        return List.copyOf(results);
    }

    private List<ToolDefinition> dependencyTools(UUID tenantId, String rootAgentCode, Set<String> scopes) {
        List<String> codes = jdbc.sql("""
                select distinct dependency.dependency_code
                from agent_registry agent
                join agent_dependency dependency
                  on dependency.tenant_id = agent.tenant_id
                 and dependency.agent_registry_id = agent.agent_registry_id
                 and dependency.dependency_type = 'TOOL'
                join tool_registry tool
                  on tool.tenant_id = dependency.tenant_id
                 and tool.tool_code = dependency.dependency_code and tool.status = 'ACTIVE'
                where agent.tenant_id = :tenant and agent.agent_code = :agent and agent.status = 'ACTIVE'
                order by dependency.dependency_code
                """).param("tenant", tenantId).param("agent", rootAgentCode).query(String.class).list();
        List<ToolDefinition> definitions = new ArrayList<>();
        for (String code : codes) {
            ToolDefinition definition = DEPENDENCY_TOOLS.get(code);
            if (definition == null) {
                throw new AgentRunException("MEDICAL_AGENT_TOOL_ADAPTER_MISSING", 409,
                        "The active medical assistant tool has no executable adapter: " + code);
            }
            String requiredScope = DEPENDENCY_SCOPE.get(code);
            if (scopes.contains(requiredScope)) definitions.add(definition);
        }
        return List.copyOf(definitions);
    }

    private ToolResult executeOne(
            UUID tenantId, UUID runId, UUID childRunId, UUID leaseId, UUID patientId, UUID encounterId,
            String watermark, ToolDefinition definition, String objective) {
        ensureLease(tenantId, leaseId, patientId, encounterId, watermark);
        String version = jdbc.sql("""
                select tool_version from tool_registry
                where tenant_id = :tenant and tool_code = :code and status = 'ACTIVE'
                order by updated_at desc, tool_registry_id desc limit 1
                """).param("tenant", tenantId).param("code", definition.code())
                .query(String.class).optional().orElseThrow(() -> new AgentRunException(
                        "MEDICAL_AGENT_TOOL_DISABLED", 409,
                        "The required scoped clinical tool is not active: " + definition.code()));
        UUID invocationId = UUID.randomUUID();
        String inputHash = sha256(tenantId + "|" + patientId + "|" + encounterId + "|" + definition.code()
                + "|" + watermark + "|" + objective);
        long started = System.nanoTime();
        try {
            List<Map<String, Object>> items = "AGENT_EVIDENCE_APPEND".equals(definition.code())
                    ? appendRunEvidence(tenantId, runId, childRunId, invocationId, watermark)
                    : read(definition.code(), tenantId, patientId, encounterId, objective);
            List<Map<String, Object>> references = references(definition.code(), items, watermark);
            long durationMs = elapsedMillis(started);
            String resultHash = sha256(json(items));
            record(tenantId, invocationId, runId, childRunId, definition.code(), version, watermark,
                    inputHash, resultHash, items.size(), "SUCCEEDED", durationMs, null);
            return new ToolResult(invocationId, definition.code(), version, definition.displayName(),
                    items, references, durationMs);
        } catch (AgentRunException failure) {
            record(tenantId, invocationId, runId, childRunId, definition.code(), version, watermark,
                    inputHash, null, 0, "FAILED", elapsedMillis(started), failure.code());
            throw failure;
        } catch (RuntimeException failure) {
            record(tenantId, invocationId, runId, childRunId, definition.code(), version, watermark,
                    inputHash, null, 0, "FAILED", elapsedMillis(started), "MEDICAL_AGENT_TOOL_FAILED");
            throw new AgentRunException("MEDICAL_AGENT_TOOL_FAILED", 503,
                    "The scoped clinical tool failed: " + definition.code());
        }
    }

    /**
     * Appends only technical provenance to the current medical-agent run. This adapter deliberately
     * cannot create or alter clinical records, orders, tasks, or patient facts.
     */
    private List<Map<String, Object>> appendRunEvidence(
            UUID tenantId, UUID runId, UUID childRunId, UUID invocationId, String watermark) {
        return transactions.execute(status -> {
            UUID eventId = UUID.randomUUID();
            long sequence = jdbc.sql("""
                    update medical_agent_run set sequence = sequence + 1, row_version = row_version + 1,
                      last_heartbeat_at = case when state = 'RUNNING' then now() else last_heartbeat_at end
                    where tenant_id = :tenant and run_id = :run returning sequence
                    """).param("tenant", tenantId).param("run", runId).query(Long.class).single();
            jdbc.sql("""
                    insert into medical_agent_run_event(
                      tenant_id, run_id, sequence, event_id, event_type, child_run_id, payload)
                    values (:tenant, :run, :sequence, :event, 'AgentEvidenceAppended', :child,
                      cast(:payload as jsonb))
                    """).param("tenant", tenantId).param("run", runId).param("sequence", sequence)
                    .param("event", eventId).param("child", childRunId).param("payload", json(Map.of(
                            "tool_code", "AGENT_EVIDENCE_APPEND",
                            "invocation_id", invocationId.toString(),
                            "evidence_kind", "TOOL_INVOCATION",
                            "authorization_watermark_hash", sha256(watermark))))
                    .update();
            return List.of(item(
                    "source_type", "MEDICAL_AGENT_RUN_EVENT",
                    "source_id", eventId,
                    "event_type", "AgentEvidenceAppended",
                    "sequence", sequence,
                    "evidence_kind", "TOOL_INVOCATION",
                    "recorded_at", OffsetDateTime.now()));
        });
    }

    private void ensureLease(UUID tenantId, UUID leaseId, UUID patientId, UUID encounterId, String watermark) {
        long valid = jdbc.sql("""
                select count(*) from context_lease
                where tenant_id = :tenant and lease_id = :lease and patient_id is not distinct from :patient
                  and encounter_id is not distinct from :encounter and authorization_watermark = :watermark
                  and revoked_at is null and expires_at > now()
                """).param("tenant", tenantId).param("lease", leaseId).param("patient", patientId)
                .param("encounter", encounterId).param("watermark", watermark).query(Long.class).single();
        if (valid != 1) {
            throw new AgentRunException("CONTEXT_NOT_PERMITTED", 403,
                    "The medical-agent context lease expired before tool execution");
        }
    }

    private List<Map<String, Object>> read(String code, UUID tenantId, UUID patientId, UUID encounterId, String objective) {
        return switch (code) {
            case "PATIENT_CONTEXT_READ" -> patientContext(tenantId, patientId, encounterId);
            case "BILLING_CONTEXT_READ" -> scopedRows("charge_item", "charge_item_id", tenantId, patientId, encounterId);
            case "SCHEDULING_CONTEXT_READ" -> schedulingContext(tenantId, patientId, encounterId);
            case "RESEARCH_CONTEXT_READ" -> researchCriteria(tenantId);
            case "TCM_CONTEXT_READ" -> tcmContext(tenantId, patientId, encounterId);
            case "CLINICAL_DOCUMENT_READ" -> documents(tenantId, patientId, encounterId);
            case "CLINICAL_ORDER_READ" -> orders(tenantId, patientId, encounterId);
            case "CLINICAL_RESULT_READ" -> results(tenantId, patientId, encounterId);
            case "CLINICAL_TASK_READ" -> tasks(tenantId, patientId, encounterId);
            case "CLINICAL_ATTACHMENT_READ" -> attachments(tenantId, patientId, encounterId);
            case "BUSINESS_CONFIGURATION_READ" -> configurationContext(tenantId, objective);
            case "VITAL_SIGN_READ" -> vitalSigns(tenantId, patientId, encounterId);
            case "ALLERGY_READ" -> allergies(tenantId, patientId);
            case "CARE_PLAN_READ" -> carePlans(tenantId, patientId, encounterId);
            case "BLOOD_TRANSFUSION_READ" -> bloodTransfusions(tenantId, patientId, encounterId);
            case "LAB_TREND_READ" -> laboratoryTrend(tenantId, patientId, encounterId);
            case "SURGERY_SCHEDULE_READ" -> surgerySchedule(tenantId, patientId, encounterId);
            case "ANESTHESIA_RECORD_READ" -> typedDocuments(tenantId, patientId, encounterId,
                    "ANESTHESIA", "ANESTHESIA_DOCUMENT_VERSION");
            case "INFECTION_EVENT_READ" -> infectionEvents(tenantId, patientId, encounterId);
            case "NURSING_RECORD_READ" -> nursingRecords(tenantId, patientId, encounterId);
            case "PATHOLOGY_REPORT_READ" -> typedDocuments(tenantId, patientId, encounterId,
                    "PATH", "PATHOLOGY_DOCUMENT_VERSION");
            case "IMAGING_REPORT_READ" -> resultReports(tenantId, patientId, encounterId, "IMAGING");
            case "MDT_RECORD_READ" -> typedDocuments(tenantId, patientId, encounterId,
                    "MDT", "MDT_DOCUMENT_VERSION");
            case "MEDICATION_ADMIN_READ" -> medicationAdministrations(tenantId, patientId, encounterId);
            case "ENCOUNTER_TIMELINE_READ" -> encounterTimeline(tenantId, patientId, encounterId);
            case "DOCUMENT_TEMPLATE_READ" -> documentTemplates(tenantId);
            case "CLINICAL_RULE_EVALUATE" -> clinicalRuleResults(tenantId, patientId, encounterId);
            case "CRITICAL_VALUE_READ" -> criticalValues(tenantId, patientId, encounterId);
            case "CONSULTATION_READ" -> consultations(tenantId, patientId, encounterId);
            case "KNOWLEDGE_SEARCH" -> knowledgeIndex(tenantId, objective);
            case "KNOWLEDGE_LOOKUP" -> knowledgeConcepts(tenantId);
            case "KNOWLEDGE_GRAPH" -> knowledgeRelations(tenantId);
            case "PATHWAY_KNOWLEDGE_SEARCH" -> pathwayKnowledgeIndex(tenantId);
            default -> throw new AgentRunException("MEDICAL_AGENT_TOOL_NOT_ALLOWED", 403,
                    "The requested tool is not in the medical-agent allowlist");
        };
    }

    private List<Map<String, Object>> configurationContext(UUID tenantId, String objective) {
        var terms = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_]{2,}|糖尿病|血糖|抗凝|心衰|胸痛|护理|随访|院感|文书|病历|质控|医保|研究|中医|危急值")
                .matcher(objective == null ? "" : objective);
        var keywords = new ArrayList<String>();
        while (terms.find() && keywords.size() < 12) keywords.add(terms.group().toLowerCase());
        return configurationRuntime.activeConfigurationsForAgent(tenantId).stream()
                .filter(row -> keywords.stream().anyMatch(term ->
                        (row.get("display_name") + " " + row.get("config_key")).toLowerCase().contains(term)))
                .limit(5).map(row -> {
                    var bounded = new LinkedHashMap<String, Object>(row);
                    if (json(bounded.get("payload")).length() > 6000) {
                        bounded.remove("payload");
                        bounded.put("content_unavailable", "Configuration exceeds the bounded context; inspect its published version in the workbench.");
                    }
                    return (Map<String, Object>) bounded;
                }).toList();
    }

    private List<Map<String, Object>> patientContext(UUID tenantId, UUID patientId, UUID encounterId) {
        List<Map<String, Object>> context = new ArrayList<>(jdbc.sql("""
                select p.patient_id, p.sex_code, extract(year from age(p.birth_date))::int as age_years,
                  p.row_version from patient p
                where p.tenant_id = :tenant and p.patient_id = :patient and p.status <> 'VOID'
                """).param("tenant", tenantId).param("patient", patientId)
                .query((rs, row) -> item("source_type", "PATIENT", "source_id", rs.getObject("patient_id", UUID.class),
                        "sex_code", rs.getString("sex_code"), "age_years", rs.getInt("age_years"),
                        "version", rs.getLong("row_version"))).list());
        context.addAll(jdbc.sql("""
                select v.diagnosis_version_id, v.version_no, v.code, v.code_display_snapshot,
                  v.diagnosis_text, v.diagnosis_role, v.certainty, v.evidence_summary, v.plan_summary
                from clinical_diagnosis d join clinical_diagnosis_version v
                  on v.tenant_id = d.tenant_id and v.diagnosis_version_id = d.current_version_id
                where d.tenant_id = :tenant and d.patient_id = :patient and d.encounter_id = :encounter
                  and d.lifecycle_status = 'ACTIVE' order by d.updated_at desc limit 30
                """).param("tenant", tenantId).param("patient", patientId).param("encounter", encounterId)
                .query((rs, row) -> item("source_type", "DIAGNOSIS_VERSION",
                        "source_id", rs.getObject("diagnosis_version_id", UUID.class), "version", rs.getLong("version_no"),
                        "code", rs.getString("code"), "display", rs.getString("code_display_snapshot"),
                        "diagnosis_text", rs.getString("diagnosis_text"), "role", rs.getString("diagnosis_role"),
                        "certainty", rs.getString("certainty"), "evidence_summary", rs.getString("evidence_summary"),
                        "plan_summary", rs.getString("plan_summary"))).list());
        return List.copyOf(context);
    }

    // Table/column names are private compile-time allowlisted call sites, never model or user input.
    private List<Map<String, Object>> scopedRows(String table, String idColumn, UUID tenantId,
            UUID patientId, UUID encounterId) {
        return jdbc.sql("select to_jsonb(r)::text as evidence from " + table
                + " r where tenant_id = :tenant and patient_id = :patient and encounter_id = :encounter"
                + " order by created_at desc limit 20")
                .param("tenant", tenantId).param("patient", patientId).param("encounter", encounterId)
                .query((rs, row) -> {
                    Map<String, Object> data = new LinkedHashMap<>(map(rs.getString("evidence")));
                    Object id = data.get(idColumn);
                    data.remove("tenant_id"); data.remove("patient_id"); data.remove("encounter_id");
                    return item("source_type", table.toUpperCase(java.util.Locale.ROOT), "source_id", id,
                            "version", data.getOrDefault("row_version", 1), "record", data);
                }).list();
    }

    private List<Map<String, Object>> schedulingContext(UUID tenantId, UUID patientId, UUID encounterId) {
        List<Map<String, Object>> rows = new ArrayList<>(scopedRows("imaging_order", "imaging_order_id", tenantId, patientId, encounterId));
        rows.addAll(scopedRows("specialty_execution_case", "specialty_execution_case_id", tenantId, patientId, encounterId));
        return List.copyOf(rows);
    }

    private List<Map<String, Object>> tcmContext(UUID tenantId, UUID patientId, UUID encounterId) {
        List<Map<String, Object>> rows = new ArrayList<>(scopedRows("tcm_record", "tcm_record_id", tenantId, patientId, encounterId));
        rows.addAll(scopedRows("tcm_four_examinations", "exam_id", tenantId, patientId, encounterId));
        rows.addAll(scopedRows("tcm_herbal_prescription", "prescription_id", tenantId, patientId, encounterId));
        return List.copyOf(rows);
    }

    private List<Map<String, Object>> researchCriteria(UUID tenantId) {
        // Protocol criteria only; no other patient's cohort membership or research records are exposed.
        return jdbc.sql("""
                select research_cohort_id, cohort_name, inclusion_criteria, exclusion_criteria, updated_at
                from research_cohort where tenant_id = :tenant and status = 'ACTIVE'
                order by cohort_code limit 20
                """).param("tenant", tenantId)
                .query((rs, row) -> item("source_type", "RESEARCH_COHORT", "source_id",
                        rs.getObject("research_cohort_id", UUID.class), "title", rs.getString("cohort_name"),
                        "inclusion_criteria", rs.getString("inclusion_criteria"),
                        "exclusion_criteria", rs.getString("exclusion_criteria"),
                        "version", rs.getString("updated_at"))).list();
    }

    private List<Map<String, Object>> knowledgeIndex(UUID tenantId, String objective) {
        // Search published, non-sensitive content, not just titles. Preserve the version and chunk identity.
        var terms = new ArrayList<String>();
        var matcher = java.util.regex.Pattern.compile("血糖|胰岛素|低血糖|心衰|胸痛|抗凝|感染|脓毒症|中医|方药|用药|过敏|随访|出院|病历|质控|护理|手术|医保|收费|研究|检查|[A-Za-z][A-Za-z0-9_-]{2,30}")
                .matcher(objective == null ? "" : objective);
        while (matcher.find() && terms.size() < 12) terms.add("%" + matcher.group() + "%");
        if (terms.isEmpty()) return List.of();
        return jdbc.sql("""
                select v.doc_version_id, v.version, v.content_hash, d.title, d.document_code,
                  d.content_type, d.classification, c.chunk_id, c.section_path, left(c.text, 1800) as excerpt
                from knowledge_chunk c
                join knowledge_document_version v on v.tenant_id = c.tenant_id and v.doc_version_id = c.doc_version_id
                join knowledge_document d on d.tenant_id = v.tenant_id and d.document_id = v.document_id
                where c.tenant_id = :tenant and v.status = 'ACTIVE'
                  and d.classification in ('PUBLIC', 'INTERNAL')
                  and (d.effective_from is null or d.effective_from <= now())
                  and (d.effective_to is null or d.effective_to > now())
                  and (c.text ilike any(string_to_array(:terms, '|'))
                    or d.title ilike any(string_to_array(:terms, '|')))
                order by v.created_at desc, c.chunk_id limit 12
                """).param("tenant", tenantId).param("terms", String.join("|", terms))
                .query((rs, row) -> item(
                        "source_type", "KNOWLEDGE_CHUNK", "source_id", rs.getObject("chunk_id", UUID.class),
                        "document_version_id", rs.getObject("doc_version_id", UUID.class),
                        "title", rs.getString("title"), "version", rs.getString("version"),
                        "content_type", rs.getString("content_type"), "classification", rs.getString("classification"),
                        "section_path", rs.getString("section_path"), "text", rs.getString("excerpt"),
                        "content_hash", rs.getString("content_hash"))).list();
    }

    private List<Map<String, Object>> knowledgeConcepts(UUID tenantId) {
        return jdbc.sql("""
                select concept_id, source_type, system, code, display
                from knowledge_concept where tenant_id = :tenant
                order by display limit 200
                """).param("tenant", tenantId)
                .query((rs, row) -> item(
                        "source_type", "KNOWLEDGE_CONCEPT",
                        "source_id", rs.getObject("concept_id", UUID.class),
                        "system", rs.getString("system") == null ? "" : rs.getString("system"),
                        "code", rs.getString("code") == null ? "" : rs.getString("code"),
                        "display", rs.getString("display"))).list();
    }

    private List<Map<String, Object>> knowledgeRelations(UUID tenantId) {
        return jdbc.sql("""
                select relation_id, from_concept, to_concept, rel_type, version
                from knowledge_relation where tenant_id = :tenant
                order by rel_type limit 200
                """).param("tenant", tenantId)
                .query((rs, row) -> item(
                        "source_type", "KNOWLEDGE_RELATION", "source_id", rs.getObject("relation_id", UUID.class),
                        "from_concept", rs.getObject("from_concept", UUID.class).toString(),
                        "to_concept", rs.getObject("to_concept", UUID.class).toString(),
                        "rel_type", rs.getString("rel_type"),
                        "version", rs.getString("version") == null ? "" : rs.getString("version"))).list();
    }

    private List<Map<String, Object>> pathwayKnowledgeIndex(UUID tenantId) {
        return jdbc.sql("""
                select stage.stage_id, version.version_no, knowledge.pathway_code, knowledge.display_name, knowledge.diagnosis_code,
                  knowledge.specialty_code, stage.stage_name, stage.sequence_no as stage_sequence,
                  coalesce(task.task_type, '') as task_type, coalesce(task.content, '') as content,
                  coalesce(task.code_ref, '') as code_ref
                from pathway_knowledge knowledge
                join pathway_knowledge_version version
                  on version.tenant_id = knowledge.tenant_id
                 and version.pathway_knowledge_id = knowledge.pathway_knowledge_id
                join pathway_knowledge_stage stage
                  on stage.tenant_id = version.tenant_id and stage.pathway_version_id = version.pathway_version_id
                left join pathway_knowledge_task task
                  on task.tenant_id = stage.tenant_id and task.stage_id = stage.stage_id
                where knowledge.tenant_id = :tenant and version.status = 'ACTIVE'
                order by knowledge.display_name, stage.sequence_no, task.sequence_no
                limit 300
                """).param("tenant", tenantId)
                .query((rs, row) -> item(
                        "source_type", "PATHWAY_KNOWLEDGE_STAGE", "source_id", rs.getObject("stage_id", UUID.class),
                        "version", rs.getInt("version_no"), "pathway_code", rs.getString("pathway_code"),
                        "display_name", rs.getString("display_name"),
                        "diagnosis_code", rs.getString("diagnosis_code"),
                        "specialty_code", rs.getString("specialty_code"),
                        "stage_name", rs.getString("stage_name"),
                        "stage_sequence", rs.getInt("stage_sequence"),
                        "task_type", rs.getString("task_type"),
                        "content", rs.getString("content"),
                        "code_ref", rs.getString("code_ref"))).list();
    }

    private List<Map<String, Object>> documents(UUID tenantId, UUID patientId, UUID encounterId) {
        return jdbc.sql("""
                select document.document_id, version.document_version_id, document.document_type_code,
                  version.status, version.version_no, version.sections::text, version.content_hash,
                  version.created_at
                from clinical_document document
                join clinical_document_version version on version.tenant_id = document.tenant_id
                  and version.document_id = document.document_id
                  and version.document_version_id = document.current_version_id
                where document.tenant_id = :tenant and document.patient_id = :patient
                  and document.encounter_id = :encounter and document.status <> 'VOID'
                order by document.updated_at desc, document.document_id limit 12
                """).param("tenant", tenantId).param("patient", patientId).param("encounter", encounterId)
                .query((rs, row) -> item(
                        "source_type", "DOCUMENT_VERSION",
                        "source_id", rs.getObject("document_version_id", UUID.class),
                        "document_id", rs.getObject("document_id", UUID.class),
                        "document_type", rs.getString("document_type_code"),
                        "status", rs.getString("status"),
                        "version", rs.getInt("version_no"),
                        "sections", map(rs.getString("sections")),
                        "content_hash", rs.getString("content_hash"),
                        "recorded_at", rs.getObject("created_at", OffsetDateTime.class))).list();
    }

    private List<Map<String, Object>> orders(UUID tenantId, UUID patientId, UUID encounterId) {
        return jdbc.sql("""
                select orders.order_id, orders.status, orders.order_scope, orders.clinical_indication,
                  item.order_item_id, item.item_type, item.catalog_code, item.display_name,
                  item.requested_quantity, item.quantity_unit, item.instructions, item.item_state,
                  item.row_version, item.updated_at
                from clinical_order orders
                join clinical_order_item item on item.tenant_id = orders.tenant_id and item.order_id = orders.order_id
                where orders.tenant_id = :tenant and orders.patient_id = :patient
                  and orders.encounter_id = :encounter
                order by item.updated_at desc, item.order_item_id limit 20
                """).param("tenant", tenantId).param("patient", patientId).param("encounter", encounterId)
                .query((rs, row) -> item(
                        "source_type", "ORDER_ITEM",
                        "source_id", rs.getObject("order_item_id", UUID.class),
                        "order_id", rs.getObject("order_id", UUID.class),
                        "order_status", rs.getString("status"),
                        "order_scope", rs.getString("order_scope"),
                        "clinical_indication", rs.getString("clinical_indication"),
                        "item_type", rs.getString("item_type"),
                        "catalog_code", rs.getString("catalog_code"),
                        "display_name", rs.getString("display_name"),
                        "quantity", rs.getBigDecimal("requested_quantity"),
                        "unit", rs.getString("quantity_unit"),
                        "instructions", rs.getString("instructions"),
                        "state", rs.getString("item_state"),
                        "version", rs.getLong("row_version"),
                        "recorded_at", rs.getObject("updated_at", OffsetDateTime.class))).list();
    }

    private List<Map<String, Object>> results(UUID tenantId, UUID patientId, UUID encounterId) {
        return jdbc.sql("""
                select result.result_id, version.result_version_id, result.report_type, result.source_system,
                  version.version_no, version.report_status, version.conclusion, version.reported_at,
                  version.change_type, version.correction_reason,
                  exists(select 1 from critical_value_case critical
                    where critical.tenant_id = result.tenant_id and critical.result_id = result.result_id
                      and critical.state <> 'DISPOSED') as open_critical
                from clinical_result result
                join clinical_result_version version on version.tenant_id = result.tenant_id
                  and version.result_id = result.result_id and version.result_version_id = result.current_version_id
                where result.tenant_id = :tenant and result.patient_id = :patient
                  and result.encounter_id = :encounter
                order by version.reported_at desc, result.result_id limit 20
                """).param("tenant", tenantId).param("patient", patientId).param("encounter", encounterId)
                .query((rs, row) -> item(
                        "source_type", "RESULT_VERSION",
                        "source_id", rs.getObject("result_version_id", UUID.class),
                        "result_id", rs.getObject("result_id", UUID.class),
                        "report_type", rs.getString("report_type"),
                        "source_system", rs.getString("source_system"),
                        "version", rs.getLong("version_no"),
                        "report_status", rs.getString("report_status"),
                        "conclusion", rs.getString("conclusion"),
                        "open_critical", rs.getBoolean("open_critical"),
                        "change_type", rs.getString("change_type"),
                        "correction_reason", rs.getString("correction_reason"),
                        "recorded_at", rs.getObject("reported_at", OffsetDateTime.class))).list();
    }

    private List<Map<String, Object>> tasks(UUID tenantId, UUID patientId, UUID encounterId) {
        return jdbc.sql("""
                select task_id, source_type, task_type, title, risk_level, state, business_state,
                  due_at, escalation_at, source_route, row_version, updated_at,
                  task_rule_config_id, task_rule_version, rule_snapshot::text,
                  (select count(*) from clinical_task_event event
                    where event.tenant_id = clinical_task.tenant_id
                      and event.task_id = clinical_task.task_id) as event_count,
                  (select count(*) from clinical_task_delegation delegation
                    where delegation.tenant_id = clinical_task.tenant_id
                      and delegation.task_id = clinical_task.task_id) as delegation_count,
                  (select count(*) from clinical_task_notification notification
                    where notification.tenant_id = clinical_task.tenant_id
                      and notification.task_id = clinical_task.task_id) as notification_count
                from clinical_task where tenant_id = :tenant and patient_id = :patient
                  and encounter_id = :encounter and state not in ('WITHDRAWN', 'EXPIRED')
                order by case risk_level when 'CRITICAL' then 0 when 'HIGH' then 1 else 2 end,
                  due_at nulls last, updated_at desc, task_id limit 20
                """).param("tenant", tenantId).param("patient", patientId).param("encounter", encounterId)
                .query((rs, row) -> item(
                        "source_type", "CLINICAL_TASK",
                        "source_id", rs.getObject("task_id", UUID.class),
                        "task_type", rs.getString("task_type"),
                        "origin", rs.getString("source_type"),
                        "title", rs.getString("title"),
                        "risk_level", rs.getString("risk_level"),
                        "state", rs.getString("state"),
                        "business_state", rs.getString("business_state"),
                        "due_at", rs.getObject("due_at", OffsetDateTime.class),
                        "escalation_at", rs.getObject("escalation_at", OffsetDateTime.class),
                        "task_rule_config_id", rs.getObject("task_rule_config_id", UUID.class),
                        "task_rule_version", rs.getObject("task_rule_version", Long.class),
                        "rule_snapshot", map(rs.getString("rule_snapshot")),
                        "event_count", rs.getLong("event_count"),
                        "delegation_count", rs.getLong("delegation_count"),
                        "notification_count", rs.getLong("notification_count"),
                        "source_route", rs.getString("source_route"),
                        "version", rs.getLong("row_version"),
                        "recorded_at", rs.getObject("updated_at", OffsetDateTime.class))).list();
    }

    private List<Map<String, Object>> vitalSigns(UUID tenantId, UUID patientId, UUID encounterId) {
        return jdbc.sql("""
                select vital_sign_record_id, recorded_at, source, temperature, pulse, respiration,
                  systolic_bp, diastolic_bp, spo2, row_version
                from vital_sign_record where tenant_id = :tenant and patient_id = :patient
                  and encounter_id = :encounter
                order by recorded_at desc, vital_sign_record_id desc limit 24
                """).param("tenant", tenantId).param("patient", patientId).param("encounter", encounterId)
                .query((rs, row) -> item(
                        "source_type", "VITAL_SIGN_RECORD", "source_id",
                        rs.getObject("vital_sign_record_id", UUID.class), "source", rs.getString("source"),
                        "temperature_c", rs.getBigDecimal("temperature"), "pulse_bpm", rs.getObject("pulse"),
                        "respiration_per_min", rs.getObject("respiration"),
                        "systolic_bp_mmhg", rs.getObject("systolic_bp"),
                        "diastolic_bp_mmhg", rs.getObject("diastolic_bp"), "spo2_percent", rs.getBigDecimal("spo2"),
                        "version", rs.getLong("row_version"),
                        "recorded_at", rs.getObject("recorded_at", OffsetDateTime.class))).list();
    }

    private List<Map<String, Object>> allergies(UUID tenantId, UUID patientId) {
        return jdbc.sql("""
                select allergy_id, substance_code, display_name, verification_status, clinical_status, severity, row_version
                from patient_allergy where tenant_id = :tenant and patient_id = :patient
                  and clinical_status = 'ACTIVE' and verification_status <> 'REFUTED'
                order by severity desc, display_name limit 100
                """).param("tenant", tenantId).param("patient", patientId).query((rs, row) -> item(
                        "source_type", "PATIENT_ALLERGY", "source_id", rs.getObject("allergy_id", UUID.class), "version", rs.getLong("row_version"), "allergy_id", rs.getObject("allergy_id", UUID.class).toString(), "substance_code", rs.getString("substance_code"),
                        "display_name", rs.getString("display_name"), "verification_status", rs.getString("verification_status"),
                        "severity", rs.getString("severity"), "row_version", rs.getLong("row_version"))).list();
    }

    private List<Map<String, Object>> carePlans(UUID tenantId, UUID patientId, UUID encounterId) {
        return jdbc.sql("""
                select care_plan_id, nursing_problem, goal, intervention, priority, status, row_version
                from nursing_care_plan where tenant_id = :tenant and patient_id = :patient and encounter_id = :encounter
                order by case priority when 'HIGH' then 1 when 'MEDIUM' then 2 else 3 end, created_at desc limit 100
                """).param("tenant", tenantId).param("patient", patientId).param("encounter", encounterId).query((rs, row) -> item(
                        "source_type", "NURSING_CARE_PLAN", "source_id", rs.getObject("care_plan_id", UUID.class), "version", rs.getLong("row_version"), "care_plan_id", rs.getObject("care_plan_id", UUID.class).toString(), "nursing_problem", rs.getString("nursing_problem"),
                        "goal", rs.getString("goal"), "intervention", rs.getString("intervention"), "priority", rs.getString("priority"),
                        "status", rs.getString("status"), "row_version", rs.getLong("row_version"))).list();
    }

    private List<Map<String, Object>> bloodTransfusions(UUID tenantId, UUID patientId, UUID encounterId) {
        return jdbc.sql("""
                select transfusion_id, blood_product, blood_type, volume_ml, started_at, reaction_type, row_version
                from blood_transfusion where tenant_id = :tenant and patient_id = :patient and encounter_id = :encounter
                order by started_at desc limit 100
                """).param("tenant", tenantId).param("patient", patientId).param("encounter", encounterId).query((rs, row) -> item(
                        "source_type", "BLOOD_TRANSFUSION", "source_id", rs.getObject("transfusion_id", UUID.class), "version", rs.getLong("row_version"), "transfusion_id", rs.getObject("transfusion_id", UUID.class).toString(), "blood_product", rs.getString("blood_product"),
                        "blood_type", rs.getString("blood_type"), "volume_ml", rs.getInt("volume_ml"),
                        "started_at", rs.getObject("started_at", OffsetDateTime.class).toInstant().toString(),
                        "reaction_type", rs.getString("reaction_type") == null ? "NONE" : rs.getString("reaction_type"), "row_version", rs.getLong("row_version"))).list();
    }

    private List<Map<String, Object>> laboratoryTrend(UUID tenantId, UUID patientId, UUID encounterId) {
        return jdbc.sql("""
                select observation.observation_id, observation.item_code, observation.item_name,
                  observation.value_type, observation.numeric_value, observation.text_value,
                  observation.unit, observation.reference_low, observation.reference_high,
                  observation.abnormal_flag, version.version_no, version.reported_at
                from clinical_result result
                join clinical_result_version version on version.tenant_id = result.tenant_id
                  and version.result_version_id = result.current_version_id
                join clinical_result_observation observation on observation.tenant_id = version.tenant_id
                  and observation.result_version_id = version.result_version_id
                where result.tenant_id = :tenant and result.patient_id = :patient
                  and result.encounter_id = :encounter and result.report_type = 'LAB'
                order by version.reported_at desc, observation.item_code, observation.observation_id limit 60
                """).param("tenant", tenantId).param("patient", patientId).param("encounter", encounterId)
                .query((rs, row) -> item(
                        "source_type", "LAB_OBSERVATION", "source_id", rs.getObject("observation_id", UUID.class),
                        "item_code", rs.getString("item_code"), "item_name", rs.getString("item_name"),
                        "value_type", rs.getString("value_type"), "numeric_value", rs.getBigDecimal("numeric_value"),
                        "text_value", rs.getString("text_value"), "unit", rs.getString("unit"),
                        "reference_low", rs.getBigDecimal("reference_low"),
                        "reference_high", rs.getBigDecimal("reference_high"),
                        "abnormal_flag", rs.getString("abnormal_flag"), "version", rs.getLong("version_no"),
                        "recorded_at", rs.getObject("reported_at", OffsetDateTime.class))).list();
    }

    private List<Map<String, Object>> surgerySchedule(UUID tenantId, UUID patientId, UUID encounterId) {
        return jdbc.sql("""
                select surgical_procedure_id, procedure_name, body_site, laterality, status,
                  scheduled_at, time_out_at, completed_at, row_version
                from surgical_procedure where tenant_id = :tenant and patient_id = :patient
                  and encounter_id = :encounter
                order by scheduled_at desc, surgical_procedure_id desc limit 12
                """).param("tenant", tenantId).param("patient", patientId).param("encounter", encounterId)
                .query((rs, row) -> item(
                        "source_type", "SURGICAL_PROCEDURE", "source_id",
                        rs.getObject("surgical_procedure_id", UUID.class),
                        "procedure_name", rs.getString("procedure_name"), "body_site", rs.getString("body_site"),
                        "laterality", rs.getString("laterality"), "status", rs.getString("status"),
                        "scheduled_at", rs.getObject("scheduled_at", OffsetDateTime.class),
                        "time_out_at", rs.getObject("time_out_at", OffsetDateTime.class),
                        "completed_at", rs.getObject("completed_at", OffsetDateTime.class),
                        "version", rs.getLong("row_version"))).list();
    }

    private List<Map<String, Object>> infectionEvents(UUID tenantId, UUID patientId, UUID encounterId) {
        return jdbc.sql("""
                select infection_event_id, infection_type, organism_code, status, conclusion,
                  reported_at, resolved_at, row_version
                from infection_monitoring_event where tenant_id = :tenant and patient_id = :patient
                  and encounter_id = :encounter
                order by reported_at desc, infection_event_id desc limit 20
                """).param("tenant", tenantId).param("patient", patientId).param("encounter", encounterId)
                .query((rs, row) -> item(
                        "source_type", "INFECTION_MONITORING_EVENT", "source_id",
                        rs.getObject("infection_event_id", UUID.class),
                        "infection_type", rs.getString("infection_type"), "organism_code", rs.getString("organism_code"),
                        "status", rs.getString("status"), "conclusion", rs.getString("conclusion"),
                        "resolved_at", rs.getObject("resolved_at", OffsetDateTime.class),
                        "version", rs.getLong("row_version"),
                        "recorded_at", rs.getObject("reported_at", OffsetDateTime.class))).list();
    }

    private List<Map<String, Object>> nursingRecords(UUID tenantId, UUID patientId, UUID encounterId) {
        return jdbc.sql("""
                select note_id, note_type, recorded_at, synced_at, device_id, content, row_version
                from nursing_bedside_note where tenant_id = :tenant and patient_id = :patient
                  and encounter_id = :encounter
                order by recorded_at desc, note_id desc limit 24
                """).param("tenant", tenantId).param("patient", patientId).param("encounter", encounterId)
                .query((rs, row) -> item(
                        "source_type", "NURSING_BEDSIDE_NOTE", "source_id", rs.getObject("note_id", UUID.class),
                        "note_type", rs.getString("note_type"), "content", rs.getString("content"),
                        "device_id", rs.getString("device_id"), "synced_at", rs.getObject("synced_at", OffsetDateTime.class),
                        "version", rs.getLong("row_version"),
                        "recorded_at", rs.getObject("recorded_at", OffsetDateTime.class))).list();
    }

    private List<Map<String, Object>> resultReports(
            UUID tenantId, UUID patientId, UUID encounterId, String reportType) {
        return jdbc.sql("""
                select result.result_id, version.result_version_id, result.source_system,
                  version.version_no, version.report_status, version.conclusion, version.reported_at,
                  version.change_type, version.correction_reason
                from clinical_result result
                join clinical_result_version version on version.tenant_id = result.tenant_id
                  and version.result_version_id = result.current_version_id
                where result.tenant_id = :tenant and result.patient_id = :patient
                  and result.encounter_id = :encounter and result.report_type = :report_type
                order by version.reported_at desc, result.result_id limit 20
                """).param("tenant", tenantId).param("patient", patientId).param("encounter", encounterId)
                .param("report_type", reportType).query((rs, row) -> item(
                        "source_type", reportType + "_REPORT_VERSION", "source_id",
                        rs.getObject("result_version_id", UUID.class), "result_id", rs.getObject("result_id", UUID.class),
                        "source_system", rs.getString("source_system"), "status", rs.getString("report_status"),
                        "conclusion", rs.getString("conclusion"), "change_type", rs.getString("change_type"),
                        "correction_reason", rs.getString("correction_reason"), "version", rs.getLong("version_no"),
                        "recorded_at", rs.getObject("reported_at", OffsetDateTime.class))).list();
    }

    private List<Map<String, Object>> typedDocuments(
            UUID tenantId, UUID patientId, UUID encounterId, String typeKeyword, String sourceType) {
        return jdbc.sql("""
                select document.document_id, version.document_version_id, document.document_type_code,
                  version.status, version.version_no, version.sections::text, version.content_hash,
                  version.created_at
                from clinical_document document
                join clinical_document_version version on version.tenant_id = document.tenant_id
                  and version.document_id = document.document_id
                  and version.document_version_id = document.current_version_id
                where document.tenant_id = :tenant and document.patient_id = :patient
                  and document.encounter_id = :encounter and document.status <> 'VOID'
                  and upper(document.document_type_code) like '%' || :keyword || '%'
                order by document.updated_at desc, document.document_id limit 20
                """).param("tenant", tenantId).param("patient", patientId).param("encounter", encounterId)
                .param("keyword", typeKeyword).query((rs, row) -> item(
                        "source_type", sourceType, "source_id", rs.getObject("document_version_id", UUID.class),
                        "document_id", rs.getObject("document_id", UUID.class),
                        "document_type", rs.getString("document_type_code"), "status", rs.getString("status"),
                        "version", rs.getInt("version_no"), "sections", map(rs.getString("sections")),
                        "content_hash", rs.getString("content_hash"),
                        "recorded_at", rs.getObject("created_at", OffsetDateTime.class))).list();
    }

    private List<Map<String, Object>> medicationAdministrations(
            UUID tenantId, UUID patientId, UUID encounterId) {
        return jdbc.sql("""
                select administration_id, order_id, drug_code, dose_value, dose_unit, route_code,
                  administered_at, verification_note, row_version
                from medication_administration where tenant_id = :tenant and patient_id = :patient
                  and encounter_id = :encounter
                order by administered_at desc, administration_id desc limit 40
                """).param("tenant", tenantId).param("patient", patientId).param("encounter", encounterId)
                .query((rs, row) -> item(
                        "source_type", "MEDICATION_ADMINISTRATION", "source_id",
                        rs.getObject("administration_id", UUID.class), "order_id", rs.getObject("order_id", UUID.class),
                        "drug_code", rs.getString("drug_code"), "dose_value", rs.getBigDecimal("dose_value"),
                        "dose_unit", rs.getString("dose_unit"), "route_code", rs.getString("route_code"),
                        "verification_note", rs.getString("verification_note"), "version", rs.getLong("row_version"),
                        "recorded_at", rs.getObject("administered_at", OffsetDateTime.class))).list();
    }

    private List<Map<String, Object>> encounterTimeline(UUID tenantId, UUID patientId, UUID encounterId) {
        List<Map<String, Object>> timeline = new ArrayList<>();
        timeline.addAll(documents(tenantId, patientId, encounterId));
        timeline.addAll(orders(tenantId, patientId, encounterId));
        timeline.addAll(results(tenantId, patientId, encounterId));
        timeline.addAll(tasks(tenantId, patientId, encounterId));
        return List.copyOf(timeline);
    }

    private List<Map<String, Object>> documentTemplates(UUID tenantId) {
        return jdbc.sql("""
                select template.template_id, version.template_version_id, template.template_code,
                  template.display_name, template.document_type_code, version.version_no,
                  version.section_schema::text, version.required_fields, version.display_rules::text,
                  version.effective_from, version.effective_until, version.row_version
                from clinical_document_template template
                join clinical_document_template_version version on version.tenant_id = template.tenant_id
                  and version.template_id = template.template_id and version.status = 'PUBLISHED'
                where template.tenant_id = :tenant and template.lifecycle_status = 'ACTIVE'
                  and version.effective_from <= now()
                  and (version.effective_until is null or version.effective_until > now())
                order by template.document_type_code, template.template_code limit 40
                """).param("tenant", tenantId).query((rs, row) -> item(
                        "source_type", "DOCUMENT_TEMPLATE_VERSION", "source_id",
                        rs.getObject("template_version_id", UUID.class),
                        "template_id", rs.getObject("template_id", UUID.class),
                        "template_code", rs.getString("template_code"), "display_name", rs.getString("display_name"),
                        "document_type", rs.getString("document_type_code"),
                        "section_schema", map(rs.getString("section_schema")),
                        "required_fields", List.of((String[]) rs.getArray("required_fields").getArray()),
                        "display_rules", map(rs.getString("display_rules")), "version", rs.getInt("version_no"),
                        "recorded_at", rs.getObject("effective_from", OffsetDateTime.class))).list();
    }

    private List<Map<String, Object>> clinicalRuleResults(
            UUID tenantId, UUID patientId, UUID encounterId) {
        return jdbc.sql("""
                select finding_id, document_id, document_version_id, rule_code, rule_version,
                  severity, message, field_path, state, row_version, updated_at
                from quality_finding where tenant_id = :tenant
                  and document_id in (select document_id from clinical_document
                    where tenant_id = :tenant and patient_id = :patient and encounter_id = :encounter)
                  and state in ('OPEN', 'ACKNOWLEDGED')
                order by case severity when 'BLOCKING' then 0 when 'WARNING' then 1 else 2 end,
                  updated_at desc, finding_id limit 40
                """).param("tenant", tenantId).param("patient", patientId).param("encounter", encounterId)
                .query((rs, row) -> item(
                        "source_type", "QUALITY_RULE_RESULT", "source_id", rs.getObject("finding_id", UUID.class),
                        "document_id", rs.getObject("document_id", UUID.class),
                        "document_version_id", rs.getObject("document_version_id", UUID.class),
                        "rule_code", rs.getString("rule_code"), "rule_version", rs.getString("rule_version"),
                        "severity", rs.getString("severity"), "message", rs.getString("message"),
                        "field_path", rs.getString("field_path"), "state", rs.getString("state"),
                        "version", rs.getLong("row_version"),
                        "recorded_at", rs.getObject("updated_at", OffsetDateTime.class))).list();
    }

    private List<Map<String, Object>> criticalValues(UUID tenantId, UUID patientId, UUID encounterId) {
        return jdbc.sql("""
                select critical.critical_value_id, critical.result_id, critical.observation_id,
                  critical.state, critical.row_version, critical.created_at, critical.updated_at,
                  observation.item_code, observation.item_name, observation.numeric_value,
                  observation.text_value, observation.unit, observation.abnormal_flag
                from critical_value_case critical
                join clinical_result_observation observation on observation.tenant_id = critical.tenant_id
                  and observation.observation_id = critical.observation_id
                where critical.tenant_id = :tenant and critical.patient_id = :patient
                  and critical.encounter_id = :encounter
                order by critical.updated_at desc, critical.critical_value_id limit 30
                """).param("tenant", tenantId).param("patient", patientId).param("encounter", encounterId)
                .query((rs, row) -> item(
                        "source_type", "CRITICAL_VALUE_CASE", "source_id",
                        rs.getObject("critical_value_id", UUID.class), "result_id", rs.getObject("result_id", UUID.class),
                        "observation_id", rs.getObject("observation_id", UUID.class),
                        "item_code", rs.getString("item_code"), "item_name", rs.getString("item_name"),
                        "numeric_value", rs.getBigDecimal("numeric_value"), "text_value", rs.getString("text_value"),
                        "unit", rs.getString("unit"), "abnormal_flag", rs.getString("abnormal_flag"),
                        "state", rs.getString("state"), "version", rs.getLong("row_version"),
                        "recorded_at", rs.getObject("updated_at", OffsetDateTime.class))).list();
    }

    private List<Map<String, Object>> consultations(UUID tenantId, UUID patientId, UUID encounterId) {
        return jdbc.sql("""
                select consultation_id, requested_department, urgency, reason, clinical_question,
                  status, due_at, requested_at, opinion, recommendation, opinion_signed_at,
                  completed_at, row_version, updated_at
                from inpatient_consultation where tenant_id = :tenant and patient_id = :patient
                  and encounter_id = :encounter
                order by requested_at desc, consultation_id limit 20
                """).param("tenant", tenantId).param("patient", patientId).param("encounter", encounterId)
                .query((rs, row) -> item(
                        "source_type", "INPATIENT_CONSULTATION", "source_id",
                        rs.getObject("consultation_id", UUID.class),
                        "requested_department", rs.getString("requested_department"),
                        "urgency", rs.getString("urgency"), "reason", rs.getString("reason"),
                        "clinical_question", rs.getString("clinical_question"), "status", rs.getString("status"),
                        "due_at", rs.getObject("due_at", OffsetDateTime.class), "opinion", rs.getString("opinion"),
                        "recommendation", rs.getString("recommendation"),
                        "opinion_signed_at", rs.getObject("opinion_signed_at", OffsetDateTime.class),
                        "completed_at", rs.getObject("completed_at", OffsetDateTime.class),
                        "version", rs.getLong("row_version"),
                        "recorded_at", rs.getObject("updated_at", OffsetDateTime.class))).list();
    }

    private List<Map<String, Object>> attachments(UUID tenantId, UUID patientId, UUID encounterId) {
        return jdbc.sql("""
                select attachment_id, document_id, document_version_id, original_filename, media_type,
                  byte_size, content_hash, created_at
                from clinical_document_attachment
                where tenant_id = :tenant and patient_id = :patient and encounter_id = :encounter
                  and storage_status = 'AVAILABLE' and malware_scan_status = 'PASSED'
                order by created_at desc, attachment_id limit 12
                """).param("tenant", tenantId).param("patient", patientId).param("encounter", encounterId)
                .query((rs, row) -> item(
                        "source_type", "ATTACHMENT",
                        "source_id", rs.getObject("attachment_id", UUID.class),
                        "document_id", rs.getObject("document_id", UUID.class),
                        "document_version_id", rs.getObject("document_version_id", UUID.class),
                        "filename", rs.getString("original_filename"),
                        "media_type", rs.getString("media_type"),
                        "byte_size", rs.getLong("byte_size"),
                        "content_hash", rs.getString("content_hash"),
                        "recorded_at", rs.getObject("created_at", OffsetDateTime.class))).list();
    }

    private List<Map<String, Object>> references(String toolCode, List<Map<String, Object>> items, String watermark) {
        return items.stream().map(source -> {
            if (source.get("source_id") == null || source.get("source_type") == null) {
                throw new AgentRunException("MEDICAL_AGENT_SOURCE_INVALID", 500,
                        "A medical-agent tool returned evidence without a source identity");
            }
            return item("source_type", source.get("source_type"), "source_id", source.get("source_id"),
                    "source_version", String.valueOf(source.getOrDefault("version", source.getOrDefault("content_hash", "1"))),
                    "tool_code", toolCode, "authorization_watermark", watermark,
                    "content_hash", source.get("content_hash"), "title", source.get("title"),
                    "section_path", source.get("section_path"), "excerpt", source.get("text"));
        }).toList();
    }

    private void record(UUID tenantId, UUID invocationId, UUID runId, UUID childRunId,
            String toolCode, String toolVersion, String watermark, String inputHash, String resultHash,
            int itemCount, String outcome, long durationMs, String errorCode) {
        jdbc.sql("""
                insert into medical_agent_tool_invocation(
                  tenant_id, invocation_id, root_run_id, child_run_id, tool_code, tool_version,
                  authorization_watermark, input_hash, result_hash, item_count, outcome,
                  duration_ms, error_code, completed_at)
                values (:tenant, :invocation, :root, :child, :tool, :version,
                  :watermark, :input_hash, :result_hash, :items, :outcome,
                  :duration, :error, now())
                """).param("tenant", tenantId).param("invocation", invocationId).param("root", runId)
                .param("child", childRunId).param("tool", toolCode).param("version", toolVersion)
                .param("watermark", watermark).param("input_hash", inputHash).param("result_hash", resultHash)
                .param("items", itemCount).param("outcome", outcome).param("duration", durationMs)
                .param("error", errorCode).update();
    }

    private Map<String, Object> map(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> value = objectMapper.convertValue(objectMapper.readTree(json), Map.class);
            return value;
        } catch (Exception invalid) {
            throw new AgentRunException("MEDICAL_AGENT_TOOL_RESULT_INVALID", 500,
                    "A clinical document contains invalid structured content");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception invalid) {
            throw new IllegalStateException("Medical-agent tool result cannot be serialized", invalid);
        }
    }

    private static Map<String, Object> item(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            Object value = entries[index + 1];
            if (value != null) result.put(String.valueOf(entries[index]), value);
        }
        return Map.copyOf(result);
    }

    private static long elapsedMillis(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    record ToolResult(UUID invocationId, String toolCode, String toolVersion, String displayName,
            List<Map<String, Object>> items, List<Map<String, Object>> sourceReferences, long durationMs) {}

    private record ToolDefinition(String code, String displayName) {}
}
