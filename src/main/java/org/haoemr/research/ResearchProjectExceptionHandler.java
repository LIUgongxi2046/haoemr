package org.haoemr.research;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ResearchProjectController.class)
final class ResearchProjectExceptionHandler {
    @ExceptionHandler(ResearchProjectException.class)
    ResponseEntity<Map<String, Object>> domainFailure(ResearchProjectException failure) {
        return ResponseEntity.status(failure.status()).body(Map.of(
                "code", failure.code(),
                "message", failure.getMessage()));
    }
}
