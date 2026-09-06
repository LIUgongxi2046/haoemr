package org.haoemr.clinical;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
@RequestMapping("/api/v1/record-center")
final class RecordCenterController {
    private static final Set<String> PURPOSES = Set.of("RECORD_CENTER_WORKLIST");

    private final ClinicalCommandSecurity security;
    private final RecordCenterService records;

    RecordCenterController(ClinicalCommandSecurity security, RecordCenterService records) {
        this.security = security;
        this.records = records;
    }

    @GetMapping("/worklist")
    ResponseEntity<List<RecordCenterService.WorklistItem>> worklist(
            HttpServletRequest request,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "query", required = false) String query) {
        ClinicalIdentity identity = security.authorizeForPurposes(
                request, organizationId, facilityId, null, null, PURPOSES);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(records.worklist(identity, organizationId, facilityId, status, query));
    }

    @GetMapping("/review-cases")
    ResponseEntity<List<RecordCenterService.ReviewCase>> reviewCases(
            HttpServletRequest request,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestParam(value = "document_id", required = false) UUID documentId) {
        ClinicalIdentity identity = security.authorizeForPurposes(
                request, organizationId, facilityId, null, null, PURPOSES);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(records.reviewCases(identity, organizationId, facilityId, documentId));
    }

    @PostMapping("/review-cases")
    ResponseEntity<RecordCenterService.ReviewCase> createReviewCase(
            HttpServletRequest request,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CreateReviewCase command) {
        ClinicalIdentity identity = security.authorizeForPurposes(
                request, organizationId, facilityId, null, null, PURPOSES);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(records.createReviewCase(identity, idempotencyKey, organizationId, facilityId, command));
    }

    @PostMapping("/review-cases/{reviewCaseId}/transitions")
    ResponseEntity<RecordCenterService.ReviewCase> transitionReviewCase(
            HttpServletRequest request,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable UUID reviewCaseId,
            @RequestBody TransitionReviewCase command) {
        ClinicalIdentity identity = security.authorizeForPurposes(
                request, organizationId, facilityId, null, null, PURPOSES);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(records.transitionReviewCase(
                        identity, idempotencyKey, organizationId, facilityId, reviewCaseId, command));
    }

    record CreateReviewCase(UUID documentId, UUID documentVersionId, String reviewScope,
                            String reason, String priority, UUID assigneeUserId, Instant dueAt) {}

    record TransitionReviewCase(long expectedRowVersion, String targetStatus, String reason,
                                UUID assigneeUserId) {}
}
