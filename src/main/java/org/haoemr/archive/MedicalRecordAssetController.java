package org.haoemr.archive;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import org.haoemr.contracts.MedicalRecordAssetActionRequestWire;
import org.haoemr.contracts.MedicalRecordAssetBorrowRequestWire;
import org.haoemr.contracts.MedicalRecordAssetBorrowUpdateRequestWire;
import org.haoemr.contracts.MedicalRecordAssetDistributionCreateRequestWire;
import org.haoemr.contracts.MedicalRecordAssetDistributionDeliveryRequestWire;
import org.haoemr.contracts.MedicalRecordAssetDistributionPackageWire;
import org.haoemr.contracts.MedicalRecordAssetIngestRequestWire;
import org.haoemr.contracts.MedicalRecordAssetIntegrityCheckRequestWire;
import org.haoemr.contracts.MedicalRecordAssetIntegrityEventWire;
import org.haoemr.contracts.MedicalRecordAssetRegisterRequestWire;
import org.haoemr.contracts.MedicalRecordAssetRetireRequestWire;
import org.haoemr.contracts.MedicalRecordAssetReturnRequestWire;
import org.haoemr.contracts.MedicalRecordAssetUpdateRequestWire;
import org.haoemr.contracts.MedicalRecordAssetWire;
import org.haoemr.security.ClinicalCommandSecurity;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
final class MedicalRecordAssetController {
    private final ClinicalCommandSecurity security;
    private final MedicalRecordAssetService assets;

    MedicalRecordAssetController(ClinicalCommandSecurity security, MedicalRecordAssetService assets) {
        this.security = security;
        this.assets = assets;
    }

    @GetMapping("/medical-record-assets")
    ResponseEntity<List<MedicalRecordAssetWire>> list(
            HttpServletRequest request,
            @RequestParam("patient_id") UUID patientId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientContextId) {
        if (!patientId.equals(patientContextId)) throw MedicalRecordAssetService.contextDenied();
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(assets.listAssets(identity, organizationId, facilityId, patientId));
    }

    @PostMapping("/medical-record-assets")
    ResponseEntity<MedicalRecordAssetWire> register(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody MedicalRecordAssetRegisterRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(assets.register(identity, idempotencyKey, command));
    }

    @PostMapping("/medical-record-assets/ingestions")
    ResponseEntity<MedicalRecordAssetWire> ingest(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody MedicalRecordAssetIngestRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(assets.ingest(identity, idempotencyKey, command));
    }

    @PostMapping("/medical-record-assets/{asset_id}/borrows")
    ResponseEntity<MedicalRecordAssetWire> borrow(
            HttpServletRequest request,
            @PathVariable("asset_id") UUID assetId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody MedicalRecordAssetBorrowRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(assets.borrow(identity, idempotencyKey, assetId, command));
    }

    @PostMapping("/medical-record-assets/{asset_id}/returns")
    ResponseEntity<MedicalRecordAssetWire> returnAsset(
            HttpServletRequest request,
            @PathVariable("asset_id") UUID assetId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody MedicalRecordAssetReturnRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(assets.returnAsset(identity, idempotencyKey, assetId, command));
    }

    @PatchMapping("/medical-record-assets/{asset_id}/borrow")
    ResponseEntity<MedicalRecordAssetWire> updateBorrow(
            HttpServletRequest request,
            @PathVariable("asset_id") UUID assetId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody MedicalRecordAssetBorrowUpdateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(assets.updateBorrow(identity, idempotencyKey, assetId, command));
    }

    @PatchMapping("/medical-record-assets/{asset_id}")
    ResponseEntity<MedicalRecordAssetWire> update(
            HttpServletRequest request,
            @PathVariable("asset_id") UUID assetId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody MedicalRecordAssetUpdateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(assets.update(identity, idempotencyKey, assetId, command));
    }

    @PostMapping("/medical-record-assets/{asset_id}/retirements")
    ResponseEntity<MedicalRecordAssetWire> retire(
            HttpServletRequest request,
            @PathVariable("asset_id") UUID assetId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody MedicalRecordAssetRetireRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(assets.retire(identity, idempotencyKey, assetId, command));
    }

