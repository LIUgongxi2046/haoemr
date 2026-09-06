package org.haoemr.clinical;

final class ClinicalCommandException extends RuntimeException {

    private final String code;
    private final int status;
    private final String recoveryToken;

    ClinicalCommandException(String code, int status, String message) {
        this(code, status, message, null);
    }

    ClinicalCommandException(String code, int status, String message, String recoveryToken) {
        super(message);
        this.code = code;
        this.status = status;
        this.recoveryToken = recoveryToken;
    }

    String code() { return code; }

    int status() { return status; }

    String recoveryToken() { return recoveryToken; }
}
