package org.haoemr.quality;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.haoemr.contracts.QualityGovernanceAgentProposalRequestWire;
import org.haoemr.contracts.QualityGovernanceAgentProposalWire;
import org.haoemr.contracts.QualityGovernanceRecordCreateRequestWire;
import org.haoemr.contracts.QualityGovernanceRecordUpdateRequestWire;
import org.haoemr.contracts.QualityGovernanceRecordVoidRequestWire;
import org.haoemr.contracts.QualityGovernanceRecordWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
final class QualityGovernanceService {
    private static final List<String> MODULES = List.of(
            "QUALITY_CENTER", "DEPARTMENT_QC", "QUALITY_RATING", "INFECTION_EVENTS", "CREDENTIALS",
            "ARCHIVE_ASSET");
    private static final List<String> AUTHOR_ROLES = List.of(
            "SYSTEM_ADMIN", "CLINICAL_ADMIN", "CONFIG_AUTHOR", "CONFIG_APPROVER",
            "AUTHORIZATION_ADMIN", "QUALITY_MANAGER", "MEDICAL_AFFAIRS", "INFECTION_CONTROL",
            "MEDICAL_RECORDS");
    private static final List<String> ARCHIVE_AUTHOR_ROLES = List.of("MEDICAL_RECORDS", "CLINICAL_ADMIN");
    private static final List<String> TERMINAL_STATES = List.of("VERIFIED", "CLOSED");

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    QualityGovernanceService(JdbcClient jdbc, TransactionTemplate transactions, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    List<QualityGovernanceRecordWire> listRecords(
            ClinicalIdentity identity, UUID organizationId, UUID facilityId,
            String moduleCode, UUID parentId, String recordKind) {
        String module = module(moduleCode);
        assertParent(identity.tenantId(), facilityId, module, parentId);
        String kind = recordKind == null || recordKind.isBlank() ? null : kind(recordKind);
        String sql = """
                select quality_governance_record_id, organization_id, facility_id, module_code,
                  parent_resource_id, hierarchy_level, record_kind, record_code, title, owner,
                  status, due_at, description, evidence_uri, evidence_hash, payload::text,
                  row_version, created_by, updated_by, created_at, updated_at, voided_at, void_reason
                from quality_governance_record
                where tenant_id = :tenant and organization_id = :organization and facility_id = :facility
                  and module_code = :module and parent_resource_id = :parent and voided_at is null
                """ + (kind == null ? "" : " and record_kind = :kind")
                + " order by hierarchy_level, due_at nulls last, updated_at desc, quality_governance_record_id";
        JdbcClient.StatementSpec query = jdbc.sql(sql).param("tenant", identity.tenantId())
                .param("organization", organizationId).param("facility", facilityId)
                .param("module", module).param("parent", parentId);
        if (kind != null) query = query.param("kind", kind);
        return query.query((rs, row) -> record(
                rs.getObject("quality_governance_record_id", UUID.class),
                rs.getObject("organization_id", UUID.class), rs.getObject("facility_id", UUID.class),
                rs.getString("module_code"), rs.getObject("parent_resource_id", UUID.class),
                rs.getInt("hierarchy_level"), rs.getString("record_kind"), rs.getString("record_code"),
                rs.getString("title"), rs.getString("owner"), rs.getString("status"),
                rs.getObject("due_at", OffsetDateTime.class), rs.getString("description"),
                rs.getString("evidence_uri"), rs.getString("evidence_hash"), rs.getString("payload"),
                rs.getLong("row_version"), rs.getObject("created_by", UUID.class),
                rs.getObject("updated_by", UUID.class), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class), rs.getObject("voided_at", OffsetDateTime.class),
                rs.getString("void_reason"))).list();
    }

