# F01-C2 生产配置失败关闭测试报告

日期：2026-08-20  
阶段：S008 实施 + S009 `IMPLEMENT/EXECUTE/REPORT`  
结论：`LOCAL_VERIFIED`；真实适配器和远端 CI 仍未验证。

## 1. 验收范围

| test_id | 风险/需求 | 层级 | 预期 | 证据 |
|---|---|---|---|---|
| CFG-001 | 非生产环境兼容 | 单元 | `dev-synthetic` 不要求生产配置 | JUnit 通过 |
| CFG-002 | Boot 注册漂移 | 组件 | 打包资源可发现生产 EnvironmentPostProcessor | JUnit 通过 |
| CFG-003 | prod 缺关键配置 | 单元/启动 | 聚合拒绝，不创建上下文 | JUnit + 实际 bootRun |
| CFG-004 | prod 混入合成身份 | 单元 | 拒绝 `prod + dev-synthetic` | JUnit 通过 |
| CFG-005 | 明文秘密/HTTP | 安全单元 | 拒绝且错误不回显秘密 | JUnit 通过 |
| CFG-006 | 环境 secret-ref 未提供 | 安全单元 | 拒绝缺失目标 | JUnit 通过 |
| CFG-007 | 文件 secret-ref | 安全单元 | 仅接受可读、非空、受限大小文件 | JUnit 通过 |
| CFG-008 | AI 启用但配置不全 | 单元 | 模型端点/ID/API key/驻留缺失即拒绝 | JUnit 通过 |
| CFG-009 | 完整本地生产配置 | 单元 | 配置门禁通过 | JUnit 通过 |
| CFG-010 | 完整受控 AI 配置 | 单元 | 配置门禁通过 | JUnit 通过 |
| CFG-011 | Resource Server issuer/audience 漂移 | 安全单元 | 验签配置必须与生产门禁完全一致 | JUnit 通过 |

测试数据均为合成属性和临时文件，不包含医院密钥或真实凭据。

## 2. 实现证据

- `ProductionEnvironmentPostProcessor` 使用 Spring Boot 4.1 当前 `org.springframework.boot.EnvironmentPostProcessor` 扩展点，并通过 `META-INF/spring.factories` 注册。
- 执行顺序位于 Config Data 之后、Bean 和 Web Server 创建之前。
- prod 强制数据库、OIDC/MFA、CA/可信时间戳、KMS、数据库/对象密钥引用、对象存储锁、集成证书和数据驻留配置。
- AI 默认关闭；启用时强制 HTTPS、模型 ID、API key 引用和 `ON_PREM_ONLY`/`CHINA_REGION_ONLY`。
- secret-ref 只接受 `env://` 与 `file://`；错误只输出属性名和固定原因。

## 3. 实际执行

窄测试：

```text
./gradlew test --tests org.haoemr.configuration.ProductionEnvironmentPostProcessorTest
11 tests, 0 failures, 0 errors, 0 skipped
```

实际启动故障测试：

```text
./gradlew bootRun --args='--spring.profiles.active=prod --spring.main.web-application-type=none'
exit 1
Production configuration rejected (secret values omitted)
```

该失败发生在 `EnvironmentPostProcessorApplicationListener` 阶段，早于应用上下文和 Web Server 创建。

全量根门禁：

```text
scripts/verify.sh
Java: 22 suites / 58 tests / 0 failure / 0 error / 0 skipped
Web: 4 files / 13 tests
Contract: 3/3; generated outputs: 91 clean
Database: V1-V22 + isolated restore fingerprint PASS
AI eval: 100/100; security dataset: 15 payloads / 12 surfaces
Traceability: 138/138; route map: 194/194
```

安全扫描新增并通过：凭据模式、生产 bundle 合成身份、prod 内联秘密和 prod/dev profile 隔离。

## 4. 未关闭风险

- C01-I1 已在本机实现 OIDC Resource Server、MFA ACR 与账户/角色任期映射；真实医院 IdP/JWK 与签名密钥联调仍未执行。
- CA 签名、可信时间戳、吊销和验真未联调。
- KMS/HSM 加解密、密钥轮换和备份恢复未演练。
- 对象存储锁定、医院 LIS/PACS/HIS mTLS 连接器未联调。
- AI 启用只具备配置门禁；DeepSeek harness、模型制品、黄金集和红队仍属于 A01/DR-011。

因此本批是配置安全门禁 `LOCAL_VERIFIED`，不是生产发布 GO。
