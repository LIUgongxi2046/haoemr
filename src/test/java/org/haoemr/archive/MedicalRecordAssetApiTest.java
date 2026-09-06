package org.haoemr.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.MedicalRecordAssetBorrowRequestWire;
import org.haoemr.contracts.MedicalRecordAssetActionRequestWire;
import org.haoemr.contracts.MedicalRecordAssetDistributionCreateRequestWire;
import org.haoemr.contracts.MedicalRecordAssetDistributionDeliveryRequestWire;
import org.haoemr.contracts.MedicalRecordAssetDistributionPackageWire;
import org.haoemr.contracts.MedicalRecordAssetIngestRequestWire;
import org.haoemr.contracts.MedicalRecordAssetBorrowUpdateRequestWire;
import org.haoemr.contracts.MedicalRecordAssetIntegrityCheckRequestWire;
import org.haoemr.contracts.MedicalRecordAssetRegisterRequestWire;
import org.haoemr.contracts.MedicalRecordAssetRetireRequestWire;
import org.haoemr.contracts.MedicalRecordAssetReturnRequestWire;
import org.haoemr.contracts.MedicalRecordAssetUpdateRequestWire;
import org.haoemr.contracts.MedicalRecordAssetWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class MedicalRecordAssetApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa14";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa15";
    private static final String CONTENT_HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Autowired
    private MedicalRecordAssetService assets;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    private ClinicalIdentity nonRecordsIdentity() {
        return new ClinicalIdentity(tenant,
                UUID.fromString("018f0000-0000-7000-8000-00000000aa04"),
                List.of(UUID.fromString("018f0000-0000-7000-8000-00000000aa09")));
    }

    private UUID seedPatient() {
        UUID patientId = UUID.randomUUID();
        jdbc.sql("""
                insert into patient(tenant_id, patient_id, display_name, sex_code, birth_date, status)
                values (cast(:tenant as uuid), :patient, '合成病案患者', 'F', :birth, 'ACTIVE')
                """).param("tenant", TENANT).param("patient", patientId)
                .param("birth", LocalDate.of(1960, 7, 7)).update();
        return patientId;
    }

    private MedicalRecordAssetWire register(UUID patientId) {
        return assets.register(identity(), "asset-" + UUID.randomUUID(),
                new MedicalRecordAssetRegisterRequestWire(organization, facility, patientId, null,
                        MedicalRecordAssetRegisterRequestWire.AssetTypeValue.PAPER, "档案库房-3-2", CONTENT_HASH,
                        null, null, null, null, null, null, null, null));
    }

    private MedicalRecordAssetWire borrow(UUID patientId, UUID assetId, long expectedRowVersion, Instant dueAt) {
        return assets.borrow(identity(), "borrow-" + UUID.randomUUID(), assetId,
                new MedicalRecordAssetBorrowRequestWire(organization, facility, patientId, expectedRowVersion, dueAt));
    }

    private MedicalRecordAssetWire verify(UUID patientId, MedicalRecordAssetWire asset) {
        assets.verifyIntegrity(identity(), "verify-" + UUID.randomUUID(), asset.medicalRecordAssetId(),
                new MedicalRecordAssetIntegrityCheckRequestWire(
                        organization, facility, patientId, CONTENT_HASH, asset.rowVersion()));
        return assets.listAssets(identity(), organization, facility, patientId).stream()
                .filter(candidate -> candidate.medicalRecordAssetId().equals(asset.medicalRecordAssetId()))
                .findFirst().orElseThrow();
    }

    private MedicalRecordAssetWire returnAsset(UUID patientId, UUID assetId, long expectedRowVersion) {
        return assets.returnAsset(identity(), "return-" + UUID.randomUUID(), assetId,
                new MedicalRecordAssetReturnRequestWire(organization, facility, patientId, expectedRowVersion));
    }

    @Test
    void givenAsset_whenRegisteringAndListing_thenArchived() {
        UUID patientId = seedPatient();
        MedicalRecordAssetWire registered = register(patientId);
        assertThat(registered.status()).isEqualTo(MedicalRecordAssetWire.StatusValue.ARCHIVED);
        assertThat(registered.contentHash()).isEqualTo(CONTENT_HASH);

        List<MedicalRecordAssetWire> listed = assets.listAssets(identity(), organization, facility, patientId);
        assertThat(listed).extracting(MedicalRecordAssetWire::medicalRecordAssetId)
                .contains(registered.medicalRecordAssetId());
    }

    @Test
    void productionRoleAndChineseRetentionGatesFailClosed() {
        UUID patientId = seedPatient();
        MedicalRecordAssetRegisterRequestWire fifteenYears = new MedicalRecordAssetRegisterRequestWire(
                organization, facility, patientId, null,
                MedicalRecordAssetRegisterRequestWire.AssetTypeValue.PAPER, "病案库房-3-2", CONTENT_HASH,
                null, null, null, null, null, null, null, 15);
        assertThatThrownBy(() -> assets.register(identity(), "retention-denied-" + UUID.randomUUID(), fifteenYears))
                .isInstanceOf(MedicalRecordAssetException.class)
                .satisfies(error -> assertThat(((MedicalRecordAssetException) error).code())
                        .isEqualTo("MEDICAL_RECORD_ASSET_REQUEST_INVALID"));
        assertThatThrownBy(() -> assets.register(nonRecordsIdentity(), "role-denied-" + UUID.randomUUID(),
                new MedicalRecordAssetRegisterRequestWire(
                        organization, facility, patientId, null,
                        MedicalRecordAssetRegisterRequestWire.AssetTypeValue.PAPER, "病案库房-3-2", CONTENT_HASH,
                        null, null, null, null, null, null, null, 30)))
                .isInstanceOf(MedicalRecordAssetException.class)
                .satisfies(error -> assertThat(((MedicalRecordAssetException) error).code())
                        .isEqualTo("MEDICAL_RECORD_ASSET_ROLE_REQUIRED"));
        assertThatThrownBy(() -> assets.listAssets(nonRecordsIdentity(), organization, facility, patientId))
                .isInstanceOf(MedicalRecordAssetException.class)
                .satisfies(error -> assertThat(((MedicalRecordAssetException) error).code())
                        .isEqualTo("MEDICAL_RECORD_ASSET_ROLE_REQUIRED"));
    }

    @Test
    void malwareAndCdaStatusesComeFromServerAdaptersNotUserClaims() {
        UUID patientId = seedPatient();
        byte[] eicar = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"
                .getBytes(StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> assets.ingest(identity(), "eicar-" + UUID.randomUUID(),
                new MedicalRecordAssetIngestRequestWire(
                        organization, facility, patientId, null,
                        MedicalRecordAssetIngestRequestWire.AssetTypeValue.DIGITAL, "安全测试站-01", "EICAR 合成检测文件",
                        "eicar.txt", "text/plain", 1, "SECURITY-TEST", Base64.getEncoder().encodeToString(eicar),
                        MedicalRecordAssetIngestRequestWire.CdaStatusValue.NOT_APPLICABLE, 30)))
                .isInstanceOf(MedicalRecordAssetException.class)
                .satisfies(error -> assertThat(((MedicalRecordAssetException) error).code())
                        .isEqualTo("MEDICAL_RECORD_ASSET_MALWARE_REJECTED"));

        assertThatThrownBy(() -> assets.register(identity(), "fake-cda-" + UUID.randomUUID(),
                new MedicalRecordAssetRegisterRequestWire(
                        organization, facility, patientId, null,
                        MedicalRecordAssetRegisterRequestWire.AssetTypeValue.DIGITAL, "CDA导入站-01", CONTENT_HASH,
                        "住院CDA", "application/xml", 1, "EMR",
                        MedicalRecordAssetRegisterRequestWire.CdaStatusValue.VERIFIED,
                        MedicalRecordAssetRegisterRequestWire.ScanStatusValue.NOT_APPLICABLE,
                        MedicalRecordAssetRegisterRequestWire.PreservationStatusValue.NOT_SCHEDULED, 30)))
                .isInstanceOf(MedicalRecordAssetException.class)
                .satisfies(error -> assertThat(((MedicalRecordAssetException) error).code())
                        .isEqualTo("MEDICAL_RECORD_ASSET_REQUEST_INVALID"));

        byte[] cda = "<ClinicalDocument xmlns=\"urn:hl7-org:v3\"><id root=\"1.2.3\"/></ClinicalDocument>"
                .getBytes(StandardCharsets.UTF_8);
        MedicalRecordAssetWire pending = assets.ingest(identity(), "cda-ingest-" + UUID.randomUUID(),
                new MedicalRecordAssetIngestRequestWire(
                        organization, facility, patientId, null,
                        MedicalRecordAssetIngestRequestWire.AssetTypeValue.DIGITAL, "CDA导入站-01", "住院CDA",
                        "inpatient-cda.xml", "application/xml", 1, "EMR", Base64.getEncoder().encodeToString(cda),
                        MedicalRecordAssetIngestRequestWire.CdaStatusValue.PENDING, 30));
        MedicalRecordAssetWire validated = assets.validateCda(identity(), "cda-validate-" + UUID.randomUUID(),
                pending.medicalRecordAssetId(),
                new MedicalRecordAssetActionRequestWire(organization, facility, patientId, pending.rowVersion()));
        assertThat(validated.cdaStatus()).isEqualTo(MedicalRecordAssetWire.CdaStatusValue.VERIFIED);
        assertThat(validated.cdaValidationEngine()).isEqualTo("synthetic-secure-cda-structure-validator");
        assertThat(validated.cdaValidationEvidenceHash()).matches("[0-9a-f]{64}");
        assertThat(validated.cdaValidatedAt()).isNotNull();
    }

    @Test
    void givenArchivedAsset_whenBorrowing_thenBorrowed() {
        UUID patientId = seedPatient();
        MedicalRecordAssetWire registered = verify(patientId, register(patientId));
        MedicalRecordAssetWire borrowed = borrow(patientId, registered.medicalRecordAssetId(), registered.rowVersion(),
                Instant.now().plus(7, ChronoUnit.DAYS));
        assertThat(borrowed.status()).isEqualTo(MedicalRecordAssetWire.StatusValue.BORROWED);
        assertThat(borrowed.borrowedBy()).isEqualTo(UUID.fromString(USER));
        assertThat(borrowed.dueAt()).isNotNull();
    }

    @Test
    void givenUnverifiedAsset_whenBorrowing_thenIntegrityGateRejectsIt() {
        UUID patientId = seedPatient();
        MedicalRecordAssetWire registered = register(patientId);

        assertThatThrownBy(() -> borrow(patientId, registered.medicalRecordAssetId(), registered.rowVersion(),
                Instant.now().plus(7, ChronoUnit.DAYS)))
                .isInstanceOf(MedicalRecordAssetException.class)
                .satisfies(e -> assertThat(((MedicalRecordAssetException) e).code())
                        .isEqualTo("MEDICAL_RECORD_ASSET_INTEGRITY_REQUIRED"));
    }

    @Test
    void givenBorrowedAsset_whenReturning_thenArchived() {
        UUID patientId = seedPatient();
        MedicalRecordAssetWire registered = verify(patientId, register(patientId));
        MedicalRecordAssetWire borrowed = borrow(patientId, registered.medicalRecordAssetId(), registered.rowVersion(),
                Instant.now().plus(7, ChronoUnit.DAYS));
        MedicalRecordAssetWire returned = returnAsset(patientId, registered.medicalRecordAssetId(), borrowed.rowVersion());
        assertThat(returned.status()).isEqualTo(MedicalRecordAssetWire.StatusValue.ARCHIVED);
        assertThat(returned.borrowedBy()).isNull();
    }

    @Test
    void givenBorrowedAsset_whenBorrowingAgain_thenRejected() {
        UUID patientId = seedPatient();
        MedicalRecordAssetWire registered = verify(patientId, register(patientId));
        MedicalRecordAssetWire borrowed = borrow(patientId, registered.medicalRecordAssetId(), registered.rowVersion(),
                Instant.now().plus(7, ChronoUnit.DAYS));
        assertThatThrownBy(() -> borrow(patientId, registered.medicalRecordAssetId(), borrowed.rowVersion(),
                Instant.now().plus(8, ChronoUnit.DAYS)))
                .isInstanceOf(MedicalRecordAssetException.class)
                .satisfies(e -> assertThat(((MedicalRecordAssetException) e).code())
                        .isEqualTo("MEDICAL_RECORD_ASSET_STATE_INVALID"));
    }

    @Test
    void givenStaleVersion_whenBorrowing_thenRejected() {
        UUID patientId = seedPatient();
        MedicalRecordAssetWire registered = verify(patientId, register(patientId));
        assertThatThrownBy(() -> borrow(patientId, registered.medicalRecordAssetId(), 99L,
                Instant.now().plus(7, ChronoUnit.DAYS)))
                .isInstanceOf(MedicalRecordAssetException.class)
                .satisfies(e -> assertThat(((MedicalRecordAssetException) e).code())
                        .isEqualTo("MEDICAL_RECORD_ASSET_VERSION_CONFLICT"));
    }

    @Test
    void givenPastDueAt_whenBorrowing_thenRejected() {
        UUID patientId = seedPatient();
        MedicalRecordAssetWire registered = verify(patientId, register(patientId));
        assertThatThrownBy(() -> borrow(patientId, registered.medicalRecordAssetId(), registered.rowVersion(),
                Instant.now().minus(1, ChronoUnit.DAYS)))
                .isInstanceOf(MedicalRecordAssetException.class)
                .satisfies(e -> assertThat(((MedicalRecordAssetException) e).code())
                        .isEqualTo("MEDICAL_RECORD_ASSET_REQUEST_INVALID"));
    }

    @Test
    void givenAssetIdentity_whenTampered_thenDatabaseRejectsMutation() {
        UUID patientId = seedPatient();
        MedicalRecordAssetWire registered = verify(patientId, register(patientId));
        assertThatThrownBy(() -> jdbc.sql("""
                update medical_record_asset set content_hash = 'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff'
                where tenant_id = cast(:tenant as uuid) and medical_record_asset_id = :asset
                """).param("tenant", TENANT).param("asset", registered.medicalRecordAssetId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void givenAsset_whenEditingMetadata_thenImmutableHashIsPreserved() {
        UUID patientId = seedPatient();
        MedicalRecordAssetWire registered = verify(patientId, register(patientId));
        MedicalRecordAssetWire updated = assets.update(identity(), "update-" + UUID.randomUUID(),
                registered.medicalRecordAssetId(), new MedicalRecordAssetUpdateRequestWire(
                        organization, facility, patientId, "旧病案首页", "application/pdf", 8,
                        "LegacyEMR", "病案库 A-03",
                        MedicalRecordAssetUpdateRequestWire.CdaStatusValue.NOT_APPLICABLE,
                        MedicalRecordAssetUpdateRequestWire.ScanStatusValue.NOT_APPLICABLE,
                        MedicalRecordAssetUpdateRequestWire.PreservationStatusValue.SCHEDULED,
                        30, registered.rowVersion()));

        assertThat(updated.displayName()).isEqualTo("旧病案首页");
        assertThat(updated.custodyLocation()).isEqualTo("病案库 A-03");
        assertThat(updated.contentHash()).isEqualTo(CONTENT_HASH);
    }

    @Test
    void givenObservedHashMismatch_whenVerifying_thenBorrowIsBlocked() {
        UUID patientId = seedPatient();
        MedicalRecordAssetWire registered = register(patientId);
        String wrongHash = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";
        assets.verifyIntegrity(identity(), "verify-" + UUID.randomUUID(), registered.medicalRecordAssetId(),
                new MedicalRecordAssetIntegrityCheckRequestWire(
                        organization, facility, patientId, wrongHash, registered.rowVersion()));
        MedicalRecordAssetWire failed = assets.listAssets(identity(), organization, facility, patientId).getFirst();

        assertThat(failed.integrityStatus()).isEqualTo(MedicalRecordAssetWire.IntegrityStatusValue.FAILED);
        assertThatThrownBy(() -> borrow(patientId, failed.medicalRecordAssetId(), failed.rowVersion(),
                Instant.now().plus(7, ChronoUnit.DAYS)))
                .isInstanceOf(MedicalRecordAssetException.class)
                .satisfies(e -> assertThat(((MedicalRecordAssetException) e).code())
                        .isEqualTo("MEDICAL_RECORD_ASSET_INTEGRITY_REQUIRED"));
    }

    @Test
    void givenActiveBorrow_whenEditingDueDateAndReturning_thenLifecycleIsPersisted() {
        UUID patientId = seedPatient();
        MedicalRecordAssetWire registered = verify(patientId, register(patientId));
        MedicalRecordAssetWire borrowed = borrow(patientId, registered.medicalRecordAssetId(),
                registered.rowVersion(), Instant.now().plus(7, ChronoUnit.DAYS));
        Instant extendedDueAt = Instant.now().plus(14, ChronoUnit.DAYS);
        MedicalRecordAssetWire extended = assets.updateBorrow(identity(), "borrow-update-" + UUID.randomUUID(),
                borrowed.medicalRecordAssetId(), new MedicalRecordAssetBorrowUpdateRequestWire(
                        organization, facility, patientId, borrowed.rowVersion(), extendedDueAt));

        assertThat(extended.dueAt()).isAfter(Instant.now().plus(13, ChronoUnit.DAYS));
        MedicalRecordAssetWire returned = returnAsset(patientId, extended.medicalRecordAssetId(), extended.rowVersion());
        assertThat(returned.status()).isEqualTo(MedicalRecordAssetWire.StatusValue.ARCHIVED);
    }

    @Test
    void givenArchivedAsset_whenRetired_thenItCannotReenterOperationalFlow() {
        UUID patientId = seedPatient();
        MedicalRecordAssetWire registered = register(patientId);
        MedicalRecordAssetWire retired = assets.retire(identity(), "retire-" + UUID.randomUUID(),
                registered.medicalRecordAssetId(), new MedicalRecordAssetRetireRequestWire(
                        organization, facility, patientId, "重复编目作废", registered.rowVersion()));

        assertThat(retired.status()).isEqualTo(MedicalRecordAssetWire.StatusValue.RETIRED);
        assertThat(retired.retirementReason()).isEqualTo("重复编目作废");
        assertThatThrownBy(() -> borrow(patientId, retired.medicalRecordAssetId(), retired.rowVersion(),
                Instant.now().plus(7, ChronoUnit.DAYS)))
                .isInstanceOf(MedicalRecordAssetException.class)
                .satisfies(e -> assertThat(((MedicalRecordAssetException) e).code())
                        .isEqualTo("MEDICAL_RECORD_ASSET_STATE_INVALID"));
    }

    @Test
    void givenMetadataOnlyAsset_whenSealing_thenRealStoredContentIsRequired() {
        UUID patientId = seedPatient();
        MedicalRecordAssetWire registered = register(patientId);
        MedicalRecordAssetWire scheduled = assets.update(identity(), "schedule-" + UUID.randomUUID(),
                registered.medicalRecordAssetId(), new MedicalRecordAssetUpdateRequestWire(
                        organization, facility, patientId, registered.displayName(), registered.mediaType(),
                        registered.pageCount(), registered.sourceSystem(), registered.custodyLocation(),
                        MedicalRecordAssetUpdateRequestWire.CdaStatusValue.NOT_APPLICABLE,
                        MedicalRecordAssetUpdateRequestWire.ScanStatusValue.NOT_APPLICABLE,
                        MedicalRecordAssetUpdateRequestWire.PreservationStatusValue.SCHEDULED,
                        registered.retentionYears(), registered.rowVersion()));
        MedicalRecordAssetWire verified = verify(patientId, scheduled);
        assertThatThrownBy(() -> assets.update(identity(), "seal-" + UUID.randomUUID(),
                verified.medicalRecordAssetId(), new MedicalRecordAssetUpdateRequestWire(
                        organization, facility, patientId, verified.displayName(), verified.mediaType(),
                        verified.pageCount(), verified.sourceSystem(), verified.custodyLocation(),
                        MedicalRecordAssetUpdateRequestWire.CdaStatusValue.NOT_APPLICABLE,
                        MedicalRecordAssetUpdateRequestWire.ScanStatusValue.NOT_APPLICABLE,
                        MedicalRecordAssetUpdateRequestWire.PreservationStatusValue.SEALED,
                        verified.retentionYears(), verified.rowVersion())))
                .isInstanceOf(MedicalRecordAssetException.class)
                .satisfies(error -> assertThat(((MedicalRecordAssetException) error).code())
                        .isEqualTo("MEDICAL_RECORD_ASSET_STATE_INVALID"));
    }

    @Test
    void givenRealFile_whenFullExternalLifecycleRuns_thenEveryWritePersistsAndWormIsEnforced() {
        UUID patientId = seedPatient();
        byte[] original = "\u59d3\u540d:\u5408\u6210\u60a3\u8005\n\u8bca\u65ad:\u9ad8\u8840\u538b\n".getBytes(StandardCharsets.UTF_8);
        MedicalRecordAssetWire ingested = assets.ingest(identity(), "ingest-" + UUID.randomUUID(),
                new MedicalRecordAssetIngestRequestWire(
                        organization, facility, patientId, null,
                        MedicalRecordAssetIngestRequestWire.AssetTypeValue.SCAN, "\u626b\u63cf\u5de5\u4f5c\u7ad9-01", "\u5165\u9662\u8bb0\u5f55\u626b\u63cf\u4ef6",
                        "admission.txt", "text/plain", 1, "SCANNER-01", Base64.getEncoder().encodeToString(original),
                        MedicalRecordAssetIngestRequestWire.CdaStatusValue.NOT_APPLICABLE, 30));

        assertThat(ingested.storageStatus()).isEqualTo(MedicalRecordAssetWire.StorageStatusValue.AVAILABLE);
        assertThat(ingested.byteSize()).isEqualTo(original.length);
        assertThat(assets.content(identity(), organization, facility, patientId,
                ingested.medicalRecordAssetId()).content())
                .isEqualTo(original);

        MedicalRecordAssetWire ocr = assets.runOcr(identity(), "ocr-" + UUID.randomUUID(), ingested.medicalRecordAssetId(),
                new MedicalRecordAssetActionRequestWire(organization, facility, patientId, ingested.rowVersion()));
        assertThat(ocr.ocrStatus()).isEqualTo(MedicalRecordAssetWire.OcrStatusValue.COMPLETED);
        assertThat(ocr.ocrText()).contains("\u9ad8\u8840\u538b");
        assertThat(ocr.scanStatus()).isEqualTo(MedicalRecordAssetWire.ScanStatusValue.OCR_REVIEWED);

        assertThat(assets.verifyStoredContent(identity(), "storage-verify-" + UUID.randomUUID(),
                ocr.medicalRecordAssetId(), new MedicalRecordAssetActionRequestWire(
                        organization, facility, patientId, ocr.rowVersion())).result().name()).isEqualTo("VERIFIED");
        MedicalRecordAssetWire verified = assets.listAssets(identity(), organization, facility, patientId).getFirst();

        MedicalRecordAssetDistributionPackageWire distribution = assets.createDistribution(identity(),
                "distribution-" + UUID.randomUUID(), verified.medicalRecordAssetId(),
                new MedicalRecordAssetDistributionCreateRequestWire(organization, facility, patientId,
                        verified.rowVersion(), "\u4fdd\u9669\u7406\u8d54", "\u5408\u6210\u4fdd\u9669\u516c\u53f8",
                        MedicalRecordAssetDistributionCreateRequestWire.RequesterTypeValue.INSURER,
                        "\u4fdd\u9669\u7406\u8d54\u6750\u6599\u4eba\u5de5\u6838\u9a8c", "\u60a3\u8005\u4e66\u9762\u6388\u6743\u53ca\u7406\u8d54\u7533\u8bf7",
                        "\u5165\u9662\u8bb0\u5f55\u626b\u63cf\u4ef6\u5355\u4efd\u590d\u5236", true,
                        MedicalRecordAssetDistributionCreateRequestWire.DeliveryChannelValue.SECURE_PORTAL,
                        Instant.now().plus(7, ChronoUnit.DAYS)));
        MedicalRecordAssetService.DistributionBinary zip = assets.distributionContent(
                identity(), organization, facility, patientId,
                verified.medicalRecordAssetId(), distribution.distributionPackageId());
        assertThat(zip.content()).startsWith(new byte[] {'P', 'K'});
        assertThat(zip.contentHash()).isEqualTo(distribution.contentHash());
        MedicalRecordAssetDistributionPackageWire delivered = assets.deliverDistribution(identity(),
                "deliver-" + UUID.randomUUID(), verified.medicalRecordAssetId(), distribution.distributionPackageId(),
                new MedicalRecordAssetDistributionDeliveryRequestWire(
                        organization, facility, patientId, distribution.rowVersion(),
                        "\u75c5\u6848\u7ba1\u7406\u4e13\u7528\u7ae0-TEST", "\u5408\u6210\u7b7e\u6536-TEST"));
        assertThat(delivered.status()).isEqualTo(MedicalRecordAssetDistributionPackageWire.StatusValue.DELIVERED);

        MedicalRecordAssetWire current = assets.listAssets(identity(), organization, facility, patientId).getFirst();
        MedicalRecordAssetWire scheduled = assets.update(identity(), "schedule-real-" + UUID.randomUUID(),
                current.medicalRecordAssetId(), new MedicalRecordAssetUpdateRequestWire(
                        organization, facility, patientId, current.displayName(), current.mediaType(), current.pageCount(),
                        current.sourceSystem(), current.custodyLocation(), MedicalRecordAssetUpdateRequestWire.CdaStatusValue.NOT_APPLICABLE,
                        MedicalRecordAssetUpdateRequestWire.ScanStatusValue.OCR_REVIEWED,
                        MedicalRecordAssetUpdateRequestWire.PreservationStatusValue.SCHEDULED, 30, current.rowVersion()));
        MedicalRecordAssetWire sealed = assets.update(identity(), "seal-real-" + UUID.randomUUID(),
                scheduled.medicalRecordAssetId(), new MedicalRecordAssetUpdateRequestWire(
                        organization, facility, patientId, scheduled.displayName(), scheduled.mediaType(), scheduled.pageCount(),
                        scheduled.sourceSystem(), scheduled.custodyLocation(), MedicalRecordAssetUpdateRequestWire.CdaStatusValue.NOT_APPLICABLE,
                        MedicalRecordAssetUpdateRequestWire.ScanStatusValue.OCR_REVIEWED,
                        MedicalRecordAssetUpdateRequestWire.PreservationStatusValue.SEALED, 30, scheduled.rowVersion()));
        assertThat(sealed.objectLockStatus()).isEqualTo(MedicalRecordAssetWire.ObjectLockStatusValue.LOCKED);
        assertThat(sealed.wormRetainUntil()).isAfter(Instant.now().plus(29 * 365, ChronoUnit.DAYS));
        assertThat(sealed.retentionYears()).isEqualTo(30);
        assertThat(sealed.recordCategory()).isEqualTo(MedicalRecordAssetWire.RecordCategoryValue.INPATIENT);
        assertThat(sealed.objectLockEvidence()).contains("filesystem-advisory");

        assets.verifyStoredContent(identity(), "restore-" + UUID.randomUUID(), sealed.medicalRecordAssetId(),
                new MedicalRecordAssetActionRequestWire(organization, facility, patientId, sealed.rowVersion()));
        assertThat(assets.listAssets(identity(), organization, facility, patientId).getFirst().preservationStatus())
                .isEqualTo(MedicalRecordAssetWire.PreservationStatusValue.VERIFIED);
        assertThatThrownBy(() -> jdbc.sql("""
                update medical_record_asset set worm_retain_until = now()
                where tenant_id = cast(:tenant as uuid) and medical_record_asset_id = :asset
                """).param("tenant", TENANT).param("asset", sealed.medicalRecordAssetId()).update())
                .isInstanceOf(DataAccessException.class);
    }

    /**
     * 合成演示账号 林伟（linwei）在 dev-synthetic 种子中附有 MEDICAL_RECORDS 病案岗任期（…aa1a），
     * 使其可开箱演示病案资产中心；服务端授权规则本身仍只放行 MEDICAL_RECORDS / CLINICAL_ADMIN。
     */
    private ClinicalIdentity demoLinweiIdentity() {
        return new ClinicalIdentity(tenant, UUID.fromString("018f0000-0000-7000-8000-00000000aa04"),
                List.of(UUID.fromString("018f0000-0000-7000-8000-00000000aa05"),
                        UUID.fromString("018f0000-0000-7000-8000-00000000aa09"),
                        UUID.fromString("018f0000-0000-7000-8000-00000000aa1a")));
    }

    @Test
    void demoLinweiAccountWithSeededRecordsRoleCanAccessAssetWorkflow() {
        UUID patientId = seedPatient();
        assertThatCode(() -> assets.listAssets(demoLinweiIdentity(), organization, facility, patientId))
                .doesNotThrowAnyException();
        assertThat(assets.listAssets(demoLinweiIdentity(), organization, facility, patientId)).isEmpty();
    }
}
