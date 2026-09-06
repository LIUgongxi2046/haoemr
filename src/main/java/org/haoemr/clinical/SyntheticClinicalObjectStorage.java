package org.haoemr.clinical;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("dev-synthetic")
final class SyntheticClinicalObjectStorage implements ClinicalObjectStorage {
    private final Path root;

    SyntheticClinicalObjectStorage(@Value("${haoemr.synthetic-object-storage-root:${java.io.tmpdir}/haoemr-synthetic-objects}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public void put(String storageKey, byte[] content, String mediaType) {
        Path target = resolve(storageKey);
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            try {
                Files.write(temporary, content);
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException failure) {
            throw new ClinicalCommandException("ATTACHMENT_STORAGE_FAILED", 503,
                    "The attachment object could not be stored safely");
        }
    }

    @Override
    public void deleteBestEffort(String storageKey) {
        try { Files.deleteIfExists(resolve(storageKey)); } catch (IOException ignored) { }
    }

    private Path resolve(String storageKey) {
        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(root)) throw new ClinicalCommandException(
                "INVALID_ATTACHMENT_STORAGE_KEY", 400, "Attachment storage key escaped its tenant prefix");
        return target;
    }
}
