package org.haoemr.archive;

import java.time.Instant;

interface ArchiveObjectStorage {
    String provider();
    void putImmutable(String storageKey, byte[] content);
    byte[] read(String storageKey);
    SealEvidence seal(String storageKey, Instant retainUntil);
    void deleteUnsealedBestEffort(String storageKey);

    record SealEvidence(String provider, String evidence) {}
}
