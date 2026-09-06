package org.haoemr.development;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Deterministic, non-patient tertiary-hospital configuration fixtures for dev-synthetic. */
final class TertiaryBusinessConfigurationCatalog {

    private static final List<String> PROTECTED_MODULES = List.of(
            "CORE_PATIENT", "CLINICAL_RECORD", "DIGITAL_SIGNATURE", "AUDIT_OUTBOX");

    private TertiaryBusinessConfigurationCatalog() { }

    static List<ConfigurationSeed> configurations() {
        return List.of(
                new ConfigurationSeed(id("c101"), id("c321"), "WORKFLOW",
                        "syn-workflow-closed-loop-v1", "runtime-workflow-consult-v1",
                        "三级医院门急住一体化闭环流程", workflowPayload()),
                configuration("WORKFLOW", "workflow-emergency-green-channel-v1",
                        "急诊胸痛卒中创伤绿色通道",
                        workflowProfilePayload("emergency-green-channel", "覆盖急诊预检、专病绿色通道、抢救、检查、专科接诊和转运交接。",
                                List.of(
                                        step("triage", "预检分诊与生命体征采集", "急诊分诊护士", 5),
                                        step("activation", "绿色通道判定与启动", "急诊主班医师", 5),
                                        step("resuscitation", "抢救与专病量表评估", "急诊抢救团队", 10),
                                        step("diagnostics", "床旁检查与危急结果审核", "急诊医技联合组", 30),
                                        step("specialist", "胸痛卒中创伤专科决策", "专病中心值班组", 30),
                                        step("transfer", "介入手术入院转运交接", "急诊与接收科室", 20)),
                                "I 级立即处置；II 级 10 分钟；胸痛首份心电图 10 分钟；卒中影像 25 分钟逐级升级",
                                List.of("STEMI 直接 PCI", "缺血性卒中溶栓评估", "多发伤一体化救治"))),
                configuration("WORKFLOW", "workflow-inpatient-care-transfer-v1",
                        "住院诊疗、转科与床旁交接流程",
                        workflowProfilePayload("inpatient-care-transfer", "覆盖入院评估、风险筛查、医嘱执行、护理、会诊、转科和出院准备。",
                                List.of(
                                        step("admission", "入院接诊与首次评估", "住院经治医生", 60),
                                        step("risk", "VTE 跌倒压疮营养风险筛查", "责任护士与经治医生", 240),
                                        step("plan", "诊疗计划与医嘱审核", "医疗组与临床药师", 120),
                                        step("execution", "治疗护理与结果跟踪", "医护药技团队", 480),
                                        step("consult", "跨科会诊与疑难讨论", "会诊专家组", 120),
                                        step("transfer", "转科转床与 SBAR 交接", "转出转入医护团队", 30),
                                        step("discharge-readiness", "出院准备与连续照护评估", "医疗组与责任护士", 240)),
                                "首次病程 8 小时；急会诊 30 分钟；普通会诊 24 小时；转科交接 30 分钟",
                                List.of("高龄多病共存入院", "ICU 转普通病房", "跨院区转科"))),
                configuration("WORKFLOW", "workflow-perioperative-safety-v1",
                        "围手术期安全与麻醉闭环流程",
                        workflowProfilePayload("perioperative-safety", "覆盖手术指征、术前讨论、麻醉评估、三方核查、术中记录、复苏和术后交接。",
                                List.of(
                                        step("indication", "手术指征与分级确认", "手术医师与上级医师", 240),
                                        step("discussion", "术前讨论与知情同意", "手术团队", 480),
                                        step("anesthesia", "麻醉评估与风险分层", "麻醉科医师", 240),
                                        step("check", "患者部位术式三方核查", "手术医师麻醉医师巡回护士", 10),
                                        step("operation", "手术麻醉与植入物记录", "手术麻醉护理团队", 360),
                                        step("pacu", "复苏评估与离室判定", "麻醉复苏团队", 120),
                                        step("postop", "术后医嘱与病区交接", "手术医师与责任护士", 30)),
                                "术前核查未通过禁止开台；手术记录 24 小时；麻醉记录离室前完成",
                                List.of("急诊剖腹探查", "择期关节置换", "日间手术异常留观"))),
                configuration("WORKFLOW", "workflow-critical-result-loop-v1",
                        "检验影像危急值闭环处置流程",
                        workflowProfilePayload("critical-result-loop", "覆盖结果审核、危急值识别、双通道通知、临床确认、处置、复查和质控复盘。",
                                List.of(
                                        step("verification", "结果复核与危急值识别", "检验影像审核人员", 5),
                                        step("notification", "双通道通知责任团队", "医技值班人员", 5),
                                        step("acknowledge", "临床接收确认", "经治医生或值班医生", 5),
                                        step("action", "临床评估与处置记录", "经治医疗组", 10),
                                        step("repeat", "复查申请与趋势确认", "经治医生与医技科室", 60),
                                        step("closure", "患者通知与闭环确认", "经治医生与责任护士", 30)),
                                "危急值发布后 10 分钟内确认；未确认 5/10/15 分钟升级到科主任和医务处",
                                List.of("高钾危急值", "影像主动脉夹层", "血培养阳性"))),
                configuration("WORKFLOW", "workflow-mdt-consult-v1",
                        "疑难病例会诊与 MDT 决策流程",
                        workflowProfilePayload("mdt-consult", "覆盖申请准入、资料质控、专家邀请、讨论、意见签署、执行反馈和复评。",
                                List.of(
                                        step("request", "会诊申请与问题定义", "申请科室经治医生", 60),
                                        step("materials", "病历影像病理资料质控", "MDT 秘书", 240),
                                        step("triage", "普通急会诊分级", "医务处会诊中心", 30),
                                        step("experts", "专家邀请与冲突校验", "MDT 召集人", 120),
                                        step("meeting", "多学科讨论与异议记录", "MDT 专家组", 120),
                                        step("decision", "综合意见签署", "MDT 召集人与申请医师", 60),
                                        step("feedback", "执行反馈与必要时复评", "经治医疗组", 1440)),
                                "急会诊 30 分钟到场；普通会诊 24 小时；MDT 意见会后 2 小时签署",
                                List.of("肿瘤 MDT", "疑难感染会诊", "复杂危重孕产妇联合会诊"))),
                configuration("WORKFLOW", "workflow-discharge-followup-v1",
                        "出院准备、未回结果与随访闭环",
                        workflowProfilePayload("discharge-followup", "覆盖出院标准、用药重整、未回结果、出院记录、健康教育、预约和异常回访。",
                                List.of(
                                        step("readiness", "出院标准与风险评估", "经治医生与责任护士", 240),
                                        step("medication", "出院用药重整与药学教育", "经治医生与临床药师", 120),
                                        step("pending", "未回重要结果责任绑定", "经治医疗组", 60),
                                        step("summary", "出院记录与诊断编码", "经治医生与上级医师", 1440),
                                        step("education", "患者教育与红旗症状确认", "责任护士", 60),
                                        step("appointment", "复诊预约与随访任务创建", "随访中心", 120),
                                        step("followup", "结果通知与异常回访", "经治团队与随访中心", 4320)),
                                "出院记录 24 小时；重要未回结果回报后 30 分钟通知；高风险患者 72 小时回访",
                                List.of("抗凝患者出院", "病理结果出院后回报", "心衰高风险随访"))),
                configuration("WORKFLOW", "workflow-record-quality-archive-v1",
                        "病案质控、编码与封存归档流程",
                        workflowProfilePayload("record-quality-archive", "覆盖完整性校验、首页编码、缺陷退回、医师修订、终末质控、电子签名和封存。",
                                List.of(
                                        step("completeness", "病历完整性自动校验", "病案质控系统", 30),
                                        step("coding", "首页诊断手术编码", "病案编码员", 480),
                                        step("defect", "缺陷分级与责任派发", "病案质控员", 120),
                                        step("revision", "临床缺陷修订与说明", "责任医师", 1440),
                                        step("review", "终末质控与 DRG 校验", "病案室与质控办", 480),
                                        step("archive", "签名验真与病案封存", "病案管理员", 120)),
                                "出院病历 3 个工作日归档；甲级病案率月度监测；封存后更正走留痕流程",
                                List.of("主要诊断选择复核", "手术编码冲突", "超时未归档升级"))),
                new ConfigurationSeed(id("c102"), id("c322"), "FORM_TEMPLATE",
                        "syn-medical-record-v1", "runtime-form-record-v1",
                        "三级医院结构化病历模板集", formPayload()),
                configuration("FORM_TEMPLATE", "form-outpatient-record-v1", "门诊结构化病历模板",
                        formProfilePayload("outpatient-record", "覆盖门诊主诉、现病史、过敏、诊断、处置和复诊计划。",
                                List.of(
                                        field("chief_complaint", "主诉", "TEXTAREA", "content", true, false, "SNOMED-CT", null),
                                        field("present_illness", "现病史", "TEXTAREA", "content", true, false, "SNOMED-CT", null),
                                        field("allergies", "过敏史", "CODE", "safety", true, false, "SNOMED-CT", null),
                                        field("primary_diagnosis", "主要诊断", "CODE", "content", true, true, "ICD-10-CN", null),
                                        field("treatment_plan", "诊疗计划", "TEXTAREA", "content", true, false, null, null),
                                        field("followup_plan", "复诊与随访计划", "TEXTAREA", "safety", false, false, null, null)),
                                "A4-门诊结构化病历-v4", List.of("普通门诊", "专家门诊", "专病门诊"))),
                configuration("FORM_TEMPLATE", "form-emergency-resuscitation-v1", "急诊抢救与留观记录模板",
                        formProfilePayload("emergency-resuscitation", "覆盖分诊级别、生命体征、抢救措施、用药、检查、去向和交接。",
                                List.of(
                                        field("triage_level", "分诊级别", "CODE", "safety", true, true, "EMERGENCY-TRIAGE", null),
                                        field("symptom_onset", "症状起始时间", "DATETIME", "content", true, false, null, null),
                                        field("vital_signs", "首组生命体征", "TEXTAREA", "safety", true, true, "LOINC", null),
                                        field("resuscitation_actions", "抢救措施与时间轴", "TEXTAREA", "content", true, false, null, null),
                                        field("emergency_medications", "急救用药记录", "TEXTAREA", "content", true, false, "ATC", null),
                                        field("emergency_diagnosis", "急诊诊断", "CODE", "content", true, true, "ICD-10-CN", null),
                                        field("disposition", "急诊去向", "CODE", "safety", true, true, "ENCOUNTER-DISPOSITION", null)),
                                "A4-急诊抢救留观记录-v3", List.of("急诊抢救", "急诊留观", "院前院内交接"))),
                configuration("FORM_TEMPLATE", "form-inpatient-first-course-v1", "住院首次病程与入院评估模板",
                        formProfilePayload("inpatient-first-course", "覆盖入院原因、病史、查体、风险评估、诊断依据和诊疗计划。",
                                List.of(
                                        field("admission_reason", "入院原因", "TEXTAREA", "content", true, false, null, null),
                                        field("history_summary", "病史摘要", "TEXTAREA", "content", true, false, "SNOMED-CT", null),
                                        field("physical_exam", "专科体格检查", "TEXTAREA", "content", true, false, null, null),
                                        field("vte_risk", "VTE 风险评分", "CALCULATED", "safety", true, true, null, "VTE_SCORE(age,diagnosis,mobility,surgery)"),
                                        field("nutrition_risk", "营养风险评分", "NUMBER", "safety", true, false, null, null),
                                        field("admission_diagnosis", "入院诊断", "CODE", "content", true, true, "ICD-10-CN", null),
                                        field("diagnostic_basis", "诊断依据与鉴别", "TEXTAREA", "content", true, false, null, null),
                                        field("care_plan", "诊疗计划", "TEXTAREA", "safety", true, false, null, null)),
                                "A4-住院首次病程-v5", List.of("入院记录", "首次病程", "阶段小结"))),
                configuration("FORM_TEMPLATE", "form-perioperative-anesthesia-v1", "手术麻醉与安全核查模板",
                        formProfilePayload("perioperative-anesthesia", "覆盖术式部位、知情同意、ASA、麻醉计划、三方核查、植入物和术后去向。",
                                List.of(
                                        field("procedure_name", "拟行手术", "CODE", "content", true, true, "ICD-9-CM-3", null),
                                        field("surgical_site", "手术部位与侧别", "TEXT", "safety", true, true, null, null),
                                        field("consent_status", "知情同意状态", "CODE", "safety", true, true, "CONSENT-STATUS", null),
                                        field("asa_class", "ASA 分级", "CODE", "safety", true, true, "ASA-CLASS", null),
                                        field("anesthesia_plan", "麻醉计划", "TEXTAREA", "content", true, false, null, null),
                                        field("time_out", "三方核查记录", "TEXTAREA", "safety", true, true, null, null),
                                        field("implant_trace", "植入物追溯信息", "TEXTAREA", "content", false, true, "UDI", null),
                                        field("postop_destination", "术后去向", "CODE", "safety", true, false, "ENCOUNTER-DISPOSITION", null)),
                                "A4-围手术期安全核查-v4", List.of("术前评估", "麻醉记录", "手术记录", "PACU 记录"))),
                configuration("FORM_TEMPLATE", "form-nursing-assessment-handoff-v1", "护理入院评估与交接班模板",
                        formProfilePayload("nursing-assessment-handoff", "覆盖自理能力、跌倒压疮、管路、用药、疼痛和 SBAR 交接。",
                                List.of(
                                        field("self_care", "自理能力评分", "NUMBER", "content", true, false, null, null),
                                        field("fall_risk", "跌倒风险评分", "CALCULATED", "safety", true, true, null, "MORSE_SCORE(history,mobility,medication)"),
                                        field("pressure_injury", "压疮风险评分", "CALCULATED", "safety", true, true, null, "BRADEN_SCORE(sensation,moisture,mobility,nutrition)"),
                                        field("pain_score", "疼痛评分", "NUMBER", "content", true, false, null, null),
                                        field("lines_tubes", "管路与导管", "TEXTAREA", "safety", false, false, "SNOMED-CT", null),
                                        field("medication_handoff", "重点用药交接", "TEXTAREA", "safety", false, false, "ATC", null),
                                        field("sbar_handoff", "SBAR 交接摘要", "TEXTAREA", "content", true, false, null, null)),
                                "A4-护理评估交接-v4", List.of("入院护理评估", "转科交接", "床旁交接班"))),
                configuration("FORM_TEMPLATE", "form-mdt-consult-v1", "会诊与 MDT 讨论记录模板",
                        formProfilePayload("mdt-consult", "覆盖会诊目的、材料、专科意见、异议、共识和执行责任。",
                                List.of(
                                        field("consult_question", "会诊目的与问题", "TEXTAREA", "content", true, false, null, null),
                                        field("diagnosis_summary", "诊断与分期摘要", "TEXTAREA", "content", true, false, "ICD-10-CN", null),
                                        field("evidence_summary", "影像病理检验证据", "TEXTAREA", "content", true, false, "LOINC", null),
                                        field("specialist_opinions", "各专科意见", "TEXTAREA", "content", true, false, null, null),
                                        field("dissent", "异议与保留意见", "TEXTAREA", "safety", false, true, null, null),
                                        field("consensus", "综合会诊意见", "TEXTAREA", "content", true, true, null, null),
                                        field("owner_and_due", "执行责任人与时限", "TEXT", "safety", true, true, null, null)),
                                "A4-会诊MDT记录-v3", List.of("院内会诊", "院际会诊", "肿瘤 MDT", "疑难病例讨论"))),
                configuration("FORM_TEMPLATE", "form-discharge-followup-v1", "出院记录与连续照护模板",
                        formProfilePayload("discharge-followup", "覆盖出院诊断、手术操作、用药重整、未回结果、患者教育和随访计划。",
                                List.of(
                                        field("discharge_diagnoses", "出院诊断", "CODE", "content", true, true, "ICD-10-CN", null),
                                        field("procedures", "手术与操作", "CODE", "content", false, true, "ICD-9-CM-3", null),
                                        field("hospital_course", "住院经过", "TEXTAREA", "content", true, false, null, null),
                                        field("medication_reconciliation", "出院用药重整", "TEXTAREA", "safety", true, true, "ATC", null),
                                        field("pending_results", "未回重要结果", "TEXTAREA", "safety", false, true, "LOINC", null),
                                        field("patient_education", "健康教育与红旗症状", "TEXTAREA", "content", true, false, null, null),
                                        field("followup_schedule", "复诊与随访安排", "TEXTAREA", "safety", true, false, null, null)),
                                "A4-出院记录连续照护-v4", List.of("出院记录", "出院小结", "患者指导", "随访计划"))),
                new ConfigurationSeed(id("c103"), id("c323"), "RULE",
                        "syn-clinical-safety-v1", "runtime-rule-safety-v1",
                        "三级医院临床安全与时限规则集", rulePayload()),
                configuration("RULE", "rule-medication-safety-v1", "处方审核与用药安全规则集",
                        ruleProfilePayload("medication-safety", "覆盖过敏、重复用药、肾功能、儿科剂量和高警示药双核对。",
                                List.of(
                                        rule("med-allergy", "严重过敏处方阻断", "PLATFORM_HARD", 1200, "过敏反应严重且药物成分命中", "阻断处方并要求选择替代药", "国家药品不良反应监测规范"),
                                        rule("med-duplicate", "同成分重复用药阻断", "PLATFORM_HARD", 1150, "活动医嘱存在同成分或治疗重复", "阻断新增医嘱并展示冲突", "处方管理办法"),
                                        rule("med-renal", "肾功能剂量校验", "INSTITUTION_HARD", 1050, "eGFR 低于药品剂量阈值", "阻断超剂量并要求药师复核", "院内肾功能剂量规范"),
                                        rule("med-pediatric", "儿科体重剂量校验", "INSTITUTION_HARD", 1000, "年龄小于 14 岁且已记录体重", "超出 mg/kg 范围时阻断", "院内儿科用药目录"),
                                        rule("med-high-alert", "高警示药双人核对", "INSTITUTION_HARD", 950, "命中高警示药品目录", "要求医师药师或双护士复核", "院内高警示药品管理制度")),
                                "严重青霉素过敏且肾功能不全患者开具抗菌药物")),
                configuration("RULE", "rule-emergency-critical-care-v1", "急诊抢救与脓毒症规则集",
                        ruleProfilePayload("emergency-critical-care", "覆盖急诊分级、胸痛卒中时限、脓毒症筛查和抢救任务升级。",
                                List.of(
                                        rule("ed-triage", "急诊 I/II 级立即处置", "INSTITUTION_HARD", 1100, "分诊等级为 I 或 II 级", "立即创建抢救任务并通知急诊主班", "急诊预检分诊制度"),
                                        rule("ed-ecg", "胸痛十分钟心电图", "INSTITUTION_HARD", 1000, "胸痛或疑似急性冠脉综合征", "10 分钟内完成首份心电图", "胸痛中心救治流程"),
                                        rule("ed-stroke", "卒中影像时限升级", "INSTITUTION_HARD", 950, "卒中筛查阳性且发病时间窗内", "启动卒中通道并在 25 分钟内完成影像", "卒中中心救治流程"),
                                        rule("ed-sepsis", "脓毒症早期筛查", "INSTITUTION_HARD", 900, "qSOFA 或 NEWS2 达到阈值", "启动脓毒症路径和乳酸复查", "脓毒症院内路径"),
                                        rule("ed-timeout", "抢救任务超时升级", "REMINDER", 600, "关键抢救任务接近时限", "提醒主班医师并升级科主任", "急诊抢救质量管理制度")),
                                "高热低血压患者进入急诊抢救区")),
                configuration("RULE", "rule-inpatient-quality-v1", "住院风险与核心制度规则集",
                        ruleProfilePayload("inpatient-quality", "覆盖首次病程、三级查房、VTE、跌倒压疮和会诊时限。",
                                List.of(
                                        rule("ip-first-course", "首次病程时限", "INSTITUTION_HARD", 1050, "患者入院且首次病程未完成", "8 小时临近时限时逐级升级", "病历书写基本规范"),
                                        rule("ip-vte", "住院 VTE 风险评估", "INSTITUTION_HARD", 1000, "入院或术后 24 小时未完成 VTE 评估", "阻断病程提交并创建评估任务", "国家医疗质量安全改进目标"),
                                        rule("ip-fall", "高跌倒风险护理措施", "INSTITUTION_HARD", 900, "跌倒风险评分达到高危", "要求防跌倒措施和患者宣教记录", "护理安全管理制度"),
                                        rule("ip-round", "三级查房完整性", "REMINDER", 650, "住院患者缺少规定层级查房记录", "提醒医疗组并纳入科室质控", "医疗质量安全核心制度"),
                                        rule("ip-consult", "会诊超时升级", "REMINDER", 600, "急会诊 30 分钟或普通会诊 24 小时未响应", "升级科主任和医务处", "会诊制度")),
                                "高龄骨折患者入院并计划手术")),
                configuration("RULE", "rule-perioperative-transfusion-v1", "围手术期与输血安全规则集",
                        ruleProfilePayload("perioperative-transfusion", "覆盖手术分级、术前核查、抗菌药时点、植入物和输血双核对。",
                                List.of(
                                        rule("op-grade", "手术分级授权校验", "PLATFORM_HARD", 1200, "术式等级超过术者授权", "阻断排台并要求重新授权", "手术分级管理制度"),
                                        rule("op-timeout", "三方安全核查", "INSTITUTION_HARD", 1100, "患者进入手术间且核查未完成", "禁止开始手术和麻醉关键步骤", "手术安全核查制度"),
                                        rule("op-antibiotic", "预防用抗菌药时点", "INSTITUTION_HARD", 950, "需要预防用药且切皮前未给药", "提醒麻醉与手术团队并记录原因", "围手术期抗菌药物管理规范"),
                                        rule("op-implant", "植入物 UDI 追溯", "INSTITUTION_HARD", 900, "使用植入物且 UDI 未绑定", "阻断手术记录终签", "医疗器械唯一标识管理制度"),
                                        rule("op-transfusion", "输血床旁双核对", "PLATFORM_HARD", 1150, "血液制品准备执行", "要求患者血袋医嘱三方匹配并双签", "临床输血技术规范")),
                                "关节置换患者术中备血并使用植入物")),
                configuration("RULE", "rule-antimicrobial-stewardship-v1", "抗菌药物分级与复评规则集",
                        ruleProfilePayload("antimicrobial-stewardship", "覆盖分级授权、特殊级审批、培养送检、72 小时复评和降阶梯。",
                                List.of(
                                        rule("abx-authority", "抗菌药物分级授权", "PLATFORM_HARD", 1150, "处方药品等级超过医师权限", "阻断处方并要求有权限医师开具", "抗菌药物临床应用管理办法"),
                                        rule("abx-special", "特殊使用级会诊审批", "INSTITUTION_HARD", 1050, "开具特殊使用级抗菌药物", "要求感染或药学专家会诊审批", "院内抗菌药物分级目录"),
                                        rule("abx-culture", "治疗前微生物送检", "REMINDER", 700, "重症感染首次经验治疗", "提醒用药前完成合格标本送检", "抗菌药物临床应用指导原则"),
                                        rule("abx-review", "抗菌药物 72 小时复评", "REMINDER", 650, "抗菌药物使用达到 72 小时", "结合培养和疗效完成复评与降阶梯", "抗菌药物专项管理制度")),
                                "重症肺炎经验性联合抗菌治疗")),
                configuration("RULE", "rule-critical-result-v1", "危急值与重要结果规则集",
                        ruleProfilePayload("critical-result", "覆盖危急值识别、通知确认、处置记录、复查和出院未回结果追踪。",
                                List.of(
                                        rule("result-threshold", "危急值阈值识别", "PLATFORM_HARD", 1200, "最终结果命中院级危急值阈值", "创建不可静默关闭的危急值事件", "危急值报告制度"),
                                        rule("result-notify", "危急值双通道通知", "INSTITUTION_HARD", 1100, "危急值事件生成", "同时通知经治医生和病区值班人员", "危急值闭环管理制度"),
                                        rule("result-ack", "十分钟确认升级", "INSTITUTION_HARD", 1000, "危急值 10 分钟未确认", "升级科主任医务处并保留通知证据", "危急值报告制度"),
                                        rule("result-action", "处置和复查完整性", "INSTITUTION_HARD", 900, "危急值已确认但无处置或复查", "阻断事件关闭并要求补充记录", "检查检验结果闭环制度"),
                                        rule("result-pending", "出院未回重要结果", "REMINDER", 650, "出院时存在未回重要结果", "绑定责任人并在回报后通知患者", "患者安全目标")),
                                "出院患者病理结果回报恶性诊断")),
                configuration("RULE", "rule-record-timeliness-v1", "病历时限与终末质控规则集",
                        ruleProfilePayload("record-timeliness", "覆盖病历完成时限、签名、诊断编码、缺陷整改和封存更正。",
                                List.of(
                                        rule("record-required", "必填病历完整性", "PLATFORM_HARD", 1100, "病历终签前存在必填项缺失", "阻断签署并定位缺失字段", "病历书写基本规范"),
                                        rule("record-signature", "签名与责任层级", "INSTITUTION_HARD", 1000, "签名人权限或上级审核不满足", "阻断终签并要求正确责任人签署", "电子病历应用管理规范"),
                                        rule("record-diagnosis", "主要诊断与手术编码一致性", "INSTITUTION_HARD", 900, "首页编码与病程手术记录冲突", "创建编码复核任务", "病案首页数据质量规范"),
                                        rule("record-timeout", "出院病历归档时限", "REMINDER", 650, "出院后接近归档时限", "提醒责任医师科主任和病案室", "病历管理制度"),
                                        rule("record-amend", "封存病历更正留痕", "PLATFORM_HARD", 1050, "封存后申请更正", "只允许追加更正记录并保留原文", "电子病历档案管理制度")),
                                "出院病案主要诊断与手术记录存在冲突")),
                new ConfigurationSeed(id("c104"), id("c324"), "SCOPE",
                        "syn-role-scope-v1", "runtime-scope-clinical-v1",
                        "三级医院角色职责与数据范围", scopePayload()),
                configuration("SCOPE", "scope-outpatient-clinician-v1", "门诊诊疗职责与患者关系范围",
                        scopeProfilePayload("outpatient-clinician", "按接诊关系、门诊科室、签署状态和复诊任务限定门诊访问。",
                                List.of(
                                        permission("门诊经治医生", "门诊病历草稿", "读写", "本人当前接诊患者", "ALLOW", 0, "终签后更正需上级复核"),
                                        permission("门诊上级医师", "门诊病历", "审核签署", "本科室待审核病历", "ALLOW", 0, "不能审核本人起草记录"),
                                        permission("门诊护士", "分诊与护理记录", "读写", "当班诊区患者", "ALLOW", 0, "无诊断和处方修改权"),
                                        permission("非接诊医生", "门诊病历全文", "读取", "无接诊关系患者", "DENY", 0, "需发起会诊或临时授权"),
                                        permission("门诊医生", "批量病历导出", "导出", "全部门诊患者", "DENY", 0, "保护性拒绝优先")))),
                configuration("SCOPE", "scope-emergency-break-glass-v1", "急诊抢救与破窗授权范围",
                        scopeProfilePayload("emergency-break-glass", "按急诊在诊、抢救团队和短时破窗授权开放最小必要病历。",
                                List.of(
                                        permission("急诊经治医生", "急诊病历与医嘱", "读写", "当前急诊责任患者", "ALLOW", 0, "抢救结束后自动收窄"),
                                        permission("急诊抢救护士", "急救执行与护理记录", "读写", "当前抢救区患者", "ALLOW", 0, "医嘱核对与执行人分离"),
                                        permission("急救破窗人员", "急救最小病历", "只读", "破窗授权患者", "ALLOW", 2, "强制理由通知和 24 小时复核"),
                                        permission("急诊会诊医生", "急诊病历全文", "只读", "会诊授权患者", "ALLOW", 4, "禁止导出和转授权"),
                                        permission("破窗人员", "科研与批量数据", "读取", "全部患者", "DENY", 0, "破窗仅限直接救治")))),
                configuration("SCOPE", "scope-inpatient-care-team-v1", "住院医疗组、病区与转科范围",
                        scopeProfilePayload("inpatient-care-team", "按医疗组、病区、值班班次、会诊和转科交接计算住院权限。",
                                List.of(
                                        permission("住院经治医生", "住院病历与医嘱", "读写", "本人医疗组患者", "ALLOW", 0, "上级医师完成最终审核"),
                                        permission("医疗组上级医师", "住院病历", "审核签署", "本医疗组患者", "ALLOW", 0, "作者与审核人分离"),
                                        permission("病区值班医生", "住院病历与急救医嘱", "读写", "当班病区患者", "ALLOW", 12, "交班后自动失效"),
                                        permission("会诊医生", "住院病历全文", "只读", "会诊授权患者", "ALLOW", 8, "只能形成会诊意见"),
                                        permission("转出科室人员", "转科后病历", "写入", "已完成转科患者", "DENY", 0, "仅允许补签既有记录")))),
                configuration("SCOPE", "scope-nursing-ward-v1", "护理病区与医嘱执行职责范围",
                        scopeProfilePayload("nursing-ward", "按病区、责任床位、班次和护理审核关系限定护理记录与医嘱执行。",
                                List.of(
                                        permission("责任护士", "护理记录与医嘱执行", "读写", "本人责任床位患者", "ALLOW", 0, "执行需关联医嘱和患者核对"),
                                        permission("当班护士", "护理任务队列", "执行", "当班病区患者", "ALLOW", 12, "班次结束自动失效"),
                                        permission("护士长", "护理记录", "审核", "本病区患者", "ALLOW", 0, "作者与审核人分离"),
                                        permission("护理部质控员", "护理质量数据", "审核", "授权抽检病区", "ALLOW", 8, "只能创建质控问题"),
                                        permission("护理人员", "医师诊断与处方", "修改", "本病区患者", "DENY", 0, "职责分离")))),
                configuration("SCOPE", "scope-pharmacy-medtech-v1", "药学与医技任务队列职责范围",
                        scopeProfilePayload("pharmacy-medtech", "按处方审核、标本、检查申请、报告审核和危急值任务队列限定访问。",
                                List.of(
                                        permission("临床药师", "处方与用药相关病历", "审核", "药学服务患者", "ALLOW", 8, "不得修改诊断和医生原始医嘱"),
                                        permission("调剂药师", "已审核处方", "调剂", "本人药房任务队列", "ALLOW", 0, "审核与调剂按高风险规则分离"),
                                        permission("检验技师", "标本与检验结果草稿", "读写", "本科室任务队列", "ALLOW", 0, "采集人与审核人分离"),
                                        permission("影像医师", "影像检查与报告", "审核", "本科室任务队列", "ALLOW", 0, "报告审核后更正需留痕"),
                                        permission("医技人员", "非本科任务和病历全文", "读取", "全部患者", "DENY", 0, "最小必要原则")))),
                configuration("SCOPE", "scope-record-quality-v1", "病案、编码与医疗质量职责范围",
                        scopeProfilePayload("record-quality", "按出院状态、抽检任务、编码责任和封存状态限定病案质控访问。",
                                List.of(
                                        permission("病案编码员", "病案首页与编码", "编辑", "本人编码任务队列", "ALLOW", 0, "不得修改临床正文"),
                                        permission("病案管理员", "已出院病案", "归档封存", "病案室待归档队列", "ALLOW", 0, "封存后仅允许追加更正"),
                                        permission("科室质控医师", "病历与缺陷", "审核", "本科室抽检病历", "ALLOW", 8, "只能创建和关闭质控问题"),
                                        permission("院级质控专家", "病历与质量指标", "审核", "授权专项抽检范围", "ALLOW", 8, "访问全量留痕"),
                                        permission("病案人员", "在诊患者医嘱", "修改", "全部患者", "DENY", 0, "病案职责与临床职责分离")))),
                configuration("SCOPE", "scope-research-data-v1", "科研数据、伦理与脱敏访问范围",
                        scopeProfilePayload("research-data", "按伦理批件、数据集、用途、有效期和脱敏级别限定科研访问。",
                                List.of(
                                        permission("课题负责人", "脱敏科研数据集", "申请读取", "伦理批准课题队列", "ALLOW", 24, "用途和有效期必须匹配"),
                                        permission("科研数据管理员", "科研数据集", "生成脱敏", "已批准数据申请", "ALLOW", 8, "生成与审批人分离"),
                                        permission("伦理审查人员", "数据申请与方案", "审核", "本人审查任务", "ALLOW", 8, "不得直接提取患者级数据"),
                                        permission("研究成员", "脱敏数据集", "只读", "本人课题授权数据集", "ALLOW", 24, "禁止反识别和转授权"),
                                        permission("科研人员", "可识别临床病历", "批量导出", "全部患者", "DENY", 0, "必须走特批与独立审计")))));
    }

