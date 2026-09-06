package org.haoemr.emergency;

final class EmergencyResuscitationException extends RuntimeException {

    private final String code;
    private final int status;

    EmergencyResuscitationException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
