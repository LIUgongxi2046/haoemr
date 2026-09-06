package org.haoemr.neonatal;

final class NeonatalScreeningRecordException extends RuntimeException {

    private final String code;
    private final int status;

    NeonatalScreeningRecordException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
