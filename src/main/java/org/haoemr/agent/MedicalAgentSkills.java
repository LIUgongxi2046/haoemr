package org.haoemr.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;

/** Versioned, application-owned workflows; clinical input cannot redefine a skill. */
final class MedicalAgentSkills {
    static final Set<String> DOCUMENT_FIELDS = Set.of("chief_complaint", "present_illness", "past_history",
            "allergy_history", "physical_exam", "auxiliary_exam", "assessment", "treatment_plan", "followup_plan");
    private static final Map<String, Map<String, Object>> SKILLS = load();

    private MedicalAgentSkills() {}

    static Map<String, Object> skill(String agentCode) {
        return SKILLS.getOrDefault(agentCode, Map.of());
    }

    static Set<String> codes() { return SKILLS.keySet(); }

    static String instructions(String agentCode) {
        Map<String, Object> skill = skill(agentCode);
        if (skill.isEmpty()) return "";
        String instruction = "\n当前受控技能 " + agentCode + "@" + skill.get("version") + "："
                + skill.get("title") + "。\n执行步骤：" + skill.get("workflow")
                + "\nsummary 在500字内交付本次任务的实际成果，不只写已完成；facts 最多12条、每条300字内，逐条列依据及来源 source_id。"
                + "缺少关键输入、有效知识或工具结果时写入 gaps，不猜测数据。warnings 标注风险与适用边界。"
                + "gaps 和 warnings 各最多12条、每条200字内。资料不足也应明确哪些工作已经完成、哪些不能完成。不得把仿真知识当作真实诊疗规范。";
        if (Boolean.TRUE.equals(skill.get("document_draft"))) {
            instruction += "\n另外返回 draft_sections 对象，键只能是 chief_complaint、present_illness、past_history、"
                    + "allergy_history、physical_exam、auxiliary_exam、assessment、treatment_plan、followup_plan。"
                    + "值为可供医生编辑的段落字符串；只包含有证据支持的字段，缺失信息放 gaps。"
                    + "每个段落控制在400字内，不要重复 summary 和 facts 的全部内容。不要把患者标识、签名、权限、已执行声明或来源 ID 写入段落正文。";
        }
        return instruction;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> load() {
        try (var stream = MedicalAgentSkills.class.getResourceAsStream("/medical-agents/skills-v1.json")) {
            if (stream == null) throw new IllegalStateException("Medical-agent skills are missing");
            List<Map<String, Object>> rows = new ObjectMapper().readValue(stream, List.class);
            Map<String, Map<String, Object>> result = new LinkedHashMap<>();
            for (var row : rows) {
                String code = (String) row.get("agent_code");
                if (code == null || result.put(code, Map.copyOf(row)) != null) {
                    throw new IllegalStateException("Duplicate or invalid medical-agent skill");
                }
            }
            return Map.copyOf(result);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot load medical-agent skills", failure);
        }
    }
}
