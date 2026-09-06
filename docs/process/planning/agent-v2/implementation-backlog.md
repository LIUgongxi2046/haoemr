# HaoEMR Medical Agent Harness 实施 Backlog

> 模式：`BACKLOG`  
> 计划日期：2026-08-25  
> 计划状态：本目录所有任务均为 `PLANNED`  
> 产品输入：`docs/product/prd/2026-08-25-haoemr-agent-optimization-prd.md`
> 架构输入：`docs/design/architecture/2026-08-25-haoemr-medical-agent-harness-lld.md`
> UI 输入：`docs/design/ui-delivery/ai-medical-assistant-v2/`

## 1. 交付结论

本 Backlog 将 Agent 优化版拆成可独立实施、验证和回滚的工作包，不把 5 个主 Agent 当作 5 个大 Prompt，而是分为：

- 6 个必须留证的产品/架构决策门禁；
- 14 个 Medical Harness 公共能力任务；
- 5 个主 Agent 基座任务 + 5 个家族组合发布任务；
- 33 个子 Agent 独立工作包；
- 5 个“AI 医助小南”UI 纵向切片任务；
- 4 个首链试点任务；
- 6 个独立授权的发布任务。

完整 DAG 见 `task-dag.csv`，33 个子 Agent 的精确输入、Tool、Schema、阻断条件和 DoD 见 `child-agent-task-cards.md`，第一个 S008 实施上下文见 `s008-first-slice-context.md`。

## 2. 范围、假设与非目标

### 2.1 本轮范围

- 建立兼容现有单次 `AgentRunService` 的 Medical Harness，逐步接入结构化 Tool Loop 和声明式主子 Agent 编排。
- 把 5 个 P0 主 Agent 固化为不可变 Release，把获批的子 Agent 固化为深度不超过 1 的 Composition Release。
- 保持 AI 候选与临床事实物理分层；任何写操作仍走 Proposal→Approval→Domain Execution。
- 将主/子 Agent 实时状态、交接、来源和贡献接入小南任务工作台。
- 建立独立 Eval、Composition Eval、跨患者红队、回放和灰度门禁。

### 2.2 当前工程基线（只读观测）

- 现有 `AgentRunService` 是文书绑定、同步、单次模型调用；还不具备 Tool Loop、child run 和 Composition Runtime。
- `AgentRegistry` / `SkillRegistry` / `ToolRegistry` / `AgentDependency` / Prompt Release / Model Catalog 已存在，但不能表达完整 Release Manifest 和家族组合。
- `ai_run_event` 已是可演进的追加式证据；`ai_run` 仍强制绑定 document，没有 root/parent/release/composition 定位。
- ContextLease、Proposal、Approval、Execution、Audit、Outbox 可复用；当前仍需 Agent 作用域和子运行授权收窄。
- 前端已有 Agent Catalog / Compose / Run / Evals / Context 页和全局小南入口，但未按 v2 交付图接入真实主子运行。

### 2.3 计划假设

- 推荐但不代替业务批准：首链优先住院查房，因为已有住院事实链、文书、结果、任务和护理数据基础。
- 未获批前，33 个均作为“候选子 Agent 工作包”；`DEC-002` 可将某项改判为 Skill / Tool / DEFER，此时保留 task ID 并改变实施类型，不删除追溯。
- 默认不允许跨家族直接挂载子 Agent；如需复用住院每日事实，优先固化为受控 Skill/输入制品，或引用已完成上游运行，不让一个 child release 拥有两个模糊 parent。
- 计划中的预算是 LLD 建议上限，不是已证实性能 SLA。

### 2.4 非目标

- 不在计划阶段改动业务代码、数据库或外部环境。
- 不引入微服务、ORM、通用 SQL/HTTP/文件/命令执行 Tool，不允许模型动态生成编排脚本。
- 不自动签署、落临床事实、确认诊断/危急值/处置、发送消息或关闭任务。
- 不把本机合成演示或状态表验证表述为真实医院集成已就绪。

## 3. 决策门禁

