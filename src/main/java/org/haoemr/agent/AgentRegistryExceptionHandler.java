package org.haoemr.agent;

import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AgentRegistryController.class)
final class AgentRegistryExceptionHandler {
    @ExceptionHandler(AgentRegistryException.class)
    ResponseEntity<ApiErrorEnvelope> domainFailure(AgentRegistryException failure) {
        return ResponseEntity.status(failure.status()).body(error(failure.code(), failure.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiErrorEnvelope> constraintFailure(DataIntegrityViolationException ignored) {
        return ResponseEntity.status(409).body(error(
                "AGENT_REGISTRY_CONSTRAINT_CONFLICT", "The agent registry command conflicts with current facts"));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiErrorEnvelope> missingHeader(MissingRequestHeaderException ignored) {
        return ResponseEntity.badRequest().body(error(
                "REQUIRED_COMMAND_HEADER_MISSING", "A required command header is missing"));
    }

    private static ApiErrorEnvelope error(String code, String message) {
        return new ApiErrorEnvelope(new ApiError(
                code, "CONFLICT", message, UUID.randomUUID().toString(), false, null, List.of()));
    }

    record ApiErrorEnvelope(ApiError error) {}
    record ApiError(String code, String category, String message, String traceId,
                    boolean retryable, Object recovery, List<Object> violations) {}
}
