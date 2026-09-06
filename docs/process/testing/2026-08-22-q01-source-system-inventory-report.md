# Q01 源系统盘点（V126）证据报告

> 日期：2026-08-22
> 切片：`SourceSystemInventory`（`source_system_inventory`）
> 范围：Q01 病案迁移·源系统盘点首切
> 结论：**VERIFIED**（本机全量门禁通过）

## 1. 结论

在既有 `historical_migration_batch`（V115，试迁/对账/切换/回退）之上，补齐历史迁移管线的最前置「源系统盘点」：注册待迁移源系统并维护其生命周期 `REGISTERED → CONFIGURED → ACTIVE → RETIRED`（终态），源编码唯一且身份不可变，全部迁移经服务端命令（乐观锁 + 幂等 + 审计哈希链 + Outbox）推进。为后续「源映射/患者匹配/断点重跑」提供可校验的源系统主数据底座。

## 2. 高风险验收表

| 验收项 | 硬门/约束 | 证据 |
|---|---|---|
| 源编码唯一 | `source_system_inventory_source_code_unique`（tenant, source_code） | assert-v126 |
| 身份不可变 | `source_system_inventory_immutable` 触发器（code/type/注册人/时间） | `givenSource_whenTampered_thenDatabaseRejectsMutation` |
| 状态机合法迁移 | `REGISTERED→CONFIGURED→ACTIVE→RETIRED`；非法迁移 `SOURCE_SYSTEM_STATE_INVALID` | `givenRegistered_whenActivatingDirectly_thenRejected` |
| 退休为终态 | `RETIRED` 后不可再配置/激活 | `givenRetired_whenConfiguring_thenRejected` |
| 并发乐观锁 | `expected_row_version` + `for update` | 服务层实现 |

## 3. 自动化门禁

```
scripts/verify.sh → VERIFY_EXIT=0
- contracts test/check：3/3，check 无漂移（365 schemas / 373 outputs / 340 operations）
- AI eval：100/100
- red-team：15 payloads / 12 surfaces
- test-schema.sh：V1–V126 迁移 + 断言，rollback 通过
- backup-restore-verify.sh：通过
- gradle test：113 suites / 445 tests / 0 failures
- web test + build：通过
- security-scan.sh：通过
- verify-traceability.mjs：138/138 FR / 138/138 AC / 138/138 route refs
- generate-route-map.mjs --audit：194/194 routes
```

## 4. 本批实现

- **迁移 V126**：`source_system_inventory`（租户/源系统/编码/名称/类型/连接状态/注册人/注册时间/row_version）；类型与状态枚举、编码唯一、身份不可变触发器、状态索引。
- **契约**：新增 `SourceSystemInventory`、`SourceSystemInventoryRegisterRequest`、`SourceSystemInventoryTransitionRequest` 三 Schema 与 5 端点（list/register/configure/activate/retire）。
- **模块**：`org.haoemr.archive` 下 `SourceSystemInventoryService`（注册 + 配置/激活/退休状态机 + 幂等 + 审计/Outbox）、`Controller`、`Exception`、`ExceptionHandler`。
- **测试**：`SourceSystemInventoryApiTest` 7 用例覆盖注册、配置、激活、退休、非法越级、退休后拒绝、篡改拒绝。
- **附修**：`OutboxDispatcherTest` 的到期租约回收用例在全量并发下受 outbox 积压 + 批量领取排序干扰而抖动，已将该用例事件置为最旧 `available_at` 使其确定被优先领取。

## 5. 未关闭风险

- 源系统「映射/患者匹配/断点重跑」仍未实现（Q01 其余项）。
- 本切片只登记源系统主数据，尚未强制 `historical_migration_batch.source_system` 必须引用已登记且未退休的源系统（可作为下一步硬门联动）。
- 按当前优先级，Q01 盘点已落地；后续继续 D01 队列成员计算引擎、A01 审批流/SSE、A02 限频/转任务等全局史诗。
