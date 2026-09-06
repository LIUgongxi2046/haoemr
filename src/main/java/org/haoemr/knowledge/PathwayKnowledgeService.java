package org.haoemr.knowledge;

import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.haoemr.contracts.PathwayKnowledgeActionRequestWire;
import org.haoemr.contracts.KnowledgeGraphEdgeWire;
import org.haoemr.contracts.KnowledgeGraphNeighborWire;
import org.haoemr.contracts.KnowledgeGraphNeighborsWire;
import org.haoemr.contracts.KnowledgeGraphNodeWire;
import org.haoemr.contracts.KnowledgeGraphPathWire;
import org.haoemr.contracts.KnowledgeGraphPathsWire;
import org.haoemr.contracts.KnowledgeGraphWire;
import org.haoemr.contracts.PathwayKnowledgeCreateRequestWire;
import org.haoemr.contracts.PathwayKnowledgeQualityPointInputWire;
import org.haoemr.contracts.PathwayKnowledgeQualityPointWire;
import org.haoemr.contracts.PathwayKnowledgeReferenceWire;
import org.haoemr.contracts.PathwayReviewQueueItemWire;
import org.haoemr.contracts.PathwayKnowledgeSearchRequestWire;
import org.haoemr.contracts.PathwayKnowledgeSearchResultWire;
import org.haoemr.contracts.PathwayKnowledgeStageInputWire;
import org.haoemr.contracts.PathwayKnowledgeStageWire;
import org.haoemr.contracts.PathwayKnowledgeTaskInputWire;
import org.haoemr.contracts.PathwayKnowledgeTaskWire;
import org.haoemr.contracts.PathwayKnowledgeVarianceInputWire;
import org.haoemr.contracts.PathwayKnowledgeVarianceWire;
import org.haoemr.contracts.PathwayKnowledgeVersionCreateRequestWire;
import org.haoemr.contracts.PathwayKnowledgeVersionWire;
import org.haoemr.contracts.PathwayKnowledgeWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class PathwayKnowledgeService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    PathwayKnowledgeService(JdbcClient jdbc, TransactionTemplate transactions, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    PathwayKnowledgeWire createKnowledge(
            ClinicalIdentity identity, String idempotencyKey, PathwayKnowledgeCreateRequestWire request) {
        String code = requireText(request.pathwayCode(), 2, "pathway_code");
        String name = requireText(request.displayName(), 2, "display_name");
        String specialty = requireText(request.specialtyCode(), 2, "specialty_code");
        String diagnosis = requireText(request.diagnosisCode(), 2, "diagnosis_code");
        return transactions.execute(status -> {
            beginCommand(identity, "PATHWAY_KNOWLEDGE_CREATE", idempotencyKey, sha256(code));
            UUID knowledgeId = UUID.randomUUID();
            jdbc.sql("""
                    insert into pathway_knowledge(
                      tenant_id, pathway_knowledge_id, pathway_code, display_name, specialty_code,
                      diagnosis_code, inclusion_criteria, exclusion_criteria, avg_los_days, status, created_by)
                    values (:tenant, :knowledge, :code, :name, :specialty, :diagnosis,
                      :inclusion, :exclusion, :los, 'ACTIVE', :actor)
                    """).param("tenant", identity.tenantId()).param("knowledge", knowledgeId)
                    .param("code", code).param("name", name).param("specialty", specialty)
                    .param("diagnosis", diagnosis).param("inclusion", request.inclusionCriteria())
                    .param("exclusion", request.exclusionCriteria()).param("los", request.avgLosDays())
                    .param("actor", identity.userId()).update();
            appendEvidence(identity, knowledgeId, "PATHWAY_KNOWLEDGE_CREATED", "PathwayKnowledgeCreated");
            completeCommand(identity, "PATHWAY_KNOWLEDGE_CREATE", idempotencyKey, knowledgeId);
            return knowledge(identity.tenantId(), knowledgeId);
        });
    }

    List<PathwayKnowledgeWire> listKnowledge(ClinicalIdentity identity, String specialtyCode, String diagnosisCode) {
        List<UUID> ids = jdbc.sql("""
                select pathway_knowledge_id from pathway_knowledge
                where tenant_id = :tenant
                  and (cast(:specialty as varchar) is null or specialty_code = cast(:specialty as varchar))
                  and (cast(:diagnosis as varchar) is null or diagnosis_code = cast(:diagnosis as varchar))
                order by display_name, pathway_knowledge_id limit 500
                """).param("tenant", identity.tenantId()).param("specialty", specialtyCode)
                .param("diagnosis", diagnosisCode).query(UUID.class).list();
        return ids.stream().map(id -> knowledge(identity.tenantId(), id)).toList();
    }

    PathwayKnowledgeVersionWire createVersion(
            ClinicalIdentity identity, String idempotencyKey, UUID knowledgeId,
            PathwayKnowledgeVersionCreateRequestWire request) {
        if (request.stages() == null || request.stages().isEmpty()) {
            throw invalid("at least one stage is required");
        }
        return transactions.execute(status -> {
            beginCommand(identity, "PATHWAY_VERSION_CREATE", idempotencyKey, sha256(knowledgeId.toString()));
            jdbc.sql("""
                    select pathway_knowledge_id from pathway_knowledge
                    where tenant_id = :tenant and pathway_knowledge_id = :knowledge for update
                    """).param("tenant", identity.tenantId()).param("knowledge", knowledgeId)
                    .query(UUID.class).optional().orElseThrow(PathwayKnowledgeService::contextDenied);
            UUID versionId = UUID.randomUUID();
            int versionNo = nextVersionNo(identity.tenantId(), knowledgeId);
            String contentHash = contentHash(request);
            jdbc.sql("""
                    insert into pathway_knowledge_version(
                      tenant_id, pathway_version_id, pathway_knowledge_id, version_no, content_hash,
                      status, submitted_by)
                    values (:tenant, :version, :knowledge, :no, :hash, 'DRAFT', :actor)
                    """).param("tenant", identity.tenantId()).param("version", versionId)
                    .param("knowledge", knowledgeId).param("no", versionNo).param("hash", contentHash)
                    .param("actor", identity.userId()).update();
            for (PathwayKnowledgeStageInputWire stage : request.stages()) {
                UUID stageId = insertStage(identity.tenantId(), versionId, stage);
                if (stage.tasks() != null) {
                    for (PathwayKnowledgeTaskInputWire task : stage.tasks()) {
                        insertTask(identity.tenantId(), stageId, task);
                    }
                }
            }
            if (request.variances() != null) {
                for (PathwayKnowledgeVarianceInputWire variance : request.variances()) {
                    insertVariance(identity.tenantId(), versionId, variance);
                }
            }
            if (request.qualityPoints() != null) {
                for (PathwayKnowledgeQualityPointInputWire point : request.qualityPoints()) {
                    insertQualityPoint(identity.tenantId(), versionId, point);
                }
            }
            appendEvidence(identity, versionId, "PATHWAY_VERSION_CREATED", "PathwayVersionCreated");
            completeCommand(identity, "PATHWAY_VERSION_CREATE", idempotencyKey, versionId);
            return version(identity.tenantId(), versionId);
        });
    }

    List<PathwayKnowledgeVersionWire> listVersions(ClinicalIdentity identity, UUID knowledgeId) {
        List<UUID> ids = jdbc.sql("""
                select pathway_version_id from pathway_knowledge_version
                where tenant_id = :tenant and pathway_knowledge_id = :knowledge
                order by version_no desc, pathway_version_id desc limit 100
                """).param("tenant", identity.tenantId()).param("knowledge", knowledgeId).query(UUID.class).list();
        return ids.stream().map(id -> version(identity.tenantId(), id)).toList();
    }

    PathwayKnowledgeVersionWire submitVersion(ClinicalIdentity identity, String idempotencyKey, UUID versionId) {
        return transactions.execute(status -> {
            beginCommand(identity, "PATHWAY_VERSION_SUBMIT", idempotencyKey, sha256(versionId.toString()));
            transition(identity, versionId, "DRAFT", "IN_REVIEW");
            completeCommand(identity, "PATHWAY_VERSION_SUBMIT", idempotencyKey, versionId);
            return version(identity.tenantId(), versionId);
        });
    }

    PathwayKnowledgeVersionWire reviewVersion(ClinicalIdentity identity, String idempotencyKey, UUID versionId) {
        return transactions.execute(status -> {
            beginCommand(identity, "PATHWAY_VERSION_REVIEW", idempotencyKey, sha256(versionId.toString()));
            VersionHead head = lockVersion(identity.tenantId(), versionId);
            if (!"IN_REVIEW".equals(head.status())) {
                throw new PathwayKnowledgeException("PATHWAY_VERSION_STATE_INVALID", 409,
                        "Only an in-review version can be reviewed");
            }
            if (identity.userId().equals(head.submittedBy())) {
                throw new PathwayKnowledgeException("PATHWAY_REVIEW_SEPARATION_REQUIRED", 409,
                        "The submitter cannot review their own pathway");
            }
            jdbc.sql("""
                    update pathway_knowledge_version
                    set status = 'APPROVED', reviewed_by = :actor, reviewed_at = now()
                    where tenant_id = :tenant and pathway_version_id = :version
                    """).param("actor", identity.userId()).param("tenant", identity.tenantId())
                    .param("version", versionId).update();
            appendEvidence(identity, versionId, "PATHWAY_VERSION_REVIEWED", "PathwayVersionReviewed");
            completeCommand(identity, "PATHWAY_VERSION_REVIEW", idempotencyKey, versionId);
            return version(identity.tenantId(), versionId);
        });
    }

    PathwayKnowledgeVersionWire approveVersion(ClinicalIdentity identity, String idempotencyKey, UUID versionId) {
        return transactions.execute(status -> {
            beginCommand(identity, "PATHWAY_VERSION_APPROVE", idempotencyKey, sha256(versionId.toString()));
            VersionHead head = lockVersion(identity.tenantId(), versionId);
            if (!"APPROVED".equals(head.status())) {
                throw new PathwayKnowledgeException("PATHWAY_VERSION_STATE_INVALID", 409,
                        "Only an approved version can be published");
            }
            if (head.reviewedBy() != null && identity.userId().equals(head.reviewedBy())) {
                throw new PathwayKnowledgeException("PATHWAY_APPROVE_SEPARATION_REQUIRED", 409,
                        "The reviewer cannot approve the same pathway");
            }
            jdbc.sql("""
                    update pathway_knowledge_version
                    set status = 'ACTIVE', approved_by = :actor, approved_at = now(), published_at = now()
                    where tenant_id = :tenant and pathway_version_id = :version
                    """).param("actor", identity.userId()).param("tenant", identity.tenantId())
                    .param("version", versionId).update();
            materializeConfigurationDraft(identity, head.knowledgeId(), versionId);
            appendEvidence(identity, versionId, "PATHWAY_VERSION_PUBLISHED", "PathwayVersionPublished");
            completeCommand(identity, "PATHWAY_VERSION_APPROVE", idempotencyKey, versionId);
            return version(identity.tenantId(), versionId);
        });
    }

    PathwayKnowledgeVersionWire retireVersion(ClinicalIdentity identity, String idempotencyKey, UUID versionId) {
        return transactions.execute(status -> {
            beginCommand(identity, "PATHWAY_VERSION_RETIRE", idempotencyKey, sha256(versionId.toString()));
            VersionHead head = lockVersion(identity.tenantId(), versionId);
            if (!"ACTIVE".equals(head.status())) {
                throw new PathwayKnowledgeException("PATHWAY_VERSION_STATE_INVALID", 409,
                        "Only an active version can be retired");
            }
            jdbc.sql("""
                    update pathway_knowledge_version
                    set status = 'RETIRED'
                    where tenant_id = :tenant and pathway_version_id = :version
                    """).param("tenant", identity.tenantId()).param("version", versionId).update();
            appendEvidence(identity, versionId, "PATHWAY_VERSION_RETIRED", "PathwayVersionRetired");
            completeCommand(identity, "PATHWAY_VERSION_RETIRE", idempotencyKey, versionId);
            return version(identity.tenantId(), versionId);
        });
    }

    List<PathwayReviewQueueItemWire> reviewQueue(ClinicalIdentity identity) {
        return jdbc.sql("""
                select k.pathway_knowledge_id, k.display_name, k.diagnosis_code,
                  v.pathway_version_id, v.version_no, v.status
                from pathway_knowledge k
                join pathway_knowledge_version v on v.pathway_knowledge_id = k.pathway_knowledge_id
                where k.tenant_id = :tenant and v.status in ('DRAFT','IN_REVIEW','APPROVED')
                order by v.status, k.display_name
                """).param("tenant", identity.tenantId())
                .query((rs, row) -> new PathwayReviewQueueItemWire(
                        rs.getObject("pathway_knowledge_id", UUID.class), rs.getString("display_name"),
                        rs.getString("diagnosis_code"), rs.getObject("pathway_version_id", UUID.class),
                        rs.getInt("version_no"),
                        PathwayReviewQueueItemWire.StatusValue.valueOf(rs.getString("status")))).list();
    }

    KnowledgeGraphWire graph(ClinicalIdentity identity, int limit) {
        int nodeLimit = Math.min(Math.max(limit, 10), 300);
        List<KnowledgeGraphNodeWire> nodes = jdbc.sql("""
                select concept_id, display, system from knowledge_concept
                where tenant_id = :tenant and concept_id in (
                  select from_concept from knowledge_relation where tenant_id = :tenant
                  union select to_concept from knowledge_relation where tenant_id = :tenant
                )
                order by (select count(*) from knowledge_relation r
                  where r.tenant_id = :tenant and (r.from_concept = concept_id or r.to_concept = concept_id)) desc
                limit :limit
                """).param("tenant", identity.tenantId()).param("limit", nodeLimit)
                .query((rs, row) -> new KnowledgeGraphNodeWire(
                        rs.getObject("concept_id", UUID.class), rs.getString("display"), rs.getString("system"))).list();
        if (nodes.isEmpty()) return new KnowledgeGraphWire(nodes, List.of());
        Set<UUID> ids = nodes.stream().map(KnowledgeGraphNodeWire::id).collect(Collectors.toSet());
        List<KnowledgeGraphEdgeWire> edges = jdbc.sql("""
                select from_concept, to_concept, rel_type,
                  coalesce(nullif(version, ''), rel_type) as predicate
                from knowledge_relation
                where tenant_id = :tenant and from_concept = any(:ids::uuid[]) and to_concept = any(:ids::uuid[])
                limit 2000
                """).param("tenant", identity.tenantId()).param("ids", ids.toArray(UUID[]::new))
                .query((rs, row) -> new KnowledgeGraphEdgeWire(
                        rs.getObject("from_concept", UUID.class), rs.getObject("to_concept", UUID.class),
                        rs.getString("rel_type"), rs.getString("predicate"))).list();
        return new KnowledgeGraphWire(nodes, edges);
    }

    KnowledgeGraphNeighborsWire neighbors(ClinicalIdentity identity, UUID conceptId) {
        KnowledgeGraphNodeWire node = node(identity.tenantId(), conceptId);
        if (node == null) {
            throw new PathwayKnowledgeException("CONCEPT_NOT_FOUND", 404, "Knowledge graph concept not found");
        }
        return new KnowledgeGraphNeighborsWire(node,
                neighborRelations(identity.tenantId(), conceptId, false),
                neighborRelations(identity.tenantId(), conceptId, true));
    }

    KnowledgeGraphPathsWire paths(ClinicalIdentity identity, UUID fromId, UUID toId, Integer maxDepth) {
        int depth = maxDepth == null ? 3 : Math.min(Math.max(maxDepth, 1), 4);
        KnowledgeGraphNodeWire from = node(identity.tenantId(), fromId);
        KnowledgeGraphNodeWire to = node(identity.tenantId(), toId);
        if (from == null || to == null) {
            throw new PathwayKnowledgeException("CONCEPT_NOT_FOUND", 404, "start or end concept not found");
        }
        if (fromId.equals(toId)) {
            return new KnowledgeGraphPathsWire(from, to, List.of(new KnowledgeGraphPathWire(List.of(from), List.of())));
        }
        List<Relation> relations = relations(identity.tenantId());
        Map<UUID, List<Relation>> adjacency = adjacency(relations);

        Map<UUID, Integer> distance = new HashMap<>();
        Deque<UUID> queue = new ArrayDeque<>();
        distance.put(fromId, 0);
        queue.add(fromId);
        while (!queue.isEmpty()) {
            UUID current = queue.poll();
            int d = distance.get(current);
            if (d >= depth || current.equals(toId)) continue;
            for (Relation relation : adjacency.getOrDefault(current, List.of())) {
                UUID other = relation.from().equals(current) ? relation.to() : relation.from();
                if (distance.containsKey(other)) continue;
                distance.put(other, d + 1);
                queue.add(other);
            }
        }

        List<KnowledgeGraphPathWire> resultPaths = new ArrayList<>();
        if (distance.containsKey(toId)) {
            List<KnowledgeGraphPathWire> found = new ArrayList<>();
            List<UUID> nodePath = new ArrayList<>();
            List<Relation> relationPath = new ArrayList<>();
            Set<UUID> onPath = new HashSet<>();
            nodePath.add(fromId);
            onPath.add(fromId);
            enumeratePaths(fromId, toId, depth, adjacency, distance, nodePath, relationPath, onPath, found, 20);
            Map<UUID, KnowledgeGraphNodeWire> nodeIndex = nodesById(identity.tenantId(),
                    found.stream().flatMap(path -> path.nodes().stream()).map(KnowledgeGraphNodeWire::id).collect(Collectors.toSet()));
            for (KnowledgeGraphPathWire path : found) {
                List<KnowledgeGraphNodeWire> resolvedNodes = path.nodes().stream()
                        .map(node -> nodeIndex.getOrDefault(node.id(), node)).toList();
                resultPaths.add(new KnowledgeGraphPathWire(resolvedNodes, path.edges()));
            }
        }
        return new KnowledgeGraphPathsWire(from, to, resultPaths);
    }

    KnowledgeGraphWire ego(ClinicalIdentity identity, UUID conceptId, Integer depthParam, Integer limitParam) {
        int depth = depthParam == null ? 2 : Math.min(Math.max(depthParam, 1), 3);
        int limit = limitParam == null ? 200 : Math.min(Math.max(limitParam, 20), 500);
        if (node(identity.tenantId(), conceptId) == null) {
            throw new PathwayKnowledgeException("CONCEPT_NOT_FOUND", 404, "Knowledge graph concept not found");
        }
        List<Relation> relations = relations(identity.tenantId());
        Map<UUID, List<Relation>> adjacency = adjacency(relations);
        Set<UUID> included = new LinkedHashSet<>();
        Map<UUID, Integer> distance = new HashMap<>();
        Deque<UUID> queue = new ArrayDeque<>();
        included.add(conceptId);
        distance.put(conceptId, 0);
        queue.add(conceptId);
        while (!queue.isEmpty() && included.size() < limit) {
            UUID current = queue.poll();
            int d = distance.get(current);
            if (d >= depth) continue;
            for (Relation relation : adjacency.getOrDefault(current, List.of())) {
                if (included.size() >= limit) break;
                UUID other = relation.from().equals(current) ? relation.to() : relation.from();
                if (distance.containsKey(other)) continue;
                distance.put(other, d + 1);
                included.add(other);
                queue.add(other);
            }
        }
        Map<UUID, KnowledgeGraphNodeWire> nodeIndex = nodesById(identity.tenantId(), included);
        List<KnowledgeGraphNodeWire> nodes = included.stream().map(nodeIndex::get).filter(Objects::nonNull).toList();
        List<KnowledgeGraphEdgeWire> edges = new ArrayList<>();
        for (Relation relation : relations) {
            if (included.contains(relation.from()) && included.contains(relation.to())) {
                edges.add(new KnowledgeGraphEdgeWire(relation.from(), relation.to(),
                        relation.relation(), relation.predicate()));
            }
            if (edges.size() >= 2000) break;
        }
        return new KnowledgeGraphWire(nodes, edges);
    }

    private List<Relation> relations(UUID tenantId) {
        return jdbc.sql("""
                select from_concept, to_concept, rel_type,
                  coalesce(nullif(version, ''), rel_type) as predicate
                from knowledge_relation where tenant_id = :tenant
                """).param("tenant", tenantId)
                .query((rs, row) -> new Relation(
                        rs.getObject("from_concept", UUID.class), rs.getObject("to_concept", UUID.class),
                        rs.getString("rel_type"), rs.getString("predicate"))).list();
    }

    private Map<UUID, List<Relation>> adjacency(List<Relation> relations) {
        Map<UUID, List<Relation>> adjacency = new HashMap<>();
        for (Relation relation : relations) {
            adjacency.computeIfAbsent(relation.from(), key -> new ArrayList<>()).add(relation);
            adjacency.computeIfAbsent(relation.to(), key -> new ArrayList<>()).add(relation);
        }
        return adjacency;
    }

    private KnowledgeGraphNodeWire node(UUID tenantId, UUID conceptId) {
        return jdbc.sql("""
                select concept_id, display, system from knowledge_concept
                where tenant_id = :tenant and concept_id = :id
                """).param("tenant", tenantId).param("id", conceptId)
                .query((rs, row) -> new KnowledgeGraphNodeWire(
                        rs.getObject("concept_id", UUID.class), rs.getString("display"), rs.getString("system")))
                .optional().orElse(null);
    }

    private Map<UUID, KnowledgeGraphNodeWire> nodesById(UUID tenantId, Set<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<UUID, KnowledgeGraphNodeWire> index = new HashMap<>();
        jdbc.sql("""
                select concept_id, display, system from knowledge_concept
                where tenant_id = :tenant and concept_id = any(:ids::uuid[])
                """).param("tenant", tenantId).param("ids", ids.toArray(UUID[]::new))
                .query((rs, row) -> new KnowledgeGraphNodeWire(
                        rs.getObject("concept_id", UUID.class), rs.getString("display"), rs.getString("system")))
                .stream().forEach(node -> index.put(node.id(), node));
        return index;
    }

    private List<KnowledgeGraphNeighborWire> neighborRelations(UUID tenantId, UUID conceptId, boolean outgoing) {
        String sql = outgoing
                ? """
                  select c.concept_id, c.display, c.system,
                    coalesce(nullif(r.version, ''), r.rel_type) as predicate
                  from knowledge_relation r
                  join knowledge_concept c on c.tenant_id = r.tenant_id and c.concept_id = r.to_concept
                  where r.tenant_id = :tenant and r.from_concept = :id
                  order by c.display limit 100
                  """
                : """
                  select c.concept_id, c.display, c.system,
                    coalesce(nullif(r.version, ''), r.rel_type) as predicate
                  from knowledge_relation r
                  join knowledge_concept c on c.tenant_id = r.tenant_id and c.concept_id = r.from_concept
                  where r.tenant_id = :tenant and r.to_concept = :id
                  order by c.display limit 100
                  """;
        return jdbc.sql(sql).param("tenant", tenantId).param("id", conceptId)
                .query((rs, row) -> new KnowledgeGraphNeighborWire(
                        new KnowledgeGraphNodeWire(rs.getObject("concept_id", UUID.class),
                                rs.getString("display"), rs.getString("system")),
                        rs.getString("predicate"))).list();
    }

    private void enumeratePaths(UUID current, UUID target, int depth, Map<UUID, List<Relation>> adjacency,
                                Map<UUID, Integer> distance, List<UUID> nodePath, List<Relation> relationPath,
                                Set<UUID> onPath, List<KnowledgeGraphPathWire> results, int cap) {
        if (results.size() >= cap) return;
        if (current.equals(target)) {
            results.add(new KnowledgeGraphPathWire(nodePath.stream().map(id -> new KnowledgeGraphNodeWire(id, "", ""))
                    .toList(), relationPath.stream().map(relation -> new KnowledgeGraphEdgeWire(
                            relation.from(), relation.to(), relation.relation(), relation.predicate())).toList()));
            return;
        }
        int currentDistance = distance.get(current);
        if (currentDistance >= depth) return;
        for (Relation relation : adjacency.getOrDefault(current, List.of())) {
            UUID other = relation.from().equals(current) ? relation.to() : relation.from();
            if (distance.get(other) != currentDistance + 1) continue;
            if (onPath.contains(other)) continue;
            onPath.add(other);
            nodePath.add(other);
            relationPath.add(relation);
            enumeratePaths(other, target, depth, adjacency, distance, nodePath, relationPath, onPath, results, cap);
            relationPath.remove(relationPath.size() - 1);
            nodePath.remove(nodePath.size() - 1);
            onPath.remove(other);
            if (results.size() >= cap) return;
        }
    }

    PathwayKnowledgeSearchResultWire search(ClinicalIdentity identity, PathwayKnowledgeSearchRequestWire request) {
        String query = requireText(request.query(), 1, "query");
        int limit = request.limit() == null ? 20 : Math.min(Math.max(request.limit(), 1), 50);
        String like = "%" + query + "%";
        List<PathwayKnowledgeReferenceWire> references = jdbc.sql("""
                select knowledge.pathway_knowledge_id, version.pathway_version_id,
                  knowledge.display_name, knowledge.diagnosis_code, knowledge.specialty_code,
                  version.content_hash,
                  coalesce(knowledge.inclusion_criteria, '') as excerpt
                from pathway_knowledge knowledge
                join pathway_knowledge_version version
                  on version.tenant_id = knowledge.tenant_id
                 and version.pathway_knowledge_id = knowledge.pathway_knowledge_id
                where knowledge.tenant_id = :tenant and version.status = 'ACTIVE'
                  and (cast(:specialty as varchar) is null or knowledge.specialty_code = cast(:specialty as varchar))
                  and (cast(:diagnosis as varchar) is null or knowledge.diagnosis_code = cast(:diagnosis as varchar))
                  and (knowledge.display_name ilike :like or knowledge.diagnosis_code ilike :like
                       or knowledge.specialty_code ilike :like)
                order by knowledge.display_name limit :limit
                """).param("tenant", identity.tenantId()).param("specialty", request.specialtyCode())
                .param("diagnosis", request.diagnosisCode()).param("like", like).param("limit", limit)
                .query((rs, row) -> new PathwayKnowledgeReferenceWire(
                        rs.getObject("pathway_knowledge_id", UUID.class),
                        rs.getObject("pathway_version_id", UUID.class),
                        rs.getString("display_name"), rs.getString("diagnosis_code"),
                        rs.getString("specialty_code"), rs.getString("excerpt"),
                        rs.getString("content_hash"))).list();
        return new PathwayKnowledgeSearchResultWire(references);
    }

    // 收敛方案：知识审批通过后，把知识内容物化成一个「临床路径配置」草稿（config_item CLINICAL_PATHWAY）。
    // 执行配置 clinical_pathway_* 只由业务配置域发布（ClinicalPathwayConfigurationPublisher），
    // 知识域不再直写执行配置，消除双源发布冲突。
    private void materializeConfigurationDraft(ClinicalIdentity identity, UUID knowledgeId, UUID versionId) {
        PathwayKnowledgeWire knowledge = knowledge(identity.tenantId(), knowledgeId);
        List<PathwayKnowledgeStageWire> stages = stages(identity.tenantId(), versionId);
        int versionNo = jdbc.sql("""
                select version_no from pathway_knowledge_version
                where tenant_id = :tenant and pathway_version_id = :version
                """).param("tenant", identity.tenantId()).param("version", versionId)
                .query(Integer.class).single();

        Map<String, Object> payload = new HashMap<>();
        payload.put("schema_version", 1);
        payload.put("pathway_code", knowledge.pathwayCode());
        payload.put("specialty_code", knowledge.specialtyCode());
        payload.put("diagnosis_code", knowledge.diagnosisCode());
        payload.put("version_no", versionNo);
        payload.put("admission_criteria", knowledge.inclusionCriteria() == null ? "" : knowledge.inclusionCriteria());
        payload.put("publication_scope", "本机构");
        payload.put("version_immutable_after_publish", true);
        List<Map<String, Object>> stageList = new ArrayList<>();
        for (PathwayKnowledgeStageWire stage : stages) {
            Map<String, Object> stagePayload = new HashMap<>();
            stagePayload.put("code", stage.stageCode());
            stagePayload.put("name", stage.stageName());
            stagePayload.put("start", stage.expectedDayStart());
            stagePayload.put("end", stage.expectedDayEnd());
            List<Map<String, Object>> taskList = new ArrayList<>();
            for (PathwayKnowledgeTaskWire task : stage.tasks()) {
                String taskCode = task.codeRef() == null
                        ? "TASK-" + sha256(task.content()).substring(0, 12) : task.codeRef();
                Map<String, Object> taskPayload = new HashMap<>();
                taskPayload.put("code", taskCode);
                taskPayload.put("name", task.content());
                taskPayload.put("source_type", sourceType(task.taskType()));
                taskPayload.put("source_key", taskCode);
                taskPayload.put("required", task.required());
                taskList.add(taskPayload);
            }
            stagePayload.put("tasks", taskList);
            stageList.add(stagePayload);
        }
        payload.put("stages", stageList);
        String payloadJson = json(payload);

        UUID openConfigId = jdbc.sql("""
                select config_id from config_item
                where tenant_id = :tenant and config_type = 'CLINICAL_PATHWAY' and config_key = :key
                  and status in ('DRAFT', 'PENDING_APPROVAL', 'APPROVED')
                """).param("tenant", identity.tenantId()).param("key", knowledge.pathwayCode())
                .query(UUID.class).optional().orElse(null);
        if (openConfigId == null) {
            jdbc.sql("""
                    insert into config_item(
                      tenant_id, config_id, config_type, config_key, display_name, payload, status, created_by)
                    values (:tenant, :config, 'CLINICAL_PATHWAY', :key, :name, cast(:payload as jsonb), 'DRAFT', :actor)
                    """).param("tenant", identity.tenantId()).param("config", UUID.randomUUID())
                    .param("key", knowledge.pathwayCode()).param("name", knowledge.displayName())
                    .param("payload", payloadJson).param("actor", identity.userId()).update();
        } else {
            jdbc.sql("""
                    update config_item set display_name = :name, payload = cast(:payload as jsonb),
                      validation_state = 'NOT_VALIDATED', validation_errors = '[]'::jsonb,
                      approval_state = 'DRAFT', approved_by = null, status = 'DRAFT',
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and config_id = :config
                    """).param("name", knowledge.displayName()).param("payload", payloadJson)
                    .param("tenant", identity.tenantId()).param("config", openConfigId).update();
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception failure) {
            throw new PathwayKnowledgeException("PATHWAY_PAYLOAD_SERIALIZATION_FAILED", 500,
                    "Failed to serialize pathway configuration payload");
        }
    }

    private static String sourceType(PathwayKnowledgeTaskWire.TaskTypeValue type) {
        return switch (type) {
            case MEDICATION, LAB, IMAGING -> "ORDER_ITEM";
            case NURSING, EDUCATION, ASSESSMENT -> "DOCUMENT_TASK";
        };
    }

    private UUID insertStage(UUID tenantId, UUID versionId, PathwayKnowledgeStageInputWire stage) {
        UUID stageId = UUID.randomUUID();
        jdbc.sql("""
                insert into pathway_knowledge_stage(
                  tenant_id, stage_id, pathway_version_id, stage_code, stage_name, sequence_no,
                  expected_day_start, expected_day_end, stage_goal, assessment_points)
                values (:tenant, :stage, :version, :code, :name, :seq, :dayStart, :dayEnd, :goal, :assessment)
                """).param("tenant", tenantId).param("stage", stageId).param("version", versionId)
                .param("code", stage.stageCode()).param("name", stage.stageName())
                .param("seq", stage.sequenceNo()).param("dayStart", stage.expectedDayStart())
                .param("dayEnd", stage.expectedDayEnd()).param("goal", stage.stageGoal())
                .param("assessment", stage.assessmentPoints()).update();
        return stageId;
    }

    private void insertTask(UUID tenantId, UUID stageId, PathwayKnowledgeTaskInputWire task) {
        jdbc.sql("""
                insert into pathway_knowledge_task(
                  tenant_id, task_id, stage_id, task_type, content, code_ref, required, sequence_no)
                values (:tenant, :task, :stage, :type, :content, :codeRef, :required, :seq)
                """).param("tenant", tenantId).param("task", UUID.randomUUID()).param("stage", stageId)
                .param("type", task.taskType().name()).param("content", task.content())
                .param("codeRef", task.codeRef()).param("required", task.required())
                .param("seq", task.sequenceNo()).update();
    }

    private void insertVariance(UUID tenantId, UUID versionId, PathwayKnowledgeVarianceInputWire variance) {
        jdbc.sql("""
                insert into pathway_knowledge_variance(
                  tenant_id, variance_id, pathway_version_id, variance_type, trigger_condition,
                  disposition, record_requirement)
                values (:tenant, :variance, :version, :type, :trigger, :disposition, :record)
                """).param("tenant", tenantId).param("variance", UUID.randomUUID())
                .param("version", versionId).param("type", variance.varianceType())
                .param("trigger", variance.triggerCondition()).param("disposition", variance.disposition())
                .param("record", variance.recordRequirement()).update();
    }

    private void insertQualityPoint(UUID tenantId, UUID versionId, PathwayKnowledgeQualityPointInputWire point) {
        jdbc.sql("""
                insert into pathway_knowledge_quality_point(
                  tenant_id, quality_point_id, pathway_version_id, indicator, standard, frequency)
                values (:tenant, :point, :version, :indicator, :standard, :frequency)
                """).param("tenant", tenantId).param("point", UUID.randomUUID())
                .param("version", versionId).param("indicator", point.indicator())
                .param("standard", point.standard()).param("frequency", point.frequency()).update();
    }

    private int nextVersionNo(UUID tenantId, UUID knowledgeId) {
        Integer count = jdbc.sql("""
                select count(*) from pathway_knowledge_version
                where tenant_id = :tenant and pathway_knowledge_id = :knowledge
                """).param("tenant", tenantId).param("knowledge", knowledgeId).query(Integer.class).single();
        return (count == null ? 0 : count) + 1;
    }

    private String contentHash(PathwayKnowledgeVersionCreateRequestWire request) {
        try {
            return sha256(objectMapper.writeValueAsString(List.of(request.stages(),
                    request.variances() == null ? List.of() : request.variances(),
                    request.qualityPoints() == null ? List.of() : request.qualityPoints())));
        } catch (Exception failure) {
            throw new PathwayKnowledgeException("PATHWAY_CONTENT_HASH_FAILED", 500,
                    "Failed to hash pathway content");
        }
    }

    private void transition(ClinicalIdentity identity, UUID versionId, String from, String to) {
        VersionHead head = lockVersion(identity.tenantId(), versionId);
        if (!from.equals(head.status())) {
            throw new PathwayKnowledgeException("PATHWAY_VERSION_STATE_INVALID", 409,
                    "Only a " + from.toLowerCase() + " version can transition to " + to.toLowerCase());
        }
        jdbc.sql("""
                update pathway_knowledge_version set status = :to
                where tenant_id = :tenant and pathway_version_id = :version
                """).param("to", to).param("tenant", identity.tenantId()).param("version", versionId).update();
    }

    private VersionHead lockVersion(UUID tenantId, UUID versionId) {
        return jdbc.sql("""
                select pathway_knowledge_id, status, submitted_by, reviewed_by from pathway_knowledge_version
                where tenant_id = :tenant and pathway_version_id = :version for update
                """).param("tenant", tenantId).param("version", versionId)
                .query((rs, row) -> new VersionHead(
                        rs.getObject("pathway_knowledge_id", UUID.class), rs.getString("status"),
                        rs.getObject("submitted_by", UUID.class), rs.getObject("reviewed_by", UUID.class)))
                .optional().orElseThrow(PathwayKnowledgeService::contextDenied);
    }

    private PathwayKnowledgeWire knowledge(UUID tenantId, UUID knowledgeId) {
        return jdbc.sql("""
                select pathway_knowledge_id, pathway_code, display_name, specialty_code, diagnosis_code,
                  inclusion_criteria, exclusion_criteria, avg_los_days, status, created_by, created_at, updated_at
                from pathway_knowledge where tenant_id = :tenant and pathway_knowledge_id = :knowledge
                """).param("tenant", tenantId).param("knowledge", knowledgeId)
                .query((rs, row) -> new PathwayKnowledgeWire(
                        rs.getObject("pathway_knowledge_id", UUID.class), rs.getString("pathway_code"),
                        rs.getString("display_name"), rs.getString("specialty_code"),
                        rs.getString("diagnosis_code"), rs.getString("inclusion_criteria"),
                        rs.getString("exclusion_criteria"),
                        rs.getObject("avg_los_days") == null ? null : rs.getInt("avg_los_days"),
                        PathwayKnowledgeWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getObject("created_by", UUID.class),
                        instant(rs, "created_at"), instant(rs, "updated_at")))
                .optional().orElseThrow(PathwayKnowledgeService::contextDenied);
    }

    private PathwayKnowledgeVersionWire version(UUID tenantId, UUID versionId) {
        return jdbc.sql("""
                select pathway_version_id, pathway_knowledge_id, version_no, content_hash, status,
                  submitted_by, reviewed_by, approved_by, submitted_at, reviewed_at, approved_at, published_at
                from pathway_knowledge_version where tenant_id = :tenant and pathway_version_id = :version
                """).param("tenant", tenantId).param("version", versionId)
                .query((rs, row) -> new PathwayKnowledgeVersionWire(
                        rs.getObject("pathway_version_id", UUID.class),
                        rs.getObject("pathway_knowledge_id", UUID.class), rs.getInt("version_no"),
                        rs.getString("content_hash"),
                        PathwayKnowledgeVersionWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getObject("submitted_by", UUID.class), rs.getObject("reviewed_by", UUID.class),
                        rs.getObject("approved_by", UUID.class), instant(rs, "submitted_at"),
                        instant(rs, "reviewed_at"), instant(rs, "approved_at"), instant(rs, "published_at"),
                        stages(tenantId, versionId), variances(tenantId, versionId), qualityPoints(tenantId, versionId)))
                .optional().orElseThrow(PathwayKnowledgeService::contextDenied);
    }

    private List<PathwayKnowledgeStageWire> stages(UUID tenantId, UUID versionId) {
        List<StageHead> heads = jdbc.sql("""
                select stage_id, stage_code, stage_name, sequence_no, expected_day_start, expected_day_end,
                  stage_goal, assessment_points
                from pathway_knowledge_stage
                where tenant_id = :tenant and pathway_version_id = :version
                order by sequence_no
                """).param("tenant", tenantId).param("version", versionId)
                .query((rs, row) -> new StageHead(
                        rs.getObject("stage_id", UUID.class), rs.getString("stage_code"),
                        rs.getString("stage_name"), rs.getInt("sequence_no"),
                        rs.getInt("expected_day_start"), rs.getInt("expected_day_end"),
                        rs.getString("stage_goal"), rs.getString("assessment_points"))).list();
        List<PathwayKnowledgeStageWire> stages = new ArrayList<>();
        for (StageHead head : heads) {
            List<PathwayKnowledgeTaskWire> tasks = jdbc.sql("""
                    select task_id, task_type, content, code_ref, required, sequence_no
                    from pathway_knowledge_task
                    where tenant_id = :tenant and stage_id = :stage order by sequence_no
                    """).param("tenant", tenantId).param("stage", head.stageId())
                    .query((rs, row) -> new PathwayKnowledgeTaskWire(
                            rs.getObject("task_id", UUID.class),
                            PathwayKnowledgeTaskWire.TaskTypeValue.valueOf(rs.getString("task_type")),
                            rs.getString("content"), rs.getString("code_ref"), rs.getBoolean("required"),
                            rs.getInt("sequence_no"))).list();
            stages.add(new PathwayKnowledgeStageWire(
                    head.stageId(), head.stageCode(), head.stageName(), head.sequenceNo(),
                    head.dayStart(), head.dayEnd(), head.goal(), head.assessment(), tasks));
        }
        return stages;
    }

    private List<PathwayKnowledgeVarianceWire> variances(UUID tenantId, UUID versionId) {
        return jdbc.sql("""
                select variance_id, variance_type, trigger_condition, disposition, record_requirement
                from pathway_knowledge_variance where tenant_id = :tenant and pathway_version_id = :version
                """).param("tenant", tenantId).param("version", versionId)
                .query((rs, row) -> new PathwayKnowledgeVarianceWire(
                        rs.getObject("variance_id", UUID.class), rs.getString("variance_type"),
                        rs.getString("trigger_condition"), rs.getString("disposition"),
                        rs.getString("record_requirement"))).list();
    }

    private List<PathwayKnowledgeQualityPointWire> qualityPoints(UUID tenantId, UUID versionId) {
        return jdbc.sql("""
                select quality_point_id, indicator, standard, frequency
                from pathway_knowledge_quality_point where tenant_id = :tenant and pathway_version_id = :version
                """).param("tenant", tenantId).param("version", versionId)
                .query((rs, row) -> new PathwayKnowledgeQualityPointWire(
                        rs.getObject("quality_point_id", UUID.class), rs.getString("indicator"),
                        rs.getString("standard"), rs.getString("frequency"))).list();
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        return rs.getObject(column, OffsetDateTime.class) == null
                ? null : rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new PathwayKnowledgeException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new PathwayKnowledgeException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
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
                values (:tenant, :audit, now(), :actor, :action, 'PATHWAY_KNOWLEDGE', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", resourceId)
                .param("patient_hash", sha256(identity.tenantId() + "|null"))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'PATHWAY_KNOWLEDGE', :aggregate, 1, :event_type, 1,
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

    private static PathwayKnowledgeException invalid(String message) {
        return new PathwayKnowledgeException("PATHWAY_KNOWLEDGE_REQUEST_INVALID", 400, message);
    }

    static PathwayKnowledgeException contextDenied() {
        return new PathwayKnowledgeException("CONTEXT_NOT_PERMITTED", 403,
                "The requested pathway knowledge context is not permitted");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record VersionHead(UUID knowledgeId, String status, UUID submittedBy, UUID reviewedBy) {}
    private record Relation(UUID from, UUID to, String relation, String predicate) {}
    private record StageHead(UUID stageId, String stageCode, String stageName, int sequenceNo,
                             int dayStart, int dayEnd, String goal, String assessment) {}
}
