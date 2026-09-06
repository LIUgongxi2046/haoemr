package org.haoemr.archive;

final class SourceSystemInventoryException extends RuntimeException {

    private final String code;
    private final int status;

    SourceSystemInventoryException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
