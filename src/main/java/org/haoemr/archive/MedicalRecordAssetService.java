package org.haoemr.archive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
final class MedicalRecordAssetService {
    private static final List<String> ASSET_ROLES = List.of("MEDICAL_RECORDS", "CLINICAL_ADMIN");
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ArchiveObjectStorage storage;
    private final ArchiveOcrEngine ocr;
    private final ArchiveMalwareScanner malwareScanner;
    private final ArchiveDocumentValidator documentValidator;
    private final ObjectMapper mapper;

    MedicalRecordAssetService(
            JdbcClient jdbc, TransactionTemplate transactions, ArchiveObjectStorage storage,
            ArchiveOcrEngine ocr, ArchiveMalwareScanner malwareScanner,
            ArchiveDocumentValidator documentValidator, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.storage = storage;
        this.ocr = ocr;
        this.malwareScanner = malwareScanner;
        this.documentValidator = documentValidator;
        this.mapper = mapper;
    }

    MedicalRecordAssetWire register(
            ClinicalIdentity identity, String idempotencyKey, MedicalRecordAssetRegisterRequestWire request) {
        requireRole(identity, request.facilityId());
        if (request.patientId() == null || request.assetType() == null) {
            throw invalid("patient_id and asset_type are required");
        }
        String location = requireText(request.location(), 2, "location");
        String contentHash = requireHash(request.contentHash());
        String displayName = optionalText(request.displayName(), "病案资产", 2, "display_name");
        String mediaType = optionalText(request.mediaType(), "application/octet-stream", 3, "media_type");
        int pageCount = request.pageCount() == null ? 1 : requireRange(request.pageCount(), 1, 100000, "page_count");
        String sourceSystem = optionalText(request.sourceSystem(), "haoemr", 2, "source_system");
        String cdaStatus = request.cdaStatus() == null ? "NOT_APPLICABLE" : request.cdaStatus().name();
        requireInitialCdaStatus(cdaStatus);
        String scanStatus = request.scanStatus() == null
                ? (request.assetType() == MedicalRecordAssetRegisterRequestWire.AssetTypeValue.SCAN
                        ? "CAPTURED" : "NOT_APPLICABLE")
                : request.scanStatus().name();
        String preservationStatus = request.preservationStatus() == null
                ? "NOT_SCHEDULED" : request.preservationStatus().name();
        int retentionYears = legalRetentionYears(identity.tenantId(), request.patientId(), request.encounterId(),
                request.retentionYears());
        requireScanStatus(request.assetType().name(), scanStatus);
        if (!"NOT_SCHEDULED".equals(preservationStatus) && !"SCHEDULED".equals(preservationStatus)) {
            throw invalid("a new asset can only be NOT_SCHEDULED or SCHEDULED for preservation");
        }
        requirePatient(identity.tenantId(), request.patientId());
        return transactions.execute(status -> {
            beginCommand(identity, "MEDICAL_RECORD_ASSET_REGISTER", idempotencyKey,
                    sha256(request.patientId() + "|" + request.assetType() + "|" + contentHash));
            UUID assetId = UUID.randomUUID();
            jdbc.sql("""
                    insert into medical_record_asset(
                      tenant_id, medical_record_asset_id, organization_id, facility_id,
                      patient_id, encounter_id, asset_type,
                      location, content_hash, status, display_name, media_type, page_count,
                      source_system, custody_location, cda_status, scan_status,
                      preservation_status, retention_years)
                    values (:tenant, :asset, :organization, :facility, :patient, :encounter, :asset_type,
                      :location, :hash, 'ARCHIVED', :display_name, :media_type, :page_count,
                      :source_system, :location, :cda_status, :scan_status,
                      :preservation_status, :retention_years)
                    """).param("tenant", identity.tenantId()).param("asset", assetId)
                    .param("organization", request.organizationId()).param("facility", request.facilityId())
                    .param("patient", request.patientId()).param("encounter", request.encounterId())
                    .param("asset_type", request.assetType().name()).param("location", location)
                    .param("hash", contentHash).param("display_name", displayName)
                    .param("media_type", mediaType).param("page_count", pageCount)
                    .param("source_system", sourceSystem).param("cda_status", cdaStatus)
                    .param("scan_status", scanStatus).param("preservation_status", preservationStatus)
                    .param("retention_years", retentionYears).update();
            appendEvidence(identity, request.patientId(), assetId, "MEDICAL_RECORD_ASSET_REGISTERED",
                    "MedicalRecordAssetRegistered");
            completeCommand(identity, "MEDICAL_RECORD_ASSET_REGISTER", idempotencyKey, assetId);
            return asset(identity.tenantId(), assetId);
        });
    }

    MedicalRecordAssetWire ingest(
            ClinicalIdentity identity, String idempotencyKey, MedicalRecordAssetIngestRequestWire request) {
        if (request == null || request.patientId() == null || request.assetType() == null) {
            throw invalid("patient_id and asset_type are required");
        }
        requireRole(identity, request.facilityId());
        String location = requireText(request.location(), 2, "location");
        String displayName = requireText(request.displayName(), 2, "display_name");
        String filename = safeFilename(request.originalFilename());
        String mediaType = requireMediaType(request.mediaType());
        int pageCount = requireRange(request.pageCount(), 1, 100000, "page_count");
        String sourceSystem = requireText(request.sourceSystem(), 2, "source_system");
        int retentionYears = legalRetentionYears(identity.tenantId(), request.patientId(), request.encounterId(),
                request.retentionYears());
        byte[] content = decodeContent(request.contentBase64());
        validateMagic(mediaType, content);
        ArchiveMalwareScanner.ScanResult scan = malwareScanner.scan(content, filename, mediaType);
        if (scan.status() == ArchiveMalwareScanner.Status.REJECTED) {
            throw new MedicalRecordAssetException("MEDICAL_RECORD_ASSET_MALWARE_REJECTED", 422,
                    "Asset content was rejected by the configured malware scanner");
        }
        String contentHash = sha256(content);
        String scanStatus = request.assetType() == MedicalRecordAssetIngestRequestWire.AssetTypeValue.SCAN
                ? "CAPTURED" : "NOT_APPLICABLE";
        String cdaStatus = request.cdaStatus() == null ? "NOT_APPLICABLE" : request.cdaStatus().name();
        requireInitialCdaStatus(cdaStatus);
        requirePatient(identity.tenantId(), request.patientId());
        UUID assetId = UUID.randomUUID();
        String storageKey = identity.tenantId() + "/medical-record-assets/" + assetId
                + "/original/" + contentHash + "-" + filename;
        try {
            return transactions.execute(status -> {
                beginCommand(identity, "MEDICAL_RECORD_ASSET_INGEST", idempotencyKey,
                        sha256(request.patientId() + "|" + request.assetType() + "|" + filename + "|" + contentHash));
                storage.putImmutable(storageKey, content);
                jdbc.sql("""
                        insert into medical_record_asset(
                          tenant_id, medical_record_asset_id, organization_id, facility_id,
                          patient_id, encounter_id, asset_type,
                          location, content_hash, status, display_name, media_type, page_count,
                          source_system, custody_location, cda_status, scan_status,
                          preservation_status, retention_years, original_filename, byte_size,
                          storage_key, storage_status, storage_provider, malware_scan_status, malware_scan_engine)
                        values (:tenant, :asset, :organization, :facility, :patient, :encounter, :asset_type,
                          :location, :hash, 'ARCHIVED', :display_name, :media_type, :page_count,
                          :source_system, :location, :cda_status, :scan_status,
                          'NOT_SCHEDULED', :retention_years, :filename, :byte_size,
                          :storage_key, 'AVAILABLE', :storage_provider, :malware_status, :malware_engine)
                        """).param("tenant", identity.tenantId()).param("asset", assetId)
                        .param("organization", request.organizationId()).param("facility", request.facilityId())
                        .param("patient", request.patientId()).param("encounter", request.encounterId())
                        .param("asset_type", request.assetType().name()).param("location", location)
                        .param("hash", contentHash).param("display_name", displayName)
                        .param("media_type", mediaType).param("page_count", pageCount)
                        .param("source_system", sourceSystem).param("cda_status", cdaStatus)
                        .param("scan_status", scanStatus).param("retention_years", retentionYears)
                        .param("filename", filename).param("byte_size", (long) content.length)
                        .param("storage_key", storageKey).param("malware_status", scan.status().name())
                        .param("malware_engine", scan.engine()).param("storage_provider", storage.provider()).update();
                appendEvidence(identity, request.patientId(), assetId, "MEDICAL_RECORD_ASSET_INGESTED",
                        "MedicalRecordAssetIngested");
                completeCommand(identity, "MEDICAL_RECORD_ASSET_INGEST", idempotencyKey, assetId);
                return asset(identity.tenantId(), assetId);
            });
        } catch (RuntimeException failure) {
            storage.deleteUnsealedBestEffort(storageKey);
            throw failure;
        }
    }

