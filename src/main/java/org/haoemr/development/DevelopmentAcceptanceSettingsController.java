package org.haoemr.development;

import jakarta.servlet.http.HttpServletRequest;
import org.haoemr.contracts.DevelopmentAcceptanceSettingsUpdateRequestWire;
import org.haoemr.contracts.DevelopmentAcceptanceSettingsWire;
import org.haoemr.security.ClinicalCommandSecurity;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("dev-synthetic")
@RestController
@RequestMapping("/api/v1")
final class DevelopmentAcceptanceSettingsController {
    private final ClinicalCommandSecurity security;
    private final DevelopmentAcceptanceSettingsService settings;

    DevelopmentAcceptanceSettingsController(
            ClinicalCommandSecurity security, DevelopmentAcceptanceSettingsService settings) {
        this.security = security;
        this.settings = settings;
    }

    @GetMapping("/development-acceptance-settings")
    ResponseEntity<DevelopmentAcceptanceSettingsWire> get(HttpServletRequest request) {
        ClinicalIdentity identity = security.authenticate(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(settings.get(identity));
    }

    @PutMapping("/development-acceptance-settings")
    ResponseEntity<DevelopmentAcceptanceSettingsWire> update(
            HttpServletRequest request, @RequestBody DevelopmentAcceptanceSettingsUpdateRequestWire command) {
        ClinicalIdentity identity = security.authenticate(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(settings.update(identity, command));
    }
}