    static List<CapabilityPackSeed> capabilityPacks() {
        String core = "SYN-CORE-CLINICAL";
        String tertiary = "SYN-TERTIARY-HOSPITAL";
        return List.of(
                pack("c301", "d601", "c701", core, "临床核心能力包", null,
                        "全院公共内核", List.of("CORE_PATIENT", "CLINICAL_RECORD", "ORDER_RESULT", "DIGITAL_SIGNATURE", "AUDIT_OUTBOX")),
                pack("c302", "d602", "c702", tertiary, "三级医院综合能力包", core,
                        "全院门急住医技", List.of("CORE_PATIENT", "CLINICAL_RECORD", "ORDER_RESULT", "DIGITAL_SIGNATURE", "AUDIT_OUTBOX", "OUTPATIENT", "EMERGENCY", "INPATIENT", "NURSING", "MEDICAL_TECH", "QUALITY_RESEARCH")),
                pack("c331", "d603", "c703", "SYN-OUTPATIENT", "门诊诊疗能力包", tertiary,
                        "门诊部", baseline("OUTPATIENT")),
                pack("c332", "d604", "c704", "SYN-EMERGENCY", "急诊急救能力包", tertiary,
                        "急诊医学科", baseline("EMERGENCY", "CRITICAL_CARE")),
                pack("c333", "d605", "c705", "SYN-INPATIENT", "住院诊疗能力包", tertiary,
                        "住院部", baseline("INPATIENT", "NURSING")),
                pack("c334", "c606", "c706", "SYN-NURSING", "临床护理能力包", tertiary,
                        "护理部", baseline("NURSING")),
                pack("c335", "c607", "c707", "SYN-SURGERY", "围术期管理能力包", tertiary,
                        "手术部", baseline("SURGERY", "ANESTHESIA", "CRITICAL_CARE")),
                pack("c336", "c608", "c708", "SYN-CRITICAL-CARE", "重症医学能力包", tertiary,
                        "重症医学科", baseline("CRITICAL_CARE", "NURSING")),
                pack("c337", "c609", "c709", "SYN-MEDICAL-TECH", "医技协同能力包", tertiary,
                        "医技科室", baseline("MEDICAL_TECH")),
                pack("c338", "c60a", "c70a", "SYN-QUALITY-RESEARCH", "质控科研能力包", tertiary,
                        "医务处与科研处", baseline("QUALITY_RESEARCH")),
                pack("c303", "c304", "c70b", "SYN-CARDIOLOGY", "心血管专科能力包", tertiary,
                        "心血管内科", baseline("SPECIALTY_CARDIOLOGY")),
                pack("c339", "c60c", "c70c", "SYN-PEDIATRICS", "儿科专科能力包", tertiary,
                        "儿科", baseline("SPECIALTY_PEDIATRICS")),
                pack("c340", "c60d", "c70d", "SYN-MENTAL-HEALTH", "精神心理专科能力包", tertiary,
                        "精神心理科", baseline("SPECIALTY_MENTAL_HEALTH")),
                pack("c341", "c60e", "c70e", "SYN-ONCOLOGY", "肿瘤诊疗能力包", tertiary,
                        "肿瘤科", baseline("SPECIALTY_ONCOLOGY")),
                pack("c342", "c60f", "c70f", "SYN-OBSTETRICS", "妇产诊疗能力包", tertiary,
                        "妇产科", baseline("SPECIALTY_OBSTETRICS")));
    }

