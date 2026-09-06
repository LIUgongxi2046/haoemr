package org.haoemr.executioncenter;

import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ExecutionWorklistController.class)
final class ExecutionWorklistExceptionHandler {
    @ExceptionHandler(ExecutionWorklistException.class)
    ResponseEntity<ApiErrorEnvelope> domainFailure(ExecutionWorklistException failure) {
        return ResponseEntity.status(failure.status()).body(new ApiErrorEnvelope(new ApiError(
                failure.code(), "VALIDATION", failure.getMessage(), UUID.randomUUID().toString(),
                false, null, List.of())));
    }

    record ApiErrorEnvelope(ApiError error) {}
    record ApiError(String code, String category, String message, String traceId,
                    boolean retryable, Object recovery, List<Object> violations) {}
}
