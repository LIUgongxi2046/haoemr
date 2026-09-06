package org.haoemr.administration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
final class AdministrationRuntimeService {
    private static final Set<String> ADMIN_ROLES = Set.of(
            "SYSTEM_ADMIN", "CLINICAL_ADMIN", "SECURITY_AUDITOR", "AUTHORIZATION_ADMIN");
    private static final Set<String> JOB_KINDS = Set.of(
            "ADMIN_GOVERNANCE_AGENT", "AUDIT_CHAIN_VERIFY", "ROLE_CONFLICT_REVIEW",
            "CREDENTIAL_EXPIRY_REVIEW", "MASTER_DATA_RECONCILIATION", "NOTIFICATION_RECONCILIATION");

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    AdministrationRuntimeService(JdbcClient jdbc, TransactionTemplate transactions, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    List<JobRunWire> listRuns(ClinicalIdentity identity, UUID configId) {
        requireAdministrator(identity);
        String sql = """
                select run_id, config_id, job_kind, status, requested_by, attempt,
                  processed_count, succeeded_count, failed_count, result::text, error_code,
                  error_message, started_at, finished_at, row_version, created_at, updated_at
                from administration_job_run
                where tenant_id = :tenant
                """ + (configId == null ? "" : " and config_id = :config")
                + " order by created_at desc, run_id desc limit 500";
        JdbcClient.StatementSpec statement = jdbc.sql(sql).param("tenant", identity.tenantId());
        if (configId != null) statement = statement.param("config", configId);
        return statement.query((rs, row) -> jobRun(
                rs.getObject("run_id", UUID.class), rs.getObject("config_id", UUID.class),
                rs.getString("job_kind"), rs.getString("status"), rs.getObject("requested_by", UUID.class),
                rs.getInt("attempt"), rs.getInt("processed_count"), rs.getInt("succeeded_count"),
                rs.getInt("failed_count"), jsonMap(rs.getString("result")), rs.getString("error_code"),
                rs.getString("error_message"), instant(rs.getObject("started_at", OffsetDateTime.class)),
                instant(rs.getObject("finished_at", OffsetDateTime.class)), rs.getLong("row_version"),
                instant(rs.getObject("created_at", OffsetDateTime.class)),
                instant(rs.getObject("updated_at", OffsetDateTime.class)))).list();
    }

    List<GovernanceFindingWire> listFindings(ClinicalIdentity identity, UUID runId) {
        requireAdministrator(identity);
        return jdbc.sql("""
                select finding_id, run_id, finding_type, severity, resource_type, resource_id,
                  summary, recommendation, evidence::text, status, resolved_by, resolved_at,
                  row_version, created_at, updated_at
                from administration_governance_finding
                where tenant_id = :tenant and run_id = :run
                order by case severity when 'CRITICAL' then 0 when 'HIGH' then 1 when 'MEDIUM' then 2 else 3 end,
                  created_at, finding_id
                """).param("tenant", identity.tenantId()).param("run", runId)
                .query((rs, row) -> new GovernanceFindingWire(
                        rs.getObject("finding_id", UUID.class), rs.getObject("run_id", UUID.class),
                        rs.getString("finding_type"), rs.getString("severity"), rs.getString("resource_type"),
                        rs.getObject("resource_id", UUID.class), rs.getString("summary"),
                        rs.getString("recommendation"), jsonMap(rs.getString("evidence")), rs.getString("status"),
                        rs.getObject("resolved_by", UUID.class), instant(rs.getObject("resolved_at", OffsetDateTime.class)),
                        rs.getLong("row_version"), instant(rs.getObject("created_at", OffsetDateTime.class)),
                        instant(rs.getObject("updated_at", OffsetDateTime.class)))).list();
    }

    JobRunWire start(ClinicalIdentity identity, UUID configId, String idempotencyKey) {
        requireAdministrator(identity);
        if (idempotencyKey == null || idempotencyKey.isBlank()) invalid("Idempotency-Key 不能为空");
        return transactions.execute(status -> {
            JobDefinition definition = jdbc.sql("""
                    select config_id, payload ->> 'job_kind' as job_kind
                    from config_item
                    where tenant_id = :tenant and config_id = :config and config_type = 'JOB' and status = 'ACTIVE'
                    for update
                    """).param("tenant", identity.tenantId()).param("config", configId)
                    .query((rs, row) -> new JobDefinition(
                            rs.getObject("config_id", UUID.class), rs.getString("job_kind")))
                    .optional().orElseThrow(() -> new AdministrationRuntimeException(
                            "ADMIN_JOB_NOT_ACTIVE", 409, "只有已校验、独立审批并发布的任务才能执行"));
            if (!JOB_KINDS.contains(definition.jobKind())) {
                throw new AdministrationRuntimeException(
                        "ADMIN_JOB_KIND_UNSUPPORTED", 422, "不支持的任务类型：" + definition.jobKind());
            }
            UUID runId = UUID.randomUUID();
            int inserted = jdbc.sql("""
                    insert into administration_job_run(
                      tenant_id, run_id, config_id, job_kind, status, requested_by, idempotency_key)
                    values (:tenant, :run, :config, :kind, 'QUEUED', :actor, :key)
                    on conflict (tenant_id, idempotency_key) do nothing
                    """).param("tenant", identity.tenantId()).param("run", runId)
                    .param("config", configId).param("kind", definition.jobKind())
                    .param("actor", identity.userId()).param("key", idempotencyKey).update();
            if (inserted != 1) {
                return jdbc.sql("select run_id from administration_job_run where tenant_id = :tenant and idempotency_key = :key")
                        .param("tenant", identity.tenantId()).param("key", idempotencyKey).query(UUID.class)
                        .optional().map(id -> findRun(identity.tenantId(), id)).orElseThrow();
            }
            appendAudit(identity.tenantId(), identity.userId(), runId, "ADMIN_JOB_QUEUED");
            return findRun(identity.tenantId(), runId);
        });
    }

    JobRunWire cancel(ClinicalIdentity identity, UUID runId, long expectedVersion) {
        requireAdministrator(identity);
        int updated = jdbc.sql("""
                update administration_job_run set status = 'CANCELLED', finished_at = now(),
                  row_version = row_version + 1, updated_at = now()
                where tenant_id = :tenant and run_id = :run and status = 'QUEUED' and row_version = :version
                """).param("tenant", identity.tenantId()).param("run", runId)
                .param("version", expectedVersion).update();
        if (updated != 1) conflict("任务已开始、已结束或版本发生变化");
        appendAudit(identity.tenantId(), identity.userId(), runId, "ADMIN_JOB_CANCELLED");
        return findRun(identity.tenantId(), runId);
    }

    JobRunWire retry(ClinicalIdentity identity, UUID runId, String idempotencyKey) {
        requireAdministrator(identity);
        JobRunWire previous = findRun(identity.tenantId(), runId);
        if (!Set.of("FAILED", "PARTIAL", "CANCELLED").contains(previous.status())) {
            conflict("只有失败、部分成功或已取消的任务可以重试");
        }
        return start(identity, previous.configId(), idempotencyKey);
    }

    GovernanceFindingWire resolve(
            ClinicalIdentity identity, UUID findingId, long expectedVersion, String resolution) {
        requireAdministrator(identity);
        if (resolution == null || resolution.trim().length() < 8) invalid("处置说明至少 8 个字符");
        int updated = jdbc.sql("""
                update administration_governance_finding
                set status = 'RESOLVED', resolved_by = :actor, resolved_at = now(),
                  evidence = evidence || jsonb_build_object('resolution', :resolution),
                  row_version = row_version + 1, updated_at = now()
                where tenant_id = :tenant and finding_id = :finding and status <> 'RESOLVED'
                  and row_version = :version
                """).param("actor", identity.userId()).param("resolution", resolution.trim())
                .param("tenant", identity.tenantId()).param("finding", findingId)
                .param("version", expectedVersion).update();
        if (updated != 1) conflict("治理问题已处置或版本发生变化");
        appendAudit(identity.tenantId(), identity.userId(), findingId, "ADMIN_FINDING_RESOLVED");
        return findFinding(identity.tenantId(), findingId);
    }

    @Scheduled(fixedDelayString = "${haoemr.admin-jobs.poll-delay-ms:1000}")
    void executeQueued() {
        UUID runId = transactions.execute(status -> jdbc.sql("""
                select run_id from administration_job_run
                where status = 'QUEUED' order by created_at, run_id
                for update skip locked limit 1
                """).query(UUID.class).optional().map(id -> {
                    jdbc.sql("""
                            update administration_job_run set status = 'RUNNING', attempt = attempt + 1,
                              started_at = now(), row_version = row_version + 1, updated_at = now()
                            where run_id = :run and status = 'QUEUED'
                            """).param("run", id).update();
                    return id;
                }).orElse(null));
        if (runId == null) return;
        try {
            transactions.executeWithoutResult(status -> executeRun(runId));
        } catch (RuntimeException failure) {
            transactions.executeWithoutResult(status -> jdbc.sql("""
                    update administration_job_run set status = 'FAILED', error_code = 'ADMIN_JOB_EXECUTION_FAILED',
                      error_message = :message, finished_at = now(), row_version = row_version + 1, updated_at = now()
                    where run_id = :run and status = 'RUNNING'
                    """).param("message", safeMessage(failure)).param("run", runId).update());
        }
    }

    private void executeRun(UUID runId) {
        RunningJob job = jdbc.sql("""
                select tenant_id, run_id, job_kind, requested_by
                from administration_job_run where run_id = :run and status = 'RUNNING' for update
                """).param("run", runId).query((rs, row) -> new RunningJob(
                        rs.getObject("tenant_id", UUID.class), rs.getObject("run_id", UUID.class),
                        rs.getString("job_kind"), rs.getObject("requested_by", UUID.class))).single();
        List<FindingDraft> findings = new ArrayList<>();
        int checks = switch (job.jobKind()) {
            case "AUDIT_CHAIN_VERIFY" -> auditChain(job, findings);
            case "ROLE_CONFLICT_REVIEW" -> roleConflicts(job, findings);
            case "CREDENTIAL_EXPIRY_REVIEW" -> credentialExpiry(job, findings);
            case "MASTER_DATA_RECONCILIATION" -> masterData(job, findings);
            case "NOTIFICATION_RECONCILIATION" -> notificationReconciliation(job, findings);
            case "ADMIN_GOVERNANCE_AGENT" -> auditChain(job, findings) + roleConflicts(job, findings)
                    + credentialExpiry(job, findings) + masterData(job, findings)
                    + notificationReconciliation(job, findings) + workgroupCoverage(job, findings);
            default -> throw new IllegalStateException("Unsupported administration job " + job.jobKind());
        };
        for (FindingDraft finding : findings) insertFinding(job, finding);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("execution_mode", "DETERMINISTIC_RULE_ENGINE");
        result.put("checked_count", checks);
        result.put("finding_count", findings.size());
        result.put("completed_at", Instant.now().toString());
        jdbc.sql("""
                update administration_job_run set status = 'SUCCEEDED', processed_count = :processed,
                  succeeded_count = :succeeded, failed_count = 0, result = cast(:result as jsonb),
                  finished_at = now(), row_version = row_version + 1, updated_at = now()
                where tenant_id = :tenant and run_id = :run and status = 'RUNNING'
                """).param("processed", checks).param("succeeded", checks)
                .param("result", json(result)).param("tenant", job.tenantId()).param("run", runId).update();
        appendAudit(job.tenantId(), job.requestedBy(), runId, "ADMIN_JOB_SUCCEEDED");
    }

    private int auditChain(RunningJob job, List<FindingDraft> findings) {
        List<AuditLink> links = jdbc.sql("""
                select audit_event_id, previous_hash, event_hash from audit_event
                where tenant_id = :tenant order by occurred_at, audit_event_id
                """).param("tenant", job.tenantId()).query((rs, row) -> new AuditLink(
                        rs.getObject("audit_event_id", UUID.class), rs.getString("previous_hash"),
                        rs.getString("event_hash"))).list();
        String previous = null;
        for (AuditLink link : links) {
            if (!java.util.Objects.equals(previous, link.previousHash())) {
                findings.add(new FindingDraft("AUDIT_CHAIN_BREAK", "CRITICAL", "AUDIT_EVENT", link.id(),
                        "审计链前序摘要不连续", "立即冻结相关导出并由安全审计员核对数据库与备份证据",
                        Map.of("expected_previous_hash", previous == null ? "GENESIS" : previous,
                                "actual_previous_hash", link.previousHash() == null ? "GENESIS" : link.previousHash())));
            }
            previous = link.eventHash();
        }
        return links.size();
    }

    private int roleConflicts(RunningJob job, List<FindingDraft> findings) {
        List<RoleSet> people = jdbc.sql("""
                select person_id, array_agg(distinct role_code order by role_code) roles
                from role_assignment where tenant_id = :tenant and status = 'ACTIVE'
                  and valid_from <= now() and (valid_until is null or valid_until > now())
                group by person_id
                """).param("tenant", job.tenantId()).query((rs, row) -> new RoleSet(
                        rs.getObject("person_id", UUID.class), List.of((String[]) rs.getArray("roles").getArray()))).list();
        for (RoleSet person : people) {
            conflictFinding(person, "SYSTEM_ADMIN", "SECURITY_AUDITOR", "系统管理与安全审计职责未分离", findings);
            conflictFinding(person, "CONFIG_AUTHOR", "CONFIG_APPROVER", "配置创建与批准职责未分离", findings);
            conflictFinding(person, "AUTHORIZATION_ADMIN", "SECURITY_AUDITOR", "授权管理与安全审计职责未分离", findings);
        }
        return people.size();
    }

    private void conflictFinding(RoleSet person, String left, String right, String summary, List<FindingDraft> findings) {
        if (person.roles().contains(left) && person.roles().contains(right)) {
            findings.add(new FindingDraft("ROLE_SEPARATION_CONFLICT", "HIGH", "WORKFORCE_PERSON", person.personId(),
                    summary, "结束冲突任期，改由独立人员承担审批或审计职责", Map.of("roles", List.of(left, right))));
        }
    }

    private int credentialExpiry(RunningJob job, List<FindingDraft> findings) {
        return jdbc.sql("""
                select credential_id, credential_type, registration_number, valid_until
                from practitioner_credential where tenant_id = :tenant and status = 'ACTIVE'
                  and valid_until is not null and valid_until <= now() + interval '30 days'
                order by valid_until
                """).param("tenant", job.tenantId()).query((rs, row) -> {
                    UUID id = rs.getObject("credential_id", UUID.class);
                    OffsetDateTime until = rs.getObject("valid_until", OffsetDateTime.class);
                    findings.add(new FindingDraft("CREDENTIAL_EXPIRING", until.isBefore(OffsetDateTime.now()) ? "CRITICAL" : "HIGH",
                            "PRACTITIONER_CREDENTIAL", id, "执业资质已过期或将在 30 天内到期",
                            "资质管理岗核验官方注册信息，续期或停用对应临床权限",
                            Map.of("credential_type", rs.getString("credential_type"),
                                    "registration_number", rs.getString("registration_number"),
                                    "valid_until", until.toString())));
                    return id;
                }).list().size();
    }

    private int masterData(RunningJob job, List<FindingDraft> findings) {
        return jdbc.sql("""
                select record_id, code_system, local_code, mapping_status
                from master_data_record where tenant_id = :tenant and status = 'ACTIVE'
                  and mapping_status in ('UNMATCHED', 'CONFLICT', 'LOCAL_ONLY')
                order by code_system, local_code
                """).param("tenant", job.tenantId()).query((rs, row) -> {
                    UUID id = rs.getObject("record_id", UUID.class);
                    String mapping = rs.getString("mapping_status");
                    findings.add(new FindingDraft("MASTER_DATA_MAPPING_" + mapping,
                            "CONFLICT".equals(mapping) ? "HIGH" : "MEDIUM", "MASTER_DATA_RECORD", id,
                            "主数据编码尚未完成国家或行业标准映射",
                            "由主数据管理员核对权威来源、版本和本地编码映射后重新发布",
                            Map.of("code_system", rs.getString("code_system"), "local_code", rs.getString("local_code"),
                                    "mapping_status", mapping)));
                    return id;
                }).list().size();
    }

    private int notificationReconciliation(RunningJob job, List<FindingDraft> findings) {
        return jdbc.sql("""
                select event_id, event_type, created_at from outbox_event
                where tenant_id = :tenant and published_at is null and created_at < now() - interval '5 minutes'
                order by created_at limit 500
                """).param("tenant", job.tenantId()).query((rs, row) -> {
                    UUID id = rs.getObject("event_id", UUID.class);
                    findings.add(new FindingDraft("OUTBOX_DELIVERY_DELAY", "HIGH", "OUTBOX_EVENT", id,
                            "事务事件超过 5 分钟仍未发布", "检查消息出口、重试失败项并核对下游回执",
                            Map.of("event_type", rs.getString("event_type"),
                                    "created_at", rs.getObject("created_at", OffsetDateTime.class).toString())));
                    return id;
                }).list().size();
    }

    private int workgroupCoverage(RunningJob job, List<FindingDraft> findings) {
        return jdbc.sql("""
                select group_item.workgroup_id, group_item.workgroup_code
                from administration_workgroup group_item
                left join administration_workgroup_member member on member.tenant_id = group_item.tenant_id
                  and member.workgroup_id = group_item.workgroup_id and member.status = 'ACTIVE'
                  and member.effective_from <= now() and (member.effective_until is null or member.effective_until > now())
                where group_item.tenant_id = :tenant and group_item.status = 'ACTIVE'
                group by group_item.workgroup_id, group_item.workgroup_code having count(member.member_id) = 0
                """).param("tenant", job.tenantId()).query((rs, row) -> {
                    UUID id = rs.getObject("workgroup_id", UUID.class);
                    findings.add(new FindingDraft("WORKGROUP_WITHOUT_MEMBER", "MEDIUM", "ADMIN_WORKGROUP", id,
                            "有效工作组没有在效成员", "补充成员和职责，或停用不再承担业务流程的工作组",
                            Map.of("workgroup_code", rs.getString("workgroup_code"))));
                    return id;
                }).list().size();
    }

    private void insertFinding(RunningJob job, FindingDraft finding) {
        jdbc.sql("""
                insert into administration_governance_finding(
                  tenant_id, finding_id, run_id, finding_type, severity, resource_type, resource_id,
                  summary, recommendation, evidence)
                values (:tenant, :finding, :run, :type, :severity, :resource_type, :resource,
                  :summary, :recommendation, cast(:evidence as jsonb))
                """).param("tenant", job.tenantId()).param("finding", UUID.randomUUID())
                .param("run", job.runId()).param("type", finding.type()).param("severity", finding.severity())
                .param("resource_type", finding.resourceType()).param("resource", finding.resourceId())
                .param("summary", finding.summary()).param("recommendation", finding.recommendation())
                .param("evidence", json(finding.evidence())).update();
    }

    private JobRunWire findRun(UUID tenantId, UUID runId) {
        return jdbc.sql("""
                select run_id, config_id, job_kind, status, requested_by, attempt,
                  processed_count, succeeded_count, failed_count, result::text, error_code,
                  error_message, started_at, finished_at, row_version, created_at, updated_at
                from administration_job_run where tenant_id = :tenant and run_id = :run
                """).param("tenant", tenantId).param("run", runId).query((rs, row) -> jobRun(
                        rs.getObject("run_id", UUID.class), rs.getObject("config_id", UUID.class),
                        rs.getString("job_kind"), rs.getString("status"), rs.getObject("requested_by", UUID.class),
                        rs.getInt("attempt"), rs.getInt("processed_count"), rs.getInt("succeeded_count"),
                        rs.getInt("failed_count"), jsonMap(rs.getString("result")), rs.getString("error_code"),
                        rs.getString("error_message"), instant(rs.getObject("started_at", OffsetDateTime.class)),
                        instant(rs.getObject("finished_at", OffsetDateTime.class)), rs.getLong("row_version"),
                        instant(rs.getObject("created_at", OffsetDateTime.class)),
                        instant(rs.getObject("updated_at", OffsetDateTime.class))))
                .optional().orElseThrow(() -> new AdministrationRuntimeException(
                        "ADMIN_JOB_RUN_NOT_FOUND", 404, "任务执行记录不存在"));
    }

    private GovernanceFindingWire findFinding(UUID tenantId, UUID findingId) {
        return jdbc.sql("""
                select finding_id, run_id, finding_type, severity, resource_type, resource_id,
                  summary, recommendation, evidence::text, status, resolved_by, resolved_at,
                  row_version, created_at, updated_at
                from administration_governance_finding where tenant_id = :tenant and finding_id = :finding
                """).param("tenant", tenantId).param("finding", findingId).query((rs, row) -> new GovernanceFindingWire(
                        rs.getObject("finding_id", UUID.class), rs.getObject("run_id", UUID.class),
                        rs.getString("finding_type"), rs.getString("severity"), rs.getString("resource_type"),
                        rs.getObject("resource_id", UUID.class), rs.getString("summary"),
                        rs.getString("recommendation"), jsonMap(rs.getString("evidence")), rs.getString("status"),
                        rs.getObject("resolved_by", UUID.class), instant(rs.getObject("resolved_at", OffsetDateTime.class)),
                        rs.getLong("row_version"), instant(rs.getObject("created_at", OffsetDateTime.class)),
                        instant(rs.getObject("updated_at", OffsetDateTime.class))))
                .optional().orElseThrow(() -> new AdministrationRuntimeException(
                        "ADMIN_FINDING_NOT_FOUND", 404, "治理问题不存在"));
    }

    private JobRunWire jobRun(UUID runId, UUID configId, String kind, String status, UUID requestedBy,
            int attempt, int processed, int succeeded, int failed, Map<String, Object> result,
            String errorCode, String errorMessage, Instant startedAt, Instant finishedAt, long rowVersion,
            Instant createdAt, Instant updatedAt) {
        return new JobRunWire(runId, configId, kind, status, requestedBy, attempt, processed, succeeded,
                failed, result, errorCode, errorMessage, startedAt, finishedAt, rowVersion, createdAt, updatedAt);
    }

    private void requireAdministrator(ClinicalIdentity identity) {
        if (identity.roleAssignmentIds().isEmpty()) denied();
        long count = jdbc.sql("""
                select count(*) from role_assignment where tenant_id = :tenant and user_id = :user
                  and role_assignment_id in (:roles) and role_code in (:codes) and status = 'ACTIVE'
                  and valid_from <= now() and (valid_until is null or valid_until > now())
                """).param("tenant", identity.tenantId()).param("user", identity.userId())
                .param("roles", identity.roleAssignmentIds()).param("codes", ADMIN_ROLES).query(Long.class).single();
        if (count == 0) denied();
    }

    private void appendAudit(UUID tenantId, UUID userId, UUID resourceId, String action) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", tenantId).query(UUID.class).single();
        String previous = jdbc.sql("select event_hash from audit_event where tenant_id = :tenant order by occurred_at desc, audit_event_id desc limit 1")
                .param("tenant", tenantId).query(String.class).optional().orElse(null);
        UUID audit = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String hash = sha256(tenantId + "|" + audit + "|" + action + "|" + resourceId + "|" + trace
                + "|" + (previous == null ? "GENESIS" : previous));
        jdbc.sql("""
                insert into audit_event(tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'ADMINISTRATION_RUNTIME',
                  :resource, :trace, :previous, :hash)
                """).param("tenant", tenantId).param("audit", audit).param("actor", userId)
                .param("action", action).param("resource", resourceId).param("trace", trace)
                .param("previous", previous).param("hash", hash).update();
    }

