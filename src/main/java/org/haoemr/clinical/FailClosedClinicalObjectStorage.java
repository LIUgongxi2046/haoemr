package org.haoemr.clinical;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!dev-synthetic")
final class FailClosedClinicalObjectStorage implements ClinicalObjectStorage {
    @Override
    public void put(String storageKey, byte[] content, String mediaType) {
        throw new ClinicalCommandException("OBJECT_STORAGE_ADAPTER_UNAVAILABLE", 503,
                "The approved immutable object-storage adapter is not available");
    }

    @Override
    public void deleteBestEffort(String storageKey) { }
}
