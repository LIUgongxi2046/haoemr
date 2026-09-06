package org.haoemr.agent;

import java.util.List;
import java.util.UUID;
import org.haoemr.security.ClinicalAccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = MedicalAgentHarnessController.class)
final class MedicalAgentHarnessExceptionHandler {

    @ExceptionHandler(ClinicalAccessDeniedException.class)
    ResponseEntity<ApiErrorEnvelope> accessDenied(ClinicalAccessDeniedException denied) {
        HttpStatus status = "AUTHENTICATION_REQUIRED".equals(denied.code())
                ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(error(denied.code(), "AUTHORIZATION", denied.getMessage()));
    }

    @ExceptionHandler(AgentRunException.class)
    ResponseEntity<ApiErrorEnvelope> failure(AgentRunException failure) {
        String category = failure.status() == 409 ? "CONFLICT"
                : failure.status() == 403 ? "AUTHORIZATION" : "VALIDATION";
        return ResponseEntity.status(failure.status()).body(error(failure.code(), category, failure.getMessage()));
    }

    private static ApiErrorEnvelope error(String code, String category, String message) {
        return new ApiErrorEnvelope(new ApiError(code, category, message, UUID.randomUUID().toString(),
                false, null, List.of()));
    }

    record ApiErrorEnvelope(ApiError error) {}
    record ApiError(String code, String category, String message, String traceId,
            boolean retryable, Object recovery, List<Object> violations) {}
}
