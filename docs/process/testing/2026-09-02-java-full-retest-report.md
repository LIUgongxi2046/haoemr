# HaoEMR Java 全量回归复跑报告

- 复跑日期：2026-09-02（Asia/Shanghai）
- 复跑依据：`2026-08-31-s009-pc-full-functional-s010-security-report.md`（该报告冻结提交 `c50c7f8`，总体结论 NO_GO）
- 当前代码：`main` @ `6def1df`（含全院病历中心、医疗质量中心、病案资产中心、住院药房迁移）
- 复跑环境：**干净隔离库**（重建 `haoemr_dev`，0 表，Flyway 全量重迁移 232 个迁移至 V342）；`dev-synthetic` profile；为 prod 门禁补充归档环境变量
- 总体结论：**Java 全量 775 项 / 6 失败**（对比报告 714 项 / 2 失败）；Web 单测 160/160 通过

## 1. 复跑环境修正说明

上一轮（打在同名但脏的 dev 库上，且与运行后端并发）出现了 22 项失败，其中大量是脏数据/并发噪声。本轮为得到确定结论做了两处修正：

1. 停掉运行中的后端（8080），重建干净 `haoemr_dev` 库，使 Flyway 从头迁移、数据导入从零开始。
2. 补充 `ProdSecurityApiTest` 因归档生产化新增门禁而缺失的三项环境变量（`haoemr.archive.ocr-endpoint`、`cda-validation-endpoint`、`malware-scanner.host`），该测试 5/5 恢复通过（与报告「归类为环境配置」一致）。

修正后，脏库噪声（MedicalAgentHarnessApiTest 的 outbox 外键冲突、TertiaryTaskCenterDataImportTest 的 DuplicateKey、OutboxDispatcherTest 的 PENDING/PUBLISHED）全部消失。

## 2. 结果概览

| 项 | 报告（8/31） | 本次复跑 | 变化 |
| --- | --- | --- | --- |
| Java 全量 | 714 项 / 2 失败 | 775 项 / 6 失败 | 新增 61 项测试；失败 2 → 6 |
| Web 单测 | 131/131 | 160/160 ✅ | 全通过 |
| ProdSecurityApiTest | 5 项环境配置失败 | 5/5 通过（补 env 后）✅ | 环境问题已消除 |
| 业务配置导入 | 2 项稳定失败 | **3 项失败** | 未修复，且扩大到 3 项 |
| ClinicalTaskApiTest | 通过 | **2 项失败（403）** | 报告后新引入回归 |
| SpecialtySupportApiTest | 通过 | **1 项失败（错误码漂移）** | 报告后新引入回归 |

## 3. 6 项真实失败明细

### 3.1 业务配置导入数据口径（3 项，报告的「2 项稳定失败」仍在且扩大）

| 测试 | 期望 | 实际 |
| --- | --- | --- |
| `TertiaryBusinessConfigurationImportTest.devSyntheticProfileImportsCompleteTertiaryHospitalBusinessConfiguration` | AI 中心页面不保留 active simulation profiles = 3 | 0 |
| `TertiaryBusinessConfigurationDatasetImportTest.devSyntheticProfileImportsTheCompleteTertiaryHospitalBusinessConfigurationDataset` | configurations = 8 | 0（且 `complete`=0） |
| `TertiaryBusinessConfigurationDatasetImportTest.catalogProvidesEightDistinctTertiaryHospitalProfilesForEveryBusinessConfigurationType` | 8 个三级医院画像全类型 | 目录断言失败 |

根因：`SyntheticDataImporter` / 三级医院业务配置数据集导入实际落库 **0 条**（计数口径或导入链路失配），与报告「models=6 vs 1、tools=24 vs 28」属同一根因的延续，且由「数量不对」演变为「导入为 0」。归属：`feat(admin): productionize system administration workflows`、`feat(mock): productionize interface simulation workflows` 等报告后提交对 `SyntheticDataImporter`/`TertiaryBusinessConfigurationDataset` 的改动。

### 3.2 ClinicalTaskApiTest（2 项，报告后新引入回归）

| 测试 | 期望 | 实际 |
| --- | --- | --- |
| `givenAnOrderExecutionTask_whenViewedClaimedAndExecuted_thenOnlyTheSourceCanCompleteIt` | 200 | 403 |
| `givenAClaimedTask_whenDelegatedTransferredAndEscalated_thenResponsibilityChainIsComplete` | 200 | 403 |

