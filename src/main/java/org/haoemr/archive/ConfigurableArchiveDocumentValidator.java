package org.haoemr.archive;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@Profile("!dev-synthetic")
final class ConfigurableArchiveDocumentValidator implements ArchiveDocumentValidator {
    private final String endpoint;
    private final ObjectMapper mapper;
    private final HttpClient http;

    ConfigurableArchiveDocumentValidator(
            @Value("${haoemr.archive.cda-validation-endpoint:}") String endpoint,
            ObjectMapper mapper) {
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public ValidationResult validate(byte[] content, String mediaType, String filename) {
        if (endpoint.isBlank()) {
            throw failure("CDA_VALIDATION_ADAPTER_UNAVAILABLE", 503,
                    "CDA validation requires haoemr.archive.cda-validation-endpoint");
        }
        try {
            String body = mapper.writeValueAsString(Map.of("filename", filename, "media_type", mediaType,
                    "profile", "CDA_R2_CN_LOCALIZED", "content_base64",
                    Base64.getEncoder().encodeToString(content)));
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw failure("CDA_VALIDATION_PROVIDER_REJECTED", 502,
                        "Configured CDA validation provider rejected the request");
            }
            JsonNode json = mapper.readTree(response.body());
            JsonNode validNode = json.path("valid");
            String engine = json.path("engine").isString() ? json.path("engine").stringValue().trim() : "";
            if (!validNode.isBoolean() || engine.isEmpty()) {
                throw failure("CDA_VALIDATION_PROVIDER_RESPONSE_INVALID", 502,
                        "Configured CDA validation provider returned invalid evidence");
            }
            return new ValidationResult(validNode.booleanValue(), engine,
                    sha256(response.body().getBytes(StandardCharsets.UTF_8)));
        } catch (MedicalRecordAssetException domain) {
            throw domain;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failure("CDA_VALIDATION_PROVIDER_INTERRUPTED", 503, "CDA validation was interrupted");
        } catch (Exception unavailable) {
            throw failure("CDA_VALIDATION_PROVIDER_UNAVAILABLE", 503,
                    "Configured CDA validation provider is unavailable");
        }
    }

    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static MedicalRecordAssetException failure(String code, int status, String message) {
        return new MedicalRecordAssetException(code, status, message);
    }
}
