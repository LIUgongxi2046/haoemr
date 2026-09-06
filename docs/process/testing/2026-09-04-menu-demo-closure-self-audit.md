# 全部菜单 demo 与闭环缺口自查报告

- 日期：2026-09-04（Asia/Shanghai）
- 范围：全部一级导航（4 组 15 项）→ 各中心/工作域二级菜单 → 任务中心视图/系统管理 12 项/模拟接口 18 子菜单 → 十个专科 × 七层深页；合计合同路由 198 条、视图 200 个
- 证据方式：代码静态证据（视图 → 共享组件 → `web/src/api/*` → `clinical-api.ts:334 fetch('/api/v1…')` → Java Controller/Service/迁移）＋既有验收文档（`docs/process/testing/2026-08-20 … 2026-09-02`）＋真机抽样（运行中的 4177 前端 + 8080 后端 + PostgreSQL）
- 判定术语
  - **demo/仿真**：页面数据写死不经过后端；含「模拟/演示/占位/合成/示例/待真实」自述；主要按钮只改本地状态；或为不可独立到达的壳页
  - **真实（real）**：读写均走真实 `/api/v1` 端点并持久化（幂等键 / 乐观锁 / 审计哈希链 / outbox）
  - **闭环（CLOSED）**：核心业务对象生命周期完整且被下游消费或可达终态；**OPEN**：有产出/中间态但无下游消费或无法到终态；**READONLY-OK**：定位即只读（调阅/检索/证据/看板/驾驶舱）
- **外部真实依赖未联调**（OIDC/MFA、CA/TSA、LIS/PACS/医保/设备网关/直报、真实模型、消息总线、扫描采集网关等）＝「外部边界未接」：页面普遍 fail-closed 如实标注，属系统级边界而非页面 demo；但依赖该外部源的“业务闭环”在该处标 OPEN

> 实施后更新（2026-09-05/06）：以下主体表格是 09-04 的发现快照，保留其当时的行级证据；当前落实状态以 `docs/process/planning/2026-09-04-menu-gap-remediation-plan.md` §0.1 为准。T1.1–T1.4、T2.1–T2.5、T3.1–T3.7、T4.1–T4.9、T5.1–T5.3、T6.1–T6.5、T7.1–T7.4 与 T8.1–T8.3 的本地修复已实施或按真实边界诚实收口。其中特别包括：历史迁移已补固定输入到观察/归档的状态机；Eva 运行已可进入候选审批；旧门诊文书假模型已隔离，只有 `WAITING_FOR_REVIEW` 才会被表述为可人工复核候选；十个专科 QC、跨层引用与精神心理密级已补真实约束。尚未替代的验收是 Java 21 定向/迁移 E2E 回归，以及需要医院凭据、通道或设备的外部联调；这些仍维持 NO-GO，不能由本地仿真替代。

---

## 一、执行摘要

总体结论：全菜单 198 条路由已无“纯静态假页面”；真实程度分层：真实+闭环 > 真实+本层闭环但跨层断链/只读定位 > generic 配置壳（有真存储无行为消费者）> 仿真菜单（模拟接口，设计如此并如实标注）> 少量残留“演示感”。

仍在菜单上的“演示/仿真/未闭环”问题清单：
1. 演示感残留（少量写死数字/静态壳）：
   - `clinical` 临床业务门户：门诊/住院指标卡与告警为写死常量（`ClinicalPortalPage.vue:43/83`），急诊块才真实
   - `unified-home` 统一首页：纯静态导航壳（16 行无 script），真机访问导向 `/clinical`，“按角色聚合任务”未实现
2. 仿真/外部边界（页面如实标注）：模拟接口菜单整组＝生产化仿真控制面；`pacs-viewer` 影像查看器 fail-closed；`lis-report` 无真实 LIS 入站；`devices/device-monitoring/integration*` 数据源为内置模拟网关/接口
3. 真实但缺闭环集中带：执行中心（收费/门诊药房/病理/治疗/麻醉/监护）、数据中心（集成映射/消息/迁移/数据质量/科研导出与图表）、AI 中心（评测/agent 配置壳/运行页/预算）、任务与 Eva 审批链、业务配置 6 壳（配完即止）、知识中心发布衔接与图谱数据
4. 专科深页（70 条全 real）：10 个 QC 复核层“名义复核”（ID 手填、后端零存在性校验）；treatment/evidence/care 的“高风险进复核”文案普遍未接线；眼科术前→术后、精神危机交接→随访、ENT 气道交接→随访跨层断链；多处页眉错贴“妇产/儿科”
5. 外部真实依赖未接统一清单见 §五；模拟接口菜单明细见 §四

