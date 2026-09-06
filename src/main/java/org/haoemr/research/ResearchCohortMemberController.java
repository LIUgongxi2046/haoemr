package org.haoemr.research;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ResearchCohortMemberComputeRequestWire;
import org.haoemr.contracts.ResearchCohortMemberWire;
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
final class ResearchCohortMemberController {
    private final ClinicalCommandSecurity security;
    private final ResearchCohortMemberService members;

    ResearchCohortMemberController(ClinicalCommandSecurity security, ResearchCohortMemberService members) {
        this.security = security;
        this.members = members;
    }

    @GetMapping("/research-cohort-members")
    ResponseEntity<List<ResearchCohortMemberWire>> list(
            HttpServletRequest request,
            @RequestParam("research_cohort_id") UUID researchCohortId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(members.list(identity, researchCohortId));
    }

    @PostMapping("/research-cohort-members")
    ResponseEntity<ResearchCohortMemberWire> compute(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ResearchCohortMemberComputeRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(members.compute(identity, idempotencyKey, command));
    }
}