    static List<SpecialtySeed> specialties() {
        return List.of(
                specialty("c411", "ab15", "c401", "GENERAL_MEDICINE", "全科医学", "GENERAL_AVAILABLE", "SYN-SP-GENERAL", List.of("record", "order", "result", "followup")),
                specialty("c412", "aa08", "c402", "CARDIOLOGY", "心血管内科", "BASIC_CLOSED_LOOP", "SYN-SP-CARDIOLOGY", List.of("ecg", "critical-value", "cath-lab", "consult")),
                specialty("c413", "ab04", "c403", "PEDIATRICS", "儿科", "BASIC_CLOSED_LOOP", "SYN-SP-PEDIATRICS", List.of("weight-dose", "growth-chart", "neonatal-handoff")),
                specialty("c414", "ab14", "c404", "MENTAL_HEALTH", "精神心理科", "BASIC_CLOSED_LOOP", "SYN-SP-MENTAL-HEALTH", List.of("consent", "crisis", "restricted-record")),
                specialty("c415", "ab01", "c405", "EMERGENCY_MEDICINE", "急诊医学科", "BASIC_CLOSED_LOOP", "SYN-SP-EMERGENCY", List.of("triage", "resuscitation", "observation", "green-channel")),
                specialty("c416", "ab02", "c406", "NEUROLOGY", "神经内科", "BASIC_CLOSED_LOOP", "SYN-SP-NEUROLOGY", List.of("stroke", "nihss", "thrombolysis")),
                specialty("c417", "ab03", "c407", "GENERAL_SURGERY", "普通外科", "BASIC_CLOSED_LOOP", "SYN-SP-SURGERY", List.of("preop", "operation", "postop", "pathology")),
                specialty("c418", "ab05", "c408", "LABORATORY_MEDICINE", "医学检验科", "GENERAL_AVAILABLE", "SYN-SP-LAB", List.of("lis", "specimen", "critical-value", "qc")),
                specialty("c419", "ab06", "c409", "RADIOLOGY", "医学影像科", "GENERAL_AVAILABLE", "SYN-SP-RADIOLOGY", List.of("ris", "pacs", "structured-report", "critical-image")),
                specialty("c41a", "admin:OBSTETRICS", "c40a", "OBSTETRICS", "妇产科", "BASIC_CLOSED_LOOP", "SYN-SP-OBSTETRICS", List.of("antenatal", "delivery", "postpartum", "newborn")),
                specialty("c41b", "admin:ONCOLOGY", "c40b", "ONCOLOGY", "肿瘤科", "BASIC_CLOSED_LOOP", "SYN-SP-ONCOLOGY", List.of("staging", "mdt", "chemotherapy", "followup")),
                specialty("c41c", "admin:ICU", "c40c", "CRITICAL_CARE", "重症医学科", "BASIC_CLOSED_LOOP", "SYN-SP-ICU", List.of("sofa", "ventilator", "sepsis", "handoff")),
                specialty("c41d", "admin:ANESTHESIOLOGY", "c40d", "ANESTHESIOLOGY", "麻醉科", "BASIC_CLOSED_LOOP", "SYN-SP-ANESTHESIA", List.of("assessment", "anesthesia", "pacu", "controlled-drug")),
                specialty("c41e", "admin:PATHOLOGY", "c40e", "PATHOLOGY", "病理科", "GENERAL_AVAILABLE", "SYN-SP-PATHOLOGY", List.of("specimen", "gross", "microscopy", "report")),
                specialty("c41f", "admin:PHARMACY", "c40f", "PHARMACY", "药学部", "GENERAL_AVAILABLE", "SYN-SP-PHARMACY", List.of("prescription-review", "dispensing", "antimicrobial", "adverse-reaction")),
                specialty("c420", "admin:REHABILITATION", "c410", "REHABILITATION", "康复医学科", "BASIC_CLOSED_LOOP", "SYN-SP-REHABILITATION", List.of("assessment", "plan", "therapy", "outcome")));
    }