行级口径快速统计（细节以各表为准；198 条路由含 70 条专科深页）：
- demo/静态壳：1（unified-home）；clinical 门户为 hybrid
- hybrid（真实后端但外部/演示/模拟数据源或演示轨共存）：约 10 项
- real + CLOSED：约 70 项（临床主线、病历/质控/病案、质量五域、多数执行子域、科研/开源、系统管理核心写链等，均有既有 e2e/报告佐证）
- real + READONLY-OK：约 25 项（工作台/看板/证据/目录/审计页，含 10 专科 workbench）
- real + OPEN（断链/未到终态/无下游）：约 30 项（执行中心 6、数据中心 7、AI 中心 9、系统管理/平台壳 6、知识/配置 7、任务/提醒/策略 5 等）
- 专科层内 OPEN：约 18 层（QC 10 层 + risk_flag 无消费 6-8 层 + 跨层断链若干）；7+ 页模板文案错贴
- 纯仿真菜单：模拟接口整组（17 演练工作台 + 接口总览）

---

## 二、临床域

### 2.1 临床基础 · 门户与患者主数据

| 路由（菜单） | 形态 | 闭环 | 要点 |
|---|---|---|---|
| clinical 临床业务门户 | hybrid | READONLY-OK | 门诊/住院指标写死（:43/83）；急诊块真实（:11-18）；入口真实 |
| unified-home 统一首页 | demo | — | 静态壳；真机导向 /clinical |
| patient-registry 患者检索登记 | real | CLOSED | MPI 候选→合并→时间线别名；报告 c01-p1 |
| patient-merge 重复患者合并 | real | CLOSED | 全状态机+二审+可逆撤销（PatientIdentityService:239-262） |
| patient-timeline 临床时间线 | real | READONLY-OK | 多源聚合只读；报告 c01-t1 |
| emergency-access 紧急访问 | real | CLOSED | 申请→过期→安全管理员独立复核 |
| appointment-registration 预约挂号 | real | CLOSED | 号源/预约/退号/报到/接诊/叫号全落库；报告 e01 系列 |
| admission-bed 入院床位 | real | CLOSED | 入院=就诊+床位+任务同事务，并发唯一约束；报告 i01 |

### 2.2 门诊域

| 路由（菜单） | 形态 | 闭环 | 要点 |
|---|---|---|---|
| outpatient 门诊工作台 | real | READONLY-OK | 服务端聚合 + 叫号（唯一写） |
| opd-record 门诊病历 | real | CLOSED | 草稿→质控→AI 候选→签署版本链，跨页消费 |
| opd-diagnosis 门诊诊断 | real | CLOSED | 术语查库（ICD-10-CN）；终诊门禁消费 |
| opd-orders 门诊医嘱 | real | CLOSED | 签署→执行任务（OrderService:269）；检验/影像/终诊消费 |
| opd-results 检查检验 | real | CLOSED | 危急值 OPEN→已接收→已处置；真实 LIS 未接（外部边界） |
| opd-consult 会诊转诊 | real | CLOSED | SEND 生成任务并结清（ReferralService:258-284） |
| opd-followup 随访终诊 | real | CLOSED | 随访终态+就诊 FINISHED 四重门禁 |

### 2.3 急诊域

| 路由（菜单） | 形态 | 闭环 | 要点 |
|---|---|---|---|
| emergency 急诊工作台 | real | READONLY-OK | 只读实时看板 |
| er-triage 预检分诊 | real | CLOSED | LEVEL_1 立即处置硬门；分区/时限规则未接（fail-closed） |
| er-observation 抢救留观 | real | CLOSED | 去向硬闭环；床位容量未接（fail-closed） |
| er-record 急诊病历抢救 | real | CLOSED | 抢救结局硬闭环；未闭环拦截进审签 |
| er-nursing 急诊护理 | real | CLOSED | 腕带核验门；输液无结构化执行状态机（子缺口） |
| er-handoff 会诊交接 | real | CLOSED | 协同单+三域切换门禁（EncounterDomainSwitchService:37-49） |
| er-* L3-L7 纵深页 | real | READONLY-OK | 真实聚合/门禁推导/证据索引 |

### 2.4 住院域