| ID | 决策 | 责任方 | 阻断范围 | 可用证据 |
|---|---|---|---|---|
| `DEC-001` | 首链是住院查房、门诊接诊、结果闭环还是出院准备 | 产品委员会 + 试点科室 | `PILOT-*` | 签字的 use-case brief、样本和 KPI |
| `DEC-002` | 33 个候选逐个判定 `CHILD_AGENT/SKILL/TOOL/DEFER` | 产品 + 临床 + 架构 + AI 治理 | 对应 `CH-*` 上线 | R0 决议表、独立自治性/评估价值证据 |
| `DEC-003` | 模型路由、数据驻留、离院边界和降级策略 | AI 治理 + 隐私 + 安全 | `PLT-008`、真模型 Eval、灰度 | 获批 ModelRoutePolicy 与 DPA/数据分类 |
| `DEC-004` | root/child 预算、p95 目标、轨迹/反馈保留和去标识 | 产品 + 运维 + 财务 + 数据治理 | `PLT-004`、`PLT-012`、生产观测 | 预算表、容量测试和保留政策 |
| `DEC-005` | 首个真实 T3 领域适配器 | 业务负责人 + 架构 + 安全 | `REL-005`写侧灰度 | 建议优先任务创建；需 E2E 审批/执行/审计证据 |
| `DEC-006` | 跨主 Agent 子能力复用语义 | 架构 + 产品 + TRB | `PLT-009` 发布 Composition Manifest | ADR：上游运行引用 vs 共享 Skill vs 特批多 parent |

`DEC-006` 是已识别的真实冲突：PRD 将 33 个 child 分属 5 个主 Agent，LLD 的查房示例却让 `DOCUMENT_DRAFTER` 直接调用 `INPATIENT_DAILY_SUMMARIZER`。在 ADR 批准前，实现不得默认放开多 parent。

## 4. 批次、依赖与临界路径

### 4.1 建议批次

| 批次 | 目标 | 主要任务 | 出口门禁 |
|---|---|---|---|
| B0 | 决策与契约冻结 | `DEC-001..006`, `PLT-001` | 决策有责任人/状态；OpenAPI/error/event 可生成 |
| B1 | 持久化与 Harness 骨架 | `PLT-002..005` | legacy 路径回归不退化；新表有 schema tests |
| B2 | 受控 Tool/Context/Model/Composition | `PLT-006..010` | 跨患者、越权、未批准 Tool 失败关闭 |
| B3 | 验证、轨迹、Eval 和 UI 数据契约 | `PLT-011..014` | 可回放；主子来源可定位；不泄露 PHI |
| B4 | 主 Agent 基座 | `MAIN-*-001` | 5 个 root 只能路由获批 child release；没有隐式写权 |
| B5 | 首链纵向试点 | 默认 `CH-ENC-04`, `CH-DOC-06`, `PILOT-001..004` | 合成患者 UI→API→Harness→Proposal 完整；影子模式通过 |
| B6 | 门诊/住院摘要和文书扩展 | 其余 `CH-ENC-*`, `CH-DOC-*` | 独立 Eval + 组合 Eval + UI 可审阅 |
| B7 | 质控家族 | `CH-QC-*`, `MAIN-QC-002` | 硬规则不可降级；版本错配阻断 |
| B8 | 结果与协同家族 | `CH-RES-*`, `CH-CARE-*`, family integration | 危急值/结果/任务状态只读权威事实；写侧仅 Proposal |
| B9 | 全量 UI、安全和回归 | `UX-001..005`, 全家族 Eval | 5 态、权限、移动端、浏览器路由审计通过 |
| B10 | 候选包和发布 | `REL-001..006` | 每个外部写入点单独授权，有回滚和证据包 |

### 4.2 临界路径

```text
PLT-001 契约基线
  → PLT-002/003/004 Release、Run/Trajectory、Budget 持久化
  → PLT-005 Legacy Harness 兼容
  → PLT-006/007 受控 Context + Tool Gateway
  → DEC-006 + PLT-009/010 Composition + Child Runtime
  → PLT-011/012/013 Verification + Replay + Eval
  → MAIN-ENC-001 / MAIN-DOC-001
  → DEC-002 + CH-ENC-04 / CH-DOC-06
  → DEC-001 + PILOT-001
  → PILOT-002 UI 纵向切片
  → PILOT-003 组合 Eval/红队
  → PILOT-004 影子运行
  → REL-004/005/006 数据迁移、灰度、生产开启
```

决策可与公共基建并行，但不能把“还没批准”隐藏成默认实现。`PLT-001` 是推荐的第一开发任务。

## 5. 公共平台任务卡

### PLT-001 契约与运行语义基线

- 目标价值：让后端、前端、事件、Eval 对 main/child/release/composition/run/outcome 只有一套可生成语义。
- 来源：AOPT-FR-027–031、AC-027–031、NFR-009/011/013–15；LLD §2、§4、§12。
- 当前状态：`PLANNED`。
- 硬依赖：无；`DEC-006` 只阻断 Composition Manifest 发布，不阻断契约骨架。
- 可并行项：`DEC-001..006`。
- 输入上下文：PRD、LLD、`contracts/openapi.json`、`contracts/governance.source.json`、现有 Agent API tests。
- 允许修改：`contracts/`、契约生成产物、相关契约测试。
- 禁止项：不实现运行时业务逻辑；不分别手写 Java/TS 枚举；不删除旧 API。
- 实施动作：
  1. 补齐 AgentRelease、SkillRelease、ToolRelease、PromptRelease、CompositionRelease 的请求/响应 Schema。
  2. 定义 root/parent run、`targetType/targetId`、release pin、child outcome 和 PARTIAL/BLOCKED 语义。
  3. 定义事件 envelope 与对外 SSE 允许词汇，排除 prompt、token 和 PHI 原文。
  4. 为现有 create/get/list 路由保留兼容字段和 deprecation 说明。
  5. 生成 Java/TS/index/error/event 产物并补契约测试。
