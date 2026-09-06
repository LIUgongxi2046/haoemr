package org.haoemr.dictionary;

final class DictionaryException extends RuntimeException {

    private final String code;
    private final int status;

    DictionaryException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
