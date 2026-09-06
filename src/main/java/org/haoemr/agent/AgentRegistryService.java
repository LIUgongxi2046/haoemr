package org.haoemr.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.AgentRegistryDeactivateRequestWire;
import org.haoemr.contracts.AgentRegistryRegisterRequestWire;
import org.haoemr.contracts.AgentRegistryVersionRequestWire;
import org.haoemr.contracts.AgentRegistryWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class AgentRegistryService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    AgentRegistryService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    AgentRegistryWire register(
            ClinicalIdentity identity, String idempotencyKey, AgentRegistryRegisterRequestWire request) {
        String agentCode = requireText(request.agentCode(), 2, "agent_code");
        String agentName = requireText(request.agentName(), 2, "agent_name");
        String agentVersion = requireText(request.agentVersion(), 1, "agent_version");
        return transactions.execute(status -> {
            beginCommand(identity, "AGENT_REGISTRY_REGISTER", idempotencyKey,
                    sha256(agentCode + "|" + agentVersion));
            UUID registryId = UUID.randomUUID();
            jdbc.sql("""
                    insert into agent_registry(
                      tenant_id, agent_registry_id, agent_code, agent_name, agent_version, status)
                    values (:tenant, :registry, :code, :name, :version, 'ACTIVE')
                    """).param("tenant", identity.tenantId()).param("registry", registryId)
                    .param("code", agentCode).param("name", agentName).param("version", agentVersion).update();
            appendEvidence(identity, registryId, "AGENT_REGISTRY_REGISTERED", "AgentRegistryRegistered");
            completeCommand(identity, "AGENT_REGISTRY_REGISTER", idempotencyKey, registryId);
            return registry(identity.tenantId(), registryId);
        });
    }

    AgentRegistryWire deactivate(
            ClinicalIdentity identity, String idempotencyKey, UUID registryId,
            AgentRegistryDeactivateRequestWire request) {
        return transactions.execute(status -> {
            beginCommand(identity, "AGENT_REGISTRY_DEACTIVATE", idempotencyKey, sha256(registryId.toString()));
            String currentStatus = jdbc.sql("""
                    select status from agent_registry
                    where tenant_id = :tenant and agent_registry_id = :registry for update
                    """).param("tenant", identity.tenantId()).param("registry", registryId)
                    .query(String.class).optional().orElseThrow(AgentRegistryService::contextDenied);
            if (!"ACTIVE".equals(currentStatus)) {
                throw new AgentRegistryException(
                        "AGENT_REGISTRY_STATE_INVALID", 409, "Only an active agent can be deactivated");
            }
            jdbc.sql("""
                    update agent_registry set status = 'INACTIVE', updated_at = now()
                    where tenant_id = :tenant and agent_registry_id = :registry
                    """).param("tenant", identity.tenantId()).param("registry", registryId).update();
            appendEvidence(identity, registryId, "AGENT_REGISTRY_DEACTIVATED", "AgentRegistryDeactivated");
            completeCommand(identity, "AGENT_REGISTRY_DEACTIVATE", idempotencyKey, registryId);
            return registry(identity.tenantId(), registryId);
        });
    }

    AgentRegistryWire publishVersion(
            ClinicalIdentity identity, String idempotencyKey, UUID currentRegistryId,
            AgentRegistryVersionRequestWire request) {
        String agentName = requireText(request.agentName(), 2, "agent_name");
        String agentVersion = requireText(request.agentVersion(), 1, "agent_version");
        return transactions.execute(status -> {
            beginCommand(identity, "AGENT_REGISTRY_PUBLISH_VERSION", idempotencyKey,
                    sha256(currentRegistryId + "|" + agentName + "|" + agentVersion));
            RegistryHead current = jdbc.sql("""
                    select agent_code, agent_version, status from agent_registry
                    where tenant_id = :tenant and agent_registry_id = :registry for update
                    """).param("tenant", identity.tenantId()).param("registry", currentRegistryId)
                    .query((rs, row) -> new RegistryHead(
                            rs.getString("agent_code"), rs.getString("agent_version"), rs.getString("status")))
                    .optional().orElseThrow(AgentRegistryService::contextDenied);
            if (!"ACTIVE".equals(current.status())) {
                throw new AgentRegistryException(
                        "AGENT_REGISTRY_STATE_INVALID", 409, "Only an active agent can publish a new version");
            }
            if (current.version().equals(agentVersion)) {
                throw invalid("agent_version must change when publishing a new version");
            }
            List<DependencyRow> dependencies = jdbc.sql("""
                    select dependency_type, dependency_code from agent_dependency
                    where tenant_id = :tenant and agent_registry_id = :registry
                    order by dependency_type, dependency_code
                    """).param("tenant", identity.tenantId()).param("registry", currentRegistryId)
                    .query((rs, row) -> new DependencyRow(
                            rs.getString("dependency_type"), rs.getString("dependency_code"))).list();
            jdbc.sql("""
                    update agent_registry set status = 'INACTIVE', updated_at = now()
                    where tenant_id = :tenant and agent_registry_id = :registry
                    """).param("tenant", identity.tenantId()).param("registry", currentRegistryId).update();
            UUID nextRegistryId = UUID.randomUUID();
            jdbc.sql("""
                    insert into agent_registry(
                      tenant_id, agent_registry_id, agent_code, agent_name, agent_version, status)
                    values (:tenant, :registry, :code, :name, :version, 'ACTIVE')
                    """).param("tenant", identity.tenantId()).param("registry", nextRegistryId)
                    .param("code", current.code()).param("name", agentName).param("version", agentVersion).update();
            for (DependencyRow dependency : dependencies) {
                jdbc.sql("""
                        insert into agent_dependency(
                          tenant_id, agent_dependency_id, agent_registry_id, dependency_type, dependency_code)
                        values (:tenant, :dependency, :registry, :type, :code)
                        """).param("tenant", identity.tenantId()).param("dependency", UUID.randomUUID())
                        .param("registry", nextRegistryId).param("type", dependency.type())
                        .param("code", dependency.code()).update();
            }
            appendEvidence(identity, currentRegistryId,
                    "AGENT_REGISTRY_SUPERSEDED", "AgentRegistrySuperseded");
            appendEvidence(identity, nextRegistryId,
                    "AGENT_REGISTRY_VERSION_PUBLISHED", "AgentRegistryVersionPublished");
            completeCommand(identity, "AGENT_REGISTRY_PUBLISH_VERSION", idempotencyKey, nextRegistryId);
            return registry(identity.tenantId(), nextRegistryId);
        });
    }

    List<AgentRegistryWire> listAgents(ClinicalIdentity identity, String status) {
        List<UUID> ids = status == null || status.isBlank()
                ? jdbc.sql("""
                        select agent_registry_id from agent_registry
                        where tenant_id = :tenant order by agent_code, agent_registry_id limit 500
                        """).param("tenant", identity.tenantId()).query(UUID.class).list()
                : jdbc.sql("""
                        select agent_registry_id from agent_registry
                        where tenant_id = :tenant and status = :status
                        order by agent_code, agent_registry_id limit 500
                        """).param("tenant", identity.tenantId()).param("status", status).query(UUID.class).list();
        return ids.stream().map(id -> registry(identity.tenantId(), id)).toList();
    }

    private AgentRegistryWire registry(UUID tenantId, UUID registryId) {
        return jdbc.sql("""
                select agent_registry_id, agent_code, agent_name, agent_version, status
                from agent_registry where tenant_id = :tenant and agent_registry_id = :registry
                """).param("tenant", tenantId).param("registry", registryId)
                .query((rs, row) -> new AgentRegistryWire(
                        rs.getObject("agent_registry_id", UUID.class), rs.getString("agent_code"),
                        rs.getString("agent_name"), rs.getString("agent_version"),
                        AgentRegistryWire.StatusValue.valueOf(rs.getString("status"))))
                .optional().orElseThrow(AgentRegistryService::contextDenied);
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new AgentRegistryException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new AgentRegistryException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID registryId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", registryId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, UUID registryId, String action, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + registryId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'AGENT_REGISTRY', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", registryId)
                .param("patient_hash", sha256(identity.tenantId() + "|null"))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'AGENT_REGISTRY', :aggregate, 1, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", registryId).param("event_type", eventType).update();
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw invalid(field + " must be at least " + minLength + " characters");
        }
        return value.trim();
    }

    private static AgentRegistryException invalid(String message) {
        return new AgentRegistryException("AGENT_REGISTRY_REQUEST_INVALID", 400, message);
    }

    static AgentRegistryException contextDenied() {
        return new AgentRegistryException("CONTEXT_NOT_PERMITTED", 403,
                "The requested agent registry context is not permitted");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record RegistryHead(String code, String version, String status) {}
    private record DependencyRow(String type, String code) {}
}
