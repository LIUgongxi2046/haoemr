package org.haoemr.obstetrics;

final class ObstetricDeliveryException extends RuntimeException {

    private final String code;
    private final int status;

    ObstetricDeliveryException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