- 接口/Schema/迁移影响：OpenAPI 与生成产物；本任务不新增 Flyway。
- 测试与验证：`npm --prefix contracts test && npm --prefix contracts run check`；确认 `./gradlew generateContracts` 不产生未提交 drift。
- 安全与隐私：公开事件仅含不可逆 ID/状态/计数/耗时；不把上下文原文放入 error detail。
- 回滚：保留旧 operation/schema，撤回新增契约与生成产物即可；无数据回滚。
- DoD：契约生成可重复；Java/TS 类型同源；旧契约测试继续通过；新 event/error 有测试。
- 输出/交接：更新的 OpenAPI、generated indexes、契约测试和 change note，交给 `PLT-002..014`。

### PLT-002 不可变 Release 与目录持久化

- 目标价值：运行可精确回放到 Agent/Skill/Tool/Prompt/Composition 版本。
- 来源：AOPT-FR-012–17/027/028；LLD §2.1、§11。状态：`PLANNED`。
- 硬依赖：`PLT-001`。可并行：`PLT-003`、`PLT-004`。
- 允许修改：`src/main/resources/db/migration/`、`src/test/resources/schema/`、`src/main/java/org/haoemr/agent/`、相关测试。
- 禁止项：不就地修改已发布 Release；不让 display name 代替 stable code/version。
- 实施动作：设计增量 Flyway；实现 release CRUD/发布状态机；保留 legacy registry 解析；补不可变/唯一 ACTIVE/依赖 pin 数据库硬门；加 API 与 schema tests。
- 测试：`scripts/test-schema.sh`；定向 Agent/Skill/Tool Registry tests；契约生成检查。
- 安全：发布/停用是管理动作，需权限、幂等、审计、Outbox。
- 回滚：代码 feature flag 回到 legacy resolver；迁移仅扩展不破坏，不在生产 down migrate。
- DoD：同一业务编码/版本唯一；已发布不可变；旧运行仍可解析。

### PLT-003 Run/Trajectory/Parent-Child 持久化

- 目标价值：任意 root/child 运行都可追踪、取消、恢复和回放。
- 来源：AOPT-FR-002/003/021/022/029/030；LLD §4、§11。状态：`PLANNED`。
- 硬依赖：`PLT-001`。可并行：`PLT-002`、`PLT-004`。
- 实施动作：扩展 `ai_run` 的 target/root/parent/release/composition/idempotency 字段；解除 document 必填但保留兼容；强化追加式 trajectory；建立投影重建与恢复水位；加数据库和 API tests。
- 允许修改：迁移、agent run/event service/controller/tests。禁止：不把可变状态表当唯一证据；不复制 PHI 到事件 payload。
- 测试：`AgentRunApiTest`，加 parent-child、重放、取消、断点恢复和 schema test。
- 回滚：关闭 new-runtime flag，保留新列/事件；不删历史轨迹。
- DoD：可从事件重建 root/child 投影；旧 document run 读写兼容。

### PLT-004 分层预算与消耗帐本

- 目标价值：防止 root 和 child 超步数、Tool、token、时长或成本边界。
- 来源：AOPT-FR-020/029；NFR-012/014；LLD §2.2、§6。状态：`PLANNED`。
- 硬依赖：`PLT-001`；生产值依赖 `DEC-004`。
- 实施动作：扩展预算维度；定义 tenant∩use-case∩profile∩request 取最小值；记录递增消耗；在每步/每 Tool/每 child 前检查；验证超限终止语义。
- 验证：扩展 `AgentRunBudget*Test`；并发消耗不超限；超限不启动新 Tool/child。
- 回滚：回到 legacy token/duration 策略，保留新消耗证据。
- DoD：root 永远不小于子预算累计消耗；超限有稳定 error/event。

### PLT-005 Harness SPI 与 LegacySingleShotLoop

