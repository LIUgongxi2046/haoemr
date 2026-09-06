package org.haoemr.emergency;

final class EmergencyPreadmissionException extends RuntimeException {

    private final String code;
    private final int status;

    EmergencyPreadmissionException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
