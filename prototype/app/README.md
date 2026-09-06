# HaoEMR 可视化交互原型

这是 PRD v0.15 的 Full-Coverage BUILD。SCR-001–175 均已映射到可运行页面，并增加门诊域内病历、真正的全院病历工作队列、10 类核心专科的 70 个专业深页、全局 AI医助小南及完整治理页面。所有数据均为合成数据。

## 运行

从仓库根目录执行：

```bash
python3 -m http.server 4173 --directory prototype/app
```

访问 `http://127.0.0.1:4173/#clinical`。AI医助小南可从任意页面顶栏 `✦` 或右下角入口打开。

## 一级产品域

- 临床工作域：临床门户、门诊工作台、急诊工作台、住院工作站及患者通用能力
- 病历与质量：病历中心、医疗质量中心、病案资产中心
- 业务协同：诊疗执行中心、统一任务与临床路径
- 数据中心：集成、迁移、质量、设备、科研
- AI 中心：AI医助小南、模型、Agent、Skill、Tool、评估与运行治理
- 业务配置：能力包、流程、表单、规则、职责范围、发布与升级
- 系统管理：组织、账户、角色、权限、认证、字典、主数据、参数、作业和审计

## 核心路由分组

- 临床门户：`#clinical`、`#clinical-tasks`、`#credentials`
- 门诊：`#outpatient`、`#opd-record`、`#opd-diagnosis`、`#opd-orders`、`#opd-results`、`#opd-consult`、`#opd-followup`
- 病历中心：`#record`、`#record-editor`、`#record-sources`、`#record-qc`、`#record-sign`、`#record-versions`、`#record-diff`；LIS/PACS 临床调阅：`#lis-report`、`#pacs-viewer`
- 急诊工作台：`#emergency`、`#er-triage`、`#er-record`、`#er-nursing`、`#er-observation`、`#er-handoff`
- 住院：`#inpatient`、`#inpatient-overview`、`#inpatient-course`、`#inpatient-doc-editor`、`#inpatient-doc-qc`、`#inpatient-doc-versions`、`#ip-orders`、`#ip-results`、`#ip-consult`、`#ip-pathway`、`#inpatient-discharge`、`#ward`
- 病案资产：`#archive-assets`、`#archive-catalog`、`#archive-scan`、`#archive-integrity`、`#archive-borrow`、`#archive-preservation`、`#asset-detail`
- 集成与迁移：`#integration`、`#integration-connectors`、`#integration-mapping`、`#integration-messages`、`#migration`
- 科研统计：`#research`、`#cohort-builder`、`#research-stats`、`#research-dataset`
- AI医助小南：`#ai-center`、`#ai-assistant`、`#ai-reminder-detail`、`#ai-capture`、`#ai-action-review`、`#ai-assistant-policy`
- AI 平台：`#models`、`#model-connection`、`#model-routing`、`#model-evaluation`、`#agent-catalog`、`#agent`、`#agent-context`、`#tool-catalog`、`#skill-catalog`、`#agent-compose`、`#agent-evals`、`#aiops`
- 业务配置：`#workflow`、`#capability-pack`、`#form-designer`、`#rule-center`、`#scope-designer`、`#config-release`、`#config-upgrade`
- 科室适配与病理：`#specialty-coverage`、`#pathology-workbench`；院科质控：`#department-qc`
- 核心专科：`#specialty-center`，以及妇产、生殖、儿科、新生儿、精神、眼科、耳鼻喉、口腔、皮肤、中医各自的 `-workbench`、`-record`、`-evidence`、`-treatment`、`-care`、`-followup`、`-qc` 七层路由
- 系统管理：`#admin`、`#admin-org`、`#admin-users`、`#admin-roles`、`#admin-permissions`、`#admin-auth`、`#admin-dictionaries`、`#admin-master-data`、`#admin-templates`、`#admin-parameters`、`#admin-jobs`、`#admin-audit`

## 本轮设计重点

- 病历总览页只负责判断任务，正文编辑、来源、质控、签署和版本比较独立分层，避免固定三栏挤压正文。
- LIS/PACS 既有临床调阅入口，也能反向进入消息 Trace、映射和业务对账。
- 病案资产扩展到目录、扫描编目、完整性验真、借阅复制、长期保存和原件/转换件对照。
- 科研统计绑定项目、伦理、用途、队列版本、数据快照、脱敏、质量报告和到期策略，不回写临床事实。
- 系统管理将组织、人员、账号、岗位、资质、角色、权限和数据范围分离建模，并补齐认证、字典/术语、主数据、模板编号、参数、作业和管理审计。

## 验证口径

- JavaScript 语法检查：`node --check prototype/app/app.js`、`node --check prototype/app/extensions.js` 与 `node --check prototype/app/coverage.js`
- PRD/原型追踪检查：`node prototype/app/verify-traceability.mjs`，要求 FR/AC 138/138 且所有路由引用存在；清单见 `prototype/traceability.csv`
- 浏览器目标：桌面 Web，逐路由验证主标题、主体内容、一级导航高亮、全局 AI 入口、横向溢出和控制台错误
- v0.10 回归结果：123/123 个路由通过；无缺失主标题、无缺失全局 AI 入口、无横向溢出、无多重一级菜单归属。病历、质量、病案之间只通过带“跨域”标识的入口跳转。
- v0.13 增量：门诊“本次门诊病历”固定进入 `#opd-record`，“跨域：全院病历中心”固定进入 `#record`，兼容 `#record` 与 `#/record` 深链；10 个核心专科新增检查/设备证据、诊疗执行和随访交接页，并入原 S01–S10。
- v0.13 回归结果：194/194 路由、70/70 专科页真实浏览器通过；失败路由 0、控制台错误 0、页面错误 0、横向溢出 0。证据见 `prototype/browser-verification-v013.json` 和 `artifacts/playwright/v013/`。
- 自包含离线原型：`prototype/prototype.html`；由 `prototype/build-self-contained.mjs` 生成并嵌入可追溯栅格资产。
- 这不是生产系统，不包含真实患者数据、真实接口、真实签名或医疗设备集成。
