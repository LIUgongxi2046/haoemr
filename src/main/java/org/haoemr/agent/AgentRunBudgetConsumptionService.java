package org.haoemr.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.AgentRunBudgetConsumptionRecordRequestWire;
import org.haoemr.contracts.AgentRunBudgetConsumptionWire;
import org.haoemr.contracts.AgentRunBudgetSummaryWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class AgentRunBudgetConsumptionService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    AgentRunBudgetConsumptionService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    AgentRunBudgetConsumptionWire record(
            ClinicalIdentity identity, String idempotencyKey, AgentRunBudgetConsumptionRecordRequestWire request) {
        if (request.budgetId() == null || request.runId() == null || request.recordedAt() == null) {
            throw invalid("budget_id, run_id and recorded_at are required");
        }
        if (request.tokensConsumed() == null || request.durationSeconds() == null) {
            throw invalid("tokens_consumed and duration_seconds are required");
        }
        if (request.tokensConsumed() < 0 || request.durationSeconds() < 0) {
            throw invalid("tokens_consumed and duration_seconds must not be negative");
        }
        return transactions.execute(status -> {
            beginCommand(identity, "AGENT_RUN_BUDGET_CONSUME", idempotencyKey,
                    sha256(request.budgetId() + "|" + request.runId()));
            BudgetHead budget = lockBudget(identity.tenantId(), request.budgetId());
            if (!"ACTIVE".equals(budget.status())) {
                throw new AgentRunBudgetConsumptionException(
                        "BUDGET_INACTIVE", 409, "Only an active budget can record consumption");
            }
            long currentTokens = sumTokens(identity.tenantId(), request.budgetId());
            long currentDuration = sumDuration(identity.tenantId(), request.budgetId());
            if (currentTokens + request.tokensConsumed() > budget.maxTokens()) {
                throw new AgentRunBudgetConsumptionException(
                        "BUDGET_TOKENS_EXCEEDED", 409, "The budget token limit would be exceeded");
            }
            if (currentDuration + request.durationSeconds() > budget.maxDurationSeconds()) {
                throw new AgentRunBudgetConsumptionException(
                        "BUDGET_DURATION_EXCEEDED", 409, "The budget duration limit would be exceeded");
            }
            UUID consumptionId = UUID.randomUUID();
            jdbc.sql("""
                    insert into agent_run_budget_consumption(
                      tenant_id, consumption_id, budget_id, run_id, tokens_consumed, duration_seconds,
                      recorded_by, recorded_at)
                    values (:tenant, :consumption, :budget, :run, :tokens, :duration, :actor, :recorded_at)
                    """).param("tenant", identity.tenantId()).param("consumption", consumptionId)
                    .param("budget", request.budgetId()).param("run", request.runId())
                    .param("tokens", request.tokensConsumed()).param("duration", request.durationSeconds())
                    .param("actor", identity.userId())
                    .param("recorded_at", request.recordedAt().atOffset(ZoneOffset.UTC)).update();
            appendEvidence(identity, consumptionId, "AGENT_RUN_BUDGET_CONSUMED", "AgentRunBudgetConsumed");
            completeCommand(identity, "AGENT_RUN_BUDGET_CONSUME", idempotencyKey, consumptionId);
            return consumption(identity.tenantId(), consumptionId);
        });
    }

    AgentRunBudgetSummaryWire summary(ClinicalIdentity identity, UUID budgetId) {
        BudgetHead budget = lockBudget(identity.tenantId(), budgetId);
        long totalTokens = sumTokens(identity.tenantId(), budgetId);
        long totalDuration = sumDuration(identity.tenantId(), budgetId);
        return new AgentRunBudgetSummaryWire(
                budgetId, totalTokens, totalDuration, budget.maxTokens(), (long) budget.maxDurationSeconds());
    }

    List<AgentRunBudgetConsumptionWire> list(ClinicalIdentity identity, UUID budgetId) {
        return jdbc.sql("""
                select consumption_id from agent_run_budget_consumption
                where tenant_id = :tenant and budget_id = :budget
                order by recorded_at desc, consumption_id desc limit 500
                """).param("tenant", identity.tenantId()).param("budget", budgetId)
                .query(UUID.class).list().stream()
                .map(id -> consumption(identity.tenantId(), id)).toList();
    }

    private AgentRunBudgetConsumptionWire consumption(UUID tenantId, UUID consumptionId) {
        return jdbc.sql("""
                select consumption_id, budget_id, run_id, tokens_consumed, duration_seconds,
                  recorded_by, recorded_at, row_version
                from agent_run_budget_consumption
                where tenant_id = :tenant and consumption_id = :consumption
                """).param("tenant", tenantId).param("consumption", consumptionId)
                .query((rs, row) -> new AgentRunBudgetConsumptionWire(
                        rs.getObject("consumption_id", UUID.class),
                        rs.getObject("budget_id", UUID.class),
                        rs.getObject("run_id", UUID.class),
                        rs.getLong("tokens_consumed"),
                        rs.getLong("duration_seconds"),
                        rs.getObject("recorded_by", UUID.class),
                        rs.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("row_version")))
                .optional().orElseThrow(AgentRunBudgetConsumptionService::contextDenied);
    }

    private BudgetHead lockBudget(UUID tenantId, UUID budgetId) {
        return jdbc.sql("""
                select status, max_tokens, max_duration_seconds from agent_run_budget
                where tenant_id = :tenant and budget_id = :budget for update
                """).param("tenant", tenantId).param("budget", budgetId)
                .query((rs, row) -> new BudgetHead(
                        rs.getString("status"), rs.getLong("max_tokens"), rs.getInt("max_duration_seconds")))
                .optional().orElseThrow(AgentRunBudgetConsumptionService::contextDenied);
    }

    private long sumTokens(UUID tenantId, UUID budgetId) {
        return jdbc.sql("""
                select coalesce(sum(tokens_consumed), 0) from agent_run_budget_consumption
                where tenant_id = :tenant and budget_id = :budget
                """).param("tenant", tenantId).param("budget", budgetId).query(Long.class).single();
    }

    private long sumDuration(UUID tenantId, UUID budgetId) {
        return jdbc.sql("""
                select coalesce(sum(duration_seconds), 0) from agent_run_budget_consumption
                where tenant_id = :tenant and budget_id = :budget
                """).param("tenant", tenantId).param("budget", budgetId).query(Long.class).single();
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new AgentRunBudgetConsumptionException("INVALID_IDEMPOTENCY_KEY", 400,
                    "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new AgentRunBudgetConsumptionException("IDEMPOTENCY_REPLAY", 409,
                    "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID consumptionId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", consumptionId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, UUID consumptionId, String action, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + consumptionId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'AGENT_RUN_BUDGET_CONSUMPTION', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", consumptionId)
                .param("patient_hash", sha256(identity.tenantId() + "|null"))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'AGENT_RUN_BUDGET_CONSUMPTION', :aggregate, 1, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", consumptionId).param("event_type", eventType).update();
    }

    private static AgentRunBudgetConsumptionException invalid(String message) {
        return new AgentRunBudgetConsumptionException("AGENT_RUN_BUDGET_CONSUMPTION_REQUEST_INVALID", 400, message);
    }

    static AgentRunBudgetConsumptionException contextDenied() {
        return new AgentRunBudgetConsumptionException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested agent run budget consumption context is not permitted");
    }

    private record BudgetHead(String status, long maxTokens, int maxDurationSeconds) {}

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
