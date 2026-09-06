# HaoEMR 本地工具链

## 已验证基线

- JDK 21（当前工作站验证 21.0.12；CI/发行镜像应使用仍受支持的最新 JDK 21 安全小版）。`scripts/with-java21.sh` 优先读取 `HAOEMR_JAVA_HOME`，随后检测系统 JDK 和隔离工具链目录。
- Gradle Wrapper 9.6.1，发行包 SHA-256 已写入 Wrapper 与 `toolchain-versions.json`。
- Spring Boot 4.1.0、Spring Modulith 2.1.0。
- PostgreSQL 18.4，开发实例使用 `/private/tmp/haoemr-pg18-data`、socket `/private/tmp`、端口 55432。
- Node 24.13.0/npm 11.6.2；前端精确版本见 `web/package-lock.json`。

## 常用命令

```bash
# 幂等启动隔离开发数据库并按需创建 haoemr_dev；某些受限沙箱需在正常终端执行
scripts/dev-db.sh start
scripts/dev-db.sh status

# 一条命令启动数据库，并运行契约、AI eval、安全、迁移、Java、备份恢复、前端与追踪门禁
scripts/verify.sh

# 单独运行后端
scripts/with-java21.sh ./gradlew bootRun

# 单独运行前端
npm --prefix web run dev
```

## 安全约束

- 开发数据库只允许合成数据，默认配置不得导入真实患者信息。
- 不在仓库提交数据库目录、`.env`、口令、访问令牌、病历正文或日志。
- `scripts/with-java21.sh` 不修改系统默认 Java；CI 必须显式提供 JDK 21。
- 本机隔离数据库使用 trust 仅限 `/private/tmp` 开发实例；生产和共享环境禁止复制该认证策略。
