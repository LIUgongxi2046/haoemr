package org.haoemr.nursing;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.MedicationAdministrationRequestWire;
import org.haoemr.contracts.MedicationAdministrationWire;
import org.haoemr.contracts.NursingCarePlanCompleteRequestWire;
import org.haoemr.contracts.NursingCarePlanRequestWire;
import org.haoemr.contracts.NursingCarePlanWire;
import org.haoemr.contracts.NursingBedsideNoteCreateRequestWire;
import org.haoemr.contracts.NursingBedsideNoteWire;
import org.haoemr.contracts.NursingDischargeClosureRequestWire;
import org.haoemr.contracts.NursingDischargeClosureWire;
import org.haoemr.contracts.ShiftHandoverCompleteRequestWire;
import org.haoemr.contracts.ShiftHandoverCorrectionRequestWire;
import org.haoemr.contracts.ShiftHandoverCreateRequestWire;
import org.haoemr.contracts.ShiftHandoverPatientCreateRequestWire;
import org.haoemr.contracts.ShiftHandoverPatientCorrectionRequestWire;
import org.haoemr.contracts.ShiftHandoverPatientVoidRequestWire;
import org.haoemr.contracts.ShiftHandoverPatientWire;
import org.haoemr.contracts.ShiftHandoverVoidRequestWire;
import org.haoemr.contracts.ShiftHandoverWire;
import org.haoemr.contracts.VitalSignRecordRequestWire;
import org.haoemr.contracts.VitalSignRecordWire;
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
final class NursingController {
    private final ClinicalCommandSecurity security;
    private final NursingService nursing;

    NursingController(ClinicalCommandSecurity security, NursingService nursing) {
        this.security = security;
        this.nursing = nursing;
    }

    @GetMapping("/vital-signs")
    ResponseEntity<List<VitalSignRecordWire>> list(
            HttpServletRequest request,
            @RequestParam("encounter_id") UUID encounterId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId,
            @RequestHeader("X-Encounter-Context") UUID encounterContextId) {
        if (!encounterId.equals(encounterContextId)) throw NursingService.contextDenied();
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(nursing.listVitalSigns(identity, organizationId, facilityId, patientId, encounterId));
    }

    @PostMapping("/vital-signs")
    ResponseEntity<VitalSignRecordWire> record(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody VitalSignRecordRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(nursing.recordVitalSigns(identity, idempotencyKey, command));
    }

    @GetMapping("/nursing-care-plans")
    ResponseEntity<List<NursingCarePlanWire>> listCarePlans(
            HttpServletRequest request,
            @RequestParam("encounter_id") UUID encounterId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId,
            @RequestHeader("X-Encounter-Context") UUID encounterContextId) {
        if (!encounterId.equals(encounterContextId)) throw NursingService.contextDenied();
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(nursing.listCarePlans(identity, organizationId, facilityId, patientId, encounterId));
    }

    @PostMapping("/nursing-care-plans")
    ResponseEntity<NursingCarePlanWire> createCarePlan(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody NursingCarePlanRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(nursing.createCarePlan(identity, idempotencyKey, command));
    }

    @PostMapping("/nursing-care-plans/{care_plan_id}/completions")
    ResponseEntity<NursingCarePlanWire> completeCarePlan(
            HttpServletRequest request,
            @PathVariable("care_plan_id") UUID carePlanId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody NursingCarePlanCompleteRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(nursing.completeCarePlan(identity, idempotencyKey, carePlanId, command));
    }

    @GetMapping("/medication-administrations")
    ResponseEntity<List<MedicationAdministrationWire>> listAdministrations(
            HttpServletRequest request,
            @RequestParam("encounter_id") UUID encounterId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientId,
            @RequestHeader("X-Encounter-Context") UUID encounterContextId) {
        if (!encounterId.equals(encounterContextId)) throw NursingService.contextDenied();
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, encounterId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(nursing.listMedicationAdministrations(identity, organizationId, facilityId, patientId, encounterId));
    }

    @PostMapping("/medication-administrations")
    ResponseEntity<MedicationAdministrationWire> administer(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody MedicationAdministrationRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(nursing.administerMedication(identity, idempotencyKey, command));
    }

