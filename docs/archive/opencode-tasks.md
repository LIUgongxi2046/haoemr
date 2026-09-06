# HaoEMR 施工单 001：门诊病历生命周期与 AI 质控纵向切片

> v1.0 完整主 Backlog：`docs/process/planning/2026-08-14-haoemr-v1.0-implementation-backlog.md`。本文件只保留已执行的施工单 001 及其真实证据。

> 状态：`FIRST_VERTICAL_SLICE_IMPLEMENTED_WITH_PRODUCTION_GATES`  
> 目标：交付第一个真实可运行纵切，而不是继续扩展静态原型。范围严格限定为“患者→门诊就诊→病历草稿→确定性质控/AI 候选→签署→审计与 Outbox→恢复验真”。

## 0. 全局实施环境与防线

### 0.1 强制上下文

- PRD：`docs/product/prd/2026-08-11-haoemr-v1.0-prd.md`，重点读取患者、门诊、病历、质控、签署、AI、审计、备份恢复及 AC。
- 架构：`docs/design/architecture/2026-08-14-haoemr-hld.md`。
- LLD：`docs/design/architecture/2026-08-14-haoemr-lld-data.md`、`...-back.md`、`...-agent.md`、`...-front.md`。
- TRB：`docs/process/reviews/2026-08-14-haoemr-trb-review.md`，ACT-001–004 为本切片阻断项。
- 视觉：`docs/design/ui-delivery/tokens.json`、`docs/design/ui-delivery/design-system.md`、`docs/design/ui-delivery/state-matrix.md`。
- 页面锚点：`docs/design/ui-delivery/screens/outpatient.png`、`opd-record.png`、`record-editor.png`、`record-qc.png`、`record-sign.png`、`ai-assistant.png`、`ai-action-review.png`。
- 数据：`samples/data/synthetic-clinical-golden-v1.json`；仅允许合成数据进入开发环境。

### 0.2 不可破坏的不变量

1. 不使用任何现有开源 EMR/HIS 作为底座；通用基础库可以使用并锁版本/许可证。
2. PostgreSQL 是临床事实源；签署后版本不可覆盖；业务、审计摘要与 Outbox 同事务。
3. 所有写命令同时验证身份、机构、患者/就诊上下文、权限、幂等键、预期版本、状态机和规则水位。
4. AI 只产生带来源的候选；AI 停用、超时或失败时手工病历主链必须完成。
5. 线协议统一 snake_case；Java/TypeScript 域内命名由生成 codec/mapper 转换，不维护手写重复枚举。
6. 不连接真实医院接口或真实患者数据；本切片只使用合成患者。

### 0.3 本机现状与工具链门禁

- 已检测：Node 24/npm 11 可用；Java/Javac 仅 8；无 Gradle、PostgreSQL CLI、Docker。
- 目标：JDK 21、Gradle Wrapper、PostgreSQL 18、Node 锁文件；Spring Boot/Modulith 版本按 HLD 锁定，不用动态 `latest`。
- 未完成工具链安装、版本记录、空库迁移和最小 smoke test 前，禁止开始 UI 或 Agent。

## 1. 严格 DAG

```text
T001 Toolchain
  -> T002 Contracts
  -> T003 Data schema/migration
  -> T004 Data repository/recovery fixture
  -> T005 Backend context/security
  -> T006 Backend clinical lifecycle
  -> T007 Backend sign/audit/outbox/recovery
  -> T008 Agent lease/tools/budget
  -> T009 Agent drafting/QC proposal/SSE
  -> T010 Frontend design shell/dumb components
  -> T011 Frontend state/API integration
  -> T012 End-to-end, fault and evidence gate
```

任何工单失败，后续工单保持 `BLOCKED_BY_DEPENDENCY`；不得跳过 Data/Back 直接把 Mock 接入 UI。

## 2. 核心任务队列

### T001：工程工具链与可复现骨架 ✅ DONE

- **层级**：Foundation
- **输入**：HLD 1.3/3、LLD-BACK 1.2、本施工单 0.3。
- **实施**：安装/选择 JDK 21 与 PostgreSQL 18；创建 Gradle Wrapper、Java 模块化后端、React/TypeScript/Vite 前端、`contracts/`、`infra/` 和统一验证脚本；记录精确版本。
- **禁止**：用 Java 8 编译；用全局 Gradle 代替 Wrapper；为赶进度换成 Node 后端；提交默认口令。
- **DoD**：`./gradlew --version` 显示 Java 21；数据库 readiness 通过；前后端空壳可构建；一个根级命令运行 lint/unit；版本清单可审计。
- **验证证据（2026-08-14）**：JDK 21.0.2、Gradle 9.6.1、PostgreSQL 18.4；事务 smoke 回滚正确；`scripts/verify.sh` 全绿；前端主 JS gzip 60.50KB。

### T002：单一线协议与生成契约 ✅ DONE

