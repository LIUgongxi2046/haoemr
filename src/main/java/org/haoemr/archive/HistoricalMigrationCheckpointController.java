package org.haoemr.archive;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.HistoricalMigrationCheckpointRecordRequestWire;
import org.haoemr.contracts.HistoricalMigrationCheckpointWire;
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
final class HistoricalMigrationCheckpointController {
    private final ClinicalCommandSecurity security;
    private final HistoricalMigrationCheckpointService checkpoints;

    HistoricalMigrationCheckpointController(ClinicalCommandSecurity security, HistoricalMigrationCheckpointService checkpoints) {
        this.security = security;
        this.checkpoints = checkpoints;
    }

    @GetMapping("/historical-migration-checkpoints")
    ResponseEntity<List<HistoricalMigrationCheckpointWire>> list(
            HttpServletRequest request,
            @RequestParam("batch_id") UUID batchId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(checkpoints.list(identity, batchId));
    }

    @GetMapping("/historical-migration-checkpoints/latest")
    ResponseEntity<HistoricalMigrationCheckpointWire> latest(
            HttpServletRequest request,
            @RequestParam("batch_id") UUID batchId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(checkpoints.latest(identity, batchId));
    }

    @PostMapping("/historical-migration-checkpoints")
    ResponseEntity<HistoricalMigrationCheckpointWire> record(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody HistoricalMigrationCheckpointRecordRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(checkpoints.record(identity, idempotencyKey, command));
    }
}
