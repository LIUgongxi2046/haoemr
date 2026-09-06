package org.haoemr.dictionary;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.DictionaryItemCreateRequestWire;
import org.haoemr.contracts.DictionaryItemDeactivateRequestWire;
import org.haoemr.contracts.DictionaryItemWire;
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
final class DictionaryController {
    private final ClinicalCommandSecurity security;
    private final DictionaryService dictionaries;

    DictionaryController(ClinicalCommandSecurity security, DictionaryService dictionaries) {
        this.security = security;
        this.dictionaries = dictionaries;
    }

    @GetMapping("/dictionary-items")
    ResponseEntity<List<DictionaryItemWire>> list(
            HttpServletRequest request,
            @RequestParam("dictionary_code") String dictionaryCode,
            @RequestHeader("X-Organization-Context") UUID organizationId,
            @RequestHeader("X-Facility-Context") UUID facilityId) {
        ClinicalIdentity identity = security.authorize(request, organizationId, facilityId, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(dictionaries.listItems(identity, dictionaryCode));
    }

    @PostMapping("/dictionary-items")
    ResponseEntity<DictionaryItemWire> create(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DictionaryItemCreateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(dictionaries.createItem(identity, idempotencyKey, command));
    }

    @PostMapping("/dictionary-items/{dictionary_item_id}/deactivations")
    ResponseEntity<DictionaryItemWire> deactivate(
            HttpServletRequest request,
            @PathVariable("dictionary_item_id") UUID itemId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DictionaryItemDeactivateRequestWire command) {
        ClinicalIdentity identity = security.authorize(
                request, command.organizationId(), command.facilityId(), null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(dictionaries.deactivateItem(identity, idempotencyKey, itemId, command));
    }
}
