package org.haoemr.quality;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.haoemr.contracts.DataQualityFindingTransitionRequestWire;
import org.haoemr.contracts.DataQualityFindingWire;
import org.haoemr.contracts.DataQualityScanRunWire;
import org.haoemr.contracts.DataQualityTriageAdviceWire;
import org.haoemr.security.ClinicalIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
final class DataQualityOperationsService {
    private static final Logger log = LoggerFactory.getLogger(DataQualityOperationsService.class);
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    DataQualityOperationsService(JdbcClient jdbc, TransactionTemplate transactions, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    static boolean supportsScanner(String ruleCode) {
        return ScannerDefinition.supports(ruleCode);
    }

    static String requiredTargetEntity(String ruleCode) {
        return ScannerDefinition.forRule(ruleCode).targetEntity();
    }

    DataQualityScanRunWire scan(
            ClinicalIdentity identity, String idempotencyKey, UUID facilityId, UUID ruleId) {
        return scanInternal(identity.tenantId(), identity.userId(), ScanInitiator.MANUAL,
                "DATA_QUALITY_SCAN_START", idempotencyKey, facilityId, ruleId);
    }

    int sweepScheduledScans() {
        List<ScheduledScanCandidate> candidates = jdbc.sql("""
                select rule.tenant_id, rule.data_quality_rule_id, rule.rule_code, facility.facility_id
                from data_quality_rule rule
                join facility facility on facility.tenant_id = rule.tenant_id
                where rule.status = 'ACTIVE' and facility.status = 'ACTIVE'
                  and not exists (
                    select 1 from data_quality_scan_run scan
                    where scan.tenant_id = rule.tenant_id
                      and scan.data_quality_rule_id = rule.data_quality_rule_id
                      and scan.facility_id = facility.facility_id
                      and scan.started_at >= now() - interval '24 hours'
                  )
                order by rule.tenant_id, rule.data_quality_rule_id, facility.facility_id
                limit 100
                """).query((rs, row) -> new ScheduledScanCandidate(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("data_quality_rule_id", UUID.class),
                        rs.getString("rule_code"), rs.getObject("facility_id", UUID.class))).list();
        int completed = 0;
        String day = LocalDate.now(ZoneOffset.UTC).toString();
        for (ScheduledScanCandidate candidate : candidates) {
            if (!ScannerDefinition.supports(candidate.ruleCode())) {
                log.warn("Skipping scheduled data-quality scan with no allowlisted scanner: tenant={}, rule={}",
                        candidate.tenantId(), candidate.ruleId());
                continue;
            }
            String key = "scheduled-" + day + "-" + candidate.facilityId() + "-" + candidate.ruleId();
            try {
                scanInternal(candidate.tenantId(), null, ScanInitiator.SCHEDULED,
                        "DATA_QUALITY_SCHEDULED_SCAN", key, candidate.facilityId(), candidate.ruleId());
                completed++;
            } catch (DataQualityOperationsException failure) {
                if (!"IDEMPOTENCY_REPLAY".equals(failure.code())) throw failure;
            }
        }
        return completed;
    }

    private DataQualityScanRunWire scanInternal(
            UUID tenantId, UUID actorUserId, ScanInitiator initiator, String commandScope,
            String idempotencyKey, UUID facilityId, UUID ruleId) {
        RuleHead rule = loadRule(tenantId, ruleId);
        if (!"ACTIVE".equals(rule.status())) {
            throw failure("DATA_QUALITY_RULE_INACTIVE", 409, "Only an active rule can be scanned");
        }
        ScannerDefinition scanner = ScannerDefinition.forRule(rule.ruleCode());
        return transactions.execute(status -> {
            beginCommand(tenantId, commandScope, idempotencyKey,
                    sha256(facilityId + "|" + ruleId + "|" + rule.ruleCode()));
            UUID scanId = UUID.randomUUID();
            jdbc.sql("""
                    insert into data_quality_scan_run(
                      tenant_id, data_quality_scan_id, data_quality_rule_id, facility_id,
                      target_entity, status, started_by, initiator_kind)
                    values (:tenant, :scan, :rule, :facility, :target, 'RUNNING', :actor, :initiator)
                    """).param("tenant", tenantId).param("scan", scanId)
                    .param("rule", ruleId).param("facility", facilityId)
                    .param("target", rule.targetEntity()).param("actor", actorUserId)
                    .param("initiator", initiator.name()).update();

            ScanCounts counts = jdbc.sql(scanner.aggregateSql())
                    .param("tenant", tenantId).param("facility", facilityId)
                    .query((rs, row) -> new ScanCounts(
                            rs.getLong("total_count"), rs.getLong("passed_count"))).single();
            long failed = counts.total() - counts.passed();
            BigDecimal score = counts.total() == 0
                    ? BigDecimal.ZERO
                    : BigDecimal.valueOf(counts.passed())
                            .divide(BigDecimal.valueOf(counts.total()), 6, RoundingMode.HALF_UP);

            if (failed > 0) {
                jdbc.sql(scanner.findingInsertSql())
                        .param("tenant", tenantId).param("facility", facilityId)
                        .param("scan", scanId).param("rule", ruleId)
                        .param("reason_code", scanner.reasonCode())
                        .param("reason_detail", scanner.reasonDetail())
                        .param("severity", rule.severity()).update();
                jdbc.sql("""
                        insert into data_quality_finding_event(
                          tenant_id, data_quality_finding_event_id, data_quality_finding_id,
                          event_type, from_status, to_status, note, actor_user_id)
                        select tenant_id, gen_random_uuid(), data_quality_finding_id,
                          'DETECTED', null, 'OPEN', 'allowlisted fact scan', :actor
                        from data_quality_finding
                        where tenant_id = :tenant and data_quality_scan_id = :scan
                        """).param("actor", actorUserId).param("tenant", tenantId)
                        .param("scan", scanId).update();
            }
            String finalStatus = counts.total() == 0 ? "NO_DATA" : "COMPLETED";
            jdbc.sql("""
                    update data_quality_scan_run set status = :status, total_count = :total,
                      passed_count = :passed, failed_count = :failed, score = :score,
                      completed_at = now(), row_version = row_version + 1
                    where tenant_id = :tenant and data_quality_scan_id = :scan and status = 'RUNNING'
                    """).param("status", finalStatus).param("total", counts.total())
                    .param("passed", counts.passed()).param("failed", failed).param("score", score)
                    .param("tenant", tenantId).param("scan", scanId).update();
            appendEvidence(tenantId, actorUserId, scanId, "DATA_QUALITY_SCAN_COMPLETED", "DataQualityScanCompleted");
            completeCommand(tenantId, commandScope, idempotencyKey, scanId);
            return scan(tenantId, scanId);
        });
    }

    List<DataQualityScanRunWire> listScans(ClinicalIdentity identity, UUID ruleId) {
        assertRuleVisible(identity.tenantId(), ruleId);
        return jdbc.sql("""
                select data_quality_scan_id from data_quality_scan_run
                where tenant_id = :tenant and data_quality_rule_id = :rule
                order by started_at desc, data_quality_scan_id desc limit 100
                """).param("tenant", identity.tenantId()).param("rule", ruleId)
                .query(UUID.class).list().stream().map(id -> scan(identity.tenantId(), id)).toList();
    }

    List<DataQualityFindingWire> listFindings(ClinicalIdentity identity, UUID facilityId, UUID scanId) {
        if (scanId != null) {
            assertScanVisible(identity.tenantId(), scanId);
            return jdbc.sql("""
                    select f.data_quality_finding_id
                    from data_quality_finding f
                    join data_quality_scan_run s
                      on s.tenant_id = f.tenant_id and s.data_quality_scan_id = f.data_quality_scan_id
                    where f.tenant_id = :tenant and s.facility_id = :facility
                      and f.data_quality_scan_id = :scan
                    order by case f.severity when 'BLOCKING' then 1 when 'WARNING' then 2 else 3 end,
                      f.updated_at desc, f.data_quality_finding_id
                    limit 1000
                    """).param("tenant", identity.tenantId()).param("facility", facilityId).param("scan", scanId)
                    .query(UUID.class).list().stream().map(id -> finding(identity.tenantId(), id)).toList();
        }
        return jdbc.sql("""
                select f.data_quality_finding_id
                from data_quality_finding f
                join data_quality_scan_run s
                  on s.tenant_id = f.tenant_id and s.data_quality_scan_id = f.data_quality_scan_id
                where f.tenant_id = :tenant and s.facility_id = :facility
                order by case f.severity when 'BLOCKING' then 1 when 'WARNING' then 2 else 3 end,
                  f.updated_at desc, f.data_quality_finding_id
                limit 1000
                """).param("tenant", identity.tenantId()).param("facility", facilityId)
                .query(UUID.class).list().stream().map(id -> finding(identity.tenantId(), id)).toList();
    }

    DataQualityFindingWire transition(
            ClinicalIdentity identity, String idempotencyKey, UUID findingId,
            DataQualityFindingTransitionRequestWire request) {
        if (request.action() == null || request.rowVersion() == null || request.rowVersion() < 1) {
            throw invalid("action and row_version are required");
        }
        String note = requireText(request.note(), "note");
        return transactions.execute(status -> {
            beginCommand(identity, "DATA_QUALITY_FINDING_TRANSITION", idempotencyKey,
                    sha256(findingId + "|" + request.action() + "|" + request.rowVersion() + "|" + note));
            FindingHead current = jdbc.sql("""
                    select status, assigned_to, corrective_action, row_version
                    from data_quality_finding
                    where tenant_id = :tenant and data_quality_finding_id = :finding for update
                    """).param("tenant", identity.tenantId()).param("finding", findingId)
                    .query((rs, row) -> new FindingHead(rs.getString("status"),
                            rs.getObject("assigned_to", UUID.class), rs.getString("corrective_action"),
                            rs.getLong("row_version"))).optional().orElseThrow(DataQualityOperationsService::denied);
            if (current.rowVersion() != request.rowVersion()) {
                throw failure("DATA_QUALITY_FINDING_VERSION_CONFLICT", 409,
                        "Finding changed; refresh before applying the transition");
            }
            if (request.action() == DataQualityFindingTransitionRequestWire.ActionValue.ASSIGN) {
                assertAssignable(identity.tenantId(), request.organizationId(), request.facilityId(),
                        request.assigneeId() == null ? identity.userId() : request.assigneeId());
            }
            Transition next = Transition.resolve(current, request.action(), request.assigneeId(), note,
                    identity.userId());
            int updated = jdbc.sql("""
                    update data_quality_finding set status = :status, assigned_to = :assignee,
                      corrective_action = :action, updated_at = now(), row_version = row_version + 1
                    where tenant_id = :tenant and data_quality_finding_id = :finding
                      and row_version = :version
                    """).param("status", next.status()).param("assignee", next.assignee())
                    .param("action", next.correctiveAction()).param("tenant", identity.tenantId())
                    .param("finding", findingId).param("version", request.rowVersion()).update();
            if (updated != 1) {
                throw failure("DATA_QUALITY_FINDING_VERSION_CONFLICT", 409,
                        "Finding changed; refresh before applying the transition");
            }
            jdbc.sql("""
                    insert into data_quality_finding_event(
                      tenant_id, data_quality_finding_event_id, data_quality_finding_id,
                      event_type, from_status, to_status, note, actor_user_id)
                    values (:tenant, :event, :finding, :event_type, :from_status, :to_status, :note, :actor)
                    """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                    .param("finding", findingId).param("event_type", next.eventType())
                    .param("from_status", current.status()).param("to_status", next.status())
                    .param("note", note).param("actor", identity.userId()).update();
            appendEvidence(identity, findingId, "DATA_QUALITY_FINDING_" + next.eventType(),
                    "DataQualityFinding" + next.eventType());
            completeCommand(identity, "DATA_QUALITY_FINDING_TRANSITION", idempotencyKey, findingId);
            return finding(identity.tenantId(), findingId);
        });
    }

    DataQualityTriageAdviceWire createTriageAdvice(
            ClinicalIdentity identity, String idempotencyKey, UUID scanId) {
        ScanHead scan = loadScanHead(identity.tenantId(), scanId);
        if ("RUNNING".equals(scan.status()) || "FAILED".equals(scan.status())) {
            throw failure("DATA_QUALITY_SCAN_NOT_TRIAGEABLE", 409,
                    "Only completed or no-data scans can be triaged");
        }
        return transactions.execute(status -> {
            beginCommand(identity, "DATA_QUALITY_TRIAGE_CREATE", idempotencyKey,
                    sha256(scanId + "|" + scan.failedCount() + "|" + scan.rowVersion()));
            Map<String, Long> reasonCounts = new LinkedHashMap<>();
            jdbc.sql("""
                    select reason_code, count(*) as finding_count from data_quality_finding
                    where tenant_id = :tenant and data_quality_scan_id = :scan
                    group by reason_code order by finding_count desc, reason_code
                    """).param("tenant", identity.tenantId()).param("scan", scanId)
                    .query((rs, row) -> Map.entry(rs.getString("reason_code"), rs.getLong("finding_count")))
                    .list().forEach(entry -> reasonCounts.put(entry.getKey(), entry.getValue()));
            List<String> actions = prioritizedActions(scan.ruleCode(), scan.failedCount());
            String risk = scan.failedCount() == 0 ? "LOW"
                    : ("BLOCKING".equals(scan.severity()) || scan.failedCount() >= 10 ? "HIGH" : "MEDIUM");
            String summary = scan.failedCount() == 0
                    ? "本次扫描未发现失败项；建议按规则周期继续监测，零样本时先核验源系统入湖状态。"
                    : "本次扫描发现 " + scan.failedCount() + " 个失败实体；建议由数据治理人员按证据代码分派、整改并复核。";
            String evidenceHash = sha256(scanId + "|" + scan.ruleCode() + "|" + scan.totalCount()
                    + "|" + scan.failedCount() + "|" + reasonCounts);
            UUID adviceId = UUID.randomUUID();
            jdbc.sql("""
                    insert into data_quality_triage_advice(
                      tenant_id, data_quality_triage_advice_id, data_quality_scan_id, engine_kind,
                      risk_level, finding_count, summary, prioritized_actions, evidence_hash, generated_by)
                    values (:tenant, :advice, :scan, 'DETERMINISTIC_RULE_BASED', :risk, :count,
                      :summary, cast(:actions as jsonb), :hash, :actor)
                    """).param("tenant", identity.tenantId()).param("advice", adviceId)
                    .param("scan", scanId).param("risk", risk).param("count", scan.failedCount())
                    .param("summary", summary).param("actions", toJson(actions)).param("hash", evidenceHash)
                    .param("actor", identity.userId()).update();
            appendEvidence(identity, adviceId, "DATA_QUALITY_TRIAGE_ADVICE_CREATED",
                    "DataQualityTriageAdviceCreated");
            completeCommand(identity, "DATA_QUALITY_TRIAGE_CREATE", idempotencyKey, adviceId);
            return advice(identity.tenantId(), adviceId);
        });
    }

    List<DataQualityTriageAdviceWire> listTriageAdvice(ClinicalIdentity identity, UUID scanId) {
        assertScanVisible(identity.tenantId(), scanId);
        return jdbc.sql("""
                select data_quality_triage_advice_id from data_quality_triage_advice
                where tenant_id = :tenant and data_quality_scan_id = :scan
                order by generated_at desc, data_quality_triage_advice_id desc limit 50
                """).param("tenant", identity.tenantId()).param("scan", scanId)
                .query(UUID.class).list().stream().map(id -> advice(identity.tenantId(), id)).toList();
    }

    private DataQualityScanRunWire scan(UUID tenantId, UUID scanId) {
        return jdbc.sql("""
                select data_quality_scan_id, data_quality_rule_id, target_entity, status,
                  total_count, passed_count, failed_count, score, started_by, initiator_kind,
                  started_at, completed_at, row_version
                from data_quality_scan_run
                where tenant_id = :tenant and data_quality_scan_id = :scan
                """).param("tenant", tenantId).param("scan", scanId)
                .query((rs, row) -> new DataQualityScanRunWire(
                        rs.getObject("data_quality_scan_id", UUID.class),
                        rs.getObject("data_quality_rule_id", UUID.class), rs.getString("target_entity"),
                        DataQualityScanRunWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getLong("total_count"), rs.getLong("passed_count"), rs.getLong("failed_count"),
                        rs.getBigDecimal("score").doubleValue(), rs.getObject("started_by", UUID.class),
                        DataQualityScanRunWire.InitiatorKindValue.valueOf(rs.getString("initiator_kind")),
                        rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                        nullableInstant(rs.getObject("completed_at", OffsetDateTime.class)),
                        rs.getLong("row_version"))).optional().orElseThrow(DataQualityOperationsService::denied);
    }

    private DataQualityFindingWire finding(UUID tenantId, UUID findingId) {
        return jdbc.sql("""
                select data_quality_finding_id, data_quality_scan_id, data_quality_rule_id,
                  target_entity_id, reason_code, reason_detail, severity, status, assigned_to,
                  corrective_action, detected_at, updated_at, row_version
                from data_quality_finding
                where tenant_id = :tenant and data_quality_finding_id = :finding
                """).param("tenant", tenantId).param("finding", findingId)
                .query((rs, row) -> new DataQualityFindingWire(
                        rs.getObject("data_quality_finding_id", UUID.class),
                        rs.getObject("data_quality_scan_id", UUID.class),
                        rs.getObject("data_quality_rule_id", UUID.class),
                        rs.getObject("target_entity_id", UUID.class), rs.getString("reason_code"),
                        rs.getString("reason_detail"),
                        DataQualityFindingWire.SeverityValue.valueOf(rs.getString("severity")),
                        DataQualityFindingWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getObject("assigned_to", UUID.class), rs.getString("corrective_action"),
                        rs.getObject("detected_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant(), rs.getLong("row_version")))
                .optional().orElseThrow(DataQualityOperationsService::denied);
    }

    private DataQualityTriageAdviceWire advice(UUID tenantId, UUID adviceId) {
        return jdbc.sql("""
                select data_quality_triage_advice_id, data_quality_scan_id, engine_kind, risk_level,
                  finding_count, summary, prioritized_actions::text as prioritized_actions,
                  evidence_hash, generated_by, generated_at
                from data_quality_triage_advice
                where tenant_id = :tenant and data_quality_triage_advice_id = :advice
                """).param("tenant", tenantId).param("advice", adviceId)
                .query((rs, row) -> new DataQualityTriageAdviceWire(
                        rs.getObject("data_quality_triage_advice_id", UUID.class),
                        rs.getObject("data_quality_scan_id", UUID.class),
                        DataQualityTriageAdviceWire.EngineKindValue.valueOf(rs.getString("engine_kind")),
                        DataQualityTriageAdviceWire.RiskLevelValue.valueOf(rs.getString("risk_level")),
                        rs.getLong("finding_count"), rs.getString("summary"),
                        readStringList(rs.getString("prioritized_actions")), rs.getString("evidence_hash"),
                        rs.getObject("generated_by", UUID.class),
                        rs.getObject("generated_at", OffsetDateTime.class).toInstant()))
                .optional().orElseThrow(DataQualityOperationsService::denied);
    }

    private RuleHead loadRule(UUID tenantId, UUID ruleId) {
        return jdbc.sql("""
                select rule_code, target_entity, severity, status from data_quality_rule
                where tenant_id = :tenant and data_quality_rule_id = :rule
                """).param("tenant", tenantId).param("rule", ruleId)
                .query((rs, row) -> new RuleHead(rs.getString("rule_code"), rs.getString("target_entity"),
                        rs.getString("severity"), rs.getString("status")))
                .optional().orElseThrow(DataQualityOperationsService::denied);
    }

    private ScanHead loadScanHead(UUID tenantId, UUID scanId) {
        return jdbc.sql("""
                select scan.status, scan.total_count, scan.failed_count, scan.row_version,
                  rule.rule_code, rule.severity
                from data_quality_scan_run scan join data_quality_rule rule
                  on rule.tenant_id = scan.tenant_id and rule.data_quality_rule_id = scan.data_quality_rule_id
                where scan.tenant_id = :tenant and scan.data_quality_scan_id = :scan
                """).param("tenant", tenantId).param("scan", scanId)
                .query((rs, row) -> new ScanHead(rs.getString("status"), rs.getLong("total_count"),
                        rs.getLong("failed_count"), rs.getLong("row_version"),
                        rs.getString("rule_code"), rs.getString("severity")))
                .optional().orElseThrow(DataQualityOperationsService::denied);
    }

    private void assertRuleVisible(UUID tenantId, UUID ruleId) { loadRule(tenantId, ruleId); }
    private void assertScanVisible(UUID tenantId, UUID scanId) { loadScanHead(tenantId, scanId); }

    private void assertAssignable(UUID tenantId, UUID organizationId, UUID facilityId, UUID assigneeId) {
        Integer eligible = jdbc.sql("""
                select count(*) from app_user app
                where app.tenant_id = :tenant and app.user_id = :assignee and app.status = 'ACTIVE'
                  and exists (
                    select 1 from role_assignment role
                    where role.tenant_id = app.tenant_id and role.user_id = app.user_id
                      and role.organization_id = :organization
                      and (role.facility_id is null or role.facility_id = :facility)
                      and role.status = 'ACTIVE' and role.valid_from <= now()
                      and (role.valid_until is null or role.valid_until > now()))
                """).param("tenant", tenantId).param("assignee", assigneeId)
                .param("organization", organizationId).param("facility", facilityId)
                .query(Integer.class).single();
        if (eligible != 1) {
            throw failure("DATA_QUALITY_ASSIGNEE_NOT_ELIGIBLE", 422,
                    "Assignee must be an active workforce member in the current organization and facility");
        }
    }

    private List<String> prioritizedActions(String ruleCode, long failedCount) {
        List<String> actions = new ArrayList<>();
        if (failedCount == 0) {
            actions.add("确认扫描样本数和源系统入湖水位后，按计划周期复扫。");
            return actions;
        }
        actions.add(switch (ruleCode) {
            case "DQ-ORDER-PATIENT" -> "核对患者主索引、就诊与医嘱的患者/院区绑定，修复前阻断后续执行投影。";
            case "DQ-ALLERGY-CODE" -> "由药学与数据标准人员核对过敏物标准编码，禁止用自由文本替代编码。";
            case "DQ-CRITICAL-ACK" -> "按卫健委危急值闭环要求核对通知、确认与处置时间链，逾时项升级科室质控。";
            case "DQ-SURGERY-SITE" -> "核对手术名称、部位、侧别与术前 Time-out 证据，不完整项不得进入手术执行。";
            case "DQ-TRANSFUSION-TRACE" -> "核对血制品单元号、患者/就诊绑定与双人核对证据，缺失项进入输血科整改。";
            case "DQ-DISCHARGE-DIAG" -> "由病案室核对出院诊断、出院状态与就诊终结时间，差异项按病案首页纠错流程处置。";
            default -> throw failure("DATA_QUALITY_RULE_SCANNER_UNAVAILABLE", 422,
                    "No allowlisted fact scanner is registered for rule " + ruleCode);
        });
        actions.add("按阻断级、警告级顺序分派工单，整改后由不同人执行复核和关闭。");
        actions.add("保留扫描水位、整改说明、复核人和审计链，不删除原始失败证据。");
        return actions;
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        beginCommand(identity.tenantId(), scope, key, requestHash);
    }

    private void beginCommand(UUID tenantId, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw failure("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", tenantId).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) throw failure("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID resourceId) {
        completeCommand(identity.tenantId(), scope, key, resourceId);
    }

    private void completeCommand(UUID tenantId, String scope, String key, UUID resourceId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", resourceId).param("tenant", tenantId)
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, UUID resourceId, String action, String eventType) {
        appendEvidence(identity.tenantId(), identity.userId(), resourceId, action, eventType);
    }

    private void appendEvidence(
            UUID tenantId, UUID actorUserId, UUID resourceId, String action, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", tenantId).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", tenantId).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(tenantId + "|" + auditId + "|" + action + "|"
                + resourceId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'DATA_QUALITY_OPERATION', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", tenantId).param("audit", auditId)
                .param("actor", actorUserId).param("action", action).param("resource", resourceId)
                .param("patient_hash", sha256(tenantId + "|null"))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'DATA_QUALITY_OPERATION', :aggregate, 1, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", tenantId).param("event", UUID.randomUUID())
                .param("aggregate", resourceId).param("event_type", eventType).update();
    }

    private String toJson(List<String> values) {
        try { return objectMapper.writeValueAsString(values); }
        catch (Exception failure) { throw new IllegalStateException("Could not serialize triage actions", failure); }
    }

    private List<String> readStringList(String json) {
        try {
            List<String> values = new ArrayList<>();
            objectMapper.readTree(json).forEach(node -> values.add(node.stringValue()));
            return List.copyOf(values);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not read triage actions", failure);
        }
    }

    private static java.time.Instant nullableInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().length() < 2 || value.length() > 2000) {
            throw invalid(field + " must contain 2 to 2000 characters");
        }
        return value.trim();
    }

    private static DataQualityOperationsException invalid(String message) {
        return failure("DATA_QUALITY_OPERATION_REQUEST_INVALID", 400, message);
    }

    private static DataQualityOperationsException denied() {
        return failure("CONTEXT_NOT_PERMITTED", 403, "The requested data quality evidence is not permitted");
    }

    private static DataQualityOperationsException failure(String code, int status, String message) {
        return new DataQualityOperationsException(code, status, message);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record RuleHead(String ruleCode, String targetEntity, String severity, String status) {}
    private record ScheduledScanCandidate(UUID tenantId, UUID ruleId, String ruleCode, UUID facilityId) {}
    private record ScanCounts(long total, long passed) {}
    private enum ScanInitiator { MANUAL, SCHEDULED }
    private record FindingHead(String status, UUID assignedTo, String correctiveAction, long rowVersion) {}
    private record ScanHead(String status, long totalCount, long failedCount, long rowVersion,
                            String ruleCode, String severity) {}
    private record Transition(String status, UUID assignee, String correctiveAction, String eventType) {
        static Transition resolve(FindingHead current, DataQualityFindingTransitionRequestWire.ActionValue action,
                                  UUID requestedAssignee, String note, UUID actor) {
            return switch (action) {
                case ASSIGN -> {
                    requireState(current.status(), List.of("OPEN", "ASSIGNED"), action);
                    yield new Transition("ASSIGNED", requestedAssignee == null ? actor : requestedAssignee,
                            current.correctiveAction(), "ASSIGNED");
                }
                case REMEDIATE -> {
                    requireState(current.status(), List.of("OPEN", "ASSIGNED"), action);
                    yield new Transition("REMEDIATED", current.assignedTo() == null ? actor : current.assignedTo(),
                            note, "REMEDIATED");
                }
                case VERIFY -> {
                    requireState(current.status(), List.of("REMEDIATED"), action);
                    if (actor.equals(current.assignedTo())) {
                        throw failure("DATA_QUALITY_FINDING_SOD_VIOLATION", 409,
                                "The remediation assignee cannot verify their own finding");
                    }
                    yield new Transition("VERIFIED", current.assignedTo(), current.correctiveAction(), "VERIFIED");
                }
                case CLOSE -> {
                    requireState(current.status(), List.of("VERIFIED"), action);
                    yield new Transition("CLOSED", current.assignedTo(), current.correctiveAction(), "CLOSED");
                }
                case REOPEN -> {
                    requireState(current.status(), List.of("REMEDIATED", "VERIFIED", "CLOSED"), action);
                    yield new Transition("OPEN", current.assignedTo(), current.correctiveAction(), "REOPENED");
                }
            };
        }

        private static void requireState(String actual, List<String> allowed,
                                         DataQualityFindingTransitionRequestWire.ActionValue action) {
            if (!allowed.contains(actual)) {
                throw failure("DATA_QUALITY_FINDING_STATE_INVALID", 409,
                        "Action " + action + " is not allowed from status " + actual);
            }
        }
    }

    private record ScannerDefinition(String ruleCode, String fromClause, String scopePredicate,
                                     String entityId, String passPredicate,
                                     String reasonCode, String reasonDetail, String targetEntity) {
        static boolean supports(String ruleCode) {
            return List.of("DQ-ORDER-PATIENT", "DQ-ALLERGY-CODE", "DQ-CRITICAL-ACK",
                    "DQ-SURGERY-SITE", "DQ-TRANSFUSION-TRACE", "DQ-DISCHARGE-DIAG").contains(ruleCode);
        }

        static ScannerDefinition forRule(String ruleCode) {
            return switch (ruleCode) {
                case "DQ-ORDER-PATIENT" -> new ScannerDefinition(ruleCode,
                        "clinical_order source join encounter encounter on encounter.tenant_id = source.tenant_id and encounter.encounter_id = source.encounter_id",
                        "source.tenant_id = :tenant and source.facility_id = :facility", "source.order_id",
                        "source.patient_id = encounter.patient_id and source.facility_id = encounter.facility_id",
                        "ORDER_CONTEXT_MISMATCH", "医嘱患者或院区与就诊上下文不一致", "ClinicalOrder");
                case "DQ-ALLERGY-CODE" -> new ScannerDefinition(ruleCode, "patient_allergy source",
                        "source.tenant_id = :tenant and exists (select 1 from encounter encounter_scope where encounter_scope.tenant_id = source.tenant_id and encounter_scope.patient_id = source.patient_id and encounter_scope.facility_id = :facility)",
                        "source.allergy_id",
                        "source.substance_code ~ '^[A-Z0-9][A-Z0-9._-]{1,127}$'",
                        "ALLERGY_CODE_NONSTANDARD", "药物过敏物未使用可管理的标准编码", "PatientAllergy");
                case "DQ-CRITICAL-ACK" -> new ScannerDefinition(ruleCode,
                        "critical_value_case source join clinical_result result on result.tenant_id = source.tenant_id and result.result_id = source.result_id",
                        "source.tenant_id = :tenant and result.facility_id = :facility", "source.critical_value_id",
                        "exists (select 1 from critical_value_event event where event.tenant_id = source.tenant_id and event.critical_value_id = source.critical_value_id and event.event_type = 'ACKNOWLEDGED' and event.recipient_confirmed = true and event.occurred_at <= source.created_at + interval '30 minutes')",
                        "CRITICAL_VALUE_ACK_TIMEOUT", "危急值未在 30 分钟内完成收到确认", "CriticalValueCase");
                case "DQ-SURGERY-SITE" -> new ScannerDefinition(ruleCode, "surgical_procedure source",
                        "source.tenant_id = :tenant and source.facility_id = :facility", "source.surgical_procedure_id",
                        "length(trim(source.procedure_name)) >= 2 and source.body_site is not null and source.laterality is not null and (source.body_site not in ('UPPER_EXTREMITY', 'LOWER_EXTREMITY') or source.laterality <> 'NONE')",
                        "SURGERY_SITE_INCOMPLETE", "手术名称、部位或侧别不完整", "SurgicalProcedure");
                case "DQ-TRANSFUSION-TRACE" -> new ScannerDefinition(ruleCode,
                        "blood_transfusion source join encounter encounter on encounter.tenant_id = source.tenant_id and encounter.encounter_id = source.encounter_id",
                        "source.tenant_id = :tenant and source.facility_id = :facility", "source.transfusion_id",
                        "source.patient_id = encounter.patient_id and source.facility_id = encounter.facility_id and source.administered_by <> source.verified_by and length(trim(source.unit_number)) >= 2",
                        "TRANSFUSION_TRACE_INCOMPLETE", "输血单元号、患者就诊绑定或双人核对证据不完整", "BloodTransfusion");
                case "DQ-DISCHARGE-DIAG" -> new ScannerDefinition(ruleCode,
                        "inpatient_discharge source join inpatient_admission admission on admission.tenant_id = source.tenant_id and admission.admission_id = source.admission_id join encounter encounter on encounter.tenant_id = admission.tenant_id and encounter.encounter_id = admission.encounter_id",
                        "source.tenant_id = :tenant and admission.facility_id = :facility", "source.discharge_id",
                        "length(trim(source.discharge_diagnosis)) > 0 and admission.status = 'DISCHARGED' and encounter.status = 'FINISHED' and admission.discharged_at = source.discharged_at",
                        "DISCHARGE_FACT_INCONSISTENT", "出院诊断、住院状态或就诊终结时间不一致", "InpatientDischarge");
                default -> throw failure("DATA_QUALITY_RULE_SCANNER_UNAVAILABLE", 422,
                        "No allowlisted fact scanner is registered for rule " + ruleCode);
            };
        }

        String aggregateSql() {
            return "select count(*) as total_count, count(*) filter (where " + passPredicate
                    + ") as passed_count from " + fromClause + " where " + scopePredicate;
        }

        String findingInsertSql() {
            return "insert into data_quality_finding(tenant_id, data_quality_finding_id, data_quality_scan_id, "
                    + "data_quality_rule_id, target_entity_id, reason_code, reason_detail, severity, status) "
                    + "select :tenant, gen_random_uuid(), :scan, :rule, " + entityId
                    + ", :reason_code, :reason_detail, :severity, 'OPEN' from " + fromClause
                    + " where " + scopePredicate + " and not (" + passPredicate + ")";
        }
    }
}
