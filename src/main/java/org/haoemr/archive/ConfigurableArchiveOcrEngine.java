package org.haoemr.archive;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
final class ConfigurableArchiveOcrEngine implements ArchiveOcrEngine {
    private final String endpoint;
    private final ObjectMapper mapper;
    private final HttpClient http;

    ConfigurableArchiveOcrEngine(
            @Value("${haoemr.archive.ocr-endpoint:}") String endpoint,
            ObjectMapper mapper) {
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public OcrResult extract(byte[] content, String mediaType, String filename) {
        if ("text/plain".equals(mediaType)) {
            String text = new String(content, StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) throw failed("OCR_EMPTY_RESULT", 422, "Text scan contains no readable content");
            return new OcrResult(text, 1.0, "haoemr-utf8-extractor-v1");
        }
        if (endpoint.isBlank()) {
            throw failed("OCR_ADAPTER_UNAVAILABLE", 503,
                    "Image/PDF OCR requires haoemr.archive.ocr-endpoint");
        }
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "filename", filename, "media_type", mediaType,
                    "content_base64", Base64.getEncoder().encodeToString(content)));
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw failed("OCR_PROVIDER_REJECTED", 502, "Configured OCR provider rejected the request");
            }
            JsonNode json = mapper.readTree(response.body());
            String text = json.path("text").asText("").trim();
            double confidence = json.path("confidence").asDouble(-1);
            String engine = json.path("engine").asText("configured-ocr-provider").trim();
            if (text.isEmpty() || confidence < 0 || confidence > 1 || engine.isEmpty()) {
                throw failed("OCR_PROVIDER_RESPONSE_INVALID", 502, "Configured OCR provider returned invalid evidence");
            }
            return new OcrResult(text, confidence, engine);
        } catch (MedicalRecordAssetException domain) {
            throw domain;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failed("OCR_PROVIDER_INTERRUPTED", 503, "OCR request was interrupted");
        } catch (Exception failure) {
            throw failed("OCR_PROVIDER_UNAVAILABLE", 503, "Configured OCR provider is unavailable");
        }
    }

    private static MedicalRecordAssetException failed(String code, int status, String message) {
        return new MedicalRecordAssetException(code, status, message);
    }
}
