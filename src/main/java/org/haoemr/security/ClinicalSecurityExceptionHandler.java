package org.haoemr.security;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
final class ClinicalSecurityExceptionHandler {

    @ExceptionHandler(ClinicalAccessDeniedException.class)
    ResponseEntity<ApiErrorEnvelope> accessDenied(ClinicalAccessDeniedException denied) {
        HttpStatus status = "AUTHENTICATION_REQUIRED".equals(denied.code())
                ? HttpStatus.UNAUTHORIZED
                : HttpStatus.FORBIDDEN;
        return ResponseEntity.status(status).body(new ApiErrorEnvelope(new ApiError(
                denied.code(),
                "AUTHORIZATION",
                denied.getMessage(),
                UUID.randomUUID().toString(),
                false,
                new ApiRecovery("REAUTHENTICATE", null),
                List.of())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiErrorEnvelope> invalidRequest(IllegalArgumentException invalid) {
        return ResponseEntity.badRequest().body(new ApiErrorEnvelope(new ApiError(
                "INVALID_CONTEXT_LEASE_REQUEST",
                "VALIDATION",
                invalid.getMessage(),
                UUID.randomUUID().toString(),
                false,
                null,
                List.of())));
    }

    record ApiErrorEnvelope(ApiError error) {}

    record ApiError(
            String code,
            String category,
            String message,
            String traceId,
            boolean retryable,
            ApiRecovery recovery,
            List<Object> violations) {}

    record ApiRecovery(String action, String token) {}
}
