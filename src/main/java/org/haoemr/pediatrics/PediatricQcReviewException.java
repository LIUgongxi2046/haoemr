package org.haoemr.pediatrics;

final class PediatricQcReviewException extends RuntimeException {

    private final String code;
    private final int status;

    PediatricQcReviewException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
