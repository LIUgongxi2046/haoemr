package org.haoemr.referral;

final class ReferralException extends RuntimeException {

    private final String code;
    private final int status;

    ReferralException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
