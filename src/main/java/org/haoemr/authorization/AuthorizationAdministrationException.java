package org.haoemr.authorization;

final class AuthorizationAdministrationException extends RuntimeException {
    private final String code;
    private final int status;

    AuthorizationAdministrationException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }
    int status() { return status; }
}
