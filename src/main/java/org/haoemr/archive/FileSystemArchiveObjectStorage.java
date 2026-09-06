package org.haoemr.archive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!prod")
final class FileSystemArchiveObjectStorage implements ArchiveObjectStorage {
    private static final int MAX_BYTES = 50 * 1024 * 1024;
    private static final Set<PosixFilePermission> READ_ONLY = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ);
    private final Path root;

    FileSystemArchiveObjectStorage(
            @Value("${haoemr.archive.storage-root:${java.io.tmpdir}/haoemr-archive-objects}") String root) {
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("haoemr.archive.storage-root is required");
        }
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public String provider() { return "FILESYSTEM_DEV"; }

    @Override
    public void putImmutable(String storageKey, byte[] content) {
        if (content == null || content.length < 1 || content.length > MAX_BYTES) {
            throw storageFailure("MEDICAL_RECORD_ASSET_FILE_SIZE_INVALID", 422,
                    "Asset content must be between 1 byte and 50 MiB");
        }
        Path target = resolve(storageKey);
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".asset-upload-", ".tmp");
            try {
                Files.write(temporary, content, StandardOpenOption.TRUNCATE_EXISTING);
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException duplicate) {
                throw storageFailure("MEDICAL_RECORD_ASSET_OBJECT_EXISTS", 409,
                        "Immutable asset object already exists");
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (MedicalRecordAssetException domain) {
            throw domain;
        } catch (IOException failure) {
            throw storageFailure("MEDICAL_RECORD_ASSET_STORAGE_FAILED", 503,
                    "Asset object could not be stored safely");
        }
    }

    @Override
    public byte[] read(String storageKey) {
        try {
            byte[] content = Files.readAllBytes(resolve(storageKey));
            if (content.length < 1 || content.length > MAX_BYTES) {
                throw storageFailure("MEDICAL_RECORD_ASSET_OBJECT_INVALID", 503,
                        "Stored asset object has an invalid size");
            }
            return content;
        } catch (MedicalRecordAssetException domain) {
            throw domain;
        } catch (IOException failure) {
            throw storageFailure("MEDICAL_RECORD_ASSET_OBJECT_UNAVAILABLE", 503,
                    "Stored asset object is unavailable");
        }
    }

    @Override
    public SealEvidence seal(String storageKey, Instant retainUntil) {
        Path target = resolve(storageKey);
        Path lock = resolve(storageKey + ".worm-lock");
        try {
            if (!Files.isRegularFile(target)) {
                throw storageFailure("MEDICAL_RECORD_ASSET_OBJECT_UNAVAILABLE", 503,
                        "Stored asset object is unavailable");
            }
            Files.writeString(lock, "retain-until=" + retainUntil, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            makeReadOnly(target);
            makeReadOnly(lock);
        } catch (FileAlreadyExistsException existing) {
            String recorded;
            try { recorded = Files.readString(lock, StandardCharsets.UTF_8); }
            catch (IOException failure) { throw storageFailure("MEDICAL_RECORD_ASSET_WORM_LOCK_FAILED", 503,
                    "WORM lock evidence could not be read"); }
            if (!recorded.equals("retain-until=" + retainUntil)) {
                throw storageFailure("MEDICAL_RECORD_ASSET_WORM_LOCK_CONFLICT", 409,
                        "WORM retention evidence cannot be replaced");
            }
        } catch (MedicalRecordAssetException domain) {
            throw domain;
        } catch (IOException failure) {
            throw storageFailure("MEDICAL_RECORD_ASSET_WORM_LOCK_FAILED", 503,
                    "WORM lock could not be created");
        }
        return new SealEvidence("FILESYSTEM_DEV", "filesystem-advisory:" + storageKey + ":" + retainUntil);
    }

    @Override
    public void deleteUnsealedBestEffort(String storageKey) {
        try {
            Path target = resolve(storageKey);
            if (!Files.exists(resolve(storageKey + ".worm-lock"))) Files.deleteIfExists(target);
        } catch (IOException | RuntimeException ignored) { }
    }

    private void makeReadOnly(Path target) throws IOException {
        try { Files.setPosixFilePermissions(target, READ_ONLY); }
        catch (UnsupportedOperationException unsupported) {
            if (!target.toFile().setReadOnly()) throw new IOException("read-only permission rejected");
        }
    }

    private Path resolve(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw storageFailure("MEDICAL_RECORD_ASSET_STORAGE_KEY_INVALID", 400, "Storage key is required");
        }
        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(root)) throw storageFailure(
                "MEDICAL_RECORD_ASSET_STORAGE_KEY_INVALID", 400, "Storage key escaped its tenant prefix");
        return target;
    }

    private static MedicalRecordAssetException storageFailure(String code, int status, String message) {
        return new MedicalRecordAssetException(code, status, message);
    }
}
