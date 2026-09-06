# S06 眼科 followup 层·术后随访记录（V120）证据报告

> 日期：2026-08-22
> 切片：`OphthalmologyPostopFollowup`（`ophthalmology_postop_followup`）
> 范围：S06 眼科 followup 层首切
> 结论：**VERIFIED**（本机全量门禁通过）

## 1. 结论

在既有 S06 眼科 record（V61）与 treatment 术前核对（V104）之上，新增 followup 层——术后随访记录。本切片以「术后高眼压（IOP > 21 mmHg）必须附并发症处置说明」为核心硬门，同时施加眼压生理范围、术眼枚举、身份不可变与活动就诊校验四道防线。全部服务端校验在数据库约束与服务层双重落地，并通过审计哈希链 + Outbox 事件与幂等命令记录保证可取证恢复。

## 2. 高风险验收表

| 验收项 | 硬门/约束 | 证据 |
|---|---|---|
| 术后高眼压必须附并发症说明 | `ophthalmology_postop_iop_complication_check`（`iop_mmhg > 21 ⟹ complication_note 非空且 ≥2 字符`）+ 服务层 `OPHTHALMOLOGY_POSTOP_IOP_COMPLICATION_NOTE_REQUIRED` | `givenElevatedIopWithoutNote_whenRecording_thenRejected` |
| 眼压生理范围 | `ophthalmology_postop_iop_check`（`0 ≤ iop_mmhg ≤ 80`）+ 服务层越界拒绝 | `givenOutOfRangeIop_whenRecording_thenRejected` |
| 术眼枚举 | `surgical_eye in ('OD','OS','OU')` + 契约枚举 | record/列表往返一致 |
| 身份不可变 | `ophthalmology_postop_immutable` 触发器（术前身份、术眼、日期、眼压、并发症不可改） | `givenFollowup_whenTampered_thenDatabaseRejectsMutation` |
| 活动就诊校验 | `requireActiveEncounter`（`ARRIVED/IN_PROGRESS/SUSPENDED`） | 服务层校验，非活动就诊返回 `CONTEXT_NOT_PERMITTED` |

## 3. 自动化门禁

```
scripts/verify.sh → VERIFY_EXIT=0
- contracts test/check：3/3，check 无漂移（347 schemas / 355 outputs / 316 operations）
- AI eval：100/100
- red-team：15 payloads / 12 surfaces
- test-schema.sh：V1–V120 迁移 + 断言，rollback 通过
- backup-restore-verify.sh：通过
- gradle test：108 suites / 409 tests / 0 failures
- web test + build：通过
- security-scan.sh：通过
- verify-traceability.mjs：138/138 FR / 138/138 AC / 138/138 route refs
- generate-route-map.mjs --audit：194/194 routes
```

## 4. 本批实现

- **迁移 V120**：`ophthalmology_postop_followup`（租户/患者/就诊/院区/术眼/随访日期/眼压/并发症/记录者/记录时间/row_version）；`surgical_eye` 枚举、眼压范围、高眼压并发症联动三条显式命名约束；身份不可变触发器；患者+日期索引。
- **契约**：新增 `OphthalmologyPostopFollowup`、`OphthalmologyPostopFollowupCreateRequest` 两 Schema 与 `GET/POST /ophthalmology-postop-followups` 两端点。
- **模块**：`org.haoemr.ophthalmology` 下 `OphthalmologyPostopFollowupService`（record/list + 幂等 + 审计/Outbox）、`Controller`、`Exception`、`ExceptionHandler`。
- **测试**：`OphthalmologyPostopFollowupApiTest` 5 用例覆盖记录成功、高眼压附说明接受、高眼压缺说明拒绝、眼压越界拒绝、篡改被数据库拒绝。

## 5. 未关闭风险

- 真实手术排台、OCT/影像来源与知情同意仍未实现（S06 其余层与真实适配器）。
- 术后随访仅存结构化指标，未接入真实眼压设备/影像系统。
- 属于专科包 S01–S10 范畴；按当前优先级，S01–S10 其余层整体后置，先收口全局史诗（G01/A01/A02/Q01/D01/O01）。
