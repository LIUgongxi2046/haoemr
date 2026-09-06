package org.haoemr.appointment;

final class AppointmentException extends RuntimeException {

    private final String code;
    private final int status;

    AppointmentException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
