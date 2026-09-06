package org.haoemr.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.haoemr.contracts.KnowledgeGraphConceptImportWire;
import org.haoemr.contracts.KnowledgeGraphImportBatchWire;
import org.haoemr.contracts.KnowledgeGraphImportRequestWire;
import org.haoemr.contracts.KnowledgeGraphRelationImportWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The only application writer for the curated knowledge graph.  It intentionally accepts
 * dictionary concepts only: extracted facts need a separate evidence-review workflow before
 * they become graph edges used by clinical knowledge search.
 */
@Service
final class KnowledgeGraphImportService {
    private static final String COMMAND_SCOPE = "KNOWLEDGE_GRAPH_IMPORT";
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    KnowledgeGraphImportService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    KnowledgeGraphImportBatchWire importGraph(
            ClinicalIdentity identity, String idempotencyKey, KnowledgeGraphImportRequestWire request) {
        NormalizedImport command = normalize(request);
        return transactions.execute(status -> {
            Source source = loadUsableSource(identity.tenantId(), command.sourceId());
            requireDocumentVersion(identity.tenantId(), command.sourceDocumentVersionId());
            beginCommand(identity, idempotencyKey, sha256(command.sourceId() + "|"
                    + command.sourceDocumentVersionId() + "|" + command.manifestHash()));

            UUID batchId = UUID.randomUUID();
            jdbc.sql("""
                    insert into knowledge_graph_import_batch(
                      tenant_id, graph_import_batch_id, source_id, source_document_version_id,
                      manifest_hash, status, imported_by)
                    values (:tenant, :batch, :source, :document, :manifest, 'COMPLETED', :operator)
                    """).param("tenant", identity.tenantId()).param("batch", batchId)
                    .param("source", command.sourceId()).param("document", command.sourceDocumentVersionId())
                    .param("manifest", command.manifestHash()).param("operator", identity.userId()).update();

            int importedConcepts = 0;
            for (Concept concept : command.concepts()) {
                importedConcepts += jdbc.sql("""
                        insert into knowledge_concept(
                          tenant_id, concept_id, source_type, source_id, system, code, display,
                          source_graph_import_batch_id)
                        values (:tenant, :concept, 'DICTIONARY', :source_code, :system, :code, :display, :batch)
                        on conflict (tenant_id, source_type, system, code) do nothing
                        """).param("tenant", identity.tenantId()).param("concept", UUID.randomUUID())
                        .param("source_code", source.sourceCode()).param("system", concept.system())
                        .param("code", concept.code()).param("display", concept.display())
                        .param("batch", batchId).update();
            }

            Map<ConceptKey, UUID> conceptIds = resolveConcepts(identity.tenantId(), command.concepts(), command.relations());
            int importedRelations = 0;
            for (Relation relation : command.relations()) {
                UUID fromId = conceptIds.get(relation.from());
                UUID toId = conceptIds.get(relation.to());
                if (fromId == null || toId == null) {
                    throw new KnowledgeException("KNOWLEDGE_GRAPH_ENDPOINT_NOT_FOUND", 422,
                            "Every relation endpoint must be supplied in this import or already exist as a dictionary concept");
                }
                importedRelations += jdbc.sql("""
                        insert into knowledge_relation(
                          tenant_id, relation_id, from_concept, to_concept, rel_type, version,
                          source_graph_import_batch_id)
                        values (:tenant, :relation, :from, :to, :type, :version, :batch)
                        on conflict (tenant_id, from_concept, to_concept, rel_type, coalesce(version, '')) do nothing
                        """).param("tenant", identity.tenantId()).param("relation", UUID.randomUUID())
                        .param("from", fromId).param("to", toId).param("type", relation.type())
                        .param("version", relation.version()).param("batch", batchId).update();
            }
            jdbc.sql("""
                    update knowledge_graph_import_batch
                    set imported_concept_count = :concepts, imported_relation_count = :relations
                    where tenant_id = :tenant and graph_import_batch_id = :batch
                    """).param("concepts", importedConcepts).param("relations", importedRelations)
                    .param("tenant", identity.tenantId()).param("batch", batchId).update();
            appendEvidence(identity, batchId);
            completeCommand(identity, idempotencyKey, batchId);
            return batch(identity.tenantId(), batchId);
        });
    }

