package org.haoemr.dental;

final class DentalException extends RuntimeException {

    private final String code;
    private final int status;

    DentalException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
