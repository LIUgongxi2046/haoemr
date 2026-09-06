package org.haoemr.knowledge;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.PathwayKnowledgeActionRequestWire;
import org.haoemr.contracts.KnowledgeGraphNeighborsWire;
import org.haoemr.contracts.KnowledgeGraphPathsWire;
import org.haoemr.contracts.KnowledgeGraphWire;
import org.haoemr.contracts.PathwayReviewQueueItemWire;
import org.haoemr.contracts.PathwayKnowledgeCreateRequestWire;
import org.haoemr.contracts.PathwayKnowledgeSearchRequestWire;
import org.haoemr.contracts.PathwayKnowledgeSearchResultWire;
import org.haoemr.contracts.PathwayKnowledgeVersionCreateRequestWire;
import org.haoemr.contracts.PathwayKnowledgeVersionWire;
import org.haoemr.contracts.PathwayKnowledgeWire;
import org.haoemr.security.ClinicalCommandSecurity;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
final class PathwayKnowledgeController {
    private final ClinicalCommandSecurity security;
    private final PathwayKnowledgeService pathways;

    PathwayKnowledgeController(ClinicalCommandSecurity security, PathwayKnowledgeService pathways) {
        this.security = security;
        this.pathways = pathways;
    }

    @GetMapping("/pathway-review-queue")
    ResponseEntity<List<PathwayReviewQueueItemWire>> reviewQueue(
            HttpServletRequest request,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(pathways.reviewQueue(identity));
    }

    @GetMapping("/knowledge-graph")
    ResponseEntity<KnowledgeGraphWire> graph(
            HttpServletRequest request,
            @RequestParam(value = "limit", required = false, defaultValue = "200") Integer limit,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(pathways.graph(identity, limit));
    }

    @GetMapping("/knowledge-graph/nodes/{concept_id}/neighbors")
    ResponseEntity<KnowledgeGraphNeighborsWire> neighbors(
            HttpServletRequest request,
            @PathVariable("concept_id") UUID conceptId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(pathways.neighbors(identity, conceptId));
    }

    @GetMapping("/knowledge-graph/paths")
    ResponseEntity<KnowledgeGraphPathsWire> paths(
            HttpServletRequest request,
            @RequestParam("from") UUID fromId,
            @RequestParam("to") UUID toId,
            @RequestParam(value = "max_depth", required = false) Integer maxDepth,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(pathways.paths(identity, fromId, toId, maxDepth));
    }

    @GetMapping("/knowledge-graph/ego")
    ResponseEntity<KnowledgeGraphWire> ego(
            HttpServletRequest request,
            @RequestParam("concept_id") UUID conceptId,
            @RequestParam(value = "depth", required = false) Integer depth,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(pathways.ego(identity, conceptId, depth, limit));
    }

    @GetMapping("/pathway-knowledge")
    ResponseEntity<List<PathwayKnowledgeWire>> list(
            HttpServletRequest request,
            @RequestParam(value = "specialty_code", required = false) String specialtyCode,
            @RequestParam(value = "diagnosis_code", required = false) String diagnosisCode,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(pathways.listKnowledge(identity, specialtyCode, diagnosisCode));
    }

    @PostMapping("/pathway-knowledge")
    ResponseEntity<PathwayKnowledgeWire> create(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PathwayKnowledgeCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(pathways.createKnowledge(identity, idempotencyKey, command));
    }

    @PostMapping("/pathway-knowledge/{pathway_knowledge_id}/versions")
    ResponseEntity<PathwayKnowledgeVersionWire> createVersion(
            HttpServletRequest request,
            @PathVariable("pathway_knowledge_id") UUID knowledgeId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PathwayKnowledgeVersionCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(pathways.createVersion(identity, idempotencyKey, knowledgeId, command));
    }

    @GetMapping("/pathway-knowledge/{pathway_knowledge_id}/versions")
    ResponseEntity<List<PathwayKnowledgeVersionWire>> listVersions(
            HttpServletRequest request,
            @PathVariable("pathway_knowledge_id") UUID knowledgeId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(pathways.listVersions(identity, knowledgeId));
    }

    @PostMapping("/pathway-versions/{pathway_version_id}/submissions")
    ResponseEntity<PathwayKnowledgeVersionWire> submit(
            HttpServletRequest request, @PathVariable("pathway_version_id") UUID versionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PathwayKnowledgeActionRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(pathways.submitVersion(identity, idempotencyKey, versionId));
    }

    @PostMapping("/pathway-versions/{pathway_version_id}/reviews")
    ResponseEntity<PathwayKnowledgeVersionWire> review(
            HttpServletRequest request, @PathVariable("pathway_version_id") UUID versionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PathwayKnowledgeActionRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(pathways.reviewVersion(identity, idempotencyKey, versionId));
    }

    @PostMapping("/pathway-versions/{pathway_version_id}/approvals")
    ResponseEntity<PathwayKnowledgeVersionWire> approve(
            HttpServletRequest request, @PathVariable("pathway_version_id") UUID versionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PathwayKnowledgeActionRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(pathways.approveVersion(identity, idempotencyKey, versionId));
    }

    @PostMapping("/pathway-versions/{pathway_version_id}/retirements")
    ResponseEntity<PathwayKnowledgeVersionWire> retire(
            HttpServletRequest request, @PathVariable("pathway_version_id") UUID versionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PathwayKnowledgeActionRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(pathways.retireVersion(identity, idempotencyKey, versionId));
    }

    @PostMapping("/pathway-knowledge-search")
    ResponseEntity<PathwayKnowledgeSearchResultWire> search(
            HttpServletRequest request, @RequestBody PathwayKnowledgeSearchRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(pathways.search(identity, command));
    }
}
