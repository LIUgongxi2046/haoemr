package org.haoemr.authorization;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
final class AuthorizationAdministrationExceptionHandler {
    @ExceptionHandler(AuthorizationAdministrationException.class)
    ResponseEntity<Map<String, Object>> handle(AuthorizationAdministrationException exception) {
        return ResponseEntity.status(exception.status()).body(Map.of("error", Map.of(
                "code", exception.code(),
                "category", exception.status() == 403 ? "AUTHORIZATION" : "VALIDATION",
                "message", exception.getMessage(),
                "trace_id", UUID.randomUUID().toString(),
                "retryable", false,
                "violations", List.of())));
    }
}
