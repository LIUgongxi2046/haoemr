package org.haoemr.clinical;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.*;
import org.haoemr.security.ClinicalCommandSecurity;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/configuration-packs")
final class ConfigurationPackController {
    private final ClinicalCommandSecurity security;
    private final ConfigurationPackService packs;
    ConfigurationPackController(ClinicalCommandSecurity security,ConfigurationPackService packs){this.security=security;this.packs=packs;}
    @GetMapping ResponseEntity<List<ConfigurationPackCatalogItemWire>> catalog(HttpServletRequest request){return ok(packs.catalog(security.authenticate(request)));}
    @PostMapping("/prechecks") ResponseEntity<ConfigurationPackPrecheckWire> precheck(HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String key,@RequestBody ConfigurationPackPrecheckRequestWire body){return ok(packs.precheck(security.authenticate(request),key,body));}
    @PostMapping("/operations") ResponseEntity<ConfigurationPackOperationWire> install(HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String key,@RequestBody ConfigurationPackInstallRequestWire body){return ok(packs.install(security.authenticate(request),key,body));}
    @GetMapping("/operations") ResponseEntity<List<ConfigurationPackOperationWire>> list(HttpServletRequest request,
            @RequestParam("organization_id") UUID organization,@RequestParam("facility_id") UUID facility){return ok(packs.list(security.authenticate(request),organization,facility));}
    @GetMapping("/operations/{operation_id}") ResponseEntity<ConfigurationPackOperationWire> get(HttpServletRequest request,
            @PathVariable("operation_id") UUID id){return ok(packs.get(security.authenticate(request),id));}
    @PostMapping("/operations/{operation_id}/reconcile") ResponseEntity<ConfigurationPackOperationWire> reconcile(HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String key,@PathVariable("operation_id") UUID id){return ok(packs.reconcile(security.authenticate(request),id));}
    private static <T> ResponseEntity<T> ok(T value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
}
