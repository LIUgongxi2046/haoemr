package org.haoemr.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

final class DeepSeekClinicalModelProviderTest {

    @TempDir
    Path temporaryDirectory;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void givenAValidJsonObjectResponse_whenGenerating_thenOnlyReviewedSectionsAreReturned() {
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        DeepSeekChatTransport transport = request -> {
            captured.set(request);
            return Map.of("choices", java.util.List.of(Map.of(
                    "message", Map.of("content", "{\"sections\":{\"assessment\":\"candidate\"}}"))));
        };
        DeepSeekClinicalModelProvider provider = new DeepSeekClinicalModelProvider(
                "deepseek-approved", transport, objectMapper);

        Map<String, Object> result = provider.generate(new ClinicalModelProvider.DraftPrompt(
                Map.of("chief_complaint", "synthetic"), 512));

        assertThat(provider.supports("DEEPSEEK")).isTrue();
        assertThat(result).containsEntry("model_behavior", "DEEPSEEK_REVIEW_REQUIRED");
        assertThat((Map<String, Object>) result.get("sections")).containsEntry("assessment", "candidate");
        assertThat(captured.get()).containsEntry("model", "deepseek-approved").containsEntry("stream", false);
        assertThat(captured.get().get("response_format")).isEqualTo(Map.of("type", "json_object"));
    }

    @Test
    void givenAnInvalidProviderResponse_whenGenerating_thenItFailsClosedWithoutReturningRawContent() {
        DeepSeekClinicalModelProvider provider = new DeepSeekClinicalModelProvider(
                "deepseek-approved", request -> Map.of("choices", java.util.List.of()), objectMapper);

        assertThatThrownBy(() -> provider.generate(new ClinicalModelProvider.DraftPrompt(Map.of(), 128)))
                .isInstanceOfSatisfying(ModelProviderUnavailableException.class,
                        failure -> assertThat(failure.code()).isEqualTo("MODEL_PROVIDER_RESPONSE_INVALID"));
    }

    @Test
    void givenAFileSecretReference_whenResolving_thenTheMountedSecretIsReadWithoutChangingTheFile() throws Exception {
        Path secret = temporaryDirectory.resolve("deepseek-api-key");
        Files.writeString(secret, "synthetic-secret\n");

        String resolved = new SecretReferenceResolver().resolve(secret.toUri().toString());

        assertThat(resolved).isEqualTo("synthetic-secret");
        assertThat(Files.readString(secret)).isEqualTo("synthetic-secret\n");
    }

    @Test
    void givenAnInlineSecretOrHttpEndpoint_whenConfiguring_thenBothAreRejected() {
        assertThatThrownBy(() -> new SecretReferenceResolver().resolve("inline-secret"))
                .isInstanceOf(ModelProviderUnavailableException.class);
        assertThatThrownBy(() -> DeepSeekHttpChatTransport.endpoint("http://model.example/v1"))
                .isInstanceOf(ModelProviderUnavailableException.class);
    }
}
