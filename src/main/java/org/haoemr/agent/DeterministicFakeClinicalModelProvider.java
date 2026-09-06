package org.haoemr.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("dev-synthetic")
@ConditionalOnProperty(name = "haoemr.agent-run.synthetic-test-model-enabled", havingValue = "true")
final class DeterministicFakeClinicalModelProvider implements ClinicalModelProvider {

    @Override
    public boolean supports(String providerCode) {
        return "DETERMINISTIC_FAKE".equals(providerCode);
    }

    @Override
    public Map<String, Object> generate(DraftPrompt prompt) {
        Map<String, Object> proposed = new LinkedHashMap<>(prompt.currentSections());
        proposed.putIfAbsent("assessment", "AI 候选：请医生结合当前病历完成诊断评估。");
        proposed.putIfAbsent("treatment_plan", "AI 候选：请医生核对后补充处置与随访计划。");
        return Map.of(
                "sections", proposed,
                "notice", "此内容由确定性测试模型生成，必须由医生审阅后才能进入病历命令。",
                "model_behavior", "DETERMINISTIC_FAKE");
    }
}
