# A02 动作审批·真实执行落地（V124）证据报告

> 日期：2026-08-22
> 切片：`ActionExecution`（`action_execution`）
> 范围：A02 全局 AI医助小南·动作执行核验首切
> 结论：**VERIFIED**（本机全量门禁通过）

## 1. 结论

在既有 `action_approval`（V79，`PROPOSED→APPROVED/REJECTED` + 人机分离硬门）之上，新增真实执行落地层：被批准的动作进入 `PENDING → SUCCEEDED / FAILED` 执行状态机，只有 `APPROVED` 提案可执行、同提案至多一个执行、失败必须附原因、执行身份不可变，全部迁移经服务端命令（乐观锁 + 幂等 + 审计哈希链 + Outbox）推进。闭合 A02 DoD 的「结构化动作 diff/影响/审批/**执行核验**」与「零未批准副作用」。

## 2. 高风险验收表

| 验收项 | 硬门/约束 | 证据 |
|---|---|---|
| 仅已批准动作可执行 | `requireApprovedApproval` + `ACTION_NOT_APPROVED` | `givenProposedApproval_whenCreatingExecution_thenRejected` |
| 同提案至多一个执行 | `unique (tenant_id, action_approval_id)` | assert-v124 |
| 执行状态与时间/人一致 | `action_execution_status_check`（`SUCCEEDED/FAILED ⟺ executed_by + executed_at 非空`） | assert-v124 |
| 失败必附原因 | `action_execution_failure_check` + `ACTION_EXECUTION_FAILURE_REASON_REQUIRED` | `givenPendingExecution_whenFailingWithoutReason_thenRejected` |
| 执行身份不可变 | `action_execution_immutable` 触发器 | `givenExecution_whenTampered_thenDatabaseRejectsMutation` |
| 患者一致 | `ACTION_PATIENT_MISMATCH`（提案/执行患者不同则拒绝） | 服务层校验 |

## 3. 自动化门禁

```
scripts/verify.sh → VERIFY_EXIT=0
- contracts test/check：3/3，check 无漂移（360 schemas / 368 outputs / 334 operations）
- AI eval：100/100
- red-team：15 payloads / 12 surfaces
- test-schema.sh：V1–V124 迁移 + 断言，rollback 通过
- backup-restore-verify.sh：通过
- gradle test：112 suites / 436 tests / 0 failures
- web test + build：通过
- security-scan.sh：通过
- verify-traceability.mjs：138/138 FR / 138/138 AC / 138/138 route refs
- generate-route-map.mjs --audit：194/194 routes
```

## 4. 本批实现

- **迁移 V124**：`action_execution`（租户/执行/动作提案/患者/状态/执行人/执行时间/结果说明/row_version）；状态枚举、执行状态一致性、失败必附原因、同提案唯一、身份不可变触发器、提案索引。
- **契约**：新增 `ActionExecution`、`ActionExecutionCreateRequest`、`ActionExecutionTransitionRequest` 三 Schema 与 4 端点（list/create/succeed/fail）。
- **模块**：`org.haoemr.approval` 下 `ActionExecutionService`（创建 + 成功/失败状态机 + 仅已批准执行 + 患者一致 + 幂等 + 审计/Outbox）、`Controller`、`Exception`、`ExceptionHandler`。
- **测试**：`ActionExecutionApiTest` 6 用例覆盖已批准创建、成功、失败、失败缺原因拒绝、未批准拒绝、篡改拒绝。

## 5. 未关闭风险

- 顶栏/浮窗 UI、提醒限频/转任务仍未实现（A02 其余项，UI 层与专科 workbench 同属后置）。
- 执行落地目前只记录状态机，未接真实下游副作用适配器（如真实医嘱/文书创建）。
- 按当前优先级，本切片为 A02「动作执行核验」首切；后续继续 O01 定时投递调度、A01 审批流/SSE、Q01 源系统盘点等全局史诗。
