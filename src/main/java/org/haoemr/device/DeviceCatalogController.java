package org.haoemr.device;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.DeviceCatalogCreateRequestWire;
import org.haoemr.contracts.DeviceCatalogDeactivateRequestWire;
import org.haoemr.contracts.DeviceCatalogWire;
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
final class DeviceCatalogController {
    private final ClinicalCommandSecurity security;
    private final DeviceCatalogService devices;

    DeviceCatalogController(ClinicalCommandSecurity security, DeviceCatalogService devices) {
        this.security = security;
        this.devices = devices;
    }

    @GetMapping("/devices")
    ResponseEntity<List<DeviceCatalogWire>> list(
            HttpServletRequest request,
            @RequestParam(value = "status", required = false) String status,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(devices.listDevices(identity, status));
    }

    @PostMapping("/devices")
    ResponseEntity<DeviceCatalogWire> create(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DeviceCatalogCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(devices.create(identity, idempotencyKey, command));
    }

    @PostMapping("/devices/{device_id}/deactivations")
    ResponseEntity<DeviceCatalogWire> deactivate(
            HttpServletRequest request,
            @PathVariable("device_id") UUID deviceId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DeviceCatalogDeactivateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(devices.deactivate(identity, idempotencyKey, deviceId, command));
    }
}
