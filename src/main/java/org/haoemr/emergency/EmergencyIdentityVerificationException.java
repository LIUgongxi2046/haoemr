package org.haoemr.emergency;

final class EmergencyIdentityVerificationException extends RuntimeException {
    private final String code;
    private final int status;

    EmergencyIdentityVerificationException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
