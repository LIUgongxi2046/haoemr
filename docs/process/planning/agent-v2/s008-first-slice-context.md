# S008 首任务上下文：PLT-001 契约与运行语义基线

## 1. 建议调用

```text
$haonan-s008-coder
实施 docs/process/planning/agent-v2/implementation-backlog.md 中的 PLT-001。
严格限定在 contracts/ 与契约生成产物，不实现 Harness 运行时、不新增 Flyway、不改 UI 业务组件。
先读 DEVELOPMENT_PRINCIPLES.md、Agent 优化 PRD、Medical Agent Harness LLD、UI v2 README 和本任务卡。
实施后执行 npm --prefix contracts test 和 npm --prefix contracts run check，并确认重新生成无 drift。
不 commit、push、连真实模型或写外部系统。
```

## 2. 目标与边界

- 目标：建立 main/child/release/composition/run/outcome/event 的单一机器契约，为后续 DB、Harness、UI 和 Eval 提供同源 Java/TypeScript 类型。
- 用户价值：后续每个子 Agent 的状态、交接和贡献可稳定显示，旧小南入口可渐进迁移。
- 只做 contract-first；不构建虚假 API 实现，不为了测试绿而伪造后端行为。
- 任务完成前仍为 PLANNED；只有 S008 实际执行后才可报告验证结果。

## 3. 必读上下文

1. `DEVELOPMENT_PRINCIPLES.md`，特别是 P3、P9、P10、P12。
2. `docs/product/prd/2026-08-25-haoemr-agent-optimization-prd.md`，特别是 FR-027–031、AC-027–031、NFR-009/011/013–15。
3. `docs/design/architecture/2026-08-25-haoemr-medical-agent-harness-lld.md`，特别是第 2、3、4、11、12、13 节。
4. `docs/design/ui-delivery/ai-medical-assistant-v2/child-agent-experience.md` 和 `states/state-matrix.md`。
5. `contracts/openapi.json`、`contracts/governance.source.json`、`contracts/generate.mjs`、`contracts/test/contracts.test.mjs`。
6. 现有 `AgentRegistryApiTest`、`AgentRunApiTest`、`AgentDependencyApiTest`、`SkillRegistryApiTest`、`ToolRegistryApiTest`。

## 4. 允许修改

- `contracts/openapi.json`
- `contracts/governance.source.json`
- `contracts/generate.mjs`，仅当新契约无法由现有生成器表达时
- `contracts/test/contracts.test.mjs`
- `contracts/generated/`
- `web/src/generated/`
- Gradle 生成的 contract Java 类型，是否纳入版本管理以仓库现有规则为准

## 5. 禁止项

- 不修改 `src/main/java/` 运行时业务代码、Flyway migration、`web/src/vue/` 组件或 CSS。
- 不删除或不兼容替换旧 Agent/Skill/Tool/Run API。
- 不分别手写 Java 和 TypeScript 枚举，必须从契约生成。
- 公开 event/error 不含 prompt、chain-of-thought、Tool 原始输出、secret 或 PHI 原文。
- 不 commit、push、建 PR、部署、调用真实模型或写外部系统。
- 不整理、覆盖或删除工作树的无关用户变更。

## 6. 最小契约集

### 6.1 Release 身份

- Agent、Skill、Tool、Prompt、AgentComposition 都用 stable code + immutable version 定位。
- 状态名称与现有 registry/release 风格兼容，不用 display name 代替机器 code。

### 6.2 Root/Child Run

- `runId`、`rootRunId`、可选 `parentRunId`。
- `runKind` 至少表达 ROOT、CHILD、LEGACY_SINGLE_SHOT。
- `targetType`、`targetId`；保留现有 document reference 作兼容字段。
- agent/prompt/model/tool/skill/composition release pins。
- child code、criticality、parallel group、交接摘要、idempotency key 和 cursor 定位。

### 6.3 状态与结果

