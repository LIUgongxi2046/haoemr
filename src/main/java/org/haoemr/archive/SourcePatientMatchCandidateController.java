package org.haoemr.archive;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.HistoricalMigrationSourceImportWire;
import org.haoemr.contracts.SourcePatientMatchCandidateCsvImportRequestWire;
import org.haoemr.contracts.SourcePatientMatchCandidateRecordRequestWire;
import org.haoemr.contracts.SourcePatientMatchCandidateResolveRequestWire;
import org.haoemr.contracts.SourcePatientMatchCandidateWire;
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
final class SourcePatientMatchCandidateController {
    private final ClinicalCommandSecurity security;
    private final SourcePatientMatchCandidateService candidates;

    SourcePatientMatchCandidateController(ClinicalCommandSecurity security, SourcePatientMatchCandidateService candidates) {
        this.security = security;
        this.candidates = candidates;
    }

    @GetMapping("/source-patient-match-candidates")
    ResponseEntity<List<SourcePatientMatchCandidateWire>> list(
            HttpServletRequest request,
            @RequestParam("source_system_id") UUID sourceSystemId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(candidates.list(identity, sourceSystemId));
    }

    @PostMapping("/source-patient-match-candidates")
    ResponseEntity<SourcePatientMatchCandidateWire> record(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody SourcePatientMatchCandidateRecordRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(candidates.record(identity, idempotencyKey, command));
    }

    @GetMapping("/source-patient-match-candidate-imports")
    ResponseEntity<List<HistoricalMigrationSourceImportWire>> listImports(
            HttpServletRequest request,
            @RequestParam("source_system_id") UUID sourceSystemId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(candidates.listImports(identity, sourceSystemId));
    }

    @PostMapping("/source-patient-match-candidate-imports")
    ResponseEntity<HistoricalMigrationSourceImportWire> importCsv(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody SourcePatientMatchCandidateCsvImportRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(candidates.importCsv(identity, idempotencyKey, command));
    }

    @PostMapping("/source-patient-match-candidates/{candidate_id}/resolutions")
    ResponseEntity<SourcePatientMatchCandidateWire> resolve(
            HttpServletRequest request,
            @PathVariable("candidate_id") UUID candidateId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody SourcePatientMatchCandidateResolveRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(candidates.resolve(identity, idempotencyKey, candidateId, command));
    }
}
