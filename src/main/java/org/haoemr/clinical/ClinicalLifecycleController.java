package org.haoemr.clinical;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.DocumentCreateRequestWire;
import org.haoemr.contracts.DocumentCorrectionCreateRequestWire;
import org.haoemr.contracts.DocumentCorrectionPropagationRetryRequestWire;
import org.haoemr.contracts.DocumentCorrectionPropagationWire;
import org.haoemr.contracts.DocumentCorrectionWire;
import org.haoemr.contracts.DocumentDiffWire;
import org.haoemr.contracts.DocumentGovernanceSnapshotWire;
import org.haoemr.contracts.DocumentDraftSaveRequestWire;
import org.haoemr.contracts.DocumentVoidRequestWire;
import org.haoemr.contracts.DocumentVersionWire;
import org.haoemr.contracts.DocumentQualityCheckRequestWire;
import org.haoemr.contracts.EncounterRecordQualityWire;
import org.haoemr.contracts.DocumentReviewRejectRequestWire;
import org.haoemr.contracts.DocumentSignRequestWire;
import org.haoemr.contracts.DocumentSignatureRevokeRequestWire;
import org.haoemr.contracts.EncounterCreateRequestWire;
import org.haoemr.contracts.EncounterStateEventWire;
import org.haoemr.contracts.EncounterStateTransitionRequestWire;
import org.haoemr.contracts.EncounterWire;
import org.haoemr.contracts.PatientCreateRequestWire;
import org.haoemr.contracts.PatientSearchRequestWire;
import org.haoemr.contracts.PatientSummaryWire;
import org.haoemr.contracts.QualityFindingWire;
import org.haoemr.contracts.SignatureEvidenceWire;
import org.haoemr.contracts.SignatureRevocationEvidenceWire;
import org.haoemr.security.ClinicalCommandSecurity;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
final class ClinicalLifecycleController {

    private final ClinicalCommandSecurity security;
    private final ClinicalLifecycleService clinical;
    private final DocumentGovernanceService governance;

    ClinicalLifecycleController(
            ClinicalCommandSecurity security,
            ClinicalLifecycleService clinical,
            DocumentGovernanceService governance) {
        this.security = security;
        this.clinical = clinical;
        this.governance = governance;
    }