    private NormalizedImport normalize(KnowledgeGraphImportRequestWire request) {
        if (request == null || request.sourceId() == null) {
            throw invalid("source_id is required");
        }
        List<KnowledgeGraphConceptImportWire> inputConcepts = request.concepts();
        if (inputConcepts == null || inputConcepts.isEmpty() || inputConcepts.size() > 1000) {
            throw invalid("concepts must contain between 1 and 1000 entries");
        }
        List<KnowledgeGraphRelationImportWire> inputRelations = request.relations() == null ? List.of() : request.relations();
        if (inputRelations.size() > 2000) throw invalid("relations may not exceed 2000 entries");
        List<Concept> concepts = new ArrayList<>();
        Set<ConceptKey> conceptKeys = new HashSet<>();
        for (KnowledgeGraphConceptImportWire raw : inputConcepts) {
            if (raw == null) throw invalid("concept entries may not be null");
            if (raw.sourceType() != null
                    && raw.sourceType() != KnowledgeGraphConceptImportWire.SourceTypeValue.DICTIONARY) {
                throw new KnowledgeException("KNOWLEDGE_GRAPH_SOURCE_TYPE_UNSUPPORTED", 422,
                        "Only reviewed dictionary concepts can be imported into the production knowledge graph");
            }
            Concept concept = new Concept(text(raw.system(), 2, 64, "concept.system"),
                    text(raw.code(), 1, 128, "concept.code"), text(raw.display(), 2, 512, "concept.display"));
            if (!conceptKeys.add(concept.key())) {
                throw new KnowledgeException("KNOWLEDGE_GRAPH_DUPLICATE_CONCEPT", 422,
                        "A graph import may not contain duplicate concept identifiers");
            }
            concepts.add(concept);
        }
        List<Relation> relations = new ArrayList<>();
        Set<String> relationKeys = new HashSet<>();
        for (KnowledgeGraphRelationImportWire raw : inputRelations) {
            if (raw == null || raw.relationType() == null) throw invalid("relation_type is required");
            ConceptKey from = new ConceptKey(text(raw.fromSystem(), 2, 64, "relation.from_system"),
                    text(raw.fromCode(), 1, 128, "relation.from_code"));
            ConceptKey to = new ConceptKey(text(raw.toSystem(), 2, 64, "relation.to_system"),
                    text(raw.toCode(), 1, 128, "relation.to_code"));
            if (from.equals(to)) throw invalid("a relation cannot point to the same concept");
            String version = optionalText(raw.version(), 64, "relation.version");
            Relation relation = new Relation(from, to, raw.relationType().name(), version);
            if (!relationKeys.add(relation.semanticKey())) {
                throw new KnowledgeException("KNOWLEDGE_GRAPH_DUPLICATE_RELATION", 422,
                        "A graph import may not contain duplicate semantic relations");
            }
            relations.add(relation);
        }
        String manifest = manifest(concepts, relations);
        return new NormalizedImport(request.sourceId(), request.sourceDocumentVersionId(), concepts, relations, manifest);
    }

    private Source loadUsableSource(UUID tenantId, UUID sourceId) {
        return jdbc.sql("""
                select source_code, status from knowledge_source_registry
                where tenant_id = :tenant and source_id = :source for update
                """).param("tenant", tenantId).param("source", sourceId)
                .query((rs, row) -> new Source(rs.getString("source_code"), rs.getString("status")))
                .optional().filter(source -> "REGISTERED".equals(source.status()) || "ACTIVE".equals(source.status()))
                .orElseThrow(() -> new KnowledgeException("KNOWLEDGE_SOURCE_STATE_INVALID", 409,
                        "Only a registered or active knowledge source may write graph facts"));
    }

    private void requireDocumentVersion(UUID tenantId, UUID versionId) {
        if (versionId == null) return;
        boolean exists = jdbc.sql("""
                select exists(select 1 from knowledge_document_version
                  where tenant_id = :tenant and doc_version_id = :version)
                """).param("tenant", tenantId).param("version", versionId).query(Boolean.class).single();
        if (!exists) throw new KnowledgeException("KNOWLEDGE_DOCUMENT_VERSION_NOT_FOUND", 422,
                "source_document_version_id is not available in this tenant");
    }

