package org.haoemr.agent;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@Profile({"prod", "dev-synthetic"})
final class DeepSeekClinicalModelProvider implements ClinicalModelProvider {

    private final JdbcClient jdbc;
    private final String fallbackModelId;
    private final String fallbackBaseUri;
    private final String fallbackApiKeyReference;
    private final DeepSeekChatTransport testTransport;
    private final ObjectMapper objectMapper;

    @Autowired
    DeepSeekClinicalModelProvider(
            JdbcClient jdbc,
            @Value("${haoemr.production.ai.model-id:}") String modelId,
            @Value("${haoemr.production.ai.base-uri:}") String baseUri,
            @Value("${haoemr.production.ai.api-key-ref:}") String apiKeyReference,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.fallbackModelId = modelId;
        this.fallbackBaseUri = baseUri;
        this.fallbackApiKeyReference = apiKeyReference;
        this.testTransport = null;
        this.objectMapper = objectMapper;
    }

    DeepSeekClinicalModelProvider(String modelId, DeepSeekChatTransport transport, ObjectMapper objectMapper) {
        this.jdbc = null;
        this.fallbackModelId = modelId;
        this.fallbackBaseUri = "";
        this.fallbackApiKeyReference = "";
        this.testTransport = transport;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String providerCode) {
        return providerCode != null && !providerCode.isBlank() && !"DETERMINISTIC_FAKE".equals(providerCode);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> generate(DraftPrompt prompt) {
        try {
            Connection connection = connection(prompt);
            String currentSections = objectMapper.writeValueAsString(prompt.currentSections());
            Map<String, Object> request = Map.of(
                    "model", connection.modelId(),
                    "messages", List.of(
                            Map.of("role", "system", "content", """
                                    你是AI医助 Eva 的医疗文书草稿医助。只返回 JSON object，根字段必须且只能包含 sections。
                                    sections 只能是文书段落映射；不得返回签名、工具调用、SQL、授权或临床写入动作。
                                    保留不确定性，不编造未提供的患者事实；输出必须由医生人工审阅。
                                    """),
                            Map.of("role", "user", "content", "请基于当前文书段落生成候选草稿：" + currentSections)),
                    "response_format", Map.of("type", "json_object"),
                    "max_tokens", prompt.maxOutputTokens(),
                    "stream", false);
            Map<String, Object> response = connection.transport().complete(request);
            Object choicesValue = response.get("choices");
            if (!(choicesValue instanceof List<?> choices) || choices.isEmpty()
                    || !(choices.getFirst() instanceof Map<?, ?> choice)
                    || !(choice.get("message") instanceof Map<?, ?> message)
                    || !(message.get("content") instanceof String content)) {
                throw new ModelProviderUnavailableException("MODEL_PROVIDER_RESPONSE_INVALID");
            }
            Map<String, Object> candidate = objectMapper.convertValue(objectMapper.readTree(content), Map.class);
            Object sections = candidate.get("sections");
            if (!(sections instanceof Map<?, ?>)) {
                throw new ModelProviderUnavailableException("MODEL_PROVIDER_RESPONSE_INVALID");
            }
            return Map.of(
                    "sections", sections,
                    "notice", "DeepSeek 候选草稿，必须由医生审阅并重新通过业务校验。",
                    "model_behavior", "DEEPSEEK_REVIEW_REQUIRED");
        } catch (ModelProviderUnavailableException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ModelProviderUnavailableException("MODEL_PROVIDER_RESPONSE_INVALID");
        }
    }

    private Connection connection(DraftPrompt prompt) {
        if (testTransport != null) return new Connection(fallbackModelId, testTransport);
        if (jdbc != null && prompt.tenantId() != null && prompt.providerCode() != null && prompt.modelCode() != null) {
            ConnectionSettings settings = jdbc.sql("""
                    select model_code, endpoint_url, api_key_ref
                    from model_deployment
                    where tenant_id = :tenant and provider_code = :provider and model_code = :model
                      and status = 'ACTIVE' and evaluation_status = 'APPROVED' and connection_status = 'READY'
                      and test_only = false
                      and endpoint_url not like 'https://%.example/%'
                      and exists (
                        select 1 from model_evaluation evaluation
                        where evaluation.tenant_id = model_deployment.tenant_id
                          and evaluation.model_deployment_id = model_deployment.model_deployment_id
                          and evaluation.status = 'PASSED'
                      )
                    order by updated_at desc, model_deployment_id desc limit 1
                    """).param("tenant", prompt.tenantId()).param("provider", prompt.providerCode())
                    .param("model", prompt.modelCode())
                    .query((rs, row) -> new ConnectionSettings(rs.getString("model_code"),
                            rs.getString("endpoint_url"), rs.getString("api_key_ref")))
                    .optional().orElse(null);
            if (settings != null) {
                return new Connection(settings.modelId(), new DeepSeekHttpChatTransport(
                        settings.baseUri(), settings.apiKeyReference(), objectMapper,
                        java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build()));
            }
        }
        if (!fallbackModelId.isBlank() && !fallbackBaseUri.isBlank() && !fallbackApiKeyReference.isBlank()) {
            return new Connection(fallbackModelId, new DeepSeekHttpChatTransport(
                    fallbackBaseUri, fallbackApiKeyReference, objectMapper,
                    java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build()));
        }
        throw new ModelProviderUnavailableException("MODEL_PROVIDER_CONFIGURATION_MISSING");
    }

    private record Connection(String modelId, DeepSeekChatTransport transport) {}
    private record ConnectionSettings(String modelId, String baseUri, String apiKeyReference) {}
}
