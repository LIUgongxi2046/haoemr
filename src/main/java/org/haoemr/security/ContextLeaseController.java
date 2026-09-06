package org.haoemr.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.haoemr.contracts.ContextLeaseCreateRequestWire;
import org.haoemr.contracts.ContextLeaseWire;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/context-leases")
final class ContextLeaseController {

    private final ClinicalIdentityProvider identities;
    private final ContextLeaseService leases;

    ContextLeaseController(ClinicalIdentityProvider identities, ContextLeaseService leases) {
        this.identities = identities;
        this.leases = leases;
    }

    @PostMapping
    ResponseEntity<ContextLeaseWire> create(
            HttpServletRequest httpRequest,
            @RequestBody ContextLeaseCreateRequestWire request) {
        ClinicalIdentity identity = identities.current(httpRequest);
        ContextLease lease = leases.issue(
                identity,
                request.organizationId(),
                request.facilityId(),
                request.patientId(),
                request.encounterId(),
                request.taskId(),
                request.purposeCode());
        return ResponseEntity.created(URI.create("/api/v1/context-leases/" + lease.leaseId()))
                .body(ContextLeaseWireMapper.toWire(lease));
    }
}
