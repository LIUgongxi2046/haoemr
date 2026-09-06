package org.haoemr.neonatal;

final class NeonatalTreatmentException extends RuntimeException {

    private final String code;
    private final int status;

    NeonatalTreatmentException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
