package org.haoemr.reminder;

final class ClinicalReminderException extends RuntimeException {

    private final String code;
    private final int status;

    ClinicalReminderException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