    QualityGovernanceRecordWire createRecord(
            ClinicalIdentity identity, String moduleCode, UUID parentId, String idempotencyKey,
            QualityGovernanceRecordCreateRequestWire command) {
        String module = module(moduleCode);
        String kind = kind(command.recordKind().name());
        String status = status(command.status().name(), kind);
        validateRecord(command.recordCode(), command.title(), command.owner(), command.description(),
                command.evidenceUri(), command.evidenceHash(), kind);
        requireAuthor(identity, module);
        String hash = sha256(module + "|" + parentId + "|" + json(command));
        return transactions.execute(tx -> {
            beginCommand(identity, "QUALITY_GOVERNANCE_RECORD_CREATE", idempotencyKey, hash);
            assertParent(identity.tenantId(), command.facilityId(), module, parentId);
            UUID recordId = UUID.randomUUID();
            int level = level(kind);
            jdbc.sql("""
                    insert into quality_governance_record(
                      tenant_id, quality_governance_record_id, organization_id, facility_id,
                      module_code, parent_resource_id, hierarchy_level, record_kind, record_code,
                      title, owner, status, due_at, description, evidence_uri, evidence_hash, payload,
                      created_by, updated_by)
                    values (:tenant, :record, :organization, :facility, :module, :parent, :level,
                      :kind, :code, :title, :owner, :state, :due, :description, :uri, :evidence_hash,
                      cast(:payload as jsonb), :actor, :actor)
                    """).param("tenant", identity.tenantId()).param("record", recordId)
                    .param("organization", command.organizationId()).param("facility", command.facilityId())
                    .param("module", module).param("parent", parentId).param("level", level)
                    .param("kind", kind).param("code", normalizedCode(command.recordCode()))
                    .param("title", text(command.title(), 2, 256, "title"))
                    .param("owner", text(command.owner(), 2, 128, "owner")).param("state", status)
                    .param("due", timestamp(command.dueAt())).param("description", text(command.description(), 4, 2000, "description"))
                    .param("uri", nullable(command.evidenceUri())).param("evidence_hash", evidenceHash(command.evidenceHash()))
                    .param("payload", json(command.payload() == null ? Map.of() : command.payload()))
                    .param("actor", identity.userId()).update();
            appendEvidence(identity, recordId, 1, "QUALITY_GOVERNANCE_RECORD_CREATED", module, parentId);
            completeCommand(identity, "QUALITY_GOVERNANCE_RECORD_CREATE", idempotencyKey, recordId, 201);
            return loadRecord(identity.tenantId(), module, parentId, recordId);
        });
    }

    QualityGovernanceRecordWire updateRecord(
            ClinicalIdentity identity, String moduleCode, UUID parentId, UUID recordId,
            String idempotencyKey, QualityGovernanceRecordUpdateRequestWire command) {
        String module = module(moduleCode);
        requireAuthor(identity, module);
        if (command.expectedVersion() == null || command.expectedVersion() < 1) throw versionConflict();
        RecordHead head = head(identity.tenantId(), module, parentId, recordId, true);
        String nextStatus = status(command.status().name(), head.kind());
        validateTransition(head.status(), nextStatus);
        validateRecord(head.code(), command.title(), command.owner(), command.description(),
                command.evidenceUri(), command.evidenceHash(), head.kind());
        String hash = sha256(module + "|" + parentId + "|" + recordId + "|" + json(command));
        return transactions.execute(tx -> {
            beginCommand(identity, "QUALITY_GOVERNANCE_RECORD_UPDATE", idempotencyKey, hash);
            assertParent(identity.tenantId(), command.facilityId(), module, parentId);
            RecordHead current = head(identity.tenantId(), module, parentId, recordId, true);
            if (current.version() != command.expectedVersion()) throw versionConflict();
            int updated = jdbc.sql("""
                    update quality_governance_record set title = :title, owner = :owner, status = :state,
                      due_at = :due, description = :description, evidence_uri = :uri,
                      evidence_hash = :evidence_hash, payload = cast(:payload as jsonb),
                      updated_by = :actor, updated_at = now(), row_version = row_version + 1
                    where tenant_id = :tenant and quality_governance_record_id = :record
                      and module_code = :module and parent_resource_id = :parent
                      and organization_id = :organization and facility_id = :facility
                      and row_version = :version and voided_at is null
                    """).param("title", text(command.title(), 2, 256, "title"))
                    .param("owner", text(command.owner(), 2, 128, "owner")).param("state", nextStatus)
                    .param("due", timestamp(command.dueAt())).param("description", text(command.description(), 4, 2000, "description"))
                    .param("uri", nullable(command.evidenceUri())).param("evidence_hash", evidenceHash(command.evidenceHash()))
                    .param("payload", json(command.payload() == null ? Map.of() : command.payload()))
                    .param("actor", identity.userId()).param("tenant", identity.tenantId()).param("record", recordId)
                    .param("module", module).param("parent", parentId).param("organization", command.organizationId())
                    .param("facility", command.facilityId()).param("version", command.expectedVersion()).update();
            if (updated != 1) throw versionConflict();
            long version = command.expectedVersion() + 1;
            appendEvidence(identity, recordId, version, "QUALITY_GOVERNANCE_RECORD_UPDATED", module, parentId);
            completeCommand(identity, "QUALITY_GOVERNANCE_RECORD_UPDATE", idempotencyKey, recordId, 200);
            return loadRecord(identity.tenantId(), module, parentId, recordId);
        });
    }

