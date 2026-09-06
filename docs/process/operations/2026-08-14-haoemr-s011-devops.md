# HaoEMR V0.1 S011 DevOps 与部署门禁

> 日期：2026-08-14  
> 当前可部署级别：仅本机或隔离测试环境的合成数据演示  
> 真实医院生产：NO-GO

## 1. 交付拓扑

```text
浏览器 :4177
  -> Vite 合成演示服务（固定开发身份，仅隔离环境）
     -> /api 反向代理
        -> Spring Boot :8080（dev-synthetic）
           -> PostgreSQL 18 :5432
           -> 确定性 Fake Model（不访问外网、不使用 GPU）
```

三个容器均以非 root 或最小权限运行；后端和 Web 根文件系统只读，临时目录使用受限 tmpfs，端口只绑定 `127.0.0.1`。数据库数据使用命名卷。测试口令是公开的合成环境常量，不属于生产 Secret。

## 2. 可复现资产

| 资产 | 用途 | 状态 |
|---|---|---|
| `deploy/backend.Dockerfile` | JDK 21 非 root 后端镜像 | 已定义，当前主机无 Docker，未执行构建 |
| `deploy/web-synthetic.Dockerfile` | Vite 合成演示服务 | 已定义，禁止生产使用 |
| `deploy/compose.synthetic.yml` | PostgreSQL 18 + 后端 + Web | 已定义，禁止真实数据 |
| `.github/workflows/ci.yml` | 契约、迁移、恢复、Java、Web、安全与追踪门禁 | 已定义，待首次 GitHub Actions 实跑 |
| `scripts/package-synthetic.sh` | JAR、静态 Web、部署文件与 SHA-256 清单 | 本机执行验证 |

## 3. 环境与配置

生产 profile 从环境读取数据库连接，并在 Spring 上下文创建前校验 OIDC/MFA、CA/时间戳、KMS、对象存储、集成证书、数据驻留和可选 AI 配置；合成数据导入关闭，且 `prod` 不能与 `dev-synthetic` 同时激活。秘密只允许使用 `env://变量名` 或 `file:///绝对挂载路径` 引用，参考 `deploy/production.env.example`。缺失目标、明文值、HTTP 端点或关闭强制安全选项均拒绝启动。

生产临床身份、CA、KMS 和集成适配器尚未实现，因此配置校验通过后临床请求仍按 fail-closed 拒绝；不得把配置完整性当成外部系统联调完成。

严禁把数据库密码、OIDC Client Secret、CA 私钥、模型 API Key 写入镜像、Compose、Git、日志或前端 bundle。真实部署应使用医院批准的 Secret Manager/KMS，并区分测试、预生产、生产租户和密钥域。

## 4. 数据保护与恢复

- 当前恢复夹具使用 PostgreSQL custom dump，在独立数据库恢复后校验患者、就诊、病历版本和审计链指纹。
- 合成环境可通过 `docker compose ... down -v` 删除命名卷；该命令会不可恢复地删除合成数据库，执行前需确认目标项目名。
- 真实生产仍需：加密备份、跨域副本、不可变保留、RPO/RTO 审批、恢复演练、证书与对象存储恢复、操作双人复核。

## 5. 可观测性与运行手册缺口

当前只有 `/api/v1/system/readiness`、结构化 API 错误、审计链与 Outbox 证据；尚未接入指标、链路、集中日志、脱敏策略验证和告警平台。生产前至少需要：

1. SLI：API 可用率、保存/签署成功率与 P95/P99、数据库连接池、Outbox lag、AI Run 降级率。
2. 告警：跨患者拒绝激增、签署失败、审计链校验失败、恢复失败、租约异常、模型成本/延迟越界。
3. Runbook：身份服务故障、数据库主备切换、AI 熔断、证书吊销、医院接口积压、数据泄露事件。
4. 日志：默认不记录病历正文、证件号、令牌或模型完整上下文；异常样本进入受控证据库。

## 6. 生产门禁

| 门禁 | 当前 | 放行条件 |
|---|---|---|
| 统一身份/OIDC/MFA | NO-GO | 实现生产身份适配器、岗位撤销、紧急访问和审计测试 |
| CA/可信时间戳 | NO-GO | 正式证书签署、吊销、时间戳与验真闭环 |
| 安全与合规 | NO-GO | 限流、SAST/SCA/DAST、渗透、等保和医院安全评审 |
| 数据与灾备 | NO-GO | 加密、密钥管理、RPO/RTO、跨域恢复演练 |
| AI/MLOps | NO-GO | 真实模型、专科黄金集、医生双盲评审、漂移/成本/回滚门禁 |
| 医院集成 | NO-GO | LIS/PACS/HIS/CA/主数据接口契约与断链补偿演练 |
| 可观测性 | NO-GO | 指标、日志、链路、告警与 7x24 Runbook |

## 7. 建议的下一环境

下一步不是直接进入生产，而是建立不含真实 PHI 的预生产环境：使用真实 OIDC 测试租户、测试 CA、Mock LIS/PACS、合成住院纵切和故障注入。全部 P0 门禁关闭后，再由临床、信息、安全、病案与运维共同签署灰度方案。
