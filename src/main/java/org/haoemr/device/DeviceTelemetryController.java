package org.haoemr.device;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.DeviceObservationWire;
import org.haoemr.contracts.DeviceAlertTransitionRequestWire;
import org.haoemr.contracts.DeviceAlertWire;
import org.haoemr.contracts.DeviceStatusWire;
import org.haoemr.contracts.DeviceStatusBindingRequestWire;
import org.haoemr.contracts.DeviceStatusUnbindingRequestWire;
import org.haoemr.contracts.DeviceTelemetryCollectRequestWire;
import org.haoemr.contracts.DeviceTelemetryCollectResultWire;
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
final class DeviceTelemetryController {
    private final ClinicalCommandSecurity security;
    private final DeviceTelemetryService telemetry;

    DeviceTelemetryController(ClinicalCommandSecurity security, DeviceTelemetryService telemetry) {
        this.security = security;
        this.telemetry = telemetry;
    }

    @GetMapping("/device-telemetry")
    ResponseEntity<List<DeviceObservationWire>> list(
            HttpServletRequest request,
            @RequestParam(value = "device_code", required = false) String deviceCode,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(telemetry.listObservations(identity, deviceCode));
    }

    @PostMapping("/device-telemetry")
    ResponseEntity<DeviceTelemetryCollectResultWire> collect(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DeviceTelemetryCollectRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(telemetry.collect(identity, idempotencyKey, command));
    }

    @GetMapping("/device-statuses")
    ResponseEntity<List<DeviceStatusWire>> statuses(
            HttpServletRequest request,
            @RequestParam(value = "device_code", required = false) String deviceCode,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(telemetry.listStatuses(identity, deviceCode));
    }

    @GetMapping("/device-alerts")
    ResponseEntity<List<DeviceAlertWire>> alerts(
            HttpServletRequest request,
            @RequestParam(value = "device_code", required = false) String deviceCode,
            @RequestParam(value = "include_closed", defaultValue = "false") boolean includeClosed,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(telemetry.listAlerts(identity, deviceCode, includeClosed));
    }

    @PostMapping("/device-statuses/{device_code}/bindings")
    ResponseEntity<DeviceStatusWire> bindStatus(
            HttpServletRequest request,
            @PathVariable("device_code") String deviceCode,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DeviceStatusBindingRequestWire command) {
        ClinicalIdentity identity = security.authorize(request, command.organizationId(), command.facilityId(),
                command.patientId(), command.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(telemetry.bindStatus(identity, idempotencyKey, deviceCode, command));
    }

    @PostMapping("/device-statuses/{device_code}/unbindings")
    ResponseEntity<DeviceStatusWire> unbindStatus(
            HttpServletRequest request,
            @PathVariable("device_code") String deviceCode,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DeviceStatusUnbindingRequestWire command) {
        ClinicalIdentity identity = security.authorize(request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(telemetry.unbindStatus(identity, idempotencyKey, deviceCode, command));
    }

    @PostMapping("/device-alerts/{device_alert_id}/transitions")
    ResponseEntity<DeviceAlertWire> transitionAlert(
            HttpServletRequest request,
            @PathVariable("device_alert_id") UUID alertId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DeviceAlertTransitionRequestWire command) {
        ClinicalIdentity identity = security.authorize(request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(telemetry.transitionAlert(identity, idempotencyKey, alertId, command));
    }
}
