package org.haoemr.research;

final class ReleaseDownloadEventException extends RuntimeException {

    private final String code;
    private final int status;

    ReleaseDownloadEventException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
