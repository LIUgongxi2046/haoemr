# HaoEMR v1.0 全栈架构评审与风险排查报告（TRB Report）

## 0. 评审控制

| 项 | 结论 |
|---|---|
| 评审模式 | `DESIGN_REVIEW`；审查目标设计是否足以进入 S007/S008，不把原型覆盖计为生产实现 |
| 输入基线 | PRD v0.15、Prototype v0.13、UI v1.2.0、HLD v0.15、LLD-DATA/BACK/AGENT/FRONT S005 契约补全版、当前 React 门诊/住院/病案纵切代码与 S01–S10 Backlog |
| 已执行证据 | 2026-08-20 复核：FR/AC/路由追踪 138/138 `VERIFIED`；原型 194/194、专科 70/70、新增专科页 30/30；UI 审计 194 截图、3 资产、205 页资产链接、零错误/警告 |
| 决议范围 | 是否允许进入完整研发规划与分批编码；不等于功能完成、评级达标或允许真实医院生产上线 |
| 最终决议 | **CONDITIONALLY APPROVED FOR ENGINEERING**；目标设计可进入 S007/S008，完整产品与真实医院生产仍为 **NO-GO** |

## 1. 前端与后端契约握手审查

### 1.1 已发现并修复

| ID | 原错位 | 后果 | 等级 | 修复证据 | 状态 |
|---|---|---|---|---|---|
| FB-001 | 前端 `ClinicalContextLease` 使用单个 `roleAssignmentId`，Agent 使用 `role_assignment_ids`，且组织、用户和数据边界字段不齐 | 多岗位授权丢失或扩大；切机构/患者时可能复用错误租约 | P0 | FRONT 4.1 与 AGENT 2.2 统一 tenant/organization/facility/user/roles/patient/encounter/task/purpose/source/time/classification/residency/watermark/expiry | FIXED |
| FB-002 | FRONT 写 `POST /ai/runs`，BACK 未定义 AI 创建、快照、取消和候选决策 API | 浏览器只能“假流式”，断线、批准和超时无法恢复 | P0 | BACK 4.2 固化 `202 POST /api/v1/ai/runs`、snapshot/cancel/decisions；FRONT 6.1 同步 | FIXED |
| FB-003 | BACK 只有通用 `task.state.changed`，FRONT 期望 state/reference/proposal delta，但没有同一事件 envelope | 重复、乱序或保留窗断档会造成 UI 错状态、重复建议或一直转圈 | P0 | BACK/FRONT 统一 `ai.run.event`、5 类 `event_type`、`event_id/run_id/sequence/schema_version/watermark/lease_id` 和 `SNAPSHOT_REQUIRED` | FIXED |
| FB-004 | 后端线字段使用 snake_case，前端类型使用 camelCase，未声明转换责任 | 生成客户端与手写对象混用，字段会静默为 undefined | P1 | FRONT 明确线格式仅 snake_case、组件禁止消费原始 JSON、生成 Zod codec 显式映射并拒绝未知版本 | FIXED |
| FB-005 | HLD 已选 Vue 3，老 FRONT 仍按 React/TanStack Router/Zustand 设计 | S007 可能继续扩展 React，形成双路由和双患者上下文 | P1 | HLD ADR-008 与 FRONT 1.2/5.3/6.2/9.4 统一 Vue 3 + Vue Router + Pinia + TanStack Vue Query，定义单入口、逐切片对等和删除 React 门禁 | FIXED-DESIGN |

### 1.2 异常和降级对齐

- `429/5xx/timeout` 不能映射成一个永久 spinner；`PageDataState` 与 `CommandState` 可表达错误、冲突、阻断、外部等待和对账。
- 签署、医嘱、给药和配置发布超时后保留同一幂等键，先查询最终状态；前端不能给出“再试一次”并换新键。
- SSE 只传完整 UTF-8 JSON frame；客户端按事件 ID 去重、按 sequence 检查断档，保留窗外先拉快照。
- AI、LIS、PACS、搜索或 SSE 失效只降级对应边界；手工 EMR 主链仍需完成。

## 2. 后端与 Agent 边界握手审查

### 2.1 已发现并修复

