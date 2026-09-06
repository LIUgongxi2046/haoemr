package org.haoemr.research;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ResearchCohortDeactivateRequestWire;
import org.haoemr.contracts.ResearchCohortDefineRequestWire;
import org.haoemr.contracts.ResearchCohortWire;
import org.haoemr.contracts.ResearchCohortRecomputeRequestWire;
import org.haoemr.contracts.ResearchCohortRecomputeResultWire;
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
final class ResearchCohortController {
    private final ClinicalCommandSecurity security;
    private final ResearchCohortService cohorts;
    private final ResearchCohortRecomputeService recomputes;

    ResearchCohortController(ClinicalCommandSecurity security, ResearchCohortService cohorts, ResearchCohortRecomputeService recomputes) {
        this.security = security; this.cohorts = cohorts; this.recomputes = recomputes;
    }

    @PostMapping("/research-cohorts/{research_cohort_id}/recomputations")
    ResponseEntity<ResearchCohortRecomputeResultWire> recompute(
            HttpServletRequest request, @PathVariable("research_cohort_id") UUID cohortId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ResearchCohortRecomputeRequestWire command) {
        ClinicalIdentity identity = security.authorize(request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(recomputes.recompute(identity, idempotencyKey, cohortId, command));
    }

    @GetMapping("/research-cohorts")
    ResponseEntity<List<ResearchCohortWire>> list(
            HttpServletRequest request,
            @RequestParam(value = "status", required = false) String status,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(cohorts.listCohorts(identity, status));
    }

    @PostMapping("/research-cohorts")
    ResponseEntity<ResearchCohortWire> define(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ResearchCohortDefineRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(cohorts.define(identity, idempotencyKey, command));
    }

    @PostMapping("/research-cohorts/{research_cohort_id}/deactivations")
    ResponseEntity<ResearchCohortWire> deactivate(
            HttpServletRequest request,
            @PathVariable("research_cohort_id") UUID researchCohortId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ResearchCohortDeactivateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(cohorts.deactivate(identity, idempotencyKey, researchCohortId, command));
    }
}
