package org.haoemr.orders;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ClinicalOrderCreateRequestWire;
import org.haoemr.contracts.ClinicalOrderControlRequestWire;
import org.haoemr.contracts.ClinicalOrderSafetyCheckRequestWire;
import org.haoemr.contracts.ClinicalOrderSignRequestWire;
import org.haoemr.contracts.ClinicalOrderWire;
import org.haoemr.contracts.MedicationSafetyEvaluationWire;
import org.haoemr.contracts.OrderExecutionEventCreateRequestWire;
import org.haoemr.contracts.OrderExecutionTaskWire;
import org.haoemr.security.ClinicalCommandSecurity;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
final class OrderController {
    private final ClinicalCommandSecurity security;
    private final OrderService orders;

    OrderController(ClinicalCommandSecurity security, OrderService orders) {
        this.security = security;
        this.orders = orders;
    }

    @PostMapping("/orders")
    ResponseEntity<ClinicalOrderWire> create(
            HttpServletRequest httpRequest,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ClinicalOrderCreateRequestWire request) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, request.organizationId(), request.facilityId(),
                request.patientId(), request.encounterId());
        ClinicalOrderWire order = orders.create(identity, idempotencyKey, request);
        return ResponseEntity.created(URI.create("/api/v1/orders/" + order.orderId()))
                .eTag("\"" + order.rowVersion() + "\"").cacheControl(CacheControl.noStore()).body(order);
    }

    @GetMapping("/orders")
    ResponseEntity<List<ClinicalOrderWire>> list(
            HttpServletRequest httpRequest,
            @RequestParam("encounter_id") UUID encounterId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId,
            @RequestHeader("X-Encounter-Context") UUID encounterContextId) {
        if (!encounterId.equals(encounterContextId)) {
            throw new OrderException("CONTEXT_NOT_PERMITTED", 403, "The requested order context is not permitted");
        }
        ClinicalIdentity identity = security.authorize(
                httpRequest, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(orders.list(identity, patientId, encounterId, facilityId));
    }

    @GetMapping("/orders/{orderId}")
    ResponseEntity<ClinicalOrderWire> get(
            HttpServletRequest httpRequest,
            @PathVariable UUID orderId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId,
            @RequestHeader("X-Encounter-Context") UUID encounterId) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, organizationId, facilityId, patientId, encounterId);
        ClinicalOrderWire order = orders.get(identity, orderId, patientId, encounterId, facilityId);
        return ResponseEntity.ok().eTag("\"" + order.rowVersion() + "\"")
                .header("X-Data-Watermark", order.dataWatermark())
                .cacheControl(CacheControl.noStore()).body(order);
    }

    @PatchMapping("/orders/{orderId}")
    ResponseEntity<ClinicalOrderWire> update(
            HttpServletRequest httpRequest,
            @PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody ClinicalOrderCreateRequestWire request) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, request.organizationId(), request.facilityId(),
                request.patientId(), request.encounterId());
        long expectedVersion;
        try {
            expectedVersion = Long.parseLong(ifMatch.replace("\"", "").trim());
        } catch (NumberFormatException error) {
            throw new OrderException("ORDER_VERSION_INVALID", 400, "If-Match must contain the current row version");
        }
        return orderResponse(orders.update(identity, idempotencyKey, orderId, expectedVersion, request));
    }

    @PostMapping("/orders/{orderId}/sign")
    ResponseEntity<ClinicalOrderWire> sign(
            HttpServletRequest httpRequest,
            @PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ClinicalOrderSignRequestWire request) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, request.organizationId(), request.facilityId(),
                request.patientId(), request.encounterId());
        ClinicalOrderWire order = orders.sign(identity, idempotencyKey, orderId, request);
        return ResponseEntity.ok().eTag("\"" + order.rowVersion() + "\"")
                .header("X-Data-Watermark", order.dataWatermark())
                .cacheControl(CacheControl.noStore()).body(order);
    }

    @PostMapping("/orders/{orderId}/safety-check")
    ResponseEntity<MedicationSafetyEvaluationWire> checkSafety(
            HttpServletRequest httpRequest,
            @PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ClinicalOrderSafetyCheckRequestWire request) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, request.organizationId(), request.facilityId(),
                request.patientId(), request.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(orders.checkSafety(identity, idempotencyKey, orderId, request));
    }

    @PostMapping("/orders/{orderId}/stop")
    ResponseEntity<ClinicalOrderWire> stop(
            HttpServletRequest httpRequest,
            @PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ClinicalOrderControlRequestWire request) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, request.organizationId(), request.facilityId(),
                request.patientId(), request.encounterId());
        return orderResponse(orders.stop(identity, idempotencyKey, orderId, request));
    }

    @PostMapping("/orders/{orderId}/cancel")
    ResponseEntity<ClinicalOrderWire> cancel(
            HttpServletRequest httpRequest,
            @PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ClinicalOrderControlRequestWire request) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, request.organizationId(), request.facilityId(),
                request.patientId(), request.encounterId());
        return orderResponse(orders.cancel(identity, idempotencyKey, orderId, request));
    }

    @PostMapping("/executions/{executionTaskId}/events")
    ResponseEntity<OrderExecutionTaskWire> recordExecution(
            HttpServletRequest httpRequest,
            @PathVariable UUID executionTaskId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody OrderExecutionEventCreateRequestWire request) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, request.organizationId(), request.facilityId(),
                request.patientId(), request.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(orders.recordExecution(identity, idempotencyKey, executionTaskId, request));
    }

    private static ResponseEntity<ClinicalOrderWire> orderResponse(ClinicalOrderWire order) {
        return ResponseEntity.ok().eTag("\"" + order.rowVersion() + "\"")
                .header("X-Data-Watermark", order.dataWatermark())
                .cacheControl(CacheControl.noStore()).body(order);
    }
}
