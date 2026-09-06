package org.haoemr.agent;

import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * AI 医助 Eva 的运行时编排骨架。
 *
 * <p>当前编排模型是「主医助（stage=ALL）+ 专科子医助（按 stage_code）」的组合；
 * 前端在打开对话时按路由选择主医助，harness 再按 stage_code 分派子医助。本服务把
 * 「路由 → 主医助 + 阶段」的决策集中到后端，作为后续动态编排（例如按消息意图分类、
 * 多医助计划拆解）的挂载点。现阶段提供确定性路由解析；当无路由标识时，按消息文本
 * 的领域关键词做保守兜底，不改变既有 harness 行为。</p>
 */
@Service
final class AgentOrchestrator {

    /** 编排决策结果：主医助编码 + 首选阶段。 */
    record Routing(String mainAgentCode, String stageCode) {}

    /**
     * 按路由解析默认的主医助与阶段；未命中时回落到就诊事实总协调。
     */
    Routing resolve(String routeId) {
        return route(routeId);
    }

    /**
     * 路由优先：有 routeId 时按路由解析；无 routeId 时按消息意图关键词兜底，
     * 仍无法判定时回落到就诊事实总协调。
     */
    Routing resolve(String routeId, String objective) {
        String effective = (routeId == null || routeId.isBlank()) ? intentRoute(objective) : routeId;
        return route(effective);
    }

    private static Routing route(String routeId) {
        if (routeId == null || routeId.isBlank()) {
            return new Routing("ENCOUNTER_SUMMARIZER", "ACTIVE_ENCOUNTER");
        }
        String id = routeId.toLowerCase(Locale.ROOT);
        return switch (id) {
            // 病历与质控
            case "record", "record-qc", "record-versions",
                 "quality-center", "department-qc" -> new Routing("RECORD_QC", "ACTIVE_RECORD");
            case "record-editor", "opd-record", "er-record" -> new Routing("DOCUMENT_DRAFTER", "OUTPATIENT");
            // 结果闭环
            case "opd-results", "lis-report", "pacs-viewer" ->
                    new Routing("RESULT_FOLLOWUP_COORDINATOR", "NEW_RESULT");
            // 诊疗协同
            case "opd-consult" -> new Routing("CARE_COORDINATOR", "CONSULT");
            case "opd-followup" -> new Routing("CARE_COORDINATOR", "FOLLOWUP");
            case "inpatient-course" -> new Routing("DOCUMENT_DRAFTER", "FIRST_COURSE");
            case "inpatient" -> new Routing("ENCOUNTER_SUMMARIZER", "INPATIENT_DAILY");
            case "emergency", "er-triage" -> new Routing("ENCOUNTER_SUMMARIZER", "TRIAGE");
            // 院感监测
            case "infection-events" -> new Routing("INFECTION_SURVEILLANCE", "INFECTION_CASE");
            // 医保合规
            case "billing" -> new Routing("INSURANCE_COMPLIANCE", "CHARGE");
            // 医技预约调度
            case "lab-workbench" -> new Routing("MEDICAL_TECH_SCHEDULING", "EXAM_SLOT");
            case "imaging-workbench" -> new Routing("MEDICAL_TECH_SCHEDULING", "EQUIPMENT");
            // 科研随访
            case "research", "cohort-builder" -> new Routing("RESEARCH_FOLLOWUP", "COHORT");
            // 患者宣教
            case "inpatient-discharge" -> new Routing("PATIENT_EDUCATION", "MEDICATION_GUIDE");
            // 专科：血糖治理（内分泌）
            case "glycemic" -> new Routing("GLYCEMIC_MANAGEMENT", "ADMISSION_GLUCOSE");
            // 专科：心血管诊疗
            case "cardiovascular" -> new Routing("CARDIOVASCULAR_CARE", "CHEST_PAIN");
            // 专科：中医辨证审方
            case "tcm" -> new Routing("TCM_SYNDROME_REVIEW", "SYNDROME");
            // 专科：重症风险研判
            case "icu-risk" -> new Routing("ICU_RISK_ASSESSMENT", "SEPSIS_RISK");
            // 默认：就诊事实总协调
            default -> new Routing("ENCOUNTER_SUMMARIZER", "ACTIVE_ENCOUNTER");
        };
    }

    /**
     * 消息意图的保守关键词兜底：仅在无 routeId 时启用，用于把自由文本任务
     * 引导到领域主医助。领域词命中优先级由高到低，先命中的领域生效。
     */
    private static String intentRoute(String objective) {
        if (objective == null || objective.isBlank()) {
            return null;
        }
        String text = objective.toLowerCase(Locale.ROOT);
        if (containsAny(text, "带药", "用药说明", "健康宣教", "复诊提醒", "宣教", "漏服")) {
            return "inpatient-discharge";
        }
        if (containsAny(text, "院感", "感染", "暴发", "聚集", "传染病", "直报")) {
            return "infection-events";
        }
        if (containsAny(text, "医保", "drg", "dip", "编码", "收费", "计费", "报销", "费用")) {
            return "billing";
        }
        if (containsAny(text, "预约", "号源", "排程", "设备占用", "医技")) {
            return "lab-workbench";
        }
        if (containsAny(text, "科研", "队列", "入组", "结局", "随访数据")) {
            return "research";
        }
        if (containsAny(text, "血糖", "胰岛素", "低血糖", "降糖", "糖化")) {
            return "glycemic";
        }
        if (containsAny(text, "胸痛", "心衰", "房颤", "抗凝", "心血管")) {
            return "cardiovascular";
        }
        if (containsAny(text, "辨证", "中药", "方药", "煎服", "配伍", "舌脉", "四诊", "十八反")) {
            return "tcm";
        }
        if (containsAny(text, "脓毒症", "恶化", "重症", "危重", "icu")) {
            return "icu-risk";
        }
        if (containsAny(text, "质控", "病历质量", "前后矛盾", "缺项", "归档", "签署前", "整改")) {
            return "record-qc";
        }
        if (containsAny(text, "会诊", "转科", "交接", "多学科", "mdt")) {
            return "opd-consult";
        }
        if (containsAny(text, "危急值", "检验结果", "检查结果", "结果闭环", "复查")) {
            return "opd-results";
        }
        if (containsAny(text, "出院小结", "入院记录", "病程", "查房", "病历", "文书", "起草", "草稿")) {
            return "record-editor";
        }
        if (containsAny(text, "分诊", "急诊", "生命体征", "抢救")) {
            return "emergency";
        }
        return null;
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
