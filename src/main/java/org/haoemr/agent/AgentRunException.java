package org.haoemr.agent;

final class AgentRunException extends RuntimeException {

    private final String code;
    private final int status;

    AgentRunException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
