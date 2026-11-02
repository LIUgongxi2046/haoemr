package org.haoemr.model;

public final class ModelDeploymentException extends RuntimeException {

    private final String code;
    private final int status;

    ModelDeploymentException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() { return code; }

    public int status() { return status; }
}
