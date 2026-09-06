package org.haoemr.pediatrics;

final class PediatricEvidenceException extends RuntimeException {

    private final String code;
    private final int status;

    PediatricEvidenceException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
