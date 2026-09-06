package org.haoemr.outpatient;

final class OutpatientFollowupException extends RuntimeException {

    private final String code;
    private final int status;

    OutpatientFollowupException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
