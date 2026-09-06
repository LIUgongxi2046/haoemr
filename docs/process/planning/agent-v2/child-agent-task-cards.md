# P0 候选子 Agent 实施工作包（33 项）

> 本文与 `implementation-backlog.md` 和 `task-dag.csv` 共同构成可执行任务卡。  
> 所有任务状态：`PLANNED`。  
> `DEC-002` 决定每个候选最终是 `CHILD_AGENT`、`SKILL`、`TOOL` 还是 `DEFER`。未有独立状态、预算、评估价值的能力不应勉强做子 Agent。

## 1. 每个工作包的共同任务契约

- 目标价值：用一个明确诊疗环节、独立输入/输出、最小 Tool 集、子预算和独立 Eval 协助主 Agent，让用户能看见其当前动作和结果贡献。
- 来源：PRD FR-027–036 / AC-027–036 / BR-026–035；LLD §3/§6/§7/§15；UI `child-agent-experience.md`。
- 当前状态：`PLANNED`。
- 共同硬依赖：`DEC-002`对该项判定为 `CHILD_AGENT`；`PLT-006..013`；对应 `MAIN-*-001`。
- 可并行项：同 family 内不共享迁移/契约编辑锁的 child 可并行；各 family 可并行。
- 输入上下文：PRD 该环节行、LLD 对应 child 契约行、相关领域 Service/API/DB 状态机、UI 子 Agent 中文 metadata、合成 fixture。
- 允许修改：
  - `contracts/` 中该 child 输出 schema 和显示 metadata；
  - `src/main/java/org/haoemr/agent/` 中该 release/profile/prompt/tool adapter/verifier；
  - 必要的相关领域只读适配层，但不改领域硬规则；
  - `src/test/`、`evals/`、`security/`和该 child 的 UI 契约/组件测试。
- 禁止项：
  - child 不直接调用正式写领域 Tool，不落诊断、文书、危急值、通知、任务或审批终态；
  - 不读取 parent scope 之外的 tenant/patient/encounter/task；
  - 不使用通用 SQL/HTTP/file/shell Tool；不保存跨患者长期记忆；
  - 不在 provider 里硬编码业务 Prompt；不让前端自行决定可执行 Tool。
- 共同实施动作（6 步）：
  1. 确认该能力的临床环节、使用角色、事实水位、关键/非关键身份和 R0 决议。
  2. 先实现版本化输出 Schema、AgentRelease/PromptRelease 和中文展示 metadata，从契约生成 Java/TS。
  3. 仅按本卡“最小 Tool/Skill”接入 typed read/calculation adapter，每个来源返回 sourceRef/version/watermark。
  4. 在 StructuredToolLoop 中固化 stop/block/partial 条件、子预算和 verifier，确保 scope 收窄。
  5. 用合成数据建 independent golden set，加至少一个缺失、一个冲突、一个跨患者和一个 prompt-injection 负例。
  6. 在小南协作卡显示角色、当前动作、贡献区域和交接状态；通过后才交给家族 Composition 任务。
- 共同接口/Schema/迁移影响：每项至少新增一个输出 Schema 和一个 immutable release；优先共享 `PLT-002/003` 通用表，禁止为每个 child 建一张独立运行表。
- 共同测试与验证：定向 Java/API/contract test；`node evals/check-golden.mjs`；`node security/check-red-team.mjs`；相关 Vitest；进入 family release 前再跑 Composition Eval。
- 共同安全与隐私：scope/source/policy verifier 为硬门；fixture 只能是合成数据；SSE/error/log 不含原始 PHI、Prompt 或推理链。
- 共同回滚：停用该 AgentRelease 并将 family Composition 切回前版；保留历史 run/release 解析；不删除证据。
- 共同输出/交接：release manifest、prompt pin、tool/skill pins、output schema、golden/red-team report、UI metadata 和可进入对应 `MAIN-*-002` 的决议。

## 2. `ENCOUNTER_SUMMARIZER` 子 Agent（6 项）

