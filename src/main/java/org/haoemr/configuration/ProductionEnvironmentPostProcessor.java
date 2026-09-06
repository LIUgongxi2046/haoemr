package org.haoemr.configuration;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContextException;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Rejects an incomplete or unsafe production profile before application beans and the web server are created.
 * Secret values are never returned, logged, or included in validation messages.
 */
public final class ProductionEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PREFIX = "haoemr.production.";
    private static final Pattern ENV_KEY = Pattern.compile("[A-Z][A-Z0-9_]{2,127}");
    private static final Set<String> DATA_RESIDENCY = Set.of("ON_PREM_ONLY", "CHINA_REGION_ONLY");
    private static final long MAX_SECRET_FILE_BYTES = 65_536;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.matchesProfiles("prod")) {
            return;
        }

        List<String> violations = new ArrayList<>();
        if (environment.matchesProfiles("dev-synthetic")) {
            violations.add("profiles: prod cannot be combined with dev-synthetic");
        }

        requireText(environment, "spring.datasource.url", violations);
        requireText(environment, "spring.datasource.username", violations);
        requireText(environment, PREFIX + "deployment-id", violations);
        requireFalse(environment, PREFIX + "allow-development-identity", violations);
        requireFalse(environment, "haoemr.synthetic-dataset-enabled", violations);
        requireTrue(environment, PREFIX + "identity.mfa-required", violations);
        requireHttps(environment, PREFIX + "identity.issuer-uri", violations);
        requireText(environment, PREFIX + "identity.audience", violations);
        requireText(environment, PREFIX + "identity.required-acr", violations);
        requireHttps(environment, "spring.security.oauth2.resourceserver.jwt.issuer-uri", violations);
        requireText(environment, "spring.security.oauth2.resourceserver.jwt.audiences", violations);
        requireEqual(
                environment,
                PREFIX + "identity.issuer-uri",
                "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                violations);
        requireEqual(
                environment,
                PREFIX + "identity.audience",
                "spring.security.oauth2.resourceserver.jwt.audiences",
                violations);

        requireHttps(environment, PREFIX + "signing.ca-base-uri", violations);
        requireHttps(environment, PREFIX + "signing.timestamp-uri", violations);
        requireSecretRef(environment, PREFIX + "signing.client-certificate-ref", violations);
        requireSecretRef(environment, PREFIX + "signing.private-key-ref", violations);
        requireSecretRef(environment, PREFIX + "signing.trust-anchor-ref", violations);

        requireUriScheme(environment, PREFIX + "encryption.kms-key-uri", "kms", violations);
        requireSecretRef(environment, PREFIX + "encryption.database-key-ref", violations);
        requireSecretRef(environment, PREFIX + "encryption.object-key-ref", violations);

        requireHttps(environment, PREFIX + "storage.endpoint", violations);
        requireText(environment, PREFIX + "storage.bucket", violations);
        requireTrue(environment, PREFIX + "storage.object-lock-required", violations);
        requireSecretRef(environment, PREFIX + "storage.access-key-ref", violations);
        requireSecretRef(environment, PREFIX + "storage.secret-key-ref", violations);
        requireHttps(environment, "haoemr.archive.ocr-endpoint", violations);
        requireHttps(environment, "haoemr.archive.cda-validation-endpoint", violations);
        requireText(environment, "haoemr.archive.malware-scanner.host", violations);

        requireSecretRef(environment, PREFIX + "integration.truststore-ref", violations);
        requireSecretRef(environment, PREFIX + "integration.client-certificate-ref", violations);
        requireSecretRef(environment, PREFIX + "database.password-ref", violations);

        String residency = requireText(environment, PREFIX + "data-residency", violations);
        if (residency != null && !DATA_RESIDENCY.contains(residency.toUpperCase(Locale.ROOT))) {
            violations.add(PREFIX + "data-residency: must be ON_PREM_ONLY or CHINA_REGION_ONLY");
        }

        Boolean aiEnabled = requireBoolean(environment, PREFIX + "ai.enabled", violations);
        if (Boolean.TRUE.equals(aiEnabled)) {
            requireHttps(environment, PREFIX + "ai.base-uri", violations);
            requireText(environment, PREFIX + "ai.model-id", violations);
            requireSecretRef(environment, PREFIX + "ai.api-key-ref", violations);
            String aiResidency = requireText(environment, PREFIX + "ai.residency", violations);
            if (aiResidency != null && !DATA_RESIDENCY.contains(aiResidency.toUpperCase(Locale.ROOT))) {
                violations.add(PREFIX + "ai.residency: must be ON_PREM_ONLY or CHINA_REGION_ONLY");
            }
        }

        if (!violations.isEmpty()) {
            throw new ApplicationContextException(
                    "Production configuration rejected (secret values omitted): " + String.join("; ", violations));
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    private static String requireText(
            ConfigurableEnvironment environment, String property, List<String> violations) {
        String value;
        try {
            value = environment.getProperty(property);
        } catch (IllegalArgumentException unresolvedPlaceholder) {
            violations.add(property + ": required or contains an unresolved placeholder");
            return null;
        }
        if (value == null || value.isBlank()) {
            violations.add(property + ": required");
            return null;
        }
        return value.trim();
    }

    private static Boolean requireBoolean(
            ConfigurableEnvironment environment, String property, List<String> violations) {
        String value = requireText(environment, property, violations);
        if (value == null) {
            return null;
        }
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            violations.add(property + ": must be true or false");
            return null;
        }
        return Boolean.valueOf(value);
    }

    private static void requireTrue(
            ConfigurableEnvironment environment, String property, List<String> violations) {
        Boolean value = requireBoolean(environment, property, violations);
        if (Boolean.FALSE.equals(value)) {
            violations.add(property + ": must be true in prod");
        }
    }

    private static void requireFalse(
            ConfigurableEnvironment environment, String property, List<String> violations) {
        Boolean value = requireBoolean(environment, property, violations);
        if (Boolean.TRUE.equals(value)) {
            violations.add(property + ": must be false in prod");
        }
    }

    private static void requireEqual(
            ConfigurableEnvironment environment,
            String expectedProperty,
            String actualProperty,
            List<String> violations) {
        String expected = environment.getProperty(expectedProperty);
        String actual = environment.getProperty(actualProperty);
        if (expected != null && !expected.isBlank() && actual != null && !actual.isBlank()
                && !expected.trim().equals(actual.trim())) {
            violations.add(actualProperty + ": must match " + expectedProperty);
        }
    }

    private static void requireHttps(
            ConfigurableEnvironment environment, String property, List<String> violations) {
        requireUriScheme(environment, property, "https", violations);
    }

    private static void requireUriScheme(
            ConfigurableEnvironment environment, String property, String scheme, List<String> violations) {
        String value = requireText(environment, property, violations);
        if (value == null) {
            return;
        }
        try {
            URI uri = URI.create(value);
            if (!scheme.equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()) {
                violations.add(property + ": must be an absolute " + scheme + " URI");
            }
        } catch (IllegalArgumentException invalid) {
            violations.add(property + ": must be an absolute " + scheme + " URI");
        }
    }

    private static void requireSecretRef(
            ConfigurableEnvironment environment, String property, List<String> violations) {
        String reference = requireText(environment, property, violations);
        if (reference == null) {
            return;
        }
        if (reference.startsWith("env://")) {
            validateEnvironmentReference(environment, property, reference.substring("env://".length()), violations);
            return;
        }
        if (reference.startsWith("file://")) {
            validateFileReference(property, reference, violations);
            return;
        }
        violations.add(property + ": must use env:// or file:// secret reference");
    }

    private static void validateEnvironmentReference(
            ConfigurableEnvironment environment, String property, String key, List<String> violations) {
        if (!ENV_KEY.matcher(key).matches()) {
            violations.add(property + ": env reference name is invalid");
            return;
        }
        String resolved = environment.getProperty(key);
        if (resolved == null || resolved.isBlank()) {
            violations.add(property + ": referenced environment secret is unavailable");
        }
    }

    private static void validateFileReference(String property, String reference, List<String> violations) {
        try {
            URI uri = URI.create(reference);
            Path path = Path.of(uri);
            if (!path.isAbsolute() || !Files.isRegularFile(path) || !Files.isReadable(path)) {
                violations.add(property + ": referenced secret file is unavailable");
                return;
            }
            long size = Files.size(path);
            if (size < 1 || size > MAX_SECRET_FILE_BYTES) {
                violations.add(property + ": referenced secret file size is outside 1..65536 bytes");
            }
        } catch (IllegalArgumentException invalid) {
            violations.add(property + ": file reference is invalid");
        } catch (java.io.IOException unreadable) {
            violations.add(property + ": referenced secret file is unavailable");
        }
    }
}
