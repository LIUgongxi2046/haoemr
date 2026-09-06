package org.haoemr.quality;

import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = QualityGovernanceController.class)
final class QualityGovernanceExceptionHandler {
    @ExceptionHandler(QualityGovernanceException.class)
    ResponseEntity<ApiErrorEnvelope> domainFailure(QualityGovernanceException failure) {
        return ResponseEntity.status(failure.status()).body(error(failure.code(), failure.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiErrorEnvelope> constraintFailure(DataIntegrityViolationException ignored) {
        return ResponseEntity.status(409).body(error(
                "QUALITY_GOVERNANCE_CONSTRAINT_CONFLICT", "该质量治理记录与当前证据或业务约束冲突"));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiErrorEnvelope> missingHeader(MissingRequestHeaderException ignored) {
        return ResponseEntity.badRequest().body(error(
                "REQUIRED_COMMAND_HEADER_MISSING", "缺少必需的上下文或幂等请求头"));
    }

    private static ApiErrorEnvelope error(String code, String message) {
        return new ApiErrorEnvelope(new ApiError(
                code, "CONFLICT", message, UUID.randomUUID().toString(), false, null, List.of()));
    }

    record ApiErrorEnvelope(ApiError error) {}
    record ApiError(String code, String category, String message, String traceId,
                    boolean retryable, Object recovery, List<Object> violations) {}
}
