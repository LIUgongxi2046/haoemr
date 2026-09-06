# A02 提醒转任务与限频（V128）证据报告

> 日期：2026-08-22
> 切片：`ClinicalReminderConversion`（`clinical_reminder_conversion` + `clinical_task.source_type='REMINDER'`）
> 范围：A02 全局 AI医助小南·提醒转任务/限频首切
> 结论：**VERIFIED**（本机全量门禁通过）

## 1. 结论

在既有 `clinical_reminder`（V56，`PENDING→ACKNOWLEDGED/SILENCED`）之上，新增提醒转任务闭环：把待处理提醒物化为统一临床任务（`source_type='REMINDER'`），风险等级由提醒严重度映射（INFO→ROUTINE、WARNING→HIGH、CRITICAL→CRITICAL），同一条提醒至多转换一次（唯一约束 + 幂等），且仅 `PENDING` 提醒可转换（已确认/已静默不再生成任务）。「限频」由「一提醒至多一任务」硬门落地，防止提醒风暴重复刷任务。

## 2. 高风险验收表

| 验收项 | 硬门/约束 | 证据 |
|---|---|---|
| 仅待处理提醒可转任务 | `REMINDER_NOT_PENDING`（ACKNOWLEDGED/SILENCED 拒绝） | `givenAcknowledgedReminder_whenConverting_thenRejected` |
| 一提醒至多一任务 | `clinical_reminder_conversion_unique`（tenant, reminder） | `givenDuplicateConversion_whenConverting_thenRejected` |
| 任务源类型为 REMINDER | V128 扩展 `clinical_task_source_type_check` + 服务端 `source_type='REMINDER'` | `givenPendingReminder_whenConverting_thenTaskCreated` |
| 转换不可变 | `clinical_reminder_conversion_immutable`（update/delete 阻断） | `givenConversion_whenTampered_thenDatabaseRejectsMutation` |

## 3. 自动化门禁

```
scripts/verify.sh → VERIFY_EXIT=0
- contracts test/check：3/3，check 无漂移（369 schemas / 377 outputs / 344 operations）
- AI eval：100/100
- red-team：15 payloads / 12 surfaces
- test-schema.sh：V1–V128 迁移 + 断言，rollback 通过
- backup-restore-verify.sh：通过
- gradle test：115 suites / 454 tests / 0 failures
- web test + build：通过
- security-scan.sh：通过
- verify-traceability.mjs：138/138 FR / 138/138 AC / 138/138 route refs
- generate-route-map.mjs --audit：194/194 routes
```

## 4. 本批实现

- **迁移 V128**：扩展 `clinical_task.source_type` 增加 `REMINDER`；建立 `clinical_reminder_conversion`（提醒/任务/转换人/转换时间、同提醒唯一约束、不可变触发器、任务索引）。
- **契约**：新增 `ClinicalReminderConversion`、`ClinicalReminderConversionCreateRequest` 两 Schema 与 2 端点（list/convert）。
- **模块**：`org.haoemr.reminder` 下 `ClinicalReminderConversionService`（转任务 + 仅 PENDING 硬门 + 风险映射 + 幂等 + 审计/Outbox）、`Controller`、`Exception`、`ExceptionHandler`。
- **测试**：`ClinicalReminderConversionApiTest` 4 用例覆盖转换成功、重复拒绝、非待处理拒绝、篡改拒绝。

## 5. 未关闭风险

- 顶栏/浮窗 UI 仍未实现（A02 UI 层，与专科 workbench 同属后置）。
- 「限频」目前只落在「一提醒一任务」；按患者/类型的每日提醒上限与批量静默策略未实现。
- 按当前优先级，A02 代码层基本收口；后续继续 A01 审批流/SSE、Q01 源映射/患者匹配/断点重跑等全局史诗。
