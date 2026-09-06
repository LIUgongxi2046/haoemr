package org.haoemr.model;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.haoemr.agent.DeepSeekHttpChatTransport;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
final class OpenAiCompatibleModelConnectionVerifier implements ModelConnectionVerifier {

    private final ObjectMapper objectMapper;

    OpenAiCompatibleModelConnectionVerifier(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ProbeResult probe(String modelCode, String endpointUrl, String apiKeyReference) {
        long started = System.nanoTime();
        // Reserved *.example endpoints are retired development configurations, never live providers.
        if (isReservedSyntheticEndpoint(endpointUrl)) {
            return ProbeResult.failed(elapsedMillis(started), "MODEL_DEPLOYMENT_SYNTHETIC_RETIRED");
        }
        try {
            Map<String, Object> response = DeepSeekHttpChatTransport.create(
                    endpointUrl, apiKeyReference, objectMapper).complete(Map.of(
                            "model", modelCode,
                            "messages", List.of(Map.of("role", "user", "content", "Reply with OK.")),
                            "max_tokens", 8,
                            "temperature", 0,
                            "stream", false));
            Object choices = response.get("choices");
            if (!(choices instanceof List<?> list) || list.isEmpty()) {
                return ProbeResult.failed(elapsedMillis(started), "MODEL_CONNECTION_RESPONSE_INVALID");
            }
            return ProbeResult.ready(elapsedMillis(started));
        } catch (RuntimeException failure) {
            String code = failure.getMessage();
            if (code == null || code.isBlank() || code.length() > 128) code = "MODEL_CONNECTION_FAILED";
            return ProbeResult.failed(elapsedMillis(started), code);
        }
    }

    private static boolean isReservedSyntheticEndpoint(String endpointUrl) {
        try {
            String host = URI.create(endpointUrl).getHost();
            return host != null && (host.equals("example") || host.endsWith(".example"));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static long elapsedMillis(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }
}