    private Map<String, Object> jsonMap(String value) {
        try { return objectMapper.readValue(value, new TypeReference<>() {}); }
        catch (Exception invalid) { throw new IllegalStateException("Stored administration JSON is invalid", invalid); }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception invalid) { throw new IllegalStateException("Administration JSON serialization failed", invalid); }
    }

    private static Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }
    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void invalid(String message) { throw new AdministrationRuntimeException("ADMIN_REQUEST_INVALID", 400, message); }
    private static void conflict(String message) { throw new AdministrationRuntimeException("ADMIN_VERSION_CONFLICT", 409, message); }
    private static void denied() { throw new AdministrationRuntimeException("ADMIN_SCOPE_DENIED", 403, "没有系统管理运行权限"); }

    record JobRunWire(UUID runId, UUID configId, String jobKind, String status, UUID requestedBy,
            int attempt, int processedCount, int succeededCount, int failedCount, Map<String, Object> result,
            String errorCode, String errorMessage, Instant startedAt, Instant finishedAt, long rowVersion,
            Instant createdAt, Instant updatedAt) {}
    record GovernanceFindingWire(UUID findingId, UUID runId, String findingType, String severity,
            String resourceType, UUID resourceId, String summary, String recommendation,
            Map<String, Object> evidence, String status, UUID resolvedBy, Instant resolvedAt,
            long rowVersion, Instant createdAt, Instant updatedAt) {}
    record ResolveFindingRequest(long expectedVersion, String resolution) {}
    record CancelJobRequest(long expectedVersion) {}
    private record JobDefinition(UUID configId, String jobKind) {}
    private record RunningJob(UUID tenantId, UUID runId, String jobKind, UUID requestedBy) {}
    private record AuditLink(UUID id, String previousHash, String eventHash) {}
    private record RoleSet(UUID personId, List<String> roles) {}
    private record FindingDraft(String type, String severity, String resourceType, UUID resourceId,
            String summary, String recommendation, Map<String, Object> evidence) {}
}
