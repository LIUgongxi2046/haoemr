package org.haoemr.mentalhealth;

final class MentalHealthCrisisFollowupException extends RuntimeException {

    private final String code;
    private final int status;

    MentalHealthCrisisFollowupException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
