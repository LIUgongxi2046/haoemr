package org.haoemr.ent;

final class EntFollowupException extends RuntimeException {

    private final String code;
    private final int status;

    EntFollowupException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
