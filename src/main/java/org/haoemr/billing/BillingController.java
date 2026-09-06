package org.haoemr.billing;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ChargeItemRequestWire;
import org.haoemr.contracts.ChargeItemReverseRequestWire;
import org.haoemr.contracts.ChargeItemWire;
import org.haoemr.contracts.PriceCatalogVersionRequestWire;
import org.haoemr.contracts.PriceCatalogVersionWire;
import org.haoemr.contracts.EncounterSettlementWire;
import org.haoemr.contracts.SettlementReceiptCreateRequestWire;
import org.haoemr.contracts.SettlementReceiptWire;
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
final class BillingController {
    private final ClinicalCommandSecurity security;
    private final BillingService billing;

    BillingController(ClinicalCommandSecurity security, BillingService billing) {
        this.security = security;
        this.billing = billing;
    }

    @PostMapping("/price-catalogs")
    ResponseEntity<PriceCatalogVersionWire> createPriceVersion(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PriceCatalogVersionRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(billing.createPriceVersion(identity, idempotencyKey, command));
    }

    @GetMapping("/charges")
    ResponseEntity<List<ChargeItemWire>> list(
            HttpServletRequest request,
            @RequestParam("encounter_id") UUID encounterId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId,
            @RequestHeader("X-Encounter-Context") UUID encounterContextId) {
        if (!encounterId.equals(encounterContextId)) throw BillingService.contextDenied();
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(billing.listCharges(identity, organizationId, facilityId, patientId, encounterId));
    }

    @PostMapping("/charges")
    ResponseEntity<ChargeItemWire> charge(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ChargeItemRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(billing.createCharge(identity, idempotencyKey, command));
    }

    @PostMapping("/charges/{charge_item_id}/reversals")
    ResponseEntity<ChargeItemWire> reverse(
            HttpServletRequest request,
            @PathVariable("charge_item_id") UUID chargeItemId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ChargeItemReverseRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(billing.reverseCharge(identity, idempotencyKey, chargeItemId, command));
    }

    @GetMapping("/settlements")
    ResponseEntity<EncounterSettlementWire> settlement(
            HttpServletRequest request,
            @RequestParam("encounter_id") UUID encounterId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId,
            @RequestHeader("X-Encounter-Context") UUID encounterContextId) {
        if (!encounterId.equals(encounterContextId)) throw BillingService.contextDenied();
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(billing.settlement(identity, organizationId, facilityId, patientId, encounterId));
    }

    @PostMapping("/settlements/receipts")
    ResponseEntity<SettlementReceiptWire> recordReceipt(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody SettlementReceiptCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(billing.recordSettlementReceipt(identity, idempotencyKey, command));
    }
}
