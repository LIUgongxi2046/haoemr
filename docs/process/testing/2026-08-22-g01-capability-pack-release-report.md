# G01 能力包灰度发布与回滚（V121）证据报告

> 日期：2026-08-22
> 切片：`CapabilityPackRelease`（`capability_pack_release`）
> 范围：G01 配置平台·沙箱/灰度/回滚首切
> 结论：**VERIFIED**（本机全量门禁通过）

## 1. 结论

在既有 `capability_pack`（V82，能力包定义/继承/自继承硬门）之上，新增配置发布灰度层：能力包发布以 `DRAFT → CANARY → ACTIVE → RETIRED` 主链 + `CANARY → ROLLED_BACK` 回退支链组成状态机，同一能力包任意时刻至多一个 `ACTIVE` 发布（数据库部分唯一索引强制），回退必须附原因，发布身份不可变，全部迁移通过服务端命令（乐观锁 + 幂等 + 审计哈希链 + Outbox）推进。

## 2. 高风险验收表

| 验收项 | 硬门/约束 | 证据 |
|---|---|---|
| 发布目标必须是 ACTIVE 能力包 | `requireActivePack` + `CAPABILITY_PACK_NOT_ACTIVE` | 服务层校验 |
| 状态机合法迁移 | `DRAFT→CANARY→ACTIVE→RETIRED`、`CANARY→ROLLED_BACK`；非法迁移 `CAPABILITY_PACK_RELEASE_STATE_INVALID` | `givenDraft_whenPromotingDirectly_thenRejected` |
| 同包至多一个 ACTIVE 发布 | `capability_pack_release_one_active_idx` 部分唯一索引 + promote 同事务退休旧 ACTIVE | `givenTwoReleases_whenPromotingSecond_thenFirstRetired` |
| 回退必附原因 | `capability_pack_release_rollback_check`（`ROLLED_BACK ⟹ rollback_reason ≥2 字符`） | `givenCanary_whenRollingBack_thenRolledBack` |
| 时间戳与状态一致 | `canary/promote/retire` 三条 `(状态) = (时间戳非空)` 显式命名约束 | assert-v121 |
| 身份不可变 | `capability_pack_release_immutable` 触发器（pack/版本/发布人/时间不可改） | `givenRelease_whenTampered_thenDatabaseRejectsMutation` |
| 并发乐观锁 | `expected_row_version` + `for update` | `givenStaleVersion_whenStartingCanary_thenRejected` |

## 3. 自动化门禁

```
scripts/verify.sh → VERIFY_EXIT=0
- contracts test/check：3/3，check 无漂移（351 schemas / 359 outputs / 322 operations）
- AI eval：100/100
- red-team：15 payloads / 12 surfaces
- test-schema.sh：V1–V121 迁移 + 断言，rollback 通过
- backup-restore-verify.sh：通过
- gradle test：109 suites / 417 tests / 0 failures
- web test + build：通过
- security-scan.sh：通过
- verify-traceability.mjs：138/138 FR / 138/138 AC / 138/138 route refs
- generate-route-map.mjs --audit：194/194 routes
```

## 4. 本批实现

- **迁移 V121**：`capability_pack_release`（租户/发布 id/能力包/版本/生命周期/灰度开始时间/提升时间/退休时间/回退原因/发布人/发布时间/row_version）；`version`、`canary`、`promote`、`retire`、`rollback` 五条显式命名约束；`capability_pack_release_one_active_idx` 部分唯一索引；身份不可变触发器；能力包索引。
- **契约**：新增 `CapabilityPackRelease`、`CapabilityPackReleaseCreateRequest`、`CapabilityPackReleaseTransitionRequest`、`CapabilityPackReleaseRollbackRequest` 四 Schema 与 6 端点（list/create/start-canary/promote/retire/rollback）。
- **模块**：`org.haoemr.platform` 下 `CapabilityPackReleaseService`（create + 状态机迁移 + promote 同事务退休旧 ACTIVE + 幂等 + 审计/Outbox）、`Controller`、`Exception`、`ExceptionHandler`。
- **测试**：`CapabilityPackReleaseApiTest` 8 用例覆盖 DRAFT 创建、启动灰度、提升全量、回退、非法迁移拒绝、过期版本冲突、双发布单 ACTIVE、篡改拒绝。

## 5. 未关闭风险

- 可视化配置后台与三套合成医院配置的沙箱回归仍未实现（G01 其余项）。
- 能力包继承环已由既有 schema 结构保证（`pack_code` 唯一 + `inherits_from` 非延迟外键 + `capability_pack_immutable` 不可变触发器），继承恒指向更早创建的包，DAG 必然无环，无需额外环检测代码。
- 灰度发布仅维护状态机与唯一 ACTIVE 约束，未接入真实路由/配置生效引擎与 A/B 流量分配。
- 按当前优先级，本切片为 G01「沙箱/灰度/回滚」首切；后续转 O01/A01/Q01/D01/A02 全局史诗。
