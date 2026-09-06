# 2026-08-24 S009 综合复测缺陷修复计划

> 模式：`REPLAN`
> 输入：`docs/process/testing/2026-08-24-s009-comprehensive-retest-report.md`
> 状态口径：规划初始为 `PLANNED`；只有实际修复且通过 DoD 命令才可改为 `VERIFIED`。

## 1. 范围、假设与非目标

- 范围：S009-DEF-001 至 005 的可复现 P0/P1 缺陷；为 S009-DEF-002 建立默认关闭、失败关闭的 DeepSeek adapter 与 harness 契约；重跑受影响回归。
- 显式事实：当前候选基线为 `db049ae`；S009 报告、矩阵、审计脚本和产物是用户未提交改动，需原样保护。
- 推断：顶栏入口应使用 shell 级原生 modal dialog；页面只保留一个 shell `<main id="main-content">`。
- 外部阻塞：真实 DeepSeek 模型制品、量化、推理引擎、硬件、临床评审阈值尚未提供；实模质量/性能不得标为通过。
- 非目标：不改 OpenAPI/Schema，不升级框架，不接入真实密钥，不执行 commit/push/部署/数据迁移，不在未批准阈值前宣称 194 页像素级一致。

## 2. DAG 与批次

```text
task_id,title,requirement_refs,depends_on,parallel_group,risk,owner_skill,status
FIX-AI-DIALOG-01,Shell 级全局 AI 弹窗,S009-DEF-001,-,B1-A,HIGH,haonan-s008-coder,VERIFIED
FIX-SSE-AUTH-01,AI Run SSE 认证异常失败关闭,S009-DEF-003,-,B1-B,HIGH,haonan-s008-coder,VERIFIED
FIX-LANDMARK-01,唯一 main landmark 与 main-content ID,S009-DEF-004,-,B1-C,MEDIUM,haonan-s008-coder,VERIFIED
FIX-MIG-A11Y-01,迁移页 select 可访问名称,S009-DEF-005,FIX-LANDMARK-01,B1-C,MEDIUM,haonan-s008-coder,VERIFIED
FIX-DS-ADAPTER-01,DeepSeek 生产 adapter 与密钥引用,S009-DEF-002,-,B1-D,HIGH,haonan-s008-coder,VERIFIED
FIX-DS-HARNESS-01,DeepSeek 制品与 eval harness 失败关闭契约,S009-DEF-002,FIX-DS-ADAPTER-01,B2,HIGH,haonan-s009-test,OBSERVED_EXTERNAL_BLOCK
RETEST-S009-01,窄回归与受影响全回归,S009-DEF-001..005,全部 FIX,B3,HIGH,haonan-s009-test,VERIFIED
GATE-VISUAL-01,194 页像素门禁阈值与遮罩批准,S009-GAP-006,-,EXTERNAL,HIGH,product-owner,PLANNED
OPT-CHUNK-01,主 chunk 分包,S009-OBS-007,RETEST-S009-01,B4,LOW,haonan-s008-coder,PLANNED
```

执行结果：五个仓库内确定性缺陷已验证；DeepSeek adapter/harness 代码与失败关闭契约已存在，但真实制品、硬件和阈值未批准，因此 `FIX-DS-HARNESS-01` 不标记 `VERIFIED`。

关键路径：`FIX-DS-ADAPTER-01 → FIX-DS-HARNESS-01 → RETEST-S009-01`。四条 B1 修复链代码边界独立；共享 shell/UI 文件串行修改。

## 3. 施工单

### FIX-AI-DIALOG-01 Shell 级全局 AI 弹窗

- 目标价值：在任何授权页面保留当前 route 和临床上下文进行问答。
- 允许修改：`ClinicalShell.vue`、新对话组件、assistant 薄 API、`vue-shell.css`、相关测试。
- 禁止项：不让 AI 获得独立临床写权；不把患者 ID 写入 URL 或消息正文。
- 实施：按钮打开原生 modal dialog；路由不变；关联 route 和可用的患者/就诊 ContextLease；关闭时还原焦点；支持 Esc、焦点限定、背景 inert 与移动端安全区。
- 测试：`test:ui:comprehensive` 的 dialog/route-preserved 断言；390×844 和 1280×800。
- 回滚：回滚 shell 组件与 CSS，保留独立 `/ai-assistant` 页作降级入口。
- DoD：两视口点击后唯一 `role=dialog`，URL 不变，Esc 可关闭，焦点返回入口。

