package org.haoemr.outbox;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import java.util.UUID;
import org.haoemr.contracts.OutboxReconciliationWire;
import org.haoemr.security.ClinicalCommandSecurity;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only operational state for the hospital-facing outbox delivery boundary. */
@RestController
@RequestMapping("/api/v1")
final class OutboxOperationsController {
    private final ClinicalCommandSecurity security;
    private final OutboxDispatcher dispatcher;

    OutboxOperationsController(ClinicalCommandSecurity security, OutboxDispatcher dispatcher) {
        this.security = security;
        this.dispatcher = dispatcher;
    }

    @GetMapping("/outbox/reconciliation")
    ResponseEntity<OutboxReconciliationWire> reconciliation(
            HttpServletRequest request,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorizeForPurposes(
                request, organizationId, facilityId, null, null, Set.of("INTEGRATION_OPERATIONS"));
        OutboxDispatcher.ReconciliationStatus status = dispatcher.reconciliationStatus(identity.tenantId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new OutboxReconciliationWire(
                status.externalDeliveryConfigured(), status.scheduledDispatchEnabled(), status.pending(),
                status.inFlight(), status.published(), status.externalDeliveryAccepted(),
                status.closedWithoutExternalEvidence(), status.deadLetter(), status.oldestPendingSeconds()));
    }
}