| 路由（菜单） | 形态 | 闭环 | 要点 |
|---|---|---|---|
| inpatient 住院工作站 | real | CLOSED | 病区/床板/工作队列；报告 i01/r01 |
| inpatient-overview / doc-versions | real | READONLY-OK | 只读看板 / 版本时间轴 |
| inpatient-course / doc-editor / doc-qc | real | CLOSED | 文书编辑-质控-签署（doc-qc 站内无二级入口，深链可达） |
| inpatient-discharge 出院归档 | real | CLOSED | 出院→首页→质控→整改→归档四角色 e2e |
| ip-orders / ip-results | real | CLOSED | 住院医嘱/检验危急值（双模式页按路由分流） |
| ip-consult 查房会诊 | real | CLOSED | 会诊任务闭环；报告 o01 |
| ip-pathway 临床路径 | real | CLOSED | 路径实例/变异证据；报告 i01-clinical-pathway |
| ward 病区护理 | real | CLOSED | 交接班/患者级交接；报告 n01（护理外部证据仍 IN_PROGRESS） |

### 2.5 全院病历中心

| 路由（菜单） | 形态 | 闭环 | 要点 |
|---|---|---|---|
| record 病历工作台 | real | CLOSED | 队列/新建/作废/抽查流转全真实（RecordCenterPage:61-269） |
| record-editor 专注编辑 | real | CLOSED | 保存新版本/质控/签署持久化；“退出并保存”为误导文案 |
| record-sources 来源证据 | real | CLOSED | 附件 SHA-256、来源引用增/撤/作废留痕 |
| record-qc 质控审签 | real | CLOSED | 质控规则在后端（BLOCKING 落库）；“人工缺陷”页内无增删按钮 |
| record-sign 签署确认 | real | CLOSED | 与 record-qc 同组件双模式（页内进入） |
| record-versions 版本证据 | real | CLOSED | 更正/补记/撤签/批量验签写证据 |
| record-diff 版本差异 | real | READONLY-OK | 服务端 diff 只读 |
| lis-report / pacs-viewer | real | READONLY-OK | 结果域真实/报告元数据真实；外部 LIS/DICOMweb 未接，影像查看器 fail-closed |

### 2.6 质量中心 + 病案资产中心

| 路由（菜单） | 形态 | 闭环 | 要点 |
|---|---|---|---|
| quality-center 质量总览 | real | READONLY-OK | 指标由四个真实台账实时计算 |
| department-qc / quality-rating / infection-events / credentials | real | CLOSED | L3-L7 真实读写；院感 CONFIRM/REFUTE 终态必填；资质/评级写链完整；直报网关外部未接 |
| archive-assets / catalog / scan / integrity / borrow / preservation / asset-detail | real | CLOSED | 资产状态机、SHA-256 重读验真、借阅/复制交付、封包/抽检互锁真实；扫描仪硬件/水印交付/生产 WORM 为外部边界（如实标注，未连 mock 后端） |

### 2.7 专科中心 · 十专科 × 七层深页（附录 A）

总体：70 条专科层路由全部 real（无 demo），每层 list/create 均走 specialty(-layers).ts→真实后端（Controller/迁移/审计/ApiTest），主记录与关键照护层有 s01-s10 报告闭环与硬门。共性缺口：QC 复核“名义复核”、risk_flag 文案未接线、跨层无数据级衔接（眼科术前→术后、精神交接→随访、ENT 交接→随访等）、多处页眉错贴。

---

## 三、平台中心 / 配置与运维菜单

### 3.1 诊疗执行中心

| 路由（菜单） | 形态 | 闭环 | 要点 |
|---|---|---|---|
| care-operations 执行总览 | real | CLOSED | 体征/护理计划/五对给药等真实终态 |
| billing 费用结算 | real | OPEN | 无医嘱/执行自动计费、无结算/支付终态 |
| outpatient-pharmacy 门诊药房 | real | OPEN | 无门诊处方来源与审方（仅住院强制 order 关联） |
| inpatient-pharmacy 住院药房 | real | CLOSED | 摆药只可挑已签医嘱+服务端剂量/数量校验+人职分离 |
| lab-workbench 检验 | real | CLOSED | 标本选 LAB 医嘱项创建；完成回写执行→结果收口 |
| pathology-workbench 病理 | real | OPEN | 通用执行病例与病理申请/标本两表割裂，完成无报告下游 |
| imaging-workbench 检查影像 | real | CLOSED | 自建+医嘱双路径落库；真实 PACS 未接 |
| therapy-workbench 治疗 | real | OPEN | 页面执行不回写治疗医嘱任务→队列恒 PENDING |
| surgery-schedule 手术 | real | CLOSED | 排程→time-out→完成+术者≠麻醉；核查非双人操作强制 |
| anesthesia-workbench 麻醉 | real | OPEN | 手术关联仅文本无外键；完成不回写手术/监护/复苏 |
| transfusion 输血 | real | CLOSED | 双人核验自核验拒绝+反应收口；血库发血上游未接 |
| device-monitoring 设备监护 | hybrid | OPEN | 遥测来自模拟网关（:161 自述）；无告警处置动作 |

