package org.haoemr.agent;

final class AgentRegistryException extends RuntimeException {

    private final String code;
    private final int status;

    AgentRegistryException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
