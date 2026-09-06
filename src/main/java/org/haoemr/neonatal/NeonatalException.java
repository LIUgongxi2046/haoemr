package org.haoemr.neonatal;

final class NeonatalException extends RuntimeException {

    private final String code;
    private final int status;

    NeonatalException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
