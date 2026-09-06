package org.haoemr.diagnosis;

final class DiagnosisException extends RuntimeException {
    private final String code;
    private final int status;

    DiagnosisException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }
    int status() { return status; }
}
