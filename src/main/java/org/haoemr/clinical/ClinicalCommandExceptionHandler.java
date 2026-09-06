package org.haoemr.clinical;

import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {
        ClinicalLifecycleController.class,
        DocumentEvidenceController.class,
        DocumentSignatureVerificationController.class,
        DocumentAuditTrailController.class
})
final class ClinicalCommandExceptionHandler {

    @ExceptionHandler(ClinicalCommandException.class)
    ResponseEntity<ApiErrorEnvelope> commandFailure(ClinicalCommandException failure) {
        ApiRecovery recovery = failure.recoveryToken() == null
                ? null
                : new ApiRecovery("OPEN_DIFF", failure.recoveryToken());
        String category = failure.status() == 409 ? "CONFLICT" : "VALIDATION";
        return ResponseEntity.status(failure.status()).body(error(
                failure.code(), category, failure.getMessage(), recovery));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiErrorEnvelope> constraintFailure(DataIntegrityViolationException ignored) {
        return ResponseEntity.status(409).body(error(
                "CLINICAL_CONSTRAINT_CONFLICT", "CONFLICT",
                "The command conflicts with an existing clinical record", null));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiErrorEnvelope> missingHeader(MissingRequestHeaderException missing) {
        return ResponseEntity.badRequest().body(error(
                "REQUIRED_COMMAND_HEADER_MISSING", "VALIDATION",
                "A required command header is missing", null));
    }

    private static ApiErrorEnvelope error(String code, String category, String message, ApiRecovery recovery) {
        return new ApiErrorEnvelope(new ApiError(
                code, category, message, UUID.randomUUID().toString(), false, recovery, List.of()));
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