| ID | 原错位 | 后果 | 等级 | 修复证据 | 状态 |
|---|---|---|---|---|---|
| BA-001 | AGENT 规定有界重试，BACK 只说 Worker 池隔离，没有持久预算和强制 Kill | 模型可重复调用同一 Tool，耗尽连接池/Token，副作用重复 | P0 | BACK 5.2 与 AGENT 4.1 固化模型/Tool/Token/Deadline 预算、同参哈希、最多 2 次总重试和 fencing token | FIXED |
| BA-002 | `AIRunState` 与 `durable_job.state` 都叫状态但语义不同 | Worker 的 `FAILED_RETRYABLE` 可能直接泄露到 UI，破坏前端状态机 | P1 | BACK 5.2 明确专用 AIRun 与 Worker Job 分层，只通过不可变 run/job ID 关联 | FIXED |
| BA-003 | Agent 副作用候选有审批语义，但缺少后端决策端点 | UI 点“批准”后只能绕过内核或无法执行 | P0 | BACK 新增 proposal decisions；接受后由 BFF 转领域命令，重新验证身份、版本、规则和幂等 | FIXED |

### 2.2 权限与超时边界

- Tool Gateway 的 `PreToolUse` 不是 Prompt 建议，而是服务端物理门：Schema、租约、用途、角色、患者/就诊、预算、版本和副作用逐项校验。
- 有副作用 Tool 不直接写临床库；只产生 `AIProposal`，人工决策后进入既有领域命令和审计/Outbox 事务。
- 运行创建立即返回 202；模型和 Tool 在独立池执行，不占住临床请求线程。
- 取消、Deadline、紧急停用和 Worker 失租时，迟到结果因 fencing token 失效，已发生外部副作用进入对账而非伪回滚。

## 3. Agent 与数据引擎握手审查

### 3.1 已发现并修复

| ID | 原错位 | 后果 | 等级 | 修复证据 | 状态 |
|---|---|---|---|---|---|
| AD-001 | DATA `ContextReference` 有 `score`，FRONT 无；Chunk JSON 无自身 `content_hash` | 前端无法校验引用，Verifier 与 UI 使用不同证据对象 | P1 | DATA/FRONT 统一 score、hash、retrievalMethod 和 watermarks | FIXED |
| AD-002 | 数据源只有 page/paragraph 示例，运行接口只有 section/page/field，缺少统一精准定位 | 点击引用只能回文档首页，病历质控无法证明具体来源 | P1 | DATA/FRONT 同步 section/page/paragraph/field/bbox；明确 GUIDELINE_CHUNK 与 DOCUMENT_VERSION 的 sourceId 语义 | FIXED |
| AD-003 | Prompt cache 未把知识撤回、权限水位和紧急停用写成失效流程 | 已退休指南或已撤权患者资料仍可能进入后续回答 | P0 | AGENT 2.1 将 policy/knowledge/skill/model/release/watermark 纳入缓存键，并定义封禁旧键、在途过期/对账和新 run 规则 | FIXED |
| AD-004 | 模型配置未声明 `RESTRICTED`，而租约、数据和前端允许限制级病历 | 精神科等限制级数据要么无法合法路由，要么被实现者错误降级成 `SENSITIVE` | P1 | AGENT 2.3 纳入 `RESTRICTED`，并限定仅本地、院内、获批用例、禁止远程回退 | FIXED |

### 3.2 数据血缘闭环

- SQL/exact 负责患者数值、日期、医嘱和确认事实；BM25/Dense/Graph 仅用于受控召回，不能用向量相似度猜测临床事实。
- 搜索、向量、图是可重建读模型，返回结果必须回 PostgreSQL 重新授权；索引命中不等于当前用户可读。
- `ContextReference` 包含不可变版本、locator、内容哈希、检索方式、分数、取数时间和授权水位；点击时再次授权。
- 同一 run 固定 release 集合，不能混用新旧知识；撤回/到期/权限变化使缓存失效。

## 4. 当前设计—实现差距

本节只列可复现事实，不使用主观“匹配度百分比”。`FIXED` 表示设计契约已经修复；`OPEN` 表示生产实现或实测证据仍缺失。

