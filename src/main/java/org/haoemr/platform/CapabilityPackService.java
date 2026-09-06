package org.haoemr.platform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.CapabilityPackDeactivateRequestWire;
import org.haoemr.contracts.CapabilityPackDefineRequestWire;
import org.haoemr.contracts.CapabilityPackWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class CapabilityPackService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    CapabilityPackService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    CapabilityPackWire define(
            ClinicalIdentity identity, String idempotencyKey, CapabilityPackDefineRequestWire request) {
        String packCode = requireText(request.packCode(), 2, "pack_code");
        String packName = requireText(request.packName(), 2, "pack_name");
        String inherits = blankToNull(request.inheritsFrom());
        if (inherits != null && inherits.equals(packCode)) {
            throw invalid("a capability pack cannot inherit from itself");
        }
        return transactions.execute(status -> {
            validateInheritance(identity.tenantId(), null, packCode, inherits);
            beginCommand(identity, "CAPABILITY_PACK_DEFINE", idempotencyKey,
                    sha256(packCode + "|" + packName + "|" + inherits));
            UUID packId = UUID.randomUUID();
            jdbc.sql("""
                    insert into capability_pack(
                      tenant_id, capability_pack_id, pack_code, pack_name, inherits_from, status)
                    values (:tenant, :pack, :code, :name, :inherits, 'ACTIVE')
                    """).param("tenant", identity.tenantId()).param("pack", packId)
                    .param("code", packCode).param("name", packName).param("inherits", inherits).update();
            appendEvidence(identity, packId, "CAPABILITY_PACK_DEFINED", "CapabilityPackDefined");
            completeCommand(identity, "CAPABILITY_PACK_DEFINE", idempotencyKey, packId);
            return pack(identity.tenantId(), packId);
        });
    }

    CapabilityPackWire update(
            ClinicalIdentity identity, String idempotencyKey, UUID packId,
            CapabilityPackDefineRequestWire request) {
        String packCode = requireText(request.packCode(), 2, "pack_code");
        String packName = requireText(request.packName(), 2, "pack_name");
        String inherits = blankToNull(request.inheritsFrom());
        return transactions.execute(status -> {
            CapabilityPackWire current = pack(identity.tenantId(), packId);
            if (current.status() != CapabilityPackWire.StatusValue.ACTIVE) {
                throw new CapabilityPackException(
                        "CAPABILITY_PACK_STATE_INVALID", 409, "Only an active pack can be edited");
            }
            if (!current.packCode().equals(packCode)) {
                throw new CapabilityPackException(
                        "CAPABILITY_PACK_CODE_IMMUTABLE", 409, "The capability pack code cannot be changed");
            }
            validateInheritance(identity.tenantId(), packId, packCode, inherits);
            beginCommand(identity, "CAPABILITY_PACK_UPDATE", idempotencyKey,
                    sha256(packId + "|" + packName + "|" + inherits));
            jdbc.sql("""
                    update capability_pack set pack_name = :name, inherits_from = :inherits, updated_at = now()
                    where tenant_id = :tenant and capability_pack_id = :pack and status = 'ACTIVE'
                    """).param("name", packName).param("inherits", inherits)
                    .param("tenant", identity.tenantId()).param("pack", packId).update();
            appendEvidence(identity, packId, "CAPABILITY_PACK_UPDATED", "CapabilityPackUpdated");
            completeCommand(identity, "CAPABILITY_PACK_UPDATE", idempotencyKey, packId);
            return pack(identity.tenantId(), packId);
        });
    }

    CapabilityPackWire deactivate(
            ClinicalIdentity identity, String idempotencyKey, UUID packId,
            CapabilityPackDeactivateRequestWire request) {
        return transactions.execute(status -> {
            beginCommand(identity, "CAPABILITY_PACK_DEACTIVATE", idempotencyKey, sha256(packId.toString()));
            String currentStatus = jdbc.sql("""
                    select status from capability_pack
                    where tenant_id = :tenant and capability_pack_id = :pack for update
                    """).param("tenant", identity.tenantId()).param("pack", packId)
                    .query(String.class).optional().orElseThrow(CapabilityPackService::contextDenied);
            if (!"ACTIVE".equals(currentStatus)) {
                throw new CapabilityPackException(
                        "CAPABILITY_PACK_STATE_INVALID", 409, "Only an active pack can be deactivated");
            }
            jdbc.sql("""
                    update capability_pack set status = 'INACTIVE', updated_at = now()
                    where tenant_id = :tenant and capability_pack_id = :pack
                    """).param("tenant", identity.tenantId()).param("pack", packId).update();
            appendEvidence(identity, packId, "CAPABILITY_PACK_DEACTIVATED", "CapabilityPackDeactivated");
            completeCommand(identity, "CAPABILITY_PACK_DEACTIVATE", idempotencyKey, packId);
            return pack(identity.tenantId(), packId);
        });
    }

    List<CapabilityPackWire> listPacks(ClinicalIdentity identity, String status) {
        List<UUID> ids = status == null || status.isBlank()
                ? jdbc.sql("""
                        select capability_pack_id from capability_pack
                        where tenant_id = :tenant order by pack_code, capability_pack_id limit 500
                        """).param("tenant", identity.tenantId()).query(UUID.class).list()
                : jdbc.sql("""
                        select capability_pack_id from capability_pack
                        where tenant_id = :tenant and status = :status
                        order by pack_code, capability_pack_id limit 500
                        """).param("tenant", identity.tenantId()).param("status", status).query(UUID.class).list();
        return ids.stream().map(id -> pack(identity.tenantId(), id)).toList();
    }

    private CapabilityPackWire pack(UUID tenantId, UUID packId) {
        return jdbc.sql("""
                select capability_pack_id, pack_code, pack_name, inherits_from, status
                from capability_pack where tenant_id = :tenant and capability_pack_id = :pack
                """).param("tenant", tenantId).param("pack", packId)
                .query((rs, row) -> new CapabilityPackWire(
                        rs.getObject("capability_pack_id", UUID.class), rs.getString("pack_code"),
                        rs.getString("pack_name"), rs.getString("inherits_from"),
                        CapabilityPackWire.StatusValue.valueOf(rs.getString("status"))))
                .optional().orElseThrow(CapabilityPackService::contextDenied);
    }

    private void validateInheritance(UUID tenantId, UUID currentPackId, String packCode, String inherits) {
        if (inherits == null) return;
        if (inherits.equals(packCode)) throw invalid("a capability pack cannot inherit from itself");
        UUID parentId = jdbc.sql("""
                select capability_pack_id from capability_pack
                where tenant_id = :tenant and pack_code = :code and status = 'ACTIVE'
                """).param("tenant", tenantId).param("code", inherits).query(UUID.class).optional()
                .orElseThrow(() -> invalid("inherits_from must reference an active capability pack"));
        if (currentPackId == null) return;
        long cycle = jdbc.sql("""
                with recursive lineage as (
                  select capability_pack_id, inherits_from from capability_pack
                  where tenant_id = :tenant and capability_pack_id = :parent
                  union all
                  select candidate.capability_pack_id, candidate.inherits_from
                  from capability_pack candidate join lineage on candidate.pack_code = lineage.inherits_from
                  where candidate.tenant_id = :tenant
                )
                select count(*) from lineage where capability_pack_id = :current
                """).param("tenant", tenantId).param("parent", parentId).param("current", currentPackId)
                .query(Long.class).single();
        if (cycle > 0) throw invalid("capability pack inheritance cannot contain a cycle");
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new CapabilityPackException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new CapabilityPackException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID packId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", packId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, UUID packId, String action, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + packId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'CAPABILITY_PACK', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", packId)
                .param("patient_hash", sha256(identity.tenantId() + "|null"))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'CAPABILITY_PACK', :aggregate, 1, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", packId).param("event_type", eventType).update();
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw invalid(field + " must be at least " + minLength + " characters");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static CapabilityPackException invalid(String message) {
        return new CapabilityPackException("CAPABILITY_PACK_REQUEST_INVALID", 400, message);
    }

    static CapabilityPackException contextDenied() {
        return new CapabilityPackException("CONTEXT_NOT_PERMITTED", 403,
                "The requested capability pack context is not permitted");
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
