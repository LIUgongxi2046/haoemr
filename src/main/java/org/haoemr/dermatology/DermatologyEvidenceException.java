package org.haoemr.dermatology;

final class DermatologyEvidenceException extends RuntimeException {

    private final String code;
    private final int status;

    DermatologyEvidenceException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
