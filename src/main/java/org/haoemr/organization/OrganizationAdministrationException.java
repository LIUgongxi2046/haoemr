package org.haoemr.organization;

final class OrganizationAdministrationException extends RuntimeException {

    private final String code;
    private final int status;

    OrganizationAdministrationException(String code, int status, String message) {
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
