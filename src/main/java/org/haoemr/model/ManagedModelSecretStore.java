package org.haoemr.model;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class ManagedModelSecretStore {

    private static final int MAX_SECRET_LENGTH = 4096;
    private final Path root;

    ManagedModelSecretStore(
            @Value("${haoemr.model-secrets.directory:${java.io.tmpdir}/haoemr-model-secrets}")
            String directory) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
    }

    StoredSecret store(UUID tenantId, UUID deploymentId, String rawSecret) {
        String secret = normalize(rawSecret);
        Path tenantDirectory = root.resolve(tenantId.toString()).normalize();
        if (!tenantDirectory.startsWith(root)) throw unavailable();
        Path temporary = null;
        try {
            Files.createDirectories(tenantDirectory);
            restrictDirectory(root);
            restrictDirectory(tenantDirectory);
            temporary = Files.createTempFile(tenantDirectory, deploymentId + "-", ".tmp");
            Files.writeString(temporary, secret, StandardCharsets.UTF_8);
            restrictFile(temporary);
            Path target = tenantDirectory.resolve(deploymentId + "-" + UUID.randomUUID() + ".secret");
            moveAtomically(temporary, target);
            restrictFile(target);
            return new StoredSecret(target.toUri().toString(), masked(secret));
        } catch (IOException failure) {
            if (temporary != null) deletePath(temporary);
            throw unavailable();
        }
    }

    Optional<String> maskedHint(String reference) {
        Path path = managedPath(reference).orElse(null);
        if (path == null) return Optional.empty();
        try {
            String secret = Files.readString(path, StandardCharsets.UTF_8).trim();
            return Optional.of("已配置 · " + masked(secret));
        } catch (IOException failure) {
            return Optional.of("已配置 · ••••");
        }
    }

    void deleteManaged(String reference) {
        managedPath(reference).ifPresent(ManagedModelSecretStore::deletePath);
    }

    private Optional<Path> managedPath(String reference) {
        if (reference == null || !reference.startsWith("file://")) return Optional.empty();
        try {
            Path path = Path.of(URI.create(reference)).toAbsolutePath().normalize();
            return path.startsWith(root) ? Optional.of(path) : Optional.empty();
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private static String normalize(String value) {
        String secret = value == null ? "" : value.trim();
        if (secret.length() < 8 || secret.length() > MAX_SECRET_LENGTH
                || secret.chars().anyMatch(Character::isWhitespace)) {
            throw new ModelDeploymentException("MODEL_DEPLOYMENT_REQUEST_INVALID", 400,
                    "api_key must contain 8 to 4096 non-whitespace characters");
        }
        return secret;
    }

    private static String masked(String secret) {
        String suffix = secret.length() <= 4 ? secret : secret.substring(secret.length() - 4);
        return "••••" + suffix;
    }

    private static void restrictDirectory(Path directory) throws IOException {
        try {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX platforms use their filesystem ACLs.
        }
    }

    private static void restrictFile(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX platforms use their filesystem ACLs.
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target);
        }
    }

    private static void deletePath(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup; never disclose a secret path in an error response.
        }
    }

    private static ModelDeploymentException unavailable() {
        return new ModelDeploymentException("MODEL_CREDENTIAL_STORE_UNAVAILABLE", 500,
                "The protected model credential store is unavailable");
    }

    record StoredSecret(String reference, String maskedHint) {}
}