根因：任务「领取/执行/转派」链路返回 403（授权判定收紧或上下文校验变化），由提交 `36658b7 feat: productionize AI and clinical workflows` 引入（`ClinicalTaskService` 在该提交后未再改动）。

### 3.3 SpecialtySupportApiTest（1 项，报告后新引入契约漂移）

| 测试 | 期望 | 实际 |
| --- | --- | --- |
| `givenMissingEvidence_whenClaimingPositiveSupport_thenTheDeclarationIsRejected` | 错误码 `SAFETY_GATE_MISSING` | 错误码 `PACK_REQUIRED` |

根因：正向专科支持声明的错误码由 `SAFETY_GATE_MISSING` 改为 `PACK_REQUIRED`（测试未同步更新），由提交 `cf855e2 feat(config): productionize business configuration` 引入。

## 4. 报告 §3.2 的 P1/P2 问题现状（代码级核对）

| 报告问题 | 现状 |
| --- | --- |
| AI 医助 composer `<textarea>` 无 label/aria-label | ✅ 已修（`GlobalAiAssistantDialog.vue` 加 `aria-label="向 Eva 描述诊疗任务"`） |
| 护士/药师无独立角色 | ✅ 已修（`LoginContextPage` 有护士 `jiahui.xu`、药师 `qinghua.deng`） |
| 入院页缺「入院途径」 | ✅ 已修（`AdmissionBedPage` 有门诊/急诊/转院/其他） |
| 班次号源配置标题契约失配 | ✅ 已重构（「班次号源维护已移至业务配置」） |
| 病历写入脚本等 `.record-prototype-metrics` | ✅ 已修（改 `.record-metrics`；病历中心回归 34/34） |
| 病案资产写入脚本延时 Promise 未观测 POST | ✅ 已修（`waitForResponse`；病案资产回归 24/24） |
| 患者切换脚本 `.queue-patient` | ✅ 已修（改 `data-select-outpatient-patient`） |
| 预约挂号完整写链（班次选择超时） | ⚠️ UI 已重构（可预约班次选择器已存在），但无自动 E2E 脚本验证全链 |
| 系统管理 CRUD 脚本 2/6 | ⚠️ 未自动重跑 |
| DeepSeek 真实模型红队 | ❌ 仍外部阻塞（模型制品/引擎/硬件/批准阈值未配置） |

## 5. 结论

1. **报告的「业务配置导入 2 项稳定失败」未修复**，且扩大到 3 项（`SyntheticDataImporter` 导入落库 0 条）。
2. **报告之后新引入 3 项回归**：`ClinicalTaskApiTest` 2 项 403（`36658b7`）、`SpecialtySupportApiTest` 1 项错误码漂移（`cf855e2`）。
3. **报告里 5 个 NO_GO 原因中的产品/可访问性问题大多已修复**：AI composer aria-label、护士/药师角色、入院途径、班次号源重构；DeepSeek 红队仍为外部阻塞。
4. 本轮未修改任何业务代码；测试环境修正（干净库 + 归档 prod 环境变量）使 ProdSecurityApiTest 5/5 通过，属环境配置而非产品缺陷。

## 6. 建议修复顺序

1. 修复 `SyntheticDataImporter` 三级医院业务配置数据集导入落库 0 条的问题（对齐计数口径），使 3 项导入测试零失败。
2. 定位 `ClinicalTaskService` 任务领取/执行 403 回归（`36658b7`），恢复任务授权契约或更新测试。
3. 同步 `SpecialtySupportApiTest` 期望错误码为 `PACK_REQUIRED`（或确认是否应回退为 `SAFETY_GATE_MISSING`）。
4. 为「预约挂号→报到→叫号→接诊→退号」补自动 E2E 脚本，验证完整写链。

## 7. 证据位置

- Java 测试报告：`build/reports/tests/test/index.html`
- Java 测试 XML：`build/test-results/test/TEST-*.xml`
- 编译警告（5 条 Jackson 过时 API，P2 工程债）：`ConfigurableArchiveOcrEngine`、`SyntheticDataImporter`、`TertiaryDataCenterDataset`、`SyntheticDiseaseCaseCatalog` 等
