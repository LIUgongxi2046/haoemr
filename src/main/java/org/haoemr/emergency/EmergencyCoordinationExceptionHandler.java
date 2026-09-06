package org.haoemr.emergency;

import java.util.List; import java.util.UUID;
import org.springframework.http.ResponseEntity; import org.springframework.web.bind.annotation.ExceptionHandler; import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = EmergencyCoordinationController.class)
final class EmergencyCoordinationExceptionHandler {
    @ExceptionHandler(EmergencyCoordinationException.class)
    ResponseEntity<ApiErrorEnvelope> failure(EmergencyCoordinationException failure) { return ResponseEntity.status(failure.status()).body(new ApiErrorEnvelope(new ApiError(failure.code(), "CONFLICT", failure.getMessage(), UUID.randomUUID().toString(), false, null, List.of()))); }
    record ApiErrorEnvelope(ApiError error) {}
    record ApiError(String code, String category, String message, String traceId, boolean retryable, Object recovery, List<Object> violations) {}
}
