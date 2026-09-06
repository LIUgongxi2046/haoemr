package org.haoemr.platform;

final class CapabilityPackException extends RuntimeException {

    private final String code;
    private final int status;

    CapabilityPackException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
