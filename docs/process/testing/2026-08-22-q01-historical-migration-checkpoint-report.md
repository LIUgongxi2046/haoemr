# Q01 历史迁移断点重跑（V129）证据报告

> 日期：2026-08-22
> 切片：`HistoricalMigrationCheckpoint`（`historical_migration_checkpoint`）
> 范围：Q01 病案迁移·断点重跑首切
> 结论：**VERIFIED**（本机全量门禁通过）

## 1. 结论

在既有 `historical_migration_batch`（V115，试迁/对账/切换/回退）之上，补齐断点续迁能力：迁移批次可记录进度检查点（已处理记录数 + 最后源键），检查点一旦写入即不可变（审计留存），进度单调不倒退（`CHECKPOINT_REGRESSION`），且仅 `TRIAL/RECONCILED` 批次可记录（`SWITCHED/ROLLED_BACK` 拒绝 `BATCH_NOT_RESUMABLE`）。`latest` 端点返回最近检查点作为断点续迁起点。

## 2. 高风险验收表

| 验收项 | 硬门/约束 | 证据 |
|---|---|---|
| 仅可续迁批次可记录 | `BATCH_NOT_RESUMABLE`（SWITCHED/ROLLED_BACK 拒绝） | `givenSwitchedBatch_whenRecording_thenRejected` |
| 进度单调不倒退 | `CHECKPOINT_REGRESSION`（processed_records < 上一检查点拒绝） | `givenRegression_whenRecording_thenRejected` |
| 进度非负 | `historical_migration_checkpoint_processed_records_check` | assert-v129 |
| 检查点不可变 | `historical_migration_checkpoint_immutable`（update/delete 阻断） | `givenCheckpoint_whenTampered_thenDatabaseRejectsMutation` |
| 断点续迁起点 | `latest` 返回最近检查点 | `givenProgressingCheckpoints_whenRecording_thenLatestReflectsResumePoint` |

## 3. 自动化门禁

```
scripts/verify.sh → VERIFY_EXIT=0
- contracts test/check：3/3，check 无漂移（371 schemas / 379 outputs / 347 operations）
- AI eval：100/100
- red-team：15 payloads / 12 surfaces
- test-schema.sh：V1–V129 迁移 + 断言，rollback 通过
- backup-restore-verify.sh：通过
- gradle test：116 suites / 460 tests / 0 failures
- web test + build：通过
- security-scan.sh：通过
- verify-traceability.mjs：138/138 FR / 138/138 AC / 138/138 route refs
- generate-route-map.mjs --audit：194/194 routes
```

## 4. 本批实现

- **迁移 V129**：`historical_migration_checkpoint`（租户/检查点/批次/已处理记录数/最后源键/记录人/记录时间/row_version）；进度非负约束、不可变触发器、批次索引。
- **契约**：新增 `HistoricalMigrationCheckpoint`、`HistoricalMigrationCheckpointRecordRequest` 两 Schema 与 3 端点（list/record/latest）。
- **模块**：`org.haoemr.archive` 下 `HistoricalMigrationCheckpointService`（记录检查点 + 可续迁批次/进度单调硬门 + 幂等 + 审计/Outbox + latest 断点）、`Controller`、`Exception`、`ExceptionHandler`。
- **测试**：`HistoricalMigrationCheckpointApiTest` 6 用例覆盖记录、进度推进、倒退拒绝、已切换拒绝、无检查点 404、篡改拒绝。

## 5. 未关闭风险

- 源系统「映射/患者匹配」仍未实现（Q01 其余项）。
- 断点重跑目前只记录进度检查点，未接真实源系统的增量读取/续跑适配器。
- 按当前优先级，Q01 断点重跑已落地；后续继续 A01 审批流/SSE 等全局史诗。
