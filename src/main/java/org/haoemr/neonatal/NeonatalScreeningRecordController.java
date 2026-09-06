package org.haoemr.neonatal;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.NeonatalScreeningRecordCreateRequestWire;
import org.haoemr.contracts.NeonatalScreeningRecordWire;
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
final class NeonatalScreeningRecordController {
    private final ClinicalCommandSecurity security;
    private final NeonatalScreeningRecordService screenings;

    NeonatalScreeningRecordController(ClinicalCommandSecurity security, NeonatalScreeningRecordService screenings) {
        this.security = security;
        this.screenings = screenings;
    }

    @GetMapping("/neonatal-screening-records")
    ResponseEntity<List<NeonatalScreeningRecordWire>> list(
            HttpServletRequest request,
            @RequestParam("patient_id") UUID patientId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientContextId) {
        if (!patientId.equals(patientContextId)) throw NeonatalScreeningRecordService.contextDenied();
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(screenings.listRecords(identity, patientId));
    }

    @PostMapping("/neonatal-screening-records")
    ResponseEntity<NeonatalScreeningRecordWire> record(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody NeonatalScreeningRecordCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(screenings.record(identity, idempotencyKey, command));
    }
}