    QualityGovernanceRecordWire voidRecord(
            ClinicalIdentity identity, String moduleCode, UUID parentId, UUID recordId,
            String idempotencyKey, QualityGovernanceRecordVoidRequestWire command) {
        String module = module(moduleCode);
        requireAuthor(identity, module);
        String reason = text(command.reason(), 8, 500, "reason");
        if (command.expectedVersion() == null || command.expectedVersion() < 1) throw versionConflict();
        String hash = sha256(module + "|" + parentId + "|" + recordId + "|" + command.expectedVersion() + "|" + reason);
        return transactions.execute(tx -> {
            beginCommand(identity, "QUALITY_GOVERNANCE_RECORD_VOID", idempotencyKey, hash);
            assertParent(identity.tenantId(), command.facilityId(), module, parentId);
            int updated = jdbc.sql("""
                    update quality_governance_record set voided_at = now(), voided_by = :actor,
                      void_reason = :reason, updated_by = :actor, updated_at = now(), row_version = row_version + 1
                    where tenant_id = :tenant and quality_governance_record_id = :record
                      and module_code = :module and parent_resource_id = :parent
                      and organization_id = :organization and facility_id = :facility
                      and row_version = :version and voided_at is null
                    """).param("actor", identity.userId()).param("reason", reason)
                    .param("tenant", identity.tenantId()).param("record", recordId).param("module", module)
                    .param("parent", parentId).param("organization", command.organizationId())
                    .param("facility", command.facilityId()).param("version", command.expectedVersion()).update();
            if (updated != 1) throw versionConflict();
            long version = command.expectedVersion() + 1;
            appendEvidence(identity, recordId, version, "QUALITY_GOVERNANCE_RECORD_VOIDED", module, parentId);
            completeCommand(identity, "QUALITY_GOVERNANCE_RECORD_VOID", idempotencyKey, recordId, 200);
            return loadRecord(identity.tenantId(), module, parentId, recordId);
        });
    }

    List<QualityGovernanceAgentProposalWire> listAgentProposals(
            ClinicalIdentity identity, UUID organizationId, UUID facilityId, String moduleCode, UUID parentId) {
        String module = module(moduleCode);
        assertParent(identity.tenantId(), facilityId, module, parentId);
        return jdbc.sql("""
                select quality_governance_agent_proposal_id, organization_id, facility_id, module_code,
                  parent_resource_id, evidence_watermark, risk_level, summary, prioritized_actions::text,
                  model_policy, human_review_state, generated_by, created_at
                from quality_governance_agent_proposal
                where tenant_id = :tenant and organization_id = :organization and facility_id = :facility
                  and module_code = :module and parent_resource_id = :parent
                order by created_at desc, quality_governance_agent_proposal_id desc limit 20
                """).param("tenant", identity.tenantId()).param("organization", organizationId)
                .param("facility", facilityId).param("module", module).param("parent", parentId)
                .query((rs, row) -> proposal(
                        rs.getObject("quality_governance_agent_proposal_id", UUID.class),
                        rs.getObject("organization_id", UUID.class), rs.getObject("facility_id", UUID.class),
                        rs.getString("module_code"), rs.getObject("parent_resource_id", UUID.class),
                        rs.getString("evidence_watermark"), rs.getString("risk_level"), rs.getString("summary"),
                        rs.getString("prioritized_actions"), rs.getString("model_policy"),
                        rs.getString("human_review_state"), rs.getObject("generated_by", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class))).list();
    }

