package org.haoemr.administration;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.administration.WorkgroupAdministrationService.EndRequest;
import org.haoemr.administration.WorkgroupAdministrationService.WorkgroupCreateRequest;
import org.haoemr.administration.WorkgroupAdministrationService.WorkgroupMemberCreateRequest;
import org.haoemr.administration.WorkgroupAdministrationService.WorkgroupWire;
import org.haoemr.security.ClinicalCommandSecurity;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/workgroups")
final class WorkgroupAdministrationController {
    private final ClinicalCommandSecurity security;
    private final WorkgroupAdministrationService workgroups;
    WorkgroupAdministrationController(ClinicalCommandSecurity security, WorkgroupAdministrationService workgroups) {
        this.security = security; this.workgroups = workgroups;
    }
    @GetMapping
    ResponseEntity<List<WorkgroupWire>> list(HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(workgroups.list(security.authenticate(request)));
    }
    @PostMapping
    ResponseEntity<WorkgroupWire> create(HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String key, @RequestBody WorkgroupCreateRequest body) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(workgroups.create(security.authenticate(request), key, body));
    }
    @PostMapping("/{workgroupId}/members")
    ResponseEntity<WorkgroupWire> addMember(HttpServletRequest request, @PathVariable UUID workgroupId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody WorkgroupMemberCreateRequest body) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(workgroups.addMember(security.authenticate(request), workgroupId, key, body));
    }
    @PostMapping("/{workgroupId}/members/{memberId}/end")
    ResponseEntity<WorkgroupWire> endMember(HttpServletRequest request, @PathVariable UUID workgroupId,
            @PathVariable UUID memberId, @RequestHeader("Idempotency-Key") String key,
            @RequestBody EndRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(workgroups.endMember(security.authenticate(request), workgroupId, memberId, key, body));
    }
    @PostMapping("/{workgroupId}/deactivate")
    ResponseEntity<WorkgroupWire> deactivate(HttpServletRequest request, @PathVariable UUID workgroupId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody EndRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(workgroups.deactivate(security.authenticate(request), workgroupId, key, body));
    }
}
