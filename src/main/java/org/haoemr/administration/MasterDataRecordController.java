package org.haoemr.administration;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.administration.MasterDataRecordService.DeactivateRequest;
import org.haoemr.administration.MasterDataRecordService.MasterDataCreateRequest;
import org.haoemr.administration.MasterDataRecordService.MasterDataRecordWire;
import org.haoemr.administration.MasterDataRecordService.MasterDataUpdateRequest;
import org.haoemr.security.ClinicalCommandSecurity;
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
@RequestMapping("/api/v1/admin/master-data-records")
final class MasterDataRecordController {
    private final ClinicalCommandSecurity security;
    private final MasterDataRecordService masterData;

    MasterDataRecordController(ClinicalCommandSecurity security, MasterDataRecordService masterData) {
        this.security = security;
        this.masterData = masterData;
    }

    @GetMapping
    ResponseEntity<List<MasterDataRecordWire>> list(
            HttpServletRequest request,
            @RequestParam(value = "config_id", required = false) UUID configId,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(masterData.list(security.authenticate(request), configId, keyword, status));
    }

    @PostMapping
    ResponseEntity<MasterDataRecordWire> create(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody MasterDataCreateRequest body) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(masterData.create(security.authenticate(request), idempotencyKey, body));
    }

    @PutMapping("/{recordId}")
    ResponseEntity<MasterDataRecordWire> update(
            HttpServletRequest request,
            @PathVariable UUID recordId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody MasterDataUpdateRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(masterData.update(security.authenticate(request), recordId, idempotencyKey, body));
    }

    @PostMapping("/{recordId}/deactivate")
    ResponseEntity<MasterDataRecordWire> deactivate(
            HttpServletRequest request,
            @PathVariable UUID recordId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DeactivateRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(masterData.deactivate(security.authenticate(request), recordId, idempotencyKey, body));
    }
}
