package org.haoemr.archive;

import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ArchiveController.class)
final class ArchiveExceptionHandler {
    @ExceptionHandler(ArchiveException.class)
    ResponseEntity<ApiErrorEnvelope> archiveFailure(ArchiveException failure) {
        String category = failure.status() == 403 ? "AUTHORIZATION"
                : failure.status() == 400 ? "VALIDATION" : "CONFLICT";
        return ResponseEntity.status(failure.status()).body(error(failure.code(), category, failure.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiErrorEnvelope> constraintFailure(DataIntegrityViolationException ignored) {
        return ResponseEntity.status(409).body(error(
                "ARCHIVE_CONSTRAINT_CONFLICT", "CONFLICT",
                "The archive command conflicts with immutable archive evidence"));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiErrorEnvelope> missingHeader(MissingRequestHeaderException ignored) {
        return ResponseEntity.badRequest().body(error(
                "REQUIRED_COMMAND_HEADER_MISSING", "VALIDATION", "A required command header is missing"));
    }

    private static ApiErrorEnvelope error(String code, String category, String message) {
        return new ApiErrorEnvelope(new ApiError(
                code, category, message, UUID.randomUUID().toString(), false, null, List.of()));
    }

    record ApiErrorEnvelope(ApiError error) {}
    record ApiError(String code, String category, String message, String traceId,
                    boolean retryable, Object recovery, List<Object> violations) {}
}
