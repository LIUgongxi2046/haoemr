package org.haoemr.prompt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.PromptReleasePublishRequestWire;
import org.haoemr.contracts.PromptReleaseRetireRequestWire;
import org.haoemr.contracts.PromptReleaseWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class PromptReleaseService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    PromptReleaseService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    PromptReleaseWire publish(
            ClinicalIdentity identity, String idempotencyKey, PromptReleasePublishRequestWire request) {
        String promptCode = requireText(request.promptCode(), 2, "prompt_code");
        String version = requireText(request.releaseVersion(), 1, "release_version");
        String displayName = requireText(request.displayName(), 2, "display_name");
        String content = requireText(request.content(), 8, "content");
        if (request.effectiveFrom() == null) {
            throw invalid("effective_from is required");
        }
        return transactions.execute(status -> {
            beginCommand(identity, "PROMPT_RELEASE_PUBLISH", idempotencyKey,
                    sha256(promptCode + "|" + version + "|" + content));
            jdbc.sql("""
                    update prompt_release set status = 'RETIRED', effective_to = now()
                    where tenant_id = :tenant and prompt_code = :prompt and status = 'ACTIVE'
                    """).param("tenant", identity.tenantId()).param("prompt", promptCode).update();
            UUID releaseId = UUID.randomUUID();
            jdbc.sql("""
                    insert into prompt_release(
                      tenant_id, prompt_release_id, prompt_code, release_version, display_name,
                      content, status, effective_from, published_by)
                    values (:tenant, :release, :prompt, :version, :display_name,
                      :content, 'ACTIVE', :effective_from, :published_by)
                    """).param("tenant", identity.tenantId()).param("release", releaseId)
                    .param("prompt", promptCode).param("version", version).param("display_name", displayName)
                    .param("content", content)
                    .param("effective_from", request.effectiveFrom().atOffset(ZoneOffset.UTC))
                    .param("published_by", identity.userId()).update();
            appendEvidence(identity, releaseId, "PROMPT_RELEASE_PUBLISHED", "PromptReleasePublished");
            completeCommand(identity, "PROMPT_RELEASE_PUBLISH", idempotencyKey, releaseId);
            return release(identity.tenantId(), releaseId);
        });
    }

    PromptReleaseWire retire(
            ClinicalIdentity identity, String idempotencyKey, UUID releaseId,
            PromptReleaseRetireRequestWire request) {
        return transactions.execute(status -> {
            beginCommand(identity, "PROMPT_RELEASE_RETIRE", idempotencyKey, sha256(releaseId.toString()));
            String currentStatus = jdbc.sql("""
                    select status from prompt_release
                    where tenant_id = :tenant and prompt_release_id = :release for update
                    """).param("tenant", identity.tenantId()).param("release", releaseId)
                    .query(String.class).optional().orElseThrow(PromptReleaseService::contextDenied);
            if (!"ACTIVE".equals(currentStatus)) {
                throw new PromptReleaseException(
                        "PROMPT_RELEASE_STATE_INVALID", 409, "Only an active release can be retired");
            }
            jdbc.sql("""
                    update prompt_release set status = 'RETIRED', effective_to = now()
                    where tenant_id = :tenant and prompt_release_id = :release
                    """).param("tenant", identity.tenantId()).param("release", releaseId).update();
            appendEvidence(identity, releaseId, "PROMPT_RELEASE_RETIRED", "PromptReleaseRetired");
            completeCommand(identity, "PROMPT_RELEASE_RETIRE", idempotencyKey, releaseId);
            return release(identity.tenantId(), releaseId);
        });
    }

    List<PromptReleaseWire> listReleases(ClinicalIdentity identity, String promptCode) {
        return jdbc.sql("""
                select prompt_release_id from prompt_release
                where tenant_id = :tenant and prompt_code = :prompt
                order by created_at desc, prompt_release_id desc limit 100
                """).param("tenant", identity.tenantId()).param("prompt", promptCode)
                .query(UUID.class).list().stream()
                .map(id -> release(identity.tenantId(), id)).toList();
    }

    private PromptReleaseWire release(UUID tenantId, UUID releaseId) {
        return jdbc.sql("""
                select prompt_release_id, prompt_code, release_version, display_name, content,
                  status, effective_from, effective_to, published_by, created_at
                from prompt_release where tenant_id = :tenant and prompt_release_id = :release
                """).param("tenant", tenantId).param("release", releaseId)
                .query((rs, row) -> new PromptReleaseWire(
                        rs.getObject("prompt_release_id", UUID.class), rs.getString("prompt_code"),
                        rs.getString("release_version"), rs.getString("display_name"), rs.getString("content"),
                        PromptReleaseWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getObject("effective_from", OffsetDateTime.class).toInstant(),
                        rs.getObject("effective_to", OffsetDateTime.class) == null
                                ? null : rs.getObject("effective_to", OffsetDateTime.class).toInstant(),
                        rs.getObject("published_by", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional().orElseThrow(PromptReleaseService::contextDenied);
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new PromptReleaseException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new PromptReleaseException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
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
                values (:tenant, :audit, now(), :actor, :action, 'PROMPT_RELEASE', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", releaseId)
                .param("patient_hash", sha256(identity.tenantId() + "|null"))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'PROMPT_RELEASE', :aggregate, 1, :event_type, 1,
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

    private static PromptReleaseException invalid(String message) {
        return new PromptReleaseException("PROMPT_RELEASE_REQUEST_INVALID", 400, message);
    }

    static PromptReleaseException contextDenied() {
        return new PromptReleaseException("CONTEXT_NOT_PERMITTED", 403,
                "The requested prompt release context is not permitted");
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
