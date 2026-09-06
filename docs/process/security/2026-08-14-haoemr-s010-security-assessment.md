# HaoEMR V0.1 纵向切片安全审计与风险评估

> 评估日期：2026-08-14  
> 评估范围：Spring Boot API、PostgreSQL 临床事实库、ContextLease、病历版本/签署、AI Run/Tool/Proposal、Vue Web 纵切  
> 数据：仅完全合成数据  
> 决议：**开发纵切可继续；真实医院生产 NO-GO**

## 1. 安全基线与信任边界

本评估以 [OWASP ASVS 5.0.0](https://owasp.org/www-project-application-security-verification-standard/) 作为 Web 控制验证基线，以 [OWASP LLM Top 10 2025](https://genai.owasp.org/llm-top-10/?cat=253) 和 [OWASP Agentic Top 10](https://genai.owasp.org/2025/12/09/owasp-top-10-for-agentic-applications-the-benchmark-for-agentic-security-in-the-age-of-autonomous-ai/) 校准 AI 风险。OWASP 明确指出 Prompt Injection 不能仅靠 RAG 或微调解决，风险与模型被授予的 agency 强相关；Excessive Agency 的主要根因是过多功能、权限或自主性。因此当前切片采用“固定只读 Tool + 短期租约 + 候选制 + 人工决策 + 临床命令二次校验”，而不是依赖系统 Prompt 保证安全。

```text
浏览器（不可信输入）
  -> OIDC 边界（开发替身只在 dev-synthetic；其他 profile fail closed）
  -> ClinicalCommandSecurity（租户/机构/岗位/患者/就诊/租约/水印）
  -> 临床命令服务（幂等/版本/状态机/质控）
  -> PostgreSQL（复合租户 FK、签署后不可变触发器）

病历正文（不可信上下文）
  -> AI Run（deadline/budget/fencing 数据）
  -> 固定 READ_DOCUMENT_VERSION Tool（无 SQL/写入 Tool）
  -> Model Provider（当前仅确定性 fake）
  -> AgentOutputGuard（Schema + 递归禁止 authority/side-effect key）
  -> AIProposal（PENDING_REVIEW）
  -> 医生人工接受/拒绝（仍不直接写病历）
```

## 2. STRIDE 与 AI 威胁模型

| ID | 类别 | 资产/攻击面 | 典型攻击 | 当前控制 | 剩余风险 |
|---|---|---|---|---|---|
| TM-01 | Spoofing | 临床身份 | 伪造开发 Header/Token | dev adapter 限定 profile；生产 provider fail closed | 真实 OIDC/MFA 未接，P0 |
| TM-02 | Tampering | 病历正文 | 并发覆盖或签后改写 | row_version、不可变版本链、DB trigger | 更正/撤销完整流程未实现 |
| TM-03 | Repudiation | 临床/AI 决策 | 否认签署或接受 AI | audit hash chain、signature evidence、outbox | 无 CA/时间戳/外部存证，P0 |
| TM-04 | Information Disclosure | 跨患者/跨租户 | IDOR、错误信息探测 | 复合 tenant FK、ContextLease、统一 403、不记录姓名正文 | 真实接口与日志脱敏未联调 |
| TM-05 | Denial of Service | API/AI Provider | 超大 Body、请求洪泛、Token 消耗 | 1 MiB Content-Length 门禁、Tool/token/deadline budget | 分布式 rate limit 未实现，P1 |
| TM-06 | Elevation of Privilege | Agent Tools | Prompt 诱导签署/SQL/跨患者 | 仅固定只读 Tool；无模型选 Tool；输出递归护栏；人工审批 | 真实 Provider Prompt/Tool sandbox 未红队 |
| TM-07 | LLM01/ASI01 | 病历内间接提示词注入 | 正文写“忽略规则” | 正文作为不可信数据；工具路径不由模型输出决定 | 真实模型输入分层尚未验证，P0 before enable |
| TM-08 | LLM02 | PHI 泄露 | 模型返回其他患者/外发 | 租约绑定单患者/就诊；默认 ON_PREM_ONLY | 外部模型 DLP/脱敏/地域校验未实现 |
| TM-09 | LLM04/ASI06 | 上下文投毒 | 伪造来源、过期结果 | source_id/version/hash/watermark；proposal 随 lease 过期 | LIS/PACS/RAG 摄取签名未实现 |
| TM-10 | LLM05 | 不安全输出处理 | 输出 XSS/伪命令 | React 默认转义；Zod strict；AgentOutputGuard | DAST/CSP 对正式前端网关待验证 |
| TM-11 | LLM06/ASI02 | Excessive Agency | AI 直接签署/写事实 | AIProposal 与临床命令物理分离 | 未来新增 Skills/Tools 必须逐个建模 |
| TM-12 | Supply Chain | npm/Maven/模型 | 恶意依赖或模型 | lockfile/Wrapper/精确版本；npm audit | Maven/容器 SBOM、模型签名未接 |

## 3. 红队数据集与实际执行

攻击目录位于 `security/red-team-payloads.json`，共 15 条、12 个攻击面；`security/check-red-team.mjs` 验证数据集结构与防御预期。以下为本次实际执行，不把仅建模的 Payload 计作已拦截：

| 攻击 | 实际注入 | 结果 |
|---|---:|---|
| 跨患者租约/病历写入 | 3 条集成路径 | 403，副作用 0 |
| SQL Injection 搜索字符串 | 1 | 参数化查询，patient 表与行数保持 |
| 幂等重放/旧版本覆盖 | 2 | 409，无重复版本 |
| 超大 API Body | 1 | 413，在认证/临床处理前拒绝 |
| 签署后 UPDATE/DELETE | 2 | PostgreSQL check violation |
| AI 嵌套 `tool_call`/`signature` | 2 | `AI_FORBIDDEN_ACTION` |
| AI 错患者审批 | 1 | 403，Proposal 仍 PENDING_REVIEW |
| 生产适配器接受开发 Header | 1 | 401/fail closed |
| SSE 旧序号重放 | 1 | 只返回 `sequence > Last-Event-ID` |
| Prompt Injection/真实模型越狱 | 0 个真实模型调用 | **未评估，不给拦截率** |

## 4. 已验证的工程安全控制

- 所有临床 SQL 使用参数绑定；租户外键在数据库层再次约束。
- ContextLease 默认 15 分钟、绑定岗位/机构/患者/就诊/用途、水印；Worker 调 Tool 前再次检查过期时间。
- AI 只有 `READ_DOCUMENT_VERSION` 固定只读工具；没有通用 SQL、签署、文件、网络或跨患者 Tool。
- AI 输出必须是 Proposal Schema，递归禁止 `signature`、`tool_call`、`execute_sql`、`write_clinical_fact`、`tenant_id`、`authorization_watermark` 等键。
- 签署版本在数据库触发器层禁止 UPDATE/DELETE；应用绕过仍不能改写。
- Outbox 使用 `SKIP LOCKED`、短租约和 fencing token；同库消费者的业务投影与去重回执同事务，失败退避至死信，人工重放必须记录操作者和原因。
- API 设置 `nosniff`、`DENY frame`、`no-referrer`、Permissions Policy 与 API CSP。
- 生产 Web bundle 不包含开发 token、租户 UUID 或合成患者 UUID；生产身份未配置时前端拒绝请求。
- prod 配置在应用上下文创建前失败关闭：禁止合成 profile，强制 OIDC/MFA、CA/时间戳、KMS、对象锁、集成证书与数据驻留；secret-ref 只接受已提供的 `env://` 或可读 `file://`，错误不回显秘密。
- `npm audit --registry=https://registry.npmjs.org --audit-level=high`：0 vulnerabilities。
- 本地凭据模式扫描通过；本机未安装 Gitleaks，因此不能宣称完成正式 secret scan。

## 5. 漏洞与修复状态

| 漏洞 ID | 严重性 | 发现 | 状态/证据 |
|---|---|---|---|
| VUL-001 | Critical | 无真实 OIDC/MFA/岗位撤销事件接入 | OPEN；生产 profile 先 fail closed，阻断上线 |
| VUL-002 | Critical | 无 CA、可信时间戳和证书吊销验真 | OPEN；签名明确为 `PENDING_CA_EVIDENCE` |
| VUL-003 | High | 无租户/用户/AI use-case 分布式限流 | OPEN；Body/deadline/token 仅降低部分 DoS 风险 |
| VUL-004 | High | 真实模型 Prompt Injection、PHI 泄露与多语种越狱未评估 | OPEN；真实模型功能不得启用 |
| VUL-005 | High | 数据库/备份静态加密与密钥托管未部署 | OPEN；部署前 KMS/HSM/备份加密门禁 |
| VUL-006 | High | Outbox Dispatcher/消费者去重和 Worker kill 未完成 | **FIXED-CORE**；V5 与 `OutboxDispatcherTest` 已验证顺序、去重、死信、重放和过期租约 fencing。LIS/PACS 等远端连接器仍须独立 Inbox/对账后才可上线 |
| VUL-007 | Medium | 生产 bundle 含开发身份回退 | **FIXED**；`scripts/security-scan.sh` 门禁 |
| VUL-008 | Medium | 模型输出只检查顶层签名键 | **FIXED**；递归 AgentOutputGuard + 2 单测 |
| VUL-009 | Medium | API 缺通用安全头与 Body 上限 | **FIXED**；响应头集成测试 + 413 测试 |
| VUL-010 | Medium | `bbox=[]` 导致前端契约失败并误报断网 | **FIXED**；不适用 locator=`null`，Zod 单独报 CONTRACT_MISMATCH |
| VUL-011 | Medium | 无 Gitleaks/SonarQube/ZAP/Java CVE 扫描 | OPEN；当前只有本地模式扫描与 npm audit |
| VUL-012 | Low | 开发 OIDC 替身 token 固定 | ACCEPTED-DEV-ONLY；profile 隔离、生产 bundle 清除、生产 provider fail closed |

## 6. 安全发布门禁

### 6.1 当前决议

- **开发/CI 合成纵切：GO**。
- **任何真实患者数据、真实医院接口或真实模型：NO-GO**。

### 6.2 解除 NO-GO 的最小条件

1. 接入真实 OIDC/OAuth2、MFA、岗位/资质实时撤销、应急访问双人审批；删除 Header 身份替身在交付镜像中的注册可能性。
2. CA/可信时间戳/吊销列表/签名验真全链路通过，签名状态从 Pending 到 Valid 有不可抵赖证据。
3. 数据库、对象存储、备份、日志、消息均加密；密钥进入 KMS/HSM；完成恢复与密钥轮换演练。
4. API Gateway 完成分布式限流、WAF、TLS/mTLS、CORS allowlist、会话撤销和审计告警。
5. 对选定基座模型执行 100 条基础集 + 专科集 + 多语种/编码/间接注入红队；跨患者泄露和未批准副作用必须为 0。
6. 固化 Gitleaks、SAST、Java/npm/SBOM、镜像扫描与 ZAP DAST；Critical/High 为 0 或经 TRB 正式豁免。
7. 已完成同库 Outbox 租约/fencing/死信/重放自动测试；上线前仍须完成各外部连接器远端幂等/对账、真实进程 kill、SSE 乱序/断档、数据库故障和同城/异地灾备演练。

## 7. 复现与证据

- 全回归：`scripts/verify.sh`
- 安全本地扫描：`scripts/security-scan.sh`
- 红队目录校验：`node security/check-red-team.mjs`
- 安全测试：`AgentOutputGuardTest`、`FailClosedIdentityTest`、`ContextLeaseApiTest`、`ClinicalLifecycleApiTest`、`AgentRunApiTest`、`OutboxDispatcherTest`
- 依赖审计：`npm --prefix web --registry=https://registry.npmjs.org audit --audit-level=high`
- 浏览器证据：`artifacts/playwright/clinical-ai-accepted.png`