### 3.2 任务中心 + AI 助手

| 路由（菜单） | 形态 | 闭环 | 要点 |
|---|---|---|---|
| clinical-tasks 任务中心 | real | OPEN | 六视图真实落库（真机：15 待处理/14 危急高风险/8 团队）；COMPLETED 仅来源回写、EXPIRED 无守护、委派到期不自动收敛、OUTBOX 恒 PENDING |
| ai-assistant AI医助 Eva | real | OPEN | worker 异步执行真实；产出止于文本候选 WAITING_FOR_REVIEW，无自动进入候选动作审批 |
| ai-action-review 候选动作审批 | real | OPEN | 审批/核验状态机真实；仅手工提案方、无菜单入口、“执行”仅核验台账无真实副作用 |
| ai-reminder-detail 提醒证据 | real | OPEN | 无自动产出方；提醒→任务转换无消费端；source_task_id/#/reminders 未闭合、枚举错配 |
| ai-capture 医助运行证据 | real | READONLY-OK | 只读采集真实医助运行、模型调用、工具链与审计链；不提供转写功能。 |
| ai-assistant-policy Eva 策略 | real | OPEN | 运行时硬消费策略（单例 ACTIVE+限流+快照锁定）；页面可产草稿过不了运行时门禁，仅种子策略可跑 |

### 3.3 模型 / Agent / 运行运维

| 路由（菜单） | 形态 | 闭环 | 要点 |
|---|---|---|---|
| models 基座模型 | hybrid | CLOSED | 真实轨注册→连接→发布→Eva 执行闭环；.example 演示轨共存（:27/297）；页面文案“测试连接为模拟”已过时（34ac310 后仅 .example 免网络） |
| model-connection 模型连接 | real | CLOSED | 真实检测台账，READY 被发布与运行时消费 |
| model-routing 模型路由 | real | READONLY-OK | 门禁核对页与服务端一致 |
| model-evaluation 模型评估 | real | OPEN | “通过”只置 EVALUATING 永不批准；发布只查连接 READY→评测旁路 |
| agent-catalog 医助团队 | real | OPEN | 停用阻断新任务；UI 新建团队无 release/composition 行不可运行 |
| agent 医助运行 | real | OPEN | 读遗留 ai_run；UI 无创建入口、Eva 任务不落此表、文案错指 |
| agent-compose / agent-context | real | OPEN | 配置真实入库但无运行时消费者（编排走独立种子表） |
| tool-catalog / skill-catalog | real | OPEN | 自定义工具无适配器不可执行；skill 仅存在性门禁 |
| agent-evals 评测发布 | real | OPEN | 运行时门禁真实；UI schema 与门禁键错位，正向只能 SQL 种子达成 |
| aiops 运行监测 | real | CLOSED(附注) | 用量真实自采集；预算编码强约定 + 无运行时超限熔断 |

### 3.4 数据中心

| 路由（菜单） | 形态 | 闭环 | 要点 |
|---|---|---|---|
| data-center 数据总览 | real | READONLY-OK | 指标由服务端快照计算写审计链 |
| integration 集成总览 | hybrid | READONLY-OK | 连通性测试打到 mock 仿真端点 |
| integration-connectors 连接器 | hybrid | CLOSED(仿真边界内) | 元数据真实；下游采集按 system_type 映射模拟接口码 |
| integration-mapping 字段映射 | real | OPEN | 集成收发/转换不消费 mapping |
| integration-messages 消息追踪 | hybrid | OPEN | 仅人工仿真采集；无业务 producer/consumer、FAILED 不可达 |
| migration 历史迁移 | hybrid | CLOSED（受控输入范围内） | 受控 CSV 固定输入、患者匹配、试迁、对账、零差异切换、观察与归档均有持久状态、并发版本控制、审计和 Outbox；检查点支持单调续跑。当前不宣称已完成医院外部源的真实抽取/转换，后者仍需逐院联调与迁移 E2E。 |
| data-quality 数据质量 | real | OPEN | 仅 6 白名单规则可跑；注册规则可配不可跑；无调度 |
| devices 设备接入 | hybrid | CLOSED(仿真边界内) | 目录/遥测域内闭环；源=模拟网关；无跨域临床消费 |
| research 科研项目 | real | CLOSED | 项目登记→停用真实 |
| research-stats 科研统计 | hybrid | OPEN | 4 卡片真 count；趋势图为 dev fixture |
| cohort-builder 队列构建 | real | OPEN | 判据真 SQL 物化；无批量筛库端点 |
| research-dataset 数据申请 | real | OPEN | 状态机真实；“导出”只翻状态，无文件/脱敏/快照关联 |
| opensource 开源指标 | real | CLOSED | 快照/下载事件+机器人判定闭环（数值人工录入） |

