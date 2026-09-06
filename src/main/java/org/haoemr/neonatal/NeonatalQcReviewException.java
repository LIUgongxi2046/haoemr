package org.haoemr.neonatal;

final class NeonatalQcReviewException extends RuntimeException {

    private final String code;
    private final int status;

    NeonatalQcReviewException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
