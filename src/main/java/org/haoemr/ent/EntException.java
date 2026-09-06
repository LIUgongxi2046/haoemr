package org.haoemr.ent;

final class EntException extends RuntimeException {

    private final String code;
    private final int status;

    EntException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
