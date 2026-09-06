package org.haoemr.archive;

final class MedicalRecordAssetException extends RuntimeException {

    private final String code;
    private final int status;

    MedicalRecordAssetException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }

    int status() { return status; }
}