### 3.5 知识中心 + 业务配置

| 路由（菜单） | 形态 | 闭环 | 要点 |
|---|---|---|---|
| knowledge-center 路径知识库 | real | OPEN | 状态机真实；“审批”只物化 CLINICAL_PATHWAY 配置 DRAFT，无自动衔接住院执行（dev 由 importer 直插绕过） |
| pathway-graph 知识图谱 | real | OPEN(NEEDS-MANUAL) | 端点/渲染真实；图谱表无生产写入方→恒空 |
| pathway-review 审核队列 | real | OPEN | 队列/审批真实（审核人≠审批人）；执行断点同上 |
| pathway-versions 版本历史 | real | OPEN | 版本/退役真实；退役不回滚已物化配置 |
| workflow 流程设计 | real | OPEN | 配置+运行时引擎真实；产出不被临床模块消费（“配完即止”）；画布非拖拽 |
| form-designer / rule-center / scope-designer | real | OPEN | 各引擎真执行；表单无病历引用、规则未接安全链、范围未被授权执法读取 |
| capability-pack 能力包 | real | OPEN | 状态机真实；依赖/冲突/就绪度为前端本地、conflicts 写死行；无门禁执法 |
| specialty-coverage 科室适配 | real | CLOSED | 声明→专科路由门禁唯一真实闭环；expires_at 执法未见 |
| config-release / config-upgrade | real | OPEN | 生命周期真实；产出为记录型台账，发布/升级机制不消费其 payload |

### 3.6 系统管理 + 平台运维

| 路由（菜单） | 形态 | 闭环 | 要点 |
|---|---|---|---|
| admin 管理工作台 / admin-audit 管理审计 | real | READONLY-OK | 接口实时聚合 / 审计链查询导出 |
| admin-org / admin-users / admin-roles / admin-permissions / admin-dictionaries / admin-templates | real | CLOSED | 各写链真实（乐观锁/审计/职责分离）；模板被文书 resolveTemplate 消费 |
| admin-auth 认证安全 | hybrid | CLOSED(dev) | 主形态真实（auth-* 参数 dev 登录强制执行）；生产到 OIDC JWT 验签+ACR(MFA)，真实 IdP 外部 |
| admin-master-data 主数据 | real | OPEN | CRUD 闭环但无管理域外运行时消费 |
| admin-parameters 系统参数 | real | OPEN | 仅 dev 会话 auth-* 键被消费；无 AI 开关类消费 |
| admin-jobs 通知任务 | real | CLOSED | 配置壳+真实进程内调度器（治理检查→findings→处置）；“治理智能体”为规则引擎 |
| install / backup / operations / release-gates | real | OPEN | generic 配置壳：真实存档，无执行器/消费方；backup 自限合成库 |
| login-context 登录 | real | CLOSED(dev) | 真实服务端登录（bcrypt/锁定/可撤销会话）；生产 OIDC 外部 |

---

## 四、模拟接口菜单

性质：生产化仿真控制面。17 个演练工作台共用同一受门禁 mock 引擎（强制 ACTIVE+VALID+APPROVED profile、拒绝真实 PHI、SYNTHETIC_ONLY、无出网），运行/证据/审计/outbox 持久化，仿真内部闭环（含 DEGRADED/UNAVAILABLE 失败留证）。