    QualityGovernanceAgentProposalWire createAgentProposal(
            ClinicalIdentity identity, String moduleCode, UUID parentId, String idempotencyKey,
            QualityGovernanceAgentProposalRequestWire command) {
        String module = module(moduleCode);
        requireAuthor(identity, module);
        return transactions.execute(tx -> {
            beginCommand(identity, "QUALITY_GOVERNANCE_AGENT_PROPOSAL", idempotencyKey,
                    sha256(module + "|" + parentId + "|" + command.organizationId() + "|" + command.facilityId()));
            assertParent(identity.tenantId(), command.facilityId(), module, parentId);
            List<QualityGovernanceRecordWire> records = listRecords(
                    identity, command.organizationId(), command.facilityId(), module, parentId, null);
            String watermark = evidenceWatermark(module, parentId, records);
            long overdue = records.stream().filter(item -> !TERMINAL_STATES.contains(item.status().name())
                    && item.dueAt() != null && item.dueAt().isBefore(Instant.now())).count();
            long rejected = records.stream().filter(item -> item.status().name().equals("REJECTED")).count();
            long missingEvidence = records.stream().filter(item -> item.recordKind().name().equals("EVIDENCE")
                    && !item.status().name().equals("VERIFIED")).count();
            String risk = overdue > 0 && missingEvidence > 0 ? "CRITICAL"
                    : overdue + rejected + missingEvidence > 0 ? "HIGH" : records.isEmpty() ? "MEDIUM" : "LOW";
            List<String> actions = recommendations(module, records.size(), overdue, rejected, missingEvidence);
            String summary = "已按当前机构、院区和父业务对象读取 " + records.size()
                    + " 条有效质量证据；逾期 " + overdue + " 条，驳回 " + rejected
                    + " 条，待验证证据 " + missingEvidence + " 条。本结果仅为候选建议，不修改业务结论。";
            UUID proposalId = UUID.randomUUID();
            jdbc.sql("""
                    insert into quality_governance_agent_proposal(
                      tenant_id, quality_governance_agent_proposal_id, organization_id, facility_id,
                      module_code, parent_resource_id, evidence_watermark, risk_level, summary,
                      prioritized_actions, generated_by)
                    values (:tenant, :proposal, :organization, :facility, :module, :parent,
                      :watermark, :risk, :summary, cast(:actions as jsonb), :actor)
                    """).param("tenant", identity.tenantId()).param("proposal", proposalId)
                    .param("organization", command.organizationId()).param("facility", command.facilityId())
                    .param("module", module).param("parent", parentId).param("watermark", watermark)
                    .param("risk", risk).param("summary", summary).param("actions", json(actions))
                    .param("actor", identity.userId()).update();
            appendEvidence(identity, proposalId, 1, "QUALITY_GOVERNANCE_AGENT_PROPOSAL_CREATED", module, parentId);
            completeCommand(identity, "QUALITY_GOVERNANCE_AGENT_PROPOSAL", idempotencyKey, proposalId, 201);
            return listAgentProposals(identity, command.organizationId(), command.facilityId(), module, parentId)
                    .stream().filter(item -> item.qualityGovernanceAgentProposalId().equals(proposalId)).findFirst()
                    .orElseThrow(() -> failure("QUALITY_GOVERNANCE_AGENT_PROPOSAL_NOT_FOUND", 500, "候选建议写入后无法读取"));
        });
    }

