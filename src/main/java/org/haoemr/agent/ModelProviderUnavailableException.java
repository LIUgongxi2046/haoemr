package org.haoemr.agent;

final class ModelProviderUnavailableException extends RuntimeException {

    private final String code;
    private final String requestId;
    private final int promptTokens;
    private final int completionTokens;
    private final int totalTokens;
    private final long durationMs;
    private final String executionMode;

    ModelProviderUnavailableException(String code) {
        this(code, null, 0, 0, 0, 0, "NOT_STARTED");
    }

    ModelProviderUnavailableException(
            String code,
            String requestId,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            long durationMs,
            String executionMode) {
        super(code);
        this.code = code;
        this.requestId = requestId;
        this.promptTokens = Math.max(0, promptTokens);
        this.completionTokens = Math.max(0, completionTokens);
        this.totalTokens = Math.max(0, totalTokens);
        this.durationMs = Math.max(0, durationMs);
        this.executionMode = executionMode == null || executionMode.isBlank() ? "NOT_STARTED" : executionMode;
    }

    String code() {
        return code;
    }

    String requestId() {
        return requestId;
    }

    int promptTokens() {
        return promptTokens;
    }

    int completionTokens() {
        return completionTokens;
    }

    int totalTokens() {
        return totalTokens;
    }

    long durationMs() {
        return durationMs;
    }

    String executionMode() {
        return executionMode;
    }

    ModelProviderUnavailableException withInvocationEvidence(
            String providerRequestId,
            int providerPromptTokens,
            int providerCompletionTokens,
            int providerTotalTokens,
            long providerDurationMs,
            String providerExecutionMode) {
        return new ModelProviderUnavailableException(code, providerRequestId, providerPromptTokens,
                providerCompletionTokens, providerTotalTokens, providerDurationMs, providerExecutionMode);
    }
}
