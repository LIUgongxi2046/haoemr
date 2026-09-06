package org.haoemr.ophthalmology;

final class OphthalmologyQcReviewException extends RuntimeException {

    private final String code;
    private final int status;

    OphthalmologyQcReviewException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
