package org.haoemr.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.haoemr.contracts.KnowledgeDocumentCreateRequestWire;
import org.haoemr.contracts.KnowledgeDocumentVersionWire;
import org.haoemr.contracts.KnowledgeDocumentWire;
import org.haoemr.contracts.KnowledgeVersionCreateRequestWire;
import org.haoemr.contracts.KnowledgeVersionPublishRequestWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class KnowledgeDocumentService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    KnowledgeDocumentService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    KnowledgeDocumentWire createDocument(
            ClinicalIdentity identity, String idempotencyKey, KnowledgeDocumentCreateRequestWire request) {
        String code = requireText(request.documentCode(), 2, "document_code");
        String title = requireText(request.title(), 2, "title");
        if (request.contentType() == null) throw invalid("content_type is required");
        if (request.classification() == null) throw invalid("classification is required");
        return transactions.execute(status -> {
            beginCommand(identity, "KNOWLEDGE_DOCUMENT_CREATE", idempotencyKey, sha256(code));
            UUID documentId = UUID.randomUUID();
            jdbc.sql("""
                    insert into knowledge_document(
                      tenant_id, document_id, document_code, content_type, title, source_authority,
                      license, classification, effective_from, effective_to)
                    values (:tenant, :document, :code, :type, :title, :authority, :license, :class,
                      :from, :to)
                    """).param("tenant", identity.tenantId()).param("document", documentId)
                    .param("code", code).param("type", request.contentType().name())
                    .param("title", title).param("authority", request.sourceAuthority())
                    .param("license", request.license()).param("class", request.classification().name())
                    .param("from", request.effectiveFrom() == null ? null
                            : request.effectiveFrom().atOffset(ZoneOffset.UTC))
                    .param("to", request.effectiveTo() == null ? null
                            : request.effectiveTo().atOffset(ZoneOffset.UTC)).update();
            appendEvidence(identity, documentId, "KNOWLEDGE_DOCUMENT_CREATED", "KnowledgeDocumentCreated");
            completeCommand(identity, "KNOWLEDGE_DOCUMENT_CREATE", idempotencyKey, documentId);
            return document(identity.tenantId(), documentId);
        });
    }

    List<KnowledgeDocumentWire> listDocuments(ClinicalIdentity identity, String contentType) {
        List<UUID> ids = (contentType == null || contentType.isBlank())
                ? jdbc.sql("""
                        select document_id from knowledge_document
                        where tenant_id = :tenant order by title, document_id limit 500
                        """).param("tenant", identity.tenantId()).query(UUID.class).list()
                : jdbc.sql("""
                        select document_id from knowledge_document
                        where tenant_id = :tenant and content_type = :type
                        order by title, document_id limit 500
                        """).param("tenant", identity.tenantId()).param("type", contentType)
                        .query(UUID.class).list();
        return ids.stream().map(id -> document(identity.tenantId(), id)).toList();
    }

    KnowledgeDocumentVersionWire createVersion(
            ClinicalIdentity identity, String idempotencyKey, UUID documentId,
            KnowledgeVersionCreateRequestWire request) {
        String markdown = requireText(request.markdown(), 1, "markdown");
        String contentHash = sha256(markdown);
        return transactions.execute(status -> {
            beginCommand(identity, "KNOWLEDGE_VERSION_CREATE", idempotencyKey,
                    sha256(documentId + "|" + contentHash));
            jdbc.sql("""
                    select document_id from knowledge_document
                    where tenant_id = :tenant and document_id = :document for update
                    """).param("tenant", identity.tenantId()).param("document", documentId)
                    .query(UUID.class).optional().orElseThrow(KnowledgeDocumentService::contextDenied);
            UUID versionId = UUID.randomUUID();
            String version = String.valueOf(nextVersion(identity.tenantId(), documentId));
            jdbc.sql("""
                    insert into knowledge_document_version(
                      tenant_id, doc_version_id, document_id, version, content_hash, markdown, status)
                    values (:tenant, :version, :document, :v, :hash, :markdown, 'DRAFT')
                    """).param("tenant", identity.tenantId()).param("version", versionId)
                    .param("document", documentId).param("v", version)
                    .param("hash", contentHash).param("markdown", markdown).update();
            insertChunk(identity.tenantId(), versionId, markdown, contentHash);
            appendEvidence(identity, versionId, "KNOWLEDGE_VERSION_CREATED", "KnowledgeVersionCreated");
            completeCommand(identity, "KNOWLEDGE_VERSION_CREATE", idempotencyKey, versionId);
            return version(identity.tenantId(), versionId);
        });
    }

    KnowledgeDocumentVersionWire submitVersion(
            ClinicalIdentity identity, String idempotencyKey, UUID versionId) {
        return transactions.execute(status -> {
            beginCommand(identity, "KNOWLEDGE_VERSION_SUBMIT", idempotencyKey, sha256(versionId.toString()));
            transition(identity, versionId, "DRAFT", "IN_REVIEW", "KNOWLEDGE_VERSION_SUBMITTED",
                    "KnowledgeVersionSubmitted");
            completeCommand(identity, "KNOWLEDGE_VERSION_SUBMIT", idempotencyKey, versionId);
            return version(identity.tenantId(), versionId);
        });
    }

    KnowledgeDocumentVersionWire publishVersion(
            ClinicalIdentity identity, String idempotencyKey, UUID versionId,
            KnowledgeVersionPublishRequestWire request) {
        return transactions.execute(status -> {
            beginCommand(identity, "KNOWLEDGE_VERSION_PUBLISH", idempotencyKey, sha256(versionId.toString()));
            VersionHead head = lockVersion(identity.tenantId(), versionId);
            if (!("IN_REVIEW".equals(head.status()) || "APPROVED".equals(head.status()))) {
                throw new KnowledgeException("KNOWLEDGE_VERSION_STATE_INVALID", 409,
                        "Only a reviewed or approved version can be published");
            }
            jdbc.sql("""
                    update knowledge_document_version set status = 'RETIRED', effective_to = now()
                    where tenant_id = :tenant and document_id = :document and status = 'ACTIVE'
                    """).param("tenant", identity.tenantId()).param("document", head.documentId()).update();
            jdbc.sql("""
                    update knowledge_document_version set status = 'ACTIVE',
                      effective_from = :from, published_by = :published_by,
                      effective_to = null, row_version = row_version + 1
                    where tenant_id = :tenant and doc_version_id = :version
                    """).param("from", request.effectiveFrom() == null ? OffsetDateTime.now()
                            : request.effectiveFrom().atOffset(ZoneOffset.UTC))
                    .param("published_by", identity.userId()).param("tenant", identity.tenantId())
                    .param("version", versionId).update();
            appendEvidence(identity, versionId, "KNOWLEDGE_VERSION_PUBLISHED", "KnowledgeVersionPublished");
            completeCommand(identity, "KNOWLEDGE_VERSION_PUBLISH", idempotencyKey, versionId);
            return version(identity.tenantId(), versionId);
        });
    }

    KnowledgeDocumentVersionWire retireVersion(
            ClinicalIdentity identity, String idempotencyKey, UUID versionId) {
        return transactions.execute(status -> {
            beginCommand(identity, "KNOWLEDGE_VERSION_RETIRE", idempotencyKey, sha256(versionId.toString()));
            transition(identity, versionId, "ACTIVE", "RETIRED", "KNOWLEDGE_VERSION_RETIRED",
                    "KnowledgeVersionRetired");
            completeCommand(identity, "KNOWLEDGE_VERSION_RETIRE", idempotencyKey, versionId);
            return version(identity.tenantId(), versionId);
        });
    }

    private void transition(ClinicalIdentity identity, UUID versionId, String from, String to,
                            String action, String eventType) {
        VersionHead head = lockVersion(identity.tenantId(), versionId);
        if (!from.equals(head.status())) {
            throw new KnowledgeException("KNOWLEDGE_VERSION_STATE_INVALID", 409,
                    "Only a " + from.toLowerCase() + " version can transition to " + to.toLowerCase());
        }
        jdbc.sql("""
                update knowledge_document_version set status = :to,
                  effective_to = case when :to = 'RETIRED' then now() else effective_to end,
                  row_version = row_version + 1
                where tenant_id = :tenant and doc_version_id = :version
                """).param("to", to).param("tenant", identity.tenantId()).param("version", versionId).update();
        appendEvidence(identity, versionId, action, eventType);
    }

    private VersionHead lockVersion(UUID tenantId, UUID versionId) {
        return jdbc.sql("""
                select document_id, status from knowledge_document_version
                where tenant_id = :tenant and doc_version_id = :version for update
                """).param("tenant", tenantId).param("version", versionId)
                .query((rs, row) -> new VersionHead(
                        rs.getObject("document_id", UUID.class), rs.getString("status")))
                .optional().orElseThrow(KnowledgeDocumentService::contextDenied);
    }

    private void insertChunk(UUID tenantId, UUID versionId, String markdown, String contentHash) {
        jdbc.sql("""
                insert into knowledge_chunk(
                  tenant_id, chunk_id, doc_version_id, text, token_count, content_hash)
                values (:tenant, :chunk, :version, :text, :tokens, :hash)
                """).param("tenant", tenantId).param("chunk", UUID.randomUUID())
                .param("version", versionId).param("text", markdown)
                .param("tokens", markdown.length() / 4 + 1).param("hash", contentHash).update();
    }

    private int nextVersion(UUID tenantId, UUID documentId) {
        Integer count = jdbc.sql("""
                select count(*) from knowledge_document_version
                where tenant_id = :tenant and document_id = :document
                """).param("tenant", tenantId).param("document", documentId).query(Integer.class).single();
        return (count == null ? 0 : count) + 1;
    }

    private KnowledgeDocumentWire document(UUID tenantId, UUID documentId) {
        return jdbc.sql("""
                select document_id, document_code, content_type, title, source_authority, license,
                  classification, effective_from, effective_to, row_version, created_at, updated_at
                from knowledge_document where tenant_id = :tenant and document_id = :document
                """).param("tenant", tenantId).param("document", documentId)
                .query((rs, row) -> new KnowledgeDocumentWire(
                        rs.getObject("document_id", UUID.class), rs.getString("document_code"),
                        KnowledgeDocumentWire.ContentTypeValue.valueOf(rs.getString("content_type")),
                        rs.getString("title"), rs.getString("source_authority"), rs.getString("license"),
                        KnowledgeDocumentWire.ClassificationValue.valueOf(rs.getString("classification")),
                        instant(rs, "effective_from"), instant(rs, "effective_to"),
                        rs.getLong("row_version"), instant(rs, "created_at"), instant(rs, "updated_at")))
                .optional().orElseThrow(KnowledgeDocumentService::contextDenied);
    }

    private KnowledgeDocumentVersionWire version(UUID tenantId, UUID versionId) {
        return jdbc.sql("""
                select doc_version_id, document_id, version, content_hash, markdown, status,
                  effective_from, effective_to, published_by, row_version, created_at
                from knowledge_document_version where tenant_id = :tenant and doc_version_id = :version
                """).param("tenant", tenantId).param("version", versionId)
                .query((rs, row) -> new KnowledgeDocumentVersionWire(
                        rs.getObject("doc_version_id", UUID.class), rs.getObject("document_id", UUID.class),
                        rs.getString("version"), rs.getString("content_hash"), rs.getString("markdown"),
                        Map.of(),
                        KnowledgeDocumentVersionWire.StatusValue.valueOf(rs.getString("status")),
                        instant(rs, "effective_from"), instant(rs, "effective_to"),
                        rs.getObject("published_by", UUID.class), rs.getLong("row_version"),
                        instant(rs, "created_at")))
                .optional().orElseThrow(KnowledgeDocumentService::contextDenied);
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        return rs.getObject(column, OffsetDateTime.class) == null
                ? null : rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new KnowledgeException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new KnowledgeException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID resourceId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", resourceId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, UUID resourceId, String action, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + resourceId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'KNOWLEDGE_DOCUMENT', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", resourceId)
                .param("patient_hash", sha256(identity.tenantId() + "|null"))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'KNOWLEDGE_DOCUMENT', :aggregate, 1, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", resourceId).param("event_type", eventType).update();
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw invalid(field + " must be at least " + minLength + " characters");
        }
        return value.trim();
    }

    private static KnowledgeException invalid(String message) {
        return new KnowledgeException("KNOWLEDGE_REQUEST_INVALID", 400, message);
    }

    static KnowledgeException contextDenied() {
        return new KnowledgeException("CONTEXT_NOT_PERMITTED", 403,
                "The requested knowledge document context is not permitted");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record VersionHead(UUID documentId, String status) {}
}
