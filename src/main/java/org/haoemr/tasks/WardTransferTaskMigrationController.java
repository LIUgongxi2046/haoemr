package org.haoemr.tasks;

import jakarta.servlet.http.HttpServletRequest;
import org.haoemr.contracts.WardTransferTaskMigrationRequestWire;
import org.haoemr.contracts.WardTransferTaskMigrationResultWire;
import org.haoemr.security.ClinicalCommandSecurity;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
final class WardTransferTaskMigrationController {
    private final ClinicalCommandSecurity security;
    private final WardTransferTaskMigrationService migrations;

    WardTransferTaskMigrationController(
            ClinicalCommandSecurity security, WardTransferTaskMigrationService migrations) {
        this.security = security;
        this.migrations = migrations;
    }

    @PostMapping("/clinical-tasks/ward-migrations")
    ResponseEntity<WardTransferTaskMigrationResultWire> migrate(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody WardTransferTaskMigrationRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), command.patientId(), command.encounterId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(migrations.migrateTasks(identity, idempotencyKey, command));
    }
}