    MedicalRecordAssetWire borrow(
            ClinicalIdentity identity, String idempotencyKey, UUID assetId,
            MedicalRecordAssetBorrowRequestWire request) {
        requireRole(identity, request.facilityId());
        if (request.dueAt() == null) {
            throw invalid("due_at is required");
        }
        if (!request.dueAt().isAfter(Instant.now())) {
            throw invalid("due_at must be in the future");
        }
        return transactions.execute(status -> {
            beginCommand(identity, "MEDICAL_RECORD_ASSET_BORROW", idempotencyKey,
                    sha256(assetId + "|" + request.expectedRowVersion()));
            AssetHead head = lockAsset(identity.tenantId(), assetId);
            requireAssetContext(head, request.organizationId(), request.facilityId(), request.patientId());
            if (request.expectedRowVersion() == null || head.rowVersion() != request.expectedRowVersion()) {
                throw versionConflict();
            }
            if (!"ARCHIVED".equals(head.status())) {
                throw new MedicalRecordAssetException(
                        "MEDICAL_RECORD_ASSET_STATE_INVALID", 409, "Only an archived asset can be borrowed");
            }
            if (!"VERIFIED".equals(head.integrityStatus())) {
                throw new MedicalRecordAssetException(
                        "MEDICAL_RECORD_ASSET_INTEGRITY_REQUIRED", 409,
                        "An asset must pass integrity verification before it can be borrowed");
            }
            if (!"MISSING".equals(head.storageStatus()) && !"PASSED".equals(head.malwareScanStatus())) {
                throw stateInvalid("A real malware scan must pass before an asset can be borrowed");
            }
            jdbc.sql("""
                    update medical_record_asset
                    set status = 'BORROWED', borrowed_by = :borrower, borrowed_at = now(), due_at = :due,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and medical_record_asset_id = :asset and row_version = :expected
                    """).param("tenant", identity.tenantId()).param("asset", assetId)
                    .param("borrower", identity.userId())
                    .param("due", request.dueAt().atOffset(ZoneOffset.UTC))
                    .param("expected", head.rowVersion()).update();
            appendEvidence(identity, head.patientId(), assetId, "MEDICAL_RECORD_ASSET_BORROWED",
                    "MedicalRecordAssetBorrowed");
            completeCommand(identity, "MEDICAL_RECORD_ASSET_BORROW", idempotencyKey, assetId);
            return asset(identity.tenantId(), assetId);
        });
    }

