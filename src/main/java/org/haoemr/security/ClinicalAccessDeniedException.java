package org.haoemr.security;

public final class ClinicalAccessDeniedException extends RuntimeException {

    private final String code;

    ClinicalAccessDeniedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