    @GetMapping("/shift-handovers")
    ResponseEntity<List<ShiftHandoverWire>> listHandovers(
            HttpServletRequest request,
            @RequestParam("ward_id") UUID wardId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(nursing.listHandovers(identity, facilityId, wardId));
    }

    @PostMapping("/shift-handovers")
    ResponseEntity<ShiftHandoverWire> createHandover(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ShiftHandoverCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(nursing.createHandover(identity, idempotencyKey, command));
    }

    @PostMapping("/shift-handovers/{handover_id}/completions")
    ResponseEntity<ShiftHandoverWire> completeHandover(
            HttpServletRequest request,
            @PathVariable("handover_id") UUID handoverId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ShiftHandoverCompleteRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(nursing.completeHandover(identity, idempotencyKey, handoverId, command));
    }

    @PostMapping("/shift-handovers/{handover_id}/corrections")
    ResponseEntity<ShiftHandoverWire> correctHandover(
            HttpServletRequest request,
            @PathVariable("handover_id") UUID handoverId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ShiftHandoverCorrectionRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(nursing.correctHandover(identity, idempotencyKey, handoverId, command));
    }

    @PostMapping("/shift-handovers/{handover_id}/voids")
    ResponseEntity<ShiftHandoverWire> voidHandover(
            HttpServletRequest request,
            @PathVariable("handover_id") UUID handoverId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ShiftHandoverVoidRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(nursing.voidHandover(identity, idempotencyKey, handoverId, command));
    }

    @GetMapping("/shift-handover-patients")
    ResponseEntity<List<ShiftHandoverPatientWire>> listHandoverPatients(
            HttpServletRequest request,
            @RequestParam("handover_id") UUID handoverId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(nursing.listHandoverPatients(identity, handoverId));
    }

    @PostMapping("/shift-handover-patients")
    ResponseEntity<ShiftHandoverPatientWire> addHandoverPatient(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ShiftHandoverPatientCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(nursing.addHandoverPatient(identity, idempotencyKey, command));
    }

    @PostMapping("/shift-handover-patients/{shift_handover_patient_id}/corrections")
    ResponseEntity<ShiftHandoverPatientWire> correctHandoverPatient(
            HttpServletRequest request,
            @PathVariable("shift_handover_patient_id") UUID itemId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ShiftHandoverPatientCorrectionRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(nursing.correctHandoverPatient(identity, idempotencyKey, itemId, command));
    }

    @PostMapping("/shift-handover-patients/{shift_handover_patient_id}/voids")
    ResponseEntity<ShiftHandoverPatientWire> voidHandoverPatient(
            HttpServletRequest request,
            @PathVariable("shift_handover_patient_id") UUID itemId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ShiftHandoverPatientVoidRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(nursing.voidHandoverPatient(identity, idempotencyKey, itemId, command));
    }

    @GetMapping("/nursing-discharge-closures")
    ResponseEntity<List<NursingDischargeClosureWire>> listNursingDischargeClosures(
            HttpServletRequest request,
            @RequestParam("patient_id") UUID patientId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientContextId) {
        if (!patientId.equals(patientContextId)) throw NursingService.contextDenied();
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(nursing.listNursingDischargeClosures(identity, patientId));
    }

    @PostMapping("/nursing-discharge-closures")
    ResponseEntity<NursingDischargeClosureWire> closeNursingDischarge(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody NursingDischargeClosureRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(nursing.closeNursingDischarge(identity, idempotencyKey, command));
    }

    @GetMapping("/nursing-bedside-notes")
    ResponseEntity<List<NursingBedsideNoteWire>> listBedsideNotes(
            HttpServletRequest request,
            @RequestParam("patient_id") UUID patientId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientContextId) {
        if (!patientId.equals(patientContextId)) throw NursingService.contextDenied();
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(nursing.listBedsideNotes(identity, patientId));
    }

    @PostMapping("/nursing-bedside-notes")
    ResponseEntity<NursingBedsideNoteWire> syncBedsideNote(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody NursingBedsideNoteCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(nursing.syncBedsideNote(identity, idempotencyKey, command));
    }
}
