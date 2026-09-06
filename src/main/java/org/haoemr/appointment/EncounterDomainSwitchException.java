package org.haoemr.appointment;

final class EncounterDomainSwitchException extends RuntimeException {

    private final String code;
    private final int status;

    EncounterDomainSwitchException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
