package org.haoemr.tasks;

final class ClinicalTaskException extends RuntimeException {
    private final String code;
    private final int status;

    ClinicalTaskException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }
    int status() { return status; }
}

