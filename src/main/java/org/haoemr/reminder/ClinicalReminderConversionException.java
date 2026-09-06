package org.haoemr.reminder;

final class ClinicalReminderConversionException extends RuntimeException {

    private final String code;
    private final int status;

    ClinicalReminderConversionException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
