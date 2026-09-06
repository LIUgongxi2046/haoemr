package org.haoemr.executioncenter;

final class ExecutionWorklistException extends RuntimeException {
    private final String code;
    private final int status;

    ExecutionWorklistException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }
    int status() { return status; }
}