| ID | 差距 | 等级 | 当前事实 | 关闭条件 | 状态 |
|---|---|---|---|---|---|
| DR-001 | 限制级数据与模型路由枚举不一致 | P1 | LLD-Agent 已增加 `RESTRICTED` 本地硬门 | 合约测试覆盖允许、拒绝和无远程回退 | FIXED-DESIGN |
| DR-002 | 科室支持等级、专科包发布清单需要生产实现 | P1 | V6 已实现科室、专科包、支持声明表；71 个专科入口现由 Vue 读取真实支持评估并对未声明、待发布、不支持和证据过期默认阻断 | 支持级别、发行 manifest、各专科真实 API/异常恢复/临床 E2E 和科室签字一致 | PARTIAL |
| DR-003 | 高保真 UI 与生产 Web 不同源 | P1 | 生产 Web 已统一为 Vue 3：14 个生产纵向路由原生实现，71 个专科路由使用真实支持守卫，109 个规划路由安全标记未开放；React runtime 已退出 | 剩余规划路由按 Backlog 逐项完成真实业务、响应式/a11y/视觉回归和临床验收，不把注册页当成功能实现 | PARTIAL |
| DR-004 | 生产配置矩阵与 fail-closed 未完整实现 | P1 | F01-C2 已让 prod 在上下文创建前校验数据库、OIDC/MFA、CA/时间戳、KMS、对象存储、集成和可选 AI；`env://`/`file://` 引用、profile 隔离、10 个配置测试和实际缺配置启动失败均通过 | 远端 CI 首跑和预生产 Secret Manager/挂载轮换演练；真实适配器仍由 C01/R01/X01 取证 | LOCAL_VERIFIED |
| DR-005 | 138 FR 的大部分仍是 PRD/原型目标 | P1 | 门诊病历/AI 纵切及住院入院/床位/病区清单/时限任务首切已实现；完整住院、医嘱、护理、急诊、病案、数据中心、集成等尚未形成生产闭环 | 每项逐步具备代码、API、迁移、自动测试和验收证据 | OPEN |
| DR-006 | 194 路由浏览器审计尚未固化到持续集成 | P2 | `verify-browser-routes.mjs` 已进入 CI；本机真实 Chromium 审计 194/194，H1、唯一一级导航、横向溢出、控制台、失败响应和未知深链全部通过 | 远端 CI 首次成功并持续产出审计 artifact | LOCAL_VERIFIED |
| DR-007 | S/M/L 容量和恢复目标尚未实测 | P2 | HLD 为容量公式与门槛，非医院负载事实 | 1.5 倍目标负载、故障注入、恢复验真与原始报告替换假设 | OPEN |
| DR-008 | 正式身份、电子签名、加密、限流和真实模型红队未完成 | P0-RELEASE | 当前安全报告明确真实医院生产 NO-GO | S010 P0/P1 清零，独立环境验证 OIDC/MFA、CA/时间戳、KMS、限流与临床 AI 金标/红队 | OPEN |
| DR-009 | React→Vue 3 迁移尚无完整退场证据 | P1 | U01-V1–V5 已本地验证：Vue 唯一入口/Router/Context/Query/codec；14 个已实现纵向路由原生 Vue；React 源码、依赖、插件、适配层和 bundle runtime 为零，`check:no-react` 阻断回归 | 远端 CI 首次确认同一依赖扫描、构建与 194 路由审计 | LOCAL_VERIFIED |
| DR-010 | S005 定义了完整契约字段，但全量机器制品未生成 | P1 | 2026-08-20 已从 migration、OpenAPI、治理源和两份路由 CSV 确定性生成 702 字段、62 API、16 错误、2 事件、4 Agent、5 Tool、194 路由契约；本地 contract/check/root verify 全绿 | 远端 CI 首次执行仍须确认；运行时事件续传/乱序消费在 A01/U01 分别取证 | LOCAL_VERIFIED |
| DR-011 | DeepSeek 开源模型尚无当前硬件/权重/用例的可行性数据 | P2 | AGENT 9.4 已定义 adapter 和 harness 评测维度，未做任何生产能力宣称 | S009 锁定模型/量化/引擎/硬件/黄金集，生成质量、延迟、显存、安全、恢复报告 | OPEN |

## 5. TRB 决议

