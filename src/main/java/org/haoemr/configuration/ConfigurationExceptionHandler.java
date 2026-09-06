package org.haoemr.configuration;

import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ConfigurationController.class)
final class ConfigurationExceptionHandler {
    @ExceptionHandler(ConfigurationException.class)
    ResponseEntity<ApiErrorEnvelope> domainFailure(ConfigurationException failure) {
        return ResponseEntity.status(failure.status()).body(error(failure.code(), failure.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiErrorEnvelope> constraintFailure(DataIntegrityViolationException ignored) {
        return ResponseEntity.status(409).body(error(
                "CONFIG_CONSTRAINT_CONFLICT", "配置命令与当前事实冲突"));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiErrorEnvelope> missingHeader(MissingRequestHeaderException ignored) {
        return ResponseEntity.badRequest().body(error(
                "REQUIRED_COMMAND_HEADER_MISSING", "缺少必需的命令头"));
    }

    private static ApiErrorEnvelope error(String code, String message) {
        return new ApiErrorEnvelope(new ApiError(
                code, "CONFLICT", message, UUID.randomUUID().toString(), false, null, List.of()));
    }

    record ApiErrorEnvelope(ApiError error) {}
    record ApiError(String code, String category, String message, String traceId,
                    boolean retryable, Object recovery, List<Object> violations) {}
}
