package org.haoemr.agent;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

class MedicalAgentSkillsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final MedicalAgentModelGateway gateway = new MedicalAgentModelGateway(new MockEnvironment(), false, mapper);

    @Test void everyPublishedChildHasAnOwnedWorkflowAndEveryParentHasRealReadTools() throws Exception {
        try (var skills = getClass().getResourceAsStream("/medical-agents/skills-v1.json");
             var tools = getClass().getResourceAsStream("/medical-agents/tools-v1.json")) {
            List<Map<String,Object>> rows = mapper.readValue(skills, List.class);
            Map<String,Object> catalog = mapper.readValue(tools, Map.class);
            assertThat(MedicalAgentSkills.codes()).hasSize(58);
            assertThat(rows).allSatisfy(row -> {
                String code = row.get("agent_code").toString();
                assertThat(MedicalAgentSkills.instructions(code)).contains(row.get("workflow").toString(), "gaps", "source_id");
                assertThat(row.get("handoff_route")).isNotNull();
            });
            assertThat((Map<?,?>)catalog.get("dependencies")).hasSize(14);
        }
    }
    @Test void draftAcceptsSupportedParagraphsAndOmitsEmptyPlaceholders() throws Exception {
        var result = parse(Map.of("chief_complaint", "咳嗽三天", "allergy_history", ""));
        assertThat((Map<?,?>)result.get("draft_sections")).hasSize(1);
        assertThat(result.get("draft_sections")).isEqualTo(Map.of("chief_complaint","咳嗽三天"));
    }
    @Test void modelCannotSmuggleClinicalStateOrObjectsIntoDraftFields() {
        assertThatThrownBy(() -> parse(Map.of("signed_by", "model"))).isInstanceOf(ModelProviderUnavailableException.class);
        assertThatThrownBy(() -> parse(Map.of("chief_complaint", Map.of("instruction","write an order"))))
                .isInstanceOf(ModelProviderUnavailableException.class);
    }
    private Map<String,Object> parse(Map<String,Object> sections) throws Exception {
        String content = mapper.writeValueAsString(Map.of("summary","候选草稿", "facts",List.of(),"gaps",List.of(),"warnings",List.of(),"draft_sections",sections));
        return gateway.parseOutput(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",content)))));
    }
}
