package org.haoemr.archive;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.haoemr.contracts.ArchiveCaseWire;
import org.haoemr.contracts.ArchiveCreateRequestWire;
import org.haoemr.contracts.ArchiveExportCreateRequestWire;
import org.haoemr.contracts.ArchiveExportPackageWire;
import org.haoemr.contracts.ArchiveReadinessWire;
import org.haoemr.contracts.ArchiveTransitionRequestWire;
import org.haoemr.security.ClinicalCommandSecurity;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
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
@RequestMapping("/api/v1/archive")
final class ArchiveController {
    private final ClinicalCommandSecurity security;
    private final ArchiveService archive;

    ArchiveController(ClinicalCommandSecurity security, ArchiveService archive) {
        this.security = security;
        this.archive = archive;
    }

    @GetMapping("/readiness")
    ResponseEntity<ArchiveReadinessWire> readiness(
            HttpServletRequest request,
            @RequestParam("encounter_id") UUID encounterId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId,
            @RequestHeader("X-Encounter-Context") UUID encounterContextId) {
        requireSameEncounter(encounterId, encounterContextId);
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(archive.readiness(identity, patientId, encounterId, facilityId));
    }

    @PostMapping("/cases")
    ResponseEntity<ArchiveCaseWire> create(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ArchiveCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        ArchiveCaseWire created = archive.create(identity, idempotencyKey, command);
        return ResponseEntity.created(URI.create("/api/v1/archive/cases/" + created.archiveCaseId()))
                .eTag("\"" + created.rowVersion() + "\"").cacheControl(CacheControl.noStore()).body(created);
    }

    @GetMapping("/cases/{archiveCaseId}")
    ResponseEntity<ArchiveCaseWire> get(
            HttpServletRequest request,
            @PathVariable UUID archiveCaseId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId,
            @RequestHeader("X-Encounter-Context") UUID encounterId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, encounterId);
        return archiveResponse(archive.get(identity, archiveCaseId, patientId, encounterId, facilityId));
    }

    @PostMapping("/cases/{archiveCaseId}/seals")
    ResponseEntity<ArchiveCaseWire> seal(
            HttpServletRequest request,
            @PathVariable UUID archiveCaseId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ArchiveTransitionRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return archiveResponse(archive.seal(identity, idempotencyKey, archiveCaseId, command));
    }

    @PostMapping("/cases/{archiveCaseId}/unseals")
    ResponseEntity<ArchiveCaseWire> unseal(
            HttpServletRequest request,
            @PathVariable UUID archiveCaseId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ArchiveTransitionRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return archiveResponse(archive.unseal(identity, idempotencyKey, archiveCaseId, command));
    }

    @PostMapping("/cases/{archiveCaseId}/export-packages")
    ResponseEntity<ArchiveExportPackageWire> export(
            HttpServletRequest request,
            @PathVariable UUID archiveCaseId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ArchiveExportCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        ArchiveExportPackageWire exported = archive.export(identity, idempotencyKey, archiveCaseId, command);
        return ResponseEntity.created(URI.create(exported.downloadPath()))
                .header("X-Content-SHA256", exported.contentHash())
                .cacheControl(CacheControl.noStore()).body(exported);
    }

    @GetMapping("/export-packages/{exportPackageId}/content")
    ResponseEntity<byte[]> download(
            HttpServletRequest request,
            @PathVariable UUID exportPackageId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId,
            @RequestHeader("X-Encounter-Context") UUID encounterId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, encounterId);
        ArchiveService.ArchiveDownload download = archive.download(
                identity, exportPackageId, patientId, encounterId, facilityId);
        byte[] body = download.content().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .header("Content-Disposition", "attachment; filename=\"haoemr-archive-"
                        + exportPackageId + ".json\"")
                .header("X-Content-SHA256", download.contentHash())
                .contentLength(body.length).cacheControl(CacheControl.noStore()).body(body);
    }

    private static ResponseEntity<ArchiveCaseWire> archiveResponse(ArchiveCaseWire value) {
        return ResponseEntity.ok().eTag("\"" + value.rowVersion() + "\"")
                .header("X-Data-Watermark", value.dataWatermark())
                .cacheControl(CacheControl.noStore()).body(value);
    }

    private static void requireSameEncounter(UUID requested, UUID context) {
        if (!requested.equals(context)) {
            throw new ArchiveException("CONTEXT_NOT_PERMITTED", 403, "The requested archive context is not permitted");
        }
    }
}