    private void assertParent(UUID tenantId, UUID facilityId, String module, UUID parentId) {
        long found = switch (module) {
            case "QUALITY_CENTER" -> configParent(tenantId, parentId, "QUALITY_INITIATIVE");
            case "DEPARTMENT_QC" -> configParent(tenantId, parentId, "DEPARTMENT_QC_CASE");
            case "QUALITY_RATING" -> jdbc.sql("""
                    select count(*) from department_support_assessment
                    where tenant_id = :tenant and facility_id = :facility
                      and department_support_assessment_id = :parent
                    """).param("tenant", tenantId).param("facility", facilityId).param("parent", parentId)
                    .query(Long.class).single();
            case "INFECTION_EVENTS" -> jdbc.sql("""
                    select count(*) from infection_monitoring_event
                    where tenant_id = :tenant and facility_id = :facility and infection_event_id = :parent
                    """).param("tenant", tenantId).param("facility", facilityId).param("parent", parentId)
                    .query(Long.class).single();
            case "CREDENTIALS" -> jdbc.sql("""
                    select count(*) from practitioner_credential where tenant_id = :tenant and credential_id = :parent
                    """).param("tenant", tenantId).param("parent", parentId).query(Long.class).single();
            case "ARCHIVE_ASSET" -> jdbc.sql("""
                    select count(*) from medical_record_asset
                    where tenant_id = :tenant and facility_id = :facility
                      and medical_record_asset_id = :parent
                    """).param("tenant", tenantId).param("facility", facilityId).param("parent", parentId)
                    .query(Long.class).single();
            default -> 0L;
        };
        if (found != 1) throw denied();
    }

    private long configParent(UUID tenantId, UUID parentId, String configType) {
        return jdbc.sql("""
                select count(*) from config_item
                where tenant_id = :tenant and config_id = :parent and config_type = :type and status <> 'ARCHIVED'
                """).param("tenant", tenantId).param("parent", parentId).param("type", configType)
                .query(Long.class).single();
    }

    private void requireAuthor(ClinicalIdentity identity, String module) {
        if (identity.roleAssignmentIds().isEmpty()) throw forbidden();
        long allowed = jdbc.sql("""
                select count(*) from role_assignment where tenant_id = :tenant and user_id = :user
                  and role_assignment_id in (:assignments) and role_code in (:roles)
                  and status = 'ACTIVE' and valid_from <= now()
                  and (valid_until is null or valid_until > now())
                """).param("tenant", identity.tenantId()).param("user", identity.userId())
                .param("assignments", identity.roleAssignmentIds())
                .param("roles", "ARCHIVE_ASSET".equals(module) ? ARCHIVE_AUTHOR_ROLES : AUTHOR_ROLES)
                .query(Long.class).single();
        if (allowed == 0) throw forbidden();
    }

    private RecordHead head(UUID tenantId, String module, UUID parentId, UUID recordId, boolean activeOnly) {
        String sql = """
                select record_kind, record_code, status, row_version from quality_governance_record
                where tenant_id = :tenant and module_code = :module and parent_resource_id = :parent
                  and quality_governance_record_id = :record
                """ + (activeOnly ? " and voided_at is null" : "");
        return jdbc.sql(sql).param("tenant", tenantId).param("module", module).param("parent", parentId)
                .param("record", recordId).query((rs, row) -> new RecordHead(
                        rs.getString("record_kind"), rs.getString("record_code"),
                        rs.getString("status"), rs.getLong("row_version")))
                .optional().orElseThrow(QualityGovernanceService::denied);
    }

