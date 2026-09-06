package org.haoemr.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

class MedicalAgentModelGatewayTest {

    @Test
    void syntheticProfileIsExplicitAndProducesMeasuredStructuredOutput() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev-synthetic");
        environment.setProperty("haoemr.medical-agent.synthetic-test-models-enabled", "true");
        MedicalAgentModelGateway gateway = new MedicalAgentModelGateway(environment, false, new ObjectMapper());

        MedicalAgentModelGateway.ModelResult result = gateway.generate(request());

        assertThat(result.executionMode()).isEqualTo("SYNTHETIC_MODEL");
        assertThat(result.totalTokens()).isPositive();
        assertThat(result.requestId()).startsWith("synthetic-");
        assertThat(result.output()).containsKeys("summary", "facts", "gaps", "warnings");
    }

    @Test
    void nonProductionProfileCannotSilentlyFallBackToDemoOutput() {
        MedicalAgentModelGateway gateway = new MedicalAgentModelGateway(
                new MockEnvironment(), false, new ObjectMapper());

        assertThatThrownBy(() -> gateway.generate(request()))
                .isInstanceOf(ModelProviderUnavailableException.class)
                .extracting("code")
                .isEqualTo("MEDICAL_AGENT_MODEL_EXECUTION_DISABLED");
    }

    @Test
    void syntheticDevelopmentUsesTheRealTransportForANonFixtureEndpoint() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev-synthetic");
        MedicalAgentModelGateway gateway = new MedicalAgentModelGateway(environment, false, new ObjectMapper());

        assertThatThrownBy(() -> gateway.generate(request("https://api.deepseek.com/v1")))
                .isInstanceOf(ModelProviderUnavailableException.class)
                .extracting("code")
                .isEqualTo("MODEL_PROVIDER_SECRET_UNAVAILABLE");
    }

    @Test
    void syntheticProfileRejectsReservedFixtureEndpointWithoutExplicitTestSwitch() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev-synthetic");
        MedicalAgentModelGateway gateway = new MedicalAgentModelGateway(environment, false, new ObjectMapper());

        assertThatThrownBy(() -> gateway.generate(request()))
                .isInstanceOf(ModelProviderUnavailableException.class)
                .extracting("code")
                .isEqualTo("MEDICAL_AGENT_SYNTHETIC_MODEL_FORBIDDEN");
    }

    @Test
    void structuredClinicalRequestDisablesThinkingSoReasoningCannotConsumeTheFinalJsonBudget() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev-synthetic");
        MedicalAgentModelGateway gateway = new MedicalAgentModelGateway(environment, false, new ObjectMapper());

        Map<String, Object> payload = gateway.request(request("https://api.deepseek.com"));

        assertThat(payload.get("thinking")).isEqualTo(Map.of("type", "disabled"));
        assertThat(payload.get("response_format")).isEqualTo(Map.of("type", "json_object"));
    }

    @Test
    void truncatedProviderOutputIsRejectedWithAStableOperationalCode() {
        MedicalAgentModelGateway gateway = new MedicalAgentModelGateway(
                new MockEnvironment(), false, new ObjectMapper());
        Map<String, Object> response = Map.of(
                "choices", List.of(Map.of(
                        "finish_reason", "length",
                        "message", Map.of("content", "", "reasoning_content", "private reasoning"))),
                "usage", Map.of("prompt_tokens", 10, "completion_tokens", 20, "total_tokens", 30));

        assertThatThrownBy(() -> gateway.parseOutput(response))
                .isInstanceOf(ModelProviderUnavailableException.class)
                .extracting("code")
                .isEqualTo("MEDICAL_AGENT_MODEL_OUTPUT_TRUNCATED");
    }

    private static MedicalAgentModelGateway.ModelRequest request() {
        return request("https://api.tertiary-hospital.example/v1");
    }

    private static MedicalAgentModelGateway.ModelRequest request(String endpoint) {
        return new MedicalAgentModelGateway.ModelRequest(
                "DEEPSEEK", "deepseek-chat", endpoint,
                "env://HAOEMR_TEST_MODEL_KEY_MUST_BE_ABSENT_42",
                "OPD_SUMMARY", "门诊摘要医助", "汇总本次就诊", "MedicalSummary",
                "请汇总就诊重点",
                List.of(new MedicalAgentModelGateway.ToolEvidence(
                        "CLINICAL_DOCUMENT_READ", "病历文书", List.of(Map.of("section", "HPI")))),
                1024);
    }
}