    private static ConfigurationSeed configuration(
            String type, String key, String name, Map<String, Object> payload) {
        String namespace = "haoemr:tertiary-business-configuration:" + type + ":" + key;
        return new ConfigurationSeed(
                UUID.nameUUIDFromBytes((namespace + ":draft").getBytes(StandardCharsets.UTF_8)),
                UUID.nameUUIDFromBytes((namespace + ":runtime").getBytes(StandardCharsets.UTF_8)),
                type, "syn-" + key, "runtime-" + key, name, payload);
    }

    private static WorkflowStep step(String id, String name, String owner, int minutes) {
        return new WorkflowStep(id, name, owner, minutes);
    }

    private static Map<String, Object> workflowProfilePayload(
            String profileCode, String description, List<WorkflowStep> steps,
            String timeoutPolicy, List<String> syntheticCases) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(node(profileCode + "-start", "业务事件受理", "START", "系统与业务受理岗", 1, false, false));
        for (WorkflowStep step : steps) {
            nodes.add(node(profileCode + "-" + step.id(), step.name(), "TASK",
                    step.owner(), step.minutes(), false, false));
        }
        nodes.add(node(profileCode + "-sign", "业务记录签署", "SIGN",
                "经办人与上级审核人", 30, true, false));
        nodes.add(node(profileCode + "-audit", "质控与审计复核", "AUDIT",
                "医务处/质量管理部门", 240, true, false));
        nodes.add(node(profileCode + "-complete", "业务闭环完成", "END", "系统", 1, true, true));

