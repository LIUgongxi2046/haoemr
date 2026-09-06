# A01 AI 运行预算强制校验（V132）证据报告

> 日期：2026-08-22
> 切片：`AgentRunBudgetConsumption`（`agent_run_budget_consumption`）
> 范围：A01 AI 平台·运行预算强制校验首切
> 结论：**VERIFIED**（本机全量门禁通过）

## 1. 结论

在既有 `agent_run_budget`（V88，预算定义：最大 token/时长、ACTIVE/INACTIVE）之上，补齐运行时预算强制：每次 AI 运行可记录 token 与时长消耗，服务端在单事务内锁定预算并做累计校验——累计 token 不得超过 `max_tokens`（`BUDGET_TOKENS_EXCEEDED`）、累计时长不得超过 `max_duration_seconds`（`BUDGET_DURATION_EXCEEDED`），仅 `ACTIVE` 预算可记录消耗，同运行同预算至多一条、消耗记录不可变。闭合 A01 的「预算」从「定义」到「运行时强制」的缺口。

## 2. 高风险验收表

| 验收项 | 硬门/约束 | 证据 |
|---|---|---|
| 仅活动预算可记录 | `BUDGET_INACTIVE` | `givenInactiveBudget_whenRecording_thenRejected` |
| 累计 token 不超限 | `BUDGET_TOKENS_EXCEEDED`（锁预算 + 累计校验） | `givenTokensExceeded_whenRecording_thenRejected` |
| 累计时长不超限 | `BUDGET_DURATION_EXCEEDED` | `givenDurationExceeded_whenRecording_thenRejected` |
| 消耗非负 | `agent_run_budget_consumption_tokens_check`/`_duration_check` | assert-v132 |
| 同运行同预算去重 | `agent_run_budget_consumption_unique` | 服务层/数据库 |
| 消耗不可变 | `agent_run_budget_consumption_immutable` | `givenConsumption_whenTampered_thenDatabaseRejectsMutation` |

## 3. 自动化门禁

```
scripts/verify.sh → VERIFY_EXIT=0
- contracts test/check：3/3，check 无漂移（380 schemas / 388 outputs / 356 operations）
- AI eval：100/100
- red-team：15 payloads / 12 surfaces
- test-schema.sh：V1–V132 迁移 + 断言，rollback 通过
- backup-restore-verify.sh：通过
- gradle test：119 suites / 477 tests / 0 failures
- web test + build：通过
- security-scan.sh：通过
- verify-traceability.mjs：138/138 FR / 138/138 AC / 138/138 route refs
- generate-route-map.mjs --audit：194/194 routes
```

## 4. 本批实现

- **迁移 V132**：`agent_run_budget_consumption`（预算/运行/消耗 token/消耗时长/记录人/记录时间/row_version）；消耗非负约束、同运行同预算唯一、不可变触发器、预算索引。
- **契约**：新增 `AgentRunBudgetConsumption`、`AgentRunBudgetConsumptionRecordRequest`、`AgentRunBudgetSummary` 三 Schema 与 3 端点（list/record/summary）。
- **模块**：`org.haoemr.agent` 下 `AgentRunBudgetConsumptionService`（消耗记录 + 锁预算累计校验 + 汇总 + 幂等 + 审计/Outbox）、`Controller`、`Exception`、`ExceptionHandler`。
- **测试**：`AgentRunBudgetConsumptionApiTest` 6 用例覆盖记录、累计汇总、token 超限、时长超限、非活动预算拒绝、篡改拒绝。

## 5. 未关闭风险

- 预算消耗记录为显式上报，未接真实模型推理的自动计量（token/时长需 provider 回传）。
- 审批流编排与 SSE 断点恢复未实现（A01 其余项）。
- 按当前优先级，A01「预算强制」已落地；剩余 G01 可视化配置（UI）等全局项。
