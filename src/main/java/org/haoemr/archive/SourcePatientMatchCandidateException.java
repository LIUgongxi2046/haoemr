package org.haoemr.archive;

final class SourcePatientMatchCandidateException extends RuntimeException {

    private final String code;
    private final int status;

    SourcePatientMatchCandidateException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
