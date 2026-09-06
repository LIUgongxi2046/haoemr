package org.haoemr.prompt;

final class PromptReleaseException extends RuntimeException {

    private final String code;
    private final int status;

    PromptReleaseException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
