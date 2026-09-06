package org.haoemr.pediatrics;

final class PediatricException extends RuntimeException {

    private final String code;
    private final int status;

    PediatricException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