| 子菜单 | 要点 |
|---|---|
| mock-interfaces 接口总览 | 「调用」不传 profile_key 恒 422，仅可浏览 |
| admin-auth | 与主菜单“认证安全”同一组件双路由（SIMULATION 页） |
| ai-capture / model-connection / model-routing | 已被 V314 归档退役但未从菜单摘除→“配置未启用”阻断；主菜单对应页已是真实实现 |
| devices / device-monitoring / integration-* / archive-scan / archive-preservation / pathology / anesthesia / therapy | 仿真确认、闭环完整；与主菜单真实模块互不调用（仅 device-monitoring、integration-messages 把 mock 引擎当数据源且合成结果落真表无标记） |
| malware-scan / cda-validation / direct-report / empi | 仅存在于本菜单的纯演练 |
| 注意 | 预置 ACTIVE profile 14 个；integration-connectors ACTIVE 锁死 LIS_RESULTS 换选必 409；历史自查时 UI 无法演示同键重放，现已补同键重放与运行证据；工作台总数为 17。 |

---

## 五、外部真实依赖未接统一清单

1. 真实 OIDC/MFA/CA/密钥托管（生产到 JWT 验签+ACR 强制，授权码/IdP 仓外）
2. CA 电子签名/可信时间戳/证书吊销/KMS/HSM/加密备份
3. HIS/EMPI/医保/处方流转/合理用药/LIS/RIS/PACS(DICOMweb)/病理/手麻/床位主数据/消息总线
4. 扫描采集网关(TWAIN/ISIS)、设备网关(HL7/IOMT)、遥测心跳
5. 直报/区域转诊/HIE（仅结构/时限/回执模型）
6. 真实大模型（凭据/制品哈希/引擎/硬件/批准阈值/20+ 正式评测/临床盲审/红队）
7. 支付/结算渠道、病案复制物理交付、备份离线副本

---

## 六、建议与后续

按缺口类型给出（详见配套 `docs/process/planning/2026-09-04-menu-gap-remediation-plan.md`）：
1. 演示感清理（门户指标接真/unified-home 收口/文案错贴与误导文案）
2. 执行中心闭环（自动计费、门诊审方、病理/治疗/麻醉接上游、监护告警处置）
3. 数据中心闭环（集成接线、数据质量规则放开、迁移真迁、科研真导出与图表）
4. AI 中心闭环（双人发布审批、评测→发布链路、composition/context 运行时权威化、Tool/Skill 适配器、Eva→审批→执行自动接线、预算熔断）
5. 任务/提醒闭环（EXPIRED 自动收口、提醒链路闭合、审批入口）
6. 知识/配置消费（路径发布衔接、图谱写入方、rule/form/scope 接业务域、能力包执法）
7. 专科 QC 与跨层衔接（复核真实化、跨层引用、risk_flag 消费、密级覆盖）
8. 菜单/文档收口（模拟接口退役项、mock 总览、文档漂移）
9. 外部依赖独立轨道（持续 NO-GO）

---

## 附录 A：十专科 × 七层明细

约定：- = CLOSED；Ⓡ = READONLY-OK（workbench）；OPEN = 真实但断链；QC 10 专科均“名义复核”（ID 手填、后端不校验、FAIL 无整改下游）。

| 专科 | record | workbench | treatment | evidence | care | followup | qc |
|---|---|---|---|---|---|---|---|
| 妇产 | - | Ⓡ | -（分娩硬门） | -（产前检查） | - | - | OPEN |
| 生殖 | - | Ⓡ | -（移植双人核验） | OPEN（文案无消费） | - | - | OPEN |
| 儿科 | - | Ⓡ | OPEN（risk_flag 无消费） | OPEN | -（生长） | - | OPEN |
| 新生儿 | - | Ⓡ | OPEN（文案无消费） | -（筛查） | -（腕带双人） | - | OPEN |
| 精神心理 | -（RESTRICTED） | Ⓡ | - | - | -（危机交接） | -（不引用交接单） | OPEN |
| 眼科 | - | Ⓡ | -（术前核对） | - | - | OPEN（不引用术前核对） | OPEN |
| 耳鼻喉 | - | Ⓡ | - | - | -（气道交接） | -（通用随访） | OPEN |
| 口腔 | OPEN（无 ACTIVE→COMPLETED） | Ⓡ | -（材料批次） | OPEN | OPEN | -（页眉错贴） | OPEN |
| 皮肤 | - | Ⓡ | -（筛查放行） | OPEN | OPEN | OPEN（不校验已筛查） | OPEN |
| 中医 | -（毒性饮片门） | Ⓡ | -（方药门） | -（四诊门） | OPEN | -（页眉错贴） | OPEN |

附录说明：主记录与关键照护层均有 s01-s10（08-21/22）报告 LOCAL_VERIFIED 佐证；care/evidence/treatment 若干层无独立报告但代码硬门+ApiTest 在；全部专科页无 mock/写死。
