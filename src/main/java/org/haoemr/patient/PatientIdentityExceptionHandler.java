package org.haoemr.patient;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
final class PatientIdentityExceptionHandler {
    @ExceptionHandler(PatientIdentityException.class)
    ResponseEntity<Map<String, Object>> handle(PatientIdentityException exception) {
        String category = exception.status() == 403 ? "AUTHORIZATION" :
                exception.status() == 409 ? "CONFLICT" : "VALIDATION";
        return ResponseEntity.status(exception.status()).body(Map.of("error", Map.of(
                "code", exception.code(), "category", category, "message", exception.getMessage(),
                "trace_id", UUID.randomUUID().toString(), "retryable", false, "violations", List.of())));
    }
}
