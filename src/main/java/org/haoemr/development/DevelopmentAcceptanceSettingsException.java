package org.haoemr.development;

final class DevelopmentAcceptanceSettingsException extends RuntimeException {
    private final String code;
    private final int status;

    DevelopmentAcceptanceSettingsException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