- **层级**：Data Contract
- **依赖**：T001
- **输入**：TRB ACT-001；LLD-BACK 4；LLD-FRONT 4/6；LLD-AGENT 2/5。
- **实施**：建立 OpenAPI 3.1 与 JSON Schema，覆盖 ApiError、ContextLease、Patient、Encounter、Document/Version、QC、Signature、Outbox read model、AIRun/AIProposal/ContextReference 与 SSE envelope；生成 Java DTO/validator 与 TS codec/types。
- **DoD**：Schema lint 通过；生成过程可重复且工作区无漂移；snake_case→域内 camelCase round-trip 测试通过；非法枚举、未知版本、缺字段和多余字段有明确失败测试。
- **验证证据（2026-08-14）**：14 个 OpenAPI Schema；14 个 Java record + 1 个 TypeScript/Zod 生成文件；`generate --check`、Java compile、2 个契约测试和 3 个前端测试通过。

### T003：临床事实 Schema 与迁移 ✅ DONE

- **层级**：Data
- **依赖**：T002
- **输入**：LLD-DATA 4.2；LLD-BACK 3.2/3.4。
- **实施**：Flyway 迁移 tenant/organization/facility/user role、patient/identifier、encounter、clinical_document/document_version、signature、quality_finding、audit_event、outbox_event、idempotency_record；添加 FK、唯一约束、row_version、状态/时间检查和租户键。
- **DoD**：空库正向迁移成功；重复迁移幂等；约束测试拒绝跨租户引用、重复标识、非当前版本签署和重复幂等键；Schema dump 与 LLD 一致。
- **验证证据（2026-08-14）**：Flyway v1/success=true；15 张表；跨租户/重复标识/非法状态/重复幂等约束通过；测试 schema 事务回滚；`docs/database/v1-schema.sql` 771 行。

### T004：Repository、合成数据与恢复夹具 ✅ DONE

- **层级**：Data
- **依赖**：T003
- **输入**：合成金标、LLD-DATA 6/7。
- **实施**：实现按模块隔离的 Repository；导入合成患者/就诊/病历；创建数据库备份、清空隔离库、恢复、哈希/行数/关系验真的自动夹具。
- **DoD**：没有跨模块直接表访问；金标导入可重复；恢复后患者、就诊、版本链、签名、审计和 Outbox 校验一致；生产 profile 拒绝加载合成 seed。
- **验证证据（2026-08-14）**：模块边界测试通过；合成导入两次后 patient/encounter/document/version=2/2/2/2 且 row_version=1；恢复验证库指纹一致并清理；非 `dev-synthetic` profile 不注册导入器。

### T005：后端身份、ContextLease 与命令八道门 ✅ SLICE DONE

- **层级**：Backend
- **依赖**：T004
- **输入**：LLD-BACK 2/4；LLD-AGENT 2.2；TRB FB-001。
- **实施**：实现开发 OIDC 替身接口边界、组织/岗位作用域、短期 ContextLease、请求头与租约交叉校验、RBAC/ABAC、用途、患者/就诊、幂等、expectedVersion、状态机、审计/Outbox事务切面。
- **DoD**：错机构、错患者、旧标签、过期角色、过期租约、重放、缺幂等键和版本冲突全部被结构化拒绝且不泄露资源存在性；合法读写有 trace/audit 证据。

### T006：患者、门诊就诊与病历草稿生命周期 API ✅ SLICE DONE

- **层级**：Backend
- **依赖**：T005
- **输入**：PRD 对应 FR/AC；LLD-BACK 3/4。
- **实施**：实现 MPI 最小查询/登记、门诊 Encounter 创建/查询、Document 创建、草稿保存、版本查询和 diff；保存服务端不自动合并冲突正文。
- **DoD**：API/模块/Repository 单元与集成测试通过；并发保存只有一个成功，失败方获得当前 ETag 和一次性恢复 token；跨患者文书写入为 0。

### T007：质控、签署、不可变证据、Outbox 与恢复 🟡 PARTIAL（Outbox 核心已验证）

- **层级**：Backend
- **依赖**：T006
- **输入**：PRD 病历生成/质控/签署；LLD-BACK 3.2/3.4/7；LLD-DATA 7。
- **实施**：实现确定性必填/时限/一致性质控；签署事务；更正入口最小契约；hash chain 审计；Outbox dispatcher/consumer 去重；备份恢复验真 API/命令。
- **DoD**：阻断项不能签；警告需记录处置；签署后原版本更新为 0；重复签署/投影重放无重复副作用；杀 Worker 后 Outbox 可续传；隔离恢复验证通过。
- **新增证据（2026-08-14）**：V5 Outbox Dispatcher 已覆盖同聚合有序领取、`SKIP LOCKED`、租约/fencing、事务型消费者去重回执、退避/死信、重放审计和积压对账；专项 3/3、根回归通过。外部连接器仍需各自 Inbox/Outbox 对账，不能把本地投影成功等同于 LIS/PACS 已上线。

