package org.haoemr.inpatient;

final class InpatientException extends RuntimeException {

    private final String code;
    private final int status;

    InpatientException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
