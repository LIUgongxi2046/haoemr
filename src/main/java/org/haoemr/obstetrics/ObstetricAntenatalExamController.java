package org.haoemr.obstetrics;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.ObstetricAntenatalExamCreateRequestWire;
import org.haoemr.contracts.ObstetricAntenatalExamWire;
import org.haoemr.security.ClinicalCommandSecurity;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
final class ObstetricAntenatalExamController {
    private final ClinicalCommandSecurity security;
    private final ObstetricAntenatalExamService exams;

    ObstetricAntenatalExamController(ClinicalCommandSecurity security, ObstetricAntenatalExamService exams) {
        this.security = security;
        this.exams = exams;
    }

    @GetMapping("/obstetric-antenatal-exams")
    ResponseEntity<List<ObstetricAntenatalExamWire>> list(
            HttpServletRequest request,
            @RequestParam("patient_id") UUID patientId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientContextId) {
        if (!patientId.equals(patientContextId)) throw ObstetricAntenatalExamService.contextDenied();
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(exams.listRecords(identity, patientId));
    }

    @PostMapping("/obstetric-antenatal-exams")
    ResponseEntity<ObstetricAntenatalExamWire> record(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ObstetricAntenatalExamCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(exams.record(identity, idempotencyKey, command));
    }
}
