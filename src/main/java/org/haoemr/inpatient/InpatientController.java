package org.haoemr.inpatient;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.InpatientAdmissionCreateRequestWire;
import org.haoemr.contracts.InpatientBedBoardItemWire;
import org.haoemr.contracts.InpatientClinicalEventCreateRequestWire;
import org.haoemr.contracts.InpatientClinicalEventWire;
import org.haoemr.contracts.DocumentVersionWire;
import org.haoemr.contracts.InpatientDocumentStartRequestWire;
import org.haoemr.contracts.InpatientDischargeRequestWire;
import org.haoemr.contracts.InpatientDocumentRuleWire;
import org.haoemr.contracts.InpatientDocumentTaskCreateRequestWire;
import org.haoemr.contracts.InpatientDocumentTaskWire;
import org.haoemr.contracts.InpatientTransferRequestWire;
import org.haoemr.contracts.InpatientOverviewWire;
import org.haoemr.contracts.InpatientWorklistItemWire;
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
@RequestMapping("/api/v1/inpatient")
final class InpatientController {

    private final ClinicalCommandSecurity security;
    private final InpatientService inpatient;

    InpatientController(ClinicalCommandSecurity security, InpatientService inpatient) {
        this.security = security;
        this.inpatient = inpatient;
    }

    @PostMapping("/admissions")
    ResponseEntity<InpatientOverviewWire> admit(
            HttpServletRequest httpRequest,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody InpatientAdmissionCreateRequestWire request) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, request.organizationId(), request.facilityId(), request.patientId(), request.encounterId());
        InpatientOverviewWire overview = inpatient.admit(identity, idempotencyKey, request);
        return ResponseEntity.created(URI.create(
                        "/api/v1/inpatient/admissions/" + overview.admission().admissionId() + "/overview"))
                .eTag("\"" + overview.admission().rowVersion() + "\"")
                .header("X-Data-Watermark", overview.dataWatermark())
                .cacheControl(CacheControl.noStore()).body(overview);
    }

    @GetMapping("/admissions/{admissionId}/overview")
    ResponseEntity<InpatientOverviewWire> overview(
            HttpServletRequest httpRequest,
            @PathVariable UUID admissionId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId,
            @RequestHeader("X-Encounter-Context") UUID encounterId) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, organizationId, facilityId, patientId, encounterId);
        InpatientOverviewWire overview = inpatient.overview(identity, admissionId, patientId, encounterId);
        return ResponseEntity.ok().eTag("\"" + overview.admission().rowVersion() + "\"")
                .header("X-Data-Watermark", overview.dataWatermark())
                .cacheControl(CacheControl.noStore()).body(overview);
    }

    @GetMapping("/worklist")
    ResponseEntity<List<InpatientWorklistItemWire>> worklist(
            HttpServletRequest httpRequest,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestParam("ward_id") UUID wardId) {
        ClinicalIdentity identity = security.authorize(httpRequest, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(inpatient.worklist(identity, facilityId, wardId));
    }

    @GetMapping("/bed-board")
    ResponseEntity<List<InpatientBedBoardItemWire>> bedBoard(
            HttpServletRequest httpRequest,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestParam("ward_id") UUID wardId) {
        ClinicalIdentity identity = security.authorize(httpRequest, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(inpatient.bedBoard(identity, facilityId, wardId));
    }

    @PostMapping("/document-tasks/{taskId}/documents")
    ResponseEntity<DocumentVersionWire> startDocument(
            HttpServletRequest httpRequest,
            @PathVariable UUID taskId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody InpatientDocumentStartRequestWire request) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, request.organizationId(), request.facilityId(),
                request.patientId(), request.encounterId());
        DocumentVersionWire document = inpatient.startDocument(identity, idempotencyKey, taskId, request);
        return ResponseEntity.created(URI.create("/api/v1/documents/" + document.documentId()))
                .eTag("\"" + document.rowVersion() + "\"")
                .header("X-Data-Watermark", document.contentHash())
                .cacheControl(CacheControl.noStore()).body(document);
    }

    @PostMapping("/admissions/{admissionId}/transfers")
    ResponseEntity<InpatientOverviewWire> transfer(
            HttpServletRequest httpRequest,
            @PathVariable UUID admissionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody InpatientTransferRequestWire request) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, request.organizationId(), request.facilityId(),
                request.patientId(), request.encounterId());
        InpatientOverviewWire overview = inpatient.transfer(identity, idempotencyKey, admissionId, request);
        return ResponseEntity.ok()
                .eTag("\"" + overview.admission().rowVersion() + "\"")
                .header("X-Data-Watermark", overview.dataWatermark())
                .cacheControl(CacheControl.noStore()).body(overview);
    }

    @PostMapping("/admissions/{admissionId}/discharges")
    ResponseEntity<InpatientOverviewWire> discharge(
            HttpServletRequest httpRequest,
            @PathVariable UUID admissionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody InpatientDischargeRequestWire request) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, request.organizationId(), request.facilityId(),
                request.patientId(), request.encounterId());
        InpatientOverviewWire overview = inpatient.discharge(identity, idempotencyKey, admissionId, request);
        return ResponseEntity.ok()
                .eTag("\"" + overview.admission().rowVersion() + "\"")
                .header("X-Data-Watermark", overview.dataWatermark())
                .cacheControl(CacheControl.noStore()).body(overview);
    }

    @GetMapping("/document-rules")
    ResponseEntity<List<InpatientDocumentRuleWire>> documentRules(
            HttpServletRequest httpRequest,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(inpatient.documentRules(identity));
    }

    @PostMapping("/admissions/{admissionId}/document-tasks")
    ResponseEntity<InpatientDocumentTaskWire> createDocumentTask(
            HttpServletRequest httpRequest,
            @PathVariable UUID admissionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody InpatientDocumentTaskCreateRequestWire request) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, request.organizationId(), request.facilityId(),
                request.patientId(), request.encounterId());
        InpatientDocumentTaskWire task = inpatient.createDocumentTaskFromRule(
                identity, idempotencyKey, admissionId, request);
        return ResponseEntity.created(URI.create("/api/v1/inpatient/document-tasks/" + task.taskId()))
                .cacheControl(CacheControl.noStore()).body(task);
    }

    @PostMapping("/admissions/{admissionId}/clinical-events")
    ResponseEntity<InpatientClinicalEventWire> createClinicalEvent(
            HttpServletRequest httpRequest,
            @PathVariable UUID admissionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody InpatientClinicalEventCreateRequestWire request) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, request.organizationId(), request.facilityId(),
                request.patientId(), request.encounterId());
        InpatientClinicalEventWire event = inpatient.createClinicalEvent(
                identity, idempotencyKey, admissionId, request);
        return ResponseEntity.created(URI.create(
                        "/api/v1/inpatient/admissions/" + admissionId + "/clinical-events/" + event.clinicalEventId()))
                .cacheControl(CacheControl.noStore()).body(event);
    }
}
