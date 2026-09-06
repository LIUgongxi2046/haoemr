# HaoEMR S009 全量接口与 UI 复测报告

## 1. 结论

- 发布建议：**NO_GO**。
- 仓库内确定性缺陷已修复：全局 AI modal、AI Run SSE 401 失败关闭、唯一 main landmark/main-content ID、迁移页可访问名称和 DeepSeek 生产 adapter/harness 骨架已完成；614/614 Java/API、430/430 API 在线失败关闭、388/388 严格 UI 门禁通过。
- 仍为 NO_GO 的原因：真实 DeepSeek 模型制品/hash、量化、推理引擎、硬件、临床质量/性能/恢复阈值均未批准或执行；生产 OIDC/CA/KMS/医院外部系统仍未实联。
- 精确像素、间距和行距一致性：**INSUFFICIENT_EVIDENCE**。194 张 1440×1000 设计基准图存在，但尚无批准的像素阈值、动态数据遮罩和可执行比较器。现有计算样式门禁没有发现 shell 主间距、页头高度、标题字号、行高下限或横向溢出错误，不能据此宣称所有页面已像素对齐。

## 2. 范围与环境

| 项目 | 当前证据 |
|---|---|
| 候选 commit | S009 修复基线 `709ea86`；顶栏按钮补充修复尚未 commit/push |
| 后端 | Java 21.0.12.1、Spring Boot 4.1.0 |
| 数据库 | PostgreSQL 18.4；首轮审计 `127.0.0.1:55433/haoemr_s009`，修复后回归 `127.0.0.1:55432/haoemr_dev`；Flyway V1–V166，全部合成数据 |
| 前端 | Node 24.13.0、Vue 3、Vite 8.2.1 |
| 浏览器 | Playwright 1.61.1 Chromium；1280×800 与 390×844 |
| 数据集 | `s009-comprehensive-v1`，seed `20260824`，CC0-1.0，全部合成 |
| 功能分母 | 430 OpenAPI operations；194 Vue routes；155 Java suites / 614 tests |

## 3. 新增测试资产

| 资产 | 数量/作用 | 状态 |
|---|---|---|
| `samples/data/synthetic-comprehensive-s009-v1.json` | 10 个合成角色、32 个患者边界画像、7 类文本边界、5 类网络、3 类并发 | CREATED |
| `docs/process/testing/2026-08-24-s009-comprehensive-test-matrix.csv` | 2,560 条用例；UI 1,164 条，API operation 全量契约/未授权/非法输入/幂等并发，另含 AI harness | CREATED |
| `scripts/audit-api-surface.mjs` | OpenAPI/治理索引静态审计 + 430 operation 在线失败关闭检查 | OBSERVED |
| `web/scripts/verify-comprehensive-ui.mjs` | 194 路由 × 2 视口的结构、计算样式、可访问性和 AI 弹窗门禁 | OBSERVED |
| `evals/check-deepseek-harness.mjs` + `evals/deepseek/` | DeepSeek adapter/provider 契约、制品锁定、指标与恢复能力失败关闭门禁 | OBSERVED/INTEGRATED_BLOCKED |

矩阵中的 `CREATED` 表示测试已设计并生成，不代表 2,560 条均已形成独立在线正向执行证据。本轮执行分母见下一节。

## 4. 实际执行结果

| 层级 | 命令/证据 | 结果 |
|---|---|---|
| Java/API 集成 | `./gradlew test --rerun-tasks` | **614/614 PASS**；155 suites；0 fail/error/skip；Gradle 9m；JUnit 213.229s |
| OpenAPI 契约 | `npm --prefix contracts test` + `check` | **4/4 PASS**；452 schemas / 460 outputs |
| Web 单元 | `npm --prefix web test` | **23/23 PASS**；8 files |
| API 全表面静态 | `node scripts/audit-api-surface.mjs` | **430/430 PASS** |
| API 在线失败关闭 | `HAOEMR_API_BASE_URL=http://127.0.0.1:8081/api/v1 HAOEMR_API_SECURITY_MODE=dev-synthetic node scripts/audit-api-surface.mjs` | **430/430 PASS**；`streamAiRunEvents` 未认证实测 401；0 failure |
| 路由功能桌面 | 1280×800 `test:routes:browser` | **194/194 PASS**；0 semantic/console/HTTP/overflow failure |
| 路由功能移动 | 390×844 `test:routes:browser` | **194/194 PASS**；0 semantic/console/HTTP/overflow failure |
| 严格 UI 门禁 | `test:ui:comprehensive` | **388/388 route-viewports VERIFIED**；0 findings；两视口 AI 打开/发送/SSE 回复/URL 保持/Esc/焦点还原通过 |
| AI 合成集 | `node evals/check-golden.mjs` | **100/100 PASS**，仅 `DETERMINISTIC_FAKE` |
| 红队载荷 | `node security/check-red-team.mjs` | **15/15 PASS**，12 surfaces |
| DeepSeek adapter 定向 | `./gradlew test --tests org.haoemr.agent.DeepSeekClinicalModelProviderTest` | **4/4 PASS**；JSON object 契约、非法回复失败关闭、file secret ref、HTTP/内联密钥拒绝 |
| DeepSeek harness | `node evals/check-deepseek-harness.mjs` + `run-harness.mjs` | adapter/harness 结构无缺失；**INTEGRATED_BLOCKED / INSUFFICIENT_EVIDENCE / NO_GO**，0 真实模型用例 |
| 语义/追溯 | semantic contract + traceability + route map | **PASS**；194/194 routes，138/138 FR/AC |
| 安全扫描 | `scripts/security-scan.sh` | **PASS** |
| 生产构建 | `npm --prefix web run build` | **PASS**；577 modules；全局 AI 对话已异步分块；主 chunk 538.99 kB 警告 |
| 顶栏按钮专项 | `npm --prefix web run test:ui:topbar` | **19/19 PASS**；HaoEMR 品牌、医院、角色、搜索、AI、指引快捷动作、通知状态与跳转、账户/登录、专科菜单隐藏，覆盖桌面与移动 |

