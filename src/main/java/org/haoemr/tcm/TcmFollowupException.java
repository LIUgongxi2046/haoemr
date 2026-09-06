package org.haoemr.tcm;

final class TcmFollowupException extends RuntimeException {

    private final String code;
    private final int status;

    TcmFollowupException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
