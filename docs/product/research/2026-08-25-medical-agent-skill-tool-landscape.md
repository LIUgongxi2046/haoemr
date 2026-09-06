# 医疗 Agent、Skill、Tool 成熟应用场景与 HaoEMR 落地调研

> 调研日期：2026-08-25（Asia/Shanghai）  
> 证据截止：2026-08-25  
> 文档状态：市场与政策事实为 `OBSERVED`；项目方案为 `CREATED`；尚未完成真实临床验证  
> 决策模式：S001 / `ASSESS + DECIDE`  
> 当前建议：对“可引用、可复核、无直接临床副作用”的专职 Agent 组合 `INVEST`；对自主诊断、处方、签署和执行 `STOP`

## 1. 执行摘要

### 1.1 一句话战略命题

haoemr 不应做一个无边界的“AI 医生”，而应把高频医疗工作拆成一组窄职责、可版本化、可评测、可停用的 Agent；每个 Agent 通过受控 Skill 编排最小权限 Tool，只产生带来源的候选、摘要或任务预案，再由医务人员和既有临床内核确认。

非目标：不让模型直接确诊、开立或执行医嘱、解除安全阻断、签署病历、修改收费事实，也不把通用聊天框包装成已验证的专科能力。

### 1.2 核心结论

1. **成熟的是“助手”，不是“自主医生”。** 环境听写、病历摘要、文书草拟、患者消息草稿、编码/授权材料整理已进入规模化部署；严格意义上能自主规划、调用工具并发起临床动作的 Agentic AI 仍处于早期。2026 年的范围综述只纳入 7 项严格符合 Agentic AI 定义的研究，其中 6 项未进入真实工作流；综述结论是尚无系统证明具备真实部署所需的稳健性和自治水平。[npj Digital Medicine 范围综述](https://www.nature.com/articles/s41746-026-02517-5)
2. **最适合首发的是低副作用、高频、结果可核对的任务。** 推荐第一梯队：诊前/当次就诊摘要、住院病程/出院/交班草稿、病历质控第二视角、异常结果闭环、会诊转诊摘要。它们与项目既有文书、结果、任务、审签和候选层契约高度吻合。
3. **Tool 的成熟度通常高于 Agent。** 药物相互作用、剂量、单位、编码有效性、患者/就诊查询、任务状态机等应继续由确定性 Tool/规则完成；Agent 只负责检索编排、解释、归纳和形成候选，不替代硬规则。
4. **Skill 是可持续差异化的核心制品。** 模型可替换，真正沉淀的是中国病历模板、专科流程、质控规则、术语版本、输入输出 Schema、失败语义和评测集。专科扩展应做成 Skill Pack，不复制 Agent、不分叉内核。
5. **当前架构方向正确，但运行制品还不够。** 项目已有 Agent/Skill/Tool 目录、依赖解析、预算、审批、执行核验、审计和“AI 候选与临床事实分层”；下一步应把目录从“身份登记表”升级为可发布 manifest，并落地 Tool Gateway、Context Lease、来源引用和真实运行状态机。
6. **建议 90 天只验证 4 个临床 Agent + 1 个运营 Agent。** 先做 `EncounterSummarizer`、`DocumentDrafter`、`RecordQC`、`ResultFollowupCoordinator`，再做低风险 `PatientMessageDrafter`；环境听写需要音频授权、说话人分离和本地语音基础设施，放在第二波。

### 1.3 核心判断的证据与反证

| 判断 | 来源 | 置信度 | 什么会推翻当前判断 |
|---|---|---:|---|
| 真正自主的医疗 Agent 尚未成熟 | `OBSERVED`：2026 年范围综述中绝大多数研究仍是实验/模拟；医疗 LLM 评测综述中仅 5% 使用真实患者照护数据 | 高 | 出现多中心、前瞻性、真实临床工作流研究，证明自主动作在安全、有效性和成本上持续优于受控助手 |
| 环境听写/文书草稿是当前最成熟赛道 | `OBSERVED`：多家大型医疗系统规模化部署；真实世界研究观察到文书时间、下班后 EHR 时间或负担下降 | 高 | 多中心研究显示净时间不降、复核负担抵消收益，或本地中文场景错误率持续不可接受 |
| 消息草稿可用但未必节时 | `OBSERVED`：真实部署采用率约 19%–58%，多项研究未见显著回复时间下降，但主观负担有所改善 | 高 | 经本地模板和路由优化后稳定获得显著节时，且安全事件为零 |
| Skill/Tool 比“大而全 Agent”更值得开源沉淀 | `INFERRED`：模型与供应商快速变化，医疗流程、规则、Schema、评测和治理更稳定 | 中高 | 社区只采用成品模型适配器，不复用 Skill/Tool 契约，或标准化能力包缺少实施需求 |
| 项目第一阶段应限定在候选层 | `EXPLICIT + OBSERVED`：项目硬原则、国内监管方向、FDA CDS 边界和真实产品均强调人工判断/复核 | 高 | 明确法规路径、医疗器械注册、临床试验与机构授权共同支持某一窄任务的更高自治等级 |

## 2. 先统一术语：Agent、Skill、Tool 不是同一层

市场上经常把听写、聊天、搜索、预测模型都称为“Agent”。为避免架构失控，本项目采用以下操作性定义。

| 概念 | 本项目定义 | 何时使用 | 不应承担什么 |
|---|---|---|---|
| **Tool** | 单一职责、强 Schema、可审计的确定性能力或外部 API | 精确查询、规则校验、计算、检索、保存候选、创建待审批动作 | 不自主规划；不暴露任意 SQL、通用 HTTP 或跨患者搜索 |
| **Skill** | 版本化医疗工作流包，包含触发条件、模板、知识、允许 Tool、输入输出 Schema、失败语义和 Evals | 出院小结、病历质控、交班、编码、随访等可重复任务 | 不因为被加载就自动获得权限；不把指南全文常驻 Prompt |
| **Agent** | 在限定目标和预算内选择 Skill、调用白名单 Tool、生成结构化结果并接受验证的运行主体 | 单次函数无法完成的多来源、多步骤、需恢复或编排任务 | 不绕过权限、硬规则、审批和领域状态机；不拥有通用写库权 |

严格意义上的 Agent 至少具备目标导向、一定自治和工具/动作发起能力；仅做一次文本生成的是模型调用或 Skill 执行器，不必注册为 Agent。[Agentic AI 范围综述的定义与证据](https://www.nature.com/articles/s41746-026-02517-5)

### 2.1 成熟度分级

| 等级 | 判定口径 | 可否进入项目默认能力 |
|---|---|---|
| M4 规模化成熟 | 多机构生产部署；有真实工作流结果；失败可回退；责任边界清楚 | 可以，但仍需本地验证 |
| M3 生产可用 | 单机构或多机构真实部署；有采用率/效率/质量证据；必须人工复核 | 优先灰度 |
| M2 临床试验/研究可行 | 真实或回顾性数据上表现良好，但外部有效性、工作流或安全证据不足 | 影子运行或研究模块 |
| M1 原型 | 合成数据、基准或演示可行 | 不面向真实诊疗宣传 |
| M0 不应自治 | 一旦错误可能直接伤害患者，且缺少可接受的独立控制 | 禁止 Agent 直接执行 |

同时把“自治等级”与“模型能力”分开：

| 自治级别 | 可做动作 | 建议边界 |
|---|---|---|
| A0 | 只读问答/提示，不保存结果 | 可默认开放给授权用户 |
| A1 | 生成摘要、草稿、质控发现，保存为独立 AIProposal | 第一阶段上限 |
| A2 | 生成结构化业务候选，人工逐项接受后由领域内核执行 | 临床场景长期推荐上限 |
| A3 | 在机构预授权、可撤销、低风险运营规则内自动执行 | 仅排班提醒、通知重试等非临床任务可实验 |
| A4 | 自主诊断、处方、签署、治疗或解除阻断 | `DENY` |

## 3. 市场现状：产品已规模化，但“Agent”多是受控工作流助手

### 3.1 国际产品格局

| 产品/类别 | 已公开能力 | 成熟信号 | 对本项目的启示 |
|---|---|---|---|
| Microsoft Dragon Copilot / DAX | 环境语音、结构化临床笔记、信息检索、任务辅助 | 2025 年公开口径称 DAX 单月支持 600 家机构、300 万次环境会话；产品要求医生编辑确认。[Microsoft 官方](https://news.microsoft.com/2025/03/03/microsoft-dragon-copilot-provides-the-healthcare-industrys-first-unified-voice-ai-assistant-that-enables-clinicians-to-streamline-clinical-documentation-surface-information-and-automate-task/) | 环境听写是成熟入口，但价值来自深度 EHR 集成和复核流，不是单纯 ASR |
| Abridge、Ambience、Nabla、Suki | 环境听写、文书、编码/CDI、患者摘要、问答 | Cleveland Clinic 在 80 多个专科评估后推广 Ambience，并明确“不诊断、不治疗、医生全文确认”；Rush 在 28 个专科扩展 Suki；多家产品已系统级部署。[Cleveland Clinic](https://newsroom.clevelandclinic.org/2025/02/19/cleveland-clinic-announces-the-rollout-of-ambience-healthcares-ai-platform)、[Rush/Suki](https://www.suki.ai/press-releases/rush-expands-suki-partnership-with-enterprise-wide-ambient-ai-rollout-following-successful-launch/) | 产品形态已验证；开源项目应复用“来源可回查 + 专科模板 + 人工签署”模式，而非复制供应商黑盒 |
| Epic Art / Penny | 病历摘要、消息草稿、门诊/住院文书、护理交班、结果随访、编码、授权、拒付申诉、患者流 | Epic 将 AI 分成患者、临床、运营三类，并已公开大量嵌入工作流的窄任务。[Epic Art](https://www.epic.com/software/art/)、[Epic Penny](https://www.epic.com/software/penny/) | 成熟 EHR 不是一个万能 Agent，而是数十个窄 Skill 与原业务状态机结合 |
| Oracle Health Clinical AI Agent | 病历摘要、环境文书、编码、预约、患者导航、临床/运营编排 | 已覆盖 30 多个专科，并公开平均文书时间下降近 30%的供应商数据；动作以建议和审批为主。[Oracle 官方](https://www.oracle.com/news/announcement/physicians-reduce-documentation-time-with-oracle-health-clinical-ai-agent-2025-03-04/) | “统一 Agent”前台可以存在，但后台仍需专职能力、透明来源和动作边界 |
| 影像、病理、心电 AI 工具 | 检测、分割、定量、质量控制和报告辅助 | 大量产品走医疗器械路径；FDA 持续维护获批 AI 医疗器械清单。[FDA AI 医疗器械清单](https://www.fda.gov/medical-devices/software-medical-device-samd/artificial-intelligence-enabled-medical-devices) | 这是成熟 **Tool** 生态，不应在开源 EMR 中重造模型；应做合规适配器、结果来源和闭环任务 |

厂商的效率数字多来自其自身客户材料，能证明“有真实采用”，不能替代独立临床评价，也不能直接作为本项目收益承诺。

### 3.2 独立临床证据

- Stanford 45 名医生、8 个门诊学科、17,428 次就诊的前瞻性质量改进研究中，环境听写用于 55.25% 的就诊；每日文书、下班后 EHR 和总 EHR 时间中位数分别下降 6.89、5.17 和 19.95 分钟。[JAMIA / PubMed](https://pubmed.ncbi.nlm.nih.gov/39688515/)
- 新加坡真实时间动作研究纳入 9 名医生和 169 次问诊，文书时间下降 15%，眼神交流比例上升 10.6%，但总就诊周期没有显著缩短，说明主要价值是把时间还给医患沟通，不必预设为“多看病人”。[JMIR / PubMed](https://pubmed.ncbi.nlm.nih.gov/41915701/)
- 2026 年急诊研究中，8,740 次合格就诊只有 11.2% 使用环境听写，且集中在低危、无翻译场景；使用时在班文书时间下降 28%。这提示专科、严重度和语言会显著影响采用。[Annals of Emergency Medicine / PubMed](https://pubmed.ncbi.nlm.nih.gov/41665590/)
- 对 97 次多专科会话的盲评发现，环境笔记整体质量接近医生笔记，但幻觉检出率更高（31% 对 20%），更完整却不够精炼。因此“全文复核”必须是产品动作，不是免责声明。[PubMed](https://pubmed.ncbi.nlm.nih.gov/41199808/)
- 患者消息草稿的 Stanford 真实部署中，平均采用率 20%，任务负担和工作耗竭下降，但读写时间没有改善；另一项非英语医院研究采用率 58%，同样未观察到显著回复节时。[Stanford / PubMed](https://pubmed.ncbi.nlm.nih.gov/38506805/)、[荷兰研究 / PubMed](https://pubmed.ncbi.nlm.nih.gov/40575383/)
- TrialGPT 在合成患者和专家标注上能把临床试验初筛时间降低 42.6%，但仍是筛选助手而不是入组决定者。[Nature Communications / PubMed](https://pubmed.ncbi.nlm.nih.gov/39557832/)
- 2025 年 JAMA 系统综述检查 519 项医疗 LLM 评测，只有 5% 使用真实患者照护数据。模型榜单分数不能代替本地工作流验证。[JAMA / PubMed](https://pubmed.ncbi.nlm.nih.gov/39405325/)

### 3.3 中国落地与政策窗口

国家卫健委 2024 年发布 4 大类 84 项人工智能应用场景；2025 年实施意见进一步明确，到 2027 年形成一批临床专病专科垂直大模型和智能体应用，重点覆盖基层辅助、专病辅助、患者服务、科研教学和治理，同时强调“赋能而不替代”、分类管理、评测验证、数据安全和隐私保护。[84 项场景指引](https://www.nhc.gov.cn/wjw/c100175/202411/5bcb3c4edd064e31ac5d279caf5830f4.shtml)、[2025 实施意见](https://www.nhc.gov.cn/guihuaxxs/c100133/202511/d1a42ae835c743b9b3e83ac0253c3e9f.shtml)

公开案例显示，国内医院当前较常落地的是本地模型接入后的病历生成、病历内涵质控、报告解读、影像辅助和患者服务：

- 上海公开案例中，中山医院呼吸科从 2024 年 8 月试点 AI 病历辅助书写，已辅助 680 余份入院病历。[上海市政府](https://www.shanghai.gov.cn/nw4411/20250112/5d9ef649ad2748b69cb23641a0b34728.html)
- 无锡公开案例包含出院小结与手术记录生成、病历和护理文书质控、语音随访、导诊、预问诊和报告解读。[无锡市政府](https://www.wuxi.gov.cn/doc/2026/01/12/4715659.shtml)
- 淄博市中心医院公开的本地部署集中于病历内涵质控、跨结果/医嘱检查和病历辅助生成。[淄博市卫健委](https://ws.zibo.gov.cn/art/2025/2/25/art_815_2895337.html)
- 杭州医保“依保儿”把知识问答扩展到证照调取、核验和经办落地，证明高规则化、可回滚的行政 Agent 比临床自治更容易率先成熟。[国家医保局](https://www.nhsa.gov.cn/art/2025/3/17/art_52_16023.html)

结论：国内外路径趋同——先从文书、质控、检索、编码、患者服务和运营自动化切入；临床决策保持辅助定位。

## 4. 应用场景全景与成熟度矩阵

| 场景 | 主要用户 | 合适形态 | 市场成熟度 | 临床风险 | haoemr 优先级 | 判断 |
|---|---|---|---:|---:|---:|---|
| 诊前/当次就诊摘要、病历问答 | 医生、护士 | Agent + 摘要 Skill + 只读 Tool | M3–M4 | 中 | P0 | 已被 Epic/Oracle 等产品化；必须逐条引用来源 |
| 门诊/入院/病程文书草稿 | 医生 | Document Agent + 专科模板 Skill | M4 | 中 | P0 | 高频、可复核、与现有文书内核匹配 |
| 出院小结/医院经过草稿 | 医生、病案 | Document Agent + 出院 Skill | M3 | 中高 | P0 | 数据范围明确，但时序、遗漏和未决事项是主要风险 |
| 护理交班/班末小结 | 护士 | Document Agent + Handoff Skill | M3–M4 | 中 | P0 | 适合从既有生命体征、计划、给药、任务聚合；不可声称任务已完成 |
| 病历完整性/内涵质控 | 医生、质控办 | 规则 Tool + RecordQC Agent | M3–M4 | 中 | P0 | 国内落地多；硬规则与语义建议必须分层 |
| 异常结果归纳与闭环追踪 | 医生、护士、医技 | Result Agent + Task Tool | M3 | 高 | P0 | Agent 可排序和生成清单；危急值识别/状态机必须确定性 |
| 会诊、转诊、MDT 摘要 | 医生、协调员 | Care Agent + Brief Skill | M3 | 中 | P0 | 多来源归纳价值高，发送与任务归属需人工确认 |
| 用药安全解释、药物重整 | 医生、药师 | 硬规则/计算 Tool + SafetyReviewer | Tool M4；Agent M2–M3 | 高 | P0/P1 | 规则先行；Agent 仅做第二视角和可读解释 |
| ICD/手术/病案编码候选、CDI | 编码员、病案、医生 | Coding Agent + 术语 Tool | M3–M4 | 中 | P1 | 有直接效率/收入价值，但需版本化编码和人工确认 |
| 患者消息回复草稿 | 医生、护士、药师 | Message Agent + 分流 Skill | M3 | 中高 | P1 | 能减轻主观负担，未稳定证明节时；只从低风险消息开始 |
| 患者教育、出院指导可读化 | 医生、护士、患者 | Rewrite Skill + 已确认计划 Tool | M3 | 中 | P1 | 只能改写已确认事实，不新增诊疗建议 |
| 环境听写与结构化抽取 | 医生、护士 | Speech Tool + Document Agent | M4 | 中高 | P1 | 最成熟赛道，但中文多说话人、同意、音频保存和算力依赖较大 |
| 预约、提醒、随访、导航 | 门诊运营、患者 | Workflow Agent + Scheduling Tool | M3–M4（行政） | 低至中 | P1 | 允许在明确规则下达到 A3；症状分诊仍需转人工 |
| 出院准备度、床位与患者流 | 医务、护理、运营 | 预测 Tool + Coordinator Agent | M2–M3 | 中 | P1/P2 | 价值高但依赖完整任务和资源数据；先做建议与障碍清单 |
| 授权、拒付申诉、医保材料 | 医保办、运营 | RCM Agent + 文档/规则 Tool | 国外 M3–M4；国内需重构 | 低至中 | P2 | 美国证据不能直接迁移；国内应围绕医保规则版本和合规审核重做 |
| 临床指南/循证检索 | 医生、药师 | Retrieval Agent + 批准知识 Tool | M3 | 中高 | P1 | 返回证据和适用条件，不输出无依据指令；知识版本必须可追溯 |
| 临床试验匹配/研究队列 | 研究者、医生 | Research Agent + Cohort Tool | M2–M3 | 中 | P2 | 与项目研究队列契约匹配，先做研究模块和人工初筛 |
| 影像/病理/心电诊断辅助 | 医技、专科医生 | 获批模型 Tool + Workflow Agent | Tool M4；Agent M1–M2 | 高 | P2/集成 | 不自研通用模型；做 DICOM/FHIR 适配、来源、质控和任务闭环 |
| 鉴别诊断/治疗方案第二视角 | 医生 | Evidence Agent + Verifier | M2 | 高 | P2/研究 | 只做教学或影子评测；不能作为首发卖点 |
| 患者自助症状分诊 | 患者 | 受限对话 + 紧急升级规则 | M2 | 很高 | P2/NO-GO | 系统综述准确率波动大；不得取代人工分诊。[PubMed](https://pubmed.ncbi.nlm.nih.gov/40133390/) |
| 自主诊断、开药、签署、执行 | 患者/临床 | 不应交给 Agent | M0 | 极高 | 禁止 | 直接违反项目硬原则与当前证据边界 |

## 5. 建议固化进项目的 Agent / Skill / Tool 目录

### 5.1 推荐 Agent 组合

| agent_code | 目标 | 第一阶段允许自治 | 核心输出 | 建议状态 |
|---|---|---|---|---|
| `ENCOUNTER_SUMMARIZER` | 汇总诊前、当次或住院阶段事实 | A1 | 带引用摘要、来源缺口、待确认问题 | 立即实施 |
| `DOCUMENT_DRAFTER` | 生成门急住和护理文书候选 | A1 | `AIProposal<DocumentDraft>` | 立即实施 |
| `RECORD_QC` | 在确定性规则之后检查遗漏、矛盾、时序和语义 | A1 | 分层质控发现，不修改原文 | 立即实施 |
| `RESULT_FOLLOWUP_COORDINATOR` | 聚合异常结果和未闭环任务 | A1；任务仅提案 | 风险排序、责任角色、任务候选 | 立即实施 |
| `CARE_COORDINATOR` | 生成会诊、转诊、交班和随访预案 | A1/A2 | 摘要和可预览任务计划 | 保留并收窄 |
| `SAFETY_REVIEWER` | 解释确定性用药/医嘱规则并补充第二视角 | A1 | 规则引用、证据、不能判断项 | 规则 Tool 就绪后灰度 |
| `CODING_ASSISTANT` | 给出 ICD/手术/病案编码候选 | A1 | 代码候选、证据片段、版本 | 第二波 |
| `PATIENT_MESSAGE_DRAFTER` | 分类低风险消息并生成回复草稿 | A1 | 草稿、风险分级、人工升级 | 第二波 |
| `AMBIENT_DOCUMENTATION` | 将授权音频转为结构化文书草稿 | A1 | 转写引用、草稿、未识别片段 | 第二波，先解决音频治理 |
| `RESEARCH_MATCHER` | 队列/试验初筛 | A1 | 条件级匹配、缺失数据、人工判定 | 研究模块 |
| `ACTION_PLANNER` | 把目标拆成可见步骤，不执行 Tool | A0 | 候选计划和副作用预览 | 平台内部 |
| `VERIFICATION_AGENT` | 独立找错，不修改候选 | A0 | 验证报告 | 平台内部、必备 |

### 5.2 对当前 6 个前台 Agent 的调整

| 当前代码 | 建议 |
|---|---|
| `OPD_COPILOT` | 前台名称可保留；运行时路由到 `ENCOUNTER_SUMMARIZER`、`DOCUMENT_DRAFTER`、`ACTION_PLANNER`，不要让一个 Agent 同时承担全部门诊能力 |
| `DIAGNOSIS_REVIEW` | 降为 `EXPERIMENTAL`；默认只展示证据一致性和信息缺口，鉴别诊断进入研究/影子评测 |
| `ORDER_SAFETY` | 改名或映射为 `SAFETY_REVIEWER`；结论必须先来自现有过敏、剂量、相互作用和资质规则 Tool |
| `RESULT_TRIAGE` | 升级为 `RESULT_FOLLOWUP_COORDINATOR`；连接统一临床任务和危急值闭环，不停留在文本解读 |
| `DOCUMENT_QC` | 运行时拆为 `RECORD_QC` 与 `DOCUMENT_DRAFTER`，避免“找问题”和“重写文本”共用上下文 |
| `CARE_COORDINATOR` | 保留；用会诊、转诊、交班、随访 Skill 限定不同数据范围和责任角色 |

### 5.3 第一批 Skill 包

| skill_code | 用途 | 必须读取 | 输出/失败语义 |
|---|---|---|---|
| `encounter-previsit-summary@1` | 诊前摘要 | 当前患者、就诊、问题、用药、过敏、近期结果、未闭环任务 | 带引用摘要；部分来源则 `INCOMPLETE` |
| `outpatient-note-draft@1` | 门诊病历草稿 | 当次会话/录入、模板版本、已确认临床事实 | 文书候选；不产生确认诊断 |
| `inpatient-course-summary@1` | 住院阶段摘要 | 病程、医嘱、结果、会诊、任务和事件时间线 | 按时间组织；冲突不自动消解 |
| `discharge-summary-draft@1` | 出院小结 | 入院原因、医院经过、诊断、用药、结果、未决事项 | 未闭环事项必须显式列出 |
| `nursing-shift-handoff@1` | 护理交班 | 生命体征、护理计划、给药、风险、任务 | 不把“计划”写成“已执行” |
| `record-semantic-qc@1` | 内涵质控 | 当前不可变文书版本、规则结果、专科包 | 缺陷分级；硬规则严重度不可更改 |
| `result-trend-and-followup@1` | 结果趋势与闭环 | 已确认结果、危急值状态、复测和任务 | 风险清单 + 任务候选；缺数据不推测 |
| `consult-referral-brief@1` | 会诊/转诊摘要 | 当前问题、关键病史、结果、处置、会诊目标 | 摘要候选；发送需批准 |
| `patient-message-draft@1` | 患者消息回复 | 原消息、已确认计划、药物、复诊要求 | 仅白名单类别；红旗症状转人工 |
| `coding-candidate@1` | 编码候选 | 已签署文书、术语包版本、编码规则 | 代码+证据；失效码失败 |
| `medication-reconciliation@1` | 药物重整候选 | 多来源用药、时间、状态、过敏 | 冲突并列；不静默合并 |
| `guideline-evidence-brief@1` | 循证摘要 | 经批准的指南/药品说明书版本 | 结论、适用范围、引用、发布日期 |
| `trial-eligibility-screen@1` | 临床试验初筛 | 研究方案版本、患者授权数据 | 条件级满足/不满足/未知；不自动入组 |

专科差异应通过 `obstetrics-*`、`pediatrics-*` 等依赖基础 Skill 的扩展包加入字段、规则和 Evals，不复制基础 Agent。

### 5.4 第一批 Tool 契约

| tool_code | 类型 | 副作用 | 关键约束 |
|---|---|---|---|
| `get_encounter_context@1` | API/Query | NONE | Context Lease 内精确患者/就诊；返回版本和水位 |
| `list_document_versions@1` | API/Query | NONE | 默认只读当前不可变版本；历史按目的授权 |
| `list_recent_observations@1` | API/Query | NONE | 代码、时间窗、数量上限；返回单位和来源 |
| `list_orders_and_administrations@1` | API/Query | NONE | 区分计划、签署、执行、停止，不把状态扁平化 |
| `get_problem_allergy_medication_summary@1` | API/Query | NONE | 每项带来源、状态和更新时间 |
| `list_open_clinical_tasks@1` | API/Query | NONE | 返回责任角色、期限、状态和版本 |
| `search_approved_knowledge@1` | Retrieval | NONE | 只查批准 release；结果含段落、版本、发布日期 |
| `validate_icd_code@1` | Function | NONE | 编码体系和版本必填；失效码失败 |
| `validate_ucum@1` | Function | NONE | 确定性单位转换和量纲检查 |
| `calculate_pediatric_dose_range@1` | Function | NONE | 体重/年龄/药品/规则版本；过期体重失败 |
| `run_medication_safety_rules@1` | Function | NONE | 复用现有硬规则；Agent 不得改变 PASS/BLOCK |
| `transcribe_clinical_audio@1` | External/Local API | NONE | 同意、说话人、语言、保留期、数据驻留和音频哈希 |
| `create_document_proposal@1` | Command | CANDIDATE_ONLY | 只写 AIProposal；幂等和上下文版本校验 |
| `create_task_proposal@1` | Command | CANDIDATE_ONLY | 不直接分派；显示责任、期限和通知后果 |
| `propose_appointment_change@1` | Command | ASK | 显示号源、影响和撤销路径；批准后走预约内核 |
| `record_ai_feedback@1` | Command | NONE/Telemetry | 记录接受、修改、拒绝原因，不保存隐藏思维链 |

重要结构问题：当前 `tool_registry.tool_type` 允许 `DATABASE_QUERY`，但 LLD 又明确禁止任意 SQL。建议后续把“数据库查询”限定为平台内部实现类型，**不允许作为 Agent 可见能力**；Agent 可见面只登记带 Schema 和授权语义的领域查询 Tool。

## 6. 与 HaoEMR 当前基线的差距

### 6.1 已有优势

- AI 候选与临床事实物理分层，接受后重新走领域权限、版本和硬规则。
- 已有患者/就诊 Context、属性授权、紧急访问、审计哈希链、Outbox 和幂等命令。
- 已有文书版本、诊断、医嘱、结果、任务、会诊、护理、出院、用药安全、研究队列等可复用业务内核。
- 已有 Agent、Skill、Tool 目录、依赖解析、模型/Prompt 目录、预算、动作审批和执行核验纵向切片。
- LLD 已定义 Context Lease、来源引用、Tool Hook、Verification Agent 和发布门禁，方向与 SMART/FHIR 最小授权、CDS Hooks 工作流触发一致。SMART App Launch 用 OAuth scope 和 launch context 限定患者/就诊访问；CDS Hooks 2.0 定义基于工作流触发、FHIR prefetch、建议卡片和接受/拒绝反馈。[SMART App Launch 2.2](https://hl7.org/fhir/smart-app-launch/STU2.2/app-launch.html)、[CDS Hooks 2.0](https://cds-hooks.hl7.org/2.0/)

### 6.2 必须补齐的产品化缺口

| 缺口 | 当前表现 | 落地要求 |
|---|---|---|
| Registry 过薄 | Agent/Skill/Tool 主要记录 code、name、version、status | 增加 manifest 引用、内容哈希、owner、风险级、数据分类、Schema、允许 Tool、预算、eval suite、审批、签名/SBOM、发布状态 |
| 真实运行未闭环 | 目录和治理切片已存在，但真实 Skill/Tool 编排证据不足 | 实现 AIRun 状态机、Worker、Tool Gateway、SSE 恢复、fencing token、超时和紧急停用 |
| 来源引用未成为统一 UI 组件 | 当前助手有来源理念，但需系统化 | 每句话/字段可展开原文、资源版本、时间；来源过期使 Proposal 失效 |
| 前台 Agent 过于宽泛 | 6 个角色覆盖多个任务 | 前台可聚合，后台必须路由到窄 Agent + 窄 Skill；生成与验证上下文隔离 |
| 缺少真实临床 eval | 现有 100 项 AI eval 主要是工程门禁 | 建立用例金标、错误分类、临床双评、影子运行、分专科/语言/严重度切片 |
| 缺少成本与体验遥测 | 有预算对象，但未形成用例经济性 | 记录每次 run 的模型/Tool 调用、延迟、复核时间、编辑距离、采用/拒绝和故障恢复 |
| 外部模型/语音/知识适配不足 | 有模型目录和候选 DeepSeek 适配 | 明确本地/私有云/外部的 PHI 分级路由；音频、指南、术语各自独立 release |

不要先引入多 Agent 群体协作。固定工作流中，多 Agent 会增加延迟、成本、错误传递和审计复杂度；只有独立验证或复杂研究任务证明单 Agent 不足时才增加第二个 Agent。FHIR 的 Clinical Reasoning 模块已能表达规则、顺序、质量指标、指南和 order set；这类确定性知识应优先用 `PlanDefinition/Library/Measure` 或等价内部对象表达，而不是全部塞进 Prompt。[HL7 FHIR Clinical Reasoning](https://www.hl7.org/fhir/R5/clinicalreasoning-module.html)

## 7. 安全、合规与责任边界

### 7.1 中国落地最低线

2025 年国家卫健委等部门进一步要求医疗机构对电子病历信息使用承担主体责任，实施分级分类、最小可用、按岗位/任务/时限授权，所有操作可查询、可追溯，外部服务商访问需明确范围、目的和期限。[电子病历信息使用管理通知](https://app.www.gov.cn/govdata/gov/202507/01/531972/article.html)

因此每次 AI run 至少必须固化：

- 机构、用户、角色任期、患者、就诊、任务、用途、数据分类、时间窗和到期时间；
- Agent、Skill、Tool、模型、Prompt、知识和术语 release；
- 输入资源引用和版本水位，而不是把全量病历复制进日志；
- Tool 参数摘要、许可决策、副作用等级、批准人、执行结果和幂等键；
- 候选接受/修改/拒绝、最终临床对象版本和回退路径；
- 异常访问、越权、跨患者、Prompt injection 和紧急停用事件。

面向公众提供生成式服务时，还需单独评估《生成式人工智能服务管理暂行办法》所涉及的准确性、透明度、个人信息、数据来源和备案边界。[国家网信办](https://www.cac.gov.cn/2023-07/13/c_1690898327029107.htm)

### 7.2 风险分层

| 风险层 | 示例 | 默认控制 |
|---|---|---|
| R1 行政低风险 | 预约提醒、服务导航、材料清单 | 规则白名单内可 A2/A3；必须可撤销和转人工 |
| R2 文书/沟通 | 摘要、草稿、编码候选、患者消息 | A1；来源、全文复核、未确认标识、敏感内容策略 |
| R3 临床决策支持 | 结果排序、用药解释、诊断证据、指南匹配 | A1/A2；确定性规则优先、独立验证、医生批准 |
| R4 时间关键/直接治疗 | 危急值处置、剂量决定、处方、签署、执行 | Agent 不直接执行；硬规则和持证人员负责 |
| R5 医疗器械功能 | 影像诊断、患者特异治疗指令等 | 走产品分类、临床评价和适用监管路径 |

FDA 2026 年 CDS 指南虽不直接适用于中国，但提供了有用的产品边界参考：给医务人员提供信息/选项、非时间关键、允许独立审查依据的 CDS 与给出具体诊疗指令、面向患者或时间关键决策的风险不同。[FDA CDS 指南](https://www.fda.gov/regulatory-information/search-fda-guidance-documents/clinical-decision-support-software)

WHO 强调保护自治、安全与公共利益、透明可解释、责任、包容和持续响应；Joint Commission 的组织级框架进一步要求治理、数据管理、偏差控制、全生命周期监测和人员培训。[WHO](https://www.who.int/publications/i/item/9789240037403)、[Joint Commission](https://www.jointcommission.org/en-us/certification/responsible-use-of-ai-in-healthcare)

## 8. 价值、竞争与可持续性

### 8.1 用户、购买者和组织价值

| 对象 | 主要价值 | 不能用什么替代验证 |
|---|---|---|
| 医生/护士 | 少翻页、少重复录入、更快形成可用草稿、更少遗漏 | 不能只看生成速度；必须测复核总时间、严重错误和真实采用 |
| 病案/质控/编码 | 更早发现缺陷、定位证据、缩短编码与返工 | 不能用模型“准确率”代替缺陷召回、误报、版本有效性 |
| 患者 | 医患交流更专注、说明更易懂、随访更及时 | 不能让聊天满意度掩盖错误建议和延迟转人工 |
| 医疗机构 | 降低文书/协调负担，形成可治理 AI 能力平台 | 不能把供应商部署数或 token 用量当成医疗质量收益 |
| 开发者/实施商 | 可复用 Skill/Tool、公开评测、标准连接器 | 不能只有截图和 Prompt，没有运行契约、样例和回归测试 |

### 8.2 护城河假设

1. 中国原生、可公开审查的临床 Skill 包和失败语义。
2. Agent 运行与病历版本、硬规则、审签、任务和审计同一事实链。
3. 模型可替换，但来源、Schema、Evals、权限和领域动作不随模型重写。
4. 合成/脱敏黄金集、专科红队集和可复现的 FHIR/内部 API Agent benchmark。
5. 开源社区可提交 Skill、Tool adapter 和 eval case，而不触碰核心患者安全状态机。

反证：如果医疗机构只需要供应商闭源环境听写，且不愿共同维护 Skill/Evals；或开源用户无法部署本地模型和术语资源，则应收缩为 EHR 内嵌 AI 治理和适配器平台，而非自建完整模型应用生态。

### 8.3 单位经济性

不虚构市场价格、医生时薪或转化率。每个用例使用以下公式实测：

```text
每次任务净价值
= 节省的人工分钟 × 角色完全成本/分钟
 + 避免返工、漏项、拒付或延期的期望价值
 + 可证实的容量或质量收益
 - 模型推理、语音、检索、存储和网络成本
 - 人工复核与修订成本
 - 集成、监控、培训和支持的摊销成本
 - 安全事件与错误处置的期望成本
```

需要记录的未知变量：不同专科采用率、每任务复核时间、严重错误率、模型/语音成本、本地算力利用率、机构实施工时、知识/术语许可和患者同意拒绝率。

机会成本：如果先做自主诊断或多 Agent 演示，会挤占把现有 194 个业务页面、真实临床主链和生产依赖做扎实的资源，并提高合规负担；首轮应把 AI 限制在能复用现有临床内核的纵向切片。

## 9. 五个优先实验

以下阈值是 `CREATED` 的试验门槛，不是已验证性能；真实临床试验需机构伦理、数据和医疗治理批准。

| 实验 | 假设 | 方法与样本 | 周期 | 成功阈值 | 立即停止条件 |
|---|---|---|---|---|---|
| E1 诊前摘要 | 带引用摘要能减少查阅时间且不漏关键事实 | 先用 300 份合成/脱敏金标；再由 20 名医生在 500 次就诊影子评审 | 4 周 | 引用可寻址 100%；高风险事实错误 0；中位查阅时间下降 ≥25%；满意度 ≥4/5 | 任一跨患者/越权；把未确认事实写成确定结论；高风险遗漏未被门禁发现 |
| E2 出院/交班草稿 | 结构化草稿可减少净文书时间 | 10 名医生/护士，200 个病例；人工文本与 AI 草稿交叉盲评 | 4–6 周 | 关键字段完整率 ≥95%；70% 以上草稿只需轻微修改；净时间下降 ≥25% | 未执行事项被写成已完成；药物/诊断/随访严重错误；来源断裂 |
| E3 病历质控 | 规则 + 语义第二视角比规则单独使用多发现有价值缺陷 | 300 份文书，质控双人金标；比较规则、Agent、组合 | 4 周 | 严重缺陷召回 ≥90%；每份无效警报中位数 ≤0.3；硬规则结论被改写次数 0 | 错患者、泄露、Agent 降低硬规则严重度、质控建议直接改原文 |
| E4 异常结果闭环 | Agent 能减少“看见但未形成任务”的异常结果 | 500 个合成/脱敏结果事件，先全影子运行 | 4 周 | 危急值漏报 0；任务重复 0；可行动异常精确率 ≥80%；每项均有责任和状态来源 | 任何危急值被降级；虚构已通知/已复读/已处理；直接执行临床动作 |
| E5 患者消息草稿 | 只对白名单低风险消息生成草稿可降低主观负担 | 10 名医生/护士，500 条消息；只含预约、已确认计划解释、常规随访 | 4 周 | 草稿采用率 ≥30%；严重错误 0；平均总处理时间不劣于基线 10%；红旗升级召回 100% | 急症/用药调整被自动回复；未授权发送；患者上下文错配 |

## 10. 90 天落地路线

### 0–30 天：把“目录”变成“可运行契约”

- 冻结首批 5 个用例的输入/输出 Schema、风险等级、数据范围、预算、终止和人工责任。
- 为 Registry 增加 manifest/Schema/eval/owner/risk/release 引用；不急于把 Prompt 正文塞进数据库。
- 实现只读 Tool Gateway、Context Lease、来源引用和统一错误码；封禁 Agent 可见的任意 SQL/HTTP。
- 建立合成/脱敏金标和临床错误分类：错患者、否定、时序、遗漏、状态混淆、无来源、越权、重复副作用。
- UI 先做引用展开、来源过期、候选接受/修改/拒绝和失败回人工，不先做动画式多 Agent 展示。

### 31–60 天：完成两个 P0 纵向切片

- 先交付 `ENCOUNTER_SUMMARIZER + encounter-previsit-summary@1`。
- 再交付 `DOCUMENT_DRAFTER + discharge-summary-draft@1` 或 `nursing-shift-handoff@1`，按现有数据完整度二选一。
- 每个切片必须从页面触发，经过真实 API/DB、来源引用、验证、AIProposal、人工决策和最终领域对象回查。
- 建立模型路由对比：固定模型/版本/推理引擎/硬件，比较质量、延迟、成本和故障恢复；不以医学考试分数代替用例 eval。

### 61–90 天：影子、灰度和去留决策

- 上线 `RECORD_QC` 与 `RESULT_FOLLOWUP_COORDINATOR` 的影子运行；只有门禁通过后才向用户展示。
- 选择 5–20 名授权临床用户小范围灰度；记录净时间、编辑距离、采纳/拒绝、错误严重度和用户角色切片。
- 每周做安全事件和漂移复盘；模型、Prompt、Skill、Tool、知识任一变更均生成新 release 和新证据。
- 第 90 天按实验门槛对每个用例单独决定 `INVEST / ITERATE / STOP`，不因一个 Agent 成功而整体放开自治等级。

暂不进入 90 天范围：自主诊断/治疗、患者自由症状问诊、自动处方/签署、全院多 Agent 调度、通用浏览器/数据库工具、未获许可的指南和术语库、真实音频长期保存。

## 11. 最终决策

### `INVEST`

投资于：带来源的临床摘要、文书草稿、病历质控、结果闭环、会诊转诊/交班/随访协同；并把它们固化为开源的 Agent manifest、Skill Pack、Tool contract、Evals 和 UI 复核组件。

### `EXPERIMENT`

受控实验：患者消息、环境听写、编码/CDI、循证检索、临床试验匹配、出院准备度和低风险运营自动化。先验证真实净收益，再决定扩展。

### `STOP`

当前停止：自主诊断、处方、签署、治疗执行、解除用药/资质阻断、患者自助急症分诊、Agent 任意 SQL/HTTP、无来源答案直接进入病历、用多 Agent 数量包装成熟度。

## 12. 主要参考资料

### 政策、监管与标准

- [国家卫健委：卫生健康行业人工智能应用场景参考指引（2024）](https://www.nhc.gov.cn/wjw/c100175/202411/5bcb3c4edd064e31ac5d279caf5830f4.shtml)
- [国家卫健委等：关于促进和规范“人工智能+医疗卫生”应用发展的实施意见（2025）](https://www.nhc.gov.cn/guihuaxxs/c100133/202511/d1a42ae835c743b9b3e83ac0253c3e9f.shtml)
- [国家卫健委等：关于进一步加强医疗机构电子病历信息使用管理的通知（2025）](https://app.www.gov.cn/govdata/gov/202507/01/531972/article.html)
- [国家网信办：生成式人工智能服务管理暂行办法](https://www.cac.gov.cn/2023-07/13/c_1690898327029107.htm)
- [FDA：Clinical Decision Support Software Guidance（2026）](https://www.fda.gov/regulatory-information/search-fda-guidance-documents/clinical-decision-support-software)
- [HL7 SMART App Launch 2.2](https://hl7.org/fhir/smart-app-launch/STU2.2/app-launch.html)
- [HL7 CDS Hooks 2.0](https://cds-hooks.hl7.org/2.0/)
- [HL7 FHIR Clinical Reasoning](https://www.hl7.org/fhir/R5/clinicalreasoning-module.html)
- [WHO：Ethics and governance of AI for health](https://www.who.int/publications/i/item/9789240037403)
- [Joint Commission：Responsible Use of AI in Healthcare](https://www.jointcommission.org/en-us/certification/responsible-use-of-ai-in-healthcare)

### 临床与真实世界证据

- [医疗 Agentic AI 范围综述，npj Digital Medicine，2026](https://www.nature.com/articles/s41746-026-02517-5)
- [医疗 LLM 测试与评估系统综述，JAMA，2025](https://pubmed.ncbi.nlm.nih.gov/39405325/)
- [环境 AI 听写真实使用与文书时间，JAMIA，2025](https://pubmed.ncbi.nlm.nih.gov/39688515/)
- [环境 AI 听写时间动作研究，2026](https://pubmed.ncbi.nlm.nih.gov/41915701/)
- [急诊环境 AI 听写采用与文书时间，2026](https://pubmed.ncbi.nlm.nih.gov/41665590/)
- [AI 生成病历质量与幻觉评估，2025](https://pubmed.ncbi.nlm.nih.gov/41199808/)
- [患者消息草稿真实部署，2024](https://pubmed.ncbi.nlm.nih.gov/38506805/)
- [患者消息草稿系统综述，2025](https://pubmed.ncbi.nlm.nih.gov/42527471/)
- [TrialGPT 临床试验匹配，Nature Communications，2024](https://pubmed.ncbi.nlm.nih.gov/39557832/)
- [急诊出院摘要错误、幻觉与遗漏研究，2025](https://pubmed.ncbi.nlm.nih.gov/38633805/)

### 产品与医院部署证据

- [Microsoft Dragon Copilot](https://news.microsoft.com/2025/03/03/microsoft-dragon-copilot-provides-the-healthcare-industrys-first-unified-voice-ai-assistant-that-enables-clinicians-to-streamline-clinical-documentation-surface-information-and-automate-task/)
- [Epic Art for Clinicians](https://www.epic.com/software/art/)
- [Epic Penny for Revenue Cycle and Operations](https://www.epic.com/software/penny/)
- [Oracle Health Clinical AI Agent](https://www.oracle.com/health/clinical-suite/clinical-ai-agent/)
- [Cleveland Clinic 环境 AI 推广](https://newsroom.clevelandclinic.org/2025/02/19/cleveland-clinic-announces-the-rollout-of-ambience-healthcares-ai-platform)
- [新加坡卫生部：Note Buddy 与公共医疗系统推广](https://www.moh.gov.sg/newsroom/speech-by-mr-ong-ye-kung--minister-for-health-at-the-synapxe-ai-accelerate-conference-on-16-june-2025/)