**CONDITIONALLY APPROVED FOR ENGINEERING**：PRD、原型、UI、HLD 和四类 LLD 之间未发现仍未裁决的设计级 P0 冲突，可以进入 S007 规划和既有 Backlog 的 S008 分批编码。DR-002 至 DR-011 必须留在可追踪工单中；尤其先固定 DR-009 Vue 迁移批次和 DR-010 机器契约，不允许无契约扩展新页面。

**真实医院生产仍为 NO-GO**。当前证据不表示：138 项功能已经实现、所有科室已经生产验证、达到电子病历应用水平 4 级、通过网络安全测评，或可以接入真实患者数据。生产 Go 必须在真实代码、数据库迁移、接口联调、浏览器自动化、恢复环境、临床评审和试点数据中重新取证。

### 5.1 编码阶段强制行动项

| ID | 行动 | Owner 阶段 | 验收 |
|---|---|---|---|
| ACT-001 | 从同一 OpenAPI/JSON Schema 生成 Java DTO、TS codec、SSE 事件与错误字典 | S007/S008 | 无手写重复枚举；consumer/producer contract 全通过 |
| ACT-002 | 先实现临床纵切：患者→就诊→病历草稿→质控→签署→审计/Outbox→恢复验真 | S007/S008 | 非 Happy Path、并发、幂等、错患者和断电恢复测试 |
| ACT-003 | 实现 ContextLease 与患者切换物理隔离，再接 AI；AI 模块默认可停用 | S008/S009 | 跨患者/旧标签/撤权/断流红队泄漏=0 |
| ACT-004 | 实现 durable job、预算、fencing、SSE sequence/snapshot，再开放副作用候选 | S008/S009 | 重复、乱序、迟到回调、Worker kill 不产生重复副作用 |
| ACT-005 | 将“全部科室”拆成可发布支持级别，10 个核心专科逐包取证，未验证科室不宣称完整支持 | S007/S009 | 科室适配矩阵与发行 manifest 一致 |
| ACT-006 | Alpha 压测和隔离恢复演练后替换 S/M/L、SLO、RPO/RTO 假设 | S009/S011 | 报告、原始日志、失败处置和签字证据齐全 |
| ACT-007 | S010 完成威胁建模、供应链/依赖/秘密/PHI/Agent 红队，P0/P1 清零 | S010 | 安全门禁报告与可复现测试 |
| ACT-008 | 把 194 路由浏览器审计、深链恢复、图标和无溢出校验固化到 CI | S008/S009 | LOCAL_VERIFIED：脚本与 CI job 已入库，本机 194/194 零失败；远端首跑待确认 |
| ACT-009 | 落地科室支持等级与专科包发行清单；未验证科室在产品内明确显示能力边界 | S008/S009 | DB/API/UI/发行 manifest 一致，禁止虚假“全科室已验证” |
| ACT-010 | 将 U01 拆为 Vue 3 壳层、合约 codec、核心纵切、剩余路由、React 退场五个可逆批次 | S007/S008 | LOCAL_VERIFIED：U01-V1–V5 完成，单入口/单 Context，最终源码、依赖和 bundle 无 React |
| ACT-011 | 生成 S005 全量机器契约并纳入 producer/consumer CI | S007/S008/S009 | LOCAL_VERIFIED：字段/API/事件/Tool/路由集合已双向校验，生成漂移进入既有 CI；待远端 CI 首次运行确认 |
| ACT-012 | 建立 DeepSeek/其他本地模型的固定可复现 harness，只按用例批准 | S009 | 固定制品、黄金集、红队、延迟/资源和故障恢复证据 |

## 6. 未决但不阻断进入编码的问题

1. 社区版已选择 `AGPL-3.0-only`，商业授权、CLA + DCO 与商标政策文件已落地；CLA 文本和第三方医学术语/知识资产许可仍须在首次接收外部代码或公开发行前完成法律复核。
2. 团队人数、真实 180 天产能和首家试点医院未输入；S007 应按能力切片排程，不把 138 项全部虚构成已能在 180 天完成。
3. UI 的 194 个高保真页面与生产 Vue 路由已建立同一注册表，但仅 14 个纵向路由已有真实业务实现、71 个专科入口只有支持守卫；其余页面必须按 Backlog 逐项取证，不能把路由注册或迁移完成视为业务完成。
4. “电子病历 4 级产品能力前提”不等于医院现场评级。接口覆盖、数据质量、使用范围、制度和现场证据均需院方共同完成。
