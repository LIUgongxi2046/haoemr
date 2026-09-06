package org.haoemr.patient;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.haoemr.patient.PatientTimelineService.PatientTimelineWire;
import org.haoemr.security.ClinicalCommandSecurity;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
final class PatientTimelineController {
    private final ClinicalCommandSecurity security;
    private final PatientTimelineService timeline;

    PatientTimelineController(ClinicalCommandSecurity security, PatientTimelineService timeline) {
        this.security = security;
        this.timeline = timeline;
    }

    @GetMapping("/patients/{patientId}/timeline")
    ResponseEntity<PatientTimelineWire> timeline(HttpServletRequest request,
            @PathVariable UUID patientId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader(value = "X-HaoEMR-Synthetic-Failed-Timeline-Sources", required = false)
                    String syntheticFailedSources,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Set<String> types,
            @RequestParam(required = false) Set<String> statuses,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, null);
        List<String> failedSources = syntheticFailedSources == null || syntheticFailedSources.isBlank()
                ? List.of() : List.of(syntheticFailedSources.split(","));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(timeline.load(
                identity, organizationId, facilityId, patientId, from, to, types, statuses,
                cursor, limit, failedSources));
    }
}
