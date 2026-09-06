# HaoEMR 剩余任务清单

> 日期：2026-08-23（四层收尾后）
> 基线：可用菜单 **194/194**，`NOT_AVAILABLE` 清零；`npm run build` + `vue-tsc` + `vitest` 17/17 + 浏览器审计 194/194 + 契约 check 全绿。

## 一、按实现形态（全部 194 路由）

1. **生产纵向（真实合成 API）**：临床主链、门急诊/预约、药房/收费/检验/影像/输血/手术/护理、病案/院感/评级/资质、专科 71、治理/字典/能力包/迁移、数据质量/科研/开源、AI 平台目录、管理审计、全局 AI医助小南、三域门户、**门诊随访（V164）** 等。
2. **模拟接口适配器（13，待真实适配器）**：admin-auth / ai-capture / model-connection / model-routing / devices / device-monitoring / integration-connectors / integration-messages / archive-scan / archive-preservation / pathology-workbench / anesthesia-workbench / therapy-workbench。
3. **配置持久化引擎（17，已接真实 `config_item` 后端 V163，幂等+审计+Outbox）**：13 个配置路由 + 4 个编排/策略路由（agent-compose/agent-context/agent-evals/ai-assistant-policy）。
4. **指标快照引擎（5，已接真实 `metric_snapshot` 后端 V165，审计哈希链）**：`data-center`/`research`/`research-stats`/`department-qc`/`quality-center`（指标定义 + 快照记录 + 展示；指标语义聚合计算仍待接）。
5. **前端占位看板（4，待运行时编排/壳层设计）**：`agent`（Agent 受控运行，需接 AgentRun `/ai/runs` 快照 + SSE 事件流）、`ai-center`（AI 枢纽聚合）、`unified-home`（统一工作台壳）、`login-context`（登录/锁屏上下文）。

## 二、本轮（四层收尾）关键成果

- **模拟病人病历数据**：`synthetic-clinical-golden-v1.json` 从 2 病例扩到 5 病例（门诊×2、住院×2、急诊×1），新增急诊 STEMI、心衰住院、高血压门诊，各含患者+就诊+文书+观察+质控发现；后端 importer 幂等 seed。
- **配置持久化引擎（V163 `config_item`）**：通用配置注册表（幂等键 + 审计哈希链 + Outbox + 唯一键冲突 + 乐观锁），17 个配置/编排路由接真实后端。
- **门诊随访（V164 `outpatient_followup`）**：患者+就诊级租约，登记（幂等）/完成（乐观锁 + 结局留痕）。
- **指标快照引擎（V165 `metric_snapshot`）**：通用指标快照注册表（metric_type/名称/值/单位/维度/周期 + 审计哈希链），5 个指标路由接真实后端。
- 新增后端测试：`ConfigurationApiTest`（2）+ `OutpatientFollowupApiTest`（1）+ `MetricSnapshotApiTest`（1）通过；`test-schema.sh` 已接线 V163/V164/V165。

## 三、验证状态

| 项 | 结果 |
|---|---|
| `npx vite build` / `vue-tsc -b` | 0 / 0 |
| `npx vitest run` | 17/17 |
| 浏览器路由审计 | 194/194，0 failures |
| 契约 check（448 schemas） | 0 |
| 新增后端测试（config/followup/mock/audit） | 全通过 |
| 预存 `OutboxDispatcherTest` 2 个异步测试 | ❌ 仍未修（非本轮引入） |

## 四、剩余（按优先级）

1. **指标语义聚合计算**（data-center/research-stats 等 5 个指标路由已接 `metric_snapshot` 快照，但「从临床事实自动聚合出指标」的语义引擎仍待做——当前为手动记录快照）。
2. **Agent 受控运行 UI**（`agent`）：接 AgentRun `/ai/runs` 快照 + `/ai/runs/{id}/events` SSE 事件流。
3. **AI 中心枢纽**（`ai-center`）：聚合 AI 各子页入口。
4. **统一工作台壳 / 登录上下文**（`unified-home`/`login-context`）：壳层聚合 + 登录/锁屏上下文。
5. **13 个真实外部适配器**：P4 NO-GO，真实 IdP/ASR/设备/LIS-PACS-HIS-CA/扫描/存储/病理/麻醉/治疗到位后替换 mock。
6. **G01 图形化设计器**：config_item 已持久化，但 workflow 画布/表单字段/规则条件/职责范围的**图形编辑器 + 校验 + 灰度**待做。
7. **预存 `OutboxDispatcherTest`** 异步测试排查。
8. **D4 逐页像素级人工验收**。

## 五、结论

- 主度量 **194/194** 达成，`NOT_AVAILABLE` 清零。
- 四层里「数据库 + 后端 + 前端」的**可代码部分已基本完成**（迁移到 V165，新增 config/followup/metric/mock/audit/assistant 六个真实域）；剩余是「真实适配器（NO-GO）+ 指标语义聚合 + Agent 运行时接线 + 图形化设计器 + 人工验收」五类，均为需要专门设计或真实依赖的工作。