## 5. 缺陷清单

### S009-DEF-001 · 全局 AI 入口不是弹窗式随行交互（P0，FIXED/VERIFIED）

- 复现：在 `#/clinical` 点击 `aria-label="打开AI医助小南"`。
- 实际：没有 `role=dialog`；URL 从 `#/clinical` 变为 `#/ai-assistant`。
- 影响：当前工作页、患者/就诊/任务视觉上下文被替换，无法满足“任何授权页面随时交互”的交互要求。
- 证据：两个视口均出现 `AI_NOT_OPENED_AS_DIALOG` 和 `AI_LAUNCH_CHANGED_ROUTE`。
- 修复后：shell 级原生 modal dialog 保留当前 route，按页面域绑定机构或患者/就诊 ContextLease；原生 modal 提供背景 inert/焦点限定，补 Esc、安全区和焦点还原。两视口交互实测通过。

### S009-DEF-002 · DeepSeek provider harness 未集成（P0，CODE_FIXED/EXTERNAL_BLOCK）

- 缺少：`evals/deepseek/harness.config.json`、`evals/deepseek/run-harness.mjs`、`evals/deepseek/provider-contract.json`。
- Java provider 仅有 `DeterministicFakeClinicalModelProvider`，没有 DeepSeek adapter。
- 当前无法报告模型权重/量化/引擎/硬件、质量、TTFT、tokens/s、显存、超时/取消/恢复或方差；禁止宣称 DeepSeek 已可用。
- 修复后：新增仅 `prod` + AI enabled 注册的 HTTPS adapter，仅读 `env://`/`file://` 密钥引用，使用 OpenAI-compatible Chat Completions JSON object 契约，输出仍经 `AgentOutputGuard`，provider 失败转 `DEGRADED`。harness 文件已存在，但未获批的制品/硬件/阈值被正确报为 `INTEGRATED_BLOCKED`，本项不标记实模 `VERIFIED`。

### S009-DEF-003 · AI Run SSE 未认证请求返回 500（P1，FIXED/VERIFIED）

- 接口：`GET /api/v1/ai/runs/{run_id}/events`。
- 预期：dev-synthetic 下稳定 400/401/403 失败关闭；prod 应为 401/403。
- 实际：500；后台堆栈根因为 `ClinicalAccessDeniedException` 从 `AgentRunController.events` 逸出。
- 对照：普通 AI Assistant SSE 未认证返回 401。
- 修复后：`AgentRunExceptionHandler` 显式映射 `ClinicalAccessDeniedException`，强制 JSON 401/403；定向集成测试和 430 operation 在线审计均通过。

### S009-DEF-004 · 193/194 路由 landmark/ID 非法（P1，FIXED/VERIFIED）

- 两个视口共 386 次 `MAIN_CONTENT_ID_COUNT=2`、386 次 `NESTED_MAIN_LANDMARK=1`。
- 仅 `clinical` 路由未命中；其余页面 view 在 shell 的 `<main id="main-content">` 内再次渲染 `<main id="main-content">`。
- 影响：屏幕阅读器 landmark、skip link 和依赖唯一 ID 的自动化定位不可靠。
- 修复后：shell 保留唯一 `<main id="main-content">`，149 个视图/共享页根改为 `<section data-page-root>`；388 个 route-viewport 的重复 ID 与嵌套 main finding 均为 0。

