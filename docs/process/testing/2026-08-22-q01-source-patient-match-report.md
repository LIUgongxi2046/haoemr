# Q01 迁移源患者匹配（V131）证据报告

> 日期：2026-08-22
> 切片：`SourcePatientMatchCandidate`（`source_patient_match_candidate`）
> 范围：Q01 病案迁移·患者匹配首切
> 结论：**VERIFIED**（本机全量门禁通过）

## 1. 结论

在既有 `source_system_inventory`（V126）与 `source_field_mapping`（V130）之上，补齐迁移管线的「患者匹配」步骤：把迁移源患者身份（源标识 + 姓名 + 性别 + 出生日期）登记为匹配候选，服务端用确定性规则（`display_name + sex_code + birth_date` 精确匹配活动患者）计算 `match_score`（唯一命中 1.0、无命中 0.0、多命中 1.0 但留待人工），候选经人工复核决议（匹配既有患者 / 新建患者）后进入 `RESOLVED`。仅 `ACTIVE` 源系统可产生候选，候选身份与评分不可变，同源同标识唯一。

> 命名说明：本表为**迁移源患者匹配**，与既有 V29 `patient_match_candidate`（MPI 重复患者复核队列）语义不同，故命名为 `source_patient_match_candidate` / `SourcePatientMatchCandidate`，避免表名、OpenAPI 架构与路由冲突。

## 2. 高风险验收表

| 验收项 | 硬门/约束 | 证据 |
|---|---|---|
| 仅活动源可产生候选 | `SOURCE_SYSTEM_NOT_ACTIVE` | `givenInactiveSource_whenRecording_thenRejected` |
| 匹配分服务端确定计算 | `computeMatch`（精确人口学匹配），评分不可变触发器 | `givenActiveSourceAndMatchingPatient_whenRecording_thenMatchedScoreOne`、`givenActiveSourceAndNoMatch_whenRecording_thenScoreZero` |
| 同源同标识唯一 | `source_patient_match_candidate_unique` | 服务层/数据库 |
| 决议必附人/时间 | `source_patient_match_candidate_resolve_check` | assert-v131 |
| 决议目标须活动患者 | `PATIENT_INACTIVE` | `givenPendingCandidate_whenResolvingToInactivePatient_thenRejected` |
| 候选身份/评分不可变 | `source_patient_match_candidate_immutable` | `givenCandidate_whenTampered_thenDatabaseRejectsMutation` |

## 3. 自动化门禁

```
scripts/verify.sh → VERIFY_EXIT=0
- contracts test/check：3/3，check 无漂移（377 schemas / 385 outputs / 353 operations）
- AI eval：100/100
- red-team：15 payloads / 12 surfaces
- test-schema.sh：V1–V131 迁移 + 断言，rollback 通过
- backup-restore-verify.sh：通过
- gradle test：118 suites / 471 tests / 0 failures
- web test + build：通过
- security-scan.sh：通过
- verify-traceability.mjs：138/138 FR / 138/138 AC / 138/138 route refs
- generate-route-map.mjs --audit：194/194 routes
```

## 4. 本批实现

- **迁移 V131**：`source_patient_match_candidate`（源系统/源标识/姓名/性别/出生日期/匹配患者/匹配分/复核状态/决议人/决议时间/row_version）；评分 0–1、复核状态一致性、同源同标识唯一、身份不可变触发器、源系统索引。
- **契约**：新增 `SourcePatientMatchCandidate`、`SourcePatientMatchCandidateRecordRequest`、`SourcePatientMatchCandidateResolveRequest` 三 Schema 与 3 端点（list/record/resolve）。
- **模块**：`org.haoemr.archive` 下 `SourcePatientMatchCandidateService`（候选登记 + 确定性匹配 + 复核决议 + 幂等 + 审计/Outbox）、`Controller`、`Exception`、`ExceptionHandler`。
- **测试**：`SourcePatientMatchCandidateApiTest` 6 用例覆盖命中、未命中、决议、决议到非活动患者拒绝、非活动源拒绝、篡改拒绝。

## 5. 未关闭风险

- 匹配规则目前为「精确人口学匹配」，姓名模糊/别名/多候选歧义去重算法未实现（Q01 后续）。
- 未接真实源系统增量读取与试迁执行适配器。
- 按当前优先级，Q01「源盘点/源映射/断点重跑/患者匹配」已全部落地；剩余 A01 审批流/SSE、G01 可视化配置等全局项。
