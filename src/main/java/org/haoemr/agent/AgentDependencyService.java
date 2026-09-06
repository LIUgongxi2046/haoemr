package org.haoemr.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.AgentDependencyDeclareRequestWire;
import org.haoemr.contracts.AgentDependencyWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class AgentDependencyService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    AgentDependencyService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    AgentDependencyWire declare(
            ClinicalIdentity identity, String idempotencyKey, AgentDependencyDeclareRequestWire request) {
        if (request.dependencyType() == null) {
            throw invalid("dependency_type is required");
        }
        String dependencyCode = requireText(request.dependencyCode(), 2, "dependency_code");
        requireAgent(identity.tenantId(), request.agentRegistryId());
        requireResolvable(identity.tenantId(), request.dependencyType(), dependencyCode);
        return transactions.execute(status -> {
            beginCommand(identity, "AGENT_DEPENDENCY_DECLARE", idempotencyKey,
                    sha256(request.agentRegistryId() + "|" + request.dependencyType() + "|" + dependencyCode));
            UUID dependencyId = UUID.randomUUID();
            jdbc.sql("""
                    insert into agent_dependency(
                      tenant_id, agent_dependency_id, agent_registry_id, dependency_type, dependency_code)
                    values (:tenant, :dependency, :agent, :type, :code)
                    """).param("tenant", identity.tenantId()).param("dependency", dependencyId)
                    .param("agent", request.agentRegistryId()).param("type", request.dependencyType().name())
                    .param("code", dependencyCode).update();
            appendEvidence(identity, dependencyId, "AGENT_DEPENDENCY_DECLARED", "AgentDependencyDeclared");
            completeCommand(identity, "AGENT_DEPENDENCY_DECLARE", idempotencyKey, dependencyId);
            return dependency(identity.tenantId(), dependencyId);
        });
    }

    List<AgentDependencyWire> listDependencies(ClinicalIdentity identity, UUID agentRegistryId) {
        requireAgent(identity.tenantId(), agentRegistryId);
        return jdbc.sql("""
                select agent_dependency_id from agent_dependency
                where tenant_id = :tenant and agent_registry_id = :agent
                order by dependency_type, dependency_code, agent_dependency_id limit 200
                """).param("tenant", identity.tenantId()).param("agent", agentRegistryId)
                .query(UUID.class).list().stream()
                .map(id -> dependency(identity.tenantId(), id)).toList();
    }

    private AgentDependencyWire dependency(UUID tenantId, UUID dependencyId) {
        return jdbc.sql("""
                select agent_dependency_id, agent_registry_id, dependency_type, dependency_code, row_version
                from agent_dependency where tenant_id = :tenant and agent_dependency_id = :dependency
                """).param("tenant", tenantId).param("dependency", dependencyId)
                .query((rs, row) -> {
                    AgentDependencyWire.DependencyTypeValue type =
                            AgentDependencyWire.DependencyTypeValue.valueOf(rs.getString("dependency_type"));
                    String code = rs.getString("dependency_code");
                    return new AgentDependencyWire(
                            rs.getObject("agent_dependency_id", UUID.class),
                            rs.getObject("agent_registry_id", UUID.class),
                            type, code,
                            resolved(tenantId, type, code),
                            rs.getLong("row_version"));
                }).optional().orElseThrow(AgentDependencyService::contextDenied);
    }

    private boolean resolved(UUID tenantId, AgentDependencyWire.DependencyTypeValue type, String code) {
        if (type == AgentDependencyWire.DependencyTypeValue.SKILL) {
            return jdbc.sql("""
                    select count(*) from skill_registry
                    where tenant_id = :tenant and skill_code = :code and status = 'ACTIVE'
                    """).param("tenant", tenantId).param("code", code).query(Long.class).single() == 1;
        }
        return jdbc.sql("""
                select count(*) from tool_registry
                where tenant_id = :tenant and tool_code = :code and status = 'ACTIVE'
                """).param("tenant", tenantId).param("code", code).query(Long.class).single() == 1;
    }

    private void requireAgent(UUID tenantId, UUID agentRegistryId) {
        long count = jdbc.sql("""
                select count(*) from agent_registry
                where tenant_id = :tenant and agent_registry_id = :agent
                """).param("tenant", tenantId).param("agent", agentRegistryId).query(Long.class).single();
        if (count != 1) throw contextDenied();
    }

    private void requireResolvable(
            UUID tenantId, AgentDependencyDeclareRequestWire.DependencyTypeValue type, String code) {
        boolean ok;
        if (type == AgentDependencyDeclareRequestWire.DependencyTypeValue.SKILL) {
            ok = jdbc.sql("""
                    select count(*) from skill_registry
                    where tenant_id = :tenant and skill_code = :code and status = 'ACTIVE'
                    """).param("tenant", tenantId).param("code", code).query(Long.class).single() == 1;
        } else {
            ok = jdbc.sql("""
                    select count(*) from tool_registry
                    where tenant_id = :tenant and tool_code = :code and status = 'ACTIVE'
                    """).param("tenant", tenantId).param("code", code).query(Long.class).single() == 1;
        }
        if (!ok) {
            throw new AgentDependencyException(
                    "AGENT_DEPENDENCY_UNRESOLVABLE", 409,
                    "The dependency must reference an existing active " + type.name().toLowerCase());
        }
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new AgentDependencyException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new AgentDependencyException("IDEMPOTENCY_REPLAY", 409,
                    "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID dependencyId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", dependencyId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, UUID dependencyId, String action, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + dependencyId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'AGENT_DEPENDENCY', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", dependencyId)
                .param("patient_hash", sha256(identity.tenantId() + "|null"))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'AGENT_DEPENDENCY', :aggregate, 1, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", dependencyId).param("event_type", eventType).update();
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw invalid(field + " must be at least " + minLength + " characters");
        }
        return value.trim();
    }

    private static AgentDependencyException invalid(String message) {
        return new AgentDependencyException("AGENT_DEPENDENCY_REQUEST_INVALID", 400, message);
    }

    static AgentDependencyException contextDenied() {
        return new AgentDependencyException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested agent dependency context is not permitted");
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