- 目标价值：建立可扩展 Harness，同时保持已有文书草拟路径可回滚。
- 来源：LLD §1.3、§4.1、Phase 0；NFR-011/013。状态：`PLANNED`。
- 硬依赖：`PLT-002..004`。
- 实施动作：定义 SPI 接口；将现有 model call 适配为 `LegacySingleShotLoop`；按 release 选择 loop；生成标准 trajectory；用 feature flag 支持新旧路径切换。
- 允许修改：`src/main/java/org/haoemr/agent/`及 tests。禁止：不把 Harness 拆为微服务；不顺手重写临床域服务。
- 验证：现有 AgentRun/DeepSeek/OutputGuard tests 全通过；同一 fixture 新旧路径结果语义等价。
- 回滚：关闭 Harness flag 立即回 legacy service；无数据破坏。
- DoD：现有 API 不变；新轨迹可观测；不需 child/tool 也能完成旧运行。

### PLT-006 Scoped Context 与 Capability Lifecycle

- 目标价值：每个 root/child 只看到 tenant/patient/encounter/task 必要数据。
- 来源：AOPT-BR-002/007/026–033；NFR-005/008/014；LLD §2.2/2.3/§5。状态：`PLANNED`。
- 依赖：`PLT-005`。并行：`PLT-007/008`。
- 实施动作：实现 RunScope；将 ContextLease 绑定 root；子运行只能交集收窄；每步校验撤权/过期；事件仅记录 sourceRef 和授权决策摘要。
- 验证：跨 tenant/patient/encounter、过期 lease、运行中撤权、child 扩权全部失败关闭。
- 回滚：关闭新 loop，旧路径继续使用现有 ContextLease；不放宽授权。
- DoD：运行中撤权不再启动新读取/Tool；child scope 始终是 parent 子集。

### PLT-007 Tool Gateway、Risk Tier 与 NoProgress

- 目标价值：模型只能调用批准、分域、最小化 Tool，且无进展时可终止。
- 来源：AOPT-FR-018–20；LLD §8；RISK-014–18。状态：`PLANNED`。
- 依赖：`PLT-005/006`。
- 实施动作：引入 ToolRelease/typed input-output/risk tier；运行 guard pipeline；默认拒绝通用 SQL/HTTP/files/shell；只允许主 Agent 汇总后调用单次 proposal Tool；实现重复调用/no-progress/并发上限。
- 验证：未批准 Tool、schema 错误、越权、重复调用、T3 未审批和幂等冲突测试。
- 回滚：将新 Tool Release 停用；legacy loop 不获得新 Tool 权限。
- DoD：能力列表外调用不可到达领域服务；所有 Tool 有输入/输出/副作用/幂等契约。

### PLT-008 ModelAdapter、Prompt Assembly 与降级

- 目标价值：将 DeepSeek 从硬编码文书 Prompt 改为供应商无关、版本可定位的模型适配层。
- 来源：AOPT-FR-015/016/020/024；LLD §5/§9；`DEC-003`。状态：`PLANNED`。
- 硬依赖：`PLT-005/006`；真模型发布依赖 `DEC-003`。
- 实施动作：定义 ModelRequest/Response/ToolCall；拆分 system/task/policy/context contributor；pin prompt/model release；保留 fake provider；实现 timeout/rate-limit/provider-unavailable 的人工模板降级。
- 验证：DeepSeek transport contract、fake deterministic、prompt injection fixture、provider outage、不同 model route replay。
- 回滚：退回 `LegacySingleShotLoop` 和旧 prompt release；不改变已留存轨迹。
- DoD：没有业务专用 Prompt 硬编码在 provider；每次运行可识别 model/prompt release。

### PLT-009 Composition Resolver 与声明式 DAG

- 目标价值：主 Agent 只执行已批准、无环、深度 1 的组合清单。
- 来源：AOPT-FR-027–031；LLD §3、ADR-MAH-003/004。状态：`PLANNED`。
- 硬依赖：`PLT-002/005/006/007`、`DEC-006`。
- 实施动作：实现 Manifest 解析/版本 pin；验证无环/深度/唯一 parent 策略；解析关键/非关键 child；计算数据依赖与可并行组；冻结 composition release。
- 验证：循环、深度>1、未获批 child、跨 family、废弃 release、schema 不兼容全部失败。
- 回滚：将 composition release 停用并切回前版；不就地修改已发布 manifest。
- DoD：无模型生成脚本；所有 child 都可回溯到固定 release 与 parent edge。

### PLT-010 Child Runtime、取消、降级与恢复

- 目标价值：主 Agent 可安全执行并行 child，区分关键失败与部分成功。
- 来源：AOPT-FR-003/029/030；LLD §3.3/§4.4。状态：`PLANNED`。
- 依赖：`PLT-003/004/009`。
- 实施动作：实现 `SubagentProvider`；继承并收窄 scope/budget/tools；支持有界并行；传播 cancel/deadline；按 key/non-key 映射 `COMPLETED/PARTIAL/BLOCKED`；从事件水位恢复。
- 验证：部分失败、关键失败、取消竞态、deadline、重试幂等和重放不重复执行 Tool。
- 回滚：停用 composition release；legacy/single-agent 运行不受影响。
- DoD：根运行状态是确定性聚合；失败 child 不污染成功 child 证据。

