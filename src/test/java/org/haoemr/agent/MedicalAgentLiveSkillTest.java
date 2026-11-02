package org.haoemr.agent;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "HAOEMR_LIVE_SKILL_SECRET_REF", matches = "file:///.+")
class MedicalAgentLiveSkillTest {
    @Test
    void liveModelRecognizesMissingEvidenceWithoutInventingClinicalFacts() {
        var environment = new StandardEnvironment();
        environment.setActiveProfiles("dev-synthetic");
        var gateway = new MedicalAgentModelGateway(environment, false, new ObjectMapper());
        var request = new MedicalAgentModelGateway.ModelRequest(
                "DEEPSEEK", "deepseek-flash", "https://api.deepseek.com",
                System.getenv("HAOEMR_LIVE_SKILL_SECRET_REF"),
                "ACTIVE_ENCOUNTER_SUMMARIZER", "当次就诊摘要", "连接与格式检查", "CLINICAL_SUMMARY",
                "执行简短连接与格式检查。当前没有患者资料，请说明需要补充的输入，禁止生成患者事实。总输出控制在200字内。",
                List.of(), 1024);
        assertThat(gateway.request(request).toString()).contains("2.0.0", "输入核验", "人工交接");
        var result = gateway.generate(request);
        assertThat(result.executionMode()).isEqualTo("LIVE_MODEL");
        assertThat(result.requestId()).isNotBlank();
        assertThat(result.totalTokens()).isPositive();
        assertThat((List<?>) result.output().get("facts")).isEmpty();
        assertThat((List<?>) result.output().get("gaps")).isNotEmpty();
    }
}
