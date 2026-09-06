package org.haoemr.research;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ResearchCohortSnapshotRequestWire;
import org.haoemr.contracts.ResearchCohortSnapshotWire;
import org.haoemr.security.ClinicalCommandSecurity;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
final class ResearchCohortSnapshotController {
    private final ClinicalCommandSecurity security;
    private final ResearchCohortSnapshotService snapshots;

    ResearchCohortSnapshotController(ClinicalCommandSecurity security, ResearchCohortSnapshotService snapshots) {
        this.security = security;
        this.snapshots = snapshots;
    }

    @GetMapping("/research-cohort-snapshots")
    ResponseEntity<List<ResearchCohortSnapshotWire>> list(
            HttpServletRequest request,
            @RequestParam("research_cohort_id") UUID researchCohortId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(snapshots.listSnapshots(identity, researchCohortId));
    }

    @PostMapping("/research-cohort-snapshots")
    ResponseEntity<ResearchCohortSnapshotWire> record(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ResearchCohortSnapshotRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(snapshots.record(identity, idempotencyKey, command));
    }
}
