package org.haoemr.audit;

final class AuditEventException extends RuntimeException {

    private final String code;
    private final int status;

    AuditEventException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
