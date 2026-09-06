package org.haoemr.integration;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = IntegrationMessageController.class)
final class IntegrationExceptionHandler {
    @ExceptionHandler(IntegrationException.class)
    ResponseEntity<Map<String, Object>> domainFailure(IntegrationException failure) {
        return ResponseEntity.status(failure.status()).body(Map.of(
                "code", failure.code(),
                "message", failure.getMessage()));
    }
}
