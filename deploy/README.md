# HaoEMR 部署边界

`compose.synthetic.yml` 只用于本机和隔离测试环境，内含固定合成身份、确定性假模型和公开测试口令，严禁放入真实患者数据或接入医院网络。

## 合成演示环境

```bash
docker compose -f deploy/compose.synthetic.yml up --build
```

打开 `http://127.0.0.1:4177`。停止并保留合成数据使用 `down`；彻底删除合成数据库使用 `down -v`。

## 生产门禁

后端 `prod` profile 默认不注册开发身份提供者，并关闭合成数据导入。启动前会校验数据库、OIDC/MFA、CA/时间戳、KMS、对象存储、集成证书和可选 AI 配置；关键项缺失、使用 HTTP、内联秘密、未挂载的秘密引用或混入 `dev-synthetic` 均拒绝创建应用上下文。允许的秘密引用格式为 `env://变量名` 和 `file:///绝对挂载路径`，示例见 `production.env.example`。

生产 API 已接入 Spring OAuth2 Resource Server：除 readiness 外均要求 Bearer JWT，issuer、audience、签名和时间声明由资源服务器校验；随后以 `sub + tenant_id` 映射本地活动账户，并只采用数据库当前有效角色任期，Token 自报角色不生效。生产还强制指定 MFA `acr`，未知、锁定、停租户或撤权主体失败关闭。

上述能力只完成本机实现与合成验证，不表示已经完成真实医院 IdP/JWK、CA/时间戳、限流、等保、灾备、外部模型 Evals 或医院接口联调，因此仍不得作为真实医院生产部署。

生产拓扑、变量、备份恢复与待完成门禁见 `docs/process/operations/2026-08-14-haoemr-s011-devops.md`。