| ID / child | 独立目标与输入 | 最小 Tool / Skill | 输出 / 子预算 | 特有风险与阻断 | 独立 Eval 与可观测 DoD |
|---|---|---|---|---|---|
| `CH-ENC-01` `PRE_VISIT_SUMMARIZER` | 既往就诊、问题、过敏、用药、近期结果、开放任务形成诊前事实包 | patient/encounter/document/result/medication/task typed read；`encounter-previsit-summary@1` | `PreVisitSummaryV1`；6 steps / 12 tools / 35s | 不生成本次诊断/计划；患者不一致 `BLOCKED` | 新旧诊断不混淆；开放任务不当已完成；任意事实可打开来源 |
| `CH-ENC-02` `TRIAGE_CONTEXT_SUMMARIZER` | 主诉、生命体征、既往风险和分诊事实 | encounter/vital/problem/allergy typed read | `TriageContextV1`；5 / 10 / 25s | 分诊级别只引用规则/人工状态；冲突 `BLOCKED` | 不由模型重新定级；生命体征含时间/设备/版本来源 |
| `CH-ENC-03` `ACTIVE_ENCOUNTER_SUMMARIZER` | 当次录入、历史变化、新结果、医嘱和待确认问题 | encounter/document/result/order/task read | `ActiveEncounterSummaryV1`；6 / 12 / 35s | 未确认陈述必须标记；来源缺失 `INCOMPLETE` | “患者自述”不提升为临床事实；展示新旧变化与待确认 |
| `CH-ENC-04` `INPATIENT_DAILY_SUMMARIZER` | 指定时窗内医嘱、执行、结果、体征、护理、会诊和任务 | inpatient/order/execution/result/vital/consult/task read；可析出共享 `inpatient-daily-facts@1` Skill | `InpatientDailySummaryV1`；7 / 16 / 45s | 时窗/水位必填；计划不当已执行；跨 family 复用依赖 `DEC-006` | 首链候选；跨班水位、部分结果、重复事件和执行/计划分层通过 |
| `CH-ENC-05` `PERIOPERATIVE_CONTEXT_SUMMARIZER` | 术前/术后事实包与缺项 | surgery/order/result/consent/execution read | `PerioperativeContextV1`；7 / 14 / 45s | 患者、部位、侧别冲突或核查来源不可用 `BLOCKED` | 侧别/部位负例；不伪造知情同意或 time-out 已完成 |
| `CH-ENC-06` `DISCHARGE_READINESS_SUMMARIZER` | 出院准备事实和未闭环责任 | document/order/result/medication/task/followup read | `DischargeReadinessSummaryV1`；7 / 16 / 45s | 只列准备状态，不输出“可出院” | 未回结果、未完任务、用药对账和责任人缺失都可见 |

## 3. `DOCUMENT_DRAFTER` 子 Agent（10 项）

家族附加规则：child 只输出分节草稿、sourceRefs、gaps 和 warnings；`create_document_proposal` 只能由 `DOCUMENT_DRAFTER` root 聚合验证后调用一次。

