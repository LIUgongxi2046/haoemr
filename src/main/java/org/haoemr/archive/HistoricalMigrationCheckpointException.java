package org.haoemr.archive;

final class HistoricalMigrationCheckpointException extends RuntimeException {

    private final String code;
    private final int status;

    HistoricalMigrationCheckpointException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
