package org.haoemr.quality;

final class DataQualityEvaluationException extends RuntimeException {

    private final String code;
    private final int status;

    DataQualityEvaluationException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
