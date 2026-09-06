package org.haoemr.transfusion;

final class BloodTransfusionException extends RuntimeException {

    private final String code;
    private final int status;

    BloodTransfusionException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
