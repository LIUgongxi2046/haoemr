package org.haoemr.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.haoemr.contracts.KnowledgeImportBatchWire;
import org.haoemr.contracts.KnowledgeImportRequestWire;
import org.haoemr.contracts.KnowledgeSourceRegisterRequestWire;
import org.haoemr.contracts.KnowledgeSourceWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class KnowledgeSourceService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    // 选择矩阵（分类级）：只读选择性抽取，排除七巧板/HiTA/考试科普等第三方授权或非临床内容。
    private static final List<String> EXCLUDED_PATH_MARKERS =
            List.of("七巧板", "HiTA", "hita", "考试科普", "206_", ".DS_Store");
    private static final List<String> INCLUDED_TOP_DIRS = List.of("医学结构化数据", "知识详情");

    KnowledgeSourceService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    KnowledgeSourceWire register(
            ClinicalIdentity identity, String idempotencyKey, KnowledgeSourceRegisterRequestWire request) {
        String code = requireText(request.sourceCode(), 2, "source_code");
        String name = requireText(request.sourceName(), 2, "source_name");
        if (request.sourceKind() == null) throw invalid("source_kind is required");
        if (request.sensitivity() == null) throw invalid("sensitivity is required");
        return transactions.execute(status -> {
            beginCommand(identity, "KNOWLEDGE_SOURCE_REGISTER", idempotencyKey, sha256(code));
            UUID sourceId = UUID.randomUUID();
            jdbc.sql("""
                    insert into knowledge_source_registry(
                      tenant_id, source_id, source_code, source_name, source_kind, source_path,
                      license, allowed_use, sensitivity, update_frequency, checksum, status)
                    values (:tenant, :source, :code, :name, :kind, :path, :license, :use, :sens,
                      :freq, :checksum, 'REGISTERED')
                    """).param("tenant", identity.tenantId()).param("source", sourceId)
                    .param("code", code).param("name", name)
                    .param("kind", request.sourceKind().name())
                    .param("path", request.sourcePath()).param("license", request.license())
                    .param("use", request.allowedUse()).param("sens", request.sensitivity().name())
                    .param("freq", request.updateFrequency()).param("checksum", request.checksum()).update();
            appendEvidence(identity, sourceId, "KNOWLEDGE_SOURCE_REGISTERED", "KnowledgeSourceRegistered");
            completeCommand(identity, "KNOWLEDGE_SOURCE_REGISTER", idempotencyKey, sourceId);
            return source(identity.tenantId(), sourceId);
        });
    }

    List<KnowledgeSourceWire> listSources(ClinicalIdentity identity) {
        List<UUID> ids = jdbc.sql("""
                select source_id from knowledge_source_registry
                where tenant_id = :tenant order by source_code, source_id limit 500
                """).param("tenant", identity.tenantId()).query(UUID.class).list();
        return ids.stream().map(id -> source(identity.tenantId(), id)).toList();
    }

    KnowledgeImportBatchWire importSource(
            ClinicalIdentity identity, String idempotencyKey, UUID sourceId, KnowledgeImportRequestWire request) {
        String matrixVersion = requireText(request.selectionMatrixVersion(), 1, "selection_matrix_version");
        return transactions.execute(status -> {
            beginCommand(identity, "KNOWLEDGE_SOURCE_IMPORT", idempotencyKey,
                    sha256(sourceId + "|" + matrixVersion));
            SourceHead head = jdbc.sql("""
                    select source_path, status from knowledge_source_registry
                    where tenant_id = :tenant and source_id = :source for update
                    """).param("tenant", identity.tenantId()).param("source", sourceId)
                    .query((rs, row) -> new SourceHead(rs.getString("source_path"), rs.getString("status")))
                    .optional().orElseThrow(KnowledgeSourceService::contextDenied);
            if (!("REGISTERED".equals(head.status()) || "ACTIVE".equals(head.status()))) {
                throw new KnowledgeException("KNOWLEDGE_SOURCE_STATE_INVALID", 409,
                        "Only a registered or active source can be imported");
            }
            if (head.sourcePath() == null || head.sourcePath().isBlank()) {
                throw invalid("source_path is required for OBSIDIAN_VAULT import");
            }
            UUID batchId = UUID.randomUUID();
            jdbc.sql("""
                    insert into knowledge_import_batch(
                      tenant_id, batch_id, source_id, source_root, selection_matrix_version,
                      source_manifest_hash, mode, status, operator)
                    values (:tenant, :batch, :source, :root, :matrix, :manifest, 'READ_ONLY', 'RUNNING', :operator)
                    """).param("tenant", identity.tenantId()).param("batch", batchId)
                    .param("source", sourceId).param("root", head.sourcePath())
                    .param("matrix", matrixVersion).param("manifest", "")
                    .param("operator", identity.userId()).update();

            ScanResult scan = scanReadOnly(head.sourcePath());
            String manifestHash = sha256(scan.included().stream()
                    .map(f -> f.relativePath() + "|" + f.contentHash())
                    .collect(Collectors.joining("\n")));
            long imported = 0;
            for (SourceFile file : scan.included()) {
                importFile(identity, sourceId, batchId, head.sourcePath(), file);
                imported++;
            }
            jdbc.sql("""
                    update knowledge_import_batch set
                      source_manifest_hash = :manifest, imported_row_count = :imported,
                      skipped_row_count = :skipped, status = 'COMPLETED'
                    where tenant_id = :tenant and batch_id = :batch
                    """).param("manifest", manifestHash).param("imported", imported)
                    .param("skipped", scan.skipped()).param("tenant", identity.tenantId())
                    .param("batch", batchId).update();
            appendEvidence(identity, batchId, "KNOWLEDGE_SOURCE_IMPORTED", "KnowledgeSourceImported");
            completeCommand(identity, "KNOWLEDGE_SOURCE_IMPORT", idempotencyKey, batchId);
            return batch(identity.tenantId(), batchId);
        });
    }

    List<KnowledgeImportBatchWire> listImports(ClinicalIdentity identity, UUID sourceId) {
        List<UUID> ids = jdbc.sql("""
                select batch_id from knowledge_import_batch
                where tenant_id = :tenant and source_id = :source
                order by imported_at desc, batch_id desc limit 100
                """).param("tenant", identity.tenantId()).param("source", sourceId).query(UUID.class).list();
        return ids.stream().map(id -> batch(identity.tenantId(), id)).toList();
    }

    // 只读扫描源目录，按选择矩阵过滤，返回纳入文件与排除计数。
    private ScanResult scanReadOnly(String sourceRoot) {
        Path root = Path.of(sourceRoot);
        if (!Files.isDirectory(root)) {
            throw invalid("source_path is not a readable directory: " + sourceRoot);
        }
        List<SourceFile> included = new ArrayList<>();
        long skipped = 0;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (!relative.endsWith(".md")) continue;
                if (included(relative)) {
                    included.add(new SourceFile(relative, sha256File(path)));
                } else {
                    skipped++;
                }
            }
        } catch (IOException failure) {
            throw new KnowledgeException("KNOWLEDGE_SOURCE_UNREADABLE", 400,
                    "Failed to read source directory: " + failure.getMessage());
        }
        included.sort(Comparator.comparing(SourceFile::relativePath));
        return new ScanResult(included, skipped);
    }

    private boolean included(String relativePath) {
        if (!relativePath.endsWith(".md")) return false;
        for (String marker : EXCLUDED_PATH_MARKERS) {
            if (relativePath.contains(marker)) return false;
        }
        for (String topDir : INCLUDED_TOP_DIRS) {
            if (relativePath.startsWith(topDir + "/")) return true;
        }
        return false;
    }

    private boolean importFile(ClinicalIdentity identity, UUID sourceId, UUID batchId,
                               String sourceRoot, SourceFile file) {
        String documentCode = "obsidian." + sha256(file.relativePath()).substring(0, 12);
        String title = titleOf(file.relativePath());
        String contentType = file.relativePath().startsWith("医学结构化数据/") ? "GRAPH_ENTITY" : "CATALOG";
        UUID documentId = findDocumentByCode(identity.tenantId(), documentCode);
        if (documentId == null) {
            documentId = UUID.randomUUID();
            jdbc.sql("""
                    insert into knowledge_document(
                      tenant_id, document_id, document_code, content_type, title, classification)
                    values (:tenant, :document, :code, :type, :title, 'INTERNAL')
                    """).param("tenant", identity.tenantId()).param("document", documentId)
                    .param("code", documentCode).param("type", contentType).param("title", title).update();
        }
        UUID versionId = UUID.randomUUID();
        String version = String.valueOf(nextVersion(identity.tenantId(), documentId));
        String markdown = readUtf8(Path.of(sourceRoot, file.relativePath()));
        jdbc.sql("""
                insert into knowledge_document_version(
                  tenant_id, doc_version_id, document_id, version, content_hash, markdown, status)
                values (:tenant, :version, :document, :v, :hash, :markdown, 'DRAFT')
                """).param("tenant", identity.tenantId()).param("version", versionId)
                .param("document", documentId).param("v", version)
                .param("hash", file.contentHash()).param("markdown", markdown).update();
        insertChunk(identity.tenantId(), versionId, markdown, file.contentHash());
        jdbc.sql("""
                insert into knowledge_source_file(
                  tenant_id, file_id, batch_id, source_path, source_content_hash,
                  entity_category, system, table_name, included)
                values (:tenant, :file, :batch, :path, :hash, :category, :system, :table, true)
                """).param("tenant", identity.tenantId()).param("file", UUID.randomUUID())
                .param("batch", batchId).param("path", file.relativePath())
                .param("hash", file.contentHash()).param("category", categoryOf(file.relativePath()))
                .param("system", systemOf(file.relativePath())).param("table", tableOf(file.relativePath()))
                .update();
        return true;
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

    private UUID findDocumentByCode(UUID tenantId, String documentCode) {
        return jdbc.sql("""
                select document_id from knowledge_document
                where tenant_id = :tenant and document_code = :code
                """).param("tenant", tenantId).param("code", documentCode)
                .query(UUID.class).optional().orElse(null);
    }

    private int nextVersion(UUID tenantId, UUID documentId) {
        Integer count = jdbc.sql("""
                select count(*) from knowledge_document_version
                where tenant_id = :tenant and document_id = :document
                """).param("tenant", tenantId).param("document", documentId).query(Integer.class).single();
        return (count == null ? 0 : count) + 1;
    }

    private static String titleOf(String relativePath) {
        String name = relativePath.substring(relativePath.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String categoryOf(String relativePath) {
        // 取"图谱详情"或"知识详情"后的第一段目录名
        String marker = "/图谱详情/";
        int idx = relativePath.indexOf(marker);
        if (idx < 0) {
            marker = "/知识详情/";
            idx = relativePath.indexOf(marker);
        }
        if (idx < 0) return null;
        String rest = relativePath.substring(idx + marker.length());
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    private static String systemOf(String relativePath) {
        String[] parts = relativePath.split("/");
        return parts.length > 2 ? parts[parts.length - 2] : null;
    }

    private static String tableOf(String relativePath) {
        String name = relativePath.substring(relativePath.lastIndexOf('/') + 1);
        int underscore = name.indexOf('_');
        int dot = name.lastIndexOf('.');
        return underscore > 0 && dot > underscore ? name.substring(underscore + 1, dot) : null;
    }

    private KnowledgeSourceWire source(UUID tenantId, UUID sourceId) {
        return jdbc.sql("""
                select source_id, source_code, source_name, source_kind, source_path, license, allowed_use,
                  sensitivity, update_frequency, checksum, status, created_at, updated_at
                from knowledge_source_registry where tenant_id = :tenant and source_id = :source
                """).param("tenant", tenantId).param("source", sourceId)
                .query((rs, row) -> new KnowledgeSourceWire(
                        rs.getObject("source_id", UUID.class), rs.getString("source_code"),
                        rs.getString("source_name"),
                        KnowledgeSourceWire.SourceKindValue.valueOf(rs.getString("source_kind")),
                        rs.getString("source_path"), rs.getString("license"), rs.getString("allowed_use"),
                        KnowledgeSourceWire.SensitivityValue.valueOf(rs.getString("sensitivity")),
                        rs.getString("update_frequency"), rs.getString("checksum"),
                        KnowledgeSourceWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .optional().orElseThrow(KnowledgeSourceService::contextDenied);
    }

    private KnowledgeImportBatchWire batch(UUID tenantId, UUID batchId) {
        return jdbc.sql("""
                select batch_id, source_id, source_root, selection_matrix_version, source_manifest_hash,
                  mode, imported_row_count, skipped_row_count, status, imported_at, operator
                from knowledge_import_batch where tenant_id = :tenant and batch_id = :batch
                """).param("tenant", tenantId).param("batch", batchId)
                .query((rs, row) -> new KnowledgeImportBatchWire(
                        rs.getObject("batch_id", UUID.class), rs.getObject("source_id", UUID.class),
                        rs.getString("source_root"), rs.getString("selection_matrix_version"),
                        rs.getString("source_manifest_hash"),
                        KnowledgeImportBatchWire.ModeValue.valueOf(rs.getString("mode")),
                        rs.getLong("imported_row_count"), rs.getLong("skipped_row_count"),
                        KnowledgeImportBatchWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getObject("imported_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("operator", UUID.class)))
                .optional().orElseThrow(KnowledgeSourceService::contextDenied);
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
                values (:tenant, :audit, now(), :actor, :action, 'KNOWLEDGE_SOURCE', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", resourceId)
                .param("patient_hash", sha256(identity.tenantId() + "|null"))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'KNOWLEDGE_SOURCE', :aggregate, 1, :event_type, 1,
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
                "The requested knowledge source context is not permitted");
    }

    private static String readUtf8(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new KnowledgeException("KNOWLEDGE_FILE_UNREADABLE", 400,
                    "Failed to read source file " + path + ": " + failure.getMessage());
        }
    }

    private static String sha256File(Path path) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(path)));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new KnowledgeException("KNOWLEDGE_FILE_UNREADABLE", 400,
                    "Failed to hash source file " + path + ": " + failure.getMessage());
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record SourceHead(String sourcePath, String status) {}
    private record SourceFile(String relativePath, String contentHash) {}
    private record ScanResult(List<SourceFile> included, long skipped) {}
}
