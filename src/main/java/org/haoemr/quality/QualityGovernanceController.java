package org.haoemr.quality;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.QualityGovernanceAgentProposalRequestWire;
import org.haoemr.contracts.QualityGovernanceAgentProposalWire;
import org.haoemr.contracts.QualityGovernanceRecordCreateRequestWire;
import org.haoemr.contracts.QualityGovernanceRecordUpdateRequestWire;
import org.haoemr.contracts.QualityGovernanceRecordVoidRequestWire;
import org.haoemr.contracts.QualityGovernanceRecordWire;
import org.haoemr.security.ClinicalCommandSecurity;
import org.haoemr.security.ClinicalIdentity;
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
@RequestMapping("/api/v1/quality-governance/{module_code}/{parent_resource_id}")
final class QualityGovernanceController {
    private final ClinicalCommandSecurity security;
    private final QualityGovernanceService governance;

    QualityGovernanceController(ClinicalCommandSecurity security, QualityGovernanceService governance) {
        this.security = security;
        this.governance = governance;
    }

    @GetMapping("/records")
    ResponseEntity<List<QualityGovernanceRecordWire>> listRecords(
            HttpServletRequest request,
            @PathVariable("module_code") String moduleCode,
            @PathVariable("parent_resource_id") UUID parentId,
            @RequestParam(value = "record_kind", required = false) String recordKind,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(governance.listRecords(identity, organizationId, facilityId, moduleCode, parentId, recordKind));
    }

    @PostMapping("/records")
    ResponseEntity<QualityGovernanceRecordWire> createRecord(
            HttpServletRequest request,
            @PathVariable("module_code") String moduleCode,
            @PathVariable("parent_resource_id") UUID parentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody QualityGovernanceRecordCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(governance.createRecord(identity, moduleCode, parentId, idempotencyKey, command));
    }

    @PutMapping("/records/{quality_governance_record_id}")
    ResponseEntity<QualityGovernanceRecordWire> updateRecord(
            HttpServletRequest request,
            @PathVariable("module_code") String moduleCode,
            @PathVariable("parent_resource_id") UUID parentId,
            @PathVariable("quality_governance_record_id") UUID recordId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody QualityGovernanceRecordUpdateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(governance.updateRecord(identity, moduleCode, parentId, recordId, idempotencyKey, command));
    }

    @PostMapping("/records/{quality_governance_record_id}/void")
    ResponseEntity<QualityGovernanceRecordWire> voidRecord(
            HttpServletRequest request,
            @PathVariable("module_code") String moduleCode,
            @PathVariable("parent_resource_id") UUID parentId,
            @PathVariable("quality_governance_record_id") UUID recordId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody QualityGovernanceRecordVoidRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(governance.voidRecord(identity, moduleCode, parentId, recordId, idempotencyKey, command));
    }

    @GetMapping("/agent-proposals")
    ResponseEntity<List<QualityGovernanceAgentProposalWire>> listAgentProposals(
            HttpServletRequest request,
            @PathVariable("module_code") String moduleCode,
            @PathVariable("parent_resource_id") UUID parentId,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(governance.listAgentProposals(identity, organizationId, facilityId, moduleCode, parentId));
    }

    @PostMapping("/agent-proposals")
    ResponseEntity<QualityGovernanceAgentProposalWire> createAgentProposal(
            HttpServletRequest request,
            @PathVariable("module_code") String moduleCode,
            @PathVariable("parent_resource_id") UUID parentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody QualityGovernanceAgentProposalRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(governance.createAgentProposal(identity, moduleCode, parentId, idempotencyKey, command));
    }
}
