package org.haoemr.archive;

interface ArchiveDocumentValidator {
    ValidationResult validate(byte[] content, String mediaType, String filename);

    record ValidationResult(boolean valid, String engine, String evidenceHash) {}
}
