# D01 队列成员计算引擎·成员物化（V127）证据报告

> 日期：2026-08-22
> 切片：`ResearchCohortMember`（`research_cohort_member`）
> 范围：D01 数据中心·队列成员计算引擎首切
> 结论：**VERIFIED**（本机全量门禁通过）

## 1. 结论

在既有 `research_cohort`（V81，纳入/排除标准）与 `research_cohort_snapshot`（V95，成员数快照）之上，新增队列成员物化层：服务端把满足条件的患者物化进队列成员表，成员一旦计算即不可变（审计留存，重算 = 新成员/新快照），同一队列同一患者至多一个成员（唯一约束），且仅 `ACTIVE` 队列与 `ACTIVE` 患者可参与计算。三处硬门闭合「合成队列可复算」的第一步——成员清单可物化、可追溯、可去重。

## 2. 高风险验收表

| 验收项 | 硬门/约束 | 证据 |
|---|---|---|
| 仅活动队列可计算成员 | `requireActiveCohort` + `RESEARCH_COHORT_INACTIVE` | `givenInactiveCohort_whenComputing_thenRejected` |
| 仅活动患者可入队 | `requireActivePatient` + `PATIENT_INACTIVE`（含 DECEASED/VOID 等非 ACTIVE） | `givenInactivePatient_whenComputing_thenRejected` |
| 同队列同患者去重 | `research_cohort_member_unique` 唯一约束 | `givenDuplicateMember_whenComputing_thenRejected` |
| 成员不可变 | `research_cohort_member_immutable`（update/delete 阻断） | `givenMember_whenTampered_thenDatabaseRejectsMutation` |

## 3. 自动化门禁

```
scripts/verify.sh → VERIFY_EXIT=0
- contracts test/check：3/3，check 无漂移（367 schemas / 375 outputs / 342 operations）
- AI eval：100/100
- red-team：15 payloads / 12 surfaces
- test-schema.sh：V1–V127 迁移 + 断言，rollback 通过
- backup-restore-verify.sh：通过
- gradle test：114 suites / 450 tests / 0 failures
- web test + build：通过
- security-scan.sh：通过
- verify-traceability.mjs：138/138 FR / 138/138 AC / 138/138 route refs
- generate-route-map.mjs --audit：194/194 routes
```

## 4. 本批实现

- **迁移 V127**：`research_cohort_member`（租户/成员/队列/患者/计算人/计算时间/row_version）；同队列同患者唯一约束、不可变触发器（update/delete 阻断）、队列索引。
- **契约**：新增 `ResearchCohortMember`、`ResearchCohortMemberComputeRequest` 两 Schema 与 2 端点（list/compute）。
- **模块**：`org.haoemr.research` 下 `ResearchCohortMemberService`（成员物化 + 活动队列/活动患者硬门 + 幂等 + 审计/Outbox）、`Controller`、`Exception`、`ExceptionHandler`。
- **测试**：`ResearchCohortMemberApiTest` 5 用例覆盖物化成功、重复拒绝、非活动队列拒绝、非活动患者拒绝、篡改拒绝。

## 5. 未关闭风险

- 纳入/排除标准的真实结构化评估引擎（从自由文本标准到可执行过滤）仍未实现，本切片是「成员物化」底座，不声称已完成真实成员计算。
- 队列统计口径（跨队列聚合/小样本抑制）未实现。
- 按当前优先级，D01 成员物化已落地；后续继续 A01 审批流/SSE、A02 限频/转任务、Q01 源映射/患者匹配等全局史诗。
