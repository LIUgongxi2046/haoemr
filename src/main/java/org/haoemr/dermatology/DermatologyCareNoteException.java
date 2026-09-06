package org.haoemr.dermatology;

final class DermatologyCareNoteException extends RuntimeException {

    private final String code;
    private final int status;

    DermatologyCareNoteException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
