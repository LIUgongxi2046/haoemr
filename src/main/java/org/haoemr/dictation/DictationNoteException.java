package org.haoemr.dictation;

final class DictationNoteException extends RuntimeException {

    private final String code;
    private final int status;

    DictationNoteException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
