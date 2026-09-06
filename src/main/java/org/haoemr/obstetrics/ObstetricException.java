package org.haoemr.obstetrics;

final class ObstetricException extends RuntimeException {

    private final String code;
    private final int status;

    ObstetricException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
