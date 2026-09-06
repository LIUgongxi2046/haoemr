package org.haoemr.clinical;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = DocumentTemplateController.class)
final class DocumentTemplateExceptionHandler {
    @ExceptionHandler(DocumentTemplateException.class)
    ResponseEntity<Map<String, Object>> handle(DocumentTemplateException failure) {
        return ResponseEntity.status(failure.status()).body(Map.of("error", Map.of(
                "code", failure.code(), "category", failure.status() == 403 ? "AUTHORIZATION" :
                        failure.status() == 409 ? "CONFLICT" : "VALIDATION",
                "message", failure.getMessage(), "trace_id", UUID.randomUUID().toString(),
                "retryable", false, "violations", List.of())));
    }
}
