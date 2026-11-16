# HaoEMR

面向诊所、基层及一二三级医院的自主开源 EMR。项目从零实现医疗业务内核，机构和科室差异通过版本化能力包、流程、表单、规则、权限和集成配置处理。

HaoEMR 是独立项目，与 OpenEMR 项目及 OpenEMR Foundation 无隶属、授权或关联关系。

## 产品说明

HaoEMR 将患者档案、门急诊、住院、护理、医技执行、病案管理与 AI 医助放在同一个业务系统中。医生可以围绕当前患者与就诊查看资料、书写病历、处理诊断和医嘱、追踪结果与协作任务，并审核 AI 生成的候选内容。

| 使用场景 | 主要功能 |
|---|---|
| 患者与接诊 | MPI 患者档案、候选匹配与合并、预约挂号、候诊叫号、门急诊接诊、患者时间线 |
| 门诊与住院病历 | 模板书写、自动保存、版本比较、病历质控、签署与更正、住院病程、审签和出院流程 |
| 诊疗与执行 | 诊断、医嘱、用药安全检查、护理记录、给药核验、药房、收费、检验标本和影像申请 |
| 专科工作台 | 妇产、生殖、儿科、新生儿、精神心理、眼科、耳鼻喉、口腔、皮肤和中医，包含记录、治疗、护理、证据、随访与质控页面 |
| 病案与数据 | 病案资产编目、文件查看、完整性检查、借阅管理、数据质量、研究队列和数据集申请 |
| 医院与系统管理 | 组织人员、权限与审计、字典、能力包、业务配置、历史数据迁移、集成配置和运行监测 |
| AI 中心 | AI医助 EVA、医助团队、Agent / Skill / Tool 目录、模型连接、处理授权、评测发布和运行证据 |

后端采用 Java 21、Spring Boot、PostgreSQL、显式 SQL 与 Flyway，前端采用 Vue 3、TypeScript 与 Vite。系统采用模块化单体架构，通过版本化配置支持不同机构与专科。权限、患者与就诊上下文、业务状态、并发版本、操作审计和人工复核共同约束临床写入。

## AI医助 EVA 与 Agent

EVA 提供面向当前患者、当前就诊和当前页面的统一 AI 入口。用户提出任务并选择允许读取的资料范围后，系统按照已发布编排调用对应医助，保存父子任务、工具读取、模型请求、资料来源与候选结果，供医生查看和审核。

| 组成 | 职责 |
|---|---|
| EVA | 接收任务、结合页面与就诊上下文调度医助、汇总结果并展示处理进度 |
| 医助团队 | 按临床摘要、文书、质控、结果、协作、院感、编码收费、资源安排、科研、宣教、血糖、心血管、中医和重症等任务组织专业能力 |
| 医学工作流 | 58 个子医助工作流，定义各项任务的输入要求、执行步骤、证据、输出内容和人工交接条件 |
| Skill | 医学执行要求采用 2.0.0 版本，覆盖 14 类专业要求，并关联治理目录中的 24 项 Skill |
| Tool | 在授权上下文中读取病历、医嘱、结果、任务、知识和业务配置，向运行过程提供可追溯依据 |
| 模型服务 | 管理提供方、模型名称、HTTPS API 地址、密钥、连接测试、发布状态与云端处理授权 |

模型输出包含任务摘要、附有来源的事实、缺失信息和风险提示。文书工作流可以生成字段候选，医生审核采用后继续执行原有业务校验、版本检查和保存流程。模型结果作为候选保留，临床签署与业务执行由具有相应权限的人员确认。

### 模型连接与处理授权

1. 登录后进入「AI中心 → 模型服务」，新建模型 API 配置，填写提供方、提供方支持的模型名称、HTTPS 地址与有效 API Key。
2. 执行「测试连接」，确认连接状态为 `READY`，并确认部署状态为 `ACTIVE`、评估状态为 `APPROVED`。页面提供「发布」操作时，完成发布后进入模型路由。
3. 对允许云端处理的模型，通过「云端处理授权」查看授权类型、资料范围、有效期及撤销入口。连接验证与数据处理授权均通过后，才能处理对应范围的资料。
4. 进入 EVA，选择患者与就诊、填写任务并选择资料范围，发起运行后查看进度、来源与候选结果。

API Key 由后端 `ManagedModelSecretStore` 保存，数据库记录密钥引用，页面仅显示末四位。`HAOEMR_MODEL_SECRET_DIRECTORY` 指定保存目录，`HAOEMR_MEDICAL_AGENT_EXTERNAL_MODELS_ENABLED` 控制外部模型调用。部署时应持久化密钥目录并设置访问权限。

