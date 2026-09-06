package org.haoemr.clinical;

final class DocumentTemplateException extends RuntimeException {
    private final String code;
    private final int status;

    DocumentTemplateException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }
    int status() { return status; }
}
