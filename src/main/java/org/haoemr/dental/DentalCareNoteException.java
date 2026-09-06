package org.haoemr.dental;

final class DentalCareNoteException extends RuntimeException {

    private final String code;
    private final int status;

    DentalCareNoteException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
