package org.haoemr.mentalhealth;

final class MentalHealthException extends RuntimeException {

    private final String code;
    private final int status;

    MentalHealthException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
