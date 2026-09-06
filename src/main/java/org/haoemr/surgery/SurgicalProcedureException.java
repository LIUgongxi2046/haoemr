package org.haoemr.surgery;

final class SurgicalProcedureException extends RuntimeException {

    private final String code;
    private final int status;

    SurgicalProcedureException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