    @PostMapping("/patients/search")
    ResponseEntity<List<PatientSummaryWire>> searchPatients(
            HttpServletRequest httpRequest,
            @RequestBody PatientSearchRequestWire request) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, request.organizationId(), request.facilityId(), null, null);
        int limit = request.limit() == null ? 10 : request.limit();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(clinical.searchPatients(identity, request.query(), limit));
    }

    @PostMapping("/patients")
    ResponseEntity<PatientSummaryWire> createPatient(
            HttpServletRequest httpRequest,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PatientCreateRequestWire request) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, request.organizationId(), request.facilityId(), null, null);
        PatientSummaryWire patient = clinical.createPatient(
                identity, idempotencyKey, request.displayName(), request.sexCode(), request.birthDate(),
                request.assigningAuthority(), request.identifierType(), request.identifierValue(),
                request.identityStatus() == null ? null : request.identityStatus().name(),
                request.acknowledgedCandidatePatientIds());
        return ResponseEntity.created(URI.create("/api/v1/patients/" + patient.patientId()))
                .cacheControl(CacheControl.noStore()).body(patient);
    }

    @PostMapping("/encounters")
    ResponseEntity<EncounterWire> createEncounter(
            HttpServletRequest httpRequest,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody EncounterCreateRequestWire request) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, request.organizationId(), request.facilityId(), request.patientId(), null);
        EncounterWire encounter = clinical.createEncounter(
                identity, idempotencyKey, request.organizationId(), request.facilityId(), request.patientId(),
                request.encounterType().name(), request.initialStatus() == null ? null : request.initialStatus().name(),
                request.departmentId(), request.responsibleUserId(), request.startedAt(),
                request.sourceSystem(), request.sourceKey());
        return ResponseEntity.created(URI.create("/api/v1/encounters/" + encounter.encounterId()))
                .cacheControl(CacheControl.noStore()).body(encounter);
    }

    @GetMapping("/patients/{patientId}/encounters")
    ResponseEntity<List<EncounterWire>> patientEncounters(
            HttpServletRequest httpRequest,
            @PathVariable UUID patientId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(httpRequest, organizationId, facilityId, patientId, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(clinical.listPatientEncounters(identity, organizationId, facilityId, patientId));
    }

    @GetMapping("/encounters/{encounterId}")
    ResponseEntity<EncounterWire> encounter(
            HttpServletRequest httpRequest,
            @PathVariable UUID encounterId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(clinical.getEncounter(identity, organizationId, facilityId, patientId, encounterId));
    }

    @GetMapping("/encounters/{encounterId}/state-events")
    ResponseEntity<List<EncounterStateEventWire>> encounterStateEvents(
            HttpServletRequest httpRequest,
            @PathVariable UUID encounterId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(clinical.listEncounterStateEvents(identity, organizationId, facilityId, patientId, encounterId));
    }

    @PostMapping("/encounters/{encounterId}/state-transitions")
    ResponseEntity<EncounterWire> transitionEncounter(
            HttpServletRequest httpRequest,
            @PathVariable UUID encounterId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody EncounterStateTransitionRequestWire request) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, request.organizationId(), request.facilityId(), request.patientId(), encounterId);
        EncounterWire encounter = clinical.transitionEncounter(
                identity, idempotencyKey, request.organizationId(), request.facilityId(), request.patientId(),
                encounterId, request.expectedRowVersion(), request.targetStatus().name(),
                request.occurredAt(), request.reason());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(encounter);
    }

    @PostMapping("/documents")
    ResponseEntity<DocumentVersionWire> createDocument(
            HttpServletRequest httpRequest,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DocumentCreateRequestWire request) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, request.organizationId(), request.facilityId(), request.patientId(), request.encounterId());
        DocumentVersionWire version = clinical.createDocument(
                identity, idempotencyKey, request.patientId(), request.encounterId(),
                request.documentTypeCode(), request.sections());
        return documentResponse(201, version);
    }

    @GetMapping("/documents/{documentId}")
    ResponseEntity<DocumentVersionWire> currentDocument(
            HttpServletRequest httpRequest,
            @PathVariable UUID documentId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId,
            @RequestHeader("X-Encounter-Context") UUID encounterId) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, organizationId, facilityId, patientId, encounterId);
        return documentResponse(200, clinical.currentDocument(identity, documentId, patientId, encounterId));
    }

    @GetMapping("/encounters/{encounterId}/documents")
    ResponseEntity<List<DocumentVersionWire>> encounterDocuments(
            HttpServletRequest httpRequest,
            @PathVariable UUID encounterId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(clinical.encounterDocuments(identity, patientId, encounterId));
    }

    @GetMapping("/documents/{documentId}/versions")
    ResponseEntity<List<DocumentVersionWire>> documentVersions(
            HttpServletRequest httpRequest,
            @PathVariable UUID documentId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId,
            @RequestHeader("X-Encounter-Context") UUID encounterId) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(clinical.documentVersions(identity, documentId, patientId, encounterId));
    }

    @PutMapping("/documents/{documentId}/draft")
    ResponseEntity<DocumentVersionWire> saveDraft(
            HttpServletRequest httpRequest,
            @PathVariable UUID documentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DocumentDraftSaveRequestWire request) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, request.organizationId(), request.facilityId(), request.patientId(), request.encounterId());
        DocumentVersionWire version = clinical.saveDraft(
                identity, idempotencyKey, documentId, request.patientId(), request.encounterId(),
                request.expectedRowVersion(), request.sections(), request.sourceMedicalAgentRunId());
        return documentResponse(200, version);
    }

    @PostMapping("/documents/{documentId}/voids")
    ResponseEntity<DocumentVersionWire> voidDocument(
            HttpServletRequest httpRequest,
            @PathVariable UUID documentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DocumentVoidRequestWire request) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, request.organizationId(), request.facilityId(), request.patientId(), request.encounterId());
        DocumentVersionWire version = clinical.voidDocument(
                identity, idempotencyKey, documentId, request.patientId(), request.encounterId(),
                request.expectedRowVersion(), request.reason());
        return documentResponse(200, version);
    }

    @GetMapping("/documents/{documentId}/diff")
    ResponseEntity<DocumentDiffWire> diff(
            HttpServletRequest httpRequest,
            @PathVariable UUID documentId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId,
            @RequestHeader("X-Encounter-Context") UUID encounterId,
            @RequestParam("from_version_id") UUID fromVersionId,
            @RequestParam("to_version_id") UUID toVersionId) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(clinical.diff(identity, documentId, patientId, encounterId, fromVersionId, toVersionId));
    }

    @PostMapping("/documents/{documentId}/quality-checks")
    ResponseEntity<List<QualityFindingWire>> qualityChecks(
            HttpServletRequest httpRequest,
            @PathVariable UUID documentId,
            @RequestBody DocumentQualityCheckRequestWire request) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, request.organizationId(), request.facilityId(), request.patientId(), request.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(governance.runQualityChecks(
                identity, documentId, request.patientId(), request.encounterId(), request.documentVersionId(),
                request.triggerStage() == null ? "MANUAL" : request.triggerStage().name()));
    }

    @GetMapping("/encounters/{encounterId}/record-quality")
    ResponseEntity<EncounterRecordQualityWire> encounterQuality(
            HttpServletRequest httpRequest, @PathVariable UUID encounterId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId) {
        ClinicalIdentity identity = security.authorize(httpRequest, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(governance.encounterQuality(identity, patientId, encounterId));
    }

    @GetMapping("/documents/{documentId}/governance")
    ResponseEntity<DocumentGovernanceSnapshotWire> governanceSnapshot(
            HttpServletRequest httpRequest,
            @PathVariable UUID documentId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId,
            @RequestHeader("X-Encounter-Context") UUID encounterId,
            @RequestParam("document_version_id") UUID documentVersionId) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(governance.snapshot(
                identity, documentId, patientId, encounterId, documentVersionId));
    }

    @PostMapping("/documents/{documentId}/signatures")
    ResponseEntity<SignatureEvidenceWire> sign(
            HttpServletRequest httpRequest,
            @PathVariable UUID documentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DocumentSignRequestWire request) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, request.organizationId(), request.facilityId(), request.patientId(), request.encounterId());
        SignatureEvidenceWire signature = governance.sign(
                identity, idempotencyKey, documentId, request.patientId(), request.encounterId(),
                request.documentVersionId(), request.expectedRowVersion(), request.signatureRole(), request.warningDisposition());
        return ResponseEntity.created(URI.create("/api/v1/documents/" + documentId + "/signatures/" + signature.signatureId()))
                .cacheControl(CacheControl.noStore()).body(signature);
    }

    @PostMapping("/documents/{documentId}/review-rejections")
    ResponseEntity<Void> rejectReview(
            HttpServletRequest httpRequest,
            @PathVariable UUID documentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DocumentReviewRejectRequestWire request) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, request.organizationId(), request.facilityId(), request.patientId(), request.encounterId());
        governance.rejectReview(
                identity, idempotencyKey, documentId, request.patientId(), request.encounterId(),
                request.documentVersionId(), request.expectedRowVersion(), request.rejectionLevel().name(), request.reason());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/documents/{documentId}/corrections")
    ResponseEntity<List<DocumentCorrectionWire>> corrections(
            HttpServletRequest httpRequest,
            @PathVariable UUID documentId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId,
            @RequestHeader("X-Encounter-Context") UUID encounterId) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(clinical.documentCorrections(identity, documentId, patientId, encounterId));
    }

    @PostMapping("/documents/{documentId}/corrections")
    ResponseEntity<DocumentCorrectionWire> createCorrection(
            HttpServletRequest httpRequest,
            @PathVariable UUID documentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DocumentCorrectionCreateRequestWire request) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, request.organizationId(), request.facilityId(), request.patientId(), request.encounterId());
        DocumentCorrectionWire correction = clinical.createCorrection(
                identity, idempotencyKey, documentId, request.patientId(), request.encounterId(),
                request.sourceDocumentVersionId(), request.expectedRowVersion(), request.correctionType().name(),
                request.reason(), request.sections());
        return ResponseEntity.created(URI.create("/api/v1/documents/" + documentId + "/corrections/"
                        + correction.correctionId())).cacheControl(CacheControl.noStore()).body(correction);
    }

    @PostMapping("/documents/{documentId}/signature-revocations")
    ResponseEntity<SignatureRevocationEvidenceWire> revokeSignature(
            HttpServletRequest httpRequest,
            @PathVariable UUID documentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DocumentSignatureRevokeRequestWire request) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, request.organizationId(), request.facilityId(), request.patientId(), request.encounterId());
        SignatureRevocationEvidenceWire evidence = clinical.revokeSignature(
                identity, idempotencyKey, documentId, request.patientId(), request.encounterId(),
                request.signatureId(), request.expectedDocumentRowVersion(), request.reason());
        return ResponseEntity.created(URI.create("/api/v1/documents/" + documentId
                        + "/signature-revocations/" + evidence.revocationId()))
                .cacheControl(CacheControl.noStore()).body(evidence);
    }

    @PostMapping("/documents/{documentId}/correction-propagations/{propagationId}/retry")
    ResponseEntity<DocumentCorrectionPropagationWire> retryCorrectionPropagation(
            HttpServletRequest httpRequest,
            @PathVariable UUID documentId,
            @PathVariable UUID propagationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DocumentCorrectionPropagationRetryRequestWire request) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, request.organizationId(), request.facilityId(), request.patientId(), request.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(clinical.retryCorrectionPropagation(
                identity, idempotencyKey, documentId, request.patientId(), request.encounterId(),
                propagationId, request.expectedRowVersion()));
    }

    private static ResponseEntity<DocumentVersionWire> documentResponse(int status, DocumentVersionWire version) {
        return ResponseEntity.status(status)
                .eTag("\"" + version.rowVersion() + "\"")
                .header("X-Data-Watermark", version.contentHash())
                .cacheControl(CacheControl.noStore())
                .body(version);
    }
}
