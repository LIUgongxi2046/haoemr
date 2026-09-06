package org.haoemr.research;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ReleaseMetricSnapshotCreateRequestWire;
import org.haoemr.contracts.ReleaseMetricSnapshotWire;
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
final class ReleaseMetricSnapshotController {
    private final ClinicalCommandSecurity security;
    private final ReleaseMetricSnapshotService snapshots;

    ReleaseMetricSnapshotController(ClinicalCommandSecurity security, ReleaseMetricSnapshotService snapshots) {
        this.security = security;
        this.snapshots = snapshots;
    }

    @GetMapping("/release-metric-snapshots")
    ResponseEntity<List<ReleaseMetricSnapshotWire>> list(
            HttpServletRequest request,
            @RequestParam("metric_type") ReleaseMetricSnapshotWire.MetricTypeValue metricType,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(snapshots.listRecords(identity, metricType));
    }

    @PostMapping("/release-metric-snapshots")
    ResponseEntity<ReleaseMetricSnapshotWire> record(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ReleaseMetricSnapshotCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(snapshots.record(identity, idempotencyKey, command));
    }
}
