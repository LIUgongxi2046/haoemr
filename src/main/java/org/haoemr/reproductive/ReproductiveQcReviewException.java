package org.haoemr.reproductive;

final class ReproductiveQcReviewException extends RuntimeException {

    private final String code;
    private final int status;

    ReproductiveQcReviewException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
