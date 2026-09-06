package org.haoemr.pediatrics;

final class PediatricGrowthException extends RuntimeException {

    private final String code;
    private final int status;

    PediatricGrowthException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