### T008：Agent 上下文租约、只读 Tool 与运行预算 🟡 PARTIAL

- **层级**：Agent
- **依赖**：T007
- **输入**：LLD-AGENT 1–5；TRB BA-001/002、AD-003。
- **实施**：实现 AI use-case 默认关闭、AIRun/durable job 分层、Pre/Post Tool Hook、病历/结果只读 Tool、ContextReference、模型适配器接口、持久预算/Deadline/同参哈希/fencing、紧急停用。
- **DoD**：无配置模型时返回可理解的 DEGRADED 且手工主链正常；错患者/越权/过期租约 Tool 调用为 0；同参死循环被物理 Kill；迟到 Worker 结果不能写当前 run。

### T009：病历草拟与质控候选、人工审批和 SSE ✅ FAKE-PROVIDER SLICE DONE

- **层级**：Agent
- **依赖**：T008
- **输入**：LLD-AGENT 5–7；BACK 4.2/4.3；合成金标。
- **实施**：实现可替换的 deterministic fake model 供 CI，以及真实模型 provider 插槽；生成病历段落/质控建议 `AIProposal`；Verifier 校验 Schema、引用、患者、禁止行为；实现 run snapshot/cancel/decision 与序列化 SSE。
- **DoD**：CI 不依赖外部模型；引用可回到准确文书版本/字段；AI 不直接签署或写临床事实；重复/乱序/断档 SSE 可恢复；人工拒绝无副作用，接受后仍经 T006/T007 命令门。

### T010：临床可信蓝壳层与病历 Dumb Components 🟡 SLICE DONE, FULL MATRIX PENDING

- **层级**：Frontend
- **依赖**：T009
- **输入**：指定 7 张 UI 截图、tokens、design-system、state-matrix、LLD-FRONT 2–5。
- **实施**：生成 Token；实现 Shell、唯一一级导航、患者上下文条、门诊工作台、门诊病历列表、专注编辑器、来源抽屉、质控面板、签署对话框、AI 浮动助手和候选差异卡；此任务只接 Story fixtures，不接 API。
- **DoD**：组件覆盖 loading/empty/error/offline/forbidden/conflict/blocked/expired；1280/1600/200% 无主任务横向溢出；键盘、焦点、中文 IME、对比度和 accessible name 自动测试通过；与截图逐页视觉回归。

### T011：前端状态机与全栈绑定 🟡 HAPPY PATH DONE

- **层级**：Integration
- **依赖**：T010
- **输入**：生成 TS codec、LLD-FRONT 4/6、T005–T009 API。
- **实施**：绑定 Query/Command/AIRun store；实现幂等键保留、ETag 冲突 diff、加密会话草稿、ContextLease 切换清理、SSE 去重/sequence/snapshot/AbortController、AI 可停用降级。
- **DoD**：移除页面业务 Mock；切患者后旧缓存/stream/proposal 无法显示或执行；断网草稿可差异恢复；签署超时进入对账；AI 关闭仍可完成病历与签署。

### T012：纵切 E2E、故障注入与证据包 🟡 CORE EVIDENCE DONE

- **层级**：Verification
- **依赖**：T011
- **输入**：PRD 对应 AC、TRB ACT、S009/S010 后续门禁。
- **实施**：Playwright 跑完整纵切；注入并发、断网、数据库重启、Worker kill、SSE 重复/乱序/断档、模型超时、租约过期、错患者、审批过期和恢复；输出 JUnit、覆盖率、视觉 diff、契约报告、恢复报告和已知限制。
- **DoD**：核心 Happy Path 和列出的非 Happy Path 全绿；跨患者/越权/未批准副作用=0；签署版本不可变；恢复验真一致；失败日志不含姓名、证件号或病历正文；证据可由一条命令重跑。

## 3. 切片完成定义

> 2026-08-14 实施快照：门诊病历保存/质控/AI 候选/人工接受/签署不变量、Outbox Dispatcher 核心、恢复验真和真实 API 浏览器主链已完成；真实 OIDC/CA/模型、外部连接器对账、完整故障注入与全状态可访问性回归仍是生产阻断项。上方 `PARTIAL/PENDING` 不得因已有 UI 或文档资产改标为完成。

- [ ] T001–T012 顺序完成，每项有命令输出或测试制品，不以文档勾选替代。
- [ ] 新生产应用真实调用 API 和数据库；`prototype/` 仍只作为设计证据。
- [ ] 纵切覆盖合成患者，不接入真实 PHI。
- [ ] S009 给出测试结论，S010 给出威胁模型与 P0/P1 修复结论。
- [ ] 完成后才创建施工单 002；后续建议顺序：住院病历→医嘱/执行→LIS/PACS→病案/迁移→10 专科包→科研/配置/管理全域。
