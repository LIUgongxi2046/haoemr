package org.haoemr.mock;

final class MockInterfaceException extends RuntimeException {

    private final String code;
    private final int status;

    MockInterfaceException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
