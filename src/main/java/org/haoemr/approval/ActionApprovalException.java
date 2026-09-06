package org.haoemr.approval;

final class ActionApprovalException extends RuntimeException {

    private final String code;
    private final int status;

    ActionApprovalException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