### FIX-SSE-AUTH-01 AI Run SSE 失败关闭

- 目标价值：未认证/未授权流式请求稳定返回 401/403，不泄露堆栈。
- 允许修改：`AgentRunExceptionHandler`、`AgentRunApiTest`。
- 接口影响：仅修正既有错误映射，无成功响应或 Schema 变化。
- 测试：无 Authorization 访问 events 返回 401 + `AUTHENTICATION_REQUIRED`；授权 SSE 与 Last-Event-ID 恢复保持通过。
- 回滚：回滚 handler/test，无数据影响。
- DoD：定向 Java 测试通过，API 表面审计不再出现 500。

### FIX-LANDMARK-01 + FIX-MIG-A11Y-01 可访问性

- 目标价值：skip link、屏幕阅读器 landmark 和自动化定位唯一可靠。
- 允许修改：`web/src/vue/views/`与共享页组件的根标签，`MigrationPage.vue`。
- 实施：shell 保留唯一 `<main id="main-content">`；视图根元素改为 `<section data-page-root>`；为迁移页 select 添加可访问名称。
- 测试：严格 UI 门禁全路由无 `MAIN_CONTENT_ID_COUNT`、`NESTED_MAIN_LANDMARK`、`UNNAMED_FORM_CONTROLS`。
- 回滚：机械回滚根标签；无数据/API 影响。
- DoD：194×2 视口的上述三类 finding 为 0。

### FIX-DS-ADAPTER-01 + FIX-DS-HARNESS-01 DeepSeek 失败关闭集成

- 目标价值：提供可审计、默认关闭的 OpenAI-compatible Chat Completions adapter，并将“未配置实模”显式报为阻塞。
- 允许修改：`org.haoemr.agent`、`evals/deepseek/`、adapter/harness 测试。
- 安全边界：仅 `prod` + `haoemr.production.ai.enabled=true` 注册；仅接受 HTTPS 与 `env://`/`file://` 密钥引用；不记录请求正文、密钥或模型原始错误；provider 失败转 `DEGRADED`，临床主链继续。
- 契约：非流式 `POST {base-uri}/chat/completions`，Bearer 认证，JSON object 输出，仅接受 `sections`，后续仍经 `AgentOutputGuard`。
- 测试：adapter 合成 HTTP 成功/非 2xx/非法 JSON/密钥引用；harness 未配置时返回 `INSUFFICIENT_EVIDENCE` 而非假 PASS。
- 回滚：删除 prod adapter/harness 文件并恢复 provider 列表；数据不迁移。
- DoD：adapter 契约测试通过；缺制品/硬件/阈值时发布门禁仍 `NO_GO/INSUFFICIENT_EVIDENCE`。

### RETEST-S009-01 窄回归与受影响全回归

- 顺序：Java 定向 → Web 单测/构建 → DeepSeek 契约门禁 → API 在线审计 → 194 路由严格 UI 两视口 → 受影响全回归。
- 数据：只使用 `s009-comprehensive-v1` 合成数据；不访问真实患者或真实模型。
- 门禁：已修复的确定性 P0/P1 必须为 0 finding；实模和像素证据仍不足时，总体建议不高于 `CONDITIONAL_GO`，生产仍 `NO_GO`。

## 4. 授权与风险

- 本轮已授权：仓库内代码、测试、失败关闭配置和报告修改。
- 未授权/未执行：commit、push、生产部署、真实数据库迁移、真实 DeepSeek 请求、模型制品下载、外部审核。
- 待产品/安全批准：视觉 diff 阈值/遮罩/字体平台；DeepSeek 模型制品 hash、量化、引擎、硬件、数据出域和临床指标阈值。
