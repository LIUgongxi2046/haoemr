已完成 14 组、58 个子医助的工程功能验收：全部使用真实 DeepSeek 调用和应用业务 API。验收数据全部为合成病例；结果按医生待确认候选处理。

可直接打开 http://127.0.0.1:4181/#/ai-assistant ，后端为 8081，数据库为 haoemr_agent_live_20260906。原有 8080 服务与 openemr2026_dev 数据库保留。模型为 deepseek-v4-flash，凭据经模型管理服务保管，没有写入本报告和源码。

已验收的行为：

- 58/58：读取受权数据、调用真实模型、返回带来源和缺失信息的岗位候选。每个子医助均有独立 request_id。
- 58/58：候选经人工确认保存长提醒、转换任务、领取、确认来源提醒后关闭，并生成来源完成事件。
- 10/10 文书医助：生成可编辑段落，保存实际文书版本；同一旧候选在文书更新后被拦截。
- 浏览器：14 组全部 58 项可选择；文书生成和带入编辑区、长提醒创建、转任务、确认操作通过，未记录页面异常。
- 跨患者文书/提醒来源写入被拒绝；无患者的通用任务可以真实执行。
- 后端 14 项针对性测试通过；后端构建、Vue 类型检查与前端验收构建通过。

实现包含：58 个受控岗位技能；完整专业只读工具目录；真实知识正文与版本来源检索；按任务筛选配置；空上下文租约校验；文书候选结构解析；医生选择段落后保存及不可变来源记录；候选提醒来源校验；提醒任务关闭；长提醒标题处理；合成环境初始化稳定身份和不覆盖已发布医助策略。失败不会再被展示为全部可用。

首轮 37/58 成功，21 个失败已定位并修复后复验通过。历史失败记录保留用于审计。中医增加了通过正常 API 写入的纯合成四诊、方药样本，验证了真实来源读取；缺少临床规则、剂量或煎服要求时返回缺失，测试方本身不是临床处方。工程验收没有验证诊疗效果或全量生产环境部署条件。

逐项验收记录：