### PLT-011 Verification Pipeline 与 Proposal Guard

- 目标价值：所有输出在展示/候选动作前经历 schema、scope、source、policy 和临床硬门。
- 来源：AOPT-FR-004–11/019；LLD §10；P12。状态：`PLANNED`。
- 依赖：`PLT-006/007/010`。
- 实施动作：将现有 OutputGuard 适配为 verifier；增加 schema/scope/source/policy/domain verifier；定义 finding 与 root outcome 映射；限制 proposal 只由 root 聚合后一次创建；对审批/执行继续使用原领域服务。
- 验证：幻觉来源、跨患者 ref、缺必填章节、硬规则降级、未批准写动作、过期 proposal。
- 回滚：只能回到更严格/旧 guard，不能绕过验证直达领域写入。
- DoD：输出无 sourceRef 时不得伪装成事实；child 无正式动作权。

### PLT-012 Telemetry、SSE、Replay 与保留策略

- 目标价值：医生能看到协作进度，运维能定位步骤，又不暴露推理链或 PHI。
- 来源：AOPT-FR-021–23/030；LLD §4.2、§12.3、§15.3。状态：`PLANNED`。
- 依赖：`PLT-003/010/011`；生产保留依赖 `DEC-004`。
- 实施动作：实现对外事件投影；SSE reconnect/cursor；内部审计指标；脱敏回放器；retention/deletion job 契约；运行指标和告警维度。
- 验证：断线续传不丢/重事件；SSE 不含 prompt/tool raw output/PHI；replay 不产生新副作用。
- 回滚：关闭 SSE 但保留服务端事件；保留审计证据。
- DoD：UI 可通过 cursor 恢复；事件语义与契约一致；可对一次运行重建时间线。

### PLT-013 Agent/Child/Composition Eval 基建

- 目标价值：每个 child 先证明独立价值，再进入主 Agent 组合。
- 来源：AOPT-FR-023/024/027/031；NFR-007/008/014；LLD §15。状态：`PLANNED`。
- 依赖：`PLT-001/011/012`。
- 实施动作：版本化 dataset schema；支持 independent/composition/model-matrix eval；定义来源准确、遗漏、幻觉、误报/漏报、延迟/成本指标；接入红队 fixture；输出可审查报告。
- 允许修改：`evals/`、`security/`、相关测试；禁止真实 PHI 进入仓库。
- 验证：`node evals/check-golden.mjs && node security/check-red-team.mjs`；坏 fixture 能确定失败。
- 回滚：保留旧 golden runner，新 runner 独立 feature/version。
- DoD：每个 release 的报告能定位 dataset/model/prompt/tool/composition 版本。

### PLT-014 UI 数据契约与 33 个子 Agent 展示目录

- 目标价值：UI 从后端 Release Catalog 获取主/子 Agent 中文职责、当前动作和贡献，不再前端维护第二份枚举。
- 来源：AOPT-FR-001/005/030/031；UI `child-agent-experience.md`。状态：`PLANNED`。
- 依赖：`PLT-001/002/012`。
- 实施动作：定义 task workspace/catalog/timeline/contribution schema；将 5+33 display metadata 放入可版本化 release metadata；生成 TS；保留旧入口映射；为状态和权限过滤加契约测试。
- 验证：前端不硬编码可执行 child 列表；旧 Agent code 能映射到新任务。
- 回滚：用 compatibility adapter 继续读旧 catalog；不删旧 UI 入口。
- DoD：设计稿中 33 个职责都有可解析 metadata；用户不会看到未获权 child。

## 6. 主 Agent 基座与家族发布

每个主 Agent 拆为两项：`*-001` 先建只含路由/聚合/停止条件的主运行基座；`*-002` 在对应 child 通过独立 Eval 后发布家族 Composition。这样可以先做首链，不必等齐整个家族。

### MAIN-ENC-001 / MAIN-ENC-002 `ENCOUNTER_SUMMARIZER`

- 目标价值：根据诊前、分诊、接诊、每日、围术期、出院准备路由带来源摘要。
- 来源：FR-007/032，AC-007/032，LLD §6/§7.1。状态：`PLANNED`。
- `001` 依赖：`PLT-008..014`；`002` 依赖：`CH-ENC-01..06`。
- 实施动作：固化 root profile/output schema/budget；实现环节路由和阻断；汇总 facts/changes/gaps/sourceRefs/warnings；对 key/non-key child 定义 PARTIAL；发布通过 Eval 的 Manifest。
- 禁止：不输出诊断或出院决定；不读任务外患者数据。
- 验证：6 个路由、多环节拆分、来源冲突、部分数据和跨患者红队。
- 回滚：停用新 composition release，保留上一 active release。
- DoD：每个用户可见事实可定位 sourceRef；不明环节失败关闭。

