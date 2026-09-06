package org.haoemr.quality;

final class QualityGovernanceException extends RuntimeException {
    private final String code;
    private final int status;

    QualityGovernanceException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }
    int status() { return status; }
}
