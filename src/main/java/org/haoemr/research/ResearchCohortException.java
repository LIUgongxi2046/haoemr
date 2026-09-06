package org.haoemr.research;

final class ResearchCohortException extends RuntimeException {

    private final String code;
    private final int status;

    ResearchCohortException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
