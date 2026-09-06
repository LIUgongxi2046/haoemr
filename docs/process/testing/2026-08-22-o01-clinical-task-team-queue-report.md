# O01 科室级任务队列·团队视图（V122）证据报告

> 日期：2026-08-22
> 切片：`ClinicalTaskTeamQueue`（`clinical_task_team_queue`）
> 范围：O01 统一任务·科室级队列/团队视图首切
> 结论：**VERIFIED**（本机全量门禁通过）

## 1. 结论

在既有 `clinical_task`（V19）与 `clinical_task_notification`（V92）之上，新增科室级团队队列：统一临床任务可按科室入队（`ENQUEUED → CLAIMED → COMPLETED` 主链 + `ENQUEUED → WITHDRAWN` 撤回支链），同一任务在同一科室至多一个活动队列项（数据库部分唯一索引），领取必须由在该院区持有有效岗位任期者执行（团队成员校验），终态任务与跨院区任务不可入队，队列身份不可变，全部迁移经服务端命令（乐观锁 + 幂等 + 审计哈希链 + Outbox）推进。

## 2. 高风险验收表

| 验收项 | 硬门/约束 | 证据 |
|---|---|---|
| 终态任务不可入队 | `requireEnqueueableTask` + `TASK_TERMINAL`（state ∉ COMPLETED/WITHDRAWN/EXPIRED） | `givenTerminalTask_whenEnqueuing_thenRejected` |
| 同科室同任务至多一个活动项 | `clinical_task_team_queue_active_idx` 部分唯一索引 | assert-v122 |
| 领取需团队成员资格 | `requireTeamMembership`（院区有效岗位任期）+ `TEAM_MEMBERSHIP_REQUIRED` | `givenEnqueued_whenClaiming_thenClaimed` |
| 领取状态一致性 | `clinical_task_team_queue_claim_check`（`CLAIMED/COMPLETED ⟺ claimed_by + claimed_at 非空`） | assert-v122 |
| 队列身份不可变 | `clinical_task_team_queue_immutable` 触发器 | `givenQueue_whenTampered_thenDatabaseRejectsMutation` |
| 并发乐观锁 | `expected_row_version` + `for update` | `givenStaleVersion_whenClaiming_thenRejected` |

## 3. 自动化门禁

```
scripts/verify.sh → VERIFY_EXIT=0
- contracts test/check：3/3，check 无漂移（354 schemas / 362 outputs / 327 operations）
- AI eval：100/100
- red-team：15 payloads / 12 surfaces
- test-schema.sh：V1–V122 迁移 + 断言，rollback 通过
- backup-restore-verify.sh：通过
- gradle test：110 suites / 424 tests / 0 failures
- web test + build：通过
- security-scan.sh：通过
- verify-traceability.mjs：138/138 FR / 138/138 AC / 138/138 route refs
- generate-route-map.mjs --audit：194/194 routes
```

## 4. 本批实现

- **迁移 V122**：`clinical_task_team_queue`（租户/队列/院区/科室/任务/状态/入队人/入队时间/领取人/领取时间/row_version）；状态枚举、领取一致性、活动项部分唯一索引、身份不可变触发器、科室索引。
- **契约**：新增 `ClinicalTaskTeamQueue`、`ClinicalTaskTeamQueueEnqueueRequest`、`ClinicalTaskTeamQueueTransitionRequest` 三 Schema 与 5 端点（list/enqueue/claim/complete/withdraw）。
- **模块**：`org.haoemr.tasks` 下 `ClinicalTaskTeamQueueService`（入队 + 领取/完成/撤回状态机 + 团队成员校验 + 幂等 + 审计/Outbox）、`Controller`、`Exception`、`ExceptionHandler`。
- **测试**：`ClinicalTaskTeamQueueApiTest` 7 用例覆盖入队、领取、完成、撤回、终态拒绝、过期版本冲突、篡改拒绝。

## 5. 未关闭风险

- 定时投递调度与真实消息通道（SSE/推送）仍未实现（O01 其余项，消息通道属真实适配器）。
- 团队队列仅按科室聚合，未接入按岗位/专业分组的团队视图 UI 与队列负载均衡。
- 按当前优先级，本切片为 O01「科室级任务队列/团队视图」首切；后续继续 A01 审批流/SSE、D01 下载口径、A02 限频/转任务等全局史诗。