    @GetMapping("/medical-record-assets/{asset_id}/integrity-events")
    ResponseEntity<List<MedicalRecordAssetIntegrityEventWire>> integrityEvents(
            HttpServletRequest request,
            @PathVariable("asset_id") UUID assetId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(assets.listIntegrityEvents(identity, organizationId, facilityId, patientId, assetId));
    }

    @PostMapping("/medical-record-assets/{asset_id}/integrity-events")
    ResponseEntity<MedicalRecordAssetIntegrityEventWire> verifyIntegrity(
            HttpServletRequest request,
            @PathVariable("asset_id") UUID assetId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody MedicalRecordAssetIntegrityCheckRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(assets.verifyIntegrity(identity, idempotencyKey, assetId, command));
    }

    @GetMapping("/medical-record-assets/{asset_id}/content")
    ResponseEntity<byte[]> content(
            HttpServletRequest request,
            @PathVariable("asset_id") UUID assetId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, null);
        MedicalRecordAssetService.AssetBinary binary =
                assets.content(identity, organizationId, facilityId, patientId, assetId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(binary.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(binary.filename(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-SHA256", binary.contentHash()).contentLength(binary.content().length)
                .body(binary.content());
    }

    @PostMapping("/medical-record-assets/{asset_id}/ocr-runs")
    ResponseEntity<MedicalRecordAssetWire> runOcr(
            HttpServletRequest request,
            @PathVariable("asset_id") UUID assetId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody MedicalRecordAssetActionRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(assets.runOcr(identity, idempotencyKey, assetId, command));
    }

    @PostMapping("/medical-record-assets/{asset_id}/cda-validations")
    ResponseEntity<MedicalRecordAssetWire> validateCda(
            HttpServletRequest request,
            @PathVariable("asset_id") UUID assetId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody MedicalRecordAssetActionRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(assets.validateCda(identity, idempotencyKey, assetId, command));
    }

    @PostMapping("/medical-record-assets/{asset_id}/storage-verifications")
    ResponseEntity<MedicalRecordAssetIntegrityEventWire> verifyStorage(
            HttpServletRequest request,
            @PathVariable("asset_id") UUID assetId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody MedicalRecordAssetActionRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(assets.verifyStoredContent(identity, idempotencyKey, assetId, command));
    }

    @GetMapping("/medical-record-assets/{asset_id}/distribution-packages")
    ResponseEntity<List<MedicalRecordAssetDistributionPackageWire>> listDistributions(
            HttpServletRequest request,
            @PathVariable("asset_id") UUID assetId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(assets.listDistributions(identity, organizationId, facilityId, patientId, assetId));
    }

    @PostMapping("/medical-record-assets/{asset_id}/distribution-packages")
    ResponseEntity<MedicalRecordAssetDistributionPackageWire> createDistribution(
            HttpServletRequest request,
            @PathVariable("asset_id") UUID assetId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody MedicalRecordAssetDistributionCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(assets.createDistribution(identity, idempotencyKey, assetId, command));
    }

    @PostMapping("/medical-record-assets/{asset_id}/distribution-packages/{package_id}/deliveries")
    ResponseEntity<MedicalRecordAssetDistributionPackageWire> deliverDistribution(
            HttpServletRequest request,
            @PathVariable("asset_id") UUID assetId,
            @PathVariable("package_id") UUID packageId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody MedicalRecordAssetDistributionDeliveryRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(assets.deliverDistribution(identity, idempotencyKey, assetId, packageId, command));
    }

    @GetMapping("/medical-record-assets/{asset_id}/distribution-packages/{package_id}/content")
    ResponseEntity<byte[]> distributionContent(
            HttpServletRequest request,
            @PathVariable("asset_id") UUID assetId,
            @PathVariable("package_id") UUID packageId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, null);
        MedicalRecordAssetService.DistributionBinary binary =
                assets.distributionContent(identity, organizationId, facilityId, patientId, assetId, packageId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(binary.filename(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-SHA256", binary.contentHash()).contentLength(binary.content().length)
                .body(binary.content());
    }
}
