package org.haoemr.administration;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {AdministrationRuntimeController.class, MasterDataRecordController.class,
        WorkgroupAdministrationController.class})
final class AdministrationRuntimeExceptionHandler {
    @ExceptionHandler(AdministrationRuntimeException.class)
    ResponseEntity<Map<String, Object>> handle(AdministrationRuntimeException error) {
        return ResponseEntity.status(error.status()).body(Map.of(
                "code", error.code(), "message", error.getMessage(), "occurred_at", Instant.now().toString()));
    }
}
