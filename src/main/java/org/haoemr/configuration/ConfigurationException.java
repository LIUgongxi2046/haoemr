package org.haoemr.configuration;

final class ConfigurationException extends RuntimeException {

    private final String code;
    private final int status;

    ConfigurationException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
