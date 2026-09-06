package org.haoemr.imaging;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ImagingOrderCreateRequestWire;
import org.haoemr.contracts.ImagingOrderTransitionRequestWire;
import org.haoemr.contracts.ImagingOrderWire;
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
final class ImagingOrderController {
    private final ClinicalCommandSecurity security;
    private final ImagingOrderService orders;

    ImagingOrderController(ClinicalCommandSecurity security, ImagingOrderService orders) {
        this.security = security;
        this.orders = orders;
    }

    @GetMapping("/imaging-orders")
    ResponseEntity<List<ImagingOrderWire>> list(
            HttpServletRequest request,
            @RequestParam("patient_id") UUID patientId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientContextId) {
        if (!patientId.equals(patientContextId)) throw ImagingOrderService.contextDenied();
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(orders.listOrders(identity, patientId));
    }

    @PostMapping("/imaging-orders")
    ResponseEntity<ImagingOrderWire> create(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ImagingOrderCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(orders.createOrder(identity, idempotencyKey, command));
    }

    @PostMapping("/imaging-orders/{imaging_order_id}/transitions")
    ResponseEntity<ImagingOrderWire> transition(
            HttpServletRequest request,
            @PathVariable("imaging_order_id") UUID imagingOrderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ImagingOrderTransitionRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(orders.transitionOrder(identity, idempotencyKey, imagingOrderId, command));
    }
}
