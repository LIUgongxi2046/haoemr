# E01 报到生成门诊就诊（预约→就诊链路）测试报告

日期：2026-08-21  
状态：`LOCAL_VERIFIED`（E01 整体仍 `IN_PROGRESS`）  
范围：FR-019/020 / E01 接诊首切（报到生成就诊）

## 结论

预约报到（check-in）在同一事务内完成：`BOOKED → CHECKED_IN`、入候诊队列，并通过公开 `EncounterGateway` 复用病历内核 `createEncounter` 生成 `OUTPATIENT` 就诊（初始状态 `ARRIVED`），回写 `appointment.encounter_id` 形成预约→就诊闭环。就诊复用既有状态机、幂等、审计与 Outbox，不另建一套门诊专用状态。

这一结论只适用于本机合成数据。真实 HIS 挂号与分诊联动、先救治后补登与并发规模性能未验证，不构成生产发布结论。

## 高风险验收

| 编号 | 场景 | 预期 | 证据 |
|---|---|---|---|
| CL-001 | 报到生成就诊 | `appointment.encounter_id` 非空，就诊 `OUTPATIENT`/`ARRIVED` | `AppointmentSchedulingApiTest.givenBookedAppointment_whenCheckedInAndCalled_…` |
| CL-002 | 复用病历内核 | 就诊经 `EncounterGateway.createEncounter` 创建，状态机/幂等/审计/Outbox 一致 | 服务端注入网关 + 就诊状态 `ARRIVED` |

## 自动化门禁

```text
Java: 33 suites / 95 tests / 0 failure（现有报到测试新增就诊链路断言）
Web: 5 files / 17 tests / 0 failure
Contracts: 153 schemas / 161 generated outputs / 117 operations
Database: V1-V42 migration contract PASS; isolated backup/restore PASS
AI eval: 100/100 PASS
Security eval: 15 payloads / 12 surfaces PASS
Traceability: 138/138; route design map: 194/194
Security scan: credential / bundle-identity / inline-secret / profile-isolation PASS
```

## 本批实现

- 新增公开 `EncounterGateway` 接口（`org.haoemr.clinical`），`ClinicalLifecycleService` 实现之并公开 `createEncounter`。
- 新增 `V42__appointment_encounter_link.sql`：`appointment.encounter_id` 列、外键与非空索引。
- `AppointmentService.checkIn` 注入 `EncounterGateway`，报到时生成 `OUTPATIENT`/`ARRIVED` 就诊并回写 `encounter_id`；契约 `Appointment` 增加 `encounter_id`。

## 未关闭风险

- 报到后尚无「接诊→书写病历→诊断/医嘱/随访」的下一步业务动作，就诊停留在 `ARRIVED`，需后续接诊步骤推进到 `IN_PROGRESS`。
- 急诊分级/抢救/护理/留观、先救治后补登与门急诊域间切换仍未实现，E01 保持 `IN_PROGRESS`。
