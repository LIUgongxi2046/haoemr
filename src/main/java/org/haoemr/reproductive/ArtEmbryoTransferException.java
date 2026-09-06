package org.haoemr.reproductive;

final class ArtEmbryoTransferException extends RuntimeException {

    private final String code;
    private final int status;

    ArtEmbryoTransferException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
