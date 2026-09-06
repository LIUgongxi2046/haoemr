# HaoEMR P4 开源合规与供应链审计

审计日期：2026-09-06  
状态：**REVIEW_REQUIRED（代码依赖可继续开发，公开发行仍有阻断项）**  
范围：当前工作区、已安装前端依赖、现有 Spring Boot 可执行包、本地 Gradle 缓存、Git 可达历史、已跟踪媒体与资产清单。  
非范围：正式法律意见、商标检索、第三方数据授权合同审阅、线上部署与生产数据。

## 状态定义

- `VERIFIED`：本次有可重复的机器证据支持。
- `OBSERVED`：已取得事实证据，但仍有发行义务需要落实。
- `BLOCKED`：公开发行前必须补齐，当前不能声明完成。

## 执行证据

可重复执行：

```bash
node scripts/audit-open-source-compliance.mjs
scripts/verify-open-source-governance.sh
```

`audit-open-source-compliance.mjs` 在发现待复核项时返回退出码 `2`，这是审计状态，不代表脚本异常。

本次快照：

| 项目 | 结果 | 状态 |
|---|---:|---|
| 前端唯一依赖包 | 168 | VERIFIED |
| 前端缺失 license 字段 | 0 | VERIFIED |
| 后端可执行包内嵌 JAR | 72 | VERIFIED |
| 后端可由 POM/父 POM 解析许可证 | 71 | VERIFIED |
| Git 可达提交 | 83 | VERIFIED |
| Git 唯一作者 | 1 | VERIFIED |
| 已跟踪媒体文件 | 1019 | VERIFIED |
| 两份资产清单中的记录 | 11 | OBSERVED |

## 发现与处置

### P4-01 媒体与数据来源证明不足

- 严重度：高
- 状态：BLOCKED
- 证据：已跟踪媒体共 1019 个，其中 `output/` 719 个、`docs/` 217 个、`web/` 44 个、`artifacts/` 38 个、`prototype/` 1 个；现有两份 `asset-manifest.csv` 共 11 条记录。
- 判断：不能用“1019 对 11”直接推断有 1008 个侵权文件，因为大量文件可能是同一自有界面的验收截图。但当前没有发行范围清单，也无法机器证明每张参考图、图标、截图和 AI 生成图的来源及再分发权。
- 修复：公开发行前建立媒体发布允许清单；按“项目原创界面截图、AI 生成、自制图标、第三方素材、禁止发行”分类；第三方项记录来源 URL、作者、版本、许可证、下载日期和内容哈希；不能证明的文件不得进入 release 包。
- 剩余风险：截图可能包含个人信息；AI 生成资产还受模型/服务条款及输入素材权利影响。

### P4-02 前端依赖许可证均有声明，但存在重点义务

- 严重度：中
- 状态：OBSERVED
- 证据：168 个唯一包的许可证分布为 MIT 121、ISC 34、Apache-2.0 5、BSD-3-Clause 4、MPL-2.0 2、BSD-2-Clause 1、Unlicense 1；未发现 `UNKNOWN`。
- 重点包：`lightningcss@1.33.0` 与平台包 `lightningcss-darwin-x64@1.33.0` 为 MPL-2.0；`robust-predicates@3.0.3` 为 Unlicense。
- 判断：没有仅凭元数据发现与 AGPL-3.0-only 明显冲突的依赖。MPL 是文件级 copyleft，分发时需要保留通知并满足对 MPL 文件的源代码义务；Unlicense 已列入 OSI 批准许可证，但仍应随发行保留上游许可文本和来源记录。
- 修复：生成 release 专用 notices；区分运行时进入产物的包与仅构建时使用的包；若修改 MPL 文件，单独记录修改和对应源码提供方式。
- 参考：[Lightning CSS 上游仓库](https://github.com/parcel-bundler/lightningcss)、[Mozilla MPL 2.0 FAQ](https://www.mozilla.org/en-US/MPL/2.0/FAQ/)、[OSI Unlicense 页面](https://opensource.org/license/unlicense)。

### P4-03 后端依赖许可证元数据基本完整

- 严重度：中
- 状态：OBSERVED
- 证据：现有 `build/libs/haoemr-0.1.0-SNAPSHOT.jar` 包含 72 个运行时 JAR；71 个可从本地 POM 或父 POM解析许可证。声明包括 Apache-2.0 系列、BSD-2-Clause、MIT、`EPL-2.0 OR LGPL-2.1-only`、`EPL 2.0 OR GPL2 w/ CPE` 以及 Apache/BSD 双许可。
- 本地工具限制：`spring-boot-jarmode-tools-4.1.0.jar` 是 Spring Boot 打包期生成/嵌入组件，未在本地 Gradle 模块缓存中解析出独立坐标，因此机器结果为 `UNKNOWN`。Spring Boot 上游项目明确以 Apache License 2.0 发布，可将其作为人工复核证据，但正式 SBOM 仍应保留精确组件来源。
- 修复：发行构建中生成 CycloneDX 或 SPDX SBOM；从实际发行包生成完整许可证与 NOTICE 集合；保留版本、PURL、哈希和直接/传递依赖关系。
- 参考：[Spring Boot 上游仓库与许可证](https://github.com/spring-projects/spring-boot)、[Flyway 上游仓库与许可证](https://github.com/flyway/flyway)、[Logback 上游许可证](https://github.com/qos-ch/logback/blob/master/LICENSE.txt)、[GNU 许可证兼容说明](https://www.gnu.org/licenses/license-compatibility.en.html)。

### P4-04 npm 锁文件使用两个下载源

- 严重度：中
- 状态：OBSERVED
- 证据：`web/package-lock.json` 中有 120 条解析到 `registry.npmmirror.com`，72 条解析到 `registry.npmjs.org`。
- 风险：双源增加制品来源、镜像同步和供应链响应复杂度；这不是许可证冲突，但会削弱可重复发行与溯源。
- 修复：发行前选定一个获准 registry，重新生成并审查 lockfile，在 CI 使用 lockfile 完整性校验和固定 Node/npm 版本。

### P4-05 历史贡献权属集中，但外部贡献流程尚未实际验证

- 严重度：低
- 状态：OBSERVED
- 证据：83 个可达提交只出现 `LIUgongxi2046 <wyzqbbw@gmail.com>` 一位作者；0 个提交带 `Signed-off-by`。
- 判断：历史提交没有出现多作者权属链问题的机器线索。项目已采用 CLA 路线，历史提交不需要为了形式统一而改写；外部贡献开始后，必须确保贡献者实际同意 CLA，不能只在仓库放一份文本。
- 修复：接受第一份外部实质贡献前，配置 CLA 记录流程和 PR 检查；保留第三方代码复制来源与许可证证据。

## P4 退出条件

公开发行前至少完成：

1. 确定 release 文件范围，并为其中所有媒体和数据建立可追溯允许清单。
2. 从实际发行制品生成 SBOM、许可证文本和 NOTICE 包。
3. 统一 npm registry 与可重复构建策略。
4. 对医疗术语、编码体系、药品知识、标准文本和机构模板逐项记录授权；未知项不随 release 发布。
5. 在第一份外部贡献合并前启用可验证的 CLA 流程。

结论仅代表当前仓库的工程审计证据，不构成法律意见或合规认证。