`dev-synthetic` 支持有效期最长七天的 `SYNTHETIC_DEMO` 授权，仅适用于项目模拟租户、经校验的模拟就诊和 DeepSeek 官方服务。允许范围为 `RECORDS`、`ORDERS`、`RESULTS`、`TASKS`、`CONFIGURATION`。管理员可通过 `POST /api/v1/model-deployments/{modelDeploymentId}/data-processing-approvals/synthetic-demo` 创建授权，请求需要登录身份、`AI_PLATFORM_ADMIN` 上下文租约、幂等键、组织、机构、处理依据、允许范围和有效期。到期后需重新授权。真实诊疗资料使用 `CLINICAL` 审批，并提供处理依据、PIA、处理协议、服务地域与保留期限。

医学能力定义位于 `src/main/resources/medical-agents/skills-v1.json` 和 `skill-profiles-v2.json`。

## 合成演示部署

在项目根目录执行以下命令。

```bash
docker compose -f deploy/compose.synthetic.yml up --build
```

浏览器访问 `http://127.0.0.1:4177/`，默认开发账号为 `linwei`，密码为 `HaoEMR-dev!`。

该环境包含公开测试口令、固定合成身份和启动时自动导入的演示数据，只允许本机或隔离测试使用，严禁放入真实患者数据。EVA 的外部模型调用需要配置有效 API Key、通过连接测试并取得对应资料范围的处理授权。部署边界见 [部署说明](deploy/README.md)。

可生成带 SHA-256 清单的本机交付包。

```bash
scripts/package-synthetic.sh
```

## 测试数据注入

### 启动时自动导入

后端在启用 `dev-synthetic` 时执行 `SyntheticDataImporter`，读取项目内的数据文件并写入开发数据库，同时初始化演示机构、账号、权限、字典、模板、能力包和医助配置。就诊资料包含患者、病历、诊断、医嘱、结果及任务，相关导入器补充业务执行数据。

| 数据文件 | 用途 |
|---|---|
| `samples/data/synthetic-clinical-golden-v1.json` | 基础临床病例及文书 |
| `samples/data/synthetic-50-disease-cases-v1.json` | 50 类疾病的门诊、急诊与住院病例 |
| `samples/data/tertiary-data-center-business-v2.json` | 数据中心业务与配置 |
| `samples/data/synthetic-comprehensive-s009-v1.json` | 综合测试使用的数据集 |
| `samples/data/synthetic-record-archive.txt` | 病案资产上传使用的样例文件 |

Docker Compose 演示环境已配置 `SPRING_PROFILES_ACTIVE=dev-synthetic`。本机运行后端时，在配置好数据库连接和 JDK 21 后，通过以下命令启用自动导入。

```bash
SPRING_PROFILES_ACTIVE=dev-synthetic scripts/with-java21.sh ./gradlew bootRun
```

数据库连接读取 `HAOEMR_DB_URL`、`HAOEMR_DB_USER` 和 `HAOEMR_DB_PASSWORD`。导入器使用固定标识与 upsert 维护基础演示记录，重启会再次执行初始化，部分配置也会更新。请使用专用开发数据库。

### 通过页面补充演示记录

仓库提供两个 Playwright 脚本，通过登录页面、业务表单与实际 API 补充演示记录。运行前需要启动前后端、完成基础数据导入，并在本机安装 Node.js、前端依赖与 Chromium。在项目根目录执行以下命令。

```bash
npm --prefix web ci
npm --prefix web exec -- playwright install chromium
npm --prefix web run seed:data:specialty
npm --prefix web run seed:data:all-menus
```

默认访问 `http://127.0.0.1:4177`，使用开发账号 `linwei` 与密码 `HaoEMR-dev!`。可以通过 `HAOEMR_BROWSER_BASE_URL`、`HAOEMR_DEV_LOGIN_USERNAME` 和 `HAOEMR_DEV_LOGIN_PASSWORD` 指定实际地址与开发账号。

| 命令 | 脚本及行为 | 输出报告 |
|---|---|---|
| `seed:data:specialty` | `web/scripts/seed-specialty-menu-data.mjs`，覆盖十个专科的 60 个业务页面，已有记录的页面直接记录检查结果，空页面通过表单创建记录 | `output/playwright/specialty-menu-data-seed.json` |
| `seed:data:all-menus` | `web/scripts/seed-all-menu-data.mjs`，补充配置、临床执行、病案、住院文书和指标等指定业务场景，部分场景会新建记录或更新文书 | `output/playwright/all-menu-data-seed.json` |

