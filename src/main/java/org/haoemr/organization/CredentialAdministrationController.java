package org.haoemr.organization;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.PractitionerCredentialSimulationRequestWire;
import org.haoemr.contracts.PractitionerCredentialSimulationWire;
import org.haoemr.organization.CredentialAdministrationService.CredentialRevokeRequest;
import org.haoemr.organization.CredentialAdministrationService.CredentialWriteRequest;
import org.haoemr.organization.CredentialAdministrationService.PractitionerCredentialWire;
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
@RequestMapping("/api/v1/admin/credentials")
final class CredentialAdministrationController {
    private final ClinicalCommandSecurity security;
    private final CredentialAdministrationService credentials;

    CredentialAdministrationController(ClinicalCommandSecurity security, CredentialAdministrationService credentials) {
        this.security = security;
        this.credentials = credentials;
    }

    @GetMapping
    ResponseEntity<List<PractitionerCredentialWire>> list(
            HttpServletRequest request, @RequestParam(name = "person_id", required = false) UUID personId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(credentials.list(security.authenticate(request), personId));
    }

    @PostMapping
    ResponseEntity<PractitionerCredentialWire> create(
            HttpServletRequest request, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CredentialWriteRequest body) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(credentials.create(security.authenticate(request), idempotencyKey, body));
    }

    @PutMapping("/{credentialId}")
    ResponseEntity<PractitionerCredentialWire> update(
            HttpServletRequest request, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable UUID credentialId, @RequestBody CredentialWriteRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(credentials.update(security.authenticate(request), idempotencyKey, credentialId, body));
    }

    @PostMapping("/{credentialId}/revoke")
    ResponseEntity<PractitionerCredentialWire> revoke(
            HttpServletRequest request, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable UUID credentialId, @RequestBody CredentialRevokeRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(credentials.revoke(security.authenticate(request), idempotencyKey, credentialId, body));
    }

    @PostMapping("/{credentialId}/simulations")
    ResponseEntity<PractitionerCredentialSimulationWire> simulate(
            HttpServletRequest request, @PathVariable UUID credentialId,
            @RequestBody PractitionerCredentialSimulationRequestWire body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(credentials.simulate(security.authenticate(request), credentialId, body));
    }
}
