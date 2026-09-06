# haoemr 开发必读原则

> **每次开发开始前必读**（无论人还是 AI agent）。本文件来自对项目「后端优先、UI 后置」教训的复盘，把根因沉淀为可执行的硬原则。
> **违反这些原则的提交应当被拒绝。**
>
> 关联文档：`docs/process/planning/2026-08-14-...-backlog.md`（权威 backlog）、`docs/process/planning/2026-08-22-handover.md`（纪律/踩坑）、`docs/process/planning/2026-08-22-ui-replan.md`（UI 接线冲刺计划）。

---

## 一、交付哲学（最高优先级，先看这几条）

- **P1　"完成" = 用户屏幕上的菜单可用。** 一个功能只有在其菜单在 UI 可点、连真实 API、状态齐全（loading / empty / error / 权限 / success）才算完成。**后端 API + 测试绿 ≠ 完成。**
- **P2　主度量是「可用菜单数 / 194」，不是 schemas / tests。** 数据库表数、测试数、契约数是辅助指标，不是目标。你测什么，执行者就优化什么。
- **P3　纵向切片 = UI → API → DB 端到端。** 禁止「一张表 + 一个服务 + 一个 API + 测试」这种只切后端一层的横向切片。
- **P4　UI 优先，后端复用。** 优先把已就绪的后端接成页面；**有后端可用时，禁止再新建后端表/服务。**

## 二、架构原则（不可动摇）

- **P5　模块化单体，不微服务化。** 医疗主链强依赖 ACID；禁止为"微服务数量"拆分签署/医嘱事务。
- **P6　PostgreSQL 单一事实源。** 保留 PG，不换 MySQL/Oracle。理由：医疗严谨性（强约束/触发器）+ 信创国产库大多 PG 兼容。
- **P7　医疗硬规则下沉数据库。** 状态机、不可变、双人核验、范围校验等硬门必须在 DB 约束/触发器层兜底，应用层再校验。这是安全底线。
- **P8　显式 SQL + Flyway，不引入 ORM。** 保留 `JdbcClient`/手写 SQL；**禁止引入 MyBatis-Plus / JPA / Hibernate 替换**（会重写 102 个 Service 且丢失 DB 约束优势，零收益）。
- **P9　每个写操作 = 幂等 + 审计 + Outbox。** `beginCommand`（幂等键）→ `appendEvidence`（审计哈希链 + outbox_event）→ `completeCommand`。
- **P10　契约优先。** 先改 `contracts/openapi.json`，再生成 Java/TS 制品；禁止前后端各写一份枚举。
- **P11　Vue 3 单栈。** 一个生产入口、一个路由注册表；禁止 React 或双栈。
- **P12　AI 候选与临床事实物理分层。** AI 只出候选，接受后仍走领域用例重新校验权限/版本再落库。
- **P13　能力包不分叉内核。** 专科差异走能力包；禁止覆盖核心患者/就诊/签署/权限/审计状态机。

## 三、面向中国市场的选型原则

- **P14　Spring Boot 求稳，不追新大版本。** 当前 4.1.0；如需降 3.5.x 属求稳；禁止无充分理由升到更新大版本。
- **P15　信创兼容。** 数据库面向人大金仓 / 华为 openGauss/GaussDB / 达梦的 PG 兼容模式；避免使用国产库不兼容的 PG 冷门语法。
- **P16　可用国内主流加速器，但只做加法。** Element Plus（UI 组件库）、Knife4j（API 文档）等可引入；不因此改动现有代码。

## 四、范围与排序原则

- **P17　先临床主链，后扩展域。** 核心 = MPI 患者→就诊→病历→诊断/医嘱→执行/结果→质控/签署→病案封存→迁移/备份/恢复。AI 平台、科研、开源指标等辅助域在主链可用后再做。
- **P18　参考成熟方案，不闭门造车。** 已有开源 EMR 的领域建模/术语/流程允许借鉴；"从零实现"不等于拒绝学习已有正确解法。
- **P19　专科后置。** S01–S10 专科层在主链 + 治理 + AI 主菜单铺开后再做。

## 五、反模式（明确禁止）

- ❌ 后端堆切片、UI 后置
- ❌ 用 schemas/tests 数字当"完成"汇报
- ❌ 引入 MyBatis-Plus / JPA / Hibernate 替换 JdbcClient
- ❌ 换掉 PostgreSQL
- ❌ 微服务化 / 拆分临床事务
- ❌ 前后端各写一份枚举 / 绕过契约生成
- ❌ 追 Spring Boot 新大版本
- ❌ 从零重造成熟 EMR 已解决的领域建模/术语
- ❌ 手写复刻原型 UI / 自己另写一套 CSS 类名（要复用原型自己的 styles.css + DOM 结构）
- ❌ 只看源码里的导航数组就对 UI（要看实际渲染 DOM + 计算样式）

---

## 六、UI 对齐与前端构建踩坑（2026-08-23 复盘，必读，防止重犯）

