package org.haoemr.archive;

final class ArchiveException extends RuntimeException {
    private final String code;
    private final int status;

    ArchiveException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }
    int status() { return status; }
}
