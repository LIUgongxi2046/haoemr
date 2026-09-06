package org.haoemr.tasks;

final class ClinicalTaskNotificationException extends RuntimeException {

    private final String code;
    private final int status;

    ClinicalTaskNotificationException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
