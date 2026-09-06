# HaoEMR v1.0 架构包

本目录把 PRD v0.15、Prototype v0.13、194 条实际页面路由与“临床可信蓝”UI v1.2.0 契约收口为一套可评审、可实现、可测试的系统设计。五份文档共同生效，不能只实现其中一份。

## 1. 阅读顺序

| 顺序 | 文档 | 解决的问题 |
|---:|---|---|
| 1 | [HLD & ADR](./2026-08-14-haoemr-hld.md) | 系统边界、部署单元、技术选型、S/M/L 拓扑、标准与架构决策 |
| 2 | [LLD-DATA](./2026-08-14-haoemr-lld-data.md) | 临床事实、迁移、按需读模型、检索引用、数据质量与恢复 |
| 3 | [LLD-BACK](./2026-08-14-haoemr-lld-back.md) | 领域命令、状态机、事务、API、Outbox、集成和后台作业 |
| 4 | [LLD-AGENT](./2026-08-14-haoemr-lld-agent.md) | 模型切换、Agent/Skill、Hook、Tool、评测和人工批准 |
| 5 | [LLD-FRONT](./2026-08-14-haoemr-lld-front.md) | Vue 3 单栈迁移、194 路由、组件拓扑、病历编辑、SSE、弱网、可访问性和前端门禁 |

Agent 优化版增量设计：

- [Medical Agent Harness LLD](./2026-08-25-haoemr-medical-agent-harness-lld.md)：承接 Agent 优化版 PRD v0.2，参考 DeepSeek Harness 细化主 Agent、诊疗环节子 Agent、Skill、Tool、父子运行、声明式 Composition、追加式轨迹与增量迁移；应在基础 LLD-AGENT 之后阅读。

关联事实源：

- 产品需求：`docs/product/prd/2026-08-11-haoemr-v1.0-prd.md`（138 FR / 138 AC）。
- 路由与原型：`prototype/app/`、`docs/design/ui-delivery/route-design-map.csv`（194 路由，含 70 个专科深页）。
- UI 契约：`docs/design/ui-delivery/tokens.json`、`docs/design/ui-delivery/design-system.md`、`docs/design/ui-delivery/state-matrix.md`。
- 合成金标：`samples/data/synthetic-clinical-golden-v1.json`，仅用于研发和验证，不得混入生产。

## 2. 跨层追踪矩阵

