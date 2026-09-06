# C01-I1 生产 OIDC 身份与角色任期测试报告

日期：2026-08-20  
阶段：S008 实施 + S009 `IMPLEMENT/EXECUTE/REPORT`  
结论：`LOCAL_VERIFIED`；真实医院 IdP/JWK 联调尚未执行。

## 1. 验收范围

| test_id | 风险/需求 | 层级 | 预期 | 证据 |
|---|---|---|---|---|
| ID-001 | 生产 API 裸访问 | HTTP 安全 | readiness 外返统一 JSON 401 | prod API JUnit 通过 |
| ID-002 | 无效 Bearer | HTTP 安全 | 解码器拒绝，不进入临床上下文 | prod API JUnit 通过 |
| ID-003 | issuer/audience 配置漂移 | 启动安全 | Spring 验签值与产品门禁值不一致即拒绝 | 配置 JUnit 通过 |
| ID-004 | Token 自报管理员角色 | 身份映射 | 忽略 Token 角色，只返回数据库有效任期 | provider/API JUnit 通过 |
| ID-005 | 未知主体/锁定账户/停用租户 | 身份映射 | 统一失败关闭，不泄露主体与租户 | provider JUnit 通过 |
| ID-006 | 错误 MFA ACR | 身份映射 | 未达机构要求即 401 | provider/API JUnit 通过 |
| ID-007 | 角色过期/撤权 | 授权前置 | 无当前有效任期即 403 | provider JUnit 通过 |
| ID-008 | 非 API 路径 | HTTP 安全 | 即使已认证也默认拒绝 | prod API JUnit 通过 |

测试数据全部使用 C01 专用 UUID 与合成主体；HTTP 测试后按外键逆序清理，不包含真实人员或凭据。

## 2. 实现证据

- 生产安全链为无会话 OAuth2 Resource Server；只公开 `/api/v1/system/readiness`，`/api/**` 要求 JWT，其他路径默认拒绝。
- `application-prod.yml` 配置 issuer 和 audiences；Spring Security 负责签名、issuer、audience、`exp/nbf` 等 JWT 验证。
- 启动门禁强制 `spring.security.oauth2.resourceserver.jwt.*` 与 `haoemr.production.identity.*` 一致，防止双配置源漂移。
- 验签后以 `sub + tenant_id` 查询活动租户与活动账户，并要求指定 `acr`；角色仅来自数据库当前 `ACTIVE` 且在有效期内的 `role_assignment`。
- `dev-synthetic` 保持原有隔离身份流；`prod + dev-synthetic` 在上下文创建前被拒绝。

## 3. 实际执行

窄测试：

```text
ProdOidcClinicalIdentityProviderTest: 7 tests
ProdSecurityApiTest: 5 tests
ProductionEnvironmentPostProcessorTest: 11 tests
0 failures / 0 errors / 0 skipped
```

全量根门禁：

```text
scripts/verify.sh
Java: 22 suites / 58 tests / 0 failure / 0 error / 0 skipped
Web: 4 files / 13 tests
Contract: 3/3; generated outputs: 91 clean
Database: V1-V22 + isolated restore fingerprint PASS
AI eval: 100/100; security dataset: 15 payloads / 12 surfaces
Traceability: 138/138; route map: 194/194
Security scan: all four production/profile/credential gates PASS
```

## 4. 未关闭风险与下一施工单

- API 测试使用可控测试 `JwtDecoder` 证明资源服务器边界和业务映射；尚未与真实医院 IdP 的 discovery/JWK、签名轮换、时钟偏差和故障恢复联调。
- 这一批只映射账户与角色任期；自然人/职工/执业资质与登录账户分离属于下一施工单 `C01-O1`。
- 科室/病区/床位数据范围、ABAC、患者关系和紧急访问尚未实现，不能因 OIDC 已接入就声称 C01 整体完成。

因此 C01-I1 标记 `LOCAL_VERIFIED`，C01 总任务保持 `IN_PROGRESS`，下一项为 `C01-O1`。
