package org.haoemr.metrics;

final class MetricSnapshotException extends RuntimeException {

    private final String code;
    private final int status;

    MetricSnapshotException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
