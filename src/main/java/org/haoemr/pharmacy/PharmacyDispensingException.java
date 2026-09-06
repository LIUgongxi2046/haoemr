package org.haoemr.pharmacy;

final class PharmacyDispensingException extends RuntimeException {

    private final String code;
    private final int status;

    PharmacyDispensingException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
