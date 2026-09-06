package org.haoemr.platform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.CapabilityPackReleaseCreateRequestWire;
import org.haoemr.contracts.CapabilityPackReleaseRollbackRequestWire;
import org.haoemr.contracts.CapabilityPackReleaseTransitionRequestWire;
import org.haoemr.contracts.CapabilityPackReleaseWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class CapabilityPackReleaseService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    CapabilityPackReleaseService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    CapabilityPackReleaseWire create(
            ClinicalIdentity identity, String idempotencyKey, CapabilityPackReleaseCreateRequestWire request) {
        if (request.capabilityPackId() == null || request.releasedAt() == null) {
            throw invalid("capability_pack_id and released_at are required");
        }
        String releaseVersion = requireText(request.releaseVersion(), 2, "release_version");
        return transactions.execute(status -> {
            beginCommand(identity, "CAPABILITY_PACK_RELEASE_CREATE", idempotencyKey,
                    sha256(request.capabilityPackId() + "|" + releaseVersion));
            requireActivePack(identity.tenantId(), request.capabilityPackId());
            UUID releaseId = UUID.randomUUID();
            jdbc.sql("""
                    insert into capability_pack_release(
                      tenant_id, release_id, capability_pack_id, release_version, lifecycle_status,
                      released_by, released_at)
                    values (:tenant, :release, :pack, :version, 'DRAFT', :released_by, :released_at)
                    """).param("tenant", identity.tenantId()).param("release", releaseId)
                    .param("pack", request.capabilityPackId()).param("version", releaseVersion)
                    .param("released_by", identity.userId())
                    .param("released_at", request.releasedAt().atOffset(ZoneOffset.UTC)).update();
            appendEvidence(identity, releaseId, "CAPABILITY_PACK_RELEASE_DRAFTED", "CapabilityPackReleaseDrafted");
            completeCommand(identity, "CAPABILITY_PACK_RELEASE_CREATE", idempotencyKey, releaseId);
            return release(identity.tenantId(), releaseId);
        });
    }

    CapabilityPackReleaseWire startCanary(
            ClinicalIdentity identity, String idempotencyKey, UUID releaseId,
            CapabilityPackReleaseTransitionRequestWire request) {
        return transition(identity, idempotencyKey, releaseId, request, "CAPABILITY_PACK_RELEASE_START_CANARY",
                "DRAFT", "CANARY", "CapabilityPackReleaseCanaryStarted",
                "set lifecycle_status = 'CANARY', canary_started_at = now(), row_version = row_version + 1");
    }

    CapabilityPackReleaseWire promote(
            ClinicalIdentity identity, String idempotencyKey, UUID releaseId,
            CapabilityPackReleaseTransitionRequestWire request) {
        return transactions.execute(status -> {
            beginCommand(identity, "CAPABILITY_PACK_RELEASE_PROMOTE", idempotencyKey,
                    sha256(releaseId + "|" + request.expectedRowVersion()));
            ReleaseHead head = lockRelease(identity.tenantId(), releaseId);
            requireVersion(head, request.expectedRowVersion());
            requireState(head, "CANARY", "Only a canary release can be promoted");
            jdbc.sql("""
                    update capability_pack_release
                    set lifecycle_status = 'RETIRED', retired_at = now(), row_version = row_version + 1
                    where tenant_id = :tenant and capability_pack_id = :pack and lifecycle_status = 'ACTIVE'
                    """).param("tenant", identity.tenantId()).param("pack", head.capabilityPackId()).update();
            jdbc.sql("""
                    update capability_pack_release
                    set lifecycle_status = 'ACTIVE', promoted_at = now(), row_version = row_version + 1
                    where tenant_id = :tenant and release_id = :release and row_version = :expected
                    """).param("tenant", identity.tenantId()).param("release", releaseId)
                    .param("expected", head.rowVersion()).update();
            appendEvidence(identity, releaseId, "CAPABILITY_PACK_RELEASE_PROMOTED", "CapabilityPackReleasePromoted");
            completeCommand(identity, "CAPABILITY_PACK_RELEASE_PROMOTE", idempotencyKey, releaseId);
            return release(identity.tenantId(), releaseId);
        });
    }

    CapabilityPackReleaseWire retire(
            ClinicalIdentity identity, String idempotencyKey, UUID releaseId,
            CapabilityPackReleaseTransitionRequestWire request) {
        return transition(identity, idempotencyKey, releaseId, request, "CAPABILITY_PACK_RELEASE_RETIRE",
                "ACTIVE", "RETIRED", "CapabilityPackReleaseRetired",
                "set lifecycle_status = 'RETIRED', retired_at = now(), row_version = row_version + 1");
    }

    CapabilityPackReleaseWire rollback(
            ClinicalIdentity identity, String idempotencyKey, UUID releaseId,
            CapabilityPackReleaseRollbackRequestWire request) {
        return transactions.execute(status -> {
            beginCommand(identity, "CAPABILITY_PACK_RELEASE_ROLLBACK", idempotencyKey,
                    sha256(releaseId + "|" + request.expectedRowVersion()));
            ReleaseHead head = lockRelease(identity.tenantId(), releaseId);
            requireVersion(head, request.expectedRowVersion());
            requireState(head, "CANARY", "Only a canary release can be rolled back");
            String reason = requireText(request.rollbackReason(), 2, "rollback_reason");
            jdbc.sql("""
                    update capability_pack_release
                    set lifecycle_status = 'ROLLED_BACK', rollback_reason = :reason, row_version = row_version + 1
                    where tenant_id = :tenant and release_id = :release and row_version = :expected
                    """).param("tenant", identity.tenantId()).param("release", releaseId)
                    .param("reason", reason).param("expected", head.rowVersion()).update();
            appendEvidence(identity, releaseId, "CAPABILITY_PACK_RELEASE_ROLLED_BACK", "CapabilityPackReleaseRolledBack");
            completeCommand(identity, "CAPABILITY_PACK_RELEASE_ROLLBACK", idempotencyKey, releaseId);
            return release(identity.tenantId(), releaseId);
        });
    }

    List<CapabilityPackReleaseWire> listReleases(ClinicalIdentity identity, UUID capabilityPackId) {
        List<UUID> ids = capabilityPackId == null
                ? jdbc.sql("""
                        select release_id from capability_pack_release
                        where tenant_id = :tenant order by released_at desc, release_id desc limit 500
                        """).param("tenant", identity.tenantId()).query(UUID.class).list()
                : jdbc.sql("""
                        select release_id from capability_pack_release
                        where tenant_id = :tenant and capability_pack_id = :pack
                        order by released_at desc, release_id desc limit 500
                        """).param("tenant", identity.tenantId()).param("pack", capabilityPackId)
                        .query(UUID.class).list();
        return ids.stream().map(id -> release(identity.tenantId(), id)).toList();
    }

    private CapabilityPackReleaseWire transition(
            ClinicalIdentity identity, String idempotencyKey, UUID releaseId,
            CapabilityPackReleaseTransitionRequestWire request, String scope,
            String fromStatus, String toStatus, String eventType, String setClause) {
        return transactions.execute(status -> {
            beginCommand(identity, scope, idempotencyKey,
                    sha256(releaseId + "|" + request.expectedRowVersion()));
            ReleaseHead head = lockRelease(identity.tenantId(), releaseId);
            requireVersion(head, request.expectedRowVersion());
            requireState(head, fromStatus,
                    "Only a " + fromStatus.toLowerCase() + " release can transition to " + toStatus.toLowerCase());
            jdbc.sql("update capability_pack_release "
                    + setClause
                    + " where tenant_id = :tenant and release_id = :release and row_version = :expected")
                    .param("tenant", identity.tenantId()).param("release", releaseId)
                    .param("expected", head.rowVersion()).update();
            appendEvidence(identity, releaseId, scope, eventType);
            completeCommand(identity, scope, idempotencyKey, releaseId);
            return release(identity.tenantId(), releaseId);
        });
    }

    private CapabilityPackReleaseWire release(UUID tenantId, UUID releaseId) {
        return jdbc.sql("""
                select release_id, capability_pack_id, release_version, lifecycle_status,
                  canary_started_at, promoted_at, retired_at, rollback_reason, released_by, released_at, row_version
                from capability_pack_release
                where tenant_id = :tenant and release_id = :release
                """).param("tenant", tenantId).param("release", releaseId)
                .query((rs, row) -> new CapabilityPackReleaseWire(
                        rs.getObject("release_id", UUID.class),
                        rs.getObject("capability_pack_id", UUID.class),
                        rs.getString("release_version"),
                        CapabilityPackReleaseWire.LifecycleStatusValue.valueOf(rs.getString("lifecycle_status")),
                        instantOrNull(rs, "canary_started_at"),
                        instantOrNull(rs, "promoted_at"),
                        instantOrNull(rs, "retired_at"),
                        rs.getString("rollback_reason"),
                        rs.getObject("released_by", UUID.class),
                        rs.getObject("released_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("row_version")))
                .optional().orElseThrow(CapabilityPackReleaseService::contextDenied);
    }

    private void requireActivePack(UUID tenantId, UUID packId) {
        String status = jdbc.sql("""
                select status from capability_pack where tenant_id = :tenant and capability_pack_id = :pack for update
                """).param("tenant", tenantId).param("pack", packId)
                .query(String.class).optional().orElseThrow(CapabilityPackReleaseService::contextDenied);
        if (!"ACTIVE".equals(status)) {
            throw new CapabilityPackReleaseException(
                    "CAPABILITY_PACK_NOT_ACTIVE", 409, "Only an active capability pack can be released");
        }
    }

    private ReleaseHead lockRelease(UUID tenantId, UUID releaseId) {
        return jdbc.sql("""
                select capability_pack_id, lifecycle_status, row_version from capability_pack_release
                where tenant_id = :tenant and release_id = :release for update
                """).param("tenant", tenantId).param("release", releaseId)
                .query((rs, row) -> new ReleaseHead(
                        rs.getObject("capability_pack_id", UUID.class),
                        rs.getString("lifecycle_status"), rs.getLong("row_version")))
                .optional().orElseThrow(CapabilityPackReleaseService::contextDenied);
    }

    private static void requireVersion(ReleaseHead head, Long expected) {
        if (expected == null || head.rowVersion() != expected) {
            throw new CapabilityPackReleaseException(
                    "CAPABILITY_PACK_RELEASE_VERSION_CONFLICT", 409, "The release changed; reload before retrying");
        }
    }

    private static void requireState(ReleaseHead head, String expected, String message) {
        if (!expected.equals(head.status())) {
            throw new CapabilityPackReleaseException(
                    "CAPABILITY_PACK_RELEASE_STATE_INVALID", 409, message);
        }
    }

    private static Instant instantOrNull(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new CapabilityPackReleaseException("INVALID_IDEMPOTENCY_KEY", 400,
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
            throw new CapabilityPackReleaseException("IDEMPOTENCY_REPLAY", 409,
                    "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID releaseId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", releaseId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, UUID releaseId, String action, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + releaseId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'CAPABILITY_PACK_RELEASE', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", releaseId)
                .param("patient_hash", sha256(identity.tenantId() + "|null"))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'CAPABILITY_PACK_RELEASE', :aggregate, 1, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", releaseId).param("event_type", eventType).update();
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw invalid(field + " must be at least " + minLength + " characters");
        }
        return value.trim();
    }

    private static CapabilityPackReleaseException invalid(String message) {
        return new CapabilityPackReleaseException("CAPABILITY_PACK_RELEASE_REQUEST_INVALID", 400, message);
    }

    static CapabilityPackReleaseException contextDenied() {
        return new CapabilityPackReleaseException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested capability pack release context is not permitted");
    }

    private record ReleaseHead(UUID capabilityPackId, String status, long rowVersion) {}

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
