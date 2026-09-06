package org.haoemr.tcm;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.TcmRecordCreateRequestWire;
import org.haoemr.contracts.TcmRecordWire;
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
final class TcmController {
    private final ClinicalCommandSecurity security;
    private final TcmService records;

    TcmController(ClinicalCommandSecurity security, TcmService records) {
        this.security = security;
        this.records = records;
    }

    @GetMapping("/tcm-records")
    ResponseEntity<List<TcmRecordWire>> list(
            HttpServletRequest request,
            @RequestParam("patient_id") UUID patientId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId,
            @RequestHeader("X-Patient-Context") UUID patientContextId) {
        if (!patientId.equals(patientContextId)) throw TcmService.contextDenied();
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, patientId, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(records.listRecords(identity, patientId));
    }

    @PostMapping("/tcm-records")
    ResponseEntity<TcmRecordWire> create(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody TcmRecordCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(records.createRecord(identity, idempotencyKey, command));
    }
}