    private QualityGovernanceRecordWire loadRecord(UUID tenantId, String module, UUID parentId, UUID recordId) {
        return jdbc.sql("""
                select quality_governance_record_id, organization_id, facility_id, module_code,
                  parent_resource_id, hierarchy_level, record_kind, record_code, title, owner,
                  status, due_at, description, evidence_uri, evidence_hash, payload::text,
                  row_version, created_by, updated_by, created_at, updated_at, voided_at, void_reason
                from quality_governance_record where tenant_id = :tenant and module_code = :module
                  and parent_resource_id = :parent and quality_governance_record_id = :record
                """).param("tenant", tenantId).param("module", module).param("parent", parentId)
                .param("record", recordId).query((rs, row) -> record(
                        rs.getObject("quality_governance_record_id", UUID.class),
                        rs.getObject("organization_id", UUID.class), rs.getObject("facility_id", UUID.class),
                        rs.getString("module_code"), rs.getObject("parent_resource_id", UUID.class),
                        rs.getInt("hierarchy_level"), rs.getString("record_kind"), rs.getString("record_code"),
                        rs.getString("title"), rs.getString("owner"), rs.getString("status"),
                        rs.getObject("due_at", OffsetDateTime.class), rs.getString("description"),
                        rs.getString("evidence_uri"), rs.getString("evidence_hash"), rs.getString("payload"),
                        rs.getLong("row_version"), rs.getObject("created_by", UUID.class),
                        rs.getObject("updated_by", UUID.class), rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class), rs.getObject("voided_at", OffsetDateTime.class),
                        rs.getString("void_reason"))).optional().orElseThrow(QualityGovernanceService::denied);
    }

    private QualityGovernanceRecordWire record(
            UUID id, UUID organizationId, UUID facilityId, String module, UUID parentId,
            int level, String kind, String code, String title, String owner, String state,
            OffsetDateTime dueAt, String description, String evidenceUri, String evidenceHash,
            String payload, long version, UUID createdBy, UUID updatedBy, OffsetDateTime createdAt,
            OffsetDateTime updatedAt, OffsetDateTime voidedAt, String voidReason) {
        return new QualityGovernanceRecordWire(id, organizationId, facilityId,
                QualityGovernanceRecordWire.ModuleCodeValue.valueOf(module), parentId, level,
                QualityGovernanceRecordWire.RecordKindValue.valueOf(kind), code, title, owner,
                QualityGovernanceRecordWire.StatusValue.valueOf(state), instant(dueAt), description,
                evidenceUri, evidenceHash, map(payload), version, createdBy, updatedBy,
                instant(createdAt), instant(updatedAt), instant(voidedAt), voidReason);
    }

    private QualityGovernanceAgentProposalWire proposal(
            UUID id, UUID organizationId, UUID facilityId, String module, UUID parentId,
            String watermark, String risk, String summary, String actions, String modelPolicy,
            String reviewState, UUID generatedBy, OffsetDateTime createdAt) {
        return new QualityGovernanceAgentProposalWire(id, organizationId, facilityId,
                QualityGovernanceAgentProposalWire.ModuleCodeValue.valueOf(module), parentId, watermark,
                QualityGovernanceAgentProposalWire.RiskLevelValue.valueOf(risk), summary, strings(actions),
                modelPolicy, QualityGovernanceAgentProposalWire.HumanReviewStateValue.valueOf(reviewState),
                generatedBy, createdAt.toInstant());
    }

    private List<String> recommendations(String module, int count, long overdue, long rejected, long missingEvidence) {
        List<String> values = new ArrayList<>();
        if (count == 0) values.add("先由有权用户建立整改动作、证据与独立复核记录，Agent 不代替建单。");
        if (overdue > 0) values.add("按《医疗质量管理办法》和院级时限对逾期项升级，保留责任人、时间和处置证据。");
        if (missingEvidence > 0) values.add("补齐可验证的证据 URI 或 SHA-256 指纹，不用自由文本声称“已完成”。");
        if (rejected > 0) values.add("将驳回原因回传责任队列，重新整改后由不同角色复核。");
        values.add(switch (module) {
            case "QUALITY_CENTER", "DEPARTMENT_QC" -> "将问题映射到医疗质量安全核心制度、责任科室和原始业务对象，禁止在质控页面直接改临床原文。";
            case "QUALITY_RATING" -> "按电子病历系统应用水平 0–8 级、39 个评价项和功能、应用范围、数据质量、实效四维留存取证快照。";
            case "INFECTION_EVENTS" -> "区分院内感染与聚集性事件，核对 2 小时/24 小时上报时限、接收回执、更正和重复报卡。";
            case "CREDENTIALS" -> "按人员、岗位、执业范围、处方权、手术分级和麻精药品权限执行实时交集校验，不用静态角色代替临床授权。";
            case "ARCHIVE_ASSET" -> "核对病案保存年限、病毒扫描、CDA 校验证据、原件哈希、WORM 对象锁和复印调阅授权；任一门禁未通过时不得宣称已归档。";
            default -> throw invalid("未知质量治理模块");
        });
        values.add("候选建议必须由有权用户人工复核；确定性门禁、归档、上报和授权状态仍以后端业务规则为准。");
        return List.copyOf(values);
    }

    private String evidenceWatermark(String module, UUID parentId, List<QualityGovernanceRecordWire> records) {
        StringBuilder value = new StringBuilder(module).append('|').append(parentId);
        records.stream().sorted((a, b) -> a.qualityGovernanceRecordId().compareTo(b.qualityGovernanceRecordId()))
                .forEach(item -> value.append('|').append(item.qualityGovernanceRecordId())
                        .append(':').append(item.rowVersion()).append(':').append(item.status())
                        .append(':').append(item.evidenceHash()));
        return sha256(value.toString());
    }

    private void validateRecord(String code, String title, String owner, String description,
                                String uri, String hash, String kind) {
        normalizedCode(code);
        text(title, 2, 256, "title");
        text(owner, 2, 128, "owner");
        text(description, 4, 2000, "description");
        String normalizedUri = nullable(uri);
        String normalizedHash = evidenceHash(hash);
        if ("EVIDENCE".equals(kind) && normalizedUri == null && normalizedHash == null) {
            throw invalid("证据记录必须包含可验证 URI 或 SHA-256 指纹");
        }
        if (normalizedUri != null && !(normalizedUri.startsWith("https://") || normalizedUri.startsWith("urn:")
                || normalizedUri.startsWith("archive://") || normalizedUri.startsWith("document://")
                || normalizedUri.startsWith("config://"))) {
            throw invalid("证据 URI 只允许 https、urn、archive、document 或 config 协议");
        }
    }

    private void validateTransition(String current, String next) {
        if (current.equals(next)) return;
        Map<String, List<String>> allowed = Map.of(
                "OPEN", List.of("IN_PROGRESS", "READY", "REJECTED", "CLOSED"),
                "IN_PROGRESS", List.of("READY", "REJECTED", "CLOSED"),
                "READY", List.of("IN_PROGRESS", "VERIFIED", "REJECTED", "CLOSED"),
                "REJECTED", List.of("IN_PROGRESS", "READY", "CLOSED"),
                "VERIFIED", List.of("REJECTED", "CLOSED"),
                "CLOSED", List.of());
        if (!allowed.getOrDefault(current, List.of()).contains(next)) {
            throw failure("QUALITY_GOVERNANCE_STATE_CONFLICT", 409,
                    "不允许从 " + current + " 转换为 " + next);
        }
    }

    private String status(String value, String kind) {
        if (!List.of("OPEN", "IN_PROGRESS", "READY", "VERIFIED", "REJECTED", "CLOSED").contains(value)) {
            throw invalid("流程状态无效");
        }
        if ("EVIDENCE".equals(kind) && !List.of("READY", "VERIFIED", "REJECTED", "CLOSED").contains(value)) {
            throw invalid("证据记录状态必须为待验证、已验证、已驳回或已关闭");
        }
        if ("REVIEW".equals(kind) && !List.of("READY", "VERIFIED", "REJECTED", "CLOSED").contains(value)) {
            throw invalid("复核决定状态无效");
        }
        return value;
    }

    private static String module(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if (!MODULES.contains(normalized)) throw invalid("质量治理模块无效");
        return normalized;
    }

    private static String kind(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if (!List.of("ACTION", "EVIDENCE", "REVIEW").contains(normalized)) throw invalid("纵深记录类型无效");
        return normalized;
    }

    private static int level(String kind) {
        return switch (kind) { case "ACTION" -> 5; case "EVIDENCE" -> 6; case "REVIEW" -> 7; default -> throw invalid("层级无效"); };
    }

    private static String normalizedCode(String value) {
        String code = text(value, 2, 96, "record_code").toUpperCase();
        if (!code.matches("[A-Z0-9][A-Z0-9._-]{1,95}")) throw invalid("编码只允许大写字母、数字、点、下划线和连字符");
        return code;
    }

    private static String evidenceHash(String value) {
        String normalized = nullable(value);
        if (normalized != null && !normalized.matches("[0-9a-f]{64}")) throw invalid("证据指纹必须为 64 位小写 SHA-256");
        return normalized;
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String text(String value, int min, int max, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() < min || normalized.length() > max) throw invalid(field + " 长度必须在 " + min + " 至 " + max + " 之间");
        return normalized;
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) throw invalid("Idempotency-Key 无效");
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) throw failure("IDEMPOTENCY_REPLAY", 409, "该命令幂等键已使用");
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID resourceId, int status) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = :status,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("status", status).param("resource", resourceId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID resourceId, long version, String action, String module, UUID parentId) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|" + resourceId
                + "|" + version + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code, resource_type,
                  resource_id, trace_id, previous_hash, event_hash, details)
                values (:tenant, :audit, now(), :actor, :action, 'QUALITY_GOVERNANCE', :resource,
                  :trace, :previous, :hash, jsonb_build_object('module_code', :module, 'parent_resource_id', :parent))
                """).param("tenant", identity.tenantId()).param("audit", auditId).param("actor", identity.userId())
                .param("action", action).param("resource", resourceId).param("trace", trace)
                .param("previous", previousHash).param("hash", eventHash).param("module", module)
                .param("parent", parentId).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'QUALITY_GOVERNANCE', :resource, :version, :event_type, 1,
                  jsonb_build_object('module_code', :module, 'parent_resource_id', :parent))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("resource", resourceId).param("version", version).param("event_type", action)
                .param("module", module).param("parent", parentId).update();
    }

    private Map<String, Object> map(String json) {
        try {
            @SuppressWarnings("unchecked") Map<String, Object> value = objectMapper.readValue(json, Map.class);
            return value;
        } catch (Exception failure) { throw new IllegalStateException("质量治理载荷无法解析", failure); }
    }

    private List<String> strings(String json) {
        try {
            List<String> values = new ArrayList<>();
            objectMapper.readTree(json).forEach(node -> values.add(node.stringValue()));
            return List.copyOf(values);
        } catch (Exception failure) { throw new IllegalStateException("候选建议无法解析", failure); }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("质量治理载荷无法序列化", failure); }
    }

    private static OffsetDateTime timestamp(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }

    private static QualityGovernanceException invalid(String message) {
        return failure("QUALITY_GOVERNANCE_REQUEST_INVALID", 400, message);
    }
    private static QualityGovernanceException denied() {
        return failure("CONTEXT_NOT_PERMITTED", 403, "当前上下文不允许访问该质量治理对象");
    }
    private static QualityGovernanceException forbidden() {
        return failure("QUALITY_GOVERNANCE_AUTHOR_REQUIRED", 403, "当前岗位无权变更质量治理证据");
    }
    private static QualityGovernanceException versionConflict() {
        return failure("QUALITY_GOVERNANCE_VERSION_CONFLICT", 409, "记录已被其他用户更新，请刷新后重试");
    }
    private static QualityGovernanceException failure(String code, int status, String message) {
        return new QualityGovernanceException(code, status, message);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) { throw new IllegalStateException("SHA-256 unavailable", failure); }
    }

    private record RecordHead(String kind, String code, String status, long version) {}
}
