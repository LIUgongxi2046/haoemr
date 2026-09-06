package org.haoemr.integration;

final class IntegrationException extends RuntimeException {
    private final String code;
    private final int status;

    IntegrationException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() {
        return code;
    }

    int status() {
        return status;
    }
}
