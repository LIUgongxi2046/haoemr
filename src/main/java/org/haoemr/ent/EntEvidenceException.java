package org.haoemr.ent;

final class EntEvidenceException extends RuntimeException {

    private final String code;
    private final int status;

    EntEvidenceException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
