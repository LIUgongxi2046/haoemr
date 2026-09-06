package org.haoemr.tasks;

final class ClinicalTaskTeamQueueException extends RuntimeException {

    private final String code;
    private final int status;

    ClinicalTaskTeamQueueException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
