# Q01 源系统字段映射（V130）证据报告

> 日期：2026-08-22
> 切片：`SourceFieldMapping`（`source_field_mapping`）
> 范围：Q01 病案迁移·源映射首切
> 结论：**VERIFIED**（本机全量门禁通过）

## 1. 结论

在既有 `source_system_inventory`（V126，源系统盘点）之上，补齐迁移管线的「源映射」步骤：为已配置/已激活的源系统登记源字段 → 目标实体/字段的映射，映射身份不可变、同源同字段同目标唯一，仅 `CONFIGURED/ACTIVE` 源系统可接收映射（`SOURCE_SYSTEM_NOT_CONFIGURED`）。闭合「源盘点 → 源映射」链路，为后续患者匹配/试迁提供字段级映射主数据。

## 2. 高风险验收表

| 验收项 | 硬门/约束 | 证据 |
|---|---|---|
| 仅已配置/激活源可映射 | `SOURCE_SYSTEM_NOT_CONFIGURED`（REGISTERED/RETIRED 拒绝） | `givenRegisteredSource_whenRegisteringMapping_thenRejected` |
| 映射唯一 | `source_field_mapping_unique`（源+源字段+目标实体+目标字段） | `givenDuplicateMapping_whenRegistering_thenRejected` |
| 身份不可变 | `source_field_mapping_immutable` 触发器 | `givenMapping_whenTampered_thenDatabaseRejectsMutation` |
| 可停用 | `ACTIVE → INACTIVE`（乐观锁） | `givenMapping_whenDeactivating_thenInactive` |

## 3. 自动化门禁

```
scripts/verify.sh → VERIFY_EXIT=0
- contracts test/check：3/3，check 无漂移（374 schemas / 382 outputs / 350 operations）
- AI eval：100/100
- red-team：15 payloads / 12 surfaces
- test-schema.sh：V1–V130 迁移 + 断言，rollback 通过
- backup-restore-verify.sh：通过
- gradle test：117 suites / 465 tests / 0 failures
- web test + build：通过
- security-scan.sh：通过
- verify-traceability.mjs：138/138 FR / 138/138 AC / 138/138 route refs
- generate-route-map.mjs --audit：194/194 routes
```

## 4. 本批实现

- **迁移 V130**：`source_field_mapping`（源系统/源字段/目标实体/目标字段/状态/注册人/注册时间/row_version）；映射唯一约束、身份不可变触发器、源系统索引。
- **契约**：新增 `SourceFieldMapping`、`SourceFieldMappingRegisterRequest`、`SourceFieldMappingDeactivateRequest` 三 Schema 与 3 端点（list/register/deactivate）。
- **模块**：`org.haoemr.archive` 下 `SourceFieldMappingService`（映射登记 + 仅已配置源硬门 + 停用 + 幂等 + 审计/Outbox）、`Controller`、`Exception`、`ExceptionHandler`。
- **测试**：`SourceFieldMappingApiTest` 5 用例覆盖登记、重复拒绝、未配置源拒绝、停用、篡改拒绝。

## 5. 未关闭风险

- 源系统「患者匹配」（把源患者记录匹配到目标患者身份）仍未实现（Q01 最后一项）。
- 映射目前只记录字段级映射，未接真实迁移执行时的字段取值/转换/校验逻辑。
- 按当前优先级，Q01「源盘点/源映射/断点重跑」已落地；剩余 A01 审批流/SSE、Q01 患者匹配、G01 可视化配置等全局项。
