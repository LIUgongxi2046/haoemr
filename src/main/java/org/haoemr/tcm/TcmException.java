package org.haoemr.tcm;

final class TcmException extends RuntimeException {

    private final String code;
    private final int status;

    TcmException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