### S009-DEF-005 · 迁移页下拉框无可访问名称（P1，FIXED/VERIFIED）

- `migration` 路由在两个视口均发现 1 个无 label/aria-label/aria-labelledby/title 的 `<select>`。
- 修复后：源系统类型和迁移患者性别 select 均有明确 `aria-label`；两视口无 `UNNAMED_FORM_CONTROLS`。

### S009-GAP-006 · 194 张视觉基准未接入可执行视觉门禁（P1）

- 基准图完整且均为 1440×1000。
- 缺少批准阈值、动态内容遮罩、字体/平台基线与 pixel diff 产物，现有“路由通过”不能证明页面间距、行距和像素一致。

### S009-OBS-007 · 生产主 chunk 超过 500 kB（P2）

- 修复后全局 AI dialog 已异步分块，但 `dist/assets/index-*.js` 仍为 538.99 kB（gzip 118.74 kB），Vite 警告仍在；保留 P2 优化任务。

## 6. 通过项与残余风险

- 已验证：全部 430 operation 契约可解析且未认证在线请求全部失败关闭；614 个 Java/API 测试强制重跑通过；194 路由在桌面和移动视口均能加载关键语义、API settle 且无 console/HTTP/横向溢出/严格可访问性 finding；全局 AI modal 交互两视口通过；生产构建与安全扫描通过。
- 未验证：真实医院 OIDC/CA/KMS/外部接口、真实 DeepSeek 模型制品与推理硬件、医生双盲临床评审、194 页逐像素视觉阈值、全部 2,560 条矩阵的独立正向 UI 操作。
- 13 类外部能力仍使用确定性模拟适配器；其通过不等于真实 LIS/PACS/设备/模型联调通过。

## 7. 修复与复测顺序

1. 由模型/安全/临床负责人批准 DeepSeek 制品 hash、量化、引擎、硬件、数据驻留和指标阈值，然后在 `harness.config.json` 中显式配置并执行重复 eval。
2. 将 194 张基准图接入带动态遮罩、平台基线和审批阈值的视觉回归；在门禁通过前保持像素一致性 `INSUFFICIENT_EVIDENCE`。
3. 作为 P2 独立处理主 chunk 分包，不通过提高 warning 阈值隐藏问题。

## 8. 证据索引

- API：`artifacts/test-runs/api-surface-audit.json`
- UI 严格门禁：`output/playwright/comprehensive-ui-audit.json`
- 路由功能：`artifacts/playwright-ci/route-audit-s009-fixed-1280x800.json`、`artifacts/playwright-ci/route-audit-s009-fixed-390x844.json`
- Java：`build/test-results/test/`
- 数据与矩阵：`samples/data/synthetic-comprehensive-s009-v1.json`、`docs/process/testing/2026-08-24-s009-comprehensive-test-matrix.csv`

SHA-256：

- 数据集：`30643365aabf5945287625f63cadd8332854ddf1eb74756fc55942217557eea9`
- 测试矩阵：`28a9860622b2087dd93ed86a4c7413b41570e54e9e9ad842a0e68f4c72ff5c05`
- API 在线审计：`e86760e15b598d3555efb3dc8a05bf2d91ab47e5310396e6d3f7ac07f710df41`
- UI 严格门禁：`ca0a8d2acccdaee1518eaf173e1cd9f3412bbe3cc2c42a940da0d9bed1421d07`
- 桌面/移动路由功能审计：`ed6b2276a72397c567f8a00a416f0cc220763db8dd6655d35fca52c16836e99e`
- DeepSeek provider 契约：`7c8b6028b005e1807c6b9da9982d6ee8d97e9854d604ff695e34416ddea5d39d`
- DeepSeek harness 配置：`3939b4df35bfc0289daf0ddb079a0e334581d71ec3f73aa3c0a0fb3c3f7c63ce`

## 9. 顶栏按钮补充复测

后续发现医院、角色、帮助、通知和用户入口为静态或无事件控件，品牌仍显示旧标记。上述问题已修复；产品名保持 `HaoEMR`，消息中心支持筛选/单条已读/业务跳转，操作指引支持快捷动作，核心专科工作台菜单暂时隐藏。最终专项 19/19、全量 UI 388/388、两视口路由 194/194 均通过。完整证据见 `docs/process/testing/2026-08-24-topbar-button-retest-report.md`。

## 10. 全页面数据与功能补充修复（2026-08-24）

### 10.1 本批次实现

