# HaoEMR 结项前优化版本 —— 修复任务计划

- 日期：2026-09-02
- 性质：结项前最后优化版本（UI 规范、数据真实化、文案本地化、Agent 增强、质量门禁收尾）
- 当前代码：`main` @ `4ecde7c`
- 依据：人工走查 + 全量回归（Java 775/0、Web 160/160）+ 8/31 测试报告遗留项

---

## 0. 目标与原则

1. **消除可见的「演示感」**：写死的序号、英文装饰文案、原生下拉箭头不一致、状态枚举原样吐英文。
2. **数据真实化优先于视觉**：能接后端真实接口的接接口，接不了的先移除假数据（不误导验收）。
3. **低风险优先、大改分步**：CSS/文案类先做（零风险），Agent 增强拆成可独立交付的骨架。
4. **所有改动以测试/回归为门禁**：每阶段收尾跑目标测试 + 生产构建。

---

## 1. 问题总览

| # | 问题 | 根因 | 影响面 | 优先级 |
|---|---|---|---|---|
| 1 | 下拉选择框 icon 不规范 | 原生 `<select>` 未做 `appearance:none` + 自定义箭头 | 20+ 文件 ~30 处 | P1 |
| 2 | 主菜单序号是写死的假数据 | `ClinicalShell.vue` 硬编码 `count` 字符串 | 1 处（12 个菜单） | P1 |
| 3 | 前端写死/假数据 | 多处硬编码（见 3.3） | 若干 | P1–P2 |
| 4 | AI 医助 Eva 的 agent 设计可优化 | 缺运行时编排层、缺评测闭环、agent 种类少 | 后端 agent 模块 | P2（分步） |
| 5 | 文案不符合中国医疗语境 | 英文装饰文案 + 状态枚举未本地化 | 若干 | P1 |
| 6 | CI「verify」路由审计失败 | 未知路由检查崩溃 + 部分路由 400 | 1 脚本 | P1 |
| 7 | 8/31 测试报告遗留 P1 | 预约挂号写链无 E2E、系统管理 CRUD 2/6、DeepSeek 红队 BLOCKED | 若干 | P2（部分外部依赖） |

---

## 2. 修复方案评估（每项「最合适」方案）

### 2.1 下拉选择框 icon —— 全局 CSS 统一（推荐）

- 方案：`web/src/styles.css` 统一 `select { appearance: none; background-image: <chevron svg>; background-repeat: no-repeat; background-position: right 10px center; padding-right: 30px; }`，一次覆盖所有原生 select，不逐文件改。
- 理由：一处改动覆盖 30 处，零逻辑风险，视觉彻底统一。
- 补充：给缺 `aria-label` 的下拉补可访问名称（统一规范）。

### 2.2 主菜单序号 —— 方案 B 先移除 + 方案 A 并行（推荐）

- 方案 A（长期最优）：后端新增 `GET /api/v1/dashboard/nav-counts`，按当前角色返回各模块真实待办/未读数，前端 shell 挂载时拉取。
- 方案 B（结项稳妥）：先移除 `count` 假数字徽标，避免验收时被指「假数据」；接口就绪后再补回真实数。
- 评估：结项前建议 **B 立即做 + A 作为独立后端任务排入**。若 A 工期可控，优先 A。

### 2.3 前端假数据 —— 逐个接真实接口或移除

| 项 | 方案 |
|---|---|
| `QualityCenterPage` `/39` 分母 | 由 `loadSpecialtySupportAssessments` 的真实总数替换，不再写死 |
| 状态枚举英文（ACTIVE/DISPENSED/...） | 复用/扩展统一 `stateLabel()` 中文映射，所有指标卡走映射 |
| `developmentDefaults` 默认患者/就诊 UUID | 保留（dev 环境兜底，生产走 OIDC 会话，属预期行为，需在代码注释标注清楚） |

### 2.4 Eva agent 增强 —— 分三步（推荐）

- 第一步（骨架）：新增轻量**运行时编排层** `AgentOrchestrator`，按「任务类型 × 上下文」路由到既有 5 个医助团队，替代静态固定调用。
- 第二步（补 agent）：优先补 3 个高价值：**院感监测 agent**、**医保/费用合规 agent**、**随访与患者宣教 agent**（复用既有 `AgentRegistry/SkillRegistry/ToolRegistry` 注册机制）。
- 第三步（评测闭环，可后置）：把真实模型评测接入 `check-red-team.mjs` / `model_evaluation`，解除 `INTEGRATED_BLOCKED`。
- 评估：结项前至少完成第一步 + 第二步的注册骨架（1–2 个 agent 落地）。

### 2.5 中文语境文案 —— 全量本地化（推荐）

