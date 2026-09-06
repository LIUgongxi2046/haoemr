package org.haoemr.clinical;

interface ClinicalObjectStorage {
    void put(String storageKey, byte[] content, String mediaType);
    void deleteBestEffort(String storageKey);
}
