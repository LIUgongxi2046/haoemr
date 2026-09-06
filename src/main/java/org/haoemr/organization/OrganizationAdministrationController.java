package org.haoemr.organization;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.organization.OrganizationAdministrationService.OrganizationUnitCreateRequest;
import org.haoemr.organization.OrganizationAdministrationService.OrganizationUnitDeactivateRequest;
import org.haoemr.organization.OrganizationAdministrationService.OrganizationUnitWire;
import org.haoemr.organization.OrganizationAdministrationService.UnitType;
import org.haoemr.security.ClinicalCommandSecurity;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/organization-units")
final class OrganizationAdministrationController {

    private final ClinicalCommandSecurity security;
    private final OrganizationAdministrationService organization;

    OrganizationAdministrationController(
            ClinicalCommandSecurity security,
            OrganizationAdministrationService organization) {
        this.security = security;
        this.organization = organization;
    }

    @GetMapping
    List<OrganizationUnitWire> list(HttpServletRequest request) {
        ClinicalIdentity identity = security.authenticate(request);
        return organization.list(identity);
    }

    @PostMapping
    ResponseEntity<OrganizationUnitWire> create(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody OrganizationUnitCreateRequest body) {
        ClinicalIdentity identity = security.authenticate(request);
        return ResponseEntity.status(201).body(organization.create(identity, idempotencyKey, body));
    }

    @PostMapping("/{unitType}/{unitId}/deactivate")
    OrganizationUnitWire deactivate(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable UnitType unitType,
            @PathVariable UUID unitId,
            @RequestBody OrganizationUnitDeactivateRequest body) {
        ClinicalIdentity identity = security.authenticate(request);
        return organization.deactivate(identity, idempotencyKey, unitType, unitId, body);
    }
}