    MedicalRecordAssetWire returnAsset(
            ClinicalIdentity identity, String idempotencyKey, UUID assetId,
            MedicalRecordAssetReturnRequestWire request) {
        requireRole(identity, request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "MEDICAL_RECORD_ASSET_RETURN", idempotencyKey,
                    sha256(assetId + "|" + request.expectedRowVersion()));
            AssetHead head = lockAsset(identity.tenantId(), assetId);
            requireAssetContext(head, request.organizationId(), request.facilityId(), request.patientId());
            if (request.expectedRowVersion() == null || head.rowVersion() != request.expectedRowVersion()) {
                throw versionConflict();
            }
            if (!"BORROWED".equals(head.status())) {
                throw new MedicalRecordAssetException(
                        "MEDICAL_RECORD_ASSET_STATE_INVALID", 409, "Only a borrowed asset can be returned");
            }
            jdbc.sql("""
                    update medical_record_asset
                    set status = 'ARCHIVED', borrowed_by = null, borrowed_at = null, due_at = null,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and medical_record_asset_id = :asset and row_version = :expected
                    """).param("tenant", identity.tenantId()).param("asset", assetId)
                    .param("expected", head.rowVersion()).update();
            appendEvidence(identity, head.patientId(), assetId, "MEDICAL_RECORD_ASSET_RETURNED",
                    "MedicalRecordAssetReturned");
            completeCommand(identity, "MEDICAL_RECORD_ASSET_RETURN", idempotencyKey, assetId);
            return asset(identity.tenantId(), assetId);
        });
    }

    MedicalRecordAssetWire updateBorrow(
            ClinicalIdentity identity, String idempotencyKey, UUID assetId,
            MedicalRecordAssetBorrowUpdateRequestWire request) {
        requireRole(identity, request.facilityId());
        if (request.dueAt() == null || !request.dueAt().isAfter(Instant.now())) {
            throw invalid("due_at must be in the future");
        }
        return transactions.execute(status -> {
            beginCommand(identity, "MEDICAL_RECORD_ASSET_BORROW_UPDATE", idempotencyKey,
                    sha256(assetId + "|" + request.expectedRowVersion() + "|" + request.dueAt()));
            AssetHead head = lockAsset(identity.tenantId(), assetId);
            requireAssetContext(head, request.organizationId(), request.facilityId(), request.patientId());
            requireVersion(head, request.expectedRowVersion());
            if (!"BORROWED".equals(head.status())) throw stateInvalid("Only an active borrow can be edited");
            jdbc.sql("""
                    update medical_record_asset set due_at = :due,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and medical_record_asset_id = :asset and row_version = :expected
                    """).param("due", request.dueAt().atOffset(ZoneOffset.UTC))
                    .param("tenant", identity.tenantId()).param("asset", assetId)
                    .param("expected", head.rowVersion()).update();
            appendEvidence(identity, head.patientId(), assetId, "MEDICAL_RECORD_ASSET_BORROW_UPDATED",
                    "MedicalRecordAssetBorrowUpdated");
            completeCommand(identity, "MEDICAL_RECORD_ASSET_BORROW_UPDATE", idempotencyKey, assetId);
            return asset(identity.tenantId(), assetId);
        });
    }

    List<MedicalRecordAssetWire> listAssets(
            ClinicalIdentity identity, UUID organizationId, UUID facilityId, UUID patientId) {
        requireRole(identity, facilityId);
        return jdbc.sql("""
                select medical_record_asset_id from medical_record_asset
                where tenant_id = :tenant and organization_id = :organization
                  and facility_id = :facility and patient_id = :patient
                order by created_at desc, medical_record_asset_id desc limit 100
                """).param("tenant", identity.tenantId()).param("organization", organizationId)
                .param("facility", facilityId).param("patient", patientId)
                .query(UUID.class).list().stream()
                .map(id -> asset(identity.tenantId(), id)).toList();
    }

    MedicalRecordAssetWire update(
            ClinicalIdentity identity, String idempotencyKey, UUID assetId,
            MedicalRecordAssetUpdateRequestWire request) {
        requireRole(identity, request.facilityId());
        String displayName = requireText(request.displayName(), 2, "display_name");
        String mediaType = requireText(request.mediaType(), 3, "media_type");
        int pageCount = requireRange(request.pageCount(), 1, 100000, "page_count");
        String sourceSystem = requireText(request.sourceSystem(), 2, "source_system");
        String custodyLocation = requireText(request.custodyLocation(), 2, "custody_location");
        return transactions.execute(status -> {
            beginCommand(identity, "MEDICAL_RECORD_ASSET_UPDATE", idempotencyKey,
                    sha256(assetId + "|" + request.expectedRowVersion() + "|" + displayName + "|" + custodyLocation));
            AssetHead head = lockAsset(identity.tenantId(), assetId);
            requireAssetContext(head, request.organizationId(), request.facilityId(), request.patientId());
            int retentionYears = legalRetentionYears(identity.tenantId(), head.patientId(), head.encounterId(),
                    request.retentionYears());
            requireVersion(head, request.expectedRowVersion());
            if ("RETIRED".equals(head.status())) {
                throw stateInvalid("A retired asset cannot be edited");
            }
            if (!head.cdaStatus().equals(request.cdaStatus().name())) {
                throw invalid("cda_status is evidence-derived and cannot be edited manually");
            }
            requireScanStatus(head.assetType(), request.scanStatus().name());
            requireScanTransition(head.scanStatus(), request.scanStatus().name());
            requirePreservationTransition(
                    head.preservationStatus(), request.preservationStatus().name(), head.integrityStatus());
            boolean sealing = "SEALED".equals(request.preservationStatus().name())
                    && !"SEALED".equals(head.preservationStatus());
            Instant retainUntil = sealing ? retentionUntil(head.retentionBasisDate(), retentionYears) : null;
            ArchiveObjectStorage.SealEvidence sealEvidence = null;
            if (sealing) {
                if (!"AVAILABLE".equals(head.storageStatus()) || head.storageKey() == null) {
                    throw stateInvalid("Stored immutable content is required before WORM sealing");
                }
                if (!"PASSED".equals(head.malwareScanStatus())) {
                    throw stateInvalid("A real malware scan must pass before long-term sealing");
                }
                sealEvidence = storage.seal(head.storageKey(), retainUntil);
            }
            jdbc.sql("""
                    update medical_record_asset
                    set display_name = :display_name, media_type = :media_type, page_count = :page_count,
                      source_system = :source_system, custody_location = :custody_location,
                      cda_status = :cda_status, scan_status = :scan_status,
                      preservation_status = :preservation_status, retention_years = :retention_years,
                      storage_status = case when :sealing then 'SEALED' else storage_status end,
                      object_lock_status = case when :sealing then 'LOCKED' else object_lock_status end,
                      worm_retain_until = case when :sealing then :retain_until else worm_retain_until end,
                      storage_provider = case when :sealing then :storage_provider else storage_provider end,
                      object_lock_evidence = case when :sealing then :lock_evidence else object_lock_evidence end,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and medical_record_asset_id = :asset and row_version = :expected
                    """).param("tenant", identity.tenantId()).param("asset", assetId)
                    .param("display_name", displayName).param("media_type", mediaType)
                    .param("page_count", pageCount).param("source_system", sourceSystem)
                    .param("custody_location", custodyLocation).param("cda_status", request.cdaStatus().name())
                    .param("scan_status", request.scanStatus().name())
                    .param("preservation_status", request.preservationStatus().name())
                    .param("retention_years", retentionYears).param("sealing", sealing)
                    .param("retain_until", retainUntil == null ? null : retainUntil.atOffset(ZoneOffset.UTC))
                    .param("storage_provider", sealEvidence == null ? null : sealEvidence.provider())
                    .param("lock_evidence", sealEvidence == null ? null : sealEvidence.evidence())
                    .param("expected", head.rowVersion()).update();
            appendEvidence(identity, head.patientId(), assetId, "MEDICAL_RECORD_ASSET_UPDATED",
                    "MedicalRecordAssetUpdated");
            completeCommand(identity, "MEDICAL_RECORD_ASSET_UPDATE", idempotencyKey, assetId);
            return asset(identity.tenantId(), assetId);
        });
    }

    MedicalRecordAssetWire retire(
            ClinicalIdentity identity, String idempotencyKey, UUID assetId,
            MedicalRecordAssetRetireRequestWire request) {
        requireRole(identity, request.facilityId());
        String reason = requireText(request.reason(), 4, "reason");
        return transactions.execute(status -> {
            beginCommand(identity, "MEDICAL_RECORD_ASSET_RETIRE", idempotencyKey,
                    sha256(assetId + "|" + request.expectedRowVersion() + "|" + reason));
            AssetHead head = lockAsset(identity.tenantId(), assetId);
            requireAssetContext(head, request.organizationId(), request.facilityId(), request.patientId());
            requireVersion(head, request.expectedRowVersion());
            if (!"ARCHIVED".equals(head.status())) {
                throw stateInvalid("Only an in-library asset can be retired");
            }
            if ("SEALED".equals(head.preservationStatus()) || "VERIFIED".equals(head.preservationStatus())) {
                throw stateInvalid("Long-term preserved evidence cannot be retired; create a correction asset instead");
            }
            jdbc.sql("""
                    update medical_record_asset
                    set status = 'RETIRED', retired_by = :actor, retired_at = now(),
                      retirement_reason = :reason, row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and medical_record_asset_id = :asset and row_version = :expected
                    """).param("tenant", identity.tenantId()).param("asset", assetId)
                    .param("actor", identity.userId()).param("reason", reason)
                    .param("expected", head.rowVersion()).update();
            appendEvidence(identity, head.patientId(), assetId, "MEDICAL_RECORD_ASSET_RETIRED",
                    "MedicalRecordAssetRetired");
            completeCommand(identity, "MEDICAL_RECORD_ASSET_RETIRE", idempotencyKey, assetId);
            return asset(identity.tenantId(), assetId);
        });
    }

    MedicalRecordAssetIntegrityEventWire verifyIntegrity(
            ClinicalIdentity identity, String idempotencyKey, UUID assetId,
            MedicalRecordAssetIntegrityCheckRequestWire request) {
        requireRole(identity, request.facilityId());
        String observedHash = requireHash(request.observedHash()).toLowerCase();
        return transactions.execute(status -> {
            beginCommand(identity, "MEDICAL_RECORD_ASSET_VERIFY", idempotencyKey,
                    sha256(assetId + "|" + request.expectedRowVersion() + "|" + observedHash));
            AssetHead head = lockAsset(identity.tenantId(), assetId);
            requireAssetContext(head, request.organizationId(), request.facilityId(), request.patientId());
            requireVersion(head, request.expectedRowVersion());
            if ("RETIRED".equals(head.status())) throw stateInvalid("A retired asset cannot be re-verified");
            UUID eventId = recordIntegrity(identity, assetId, head, observedHash);
            completeCommand(identity, "MEDICAL_RECORD_ASSET_VERIFY", idempotencyKey, eventId);
            return integrityEvent(identity.tenantId(), eventId);
        });
    }

    MedicalRecordAssetIntegrityEventWire verifyStoredContent(
            ClinicalIdentity identity, String idempotencyKey, UUID assetId,
            MedicalRecordAssetActionRequestWire request) {
        requireRole(identity, request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "MEDICAL_RECORD_ASSET_STORAGE_VERIFY", idempotencyKey,
                    sha256(assetId + "|" + request.expectedRowVersion()));
            AssetHead head = lockAsset(identity.tenantId(), assetId);
            requireAssetContext(head, request.organizationId(), request.facilityId(), request.patientId());
            requireVersion(head, request.expectedRowVersion());
            if ("RETIRED".equals(head.status())) throw stateInvalid("A retired asset cannot be re-verified");
            if (head.storageKey() == null || "MISSING".equals(head.storageStatus())) {
                throw stateInvalid("Stored immutable content is required for server-side verification");
            }
            String observedHash = sha256(storage.read(head.storageKey()));
            UUID eventId = recordIntegrity(identity, assetId, head, observedHash);
            completeCommand(identity, "MEDICAL_RECORD_ASSET_STORAGE_VERIFY", idempotencyKey, eventId);
            return integrityEvent(identity.tenantId(), eventId);
        });
    }

    MedicalRecordAssetWire runOcr(
            ClinicalIdentity identity, String idempotencyKey, UUID assetId,
            MedicalRecordAssetActionRequestWire request) {
        requireRole(identity, request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "MEDICAL_RECORD_ASSET_OCR", idempotencyKey,
                    sha256(assetId + "|" + request.expectedRowVersion()));
            AssetHead head = lockAsset(identity.tenantId(), assetId);
            requireAssetContext(head, request.organizationId(), request.facilityId(), request.patientId());
            requireVersion(head, request.expectedRowVersion());
            if (!"SCAN".equals(head.assetType()) || !"CAPTURED".equals(head.scanStatus())) {
                throw stateInvalid("Only a captured scan can enter OCR review");
            }
            if (head.storageKey() == null || "MISSING".equals(head.storageStatus())) {
                throw stateInvalid("Stored scan content is required before OCR");
            }
            byte[] content = storage.read(head.storageKey());
            ArchiveOcrEngine.OcrResult result = ocr.extract(content, head.mediaType(), head.originalFilename());
            jdbc.sql("""
                    update medical_record_asset set ocr_status = 'COMPLETED', ocr_text = :ocr_text,
                      ocr_confidence = :confidence, ocr_engine = :engine, ocr_completed_at = now(),
                      scan_status = 'OCR_REVIEWED', row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and medical_record_asset_id = :asset and row_version = :expected
                    """).param("ocr_text", result.text()).param("confidence", result.confidence())
                    .param("engine", result.engine()).param("tenant", identity.tenantId())
                    .param("asset", assetId).param("expected", head.rowVersion()).update();
            appendEvidence(identity, head.patientId(), assetId, "MEDICAL_RECORD_ASSET_OCR_COMPLETED",
                    "MedicalRecordAssetOcrCompleted");
            completeCommand(identity, "MEDICAL_RECORD_ASSET_OCR", idempotencyKey, assetId);
            return asset(identity.tenantId(), assetId);
        });
    }

    MedicalRecordAssetWire validateCda(
            ClinicalIdentity identity, String idempotencyKey, UUID assetId,
            MedicalRecordAssetActionRequestWire request) {
        requireRole(identity, request.facilityId());
        return transactions.execute(status -> {
            beginCommand(identity, "MEDICAL_RECORD_ASSET_CDA_VALIDATE", idempotencyKey,
                    sha256(assetId + "|" + request.expectedRowVersion()));
            AssetHead head = lockAsset(identity.tenantId(), assetId);
            requireAssetContext(head, request.organizationId(), request.facilityId(), request.patientId());
            requireVersion(head, request.expectedRowVersion());
            if (!"PENDING".equals(head.cdaStatus()) || !"application/xml".equals(head.mediaType())) {
                throw stateInvalid("Only a pending XML clinical document can enter CDA validation");
            }
            if (head.storageKey() == null || "MISSING".equals(head.storageStatus())) {
                throw stateInvalid("Stored XML content is required before CDA validation");
            }
            ArchiveDocumentValidator.ValidationResult result = documentValidator.validate(
                    storage.read(head.storageKey()), head.mediaType(), head.originalFilename());
            jdbc.sql("""
                    update medical_record_asset set cda_status = :cda_status,
                      cda_validation_engine = :engine, cda_validation_evidence_hash = :evidence_hash,
                      cda_validated_at = now(), row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and medical_record_asset_id = :asset and row_version = :expected
                    """).param("cda_status", result.valid() ? "VERIFIED" : "FAILED")
                    .param("engine", result.engine()).param("evidence_hash", result.evidenceHash())
                    .param("tenant", identity.tenantId()).param("asset", assetId)
                    .param("expected", head.rowVersion()).update();
            appendEvidence(identity, head.patientId(), assetId,
                    result.valid() ? "MEDICAL_RECORD_ASSET_CDA_VERIFIED" : "MEDICAL_RECORD_ASSET_CDA_FAILED",
                    result.valid() ? "MedicalRecordAssetCdaVerified" : "MedicalRecordAssetCdaFailed");
            completeCommand(identity, "MEDICAL_RECORD_ASSET_CDA_VALIDATE", idempotencyKey, assetId);
            return asset(identity.tenantId(), assetId);
        });
    }

    AssetBinary content(
            ClinicalIdentity identity, UUID organizationId, UUID facilityId, UUID patientId, UUID assetId) {
        requireRole(identity, facilityId);
        AssetHead head = lockAsset(identity.tenantId(), assetId);
        requireAssetContext(head, organizationId, facilityId, patientId);
        if (head.storageKey() == null || "MISSING".equals(head.storageStatus())) {
            throw stateInvalid("This catalogue entry has no stored binary content");
        }
        byte[] content = storage.read(head.storageKey());
        String observed = sha256(content);
        if (!observed.equals(head.contentHash())) {
            throw new MedicalRecordAssetException("MEDICAL_RECORD_ASSET_STORAGE_INTEGRITY_FAILED", 409,
                    "Stored content no longer matches the immutable catalogue hash");
        }
        return new AssetBinary(content, head.mediaType(), head.originalFilename(), observed);
    }

    MedicalRecordAssetDistributionPackageWire createDistribution(
            ClinicalIdentity identity, String idempotencyKey, UUID assetId,
            MedicalRecordAssetDistributionCreateRequestWire request) {
        requireRole(identity, request.facilityId());
        String purpose = requireText(request.purpose(), 2, "purpose");
        String recipient = requireText(request.recipientName(), 2, "recipient_name");
        String verificationMethod = requireText(request.identityVerificationMethod(), 4,
                "identity_verification_method");
        String authorizationBasis = requireText(request.authorizationBasis(), 4, "authorization_basis");
        String copyScope = requireText(request.copyScope(), 4, "copy_scope");
        if (request.requesterType() == null || request.deliveryChannel() == null) {
            throw invalid("requester_type and delivery_channel are required");
        }
        boolean statutoryRequest = request.requesterType()
                == MedicalRecordAssetDistributionCreateRequestWire.RequesterTypeValue.PUBLIC_SECURITY
                || request.requesterType()
                == MedicalRecordAssetDistributionCreateRequestWire.RequesterTypeValue.JUDICIAL;
        if (!statutoryRequest && !Boolean.TRUE.equals(request.separateConsentConfirmed())) {
            throw invalid("separate_consent_confirmed is required for medical record copy provision");
        }
        if (request.expiresAt() == null || !request.expiresAt().isAfter(Instant.now())
                || request.expiresAt().isAfter(Instant.now().plus(30, ChronoUnit.DAYS))) {
            throw invalid("expires_at must be within the next 30 days");
        }
        UUID packageId = UUID.randomUUID();
        String storageKey = identity.tenantId() + "/medical-record-assets/" + assetId
                + "/distributions/" + packageId + ".zip";
        try {
            return transactions.execute(status -> {
                beginCommand(identity, "MEDICAL_RECORD_ASSET_DISTRIBUTION_CREATE", idempotencyKey,
                        sha256(assetId + "|" + request.expectedRowVersion() + "|" + purpose + "|" + recipient));
                AssetHead head = lockAsset(identity.tenantId(), assetId);
            requireAssetContext(head, request.organizationId(), request.facilityId(), request.patientId());
                requireVersion(head, request.expectedRowVersion());
                if (!"ARCHIVED".equals(head.status()) || !"VERIFIED".equals(head.integrityStatus())) {
                    throw stateInvalid("Only an in-library, integrity-verified asset can be copied or distributed");
                }
                if (!"PASSED".equals(head.malwareScanStatus())) {
                    throw stateInvalid("A real malware scan must pass before a copy package can be created");
                }
                if (head.storageKey() == null || "MISSING".equals(head.storageStatus())) {
                    throw stateInvalid("Stored immutable content is required for distribution");
                }
                byte[] original = storage.read(head.storageKey());
                if (!sha256(original).equals(head.contentHash())) {
                    throw stateInvalid("Stored asset failed integrity verification before distribution");
                }
                String watermark = "用途声明（未改写原件） | " + recipient + " | " + purpose + " | "
                        + packageId + " | expires " + request.expiresAt();
                byte[] archive = distributionZip(packageId, assetId, head, original, watermark, request.expiresAt());
                String packageHash = sha256(archive);
                storage.putImmutable(storageKey, archive);
                jdbc.sql("""
                        insert into medical_record_asset_distribution_package(
                          tenant_id, distribution_package_id, medical_record_asset_id, patient_id,
                          purpose, recipient_name, watermark_text, original_filename, byte_size,
                          content_hash, storage_key, status, expires_at, created_by, requester_type,
                          identity_verification_method, authorization_basis, copy_scope,
                          separate_consent_confirmed, delivery_channel)
                        values (:tenant,:package,:asset,:patient,:purpose,:recipient,:watermark,:filename,
                          :byte_size,:hash,:storage_key,'READY',:expires_at,:actor,:requester_type,
                          :verification_method,:authorization_basis,:copy_scope,:consent,:delivery_channel)
                        """).param("tenant", identity.tenantId()).param("package", packageId)
                        .param("asset", assetId).param("patient", head.patientId()).param("purpose", purpose)
                        .param("recipient", recipient).param("watermark", watermark)
                        .param("filename", safeFilename(head.originalFilename()) + ".distribution.zip")
                        .param("byte_size", (long) archive.length).param("hash", packageHash)
                        .param("storage_key", storageKey).param("expires_at", request.expiresAt().atOffset(ZoneOffset.UTC))
                        .param("actor", identity.userId()).param("requester_type", request.requesterType().name())
                        .param("verification_method", verificationMethod).param("authorization_basis", authorizationBasis)
                        .param("copy_scope", copyScope).param("consent", Boolean.TRUE.equals(request.separateConsentConfirmed()))
                        .param("delivery_channel", request.deliveryChannel().name()).update();
                jdbc.sql("""
                        update medical_record_asset set row_version = row_version + 1, updated_at = now()
                        where tenant_id = :tenant and medical_record_asset_id = :asset and row_version = :expected
                        """).param("tenant", identity.tenantId()).param("asset", assetId)
                        .param("expected", head.rowVersion()).update();
                appendEvidence(identity, head.patientId(), assetId, "MEDICAL_RECORD_ASSET_DISTRIBUTION_CREATED",
                        "MedicalRecordAssetDistributionCreated");
                completeCommand(identity, "MEDICAL_RECORD_ASSET_DISTRIBUTION_CREATE", idempotencyKey, packageId);
                return distribution(identity.tenantId(), packageId);
            });
        } catch (RuntimeException failure) {
            storage.deleteUnsealedBestEffort(storageKey);
            throw failure;
        }
    }

    List<MedicalRecordAssetDistributionPackageWire> listDistributions(
            ClinicalIdentity identity, UUID organizationId, UUID facilityId, UUID patientId, UUID assetId) {
        requireRole(identity, facilityId);
        AssetHead head = lockAsset(identity.tenantId(), assetId);
        requireAssetContext(head, organizationId, facilityId, patientId);
        return jdbc.sql("""
                select distribution_package_id from medical_record_asset_distribution_package
                where tenant_id = :tenant and medical_record_asset_id = :asset
                order by created_at desc, distribution_package_id desc limit 100
                """).param("tenant", identity.tenantId()).param("asset", assetId)
                .query(UUID.class).list().stream().map(id -> distribution(identity.tenantId(), id)).toList();
    }

    MedicalRecordAssetDistributionPackageWire deliverDistribution(
            ClinicalIdentity identity, String idempotencyKey, UUID assetId, UUID packageId,
            MedicalRecordAssetDistributionDeliveryRequestWire request) {
        requireRole(identity, request.facilityId());
        String hospitalSealNo = requireText(request.hospitalSealNo(), 4, "hospital_seal_no");
        String deliveryReceiptNo = requireText(request.deliveryReceiptNo(), 4, "delivery_receipt_no");
        return transactions.execute(status -> {
            beginCommand(identity, "MEDICAL_RECORD_ASSET_DISTRIBUTION_DELIVER", idempotencyKey,
                    sha256(assetId + "|" + packageId + "|" + request.expectedRowVersion()));
            AssetHead head = lockAsset(identity.tenantId(), assetId);
            requireAssetContext(head, request.organizationId(), request.facilityId(), request.patientId());
            DistributionHead distribution = lockDistribution(identity.tenantId(), assetId, packageId);
            if (distribution.rowVersion() != request.expectedRowVersion()) throw versionConflict();
            if (!"READY".equals(distribution.status())) throw stateInvalid("Only a ready package can be delivered");
            if (!distribution.expiresAt().isAfter(Instant.now())) throw stateInvalid("Distribution package expired");
            jdbc.sql("""
                    update medical_record_asset_distribution_package
                    set status = 'DELIVERED', delivered_by = :actor, delivered_at = now(),
                      hospital_seal_no = :hospital_seal_no, delivery_receipt_no = :delivery_receipt_no,
                      row_version = row_version + 1
                    where tenant_id = :tenant and distribution_package_id = :package and row_version = :expected
                    """).param("actor", identity.userId()).param("tenant", identity.tenantId())
                    .param("hospital_seal_no", hospitalSealNo).param("delivery_receipt_no", deliveryReceiptNo)
                    .param("package", packageId).param("expected", distribution.rowVersion()).update();
            appendEvidence(identity, head.patientId(), assetId, "MEDICAL_RECORD_ASSET_DISTRIBUTION_DELIVERED",
                    "MedicalRecordAssetDistributionDelivered");
            completeCommand(identity, "MEDICAL_RECORD_ASSET_DISTRIBUTION_DELIVER", idempotencyKey, packageId);
            return distribution(identity.tenantId(), packageId);
        });
    }

    DistributionBinary distributionContent(
            ClinicalIdentity identity, UUID organizationId, UUID facilityId,
            UUID patientId, UUID assetId, UUID packageId) {
        requireRole(identity, facilityId);
        AssetHead head = lockAsset(identity.tenantId(), assetId);
        requireAssetContext(head, organizationId, facilityId, patientId);
        DistributionHead distribution = lockDistribution(identity.tenantId(), assetId, packageId);
        if (!distribution.expiresAt().isAfter(Instant.now())) throw stateInvalid("Distribution package expired");
        byte[] content = storage.read(distribution.storageKey());
        String observed = sha256(content);
        if (!observed.equals(distribution.contentHash())) {
            throw new MedicalRecordAssetException("MEDICAL_RECORD_ASSET_DISTRIBUTION_INTEGRITY_FAILED", 409,
                    "Distribution package no longer matches its immutable hash");
        }
        return new DistributionBinary(content, distribution.originalFilename(), observed);
    }

    List<MedicalRecordAssetIntegrityEventWire> listIntegrityEvents(
            ClinicalIdentity identity, UUID organizationId, UUID facilityId, UUID patientId, UUID assetId) {
        requireRole(identity, facilityId);
        AssetHead head = lockAsset(identity.tenantId(), assetId);
        requireAssetContext(head, organizationId, facilityId, patientId);
        return jdbc.sql("""
                select integrity_event_id from medical_record_asset_integrity_event
                where tenant_id = :tenant and medical_record_asset_id = :asset
                order by verified_at desc, integrity_event_id desc limit 100
                """).param("tenant", identity.tenantId()).param("asset", assetId)
                .query(UUID.class).list().stream().map(id -> integrityEvent(identity.tenantId(), id)).toList();
    }

    private MedicalRecordAssetWire asset(UUID tenantId, UUID assetId) {
        return jdbc.sql("""
                select medical_record_asset_id, patient_id, encounter_id, asset_type, location, content_hash,
                  status, display_name, media_type, page_count, source_system, custody_location,
                  integrity_status, cda_status, cda_validation_engine, cda_validation_evidence_hash,
                  cda_validated_at, scan_status, preservation_status, retention_years,
                  record_category, retention_basis_date, retention_until,
                  original_filename, byte_size, storage_status, malware_scan_status, malware_scan_engine,
                  ocr_status, ocr_text, ocr_confidence, ocr_engine, ocr_completed_at,
                  object_lock_status, worm_retain_until, storage_provider, object_lock_evidence, legal_hold_status,
                  last_verified_at, retired_by, retired_at, retirement_reason, created_at,
                  borrowed_by, borrowed_at, due_at, row_version
                from medical_record_asset
                where tenant_id = :tenant and medical_record_asset_id = :asset
                """).param("tenant", tenantId).param("asset", assetId)
                .query((rs, row) -> new MedicalRecordAssetWire(
                        rs.getObject("medical_record_asset_id", UUID.class),
                        rs.getObject("patient_id", UUID.class),
                        rs.getObject("encounter_id", UUID.class),
                        MedicalRecordAssetWire.AssetTypeValue.valueOf(rs.getString("asset_type")),
                        rs.getString("location"),
                        rs.getString("content_hash"),
                        MedicalRecordAssetWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getString("display_name"),
                        rs.getString("media_type"),
                        rs.getInt("page_count"),
                        rs.getString("source_system"),
                        rs.getString("custody_location"),
                        MedicalRecordAssetWire.IntegrityStatusValue.valueOf(rs.getString("integrity_status")),
                        MedicalRecordAssetWire.CdaStatusValue.valueOf(rs.getString("cda_status")),
                        rs.getString("cda_validation_engine"), rs.getString("cda_validation_evidence_hash"),
                        instant(rs.getObject("cda_validated_at", OffsetDateTime.class)),
                        MedicalRecordAssetWire.ScanStatusValue.valueOf(rs.getString("scan_status")),
                        MedicalRecordAssetWire.PreservationStatusValue.valueOf(rs.getString("preservation_status")),
                        rs.getInt("retention_years"),
                        MedicalRecordAssetWire.RecordCategoryValue.valueOf(rs.getString("record_category")),
                        rs.getObject("retention_basis_date", LocalDate.class),
                        rs.getObject("retention_until", LocalDate.class),
                        rs.getString("original_filename"),
                        rs.getObject("byte_size", Long.class),
                        MedicalRecordAssetWire.StorageStatusValue.valueOf(rs.getString("storage_status")),
                        MedicalRecordAssetWire.MalwareScanStatusValue.valueOf(rs.getString("malware_scan_status")),
                        rs.getString("malware_scan_engine"),
                        MedicalRecordAssetWire.OcrStatusValue.valueOf(rs.getString("ocr_status")),
                        rs.getString("ocr_text"),
                        rs.getBigDecimal("ocr_confidence") == null
                                ? null : rs.getBigDecimal("ocr_confidence").doubleValue(),
                        rs.getString("ocr_engine"),
                        instant(rs.getObject("ocr_completed_at", OffsetDateTime.class)),
                        MedicalRecordAssetWire.ObjectLockStatusValue.valueOf(rs.getString("object_lock_status")),
                        instant(rs.getObject("worm_retain_until", OffsetDateTime.class)),
                        rs.getString("storage_provider"), rs.getString("object_lock_evidence"),
                        MedicalRecordAssetWire.LegalHoldStatusValue.valueOf(rs.getString("legal_hold_status")),
                        instant(rs.getObject("last_verified_at", OffsetDateTime.class)),
                        rs.getObject("retired_by", UUID.class),
                        instant(rs.getObject("retired_at", OffsetDateTime.class)),
                        rs.getString("retirement_reason"),
                        instant(rs.getObject("created_at", OffsetDateTime.class)),
                        rs.getObject("borrowed_by", UUID.class),
                        instant(rs.getObject("borrowed_at", OffsetDateTime.class)),
                        instant(rs.getObject("due_at", OffsetDateTime.class)),
                        rs.getLong("row_version")))
                .optional().orElseThrow(MedicalRecordAssetService::contextDenied);
    }

    private MedicalRecordAssetDistributionPackageWire distribution(UUID tenantId, UUID packageId) {
        return jdbc.sql("""
                select distribution_package_id, medical_record_asset_id, patient_id, purpose,
                  recipient_name, requester_type, identity_verification_method, authorization_basis,
                  copy_scope, separate_consent_confirmed, delivery_channel, hospital_seal_no,
                  delivery_receipt_no, watermark_text, original_filename, media_type, byte_size,
                  content_hash, status, expires_at, created_by, created_at, delivered_by,
                  delivered_at, row_version
                from medical_record_asset_distribution_package
                where tenant_id = :tenant and distribution_package_id = :package
                """).param("tenant", tenantId).param("package", packageId)
                .query((rs, row) -> new MedicalRecordAssetDistributionPackageWire(
                        rs.getObject("distribution_package_id", UUID.class),
                        rs.getObject("medical_record_asset_id", UUID.class),
                        rs.getObject("patient_id", UUID.class), rs.getString("purpose"),
                        rs.getString("recipient_name"),
                        MedicalRecordAssetDistributionPackageWire.RequesterTypeValue.valueOf(rs.getString("requester_type")),
                        rs.getString("identity_verification_method"), rs.getString("authorization_basis"),
                        rs.getString("copy_scope"), rs.getBoolean("separate_consent_confirmed"),
                        MedicalRecordAssetDistributionPackageWire.DeliveryChannelValue.valueOf(rs.getString("delivery_channel")),
                        rs.getString("hospital_seal_no"), rs.getString("delivery_receipt_no"),
                        rs.getString("watermark_text"),
                        rs.getString("original_filename"), rs.getString("media_type"),
                        rs.getLong("byte_size"), rs.getString("content_hash"),
                        MedicalRecordAssetDistributionPackageWire.StatusValue.valueOf(rs.getString("status")),
                        instant(rs.getObject("expires_at", OffsetDateTime.class)),
                        rs.getObject("created_by", UUID.class), instant(rs.getObject("created_at", OffsetDateTime.class)),
                        rs.getObject("delivered_by", UUID.class),
                        instant(rs.getObject("delivered_at", OffsetDateTime.class)), rs.getLong("row_version")))
                .optional().orElseThrow(MedicalRecordAssetService::contextDenied);
    }

    private MedicalRecordAssetIntegrityEventWire integrityEvent(UUID tenantId, UUID eventId) {
        return jdbc.sql("""
                select integrity_event_id, medical_record_asset_id, expected_hash, observed_hash,
                  result, verified_by, verified_at
                from medical_record_asset_integrity_event
                where tenant_id = :tenant and integrity_event_id = :event
                """).param("tenant", tenantId).param("event", eventId)
                .query((rs, row) -> new MedicalRecordAssetIntegrityEventWire(
                        rs.getObject("integrity_event_id", UUID.class),
                        rs.getObject("medical_record_asset_id", UUID.class),
                        rs.getString("expected_hash"), rs.getString("observed_hash"),
                        MedicalRecordAssetIntegrityEventWire.ResultValue.valueOf(rs.getString("result")),
                        rs.getObject("verified_by", UUID.class),
                        instant(rs.getObject("verified_at", OffsetDateTime.class))))
                .optional().orElseThrow(MedicalRecordAssetService::contextDenied);
    }

    private UUID recordIntegrity(
            ClinicalIdentity identity, UUID assetId, AssetHead head, String observedHash) {
        String result = head.contentHash().equalsIgnoreCase(observedHash) ? "VERIFIED" : "FAILED";
        UUID eventId = UUID.randomUUID();
        jdbc.sql("""
                insert into medical_record_asset_integrity_event(
                  tenant_id, integrity_event_id, medical_record_asset_id, expected_hash,
                  observed_hash, result, verified_by)
                values (:tenant, :event, :asset, :expected_hash, :observed_hash, :result, :actor)
                """).param("tenant", identity.tenantId()).param("event", eventId).param("asset", assetId)
                .param("expected_hash", head.contentHash()).param("observed_hash", observedHash)
                .param("result", result).param("actor", identity.userId()).update();
        jdbc.sql("""
                update medical_record_asset
                set integrity_status = :result, last_verified_at = now(),
                  preservation_status = case
                    when :result = 'VERIFIED' and preservation_status = 'SEALED' then 'VERIFIED'
                    else preservation_status end,
                  row_version = row_version + 1, updated_at = now()
                where tenant_id = :tenant and medical_record_asset_id = :asset and row_version = :expected
                """).param("result", result).param("tenant", identity.tenantId()).param("asset", assetId)
                .param("expected", head.rowVersion()).update();
        appendEvidence(identity, head.patientId(), assetId, "MEDICAL_RECORD_ASSET_INTEGRITY_" + result,
                "MedicalRecordAssetIntegrity" + result);
        return eventId;
    }

    private AssetHead lockAsset(UUID tenantId, UUID assetId) {
        return jdbc.sql("""
                select organization_id, facility_id, patient_id, encounter_id, asset_type, status,
                  content_hash, integrity_status, cda_status,
                  scan_status, preservation_status, storage_key, storage_status,
                  malware_scan_status, media_type, original_filename, retention_basis_date, row_version
                from medical_record_asset
                where tenant_id = :tenant and medical_record_asset_id = :asset for update
                """).param("tenant", tenantId).param("asset", assetId)
                .query((rs, row) -> new AssetHead(
                        rs.getObject("organization_id", UUID.class), rs.getObject("facility_id", UUID.class),
                        rs.getObject("patient_id", UUID.class), rs.getObject("encounter_id", UUID.class),
                        rs.getString("asset_type"), rs.getString("status"),
                        rs.getString("content_hash"), rs.getString("integrity_status"),
                        rs.getString("cda_status"),
                        rs.getString("scan_status"), rs.getString("preservation_status"),
                        rs.getString("storage_key"), rs.getString("storage_status"),
                        rs.getString("malware_scan_status"), rs.getString("media_type"),
                        rs.getString("original_filename"), rs.getObject("retention_basis_date", LocalDate.class),
                        rs.getLong("row_version")))
                .optional().orElseThrow(MedicalRecordAssetService::contextDenied);
    }

    private DistributionHead lockDistribution(UUID tenantId, UUID assetId, UUID packageId) {
        return jdbc.sql("""
                select status, expires_at, storage_key, original_filename, content_hash, row_version
                from medical_record_asset_distribution_package
                where tenant_id = :tenant and medical_record_asset_id = :asset
                  and distribution_package_id = :package for update
                """).param("tenant", tenantId).param("asset", assetId).param("package", packageId)
                .query((rs, row) -> new DistributionHead(rs.getString("status"),
                        instant(rs.getObject("expires_at", OffsetDateTime.class)), rs.getString("storage_key"),
                        rs.getString("original_filename"), rs.getString("content_hash"), rs.getLong("row_version")))
                .optional().orElseThrow(MedicalRecordAssetService::contextDenied);
    }

    private void requireAssetContext(AssetHead head, UUID organizationId, UUID facilityId, UUID patientId) {
        if (!head.organizationId().equals(organizationId) || !head.facilityId().equals(facilityId)
                || !head.patientId().equals(patientId)) throw contextDenied();
    }

    private void requirePatient(UUID tenantId, UUID patientId) {
        long count = jdbc.sql("""
                select count(*) from patient where tenant_id = :tenant and patient_id = :patient
                """).param("tenant", tenantId).param("patient", patientId).query(Long.class).single();
        if (count != 1) throw contextDenied();
    }

    private void requireRole(ClinicalIdentity identity, UUID facilityId) {
        if (identity.roleAssignmentIds().isEmpty()) {
            throw new MedicalRecordAssetException("MEDICAL_RECORD_ASSET_ROLE_REQUIRED", 403,
                    "An active medical records role is required");
        }
        long count = jdbc.sql("""
                select count(*) from role_assignment
                where tenant_id = :tenant and user_id = :user and role_assignment_id in (:assignments)
                  and role_code in (:roles) and status = 'ACTIVE' and valid_from <= now()
                  and (valid_until is null or valid_until > now())
                  and (facility_id is null or facility_id = :facility)
                """).param("tenant", identity.tenantId()).param("user", identity.userId())
                .param("assignments", identity.roleAssignmentIds()).param("roles", ASSET_ROLES)
                .param("facility", facilityId).query(Long.class).single();
        if (count < 1) {
            throw new MedicalRecordAssetException("MEDICAL_RECORD_ASSET_ROLE_REQUIRED", 403,
                    "The active role does not authorize medical record asset operations");
        }
    }

    private int legalRetentionYears(UUID tenantId, UUID patientId, UUID encounterId, Integer requestedYears) {
        String category = encounterId == null ? "INPATIENT" : jdbc.sql("""
                select case when encounter_type = 'INPATIENT' then 'INPATIENT' else 'OUTPATIENT' end
                from encounter where tenant_id = :tenant and encounter_id = :encounter and patient_id = :patient
                """).param("tenant", tenantId).param("encounter", encounterId).param("patient", patientId)
                .query(String.class).optional().orElseThrow(MedicalRecordAssetService::contextDenied);
        int minimum = "INPATIENT".equals(category) ? 30 : 15;
        int years = requestedYears == null ? minimum : requireRange(requestedYears, minimum, 100, "retention_years");
        if (years < minimum) throw invalid("retention_years is below the legal minimum for " + category);
        return years;
    }

    private static Instant retentionUntil(LocalDate basisDate, int retentionYears) {
        LocalDate basis = basisDate == null ? LocalDate.now(ZoneOffset.UTC) : basisDate;
        return basis.plusYears(retentionYears).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new MedicalRecordAssetException("INVALID_IDEMPOTENCY_KEY", 400,
                    "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new MedicalRecordAssetException("IDEMPOTENCY_REPLAY", 409,
                    "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID assetId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", assetId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(
            ClinicalIdentity identity, UUID patientId, UUID assetId, String action, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + assetId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'MEDICAL_RECORD_ASSET', :resource,
                  :patient_hash, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", assetId)
                .param("patient_hash", sha256(identity.tenantId() + "|" + patientId))
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        long aggregateVersion = jdbc.sql("""
                select row_version from medical_record_asset
                where tenant_id = :tenant and medical_record_asset_id = :asset
                """).param("tenant", identity.tenantId()).param("asset", assetId)
                .query(Long.class).single();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'MEDICAL_RECORD_ASSET', :aggregate, :aggregate_version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", assetId).param("aggregate_version", aggregateVersion)
                .param("event_type", eventType).update();
    }

    private static String requireText(String value, int minLength, String field) {
        if (value == null || value.trim().length() < minLength) {
            throw invalid(field + " must be at least " + minLength + " characters");
        }
        return value.trim();
    }

    private static String requireHash(String value) {
        if (value == null || !value.trim().matches("[0-9a-fA-F]{64}")) {
            throw invalid("content_hash must be exactly 64 hexadecimal characters");
        }
        return value.trim();
    }

    private static String optionalText(String value, String fallback, int minLength, String field) {
        return value == null || value.isBlank() ? fallback : requireText(value, minLength, field);
    }

    private static int requireRange(Integer value, int minimum, int maximum, String field) {
        if (value == null || value < minimum || value > maximum) {
            throw invalid(field + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static String requireMediaType(String value) {
        String mediaType = requireText(value, 3, "media_type").toLowerCase();
        if (!Set.of("application/pdf", "image/jpeg", "image/png", "text/plain", "application/xml")
                .contains(mediaType)) {
            throw invalid("media_type is not allowed for medical record asset ingestion");
        }
        return mediaType;
    }

    private static String safeFilename(String value) {
        String filename = requireText(value, 1, "original_filename").replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1).trim();
        if (filename.isEmpty() || filename.length() > 255 || filename.equals(".") || filename.equals("..")) {
            throw invalid("original_filename is invalid");
        }
        return filename.replaceAll("[^\\p{L}\\p{N}._() -]", "_");
    }

    private static byte[] decodeContent(String value) {
        if (value == null || value.isBlank()) throw invalid("content_base64 is required");
        try {
            byte[] content = Base64.getDecoder().decode(value);
            if (content.length < 1 || content.length > 50 * 1024 * 1024) {
                throw invalid("asset content must be between 1 byte and 50 MiB");
            }
            return content;
        } catch (IllegalArgumentException malformed) {
            throw invalid("content_base64 is not valid base64");
        }
    }

    private static void validateMagic(String mediaType, byte[] content) {
        boolean valid = switch (mediaType) {
            case "application/pdf" -> startsWith(content, "%PDF-".getBytes(StandardCharsets.US_ASCII));
            case "image/png" -> startsWith(content, new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
            case "image/jpeg" -> content.length >= 3 && (content[0] & 0xff) == 0xff
                    && (content[1] & 0xff) == 0xd8 && (content[2] & 0xff) == 0xff;
            case "application/xml" -> new String(content, 0, Math.min(content.length, 256), StandardCharsets.UTF_8)
                    .stripLeading().startsWith("<");
            case "text/plain" -> !new String(content, StandardCharsets.UTF_8).contains("\u0000");
            default -> false;
        };
        if (!valid) throw new MedicalRecordAssetException("MEDICAL_RECORD_ASSET_MEDIA_MISMATCH", 422,
                "Asset bytes do not match the declared media type");
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index += 1) {
            if (value[index] != prefix[index]) return false;
        }
        return true;
    }

    private byte[] distributionZip(
            UUID packageId, UUID assetId, AssetHead head, byte[] original,
            String watermark, Instant expiresAt) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
                ZipEntry originalEntry = new ZipEntry("original/" + safeFilename(head.originalFilename()));
                originalEntry.setTime(0);
                zip.putNextEntry(originalEntry);
                zip.write(original);
                zip.closeEntry();
                byte[] manifest = mapper.writeValueAsBytes(Map.of(
                        "schema", "haoemr.medical-record-asset.distribution.v1",
                        "distribution_package_id", packageId.toString(),
                        "medical_record_asset_id", assetId.toString(),
                        "original_sha256", head.contentHash(),
                        "watermark", watermark,
                        "expires_at", expiresAt.toString()));
                ZipEntry manifestEntry = new ZipEntry("manifest.json");
                manifestEntry.setTime(0);
                zip.putNextEntry(manifestEntry);
                zip.write(manifest);
                zip.closeEntry();
            }
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new MedicalRecordAssetException("MEDICAL_RECORD_ASSET_DISTRIBUTION_BUILD_FAILED", 503,
                    "Distribution package could not be built");
        }
    }

    private static void requireVersion(AssetHead head, Long expectedRowVersion) {
        if (expectedRowVersion == null || head.rowVersion() != expectedRowVersion) throw versionConflict();
    }

    private static void requireScanStatus(String assetType, String scanStatus) {
        if ("SCAN".equals(assetType) && "NOT_APPLICABLE".equals(scanStatus)) {
            throw invalid("scan_status is required for scanned assets");
        }
        if (!"SCAN".equals(assetType) && !"NOT_APPLICABLE".equals(scanStatus)) {
            throw invalid("scan_status must be NOT_APPLICABLE for non-scan assets");
        }
    }

    private static void requireInitialCdaStatus(String cdaStatus) {
        if (!Set.of("NOT_APPLICABLE", "PENDING").contains(cdaStatus)) {
            throw invalid("cda_status must start as NOT_APPLICABLE or PENDING and can only change by validation");
        }
    }

    private static void requireScanTransition(String from, String to) {
        if (from.equals(to) || "NOT_APPLICABLE".equals(from)) return;
        boolean valid = ("CAPTURED".equals(from) && "OCR_REVIEWED".equals(to))
                || ("OCR_REVIEWED".equals(from) && "INDEXED".equals(to));
        if (!valid) throw stateInvalid("Invalid scan workflow transition");
    }

    private static void requirePreservationTransition(String from, String to, String integrityStatus) {
        if (from.equals(to)) return;
        boolean valid = ("NOT_SCHEDULED".equals(from) && "SCHEDULED".equals(to))
                || ("SCHEDULED".equals(from) && ("NOT_SCHEDULED".equals(to) || "SEALED".equals(to)));
        if (!valid) throw stateInvalid("Invalid preservation workflow transition");
        if ("SEALED".equals(to) && !"VERIFIED".equals(integrityStatus)) {
            throw stateInvalid("Integrity verification is required before long-term sealing");
        }
    }

    private static MedicalRecordAssetException invalid(String message) {
        return new MedicalRecordAssetException("MEDICAL_RECORD_ASSET_REQUEST_INVALID", 400, message);
    }

    private static MedicalRecordAssetException versionConflict() {
        return new MedicalRecordAssetException(
                "MEDICAL_RECORD_ASSET_VERSION_CONFLICT", 409, "The asset changed; reload before retrying");
    }

    private static MedicalRecordAssetException stateInvalid(String message) {
        return new MedicalRecordAssetException("MEDICAL_RECORD_ASSET_STATE_INVALID", 409, message);
    }

    static MedicalRecordAssetException contextDenied() {
        return new MedicalRecordAssetException(
                "CONTEXT_NOT_PERMITTED", 403, "The requested medical record asset context is not permitted");
    }

    private record AssetHead(
            UUID organizationId, UUID facilityId, UUID patientId, UUID encounterId, String assetType,
            String status, String contentHash, String integrityStatus,
            String cdaStatus, String scanStatus, String preservationStatus, String storageKey, String storageStatus,
            String malwareScanStatus, String mediaType, String originalFilename, LocalDate retentionBasisDate,
            long rowVersion) {}

    private record DistributionHead(
            String status, Instant expiresAt, String storageKey, String originalFilename,
            String contentHash, long rowVersion) {}

    record AssetBinary(byte[] content, String mediaType, String filename, String contentHash) {}
    record DistributionBinary(byte[] content, String filename, String contentHash) {}

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
