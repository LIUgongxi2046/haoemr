package org.haoemr.research;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ResearchProjectCreateRequestWire;
import org.haoemr.contracts.ResearchProjectDeactivateRequestWire;
import org.haoemr.contracts.ResearchProjectWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class ResearchProjectService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    ResearchProjectService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    List<ResearchProjectWire> listProjects(ClinicalIdentity identity, String status) {
        StringBuilder sql = new StringBuilder("""
                select project_id from research_project where tenant_id = :tenant
                """);
        if (status != null && !status.isBlank()) sql.append(" and status = :status");
        sql.append(" order by project_code, project_id limit 500");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("tenant", identity.tenantId());
        if (status != null && !status.isBlank()) spec = spec.param("status", status.trim());
        List<UUID> ids = spec.query(UUID.class).list();
        return ids.stream().map(id -> project(identity.tenantId(), id)).toList();
    }

    ResearchProjectWire create(ClinicalIdentity identity, String idempotencyKey, ResearchProjectCreateRequestWire request) {
        String code = requireText(request.projectCode(), 2, "project_code");
        String name = requireText(request.displayName(), 2, "display_name");
        String investigator = requireText(request.principalInvestigator(), 2, "principal_investigator");
        String purpose = requireText(request.approvedPurpose(), 2, "approved_purpose");
        return transactions.execute(status -> {
            beginCommand(identity, "RESEARCH_PROJECT_CREATE", idempotencyKey, sha256(code));
            UUID projectId = UUID.randomUUID();
            jdbc.sql("""
                    insert into research_project(
                      tenant_id, project_id, project_code, display_name, project_type,
                      principal_investigator, registry_number, ethics_approval, approved_purpose,
                      data_scope, member_count, expires_at, status)
                    values (:tenant, :project, :code, :name, :type,
                      :investigator, :registry, :ethics, :purpose,
                      string_to_array(coalesce(:scope, ''), ','), :members, :expires, 'ACTIVE')
                    """).param("tenant", identity.tenantId()).param("project", projectId)
                    .param("code", code).param("name", name)
                    .param("type", request.projectType() == null ? "OBSERVATIONAL" : request.projectType().name())
                    .param("investigator", investigator)
                    .param("registry", blankToNull(request.registryNumber()))
                    .param("ethics", blankToNull(request.ethicsApproval()))
                    .param("purpose", purpose)
                    .param("scope", String.join(",", request.dataScope() == null ? List.of() : request.dataScope()))
                    .param("members", request.memberCount() == null ? 1 : request.memberCount())
                    .param("expires", request.expiresAt()).update();
            appendEvidence(identity, projectId, "RESEARCH_PROJECT_CREATED", "ResearchProjectCreated");
            completeCommand(identity, "RESEARCH_PROJECT_CREATE", idempotencyKey, projectId);
            return project(identity.tenantId(), projectId);
        });
    }

    ResearchProjectWire deactivate(ClinicalIdentity identity, String idempotencyKey, UUID projectId,
            ResearchProjectDeactivateRequestWire request) {
        return transactions.execute(status -> {
            beginCommand(identity, "RESEARCH_PROJECT_DEACTIVATE", idempotencyKey, sha256(projectId.toString()));
            String current = jdbc.sql("""
                    select status from research_project where tenant_id = :tenant and project_id = :project for update
                    """).param("tenant", identity.tenantId()).param("project", projectId)
                    .query(String.class).optional().orElseThrow(ResearchProjectService::contextDenied);
            if (!"ACTIVE".equals(current)) {
                throw new ResearchProjectException("RESEARCH_PROJECT_STATE_INVALID", 409, "仅活动项目可停用");
            }
            jdbc.sql("""
                    update research_project set status = 'INACTIVE', row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and project_id = :project
                    """).param("tenant", identity.tenantId()).param("project", projectId).update();
            appendEvidence(identity, projectId, "RESEARCH_PROJECT_DEACTIVATED", "ResearchProjectDeactivated");
            completeCommand(identity, "RESEARCH_PROJECT_DEACTIVATE", idempotencyKey, projectId);
            return project(identity.tenantId(), projectId);
        });
    }

    private ResearchProjectWire project(UUID tenantId, UUID projectId) {
        return jdbc.sql("""
                select project_id, project_code, display_name, project_type, principal_investigator,
                  registry_number, ethics_approval, approved_purpose, data_scope, member_count,
                  expires_at, status, row_version, created_at, updated_at
                from research_project where tenant_id = :tenant and project_id = :project
                """).param("tenant", tenantId).param("project", projectId)
                .query((rs, row) -> new ResearchProjectWire(
                        rs.getObject("project_id", UUID.class), rs.getString("project_code"),
                        rs.getString("display_name"),
                        ResearchProjectWire.ProjectTypeValue.valueOf(rs.getString("project_type")),
                        rs.getString("principal_investigator"), rs.getString("registry_number"),
                        rs.getString("ethics_approval"), rs.getString("approved_purpose"),
                        List.of((String[]) rs.getArray("data_scope").getArray()),
                        rs.getInt("member_count"),
                        rs.getObject("expires_at", LocalDate.class),
                        ResearchProjectWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getLong("row_version"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .optional().orElseThrow(ResearchProjectService::contextDenied);
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new ResearchProjectException("RESEARCH_PROJECT_REQUEST_INVALID", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new ResearchProjectException("IDEMPOTENCY_REPLAY", 409, "该命令键已使用");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, Object resource) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', cast(:resource as text))
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", String.valueOf(resource)).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, UUID projectId, String action, String eventType) {
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + projectId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'RESEARCH_PROJECT', :resource,
                  :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", projectId)
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'RESEARCH_PROJECT', :aggregate, 1, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", projectId).param("event_type", eventType).update();
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw new ResearchProjectException("RESEARCH_PROJECT_REQUEST_INVALID", 400, field + " is required");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static ResearchProjectException contextDenied() {
        return new ResearchProjectException("CONTEXT_NOT_PERMITTED", 403, "请求的科研项目上下文不允许访问");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