| 主医助 | 子医助 | 真实调用 | 提醒任务闭环 | 文书保存 |
|---|---|---|---|---|
| 心血管诊疗主 Agent | 抗凝用药建议 (ANTICOAGULATION_ADVISOR) | 通过 | 通过 | 不适用 |
| 心血管诊疗主 Agent | 胸痛风险分层 (CHEST_PAIN_TRIAGE) | 通过 | 通过 | 不适用 |
| 心血管诊疗主 Agent | 心衰随访计划 (HEART_FAILURE_FOLLOWUP) | 通过 | 通过 | 不适用 |
| 诊疗协同主 Agent | 会诊准备医助 (CONSULT_PREPARATION_AGENT) | 通过 | 通过 | 不适用 |
| 诊疗协同主 Agent | 出院衔接医助 (DISCHARGE_TRANSITION_AGENT) | 通过 | 通过 | 不适用 |
| 诊疗协同主 Agent | 随访协调医助 (FOLLOWUP_COORDINATION_AGENT) | 通过 | 通过 | 不适用 |
| 诊疗协同主 Agent | MDT 简报医助 (MDT_BRIEF_AGENT) | 通过 | 通过 | 不适用 |
| 诊疗协同主 Agent | 任务对账医助 (TASK_RECONCILIATION_AGENT) | 通过 | 通过 | 不适用 |
| 诊疗协同主 Agent | 转科交接医助 (TRANSFER_HANDOFF_AGENT) | 通过 | 通过 | 不适用 |
| 文书起草主 Agent | 入院记录起草医助 (ADMISSION_NOTE_DRAFTER) | 通过 | 通过 | 通过 |
| 文书起草主 Agent | 会诊文书起草医助 (CONSULT_NOTE_DRAFTER) | 通过 | 通过 | 通过 |
| 文书起草主 Agent | 出院文书起草医助 (DISCHARGE_NOTE_DRAFTER) | 通过 | 通过 | 通过 |
| 文书起草主 Agent | 急诊记录起草医助 (EMERGENCY_NOTE_DRAFTER) | 通过 | 通过 | 通过 |
| 文书起草主 Agent | 首次病程起草医助 (FIRST_COURSE_DRAFTER) | 通过 | 通过 | 通过 |
| 文书起草主 Agent | 护理交班起草医助 (NURSING_HANDOFF_DRAFTER) | 通过 | 通过 | 通过 |
| 文书起草主 Agent | 门诊病历起草医助 (OUTPATIENT_NOTE_DRAFTER) | 通过 | 通过 | 通过 |
| 文书起草主 Agent | 围术期文书起草医助 (PERIOPERATIVE_NOTE_DRAFTER) | 通过 | 通过 | 通过 |
| 文书起草主 Agent | 病程记录起草医助 (PROGRESS_NOTE_DRAFTER) | 通过 | 通过 | 通过 |
| 文书起草主 Agent | 查房记录起草医助 (WARD_ROUND_NOTE_DRAFTER) | 通过 | 通过 | 通过 |
| 就诊摘要主 Agent | 接诊摘要医助 (ACTIVE_ENCOUNTER_SUMMARIZER) | 通过 | 通过 | 不适用 |
| 就诊摘要主 Agent | 出院准备摘要医助 (DISCHARGE_READINESS_SUMMARIZER) | 通过 | 通过 | 不适用 |
| 就诊摘要主 Agent | 住院每日摘要医助 (INPATIENT_DAILY_SUMMARIZER) | 通过 | 通过 | 不适用 |
| 就诊摘要主 Agent | 围术期上下文医助 (PERIOPERATIVE_CONTEXT_SUMMARIZER) | 通过 | 通过 | 不适用 |
| 就诊摘要主 Agent | 诊前摘要医助 (PRE_VISIT_SUMMARIZER) | 通过 | 通过 | 不适用 |
| 就诊摘要主 Agent | 分诊上下文医助 (TRIAGE_CONTEXT_SUMMARIZER) | 通过 | 通过 | 不适用 |
| 血糖治理主 Agent | 入院血糖评估 (ADMISSION_GLUCOSE_ASSESSOR) | 通过 | 通过 | 不适用 |
| 血糖治理主 Agent | 出院血糖随访 (DISCHARGE_GLUCOSE_PLAN) | 通过 | 通过 | 不适用 |
| 血糖治理主 Agent | 低血糖预警 (HYPOGLYCEMIA_GUARD) | 通过 | 通过 | 不适用 |
| 血糖治理主 Agent | 胰岛素剂量建议 (INSULIN_DOSE_ADVISOR) | 通过 | 通过 | 不适用 |
| 重症风险研判主 Agent | 病情恶化预警 (DETERIORATION_MONITOR) | 通过 | 通过 | 不适用 |
| 重症风险研判主 Agent | 重症交班摘要 (ICU_HANDOFF_SUMMARIZER) | 通过 | 通过 | 不适用 |
| 重症风险研判主 Agent | 脓毒症风险分层 (SEPSIS_RISK_ASSESSOR) | 通过 | 通过 | 不适用 |
| 院感监测主 Agent | 感染病例线索复核 (INFECTION_CASE_REVIEWER) | 通过 | 通过 | 不适用 |
| 院感监测主 Agent | 法定传染病直报协助 (NOTIFIABLE_REPORT_ASSISTANT) | 通过 | 通过 | 不适用 |
| 院感监测主 Agent | 暴发预警评估 (OUTBREAK_ALERT_ASSESSOR) | 通过 | 通过 | 不适用 |
| 医保合规主 Agent | 费用合理性核查 (CHARGE_RATIONALITY_REVIEWER) | 通过 | 通过 | 不适用 |
| 医保合规主 Agent | DRG/DIP 编码核对 (DRG_CODING_REVIEWER) | 通过 | 通过 | 不适用 |
| 医技预约调度主 Agent | 设备利用分析 (EQUIPMENT_UTILIZATION_REVIEWER) | 通过 | 通过 | 不适用 |
| 医技预约调度主 Agent | 检查号源规划 (EXAM_SLOT_PLANNER) | 通过 | 通过 | 不适用 |
| 患者宣教主 Agent | 复诊提醒 (FOLLOWUP_REMINDER_ASSISTANT) | 通过 | 通过 | 不适用 |
| 患者宣教主 Agent | 出院带药说明 (DISCHARGE_MEDICATION_GUIDE) | 通过 | 通过 | 不适用 |
| 病历质控主 Agent | 运行病历质控医助 (ACTIVE_RECORD_QC_REVIEWER) | 通过 | 通过 | 不适用 |
| 病历质控主 Agent | 更正一致性医助 (CORRECTION_CONSISTENCY_REVIEWER) | 通过 | 通过 | 不适用 |
| 病历质控主 Agent | 签署前质控医助 (PRE_SIGN_QC_REVIEWER) | 通过 | 通过 | 不适用 |
| 病历质控主 Agent | 终末质控医助 (TERMINAL_RECORD_QC_REVIEWER) | 通过 | 通过 | 不适用 |
| 病历质控主 Agent | 书写中质控医助 (WRITING_QC_REVIEWER) | 通过 | 通过 | 不适用 |
| 科研随访主 Agent | 队列入组协助 (COHORT_ENROLLMENT_ASSISTANT) | 通过 | 通过 | 不适用 |
| 科研随访主 Agent | 结局采集协助 (OUTCOME_COLLECTION_ASSISTANT) | 通过 | 通过 | 不适用 |
| 结果闭环主 Agent | 更正结果对账医助 (CORRECTED_RESULT_RECONCILER) | 通过 | 通过 | 不适用 |
| 结果闭环主 Agent | 危急值上下文医助 (CRITICAL_RESULT_CONTEXT_AGENT) | 通过 | 通过 | 不适用 |
| 结果闭环主 Agent | 随访任务规划医助 (FOLLOWUP_TASK_PLANNER) | 通过 | 通过 | 不适用 |
| 结果闭环主 Agent | 新结果接收医助 (NEW_RESULT_INTAKE_AGENT) | 通过 | 通过 | 不适用 |
| 结果闭环主 Agent | 待回结果跟踪医助 (PENDING_RESULT_TRACKER) | 通过 | 通过 | 不适用 |
| 结果闭环主 Agent | 结果趋势医助 (RESULT_TREND_REVIEWER) | 通过 | 通过 | 不适用 |
| 中医辨证审方主 Agent | 煎服指导 (DECOCTION_GUIDE) | 通过 | 通过 | 不适用 |
| 中医辨证审方主 Agent | 方药思路 (HERBAL_FORMULA_ADVISOR) | 通过 | 通过 | 不适用 |
| 中医辨证审方主 Agent | 配伍禁忌 (HERB_INTERACTION_GUARD) | 通过 | 通过 | 不适用 |
| 中医辨证审方主 Agent | 辨证草稿 (SYNDROME_DIFFERENTIATION) | 通过 | 通过 | 不适用 |

证据文件：acceptance-matrix.json 提供每个医助的 run_id、模型 request_id、来源数量、文书版本和任务 ID；runs.jsonl 保存完整执行事件；all-agent-handoffs.json 保存 58 个任务闭环结果；closure-checks.json 保存跨患者和无患者边界结果；../playwright/agent-functional-20260906/ 保存页面截图、58 项选择覆盖和浏览器流程结果。数据库中的 medical_agent_document_adoption 与 medical_agent_reminder_adoption 保留不可变来源。

复现入口：setup.mjs 使用用户指定的密钥文件在本隔离库配置真实模型；accept.mjs 执行逐个调用；handoffs.mjs 执行全部候选任务承接；closure.mjs 检查任务关闭和边界。API 测试入口固定为 8081，仅面向本次独立合成环境。脚本会产生新的合成任务和文书版本。
