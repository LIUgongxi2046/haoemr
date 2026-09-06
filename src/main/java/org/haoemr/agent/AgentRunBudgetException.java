package org.haoemr.agent;

final class AgentRunBudgetException extends RuntimeException {

    private final String code;
    private final int status;

    AgentRunBudgetException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
