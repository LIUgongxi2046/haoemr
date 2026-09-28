package org.haoemr.clinical;

final class ConfigurationPackException extends RuntimeException {
    private final String code;
    private final int status;
    ConfigurationPackException(String code, int status, String message) {
        super(message); this.code = code; this.status = status;
    }
    String code() { return code; }
    int status() { return status; }
}