- 方案：扫掉所有英文装饰 eyebrow/标题（QUALITY & SAFETY、AI CAPABILITIES、TEAM QUEUE 等）→ 中文；状态枚举统一走中文映射。用一次 `grep -rnE "[A-Z]{2,}" web/src/vue` 全量清单驱动修改。

### 2.6 CI 路由审计 —— 修复脚本 + 清理 400（推荐）

- 方案：① 未知路由检查改为优雅记失败（已在做）；② 跑完整 194 路由审计，按 `failedResponses`/`consoleIssues` 清单逐个清理 400 与 console 报错；③ 保持 `verify.sh` 全绿。

### 2.7 测试报告遗留 —— 分外部/内部

- 预约挂号完整写链：补自动 E2E 脚本（预约→报到→叫号→接诊→退号）。
- 系统管理 CRUD 2/6：更新验收脚本契约后重跑。
- DeepSeek 真实模型红队：**外部依赖（模型制品/引擎/硬件/阈值）**，标记为「外部阻塞」，不阻塞本次结项。

---

## 3. 任务拆解（按阶段）

### Phase 0 —— 收口当前未提交改动（立即）
- [ ] 确认 `web/src/clinical-api.ts` 防御守卫（无就诊上下文返回空数组）与 `web/scripts/verify-browser-routes.mjs` 崩溃修复是否保留，一并提交。

### Phase 1 —— UI 规范 + 文案本地化（P1，零风险，先做）
- [ ] **T1.1** select 统一样式：`styles.css` 加 `appearance:none` + 自定义 chevron。
  - 验收：浏览器内所有下拉箭头一致；无原生小三角。
- [ ] **T1.2** 英文装饰文案本地化：QUALITY & SAFETY / AI CAPABILITIES / TEAM QUEUE / RESPONSIBILITY / DELIVERY / PATHWAY GOVERNANCE / TASK RULES / TRUSTED DATA CENTER 等 → 中文。
  - 验收：`grep -rnE "[A-Z]{2,}" web/src/vue` 仅剩代码/枚举，无可见装饰英文。
- [ ] **T1.3** 状态枚举中文映射：统一 `stateLabel()`，指标卡 ACTIVE/DISPENSED/PREPARED/ACCEPTED/DRAFT → 中文。
  - 验收：所有指标卡 `<small>` 显示中文状态。

### Phase 2 —— 假数据治理（P1）
- [ ] **T2.1** 移除/接真主菜单序号：先移除 `count` 假徽标（方案 B）；后端 `nav-counts` 接口另立任务。
- [ ] **T2.2** `QualityCenterPage` `/39` 分母改为真实总数。
- [ ] **T2.3** `developmentDefaults` 补注释说明「仅 dev 合成环境」。

### Phase 3 —— Eva agent 增强（P2，分步）
- [ ] **T3.1** 新增 `AgentOrchestrator` 运行时路由骨架。
- [ ] **T3.2** 注册 1–2 个新 agent（院感监测 / 医保合规 / 随访宣教三选优先）。
- [ ] **T3.3**（可后置）真实模型评测闭环。

### Phase 4 —— 质量门禁收尾（P1–P2）
- [ ] **T4.1** CI 路由审计：跑完整审计 → 清 400/console 报错 → verify 全绿。
- [ ] **T4.2** 预约挂号完整写链 E2E 脚本。
- [ ] **T4.3** 系统管理 CRUD 脚本契约更新 + 重跑。
- [ ] **T4.4**（外部）DeepSeek 红队——标记阻塞，待外部依赖。

---

## 4. 风险与依赖

| 风险/依赖 | 说明 | 应对 |
|---|---|---|
| select 全局样式 | 可能影响个别自定义布局 | 只改 `appearance` + 箭头，保留其余尺寸；构建后全路由抽检 |
| 主菜单真实待办数 | 需后端新接口，语义未定 | 先移除假数字，接口另行排期 |
| Eva agent 增强 | 工作量大 | 只做骨架 + 1–2 个 agent 落地，评测闭环后置 |
| DeepSeek 红队 | 外部制品/引擎/硬件/阈值 | 标记外部阻塞，不影响结项 |
| CI 路由审计 | 194 路由全量跑慢（~15min） | 本地后台跑 + 增量复跑目标路由 |

---

## 5. 建议执行顺序

1. **Phase 0**（提交当前改动）→ **Phase 1**（UI + 文案，半天内可完）→ **Phase 2**（假数据）→ **Phase 4.1**（CI 收尾）→ **Phase 3**（Agent，量力）→ **Phase 4.2/4.3**（E2E 补脚本）。

每阶段完成后：目标测试 + `npm run build` + 提交一个独立 commit，保持历史可回滚。