        List<Map<String, Object>> edges = new ArrayList<>();
        for (int index = 0; index < nodes.size() - 1; index++) {
            Map<String, Object> from = nodes.get(index);
            Map<String, Object> to = nodes.get(index + 1);
            edges.add(edge(String.valueOf(from.get("id")), String.valueOf(to.get("id")),
                    String.valueOf(from.get("name")) + "完成", false));
        }
        edges.add(edge(profileCode + "-audit", profileCode + "-" + steps.getFirst().id(),
                "质控发现阻断缺陷，退回责任环节整改", true));
        return base(description).with(
                "profile_code", profileCode,
                "service_line", steps.stream().map(WorkflowStep::owner).distinct().toList(),
                "nodes", List.copyOf(nodes), "edges", List.copyOf(edges),
                "protected_nodes", List.of(profileCode + "-sign", profileCode + "-audit", profileCode + "-complete"),
                "timeout_policy", timeoutPolicy,
                "escalation_chain", List.of("责任岗位", "科室负责人", "医务处/护理部值班", "院级质量安全委员会"),
                "synthetic_cases", syntheticCases);
    }

    private static Map<String, Object> formProfilePayload(
            String profileCode, String description, List<Map<String, Object>> specialtyFields,
            String printTemplate, List<String> templateProfiles) {
        List<Map<String, Object>> fields = new ArrayList<>(List.of(
                field("patient_name", "患者姓名", "TEXT", "identity", true, true, null, null),
                field("encounter_no", "就诊号", "TEXT", "identity", true, true, null, null),
                field("department", "责任科室", "CODE", "identity", true, false, "HAOEMR-DEPARTMENT", null),
                field("recorded_at", "记录时间", "DATETIME", "identity", true, true, null, null)));
        fields.addAll(specialtyFields);
        fields.add(field("author_signature", "经办人数字签名", "SIGNATURE", "signature", true, true, null, null));
        fields.add(field("reviewer_signature", "审核人数字签名", "SIGNATURE", "signature", true, true, null, null));
        List<Map<String, Object>> terminology = fields.stream()
                .filter(item -> item.get("terminology") != null)
                .map(item -> object("field", item.get("id"), "system", item.get("terminology")))
                .toList();
        return base(description).with(
                "profile_code", profileCode,
                "groups", List.of(
                        group("identity", "患者与就诊", 2), group("content", "业务记录", 2),
                        group("safety", "风险与安全", 2), group("signature", "签署与审核", 2)),
                "fields", List.copyOf(fields), "terminology_mapping", terminology,
                "print_template", printTemplate, "template_profiles", templateProfiles,
                "version_binding", "已生成临床文书永久绑定发布时版本；后续升级不覆盖历史病历");
    }

    private static Map<String, Object> ruleProfilePayload(
            String profileCode, String description, List<Map<String, Object>> rules, String scenario) {
        return base(description).with(
                "profile_code", profileCode,
                "conditions", List.of("患者上下文", "就诊状态", "医嘱与结果", "任务时限", "责任人与科室"),
                "actions", List.of("失败关闭", "阻断并解释", "创建任务", "逐级升级", "保留审计证据"),
                "rule_layer", "PLATFORM_AND_INSTITUTION", "rules", rules,
                "sample_case", object("case_id", "SYN-" + profileCode.toUpperCase().replace('-', '_') + "-01", "scenario", scenario),
                "precedence", List.of("平台安全硬门", "机构硬规则", "业务提醒", "AI 人工确认建议"));
    }

    private static Map<String, Object> scopeProfilePayload(
            String profileCode, String description, List<Map<String, Object>> permissions) {
        return base(description).with(
                "profile_code", profileCode,
                "roles", permissions.stream().map(item -> String.valueOf(item.get("role"))).distinct().toList(),
                "data_scopes", permissions.stream().map(item -> String.valueOf(item.get("scope"))).distinct().toList(),
                "permissions", permissions,
                "separation_of_duties", "申请人!=审批人；作者!=审核人；业务执行、质量复核和系统管理职责分离",
                "temporary_grant_hours", 8,
                "deny_overrides_allow", true,
                "break_glass_review_hours", 24,
                "decision_context", List.of("机构", "院区", "科室", "病区", "医疗组", "患者关系", "班次", "任务"));
    }

    static Map<String, Object> compositionPayload(CapabilityPackSeed pack) {
        List<Map<String, Object>> dependencies = new ArrayList<>();
        for (String module : pack.modules()) {
            if (!"CORE_PATIENT".equals(module)) dependencies.add(object("module", module, "requires", "CORE_PATIENT"));
        }
        return object(
                "schema_version", 3,
                "capability_pack_id", pack.packId().toString(),
                "inherits_from", pack.inheritsFrom(),
                "module_catalog", moduleCatalog(),
                "selected_modules", pack.modules(),
                "dependencies", dependencies,
                "conflicts", List.of(object("left", "LEGACY_EXPORT", "right", "QUALITY_RESEARCH")),
                "protected_modules", PROTECTED_MODULES,
                "scope_overrides", List.of(object("scope", pack.scope(), "module", pack.modules().getLast(), "effect", "ENABLE")),
                "rating_framework", object(
                        "code", "INTERNAL_OPERATIONAL_READINESS_V1",
                        "official_hospital_grade", false,
                        "description", "内部配置完整度与发布就绪度，不代表医院等级评审结论"),
                "rating_impact", "内部运行就绪度由依赖、冲突、发布证据和安全门动态计算",
                "rollout_tasks", List.of("依赖解析", "128 例合成病例回放", "科室负责人联合签署", "灰度观察 72 小时", "审计与回退演练"));
    }

    private static List<Map<String, Object>> moduleCatalog() {
        return List.of(
                module("CORE_PATIENT", "患者与就诊核心", "公共内核", true),
                module("CLINICAL_RECORD", "结构化病历", "公共内核", true, "CORE_PATIENT"),
                module("ORDER_RESULT", "医嘱与结果闭环", "临床业务", false, "CORE_PATIENT"),
                module("DIGITAL_SIGNATURE", "可靠电子签名与验真", "安全治理", true, "CLINICAL_RECORD"),
                module("AUDIT_OUTBOX", "审计与可靠事件", "安全治理", true, "CORE_PATIENT"),
                module("OUTPATIENT", "门诊诊疗闭环", "临床业务", false, "CLINICAL_RECORD", "ORDER_RESULT"),
                module("EMERGENCY", "急诊急救绿色通道", "临床业务", false, "CLINICAL_RECORD", "ORDER_RESULT"),
                module("INPATIENT", "住院诊疗与转科", "临床业务", false, "CLINICAL_RECORD", "ORDER_RESULT"),
                module("NURSING", "护理执行与交接班", "临床业务", false, "CORE_PATIENT", "ORDER_RESULT"),
                module("SURGERY", "围术期与手术安全", "临床业务", false, "CLINICAL_RECORD", "ORDER_RESULT"),
                module("ANESTHESIA", "麻醉评估与复苏", "临床业务", false, "CLINICAL_RECORD", "ORDER_RESULT"),
                module("CRITICAL_CARE", "重症监护与早期预警", "急危重症", false, "CLINICAL_RECORD", "ORDER_RESULT"),
                module("MEDICAL_TECH", "检验影像病理协同", "医技协同", false, "ORDER_RESULT"),
                module("QUALITY_RESEARCH", "医疗质控与合规科研", "质控科研", false, "CLINICAL_RECORD", "AUDIT_OUTBOX"),
                module("SPECIALTY_CARDIOLOGY", "心血管专科增强", "专科能力", false, "CLINICAL_RECORD", "ORDER_RESULT"),
                module("SPECIALTY_PEDIATRICS", "儿科剂量与生长曲线", "专科能力", false, "CLINICAL_RECORD", "ORDER_RESULT"),
                module("SPECIALTY_MENTAL_HEALTH", "精神心理与危机干预", "专科能力", false, "CLINICAL_RECORD"),
                module("SPECIALTY_ONCOLOGY", "肿瘤 MDT 与治疗计划", "专科能力", false, "CLINICAL_RECORD", "ORDER_RESULT"),
                module("SPECIALTY_OBSTETRICS", "产前分娩与产后闭环", "专科能力", false, "CLINICAL_RECORD", "ORDER_RESULT"),
                module("LEGACY_EXPORT", "受控历史数据导出", "兼容能力", false, "CORE_PATIENT"));
    }

    private static Map<String, Object> module(
            String code, String name, String group, boolean protectedModule, String... requires) {
        return object("code", code, "name", name, "group", group,
                "protected", protectedModule, "requires", List.of(requires));
    }

    private static Map<String, Object> workflowPayload() {
        return base("覆盖门诊、急诊、住院、医技、会诊、转科、出院和病案归档的三级医院闭环流程。")
                .with("profile_code", "integrated-clinical-closed-loop", "nodes", List.of(
                        node("registration", "实名建档与就诊登记", "START", "门诊部/急诊预检", 5, false, false),
                        node("triage", "分诊与风险分级", "TASK", "分诊护士", 10, false, false),
                        node("resuscitation", "急危重症抢救", "TASK", "急诊抢救团队", 15, false, false),
                        node("assessment", "医师接诊评估", "TASK", "经治医生", 30, false, false),
                        node("orders", "医嘱与检查申请", "TASK", "经治医生", 20, false, false),
                        node("results", "医技结果审核", "TASK", "医技审核人员", 120, false, false),
                        node("critical", "危急值闭环处置", "TASK", "经治医生与科主任", 10, false, false),
                        node("consult", "多学科会诊", "TASK", "会诊专家组", 120, false, false),
                        node("treatment", "诊疗计划执行", "TASK", "医护药技团队", 240, false, false),
                        node("handoff", "入院转科与交接", "TASK", "床位医师与责任护士", 30, false, false),
                        node("discharge", "出院审核与随访计划", "TASK", "经治医生与护士", 60, false, false),
                        node("sign", "病历数字签署", "SIGN", "经治医生/上级医师", 20, true, false),
                        node("audit", "病案质控与审计", "AUDIT", "病案室与质控办", 1440, true, false),
                        node("complete", "就诊闭环完成", "END", "系统", 1, true, true)),
                "edges", List.of(
                        edge("registration", "triage", "登记成功", false),
                        edge("triage", "resuscitation", "分级为 I/II 级", false),
                        edge("triage", "assessment", "生命体征稳定", false),
                        edge("resuscitation", "assessment", "抢救后生命体征稳定", false),
                        edge("assessment", "orders", "诊疗计划已形成", false),
                        edge("orders", "results", "医技项目已执行", false),
                        edge("results", "critical", "结果命中危急值", false),
                        edge("results", "consult", "疑难复杂或跨学科", false),
                        edge("results", "treatment", "常规结果已审核", false),
                        edge("critical", "treatment", "危急值已确认并处置", false),
                        edge("consult", "treatment", "会诊意见已签署", false),
                        edge("treatment", "handoff", "需要住院或转科", false),
                        edge("treatment", "discharge", "门急诊治疗完成", false),
                        edge("handoff", "discharge", "达到出院标准", false),
                        edge("discharge", "sign", "出院记录与随访计划完成", false),
                        edge("sign", "audit", "签名及时间戳有效", false),
                        edge("audit", "complete", "病案完整性检查通过", false),
                        edge("consult", "assessment", "资料不全退回补充", true),
                        edge("handoff", "treatment", "病情恶化启动补偿流程", true)),
                "protected_nodes", List.of("sign", "audit", "complete"),
                "timeout_policy", "危急值 10 分钟、急会诊 30 分钟、普通会诊 120 分钟、出院病历 24 小时逐级升级",
                "escalation_chain", List.of("责任人", "科主任", "医务处值班", "医疗质量委员会"),
                "synthetic_cases", List.of("急性心肌梗死绿色通道", "脑卒中溶栓", "多发伤抢救转 ICU", "复杂肿瘤 MDT"));
    }

    private static Map<String, Object> formPayload() {
        List<Map<String, Object>> groups = List.of(
                group("identity", "患者与就诊", 2), group("complaint", "主诉与现病史", 2),
                group("history", "既往史与风险", 2), group("exam", "体格检查与评分", 3),
                group("orders", "医嘱与结果", 2), group("assessment", "诊断与计划", 2),
                group("consent", "知情同意与签署", 2));
        List<Map<String, Object>> fields = List.of(
                field("patient_name", "患者姓名", "TEXT", "identity", true, true, null, null),
                field("encounter_no", "就诊号", "TEXT", "identity", true, true, null, null),
                field("encounter_type", "就诊类型", "SELECT", "identity", true, false, "ENCOUNTER_TYPE", null),
                field("department", "接诊科室", "CODE", "identity", true, false, "HAOEMR-DEPARTMENT", null),
                field("chief_complaint", "主诉", "TEXTAREA", "complaint", true, false, "SNOMED-CT", null),
                field("present_illness", "现病史", "TEXTAREA", "complaint", true, false, "SNOMED-CT", null),
                field("symptom_onset", "症状起始时间", "DATETIME", "complaint", true, false, null, null),
                field("allergies", "过敏史", "CODE", "history", true, false, "SNOMED-CT", null),
                field("medication_history", "用药史", "TEXTAREA", "history", false, false, "ATC", null),
                field("past_history", "既往史", "TEXTAREA", "history", false, false, "ICD-10-CN", null),
                field("pregnancy_status", "妊娠状态", "SELECT", "history", false, false, null, null),
                field("temperature", "体温", "NUMBER", "exam", true, false, "UCUM", null),
                field("blood_pressure", "血压", "TEXT", "exam", true, false, "UCUM", null),
                field("heart_rate", "心率", "NUMBER", "exam", true, false, "UCUM", null),
                field("spo2", "血氧饱和度", "NUMBER", "exam", true, false, "UCUM", null),
                field("pain_score", "疼痛评分", "NUMBER", "exam", true, false, null, null),
                field("early_warning", "早期预警评分", "CALCULATED", "exam", true, true, null, "NEWS2(temperature,blood_pressure,heart_rate,spo2)"),
                field("orders_summary", "医嘱摘要", "TEXTAREA", "orders", true, false, null, null),
                field("critical_values", "危急值记录", "TEXTAREA", "orders", false, true, "LOINC", null),
                field("primary_diagnosis", "主要诊断", "CODE", "assessment", true, true, "ICD-10-CN", null),
                field("secondary_diagnosis", "其他诊断", "CODE", "assessment", false, false, "ICD-10-CN", null),
                field("treatment_plan", "诊疗计划", "TEXTAREA", "assessment", true, false, null, null),
                field("followup_plan", "随访计划", "TEXTAREA", "assessment", false, false, null, null),
                field("consent_record", "知情同意记录", "TEXTAREA", "consent", true, true, null, null),
                field("clinician_signature", "医生数字签名", "SIGNATURE", "consent", true, true, null, null),
                field("supervisor_signature", "上级医师签名", "SIGNATURE", "consent", false, true, null, null));
        return base("覆盖门诊病历、急诊记录、住院首次病程、会诊、交接、出院和知情同意的结构化模板集。")
                .with("profile_code", "integrated-medical-record-set", "groups", groups, "fields", fields,
                        "terminology_mapping", List.of(
                                object("field", "primary_diagnosis", "system", "ICD-10-CN"),
                                object("field", "chief_complaint", "system", "SNOMED-CT"),
                                object("field", "critical_values", "system", "LOINC"),
                                object("field", "temperature", "system", "UCUM"),
                                object("field", "medication_history", "system", "ATC")),
                        "print_template", "A4-三级医院统一病历-v3",
                        "template_profiles", List.of("门诊病历", "急诊病历", "住院病历", "会诊记录", "出院记录", "知情同意书"));
    }

    private static Map<String, Object> rulePayload() {
        List<Map<String, Object>> rules = List.of(
                rule("allergy-block", "严重过敏处方阻断", "PLATFORM_HARD", 1200, "严重过敏且药物成分命中", "阻断处方并要求选择替代药", "国家药品不良反应监测规范"),
                rule("duplicate-medication", "重复用药阻断", "PLATFORM_HARD", 1150, "同成分或同治疗分类重复", "阻断重复医嘱并展示现有医嘱", "处方管理办法"),
                rule("high-alert-double-check", "高警示药品双人核对", "INSTITUTION_HARD", 1100, "高警示药品或浓缩电解质", "要求医师与药师双签", "三级医院高警示药品目录 2026"),
                rule("renal-dose", "肾功能剂量校验", "INSTITUTION_HARD", 1050, "eGFR 低于药品阈值", "阻断超剂量并给出肾功能剂量范围", "医院药事委员会肾功能剂量规范"),
                rule("pediatric-dose", "儿科体重剂量校验", "INSTITUTION_HARD", 1000, "年龄小于 14 岁且体重已记录", "超出 mg/kg 范围时阻断", "院内儿科用药目录 2026.2"),
                rule("pregnancy-contraindication", "妊娠禁忌用药校验", "INSTITUTION_HARD", 950, "妊娠状态与禁忌药物同时命中", "阻断并要求专科会诊", "妊娠期用药安全规范"),
                rule("critical-value", "危急值十分钟闭环", "INSTITUTION_HARD", 900, "检验或影像结果命中危急值", "10 分钟内确认并创建处置任务", "危急值报告制度"),
                rule("sepsis-screening", "脓毒症早期筛查", "INSTITUTION_HARD", 850, "qSOFA/NEWS2 达到阈值", "启动脓毒症路径和乳酸复查", "Sepsis 3.0 与医院急救路径"),
                rule("vte-risk", "住院 VTE 风险评估", "INSTITUTION_HARD", 800, "入院或术后 24 小时未评估", "阻断病程提交并创建评估任务", "国家医疗质量安全改进目标"),
                rule("consult-timeout", "会诊超时升级", "REMINDER", 600, "急会诊 30 分钟或普通会诊 120 分钟未响应", "提醒科主任并升级医务处", "医疗核心制度"),
                rule("pending-result", "出院未回结果追踪", "REMINDER", 550, "出院时存在未回重要结果", "创建结果追踪和患者通知任务", "检查检验结果闭环制度"),
                rule("record-completion", "病历完成时限提醒", "REMINDER", 500, "出院记录或首次病程接近时限", "提醒责任人与上级医师", "病历书写基本规范"),
                rule("antimicrobial-review", "抗菌药物 72 小时复评", "REMINDER", 450, "限制级抗菌药物使用达到 72 小时", "提醒感染评估与降阶梯", "抗菌药物临床应用管理办法"),
                rule("ai-summary", "AI 病情摘要建议", "AI_ADVICE", 100, "资料完整度达到 80%", "生成带来源的摘要候选供医生确认", "clinical-ai-golden-v1"));
        return base("覆盖用药安全、危急值、脓毒症、VTE、抗菌药物、会诊和病历时限的三级医院规则集。")
                .with("profile_code", "integrated-clinical-safety", "conditions", List.of("患者特征", "诊断与过敏", "医嘱", "检验检查", "任务时限"),
                        "actions", List.of("阻断", "双签", "创建任务", "逐级提醒", "人工确认建议"),
                        "rule_layer", "MIXED", "rules", rules,
                        "sample_case", object("case_id", "SYN-TERTIARY-RULE-01", "scenario", "急诊胸痛患者合并严重青霉素过敏和肾功能不全"));
    }

    private static Map<String, Object> scopePayload() {
        List<Map<String, Object>> permissions = List.of(
                permission("门诊经治医生", "门诊病历", "读写", "本人接诊患者", "ALLOW", 0, "签署后更正需上级复核"),
                permission("急诊医生", "急诊全量病历", "读写", "当前急诊患者", "ALLOW", 0, "抢救结束后自动收窄"),
                permission("住院经治医生", "住院病历与医嘱", "读写", "本人医疗组患者", "ALLOW", 0, "上级医师完成最终签署"),
                permission("会诊医生", "病历全文", "只读", "会诊授权患者", "ALLOW", 4, "禁止导出和转授权"),
                permission("责任护士", "护理记录与医嘱执行", "读写", "本人病区患者", "ALLOW", 0, "护理审核人与作者分离"),
                permission("临床药师", "用药相关病历与处方", "审核", "药学服务患者", "ALLOW", 8, "不能修改医生诊断"),
                permission("医技审核人员", "检查检验申请与结果", "审核", "本科室任务队列", "ALLOW", 0, "报告审核人与采集人分离"),
                permission("病案管理员", "封存病案", "归档", "已出院患者", "ALLOW", 0, "不得修改临床正文"),
                permission("质控专家", "病历与质量指标", "审核", "授权抽检范围", "ALLOW", 8, "只能生成质控问题"),
                permission("科研人员", "脱敏科研数据集", "只读", "伦理批准队列", "ALLOW", 24, "禁止反识别和外传"),
                permission("信息系统管理员", "系统配置", "管理", "本机构", "ALLOW", 0, "无临床病历正文读取权"),
                permission("急救破窗人员", "急救最小病历", "只读", "急救授权患者", "ALLOW", 2, "强制理由、通知与事后复核"),
                permission("跨科医生", "批量病历导出", "导出", "全部患者", "DENY", 0, "保护性拒绝优先"),
                permission("AI 医助", "最小必要临床上下文", "生成候选", "当前患者与任务", "ALLOW", 1, "不得直接写入或签署"));
        return base("按机构、院区、科室、病区、医疗组、患者关系、班次和临时授权组合计算最终权限。")
                .with("profile_code", "integrated-role-scope", "roles", List.of("门诊经治医生", "急诊医生", "住院经治医生", "会诊医生", "责任护士", "临床药师", "医技审核人员", "病案管理员", "质控专家", "科研人员", "系统管理员", "AI 医助", "临床上下文签发医生"),
                        "data_scopes", List.of("本人接诊", "医疗组", "病区", "会诊授权", "任务队列", "脱敏科研", "急救破窗"),
                        "permissions", append(permissions, object(
                                "role", "临床上下文签发医生", "role_code", "CLINICIAN",
                                "resource", "临床上下文", "resource_code", "CLINICAL_CONTEXT",
                                "action", "签发上下文租约", "action_code", "LEASE_ISSUE",
                                "scope", "文书起草", "scope_code", "DOCUMENT_DRAFT",
                                "effect", "ALLOW", "temporary_hours", 0,
                                "relationship_required", false, "shift_required", false,
                                "approval_required", false, "sod", "仍须由 ABAC 患者关系策略决定")),
                        "authorization_bindings", List.of(object(
                                "resource_code", "CLINICAL_CONTEXT", "action_code", "LEASE_ISSUE",
                                "scope_code", "DOCUMENT_DRAFT")),
                        "separation_of_duties", "作者!=审批人；采集人!=报告审核人；配置作者!=发布审批人；系统管理员无临床正文权限",
                        "temporary_grant_hours", 4,
                        "deny_overrides_allow", true,
                        "break_glass_review_hours", 24);
    }

    private static Payload base(String description) {
        return new Payload(object(
                "schema_version", 3,
                "fixture_source", "tertiary-hospital-business-config-v3",
                "hospital_level", "三级甲等",
                "organization", "江城大学附属医院",
                "description", description,
                "environment", "dev-synthetic",
                "data_policy", "确定性仿真配置；不包含真实患者信息；结构和治理约束按三级医院业务设计",
                "owner", "三级医院配置管理委员会",
                "effective_scope", List.of("江城大学附属医院", "总院区", "门诊/急诊/住院/医技/科研"),
                "controls", List.of("双人审批", "最小权限", "审计留痕", "失败关闭", "灰度发布", "可验证回退"),
                "china_compliance", object(
                        "profile", "CN_MEDICAL_PRODUCTION_2026",
                        "data_element_standard", "WS/T 363.1-2023",
                        "electronic_record_dataset", "WS 445.1-2014~WS 445.17-2014",
                        "diagnosis_code_system", "国家临床版 ICD-10（年度受控版本）",
                        "procedure_code_system", "国家临床版 ICD-9-CM-3（年度受控版本）",
                        "signature_policy", "可靠电子签名 + CA 证书 + 可信时间戳 + 签名验真",
                        "retention_policy", "门急诊病历不少于15年；住院病历不少于30年；归档后更正留痕",
                        "minimum_necessary", true,
                        "effective_from", "2026-09-01T00:00:00+08:00",
                        "review_due", "2027-03-01T00:00:00+08:00"),
                "evidence", object("dataset", "tertiary-hospital-business-config-v3", "case_count", 256,
                        "last_verified", "2026-08-31", "standards", List.of(
                                "医疗质量安全核心制度要点", "电子病历应用管理规范（试行）",
                                "WS/T 363.1-2023", "WS 445.1-2014~WS 445.17-2014",
                                "国家临床版疾病诊断和手术操作分类代码"))));
    }

    private static CapabilityPackSeed pack(
            String packId, String compositionId, String releaseId, String code, String name,
            String inherits, String scope, List<String> modules) {
        return new CapabilityPackSeed(id(packId), id(compositionId), id(releaseId), code, name, inherits, scope, modules);
    }

    private static List<String> baseline(String... additions) {
        List<String> modules = new ArrayList<>(List.of(
                "CORE_PATIENT", "CLINICAL_RECORD", "ORDER_RESULT", "DIGITAL_SIGNATURE", "AUDIT_OUTBOX"));
        modules.addAll(List.of(additions));
        return List.copyOf(modules);
    }

    private static SpecialtySeed specialty(
            String assessmentId, String departmentId, String releaseId, String scopeCode,
            String displayName, String supportLevel, String packCode, List<String> modules) {
        return new SpecialtySeed(id(assessmentId), departmentId(departmentId), id(releaseId), scopeCode, displayName,
                supportLevel, packCode, "3.0.0", modules, sha256("tertiary-evidence:" + scopeCode));
    }

    private static UUID departmentId(String reference) {
        if (!reference.startsWith("admin:")) return id(reference);
        String code = reference.substring("admin:".length());
        String facility = "018f0000-0000-7000-8000-00000000aa03";
        return UUID.nameUUIDFromBytes(("openemr2026:tertiary-hospital:department:" + facility + ":" + code)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> node(String id, String name, String type, String owner,
                                             int minutes, boolean protectedNode, boolean terminal) {
        return object("id", id, "name", name, "type", type, "owner", owner, "minutes", minutes,
                "protected", protectedNode, "terminal", terminal);
    }

    private static Map<String, Object> edge(String from, String to, String condition, boolean compensation) {
        return object("from", from, "to", to, "condition", condition,
                "event_code", from.toUpperCase().replace('-', '_') + "_TO_" + to.toUpperCase().replace('-', '_'),
                "guard", object("fact_path", "events." + from + ".completed", "operator", "EQ", "expected", true),
                "compensation", compensation);
    }

    private static Map<String, Object> group(String id, String name, int columns) {
        return object("id", id, "name", name, "columns", columns);
    }

    private static Map<String, Object> field(String id, String label, String type, String group,
                                              boolean required, boolean protectedField,
                                              String terminology, String calculation) {
        return object("id", id, "label", label, "type", type, "group", group, "required", required,
                "protected", protectedField, "terminology", chinaTerminology(terminology), "calculation", calculation,
                "visibility", "ALWAYS");
    }

    private static Map<String, Object> rule(String id, String name, String layer, int priority,
                                             String condition, String action, String evidence) {
        return object("id", id, "name", name, "layer", layer, "priority", priority,
                "condition", condition, "action", action, "evidence", evidence, "enabled", true,
                "fact_path", id, "operator", "EQ", "expected", true,
                "action_code", layer.contains("HARD") ? "BLOCK_AND_CREATE_REVIEW_TASK" : "CREATE_REVIEW_TASK",
                "evidence_meta", object("authority", "国家卫生健康委员会/医院授权委员会",
                        "source", evidence, "version", "2026受控版", "effective_from", "2026-09-01",
                        "review_due", "2027-03-01", "evidence_class", "CLINICAL_GOVERNANCE"),
                "human_approval_required", true,
                "exception", layer.contains("HARD") ? "必须记录授权例外与双签" : "允许责任人说明原因后关闭提醒");
    }

    private static Map<String, Object> permission(String role, String resource, String action, String scope,
                                                   String effect, int hours, String sod) {
        return object("role", role, "resource", resource, "action", action, "scope", scope,
                "role_code", "ROLE-" + sha256(role).substring(0, 12).toUpperCase(),
                "resource_code", "RESOURCE-" + sha256(resource).substring(0, 12).toUpperCase(),
                "action_code", "ACTION-" + sha256(action).substring(0, 12).toUpperCase(),
                "scope_code", "SCOPE-" + sha256(scope).substring(0, 12).toUpperCase(),
                "effect", effect, "temporary_hours", hours, "sod", sod,
                "relationship_required", scope.contains("患者") || scope.contains("接诊") || scope.contains("医疗组"),
                "shift_required", role.contains("医生") || role.contains("护士") || role.contains("药师"),
                "approval_required", action.contains("导出") || action.contains("管理") || action.contains("归档"));
    }

    private static String chinaTerminology(String terminology) {
        if (terminology == null) return null;
        return switch (terminology) {
            case "ICD-10-CN" -> "ICD-10-NATIONAL-CLINICAL-2026";
            case "ICD-9-CM-3" -> "ICD-9-CM-3-NATIONAL-CLINICAL-2026";
            case "SNOMED-CT" -> "WS/T-363-2023+SNOMED-MAPPING";
            case "LOINC" -> "WS/T-363-2023+LOINC-MAPPING";
            case "ATC" -> "NMPA-DRUG-CODE+ATC-MAPPING";
            case "UCUM" -> "WS/T-363-2023+UCUM-MAPPING";
            default -> "LOCAL-GOVERNED:" + terminology;
        };
    }

    private static Map<String, Object> object(Object... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("key/value pairs required");
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return result;
    }

    private static List<Map<String, Object>> append(
            List<Map<String, Object>> values, Map<String, Object> value) {
        List<Map<String, Object>> result = new ArrayList<>(values);
        result.add(value);
        return List.copyOf(result);
    }

    private static UUID id(String suffix) {
        return UUID.fromString("018f0000-0000-7000-8000-00000000" + suffix);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    record ConfigurationSeed(
            UUID draftId, UUID runtimeId, String configType, String draftKey,
            String runtimeKey, String displayName, Map<String, Object> payload) { }

    record WorkflowStep(String id, String name, String owner, int minutes) { }

    record CapabilityPackSeed(
            UUID packId, UUID compositionId, UUID releaseId, String packCode,
            String packName, String inheritsFrom, String scope, List<String> modules) { }

    record SpecialtySeed(
            UUID assessmentId, UUID departmentId, UUID releaseId, String scopeCode,
            String displayName, String supportLevel, String packCode, String semanticVersion,
            List<String> modules, String evidenceHash) { }

    private static final class Payload extends LinkedHashMap<String, Object> {
        Payload(Map<String, Object> base) { super(base); }

        Payload with(Object... pairs) {
            putAll(object(pairs));
            return this;
        }
    }
}