### MAIN-DOC-001 / MAIN-DOC-002 `DOCUMENT_DRAFTER`

- 目标价值：按合法文书任务和作者身份聚合一个可逐段审阅的草稿候选。
- 来源：FR-008/033，AC-008/033，LLD §6/§7.2。状态：`PLANNED`。
- `001` 依赖：`PLT-008..014`；`002` 依赖：`CH-DOC-01..10`。
- 实施动作：校验 document task/template/author/version；路由文书 child；聚合 section/source/gap/diff；主 Agent 只调一次 `create_document_proposal`；保留人工模板降级。
- 禁止：不自动保存/签署；不伪造查体、执行、设备、人员或上级意见。
- 验证：10 种文书路由、作者错配、模板过期、缺事实、草稿差异、proposal 幂等。
- 回滚：停用 composition，回人工模板/legacy draft；候选不落事实。
- DoD：只产生一个可审阅 proposal；所有未确认段落可见。

### MAIN-QC-001 / MAIN-QC-002 `RECORD_QC`

- 目标价值：在确定性硬规则之后，按书写/签署前/运行/终末/更正阶段提供语义第二视角。
- 来源：FR-009/034，AC-009/034，LLD §6/§7.3。状态：`PLANNED`。
- `001` 依赖：`PLT-008..014`；`002` 依赖：`CH-QC-01..05`。
- 实施动作：输入不可变版本和硬规则结果；按生命周期路由；保留 hard/AI 分层；输出证据/置信/严重度建议；不创建正式缺陷终态。
- 验证：硬规则不可降级、版本错配、低证据、误报/漏报、更正影响图。
- 回滚：停用 AI QC release，确定性 QC 继续生效。
- DoD：AI 结果永不隐藏/降级硬规则；原文不被修改。

### MAIN-RES-001 / MAIN-RES-002 `RESULT_FOLLOWUP_COORDINATOR`

- 目标价值：聚合新结果、趋势、危急值上下文、待回结果、随访任务和更正影响。
- 来源：FR-010/035，AC-010/035，LLD §6/§7.4。状态：`PLANNED`。
- `001` 依赖：`PLT-008..014`；`002` 依赖：`CH-RES-01..06`。
- 实施动作：引用权威结果/危急值/通知/任务状态；聚合 child；对不可比项并列；生成一个任务计划 proposal；未知 child 不得使 root 完成。
- 验证：未报告≠阴性、单位不可转换、危急值状态缺失、更正结果、任务 proposal 审批边界。
- 回滚：停用 AI 聚合，原结果/危急值领域流程不变。
- DoD：Agent 不生成危急值终态；不把已读当已处置。

### MAIN-CARE-001 / MAIN-CARE-002 `CARE_COORDINATOR`

- 目标价值：为会诊、MDT、转科、出院、随访和任务对账生成可预览协同候选。
- 来源：FR-011/036，AC-011/036，LLD §6/§7.5。状态：`PLANNED`。
- `001` 依赖：`PLT-008..014`；`002` 依赖：`CH-CARE-01..06`。
- 实施动作：校验目标团队/责任人；路由 child；聚合事实/缺口/未决/任务候选；保留各责任方未确认项；所有发送/转派/关闭走原权限与审批。
- 验证：伪造共识/交接、目标不明、过期计划、已读≠完成、任务来源终态。
- 回滚：停用 composition，原会诊/转科/任务流程继续。
- DoD：Agent 只交付候选；没有审批不发生任何外部副作用。

## 7. 小南 UI 纵向任务

### UX-001 任务优先入口与主 Agent 责任感

- 目标：用户从当前临床任务发起，小南自动选择一个负责主 Agent，显示理由并允许更换。
- 依赖：`PLT-014`、至少一个 `MAIN-*-001`。状态：`PLANNED`。
- 允许修改：`web/src/api/`、`web/src/vue/`、generated TS、前端测试。禁止：不重写原型 CSS；不在前端自行决定权限。
- 动作：按 `workflow-integration.md` 接入页内/侧栏/全屏入口；展示主 Agent 责任、选择理由、任务边界；接入 create run；补 loading/empty/error/permission/success。
- 验证：Vitest + Playwright；同一环节只有一个 root main；无权入口不可启动。
- 回滚：feature flag 切回旧小南 catalog。DoD：UI→API→run 可用，5 态齐全。

### UX-002 子 Agent 协作卡、交接与贡献可见

