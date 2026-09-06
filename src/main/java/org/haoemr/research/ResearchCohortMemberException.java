package org.haoemr.research;

final class ResearchCohortMemberException extends RuntimeException {

    private final String code;
    private final int status;

    ResearchCohortMemberException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
