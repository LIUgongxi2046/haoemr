package org.haoemr.agent;

final class AgentDependencyException extends RuntimeException {

    private final String code;
    private final int status;

    AgentDependencyException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
