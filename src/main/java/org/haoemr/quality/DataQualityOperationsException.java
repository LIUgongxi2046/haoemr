package org.haoemr.quality;

final class DataQualityOperationsException extends RuntimeException {
    private final String code;
    private final int status;

    DataQualityOperationsException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
