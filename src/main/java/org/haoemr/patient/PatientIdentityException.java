package org.haoemr.patient;

final class PatientIdentityException extends RuntimeException {
    private final String code;
    private final int status;

    PatientIdentityException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }
    int status() { return status; }
}
