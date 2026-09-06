package org.haoemr.approval;

final class ActionExecutionException extends RuntimeException {

    private final String code;
    private final int status;

    ActionExecutionException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