- root 至少可表达 QUEUED、RUNNING、WAITING_FOR_REVIEW、COMPLETED、PARTIAL、BLOCKED、FAILED、CANCELLED。
- child outcome 至少可表达 COMPLETED、PARTIAL、BLOCKED、FAILED、CANCELLED、SKIPPED。
- 不强迫 DB 内部状态与 UI 展示词一对一，为后续 projection 映射留出空间。

### 6.4 Composition Manifest

- 唯一 root main Agent release。
- child node 含 release ref、criticality、parallel group、input bindings、output alias、max budget。
- 最大深度 1，不允许任意脚本、shell、SQL 或自由代码。
- 不默认允许跨 family 多 parent，留待 `DEC-006` ADR 决定。

### 6.5 对外 Event Envelope

- eventId、runId、rootRunId、可选 parentRunId、sequence、occurredAt。
- 受控 event type：run/child started、progress、handoff、output available、waiting review、completed/partial/blocked/failed/cancelled。
- payload 只含 status、display metadata key、sourceRef count、warning count、duration/budget summary 等安全投影字段。
- 为 SSE cursor/reconnect 留出稳定定位。

### 6.6 错误语义

至少保留稳定 error code：release/composition 不存在、未激活或不兼容；DAG 有环、深度超限、child 未获批；parent/root/scope 不一致；子预算超限；Tool 未授权；replay/cursor 不可用。

## 7. 兼容性

- 旧 Agent/Skill/Tool registry 路由和 schema 保留，新 release 契约是增量扩展。
- 现有 document-bound create run 仍可表达；本任务不删除 document 字段。
- 旧前端 Agent code 的兼容映射可表达，但本任务不决定它们的发布状态。
- 过渡期可缺字段必须明确 optional/default/deprecated 语义，不用模糊 null 代替状态。

## 8. 实施步骤

1. 用 `rg` 列出现有 Agent/Run/Registry/Dependency/Event/Error schema 与 operation，建立兼容基线。
2. 在 `contracts/openapi.json` 增量定义第 6 节最小契约，复用现有 ID、timestamp、error 和 pagination schema。
3. 在 `contracts/governance.source.json` 注册必需 error/event/index metadata，不创建没有运行时的虚假可用 operation。
4. 仅在必要时最小修改 `generate.mjs`，并为新生成逻辑加测试。
5. 运行生成并检查 diff，确认没有无关 schema、operation 或 route 变更。
6. 增加契约测试：必填字段、枚举、release pin、parent-child、event 安全字段、旧 operation 存在性。
7. 执行验证，在 S008 交付摘要记录命令、退出码、关键输出和未决问题。

## 9. 验证与 DoD

```bash
npm --prefix contracts test
npm --prefix contracts run check
scripts/with-java21.sh ./gradlew generateContracts --no-daemon --no-configuration-cache
git diff --check
```

- [ ] 新 schema/event/error 与 PRD/LLD 一致，无任意脚本或通用 Tool 入口。
- [ ] Java/TS/generated indexes 由同一契约生成，第二次生成无 drift。
- [ ] 旧 Agent/Skill/Tool/Run operation 仍在，旧契约测试通过。
- [ ] root/child/release/composition/target/outcome/event 可被 Java 和 TypeScript 表达。
- [ ] event/error 不允许 prompt、chain-of-thought、secret 和 PHI 原文。
- [ ] 无 Flyway、runtime service、Vue 组件或无关文件变更。
- [ ] 交付摘要列出实际验证结果、未决语义和下一任务输入。

## 10. 回滚与交接

- 代码回滚：仅撤回 PLT-001 的契约、生成逻辑、测试与生成产物；不撤回用户无关变更。
- 数据/配置回滚：本任务无 DB 和运行时配置变更。
- 交接产物：schema/operation/event/error 清单、Java/TS 类型定位、兼容性说明、验证证据、需要 DEC-006 决定的字段。
- 下一批：可并行 `PLT-002`、`PLT-003`和 `PLT-004`；生产预算值仍等 `DEC-004`。

