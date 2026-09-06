package org.haoemr.agent;

final class ToolRegistryException extends RuntimeException {

    private final String code;
    private final int status;

    ToolRegistryException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
