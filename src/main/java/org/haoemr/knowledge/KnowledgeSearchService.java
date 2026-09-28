package org.haoemr.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.haoemr.contracts.KnowledgeFeedbackCreateRequestWire;
import org.haoemr.contracts.KnowledgeChunkCitationWire;
import org.haoemr.contracts.KnowledgeReferenceWire;
import org.haoemr.contracts.KnowledgeSearchRequestWire;
import org.haoemr.contracts.KnowledgeSearchResultWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class KnowledgeSearchService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    KnowledgeSearchService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    KnowledgeSearchResultWire search(ClinicalIdentity identity, KnowledgeSearchRequestWire request) {
        String query = requireText(request.query(), 1, "query");
        int limit = request.limit() == null ? 20 : Math.min(Math.max(request.limit(), 1), 50);
        String like = "%" + query + "%";
        String watermark = identity.tenantId() + ":" + Instant.now().toString();
        List<SearchRow> rows = jdbc.sql("""
                select v.doc_version_id, v.version, v.content_hash, d.title, d.document_code,
                  c.section_path, c.text
                from knowledge_chunk c
                join knowledge_document_version v on v.tenant_id = c.tenant_id and v.doc_version_id = c.doc_version_id
                join knowledge_document d on d.tenant_id = v.tenant_id and d.document_id = v.document_id
                where c.tenant_id = :tenant and v.status = 'ACTIVE'
                  and d.classification in ('PUBLIC', 'INTERNAL')
                  and (d.effective_from is null or d.effective_from <= now())
                  and (d.effective_to is null or d.effective_to > now())
                  and (v.effective_from is null or v.effective_from <= now())
                  and (v.effective_to is null or v.effective_to > now())
                  and (cast(:type as varchar) is null or d.content_type = cast(:type as varchar))
                  and (c.text ilike :like or d.title ilike :like or d.document_code ilike :like)
                order by case when d.title ilike :like then 0 else 1 end, v.created_at desc
                limit :limit
                """).param("tenant", identity.tenantId())
                .param("type", request.contentType() == null ? null : request.contentType().name())
                .param("like", like).param("limit", limit)
                .query((rs, row) -> new SearchRow(
                        rs.getObject("doc_version_id", UUID.class).toString(),
                        rs.getString("version"), rs.getString("content_hash"),
                        rs.getString("title"), rs.getString("section_path"), rs.getString("text")))
                .list();
        List<KnowledgeReferenceWire> references = new ArrayList<>();
        for (SearchRow row : rows) {
            references.add(new KnowledgeReferenceWire(
                    "DOCUMENT_VERSION", row.docVersionId(), row.version(),
                    Map.of("section_path", row.sectionPath() == null ? "" : row.sectionPath()),
                    row.contentHash(), excerpt(row.text(), query), 0.8,
                    List.of("EXACT"), watermark, Instant.now()));
        }
        recordLog(identity, query, watermark);
        return new KnowledgeSearchResultWire(references);
    }

    KnowledgeChunkCitationWire citation(ClinicalIdentity identity, UUID chunkId) {
        return jdbc.sql("""
                select c.chunk_id, c.doc_version_id, v.version, v.content_hash, d.title,
                  coalesce(c.section_path, '') as section_path, c.text, d.classification,
                  greatest(d.effective_from, v.effective_from) as effective_from,
                  least(d.effective_to, v.effective_to) as effective_to
                from knowledge_chunk c
                join knowledge_document_version v on v.tenant_id = c.tenant_id and v.doc_version_id = c.doc_version_id
                join knowledge_document d on d.tenant_id = v.tenant_id and d.document_id = v.document_id
                where c.tenant_id = :tenant and c.chunk_id = :chunk and v.status = 'ACTIVE'
                  and d.classification in ('PUBLIC', 'INTERNAL')
                  and (d.effective_from is null or d.effective_from <= now())
                  and (d.effective_to is null or d.effective_to > now())
                  and (v.effective_from is null or v.effective_from <= now())
                  and (v.effective_to is null or v.effective_to > now())
                """).param("tenant", identity.tenantId()).param("chunk", chunkId)
                .query((rs, row) -> new KnowledgeChunkCitationWire(
                        rs.getObject("chunk_id", UUID.class), rs.getObject("doc_version_id", UUID.class),
                        rs.getString("version"), rs.getString("title"), rs.getString("section_path"),
                        rs.getString("text"), rs.getString("content_hash"), rs.getString("classification"),
                        rs.getObject("effective_from", OffsetDateTime.class) == null ? null : rs.getObject("effective_from", OffsetDateTime.class).toInstant(),
                        rs.getObject("effective_to", OffsetDateTime.class) == null ? null : rs.getObject("effective_to", OffsetDateTime.class).toInstant()))
                .optional().orElseThrow(() -> new KnowledgeException("KNOWLEDGE_SOURCE_UNAVAILABLE", 404,
                        "当前权限范围内该来源不可用，可能已失效或撤回。请重新检索并核对依据。"));
    }

    void createFeedback(
            ClinicalIdentity identity, String idempotencyKey, KnowledgeFeedbackCreateRequestWire request) {
        if (request.disposition() == null) throw invalid("disposition is required");
        transactions.execute(status -> {
            beginCommand(identity, "KNOWLEDGE_FEEDBACK_CREATE", idempotencyKey,
                    sha256(String.valueOf(request.docVersionId() == null ? "" : request.docVersionId())
                            + "|" + request.disposition()));
            jdbc.sql("""
                    insert into knowledge_feedback(
                      tenant_id, feedback_id, use_case, doc_version_id, source_ref, disposition, comment, actor_user_id)
                    values (:tenant, :feedback, :use_case, :version, :source_ref, :disposition, :comment, :actor)
                    """).param("tenant", identity.tenantId()).param("feedback", UUID.randomUUID())
                    .param("use_case", request.useCase()).param("version", request.docVersionId())
                    .param("source_ref", request.sourceRef()).param("disposition", request.disposition().name())
                    .param("comment", request.comment()).param("actor", identity.userId()).update();
            completeCommand(identity, "KNOWLEDGE_FEEDBACK_CREATE", idempotencyKey, UUID.randomUUID());
            return null;
        });
    }

    private void recordLog(ClinicalIdentity identity, String query, String watermark) {
        jdbc.sql("""
                insert into knowledge_retrieval_log(
                  tenant_id, log_id, use_case, query_hash, actor_user_id, authorization_watermark)
                values (:tenant, :log, :use_case, :hash, :actor, :watermark)
                """).param("tenant", identity.tenantId()).param("log", UUID.randomUUID())
                .param("use_case", "knowledge_search").param("hash", sha256(query))
                .param("actor", identity.userId()).param("watermark", watermark).update();
    }

    private static String excerpt(String text, String query) {
        if (text == null) return "";
        int idx = text.toLowerCase().indexOf(query.toLowerCase());
        int start = idx < 0 ? 0 : Math.max(0, idx - 40);
        int end = Math.min(text.length(), start + 200);
        return (start > 0 ? "…" : "") + text.substring(start, end) + (end < text.length() ? "…" : "");
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

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw invalid(field + " must be at least " + minLength + " characters");
        }
        return value.trim();
    }

    private static KnowledgeException invalid(String message) {
        return new KnowledgeException("KNOWLEDGE_REQUEST_INVALID", 400, message);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record SearchRow(String docVersionId, String version, String contentHash, String title,
                             String sectionPath, String text) {}
}
