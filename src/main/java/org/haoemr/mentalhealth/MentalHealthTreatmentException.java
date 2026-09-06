package org.haoemr.mentalhealth;

final class MentalHealthTreatmentException extends RuntimeException {

    private final String code;
    private final int status;

    MentalHealthTreatmentException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
