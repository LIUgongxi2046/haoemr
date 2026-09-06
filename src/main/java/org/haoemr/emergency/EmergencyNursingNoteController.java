package org.haoemr.emergency;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.EmergencyNursingNoteCreateRequestWire;
import org.haoemr.contracts.EmergencyNursingNoteCorrectionRequestWire;
import org.haoemr.contracts.EmergencyNursingNoteWire;
import org.haoemr.contracts.EmergencyClinicalFactVoidRequestWire;
import org.haoemr.security.ClinicalCommandSecurity;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
final class EmergencyNursingNoteController {
    private final ClinicalCommandSecurity security;
    private final EmergencyNursingNoteService notes;

    EmergencyNursingNoteController(ClinicalCommandSecurity security, EmergencyNursingNoteService notes) {
        this.security = security;
        this.notes = notes;
    }

    @GetMapping("/emergency-nursing-notes")
    ResponseEntity<List<EmergencyNursingNoteWire>> list(
            HttpServletRequest request,
            @RequestParam("patient_id") UUID patientId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientContextId) {
        if (!patientId.equals(patientContextId)) throw EmergencyNursingNoteService.contextDenied();
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(notes.listNotes(identity, patientId));
    }

    @PostMapping("/emergency-nursing-notes")
    ResponseEntity<EmergencyNursingNoteWire> create(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody EmergencyNursingNoteCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(notes.create(identity, idempotencyKey, command));
    }

    @PostMapping("/emergency-nursing-notes/{note_id}/voids")
    ResponseEntity<EmergencyNursingNoteWire> voidNote(
            HttpServletRequest request,
            @PathVariable("note_id") UUID noteId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody EmergencyClinicalFactVoidRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(notes.voidNote(identity, idempotencyKey, noteId, command));
    }

    @PostMapping("/emergency-nursing-notes/{note_id}/corrections")
    ResponseEntity<EmergencyNursingNoteWire> correct(
            HttpServletRequest request,
            @PathVariable("note_id") UUID noteId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody EmergencyNursingNoteCorrectionRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(notes.correct(identity, idempotencyKey, noteId, command));
    }
}
