package org.haoemr.assistant;

final class ClinicalAssistantException extends RuntimeException {

    private final String code;
    private final int status;

    ClinicalAssistantException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