- 顶部副标题由“临床核心系统”改为主题深蓝色“电子病历系统”；产品名仍为 `HaoEMR`。
- 全局的“AI医助小南”展示 6 个受支持 Agent、各自介入时机、能力边界和可执行任务；用户可点击任务直接执行，也可选择 Agent 后提交自定义任务。所有请求显式携带 Agent code、name、version 与 boundary，不自动写入临床事实。
- `dev-synthetic` 幂等注入 2 条诊断、3 条医嘱、1 条异常检验结果和 2 条会诊/转诊记录；门诊首页、诊断、医嘱处方、检查检验、会诊转诊形成可复测的数据闭环。
- 门诊诊断、医嘱处方、检查检验页面新增主题化统计卡、检索与状态/类别筛选；会诊转诊页已有统计、表格、表单和状态动作，复用并纳入回归。
- 源码契约审计发现并修复 15 个专科页面错误复用 query key / ContextLease purpose 的问题，覆盖口腔、皮肤、耳鼻喉、精神、眼科、儿科、新生儿、生殖和中医等页面，避免跨专科缓存与上下文串用。

### 10.2 S009 执行证据

| 门禁 | 结果 |
|---|---|
| Web 单元测试 | **42/42 PASS**；11 files |
| Java 编译 | **PASS**；Java 21、`compileJava` |
| Web 生产构建 | **PASS**；579 modules；主 chunk 539.09 kB 警告保留为 P2 |
| 顶栏与 AI Agent 专项 | **19/19 PASS**；桌面与移动端 |
| 门诊数据与操作专项 | **5/5 PASS** |
| 全页面数据/功能审计 | **194/194 遍历**；194 页均有可用交互；0 P0/P1 |
| 严格 UI 门禁 | **388/388 route-viewports PASS**；0 findings |
| 桌面路由审计 | **194/194 PASS**；0 console/HTTP/overflow failure |
| 移动路由审计 | **194/194 PASS**；0 console/HTTP/overflow failure |
| 源码格式 | `git diff --check` **PASS** |

数据审计中，59 个路由当前直接渲染数据行或数据卡；120 个路由出现明确的初始/空状态。复核后这些状态均有可用操作，未发现运行时 P0/P1，故作为 P2 数据丰富度清单保留；它们包含“选择场景后执行”等尚未执行的工作区状态，不能简单等同于功能未开发。

### 10.3 新增证据索引

- 全页面数据/功能：`output/playwright/page-data-function-audit.json`
- 门诊专项：`output/playwright/outpatient-data-function-audit.json`
- 严格双视口：`output/playwright/comprehensive-ui-audit.json`
- 桌面路由：`artifacts/playwright-ci/route-audit-full-page-data-1280x800.json`
- 移动路由：`artifacts/playwright-ci/route-audit-full-page-data-390x844.json`
- AI医助小南截图：`output/playwright/ai-assistant-1280x800.png`、`output/playwright/ai-assistant-390x844.png`
- 门诊截图：`output/playwright/opd-diagnosis-1440x1000.png`、`output/playwright/opd-orders-1440x1000.png`、`output/playwright/opd-results-1440x1000.png`、`output/playwright/opd-consult-1440x1000.png`

SHA-256：

- 全页面数据/功能审计：`c10ae37a34962d0c34dbd866b93351afaaafc37443ca57c2676345b37585adaa`
- 门诊专项：`fa581bbe2008e8344d20290f9e9f98ea4bd30cbb51c728f0037d46a869f96796`

## 11. AI医助小南品牌统一与拟人 Logo（2026-08-24）

- 将原有通用称呼统一为“AI医助小南”，覆盖顶栏、弹窗、AI 中心、完整工作台、门诊病历候选面板、操作指引、策略页、路由标题、原型与产品/测试文档；内部 API 与 route id 保持兼容。
- 使用内置图像生成能力制作透明底拟人化医疗 AI 形象，项目版本为 512×512 RGBA PNG、304 kB；顶栏使用 30px，弹窗使用 52px，完整工作台使用 58px。
- 新增品牌契约测试，禁止用户界面回退到旧称呼，并验证顶栏与弹窗 Logo 资源真实加载。
- 当前证据：Web 单元 **42/42 PASS**，Java 编译 PASS，生产构建 PASS，顶栏/弹窗专项 **19/19 PASS**，194 路由双视口 **388/388 PASS**、0 findings。

证据：

- Logo：`web/public/brand/ai-medical-assistant-xiaonan.png`
- 品牌契约：`web/src/vue/ai-medical-assistant-brand.test.ts`
- 桌面截图：`output/playwright/ai-assistant-1280x800.png`、`output/playwright/topbar-1280x800.png`
- 移动截图：`output/playwright/ai-assistant-390x844.png`、`output/playwright/topbar-390x844.png`
- Logo SHA-256：`af83248463239b0397ddd8b0d6cb94c155c6acbc8b671f5d99a2665ab5714c29`
- 顶栏专项 SHA-256：`b8c3741dc925c8752d8fe259810b268933dad7cbfb1eb0048c77b87beca55d66`
