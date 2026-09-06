package org.haoemr.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.SkillRegistryDeactivateRequestWire;
import org.haoemr.contracts.SkillRegistryRegisterRequestWire;
import org.haoemr.contracts.SkillRegistryVersionRequestWire;
import org.haoemr.contracts.SkillRegistryWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class SkillRegistryService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    SkillRegistryService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    SkillRegistryWire register(
            ClinicalIdentity identity, String idempotencyKey, SkillRegistryRegisterRequestWire request) {
        String skillCode = requireText(request.skillCode(), 2, "skill_code");
        String skillName = requireText(request.skillName(), 2, "skill_name");
        String skillVersion = requireText(request.skillVersion(), 1, "skill_version");
        return transactions.execute(status -> {
            beginCommand(identity, "SKILL_REGISTRY_REGISTER", idempotencyKey,
                    sha256(skillCode + "|" + skillVersion));
            UUID registryId = UUID.randomUUID();
            jdbc.sql("""
                    insert into skill_registry(
                      tenant_id, skill_registry_id, skill_code, skill_name, skill_version, status)
                    values (:tenant, :registry, :code, :name, :version, 'ACTIVE')
                    """).param("tenant", identity.tenantId()).param("registry", registryId)
                    .param("code", skillCode).param("name", skillName).param("version", skillVersion).update();
            appendEvidence(identity, registryId, "SKILL_REGISTRY_REGISTERED", "SkillRegistryRegistered");
            completeCommand(identity, "SKILL_REGISTRY_REGISTER", idempotencyKey, registryId);
            return registry(identity.tenantId(), registryId);
        });
    }

    SkillRegistryWire deactivate(
            ClinicalIdentity identity, String idempotencyKey, UUID registryId,
            SkillRegistryDeactivateRequestWire request) {
        return transactions.execute(status -> {
            beginCommand(identity, "SKILL_REGISTRY_DEACTIVATE", idempotencyKey, sha256(registryId.toString()));
            String currentStatus = jdbc.sql("""
                    select status from skill_registry
                    where tenant_id = :tenant and skill_registry_id = :registry for update
                    """).param("tenant", identity.tenantId()).param("registry", registryId)
                    .query(String.class).optional().orElseThrow(SkillRegistryService::contextDenied);
            if (!"ACTIVE".equals(currentStatus)) {
                throw new SkillRegistryException(
                        "SKILL_REGISTRY_STATE_INVALID", 409, "Only an active skill can be deactivated");
            }
            jdbc.sql("""
                    update skill_registry set status = 'INACTIVE', updated_at = now()
                    where tenant_id = :tenant and skill_registry_id = :registry
                    """).param("tenant", identity.tenantId()).param("registry", registryId).update();
            appendEvidence(identity, registryId, "SKILL_REGISTRY_DEACTIVATED", "SkillRegistryDeactivated");
            completeCommand(identity, "SKILL_REGISTRY_DEACTIVATE", idempotencyKey, registryId);
            return registry(identity.tenantId(), registryId);
        });
    }

    SkillRegistryWire publishVersion(
            ClinicalIdentity identity, String idempotencyKey, UUID currentRegistryId,
            SkillRegistryVersionRequestWire request) {
        String skillName = requireText(request.skillName(), 2, "skill_name");
        String skillVersion = requireText(request.skillVersion(), 1, "skill_version");
        return transactions.execute(status -> {
            beginCommand(identity, "SKILL_REGISTRY_PUBLISH_VERSION", idempotencyKey,
                    sha256(currentRegistryId + "|" + skillName + "|" + skillVersion));
            RegistryHead current = jdbc.sql("""
                    select skill_code, skill_version, status from skill_registry
                    where tenant_id = :tenant and skill_registry_id = :registry for update
                    """).param("tenant", identity.tenantId()).param("registry", currentRegistryId)
                    .query((rs, row) -> new RegistryHead(
                            rs.getString("skill_code"), rs.getString("skill_version"), rs.getString("status")))
                    .optional().orElseThrow(SkillRegistryService::contextDenied);
            if (!"ACTIVE".equals(current.status())) {
                throw new SkillRegistryException(
                        "SKILL_REGISTRY_STATE_INVALID", 409, "Only an active skill can publish a new version");
            }
            if (current.version().equals(skillVersion)) {
                throw invalid("skill_version must change when publishing a new version");
            }
            jdbc.sql("""
                    update skill_registry set status = 'INACTIVE', updated_at = now()
                    where tenant_id = :tenant and skill_registry_id = :registry
                    """).param("tenant", identity.tenantId()).param("registry", currentRegistryId).update();
            UUID nextRegistryId = UUID.randomUUID();
            jdbc.sql("""
                    insert into skill_registry(
                      tenant_id, skill_registry_id, skill_code, skill_name, skill_version, status)
                    values (:tenant, :registry, :code, :name, :version, 'ACTIVE')
                    """).param("tenant", identity.tenantId()).param("registry", nextRegistryId)
                    .param("code", current.code()).param("name", skillName).param("version", skillVersion).update();
            appendEvidence(identity, currentRegistryId,
                    "SKILL_REGISTRY_SUPERSEDED", "SkillRegistrySuperseded");
            appendEvidence(identity, nextRegistryId,
                    "SKILL_REGISTRY_VERSION_PUBLISHED", "SkillRegistryVersionPublished");
            completeCommand(identity, "SKILL_REGISTRY_PUBLISH_VERSION", idempotencyKey, nextRegistryId);
            return registry(identity.tenantId(), nextRegistryId);
        });
    }

    List<SkillRegistryWire> listSkills(ClinicalIdentity identity, String status) {
        List<UUID> ids = status == null || status.isBlank()
                ? jdbc.sql("""
                        select skill_registry_id from skill_registry
                        where tenant_id = :tenant order by skill_code, skill_registry_id limit 500
                        """).param("tenant", identity.tenantId()).query(UUID.class).list()
                : jdbc.sql("""
                        select skill_registry_id from skill_registry
                        where tenant_id = :tenant and status = :status
                        order by skill_code, skill_registry_id limit 500
                        """).param("tenant", identity.tenantId()).param("status", status).query(UUID.class).list();
        return ids.stream().map(id -> registry(identity.tenantId(), id)).toList();
    }

    private SkillRegistryWire registry(UUID tenantId, UUID registryId) {
        return jdbc.sql("""
                select skill_registry_id, skill_code, skill_name, skill_version, status
                from skill_registry where tenant_id = :tenant and skill_registry_id = :registry
                """).param("tenant", tenantId).param("registry", registryId)
                .query((rs, row) -> new SkillRegistryWire(
                        rs.getObject("skill_registry_id", UUID.class), rs.getString("skill_code"),
                        rs.getString("skill_name"), rs.getString("skill_version"),
                        SkillRegistryWire.StatusValue.valueOf(rs.getString("status"))))
                .optional().orElseThrow(SkillRegistryService::contextDenied);
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new SkillRegistryException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new SkillRegistryException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
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
                values (:tenant, :audit, now(), :actor, :action, 'SKILL_REGISTRY', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", registryId)
                .param("patient_hash", sha256(identity.tenantId() + "|null"))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'SKILL_REGISTRY', :aggregate, 1, :event_type, 1,
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

    private static SkillRegistryException invalid(String message) {
        return new SkillRegistryException("SKILL_REGISTRY_REQUEST_INVALID", 400, message);
    }

    static SkillRegistryException contextDenied() {
        return new SkillRegistryException("CONTEXT_NOT_PERMITTED", 403,
                "The requested skill registry context is not permitted");
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
}
