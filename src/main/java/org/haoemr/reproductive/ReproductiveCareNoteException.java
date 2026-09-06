package org.haoemr.reproductive;

final class ReproductiveCareNoteException extends RuntimeException {

    private final String code;
    private final int status;

    ReproductiveCareNoteException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