    private Map<ConceptKey, UUID> resolveConcepts(UUID tenantId, List<Concept> concepts, List<Relation> relations) {
        Set<ConceptKey> required = new HashSet<>();
        concepts.forEach(concept -> required.add(concept.key()));
        relations.forEach(relation -> { required.add(relation.from()); required.add(relation.to()); });
        Map<ConceptKey, UUID> resolved = new HashMap<>();
        for (ConceptKey key : required) {
            UUID id = jdbc.sql("""
                    select concept_id from knowledge_concept
                    where tenant_id = :tenant and source_type = 'DICTIONARY'
                      and system = :system and code = :code
                    """).param("tenant", tenantId).param("system", key.system()).param("code", key.code())
                    .query(UUID.class).optional().orElse(null);
            if (id != null) resolved.put(key, id);
        }
        return resolved;
    }

    private KnowledgeGraphImportBatchWire batch(UUID tenantId, UUID batchId) {
        return jdbc.sql("""
                select graph_import_batch_id, source_id, source_document_version_id, manifest_hash,
                  imported_concept_count, imported_relation_count, status, imported_by, imported_at
                from knowledge_graph_import_batch
                where tenant_id = :tenant and graph_import_batch_id = :batch
                """).param("tenant", tenantId).param("batch", batchId)
                .query((rs, row) -> new KnowledgeGraphImportBatchWire(
                        rs.getObject("graph_import_batch_id", UUID.class), rs.getObject("source_id", UUID.class),
                        rs.getObject("source_document_version_id", UUID.class), rs.getString("manifest_hash"),
                        rs.getInt("imported_concept_count"), rs.getInt("imported_relation_count"),
                        KnowledgeGraphImportBatchWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getObject("imported_by", UUID.class),
                        rs.getObject("imported_at", OffsetDateTime.class).toInstant()))
                .optional().orElseThrow(() -> new KnowledgeException("KNOWLEDGE_GRAPH_IMPORT_NOT_FOUND", 404,
                        "The knowledge graph import batch is not available"));
    }

    private void beginCommand(ClinicalIdentity identity, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new KnowledgeException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", COMMAND_SCOPE).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new KnowledgeException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String key, UUID batchId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 201,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", batchId).param("tenant", identity.tenantId())
                .param("scope", COMMAND_SCOPE).param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, UUID batchId) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|KNOWLEDGE_GRAPH_IMPORTED|"
                + batchId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, 'KNOWLEDGE_GRAPH_IMPORTED',
                  'KNOWLEDGE_GRAPH_IMPORT_BATCH', :resource, :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("resource", batchId)
                .param("patient_hash", sha256(identity.tenantId() + "|null"))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'KNOWLEDGE_GRAPH_IMPORT_BATCH', :aggregate, 1,
                  'KnowledgeGraphImported', 1, jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", batchId).update();
    }

    private static String manifest(List<Concept> concepts, List<Relation> relations) {
        List<String> entries = new ArrayList<>();
        concepts.stream().sorted(Comparator.comparing(Concept::system).thenComparing(Concept::code))
                .forEach(concept -> entries.add("concept|" + concept.system() + "|" + concept.code()
                        + "|" + concept.display()));
        relations.stream().sorted(Comparator.comparing(Relation::semanticKey))
                .forEach(relation -> entries.add("relation|" + relation.semanticKey()));
        return sha256(String.join("\n", entries));
    }

    private static String text(String value, int min, int max, String field) {
        if (value == null || value.trim().length() < min || value.trim().length() > max) {
            throw invalid(field + " must contain between " + min + " and " + max + " characters");
        }
        return value.trim();
    }

    private static String optionalText(String value, int max, String field) {
        if (value == null || value.isBlank()) return null;
        return text(value, 1, max, field);
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

    private record Source(String sourceCode, String status) {}
    private record Concept(String system, String code, String display) {
        ConceptKey key() { return new ConceptKey(system, code); }
    }
    private record ConceptKey(String system, String code) {}
    private record Relation(ConceptKey from, ConceptKey to, String type, String version) {
        String semanticKey() {
            return from.system() + "|" + from.code() + "|" + to.system() + "|" + to.code()
                    + "|" + type + "|" + (version == null ? "" : version);
        }
    }
    private record NormalizedImport(UUID sourceId, UUID sourceDocumentVersionId, List<Concept> concepts,
                                    List<Relation> relations, String manifestHash) {}
}
