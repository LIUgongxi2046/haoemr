package org.haoemr.agent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@Profile("prod")
@ConditionalOnProperty(name = "haoemr.production.ai.enabled", havingValue = "true")
public final class DeepSeekHttpChatTransport implements DeepSeekChatTransport {

    private final URI endpoint;
    private final String apiKeyReference;
    private final ObjectMapper objectMapper;
    private final HttpClient http;
    private final SecretReferenceResolver secrets;

    DeepSeekHttpChatTransport(
            @Value("${haoemr.production.ai.base-uri}") String baseUri,
            @Value("${haoemr.production.ai.api-key-ref}") String apiKeyReference,
            ObjectMapper objectMapper) {
        this(baseUri, apiKeyReference, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
    }

    DeepSeekHttpChatTransport(
            String baseUri, String apiKeyReference, ObjectMapper objectMapper, HttpClient http) {
        this.endpoint = endpoint(baseUri);
        this.apiKeyReference = apiKeyReference;
        this.objectMapper = objectMapper;
        this.http = http;
        this.secrets = new SecretReferenceResolver();
    }

    public static DeepSeekHttpChatTransport create(
            String baseUri, String apiKeyReference, ObjectMapper objectMapper) {
        return new DeepSeekHttpChatTransport(baseUri, apiKeyReference, objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> complete(Map<String, Object> request) {
        try {
            String apiKey = secrets.resolve(apiKeyReference);
            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(45))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                    .build();
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ModelProviderUnavailableException("MODEL_PROVIDER_HTTP_" + response.statusCode());
            }
            return objectMapper.convertValue(objectMapper.readTree(response.body()), Map.class);
        } catch (ModelProviderUnavailableException failure) {
            throw failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ModelProviderUnavailableException("MODEL_PROVIDER_INTERRUPTED");
        } catch (Exception failure) {
            throw new ModelProviderUnavailableException("MODEL_PROVIDER_UNAVAILABLE");
        }
    }

    static URI endpoint(String baseUri) {
        URI base;
        try {
            base = URI.create(baseUri);
        } catch (IllegalArgumentException invalid) {
            throw new ModelProviderUnavailableException("MODEL_PROVIDER_ENDPOINT_INVALID");
        }
        if (!"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null || base.getHost().isBlank()) {
            throw new ModelProviderUnavailableException("MODEL_PROVIDER_ENDPOINT_INVALID");
        }
        String value = base.toString().replaceAll("/+$", "");
        if (value.endsWith("/chat/completions")) {
            return URI.create(value);
        }
        return URI.create(value + "/chat/completions");
    }
}
