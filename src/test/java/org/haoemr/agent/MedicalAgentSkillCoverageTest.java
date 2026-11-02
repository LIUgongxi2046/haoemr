package org.haoemr.agent;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class MedicalAgentSkillCoverageTest {
    @Test
    void everyMedicalWorkflowProvidesExecutableEvidenceAndHandoffRequirements() {
        assertThat(MedicalAgentSkills.codes()).hasSize(58);
        for (String code : MedicalAgentSkills.codes()) {
            var skill = MedicalAgentSkills.skill(code);
            assertThat(skill.get("version")).as(code).isEqualTo("2.0.0");
            for (String field : List.of("required_inputs", "steps", "output_requirements", "escalation", "registry_skills")) {
                assertThat(skill.get(field)).as(code + ":" + field).isInstanceOf(List.class);
                assertThat((List<?>) skill.get(field)).isNotEmpty();
            }
            String instructions = MedicalAgentSkills.instructions(code);
            assertThat(instructions).contains("输入核验", "执行步骤", "输出检查", "人工交接", "source_id", "2.0.0");
            for (Object step : (List<?>) skill.get("steps")) assertThat(instructions).contains(step.toString());
        }
    }

    @Test
    void specialtyWorkflowsRetainTheirDistinctClinicalBoundaries() {
        assertThat(MedicalAgentSkills.instructions("INSULIN_DOSE_ADVISOR")).contains("有效院内算法", "不给个体化剂量");
        assertThat(MedicalAgentSkills.instructions("COHORT_ENROLLMENT_ASSISTANT")).contains("研究同意", "纳排");
        assertThat(MedicalAgentSkills.instructions("HERB_INTERACTION_GUARD")).contains("中西药", "未知相互作用");
        assertThat(MedicalAgentSkills.instructions("OUTBREAK_ALERT_ASSESSOR")).contains("单患者", "全院");
    }

    @Test
    void allGovernanceSkillsHaveRuntimeConsumers() {
        var registryCodes = new HashSet<String>();
        for (String code : MedicalAgentSkills.codes()) {
            ((List<?>) MedicalAgentSkills.skill(code).get("registry_skills"))
                    .forEach(value -> registryCodes.add((String) value));
        }
        assertThat(registryCodes).hasSize(24).contains("CONTEXT_LEASE_GUARD", "PRIVACY_DEIDENTIFICATION",
                "MEDICATION_RISK_EXPLANATION", "DRG_RECORD_COMPLETENESS", "SOURCE_REFERENCE_CITATION");
    }
}
