package org.haoemr.agent;

final class AgentRunBudgetConsumptionException extends RuntimeException {

    private final String code;
    private final int status;

    AgentRunBudgetConsumptionException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