- 目标：用户能明确感受“哪些子 Agent 在帮医生，正在做什么，交给了谁，贡献了哪段结果”。
- 依赖：`UX-001`、`PLT-012/014`、至少一个 child release。状态：`PLANNED`。
- 动作：按 `child-agent-experience.md` 渲染角色/当前动作/贡献；使用 SSE 时间线；显示 main→child→main 交接；对内部技术事件默认折叠；区分独立 verifier 与 child。
- 验证：RUNNING/PARTIAL/BLOCKED/CANCELLED/reconnect；贡献卡可定位 sourceRef/output section；不显示 chain-of-thought/prompt。
- 回滚：降级为 root-only 状态卡，不影响运行。DoD：设计稿中协作信息完整渲染。

### UX-003 来源、差异、未确认项与 Proposal 审阅

- 目标：医生不离开任务即可审阅来源、差异、缺口、硬规则/AI 分层和候选动作。
- 依赖：`UX-002`、`PLT-011`。状态：`PLANNED`。
- 动作：接入 source viewer；文书逐段 diff；未确认/冲突标记；QC hard/AI 分层；proposal preview/approve/reject 跳转原权限流程。
- 验证：过期来源、版本错配、无权来源、部分结果、驳回和重新运行。
- 回滚：只关闭新审阅组件，保留原业务审批。DoD：AI 不直接落事实。

### UX-004 Agent 控制面：Catalog / Compose / Run / Evals / Context

- 目标：治理人员能查看固定 release、Composition DAG、Tool/Skill 权限、Eval 证据和运行轨迹。
- 依赖：`PLT-002/009/012/013/014`。状态：`PLANNED`。
- 动作：升级现有 5 个 Agent 页；显示 release pin/废弃；可视 DAG/关键 child；运行 replay；Eval 报告与发布门禁；ContextLease 只显示脱敏摘要。
- 验证：权限隔离、不可变 release、废弃版本解析、脱敏、空 Eval 不允许发布。
- 回滚：页面读 legacy endpoints，不改临床流程。DoD：管理员可对一次 run 定位全套版本。

### UX-005 状态、权限、移动端和全路由审计

- 目标：确保新 UI 没有遮挡临床动作、权限泄漏、未知状态或浏览器回归。
- 依赖：`UX-001..004`。状态：`PLANNED`。
- 动作：按 `states/state-matrix.md` 覆盖状态；390×844/1280×800 核验；键盘/焦点/读屏语义；路由审计；性能预算和 SSE 降级。
- 验证：`npm --prefix web test`、`npm --prefix web run build`、browser route/comprehensive UI scripts。
- 回滚：全局 feature flag 切回 v1 入口。DoD：5 态+权限+移动端+无横向溢出+无 console/HTTP 错误。

## 8. 首链试点（推荐住院查房）

### PILOT-001 住院查房 Composition Release

- 目标：将一次查房摘要和一份查房文书候选组成第一条可回放的主子链。
- 依赖：`DEC-001`选住院查房、`DEC-006`、`MAIN-ENC-001`、`MAIN-DOC-001`、`CH-ENC-04`、`CH-DOC-06`。状态：`PLANNED`。
- 动作：先固化住院每日事实输出；按 ADR 选择上游运行引用或共享 Skill；由 `DOCUMENT_DRAFTER` 作为文书任务唯一 root；产生一个 document proposal；冻结 manifest。
- 验证：时窗、执行/计划分层、上级意见来源、部分结果、重放、取消、跨患者。
- 回滚：停用 pilot composition，回人工查房模板。
- DoD：一个合成住院患者可完成 run 并在 UI 审阅 proposal；不自动签署。

### PILOT-002 查房小南 UI 纵向接线

- 目标：在住院查房环节展示主 Agent 负责、两个能力交接、来源和文书差异。
- 依赖：`PILOT-001`、`UX-001..003`。状态：`PLANNED`。
- 验证：UI→API→Harness→Proposal→驳回/接受预览；断线恢复；移动端不遮挡。
- 回滚：页面 flag 回 v1。DoD：用户可说出每个协作者的作用与贡献。

### PILOT-003 组合 Eval、红队和临床审阅包

- 目标：在影子运行前证明首链不越权、不伪造查房意见，且对医生有可审阅价值。
- 依赖：`PILOT-001/002`、`PLT-013`。状态：`PLANNED`。
- 动作：建合成 gold set；临床双人标注；组合与独立对比；跨患者/prompt injection/source spoof 红队；生成 go/no-go 证据包。
- 回滚：不通过即停留在本机 Eval。DoD：阈值、样本、模型/版本和评审人可追溯。

### PILOT-004 影子运行与负担指标

