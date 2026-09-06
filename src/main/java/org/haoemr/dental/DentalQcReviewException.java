package org.haoemr.dental;

final class DentalQcReviewException extends RuntimeException {

    private final String code;
    private final int status;

    DentalQcReviewException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
