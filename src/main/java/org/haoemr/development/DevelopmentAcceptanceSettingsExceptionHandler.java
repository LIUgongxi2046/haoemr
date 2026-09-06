package org.haoemr.development;

import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = DevelopmentAcceptanceSettingsController.class)
final class DevelopmentAcceptanceSettingsExceptionHandler {
    @ExceptionHandler(DevelopmentAcceptanceSettingsException.class)
    ResponseEntity<ApiErrorEnvelope> domainFailure(DevelopmentAcceptanceSettingsException failure) {
        return ResponseEntity.status(failure.status()).body(error(failure.code(), failure.getMessage()));
    }

    private static ApiErrorEnvelope error(String code, String message) {
        return new ApiErrorEnvelope(new ApiError(
                code, "CONFIGURATION", message, UUID.randomUUID().toString(), false, null, List.of()));
    }

    record ApiErrorEnvelope(ApiError error) {}
    record ApiError(String code, String category, String message, String traceId,
                    boolean retryable, Object recovery, List<Object> violations) {}
}