| ID / child | 独立目标与输入 | 最小 Tool / Skill | 输出 / 子预算 | 特有风险与阻断 | 独立 Eval 与可观测 DoD |
|---|---|---|---|---|---|
| `CH-DOC-01` `OUTPATIENT_NOTE_DRAFTER` | 一次门诊病历的已确认事实、模板、当前草稿 | `outpatient-note-draft@1`；encounter/document read | `OutpatientNoteDraftV1`；6 / 10 / 40s | 诊断/计划保留候选；作者/模板无效 `BLOCKED` | 缺查体不补“未见异常”；逐段来源和 diff 可审阅 |
| `CH-DOC-02` `EMERGENCY_NOTE_DRAFTER` | 急诊记录与时间关键事件缺口 | `emergency-note-draft@1`；timeline/order/execution/result read | `EmergencyNoteDraftV1`；7 / 14 / 50s | 不推测抢救动作；关键时间线冲突 `BLOCKED` | 时间顺序、先救治后补登、执行来源和缺时间反例 |
| `CH-DOC-03` `ADMISSION_NOTE_DRAFTER` | 入院史、查体、已有文书和模板 | `admission-note-draft@1`；history/exam/document read | `AdmissionNoteDraftV1`；7 / 12 / 50s | 缺失查体不自动填正常；必填来源缺失 `INCOMPLETE` | 入院必填、既往史版本冲突、查体否定与空值区分 |
| `CH-DOC-04` `FIRST_COURSE_DRAFTER` | 入院事实、问题、诊断依据、鉴别和计划候选 | `first-course-draft@1`；admission/problem/evidence read | `FirstCourseDraftV1`；7 / 12 / 50s | 诊断依据/鉴别/计划分来源；不确认诊断 | 事实与临床推理物理分层；无来源鉴别不作确认项 |
| `CH-DOC-05` `PROGRESS_NOTE_DRAFTER` | 指定时窗的事件、结果、医嘱、执行和当前草稿 | `progress-note-draft@1`；event/result/order/execution read | `ProgressNoteDraftV1`；7 / 14 / 50s | 计划/已执行严格分层；时窗不明 `BLOCKED` | 跨日、迟到结果、取消医嘱、部分执行和草稿 diff |
| `CH-DOC-06` `WARD_ROUND_NOTE_DRAFTER` | 查房事实、查房者实际输入、模板和当前草稿 | `ward-round-note-draft@1`；round facts/user input read | `WardRoundNoteDraftV1`；7 / 12 / 50s | 上级意见只能来自实际输入；不代替查房者/签名 | 首链候选；无上级输入时不生成意见；展示每日摘要交接来源 |
| `CH-DOC-07` `CONSULT_NOTE_DRAFTER` | 会诊申请，或基于会诊方实际输入草拟意见 | `consult-note-draft@1`；consult/context read | `ConsultNoteDraftV1`；6 / 10 / 45s | 申请方不得代写会诊结论；角色不符 `BLOCKED` | 申请/意见文书类型与作者角色组合负例 |
| `CH-DOC-08` `PERIOPERATIVE_NOTE_DRAFTER` | 获批类型的术前/手术/术后事实和模板 | `perioperative-note-draft@1`；surgery/event/device read | `PerioperativeNoteDraftV1`；8 / 16 / 60s | 不臆造手术、麻醉、器械、植入物、人员；侧别冲突阻断 | 文书类型分策略；部位/侧别/植入物/参与人来源反例 |
| `CH-DOC-09` `NURSING_HANDOFF_DRAFTER` | 指定班次/单元的体征、MAR、护理、风险和任务 | `nursing-shift-handoff@1`；vital/MAR/care/task read | `NursingHandoffDraftV1`；7 / 14 / 50s | 待执行/已执行分层；护理角色和班次必须匹配 | 跨班水位、漏扫 MAR、未完任务和发送/接收状态区分 |
| `CH-DOC-10` `DISCHARGE_NOTE_DRAFTER` | 住院经过、确认诊断、用药、未决结果/任务和终末模板 | `discharge-summary-draft@1`；course/diagnosis/med/task read | `DischargeNoteDraftV1`；8 / 16 / 60s | 未决项必须列出；死亡记录等高风险类型不默认启用 | 未回结果、出院带药对账、终末类型权限与高风险模板门禁 |

## 4. `RECORD_QC` 子 Agent（5 项）

家族附加规则：确定性硬规则结果是不可变输入，AI finding 只能补充，不得隐藏、覆盖或降级 hard finding。

