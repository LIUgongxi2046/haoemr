package org.haoemr.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;

/** 应用维护医学工作流，临床输入不能修改执行要求。 */
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
                + "\n输入核验：" + skill.get("required_inputs")
                + "\n执行步骤：" + skill.get("steps")
                + "\n证据与安全要求：" + skill.get("shared_rules")
                + "\n输出检查：" + skill.get("output_requirements")
                + "\n人工交接：" + skill.get("escalation")
                + "\nsummary 在500字内交付本次任务的实际成果，不只写已完成；facts 最多12条、每条300字内，逐条列依据及来源 source_id。"
                + "缺少关键输入、有效知识或工具结果时写入 gaps，不猜测数据。warnings 标注风险与适用边界。"
                + "gaps 和 warnings 各最多12条、每条200字内。资料不足也应明确哪些工作已经完成、哪些不能完成。不得把仿真知识当作真实诊疗规范。";
        if (Boolean.TRUE.equals(skill.get("document_draft"))) {
            instruction += "\n另外返回 draft_sections 对象，键只能是 chief_complaint、present_illness、past_history、"
                    + "allergy_history、physical_exam、auxiliary_exam、assessment、treatment_plan、followup_plan。"
                    + "值为可供医生编辑的正文字符串；只包含有证据支持的字段，缺失信息放 gaps。"
                    + "每个字段控制在400字内，不要重复 summary 和 facts 的全部内容。不要把患者标识、签名、权限、已执行声明或来源 ID 写入正文。";
        }
        return instruction;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> load() {
        var mapper = new ObjectMapper();
        List<Map<String, Object>> rows = mapper.readValue(Objects.requireNonNull(
                MedicalAgentSkills.class.getResourceAsStream("/medical-agents/skills-v1.json")), List.class);
        Map<String, Object> catalog = mapper.readValue(Objects.requireNonNull(
                MedicalAgentSkills.class.getResourceAsStream("/medical-agents/skill-profiles-v2.json")), Map.class);
        String version = (String) catalog.get("version");
        if (!"2.0.0".equals(version)) throw new IllegalStateException("Invalid medical skill release");
        List<String> sharedRules = texts(catalog.get("shared_rules"));
        Map<String, Map<String, Object>> profiles = new LinkedHashMap<>();
        for (Map<String, Object> profile : (List<Map<String, Object>>) catalog.get("profiles")) {
            Map<String, Object> validated = new LinkedHashMap<>();
            validated.put("profile_id", Objects.requireNonNull(profile.get("id")));
            for (String field : List.of("required_inputs", "steps", "output_requirements", "escalation", "registry_skills")) {
                validated.put(field, texts(profile.get(field)));
            }
            for (String code : texts(profile.get("agents"))) {
                if (profiles.put(code, Map.copyOf(validated)) != null) {
                    throw new IllegalStateException("Duplicate medical skill profile: " + code);
                }
            }
        }
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (var row : rows) {
            String code = (String) row.get("agent_code");
            Map<String, Object> profile = Objects.requireNonNull(profiles.get(code), "Missing profile: " + code);
            Map<String, Object> enriched = new LinkedHashMap<>(row);
            enriched.putAll(profile);
            enriched.put("version", version);
            enriched.put("shared_rules", sharedRules);
            if (code == null || result.put(code, Map.copyOf(enriched)) != null) {
                throw new IllegalStateException("Duplicate or invalid medical-agent skill");
            }
        }
        if (!profiles.keySet().equals(result.keySet())) throw new IllegalStateException("Unregistered medical skill profiles");
        return Map.copyOf(result);
    }

    private static List<String> texts(Object value) {
        if (!(value instanceof List<?> items) || items.isEmpty()
                || items.stream().anyMatch(item -> !(item instanceof String text) || text.isBlank())) {
            throw new IllegalStateException("Medical skill requirements must be nonempty text lists");
        }
        return items.stream().map(String.class::cast).toList();
    }
}
