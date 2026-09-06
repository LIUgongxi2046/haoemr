package org.haoemr.ophthalmology;

final class OphthalmologyEvidenceException extends RuntimeException {

    private final String code;
    private final int status;

    OphthalmologyEvidenceException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
