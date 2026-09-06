package org.haoemr.tcm;

final class TcmCareNoteException extends RuntimeException {

    private final String code;
    private final int status;

    TcmCareNoteException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
