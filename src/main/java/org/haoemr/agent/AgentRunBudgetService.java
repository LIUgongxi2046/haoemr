package org.haoemr.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.AgentRunBudgetDeactivateRequestWire;
import org.haoemr.contracts.AgentRunBudgetDefineRequestWire;
import org.haoemr.contracts.AgentRunBudgetUpdateRequestWire;
import org.haoemr.contracts.AgentRunBudgetWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class AgentRunBudgetService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    AgentRunBudgetService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    AgentRunBudgetWire define(
            ClinicalIdentity identity, String idempotencyKey, AgentRunBudgetDefineRequestWire request) {
        String budgetCode = requireText(request.budgetCode(), 2, "budget_code");
        String budgetName = requireText(request.budgetName(), 2, "budget_name");
        if (!budgetCode.matches("BUDGET_[A-Z0-9_]+")) {
            throw invalid("budget_code must use BUDGET_<MEDICAL_AGENT_CODE>");
        }
        if (request.maxTokens() == null || request.maxTokens() <= 0) {
            throw invalid("max_tokens must be positive");
        }
        if (request.maxDurationSeconds() == null || request.maxDurationSeconds() <= 0) {
            throw invalid("max_duration_seconds must be positive");
        }
        return transactions.execute(status -> {
            ensureActiveMedicalAgent(identity.tenantId(), budgetCode);
            beginCommand(identity, "AGENT_RUN_BUDGET_DEFINE", idempotencyKey,
                    sha256(budgetCode + "|" + request.maxTokens() + "|" + request.maxDurationSeconds()));
            UUID budgetId = UUID.randomUUID();
            jdbc.sql("""
                    insert into agent_run_budget(
                      tenant_id, budget_id, budget_code, budget_name, max_tokens,
                      max_duration_seconds, status)
                    values (:tenant, :budget, :code, :name, :tokens, :duration, 'ACTIVE')
                    """).param("tenant", identity.tenantId()).param("budget", budgetId)
                    .param("code", budgetCode).param("name", budgetName)
                    .param("tokens", request.maxTokens()).param("duration", request.maxDurationSeconds()).update();
            appendEvidence(identity, budgetId, "AGENT_RUN_BUDGET_DEFINED", "AgentRunBudgetDefined");
            completeCommand(identity, "AGENT_RUN_BUDGET_DEFINE", idempotencyKey, budgetId);
            return budget(identity.tenantId(), budgetId);
        });
    }

    AgentRunBudgetWire deactivate(
            ClinicalIdentity identity, String idempotencyKey, UUID budgetId,
            AgentRunBudgetDeactivateRequestWire request) {
        return transactions.execute(status -> {
            beginCommand(identity, "AGENT_RUN_BUDGET_DEACTIVATE", idempotencyKey, sha256(budgetId.toString()));
            String currentStatus = jdbc.sql("""
                    select status from agent_run_budget
                    where tenant_id = :tenant and budget_id = :budget for update
                    """).param("tenant", identity.tenantId()).param("budget", budgetId)
                    .query(String.class).optional().orElseThrow(AgentRunBudgetService::contextDenied);
            if (!"ACTIVE".equals(currentStatus)) {
                throw new AgentRunBudgetException(
                        "AGENT_RUN_BUDGET_STATE_INVALID", 409, "Only an active budget can be deactivated");
            }
            jdbc.sql("""
                    update agent_run_budget set status = 'INACTIVE', row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and budget_id = :budget
                    """).param("tenant", identity.tenantId()).param("budget", budgetId).update();
            appendEvidence(identity, budgetId, "AGENT_RUN_BUDGET_DEACTIVATED", "AgentRunBudgetDeactivated");
            completeCommand(identity, "AGENT_RUN_BUDGET_DEACTIVATE", idempotencyKey, budgetId);
            return budget(identity.tenantId(), budgetId);
        });
    }

    AgentRunBudgetWire update(
            ClinicalIdentity identity, String idempotencyKey, UUID budgetId,
            AgentRunBudgetUpdateRequestWire request) {
        String budgetName = requireText(request.budgetName(), 2, "budget_name");
        if (request.maxTokens() == null || request.maxTokens() <= 0) {
            throw invalid("max_tokens must be positive");
        }
        if (request.maxDurationSeconds() == null || request.maxDurationSeconds() <= 0) {
            throw invalid("max_duration_seconds must be positive");
        }
        if (request.expectedRowVersion() == null || request.expectedRowVersion() <= 0) {
            throw invalid("expected_row_version must be positive");
        }
        return transactions.execute(status -> {
            beginCommand(identity, "AGENT_RUN_BUDGET_UPDATE", idempotencyKey,
                    sha256(budgetId + "|" + budgetName + "|" + request.maxTokens() + "|"
                            + request.maxDurationSeconds() + "|" + request.expectedRowVersion()));
            BudgetHead current = jdbc.sql("""
                    select status, row_version from agent_run_budget
                    where tenant_id = :tenant and budget_id = :budget for update
                    """).param("tenant", identity.tenantId()).param("budget", budgetId)
                    .query((rs, row) -> new BudgetHead(rs.getString("status"), rs.getLong("row_version")))
                    .optional().orElseThrow(AgentRunBudgetService::contextDenied);
            if (!"ACTIVE".equals(current.status())) {
                throw new AgentRunBudgetException(
                        "AGENT_RUN_BUDGET_STATE_INVALID", 409, "Only an active budget can be updated");
            }
            if (current.rowVersion() != request.expectedRowVersion()) {
                throw new AgentRunBudgetException(
                        "AGENT_RUN_BUDGET_VERSION_CONFLICT", 409, "The budget changed; reload before retrying");
            }
            // The trigger authorization is evaluated in the exact UPDATE statement, so a pooled
            // connection can never carry a bypass into an unrelated command.
            jdbc.sql("""
                    update agent_run_budget set budget_name = :name, max_tokens = :tokens,
                      max_duration_seconds = :duration, row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and budget_id = :budget and row_version = :expected
                      and set_config('haoemr.budget_update_authorized', 'true', true) = 'true'
                    """).param("name", budgetName).param("tokens", request.maxTokens())
                    .param("duration", request.maxDurationSeconds()).param("tenant", identity.tenantId())
                    .param("budget", budgetId).param("expected", request.expectedRowVersion()).update();
            appendEvidence(identity, budgetId, "AGENT_RUN_BUDGET_UPDATED", "AgentRunBudgetUpdated");
            completeCommand(identity, "AGENT_RUN_BUDGET_UPDATE", idempotencyKey, budgetId);
            return budget(identity.tenantId(), budgetId);
        });
    }

    List<AgentRunBudgetWire> listBudgets(ClinicalIdentity identity, String status) {
        List<UUID> ids = status == null || status.isBlank()
                ? jdbc.sql("""
                        select budget_id from agent_run_budget
                        where tenant_id = :tenant order by budget_code, budget_id limit 500
                        """).param("tenant", identity.tenantId()).query(UUID.class).list()
                : jdbc.sql("""
                        select budget_id from agent_run_budget
                        where tenant_id = :tenant and status = :status
                        order by budget_code, budget_id limit 500
                        """).param("tenant", identity.tenantId()).param("status", status).query(UUID.class).list();
        return ids.stream().map(id -> budget(identity.tenantId(), id)).toList();
    }

    /** A budget is a runtime guard for one enabled main medical-agent team, never a free-form quota. */
    private void ensureActiveMedicalAgent(UUID tenantId, String budgetCode) {
        String agentCode = budgetCode.substring("BUDGET_".length());
        boolean active = jdbc.sql("""
                select exists(
                  select 1 from agent_registry
                  where tenant_id = :tenant and agent_code = :agent and status = 'ACTIVE'
                )
                """).param("tenant", tenantId).param("agent", agentCode).query(Boolean.class).single();
        if (!active) {
            throw invalid("budget_code must reference an active medical agent");
        }
    }

    private AgentRunBudgetWire budget(UUID tenantId, UUID budgetId) {
        return jdbc.sql("""
                select budget_id, budget_code, budget_name, max_tokens, max_duration_seconds, status, row_version
                from agent_run_budget where tenant_id = :tenant and budget_id = :budget
                """).param("tenant", tenantId).param("budget", budgetId)
                .query((rs, row) -> new AgentRunBudgetWire(
                        rs.getObject("budget_id", UUID.class), rs.getString("budget_code"),
                        rs.getString("budget_name"), rs.getLong("max_tokens"),
                        rs.getInt("max_duration_seconds"),
                        AgentRunBudgetWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getLong("row_version")))
                .optional().orElseThrow(AgentRunBudgetService::contextDenied);
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new AgentRunBudgetException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new AgentRunBudgetException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID budgetId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", budgetId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, UUID budgetId, String action, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + budgetId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'AGENT_RUN_BUDGET', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", budgetId)
                .param("patient_hash", sha256(identity.tenantId() + "|null"))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'AGENT_RUN_BUDGET', :aggregate, :aggregate_version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", budgetId).param("aggregate_version", budgetRowVersion(identity.tenantId(), budgetId))
                .param("event_type", eventType).update();
    }

    private long budgetRowVersion(UUID tenantId, UUID budgetId) {
        return jdbc.sql("""
                select row_version from agent_run_budget
                where tenant_id = :tenant and budget_id = :budget
                """).param("tenant", tenantId).param("budget", budgetId).query(Long.class).single();
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw invalid(field + " must be at least " + minLength + " characters");
        }
        return value.trim();
    }

    private static AgentRunBudgetException invalid(String message) {
        return new AgentRunBudgetException("AGENT_RUN_BUDGET_REQUEST_INVALID", 400, message);
    }

    static AgentRunBudgetException contextDenied() {
        return new AgentRunBudgetException("CONTEXT_NOT_PERMITTED", 403,
                "The requested agent run budget context is not permitted");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record BudgetHead(String status, long rowVersion) {}
}