需要指定页面时，分别使用 `HAOEMR_SPECIALTY_ROUTES` 和 `HAOEMR_MENU_SEED_ROUTES`，多个路由以逗号分隔。

```bash
HAOEMR_SPECIALTY_ROUTES=obgyn-record,pediatrics-record npm --prefix web run seed:data:specialty
HAOEMR_MENU_SEED_ROUTES=archive-catalog npm --prefix web run seed:data:all-menus
```

查看报告中的逐项状态和 `needs_attention` 数量。脚本遇到未完成场景会返回非零退出状态。全菜单脚本会执行实际业务写入，重复运行可能增加记录或文书版本。注入完成后，可登录临床门户、门诊、住院与专科工作台检查数据，并进入 AI 中心完成模型配置与授权。

演示数据用于开发与隔离测试，外部模型密钥和处理授权由各部署环境配置。

## 生产部署要求

`prod` profile 在启动前校验数据库、OIDC/MFA、CA/时间戳、KMS、对象存储、集成证书和可选 AI 配置。秘密通过 `env://` 或 `file://` 引用，生产环境与 `dev-synthetic` 分别部署。配置示例见 `deploy/production.env.example`。

生产使用需要完成机构身份认证、证书、数据处理审批、医院接口、备份恢复与安全评估。LIS/PACS 等外部系统需要对应的实际适配器和联调验证。首次医院设置、管理员初始化和模块数据包安装的完整流程仍需完善，部署时应核查初始化数据与机构配置。

## 项目目录

| 分类 | 目录 | 内容 |
|---|---|---|
| 后端代码 | `src/` | Spring Boot 业务代码、数据库迁移与 Java 测试 |
| 前端代码 | `web/` | Vue 3 应用、前端测试与构建配置 |
| 接口定义 | `contracts/` | OpenAPI、生成器及生成产物 |
| AI 与安全测试 | `evals/`、`security/` | AI golden eval、红队载荷与可执行安全检查 |
| 工程与部署 | `scripts/`、`deploy/`、`gradle/` | 本机检查、数据库工具、容器与构建工具链 |
| 可运行原型 | `prototype/` | 页面原型与交互验证工具 |
| 项目文档 | `docs/` | 产品、设计、开发与部署说明 |
| 验收资产 | `artifacts/` | 浏览器审计报告和验收脚本 |
| 合成样例 | `samples/` | 开发与测试使用的合成数据 |

## 开发与验证

开发前请阅读 [开发原则](DEVELOPMENT_PRINCIPLES.md)。文档入口见 [项目文档](docs/README.md)，本机配置见 [开发工具链](docs/development/toolchain.md)。

```bash
scripts/dev-db.sh status
scripts/verify.sh
```

## 开源许可与商业授权

HaoEMR Community Edition 按 [GNU Affero General Public License v3.0 only](LICENSE)（`AGPL-3.0-only`）开放源代码。该许可证允许个人和机构使用、修改、部署和商业化，但必须遵守 AGPL。修改后的版本若通过网络向用户提供服务，还须按 AGPL 第 13 条向这些用户提供对应源码。

需要在闭源产品中分发、运营闭源修改版网络服务或协商企业条款的机构，可以申请独立商业授权。商业授权是 AGPL 之外的可选路径，不会减少任何人已经从 AGPL 获得的权利。[商业授权说明](COMMERCIAL_LICENSE.md)

- 品牌和 Logo 使用边界见 [商标说明](TRADEMARKS.md)。
- 参与贡献及社区行为见 [贡献指南](CONTRIBUTING.md)、[贡献者协议](CLA.md)和 [行为准则](CODE_OF_CONDUCT.md)。
- 安全漏洞报告见 [安全说明](SECURITY.md)。
- 第三方依赖与素材状态见 [第三方声明](THIRD_PARTY_NOTICES.md)。
- 源码与协作入口见 [官方源码仓库](https://github.com/LIUgongxi2046/haoemr)。

许可证不构成医疗器械认证、临床有效性保证或生产部署批准。使用真实患者数据前，部署方必须独立完成适用的临床、安全、隐私、数据合规及监管评估。

## 作者与职业介绍

作者为刘浩南（刘浩贤），拥有 12 年医疗健康与产业互联网产品经验，曾任金域医学产品与解决方案部总监，目前从事独立开发，关注广州的 AI 产品总监、AI 产品负责人与 AI 产品经理机会。

- [职业介绍与代表案例](https://doctor-graph.com.cn/about/)
- [HaoEMR 产品介绍与个人工作](https://doctor-graph.com.cn/products/haoemr/)