> 背景：把生产 Vue 应用对齐高保真原型 `prototype/app/index.html` 时，反复踩坑。以下每条都是真实发生的根因，**违反会重犯**。

1. **原型是唯一视觉真相，不是 `tokens.json`。** `prototype/app/styles.css` 的 `:root` 已演进（`--r:12px`、`--green:#198754`、`--shadow:0 8px 24px` 等），与 `docs/design/ui-delivery/tokens.json` 不一致。对齐视觉一律以 `prototype/app/styles.css` 为准。
2. **不要「看」截图，要「读」计算样式。** 当前模型无图像输入，肉眼看不了浏览器。用 Playwright `page.evaluate(() => getComputedStyle(...))` + `element.textContent` 把「实际渲染的 DOM 结构 + 计算样式」dump 成文本，再对齐。**禁止靠读源码猜测渲染结果。**
3. **原型有多份导航定义，会互相覆盖。** `app.js` 里的 `pages` 是旧定义；实际渲染用的是 `coverage.js` 的 `coveragePages`（`pages.splice` 覆盖）+ `specialties.js` 的 `specialty-center` 拼接。对齐侧栏必须读 `coverage.js`，不是 `app.js`。
4. **复用原型自己的 CSS，不要另写一套。** 生产曾用 `.admin-*`/`.vue-clinical-shell` 自制样式，与原型 `.card`/`.table`/`.metric`/`.status`/`.btn`/`.shell`/`.sidebar`/`.main` 是两套。正确做法：`cp prototype/app/styles.css web/src/prototype.css` + 最后 import，再把生产类名映射到原型值（见 `web/src/align-prototype.css`），不要手写复刻。
5. **壳层换 `.main` 后要清掉 `.content` 的双层 padding。** `.main` 已含 `padding:0 22px 28px`，页面 `.content` 必须归零，否则内容双倍缩进（「间距差太多」主因）。
6. **换类名结构时清理旧子选择器。** 旧 `.nav-item > span { border:1px solid ... }` 会给侧栏每个图标/文字/计数徽标套白线框，且因 `>` 优先级高于 `.nav-icon`/`.nav-count` 而覆盖它们。改结构后必须 grep 清理残留的 `>` 子选择器规则。
7. **CSS 注释里不能出现 `*/` 序列。** 注释里写 `.admin-*/` 这类选择器时，`*/` 会提前结束注释，导致 lightningcss 报 `Unexpected token Delim('/')`。注释中避免 `*` 紧跟 `/`。
8. **重构 `:root` 令牌时，定义块不能参与替换。** 用脚本把 hex 替换成 `var(--...)` 时，`:root` 里的 `--nav:#102a43` 也被替换成 `--nav:var(--nav)` 自引用，导致所有颜色失效。`:root` 定义块必须用字面 hex 值，只有「使用处」才替换成 `var(...)`。
9. **vue-tsc 因内存不足会「假挂起」。** 8GB 机器同时跑 Spring Boot + gradle + 多个 node 进程时，空闲内存不足 20MB，`vue-tsc -b`/`--noEmit` 会无输出挂起。先 `lsof -ti :8080` 确认后端在、必要时 `kill` gradle daemon 释放内存，或用 `npx vite build`（不类型检查）先验证语法/打包。
10. **后端 bootRun 是 gradle daemon 的子进程，杀 daemon 会连带杀后端。** 重启后端前先 `lsof -ti :8080` 确认；不要随手 `kill` 所有 gradle 进程。
11. **整体 `cp` 原型 styles.css 会与生产 CSS 发生同名类冲突，必须逐类核对覆盖。** `prototype.css`（原样拷贝）里的 `.admin-layout{176px 1fr}`（原型管理段左导航布局）与生产 `vue-shell.css` 的 `.admin-layout{minmax(680px,1fr) 330px}`（工作台两栏）同名不同义，前者 import 靠后即覆盖后者，导致 **96 个工作台页主区被压成 176px**。浏览器路由审计不检查列宽，故一直未暴露。正确做法：import 完 `prototype.css` 后，在 `align-prototype.css` 里逐类还原生产语义（`comm -12 <(grep -oE '\.[a-z][a-z0-9-]*' prototype.css|sort -u) <(grep -oE '\.[a-z][a-z0-9-]*' styles.css vue-shell.css|sort -u)` 列出交集逐个核对）。

---

## 附：本文件来源（8 轮复盘摘要）

1. 根因：DoD 与度量都错设成后端指标 → UI 被系统性后置。
2. 结论：继续，不推翻重来；后端是资产，UI 是加法。
3. 架构评估：模块化单体 + PG + 显式 SQL + Vue 单栈 + 契约优先 = 合理甚至优秀。
4. 中国市场：Java + Vue + 关系库是对的，且信创友好；Modulith/MyBatis-Plus 非主流但不必强换。
5. 数据库：医院主流是 Oracle/MySQL/国产库三足鼎立；PG 是"第四选择"，但对信创最省事，保留。
6. 工作量：架构调整本身 1–2 天（文档 + 可选降版 + 加法组件）；真正工作量在 UI 菜单，不在架构。
