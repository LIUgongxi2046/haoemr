package org.haoemr.ophthalmology;

final class OphthalmologyPreopVerificationException extends RuntimeException {

    private final String code;
    private final int status;

    OphthalmologyPreopVerificationException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