- 目标：不影响医生工作流的前提下采集延迟、采纳/驳回、编辑负担、告警和安全指标。
- 依赖：`PILOT-003`、`DEC-003/004`。状态：`PLANNED`。
- 禁止：无试点授权不接真数据；影子输出不进入已签文书；不将反馈默认作训练数据。
- 验证：隐私/留存策略、紧急 kill switch、告警、人工降级。
- 回滚：立即停用 tenant/use-case release。DoD：获得签字的灰度是否继续决策。

## 9. 发布与外部写入任务（必须分开授权）

| ID | 任务 | 硬依赖 | 授权/风险 | 可观测 DoD | 回滚 |
|---|---|---|---|---|---|
| `REL-001` | 发布候选冻结与证据包 | 目标批次全部 DoD | 本地文件可由 S008 实施；不等于提交 | 契约、迁移、Eval、UI、SBOM/扫描结果齐全 | 删除未发布 candidate，保留证据 |
| `REL-002` | commit | `REL-001` | 需用户明确授权；不夹带工作树无关变更 | commit hash 可定位，状态干净或已记录例外 | revert commit，不 reset 用户变更 |
| `REL-003` | push / PR / 远程 CI | `REL-002` | 需外部网络写入授权 | PR/CI URL、失败日志和批准完整 | 关闭 PR/停止后续发布，不改写历史 |
| `REL-004` | 生产数据库迁移 | `REL-003`、备份/恢复验证 | 高风险外部写入，需 DBA/change window 批准 | Flyway 版本、执行日志、schema/data smoke、备份可恢复 | 扩展迁移不 down migrate；应用切 legacy flag；必要时从备份恢复 |
| `REL-005` | 影子/灰度开启 + 第一个 T3 adapter | `REL-004`、`DEC-005`、pilot GO | 需 tenant/科室/角色白名单和业务审批 | 无未审批副作用；指标/告警/审批/执行审计可见 | kill switch；停用 release/adapter；保留审计 |
| `REL-006` | 生产扩面与回滚演练 | `REL-005` 满足批准窗口 | 每次扩租户/科室/用例单独批准 | 回滚 RTO/RPO、人工降级、事件/任务一致性已演练 | 切前一 release + 停用新能力，不删除历史轨迹 |

## 10. 全局验证契约

每个纵向切片至少执行与变更相称的定向验证；宣称发布候选前执行：

```bash
npm --prefix contracts test
npm --prefix contracts run check
node evals/check-golden.mjs
node security/check-red-team.mjs
scripts/test-schema.sh
scripts/with-java21.sh ./gradlew test --no-daemon --no-configuration-cache
npm --prefix web test
npm --prefix web run build
scripts/security-scan.sh
node prototype/app/verify-traceability.mjs
node docs/design/ui-delivery/generate-route-map.mjs --audit
```

`scripts/verify.sh` 还包含启动数据库与备份恢复，属候选发布总门禁。未实际执行的命令不得标记“通过”。

## 11. 风险、授权与暂停条件

| 风险 | 触发 | 响应 | 暂停点 |
|---|---|---|---|
| 跨患者/租户数据 | sourceRef/scope 不一致 | root `BLOCKED`，安全事件，不继续 Tool/child | 任何环境 |
| 幻觉或伪造执行事实 | 无来源事实、计划当执行 | 验证失败，输出不进入 proposal | 任何环境 |
| 硬规则被 AI 降级 | QC 聚合覆盖 hard finding | 发布门禁失败 | 本地 Eval |
| 未批准写操作 | child 或 root 直达 domain write | 政策拒绝、审计、停用 release | 任何环境 |
| 模型/数据出境未批准 | route 不匹配数据分类 | 不调用供应商，回人工模板 | 接真模型前 |
| 子 Agent 数量裂变 | 没有独立状态/预算/Eval 价值 | `DEC-002` 降为 Skill/Tool | 子能力发布前 |
| 运行轨迹泄露 PHI/Prompt | SSE/log/error 出现原文 | 紧急关闭对外投影，保留审计 | 进入影子前 |
| 工作树混入无关变更 | commit candidate 超出 allowed paths | 不提交，先分离变更 | `REL-002` |

## 12. Planner 质量自检

- [x] 任务对应 PRD/LLD/UI 输入，未自行新增业务范围。
- [x] 主 Agent、子 Agent、Harness、UI、Eval 和发布都有稳定 task ID。
- [x] DAG 与并行组可执行，未将整个 Agent 家族混成一个任务。
- [x] 任务包含允许修改、禁止项、测试、安全、回滚和可观测 DoD。
- [x] commit、push/PR、DB migration、灰度和生产扩面已分离为需单独授权的任务。
- [x] 没有估算未证实时间，没有将未执行测试写成已通过。
