package org.haoemr.neonatal;

final class NeonatalWristbandVerificationException extends RuntimeException {

    private final String code;
    private final int status;

    NeonatalWristbandVerificationException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
