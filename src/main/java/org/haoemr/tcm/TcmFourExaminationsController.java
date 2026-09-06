package org.haoemr.tcm;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.TcmFourExaminationsCreateRequestWire;
import org.haoemr.contracts.TcmFourExaminationsWire;
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
final class TcmFourExaminationsController {
    private final ClinicalCommandSecurity security;
    private final TcmFourExaminationsService examinations;

    TcmFourExaminationsController(ClinicalCommandSecurity security, TcmFourExaminationsService examinations) {
        this.security = security;
        this.examinations = examinations;
    }

    @GetMapping("/tcm-four-examinations")
    ResponseEntity<List<TcmFourExaminationsWire>> list(
            HttpServletRequest request,
            @RequestParam("patient_id") UUID patientId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientContextId) {
        if (!patientId.equals(patientContextId)) throw TcmFourExaminationsService.contextDenied();
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(examinations.listRecords(identity, patientId));
    }

    @PostMapping("/tcm-four-examinations")
    ResponseEntity<TcmFourExaminationsWire> record(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody TcmFourExaminationsCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(examinations.record(identity, idempotencyKey, command));
    }
}
