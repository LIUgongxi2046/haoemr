package org.haoemr.configuration;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.configuration.ConfigurationService.ConfigurationRevisionWire;
import org.haoemr.contracts.ConfigurationItemDefineRequestWire;
import org.haoemr.contracts.ConfigurationItemUpdateRequestWire;
import org.haoemr.contracts.ConfigurationItemWire;
import org.haoemr.contracts.ConfigurationLifecycleRequestWire;
import org.haoemr.security.ClinicalCommandSecurity;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
final class ConfigurationController {
    private final ClinicalCommandSecurity security;
    private final ConfigurationService configurations;

    ConfigurationController(ClinicalCommandSecurity security, ConfigurationService configurations) {
        this.security = security;
        this.configurations = configurations;
    }

    @GetMapping("/configurations")
    ResponseEntity<List<ConfigurationItemWire>> list(
            HttpServletRequest request,
            @RequestParam(value = "config_type", required = false) String configType,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(configurations.list(identity, configType));
    }

    @PostMapping("/configurations")
    ResponseEntity<ConfigurationItemWire> define(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ConfigurationItemDefineRequestWire command,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(configurations.define(identity, idempotencyKey, command));
    }

    @GetMapping("/configurations/{config_id}")
    ResponseEntity<ConfigurationItemWire> get(
            HttpServletRequest request,
            @PathVariable("config_id") UUID configId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(configurations.get(identity, configId));
    }

    @GetMapping("/configurations/{config_id}/revisions")
    ResponseEntity<List<ConfigurationRevisionWire>> revisions(
            HttpServletRequest request,
            @PathVariable("config_id") UUID configId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(configurations.revisions(identity, configId));
    }

    @PutMapping("/configurations/{config_id}")
    ResponseEntity<ConfigurationItemWire> update(
            HttpServletRequest request,
            @PathVariable("config_id") UUID configId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ConfigurationItemUpdateRequestWire command,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(configurations.update(identity, configId, idempotencyKey, command));
    }

    @PostMapping("/configurations/{config_id}/lifecycle")
    ResponseEntity<ConfigurationItemWire> transition(
            HttpServletRequest request,
            @PathVariable("config_id") UUID configId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ConfigurationLifecycleRequestWire command,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(configurations.transition(identity, configId, idempotencyKey, command));
    }
}