| ID / child | 独立目标与输入 | 最小 Tool / Skill | 输出 / 子预算 | 特有风险与阻断 | 独立 Eval 与可观测 DoD |
|---|---|---|---|---|---|
| `CH-QC-01` `WRITING_QC_REVIEWER` | 当前草稿的结构、来源、一致性低打扰提示 | `record-semantic-qc@1`；draft/source/rule read | `WritingQcFindingsV1`；5 / 8 / 25s | 只提示不阻断；无证据 finding 降置信或隐藏 | 输入时延、误报、来源可定位；不阻塞医生输入 |
| `CH-QC-02` `PRE_SIGN_QC_REVIEWER` | 不可变待签版本的完整、一致、时序和签署条件 | `pre-sign-qc@1`；immutable version/rule read | `PreSignQcFindingsV1`；6 / 10 / 35s | 硬规则缺失/版本不匹配 `BLOCKED`；不代签 | 签名前版本竞态、hard/AI 分层、严重度不降级 |
| `CH-QC-03` `ACTIVE_RECORD_QC_REVIEWER` | 在院/在诊文书版本、逾期、复制和诊疗一致性 | `active-record-qc@1`；records/task/rule read | `ActiveRecordQcFindingsV1`；7 / 14 / 45s | 只创建缺陷候选；不修原文/不创正式终态 | 复制相似≠必然缺陷；逾期时钟、责任角色和低证据误报 |
| `CH-QC-04` `TERMINAL_RECORD_QC_REVIEWER` | 出院/归档前文书、首页、编码、结果、签名和整改闭环 | `terminal-record-qc@1`；record/archive/rule read | `TerminalRecordQcFindingsV1`；8 / 16 / 55s | 归档硬门不可用 `BLOCKED`；不自动豁免/归档 | 未完缺陷、未签文书、未决结果、首页冲突与归档阻断语义 |
| `CH-QC-05` `CORRECTION_CONSISTENCY_REVIEWER` | 更正前后版本与引用影响图 | `correction-consistency@1`；version graph/sourceRef read | `CorrectionImpactV1`；7 / 14 / 45s | 只列受影响对象；不静默改已签文书 | 版本图不完 `INCOMPLETE`；旧引用、已完任务和已导出资产的影响不被删除 |

## 5. `RESULT_FOLLOWUP_COORDINATOR` 子 Agent（6 项）

家族附加规则：结果确认、危急值判定、通知/接收/处置和任务终态均来自权威领域状态。`FOLLOWUP_TASK_PLANNER` child 只输出 plan，`create_task_proposal` 由 root 聚合后调用。

| ID / child | 独立目标与输入 | 最小 Tool / Skill | 输出 / 子预算 | 特有风险与阻断 | 独立 Eval 与可观测 DoD |
|---|---|---|---|---|---|
| `CH-RES-01` `NEW_RESULT_INTAKE_AGENT` | 新增/更正结果、申请、优先级依据和相关任务 | result/order/task read；result normalization | `NewResultIntakeV1`；5 / 10 / 30s | 结果未确认 `INCOMPLETE`；不自定优先级终态 | preliminary/final/corrected 状态、重复事件和优先级规则来源 |
| `CH-RES-02` `RESULT_TREND_REVIEWER` | 可比较趋势、异常变化和不可比项 | result read；deterministic unit/reference normalization | `ResultTrendReviewV1`；6 / 14 / 40s | 计算只用确定性 Tool；单位不可转换则并列 | 单位转换、参考范围版本、不同标本/方法不强比；模型不自行算数 |
| `CH-RES-03` `CRITICAL_RESULT_CONTEXT_AGENT` | 危急值规则、通知、接收、处置和任务上下文 | critical-rule/notification/task read | `CriticalResultContextV1`；5 / 10 / 30s | 规则状态缺失/患者冲突 `BLOCKED`；不声称已通知/处置 | 通知失败、已读未接收、已接收未处置、规则版本和超时升级 |
| `CH-RES-04` `PENDING_RESULT_TRACKER` | 已申请未报告、部分报告或更正中结果 | order/result/task read | `PendingResultListV1`；5 / 12 / 35s | 未报告永不解释为阴性；来源水位必填 | 分项部分报告、外送检验、取消申请、更正中与水位滞后 |
| `CH-RES-05` `FOLLOWUP_TASK_PLANNER` | 已确认结果和已确认计划组装任务候选内容 | result/plan/policy read；无正式写 Tool | `FollowupTaskPlanV1`；6 / 12 / 40s | 不新增治疗方案；正式任务需 root proposal+审批+领域执行 | 缺责任人/期限/已确认计划时 `INCOMPLETE`；重复任务不重建 |
| `CH-RES-06` `CORRECTED_RESULT_RECONCILER` | 结果更正对文书、任务和后续复核的影响 | result version graph/document/task read | `CorrectedResultImpactV1`；7 / 16 / 50s | 不覆盖文书/删历史任务；影响图不完 `INCOMPLETE` | 更正前后、已签引用、已完任务、已通知状态和只增影响候选 |

