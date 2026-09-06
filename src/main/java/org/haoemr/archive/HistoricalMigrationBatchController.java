package org.haoemr.archive;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.HistoricalMigrationBatchReconcileRequestWire;
import org.haoemr.contracts.HistoricalMigrationBatchRollbackRequestWire;
import org.haoemr.contracts.HistoricalMigrationBatchArchiveRequestWire;
import org.haoemr.contracts.HistoricalMigrationBatchObservationStartRequestWire;
import org.haoemr.contracts.HistoricalMigrationBatchStartRequestWire;
import org.haoemr.contracts.HistoricalMigrationBatchSwitchRequestWire;
import org.haoemr.contracts.HistoricalMigrationBatchWire;
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
final class HistoricalMigrationBatchController {
    private final ClinicalCommandSecurity security;
    private final HistoricalMigrationBatchService batches;

    HistoricalMigrationBatchController(ClinicalCommandSecurity security, HistoricalMigrationBatchService batches) {
        this.security = security;
        this.batches = batches;
    }

    @GetMapping("/historical-migration-batches")
    ResponseEntity<List<HistoricalMigrationBatchWire>> list(
            HttpServletRequest request,
            @RequestParam(value = "source_system", required = false) String sourceSystem,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(batches.listBatches(identity, sourceSystem));
    }

    @PostMapping("/historical-migration-batches")
    ResponseEntity<HistoricalMigrationBatchWire> start(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody HistoricalMigrationBatchStartRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(batches.start(identity, idempotencyKey, command));
    }

    @PostMapping("/historical-migration-batches/{batch_id}/reconciliations")
    ResponseEntity<HistoricalMigrationBatchWire> reconcile(
            HttpServletRequest request,
            @PathVariable("batch_id") UUID batchId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody HistoricalMigrationBatchReconcileRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(batches.reconcile(identity, idempotencyKey, batchId, command));
    }

    @PostMapping("/historical-migration-batches/{batch_id}/switches")
    ResponseEntity<HistoricalMigrationBatchWire> switchBatch(
            HttpServletRequest request,
            @PathVariable("batch_id") UUID batchId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody HistoricalMigrationBatchSwitchRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(batches.switchBatch(identity, idempotencyKey, batchId, command));
    }

    @PostMapping("/historical-migration-batches/{batch_id}/rollbacks")
    ResponseEntity<HistoricalMigrationBatchWire> rollback(
            HttpServletRequest request,
            @PathVariable("batch_id") UUID batchId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody HistoricalMigrationBatchRollbackRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(batches.rollback(identity, idempotencyKey, batchId, command));
    }

    @PostMapping("/historical-migration-batches/{batch_id}/observations")
    ResponseEntity<HistoricalMigrationBatchWire> startObservation(
            HttpServletRequest request,
            @PathVariable("batch_id") UUID batchId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody HistoricalMigrationBatchObservationStartRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(batches.startObservation(identity, idempotencyKey, batchId, command));
    }

    @PostMapping("/historical-migration-batches/{batch_id}/archives")
    ResponseEntity<HistoricalMigrationBatchWire> archive(
            HttpServletRequest request,
            @PathVariable("batch_id") UUID batchId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody HistoricalMigrationBatchArchiveRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(batches.archive(identity, idempotencyKey, batchId, command));
    }
}