| 需求域 | PRD | 原型/UI | HLD/LLD 责任 | 关键实现对象 | 必须通过的门禁 |
|---|---|---|---|---|---|
| 患者主索引与就诊 | FR-001 起的通用临床内核 | `patient-registry`、`patient-timeline`、门急住工作域 | HLD `clinical-app`；DATA/BACK | Patient、Identifier、Encounter、MergeCase | 重复患者、跨患者上下文、合并撤销与审计 |
| 门诊、急诊、住院 | 门急住和住院文书需求 | `outpatient`、`emergency`、`inpatient` 页面族 | HLD/BACK/FRONT | EncounterContext、Order、Result、Task | 一级归属唯一；三域上下文不得静默串换 |
| 病历创作到病案 | FR-098 起及签署/归档 | `record-*`、`inpatient-doc-*`、`archive-*` | DATA/BACK/FRONT | Document、DocumentVersion、Signature、ArchiveAsset | 已签不可覆盖；来源、质控、签署、封存和恢复可验真 |
| 医嘱、执行与结果 | 协同执行、医技、药房、输血 | `opd-orders`、`ip-orders`、`*-workbench` | BACK + integration-hub | Order、Execution、Result、ExternalMessage | 幂等、状态机、资质、危急值、双人核对、对账 |
| 10 个核心专科 | FR-129–138 | 70 个专科深页 + specialty center | HLD 能力包；BACK/FRONT；AGENT 专科门禁 | SpecialtyPack、Form、Rule、SafetyGate | 母婴/夫妇/年龄/侧别/牙位/隐私/图像/中药等专项门禁 |
| 全科室适配 | FR-125；BR-127 | `specialty-coverage` + 通用门急住工作域 | HLD ADR-007；DATA/BACK/FRONT | DepartmentSupportAssessment、SpecialtyPackRelease | 每科室只能声明通用可用/基础闭环/待交付/暂不支持；逐科室证据升级 |
| 数据迁移与恢复 | 历史病历迁移、备份恢复 | `migration`、`backup`、`release-gates` | DATA 主责，BACK 作业，HLD 部署 | SourceSnapshot、MappingVersion、ReconcileRun、RestoreProof | 原始证据、增量追平、对账、回退、隔离恢复演练 |
| 配置与后台管理 | FR-062–081、108–119 | `workflow`、`admin-*`、`model-*`、`agent-*` | HLD ADR-005；BACK/AGENT/FRONT | ConfigRelease、Policy、ModelRoute、Agent、Skill | 草稿→模拟→审批→灰度→回滚；禁止任意脚本 |
| AI医助小南与病历生成/质控 | FR-070–081、120–124 | 全局 AI FAB、`ai-assistant`、`ai-action-review` | AGENT 主责，DATA 引用，BACK Tool，FRONT 展示 | AIRun、AIProposal、ContextLease、ContextReference、ToolApproval | 错患者/越权/未批准副作用为 0；AI 停机主链可用 |
| 集成与互操作 | LIS/PACS/HIS/CA/设备入口 | `integration-*`、`lis-report`、`pacs-viewer` | HLD integration-hub；BACK/DATA | Connector、ExternalMessage、Mapping、DeadLetter | 原消息留存、幂等、超时、重试、死信、重放、对账 |
| 科研与统计 | 受治理科研需求 | `research-*`、`cohort-builder` | HLD 读模型；DATA 按需检索/分析存储；BACK 作业 | DatasetRequest、PurposeBinding、DeidentificationJob | 不直查生产库；用途/伦理/脱敏/导出水印与过期 |
| 安装、发布与开源结果 | 质量门禁和一级结果 | `install`、`opensource`、`operations` | HLD S/M/L；全部 LLD | ReleaseManifest、SBOM、Backup、UpgradePlan | 可安装、升级继承、安全、数据一致性、恢复；有效下载可验证 |

## 3. 全局字节契约

以下名词在前后端、事件、数据和 AI 中必须同义；变更需同时更新五份文档和契约测试：

- `tenantId / organizationId / userId / patientId / encounterId`：身份与临床上下文，不得从显示文本推断。
- `expectedVersion / ETag / Idempotency-Key`：并发与重试；客户端超时后先对账，不生成新键盲重试。
- `sourceWatermark / authorizationWatermark`：读模型新鲜度和授权水位；旧水位不能伪装成完整当前事实。
- `ContextLease / ContextReference`：AI 的患者范围、版本、定位、哈希、取数时间和授权证据。
- `AIProposal / ToolApproval`：候选与显式批准；候选本身永远不是临床事实。
- `OutboxEvent`：业务、审计摘要与事件同一事务；投影消费者必须幂等并可重建。

## 4. 进入编码前的统一门禁

- [ ] S006 已逐项审查 PRD、原型/UI、HLD、四份 LLD，无未裁决的 P0/P1 冲突。
- [ ] 138 条 FR/AC 与 194 路由的机器追踪仍为 `VERIFIED`。
- [ ] OpenAPI、SSE、事件、数据库迁移和 AI Schema 有版本、兼容策略与合约测试。
- [ ] 首个纵向切片至少贯通“患者→就诊→病历草稿→质控→签署→审计/Outbox→恢复验真”。
- [ ] 合成数据不会进入生产；日志、截图、前端 bundle、错误体和模型调用不泄漏 PHI。
- [ ] AI、搜索、LIS/PACS 或消息系统故障时，核心手工 EMR 主链可完成且状态真实。
