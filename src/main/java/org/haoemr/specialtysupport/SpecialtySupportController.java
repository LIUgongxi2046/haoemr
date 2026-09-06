package org.haoemr.specialtysupport;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.DepartmentSupportAssessmentPutRequestWire;
import org.haoemr.contracts.DepartmentSupportAssessmentWire;
import org.haoemr.security.ClinicalCommandSecurity;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/specialty-support")
final class SpecialtySupportController {

    private final ClinicalCommandSecurity security;
    private final SpecialtySupportService support;

    SpecialtySupportController(ClinicalCommandSecurity security, SpecialtySupportService support) {
        this.security = security;
        this.support = support;
    }

    @GetMapping("/{facilityId}")
    ResponseEntity<List<DepartmentSupportAssessmentWire>> list(
            HttpServletRequest httpRequest,
            @PathVariable UUID facilityId,
            @RequestHeader("X-Organization-Context") UUID organizationId) {
        ClinicalIdentity identity = security.authorize(httpRequest, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(support.list(identity, facilityId));
    }

    @GetMapping("/{facilityId}/{departmentId}/{scope}")
    ResponseEntity<DepartmentSupportAssessmentWire> get(
            HttpServletRequest httpRequest,
            @PathVariable UUID facilityId,
            @PathVariable UUID departmentId,
            @PathVariable String scope,
            @RequestHeader("X-Organization-Context") UUID organizationId) {
        ClinicalIdentity identity = security.authorize(httpRequest, organizationId, facilityId, null, null);
        return response(support.get(identity, facilityId, departmentId, scope));
    }

    @PutMapping("/{facilityId}/{departmentId}/{scope}")
    ResponseEntity<DepartmentSupportAssessmentWire> put(
            HttpServletRequest httpRequest,
            @PathVariable UUID facilityId,
            @PathVariable UUID departmentId,
            @PathVariable String scope,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DepartmentSupportAssessmentPutRequestWire request) {
        ClinicalIdentity identity = security.authorize(
                httpRequest, request.organizationId(), facilityId, null, null);
        return response(support.put(identity, idempotencyKey, facilityId, departmentId, scope, request));
    }

    @DeleteMapping("/{facilityId}/{departmentId}/{scope}")
    ResponseEntity<Void> delete(
            HttpServletRequest httpRequest,
            @PathVariable UUID facilityId,
            @PathVariable UUID departmentId,
            @PathVariable String scope,
            @RequestParam("expected_row_version") long expectedRowVersion,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Organization-Context") UUID organizationId) {
        ClinicalIdentity identity = security.authorize(httpRequest, organizationId, facilityId, null, null);
        support.delete(identity, idempotencyKey, organizationId, facilityId, departmentId, scope, expectedRowVersion);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static ResponseEntity<DepartmentSupportAssessmentWire> response(
            DepartmentSupportAssessmentWire assessment) {
        return ResponseEntity.ok().eTag("\"" + assessment.rowVersion() + "\"")
                .cacheControl(CacheControl.noStore()).body(assessment);
    }
}
