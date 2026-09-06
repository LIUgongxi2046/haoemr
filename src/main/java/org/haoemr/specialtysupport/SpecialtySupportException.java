package org.haoemr.specialtysupport;

final class SpecialtySupportException extends RuntimeException {

    private final String code;
    private final int status;

    SpecialtySupportException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() {
        return code;
    }

    int status() {
        return status;
    }
}
