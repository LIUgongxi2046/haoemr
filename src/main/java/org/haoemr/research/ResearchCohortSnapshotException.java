package org.haoemr.research;

final class ResearchCohortSnapshotException extends RuntimeException {

    private final String code;
    private final int status;

    ResearchCohortSnapshotException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
