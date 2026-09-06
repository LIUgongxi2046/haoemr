package org.haoemr.agent;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

public final class SecretReferenceResolver {

    private static final Pattern ENV_KEY = Pattern.compile("[A-Z][A-Z0-9_]{2,127}");
    private static final long MAX_SECRET_FILE_BYTES = 65_536;

    public String resolve(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new ModelProviderUnavailableException("MODEL_PROVIDER_SECRET_UNAVAILABLE");
        }
        if (reference.startsWith("env://")) {
            String key = reference.substring("env://".length());
            if (!ENV_KEY.matcher(key).matches()) {
                throw new ModelProviderUnavailableException("MODEL_PROVIDER_SECRET_REFERENCE_INVALID");
            }
            String value = System.getenv(key);
            if (value == null || value.isBlank()) {
                throw new ModelProviderUnavailableException("MODEL_PROVIDER_SECRET_UNAVAILABLE");
            }
            return value.trim();
        }
        if (reference.startsWith("file://")) {
            try {
                Path path = Path.of(URI.create(reference));
                long size = Files.size(path);
                if (!path.isAbsolute() || !Files.isRegularFile(path) || !Files.isReadable(path)
                        || size < 1 || size > MAX_SECRET_FILE_BYTES) {
                    throw new ModelProviderUnavailableException("MODEL_PROVIDER_SECRET_UNAVAILABLE");
                }
                String value = Files.readString(path, StandardCharsets.UTF_8).trim();
                if (value.isBlank()) {
                    throw new ModelProviderUnavailableException("MODEL_PROVIDER_SECRET_UNAVAILABLE");
                }
                return value;
            } catch (ModelProviderUnavailableException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new ModelProviderUnavailableException("MODEL_PROVIDER_SECRET_UNAVAILABLE");
            }
        }
        throw new ModelProviderUnavailableException("MODEL_PROVIDER_SECRET_REFERENCE_INVALID");
    }
}