## 6. `CARE_COORDINATOR` 子 Agent（6 项）

家族附加规则：子 Agent 不伪造共识、交接、接收或任务完成；发送、转派、改约、关闭继续走原业务权限和审批。

| ID / child | 独立目标与输入 | 最小 Tool / Skill | 输出 / 子预算 | 特有风险与阻断 | 独立 Eval 与可观测 DoD |
|---|---|---|---|---|---|
| `CH-CARE-01` `CONSULT_PREPARATION_AGENT` | 会诊摘要、问题清单和资料缺口 | `consult-referral-brief@1`；context/result read | `ConsultPreparationV1`；6 / 12 / 40s | 不输出会诊结论；目标科室/目的不明 `INCOMPLETE` | 申请方问题、目标科室、关键结果、权限边界和缺失资料 |
| `CH-CARE-02` `MDT_BRIEF_AGENT` | 多学科病例简报、已知分歧和待决问题 | `mdt-brief@1`；authorized multi-domain read | `MdtBriefV1`；7 / 14 / 50s | 只引用会前资料；不伪造共识/参会人意见 | 跨科室属性授权、分歧保留、未决项、会后记录不反向混入会前输入 |
| `CH-CARE-03` `TRANSFER_HANDOFF_AGENT` | 转床/科/院交接候选和未完成事项 | `transfer-handoff@1`；order/execution/risk/task read | `TransferHandoffV1`；7 / 14 / 50s | 发送/接收状态来自业务流程；未接收必须保留 | 源/目标单元不同、未完医嘱/执行、接收未确认和任务迁移硬门 |
| `CH-CARE-04` `DISCHARGE_TRANSITION_AGENT` | 已确认出院诊断、用药、教育、随访计划的连续照护候选 | `discharge-transition@1`；diagnosis/med/education/followup read | `DischargeTransitionV1`；7 / 14 / 50s | 只改写已确认计划；不新增治疗建议 | 出院带药、教育理解状态、未回结果、随访责任/期限和准备未完 |
| `CH-CARE-05` `FOLLOWUP_COORDINATION_AGENT` | 已确认随访计划转为提醒/任务候选和升级条件 | `followup-coordination@1`；plan/result/task-policy read | `FollowupCoordinationV1`；6 / 12 / 40s | 高风险症状/自由文本分诊转人工；不自动发送 | 过期计划、已完随访、打断阈值、升级和无授权渠道反例 |
| `CH-CARE-06` `TASK_RECONCILIATION_AGENT` | 开放、重复、过期、转派任务的对账建议 | `task-reconciliation@1`；task/source-state read | `TaskReconciliationV1`；6 / 14 / 40s | 已读不等于完成；终态只由任务来源系统确认 | 重复判定解释、过期/已取消、转派接收、来源水位和“建议≠终态” |

## 7. 子 Agent 任务大小与再拆分规则

任一 `CH-*` 如同时需要两个独立迁移、两种不同副作用 Tool，或一组无法同时证明的独立临床价值，S007 应在实施前再拆为：

1. `CH-*-CONTRACT` 契约/release/metadata；
2. `CH-*-TOOLS` typed read/calculation adapters；
3. `CH-*-LOOP` prompt/loop/verifier；
4. `CH-*-EVAL` independent golden/red-team；
5. `CH-*-UI` contribution card 和交接。

若某项最终判定为 Skill/Tool，保留原 ID，将输出改为不可变 SkillRelease/ToolRelease 和确定性测试，并从 family Composition 的 child 节点移到对应 AgentRelease 的 dependency/tool allowlist。

