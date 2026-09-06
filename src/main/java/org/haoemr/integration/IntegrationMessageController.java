package org.haoemr.integration;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.IntegrationMessageCollectRequestWire;
import org.haoemr.contracts.IntegrationMessageCollectResultWire;
import org.haoemr.contracts.IntegrationMessageReconcileRequestWire;
import org.haoemr.contracts.IntegrationMessageWire;
import org.haoemr.contracts.IntegrationReconciliationWire;
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
final class IntegrationMessageController {
    private final ClinicalCommandSecurity security;
    private final IntegrationMessageService messages;

    IntegrationMessageController(ClinicalCommandSecurity security, IntegrationMessageService messages) {
        this.security = security;
        this.messages = messages;
    }

    @GetMapping("/integration-messages")
    ResponseEntity<List<IntegrationMessageWire>> list(
            HttpServletRequest request,
            @RequestParam(value = "connector_code", required = false) String connectorCode,
            @RequestParam(value = "status", required = false) String status,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(messages.listMessages(identity, connectorCode, status));
    }

    @PostMapping("/integration-messages")
    ResponseEntity<IntegrationMessageCollectResultWire> collect(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody IntegrationMessageCollectRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(messages.collect(identity, idempotencyKey, command));
    }

    @PostMapping("/integration-messages/{message_id}/reconcile")
    ResponseEntity<IntegrationMessageWire> reconcile(
            HttpServletRequest request,
            @PathVariable("message_id") UUID messageId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody IntegrationMessageReconcileRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(messages.reconcile(identity, idempotencyKey, messageId, command));
    }

    @GetMapping("/integration-reconciliations")
    ResponseEntity<List<IntegrationReconciliationWire>> reconciliations(
            HttpServletRequest request,
            @RequestParam(value = "connector_code", required = false) String connectorCode,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(messages.listReconciliations(identity, connectorCode));
    }
}
