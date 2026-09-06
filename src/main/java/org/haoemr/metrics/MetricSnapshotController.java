package org.haoemr.metrics;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.MetricSnapshotRecordRequestWire;
import org.haoemr.contracts.MetricSnapshotWire;
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
final class MetricSnapshotController {
    private final ClinicalCommandSecurity security;
    private final MetricSnapshotService metrics;

    MetricSnapshotController(ClinicalCommandSecurity security, MetricSnapshotService metrics) {
        this.security = security;
        this.metrics = metrics;
    }

    @GetMapping("/metric-snapshots")
    ResponseEntity<List<MetricSnapshotWire>> list(
            HttpServletRequest request,
            @RequestParam(value = "metric_type", required = false) String metricType,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(metrics.list(identity, metricType));
    }

    @PostMapping("/metric-snapshots")
    ResponseEntity<MetricSnapshotWire> record(
            HttpServletRequest request,
            @RequestBody MetricSnapshotRecordRequestWire command,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(metrics.record(identity, command));
    }

    @PostMapping("/metric-snapshots/compute")
    ResponseEntity<List<MetricSnapshotWire>> compute(
            HttpServletRequest request,
            @RequestParam("metric_type") String metricType,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(metrics.compute(identity, metricType));
    }
}
