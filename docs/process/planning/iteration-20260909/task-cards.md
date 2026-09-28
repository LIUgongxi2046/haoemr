# HaoEMR 增量开发逐任务施工单

2026-09-10 续开发范围补充：ITER-DEV-002 的真实跨岗位回归进一步确认门户缺少病区授权元数据，因此原 SessionUser 契约兼容增加 authorized_ward_ids，DevelopmentSessionService 从既有 ward_role_scope 读取当前用户/院区有效范围，ClinicalPortalPage 在已有卡片中使用，无新权限系统或数据表；病区业务接口仍逐次授权。原浏览器脚本增加可配置证据目录和签署回读完成断言，保护旧日志并避免只等待 HTTP 回包即导航。

2026-09-10 续开发范围补充：ITER-DEV-002 使用现有待办 composable 的请求策略与上下文快照辅助函数；ITER-DEV-027/032 使用原编辑器的离开确认、采用撤销辅助组件；ITER-DEV-031 在原 KnowledgeSearchController/Service、web/src/api/knowledge.ts 与 AgentCandidateReview.vue 增加精确知识片段核对。新增 GET /knowledge-chunks/{chunk_id} 为原知识服务只读接口，复用 KNOWLEDGE 租约、租户及有效性授权；OpenAPI 先行，无新表、主菜单、系统管理菜单或根路由。来源不可用不回退到其它版本。对应直接相关测试和生成契约纳入本卡允许范围。

版本 1.0 · 2026-09-09。全部为 **PLANNED**。以下“允许修改”只约束未来获得实施授权后的任务；本轮只写规划文档。

任务内验收使用 ITER-TAC 编号，保留原 ITER-FR / ITER-AC；完整逐条 Given/When/Then 与执行空栏见 [验收矩阵](acceptance-matrix.csv)。

共用输入：[开发原则](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/DEVELOPMENT_PRINCIPLES.md) · [PRD v0.2](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/docs/product/prd/2026-09-09-haoemr-next-iteration-prd.md) · [原系统融合约束](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/docs/design/ui-delivery/iteration-20260909/integration-revision.md) · [宿主页内交互规格](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/docs/design/ui-delivery/iteration-20260909/screens/specifications.md) · [状态矩阵](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/docs/design/ui-delivery/iteration-20260909/states/state-matrix.md)。

每项均需遵守 [验收手册](acceptance-and-release.md) 的通用完成定义、证据规则和命令运行条件。S/M/L 为相对规模，不是工期承诺。

## ITER-DEV-001 固定当前工作基线与证据规则

- 阶段 / 状态 / 负责人：R0 / PLANNED / 研发负责人 / QA。
- 风险 / 相对规模：中 / S。
- 来源：ITER-FR-001 / ITER-AC-001；非功能：ITER-NFR-003、ITER-NFR-009。
- 硬依赖：无；外部门禁：无新增外部输入；仍需满足依赖和当前授权范围。
- 可并行规划组：PG-01；同阶段候选：无。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-15。
- 页面承接：工程/契约任务，产物为文档或支撑能力；FR 中的审计宿主不表示新增验收产品页面。相关原入口：/admin-audit。
- 允许修改：[DEVELOPMENT_PRINCIPLES.md](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/DEVELOPMENT_PRINCIPLES.md)；[docs/process/testing](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/docs/process/testing)；[web/src/vue/components/ClinicalShell.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/components/ClinicalShell.vue)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 记录候选源码哈希、已有未提交改动、数据库快照和构建配置，区分用户改动与后续任务改动。
2. 建立检查结果枚举及证据目录；记录现有 15/13 导航名称、顺序、路由和计算样式。
3. 将历史失败转为待复现条目，并记录对应版本和环境。

**接口与数据影响：**仅工程证据；无接口或迁移。

**验收标准**

- **ITER-TAC-001-01**：给定已有旧版本通过报告，当汇总本候选结果，则旧报告标过期，未执行/失败/受阻分别列出，不计入本候选通过。
- **ITER-TAC-001-02**：给定工作区已有修改，当生成任务允许修改清单，则每个已有修改文件的哈希留档，未丢失、覆盖或自动回退。
- **ITER-TAC-001-03**：给定当前源码与隔离实例可用，当记录浏览器基准，则导航、主要 DOM 与计算样式可追溯到同一候选；登录受阻记录为受阻。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**撤销本任务新增文档；保留原始测试证据。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-002 按角色请求与上下文过期响应治理

- 阶段 / 状态 / 负责人：R0 / PLANNED / 前端 / 后端 / QA。
- 风险 / 相对规模：中 / M。
- 来源：ITER-FR-002 / ITER-AC-002；非功能：ITER-NFR-003、ITER-NFR-009。
- 硬依赖：ITER-DEV-001；外部门禁：无新增外部输入；仍需满足依赖和当前授权范围。
- 可并行规划组：PG-02；同阶段候选：无。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-11、SCR-12、SCR-15。
- 页面承接：/admin-audit、/opd-record、/outpatient。
- 允许修改：[web/src/vue/components/ClinicalShell.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/components/ClinicalShell.vue)；[web/src/vue/stores/clinical-context.ts](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/stores/clinical-context.ts)；[src/main/java/org/haoemr/security](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/security)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 复现无权限请求和后台轮询的具体来源，优先修复既有请求启停逻辑。
2. 身份、机构、患者或租约切换时停止旧请求，响应返回前再次校验上下文。
3. 保留服务端授权门禁，补齐拒绝与空数据的区分。

**接口与数据影响：**默认复用接口；若缺上下文版本字段先补契约评审，不新增认证。

**验收标准**

- **ITER-TAC-002-01**：给定两角色授权不同，当切换角色并观察网络，则无授权来源停止请求，直接调用受限 API 仍被拒绝。
- **ITER-TAC-002-02**：给定A 机构请求被延迟，当切到 B 后释放 A 响应，则A 的数据不写入 B 页面缓存、计数或按钮状态。
- **ITER-TAC-002-03**：给定接口拒绝读取，当页面展示结果，则明确无权限且无敏感内容，不显示 0 条作为替代。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**回退单一请求治理变更；保留服务端授权，不恢复越权路径。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-003 关键临床链定向复现与修复切片

- 阶段 / 状态 / 负责人：R0 / PLANNED / 临床模块开发 / QA。
- 风险 / 相对规模：高 / L。
- 来源：ITER-FR-003 / ITER-AC-003；非功能：ITER-NFR-003、ITER-NFR-009。
- 硬依赖：ITER-DEV-001、ITER-DEV-002；外部门禁：无新增外部输入；仍需满足依赖和当前授权范围。
- 可并行规划组：PG-03；同阶段候选：无。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-15。
- 页面承接：工程/契约任务，产物为文档或支撑能力；FR 中的审计宿主不表示新增验收产品页面。相关原入口：/admin-audit。
- 允许修改：[web/src/vue/views/OutpatientWorkspacePage.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/views/OutpatientWorkspacePage.vue)；[web/src/vue/views/RecordEditorPage.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/views/RecordEditorPage.vue)；[src/main/java/org/haoemr/clinical](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/clinical)；[web/scripts/verify-record-center-write-regression.mjs](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/scripts/verify-record-center-write-regression.mjs)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 在隔离实例复核历史病历保存、质控及审签失败，先归因再改动。
2. 逐个已复现根因创建本任务子缺陷卡，记录允许修改文件及复现条件。
3. 回归门诊写链和住院审签链，验证失败不会伪装成功。

**接口与数据影响：**不预定迁移；根因涉及公共契约时另补阻塞卡。

**验收标准**

- **ITER-TAC-003-01**：给定同候选隔离病例，当执行门诊保存及住院审签，则UI、响应、数据库版本及审计关联一致，结果有证据。
- **ITER-TAC-003-02**：给定历史报告曾失败，当在当前环境复现，则未复现项记录版本/配置差异；已复现项有根因及针对性修复，不直接重写模块。
- **ITER-TAC-003-03**：给定签署前提或规则不满足，当尝试签署，则原门禁拒绝且正文保留；不修改断言绕过约束。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**逐根因回退代码；不回退已签署事实或删除审计。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-004 初始化数据与启动写入完整盘点

- 阶段 / 状态 / 负责人：R1 / PLANNED / 数据负责人 / 后端。
- 风险 / 相对规模：中 / M。
- 来源：ITER-FR-004 / ITER-AC-004、ITER-FR-017 / ITER-AC-017、ITER-FR-018 / ITER-AC-018；非功能：ITER-NFR-001、ITER-NFR-002、ITER-NFR-003、ITER-NFR-006、ITER-NFR-009。
- 硬依赖：ITER-DEV-001；外部门禁：无新增外部输入；仍需满足依赖和当前授权范围。
- 可并行规划组：PG-02；同阶段候选：无。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-01、SCR-03、SCR-04、SCR-05、SCR-15。
- 页面承接：工程/契约任务，产物为文档或支撑能力；FR 中的审计宿主不表示新增验收产品页面。相关原入口：/admin、/admin-audit、/admin-parameters。
- 允许修改：[src/main/resources/db/migration](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/resources/db/migration)；[src/main/java/org/haoemr/development](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/development)；[src/main/resources](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/resources)；[scripts/test-schema.sh](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/scripts/test-schema.sh)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 盘点 SQL INSERT/UPDATE/DELETE、函数、触发器、启动导入器及其 profile 条件。
2. 在专用空数据库执行当前迁移并记录表、租户、数据来源、约束与自动任务变化。
3. 把结果分成结构、必需底座、医院主数据、可选配置、演示业务和秘密引用六类。

**接口与数据影响：**本任务只调查，不改已应用迁移；数据库隔离方法须先记录。

**验收标准**

- **ITER-TAC-004-01**：给定全新专用数据库，当执行当前启动与迁移，则有逐表计数和来源解释；文本扫描不能代替运行结果。
- **ITER-TAC-004-02**：给定发现可选或演示记录，当追查 SQL 和启动调用，则每条来源、目标、条件、归属及剥离影响可定位。
- **ITER-TAC-004-03**：给定已有开发或医院数据库存在，当运行盘点，则仅使用隔离数据库，未清理既有数据和迁移历史。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**销毁仅本任务创建的隔离库；归档盘点证据。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-005 新装与升级汇合方案决策

- 阶段 / 状态 / 负责人：R1 / PLANNED / 架构 / 数据负责人。
- 风险 / 相对规模：高 / M。
- 来源：ITER-FR-018 / ITER-AC-018、ITER-FR-019 / ITER-AC-019；非功能：ITER-NFR-001、ITER-NFR-002、ITER-NFR-003、ITER-NFR-006、ITER-NFR-009。
- 硬依赖：ITER-DEV-004；外部门禁：G-MIG：新装/升级汇合及新增数据范围完成设计评审。
- 可并行规划组：PG-03；同阶段候选：ITER-DEV-006。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-03、SCR-09、SCR-10、SCR-15、SCR-16。
- 页面承接：工程/契约任务，产物为文档或支撑能力；FR 中的审计宿主不表示新增验收产品页面。相关原入口：/admin、/admin-audit、/admin-jobs、/admin-parameters、/admin-templates。
- 允许修改：[src/main/resources/db/migration](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/resources/db/migration)；[docs/process/planning/2026-09-06-clean-install-and-module-data-pack-plan.md](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/docs/process/planning/2026-09-06-clean-install-and-module-data-pack-plan.md)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 依据盘点选择保留原迁移链或专用干净新装基线，不提前指定基线版本。
2. 定义旧库升级路径、最终结构/约束比较清单和已应用校验和保护。
3. 定义每个必要新增记录的复用理由、前后版本兼容期与恢复策略。

**接口与数据影响：**阻塞迁移实施；设计决策记录必须先于 SQL。

**验收标准**

- **ITER-TAC-005-01**：给定盘点已完成，当评审两条路径，则给出选定方案及证据，结构/必要数据责任明确。
- **ITER-TAC-005-02**：给定旧库有迁移历史和医院修改，当推演升级，则不修改已应用校验和，不以清演示数据作为默认升级步骤。
- **ITER-TAC-005-03**：给定需要新增字段或表，当评审复用与回退，则逐项说明现有表为何不足；有兼容读取和停止写入方案，不声称删表即可回滚。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**决策未落实前可改文档；不得试改现有库。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-006 引导与安装公共契约及权限决策

- 阶段 / 状态 / 负责人：R1 / PLANNED / 架构 / 后端 / 前端。
- 风险 / 相对规模：高 / M。
- 来源：ITER-FR-004 / ITER-AC-004、ITER-FR-006 / ITER-AC-006、ITER-FR-008 / ITER-AC-008、ITER-FR-009 / ITER-AC-009、ITER-FR-010 / ITER-AC-010、ITER-FR-011 / ITER-AC-011、ITER-FR-012 / ITER-AC-012、ITER-FR-013 / ITER-AC-013、ITER-FR-014 / ITER-AC-014、ITER-FR-015 / ITER-AC-015；非功能：ITER-NFR-001、ITER-NFR-002、ITER-NFR-003、ITER-NFR-006、ITER-NFR-009。
- 硬依赖：ITER-DEV-001、ITER-DEV-004；外部门禁：G-API：公共接口、身份边界、事务恢复设计已定；未定项阻塞依赖任务。
- 可并行规划组：PG-03；同阶段候选：ITER-DEV-005。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-01、SCR-03、SCR-04、SCR-05、SCR-06、SCR-07、SCR-08、SCR-09、SCR-10、SCR-14、SCR-16。
- 页面承接：工程/契约任务，产物为文档或支撑能力；FR 中的审计宿主不表示新增验收产品页面。相关原入口：/admin、/admin-jobs、/admin-parameters、/admin-templates。
- 允许修改：[contracts/openapi.json](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/contracts/openapi.json)；[src/main/java/org/haoemr/security](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/security)；[src/main/java/org/haoemr/platform](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/platform)；[src/main/java/org/haoemr/clinical/DocumentTemplateService.java](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/clinical/DocumentTemplateService.java)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 逐项映射现有组织、配置、能力包、任务、模板、审计用例；列出真正缺失的安装能力。
2. 确定受控引导载体、身份绑定、租户授权、查询/命令/错误语义和页内状态参数。
3. 确定预检绑定、版本比较、幂等、操作恢复、失败证据事务边界与 Outbox 行为；冻结 OpenAPI 变更设计。

**接口与数据影响：**G-API 后由对应纵向任务先改 OpenAPI 再生成；端点与新表名此时不编造。

**验收标准**

- **ITER-TAC-006-01**：给定已有领域服务，当评审安装接口，则组织/模板/能力包/任务/审计无平行内核，新增能力有缺口证据。
- **ITER-TAC-006-02**：给定新装和重复/并发命令，当评审状态与权限表，则明确部署身份、机构范围、幂等键及冲突语义；正式认证门禁保留。
- **ITER-TAC-006-03**：给定模板安装后需要发布，当核对原服务，则保留 DRAFT→另一授权人审批发布→PUBLISHED；无新增通用待审状态机。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**只回退未采用契约设计；已发布接口必须兼容演进。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-007 首包内容、格式与信任来源定版

- 阶段 / 状态 / 负责人：R1 / PLANNED / 产品 / 模板负责人 / 部署负责人。
- 风险 / 相对规模：中 / M。
- 来源：ITER-FR-008 / ITER-AC-008、ITER-FR-009 / ITER-AC-009、ITER-FR-017 / ITER-AC-017；非功能：ITER-NFR-001、ITER-NFR-002、ITER-NFR-003、ITER-NFR-006、ITER-NFR-009。
- 硬依赖：ITER-DEV-006；外部门禁：G-PACK：正式内容、许可与信任来源到位后才可交付正式包。
- 可并行规划组：PG-04；同阶段候选：ITER-DEV-008、ITER-DEV-009。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-04、SCR-05。
- 页面承接：工程/契约任务，产物为文档或支撑能力；FR 中的审计宿主不表示新增验收产品页面。相关原入口：/admin-parameters。
- 允许修改：[samples/data](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/samples/data)；[contracts](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/contracts)；[docs/product/prd/2026-09-09-haoemr-next-iteration-prd.md](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/docs/product/prd/2026-09-09-haoemr-next-iteration-prd.md)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 指定门诊基础模板包的稳定编码、版本、字段、适用范围与许可负责人。
2. 固定容器和 schema、大小/展开量/条目限制及版本规则，制作合成包和恶意包夹具。
3. 确定正式可信发布者及校验方式；测试信任根与正式来源严格区分。

**接口与数据影响：**数据包目录为待建交付物，不改变业务 Schema。

**验收标准**

- **ITER-TAC-007-01**：给定结构规范已定，当制作合法及异常夹具，则覆盖合法、损坏、越界、递归压缩、超限、重复编码、缺依赖、未知来源。
- **ITER-TAC-007-02**：给定首包含模板，当逐项审查内容，则不含患者、就诊、账号、密钥、SQL 或脚本；临床内容由指定负责人复核。
- **ITER-TAC-007-03**：给定仅测试发布者可用，当准备正式交付，则测试可继续，正式包门禁保持未满足；哈希通过不等于来源可信。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**撤回未发布包；已安装版本不删除，后续使用受控新版本。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-008 落实干净安装与兼容升级路径

- 阶段 / 状态 / 负责人：R1 / PLANNED / 数据 / 后端 / 运维。
- 风险 / 相对规模：高 / L。
- 来源：ITER-FR-018 / ITER-AC-018；非功能：ITER-NFR-001、ITER-NFR-002、ITER-NFR-003、ITER-NFR-006、ITER-NFR-009。
- 硬依赖：ITER-DEV-005、ITER-DEV-006；外部门禁：G-MIG、G-API 必须满足。
- 可并行规划组：PG-04；同阶段候选：ITER-DEV-007、ITER-DEV-009。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-03、SCR-15。
- 页面承接：工程/契约任务，产物为文档或支撑能力；FR 中的审计宿主不表示新增验收产品页面。相关原入口：/admin、/admin-audit。
- 允许修改：[src/main/resources/db/migration](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/resources/db/migration)；[src/main/resources](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/resources)；[scripts](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/scripts)；[deploy](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/deploy)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 按 G-MIG 实施最小迁移与启动配置，保持结构和约束完整。
2. 把可选/演示初始化从正式路径分离，不禁用触发器和授权约束。
3. 在新空库和旧库隔离副本分别运行，输出汇合差异与数据指纹。

**接口与数据影响：**仅追加审定迁移；新基线仅用于空库，不能对旧库强制 baseline。

**验收标准**

- **ITER-TAC-008-01**：给定空库，当执行正式初始化路径，则患者/就诊/医嘱/收费/执行记录为 0，必要结构与数据存在。
- **ITER-TAC-008-02**：给定含医院修改和历史病历的旧库副本，当升级，则原记录、版本、引用、审计与迁移历史保留，最终约束与新装一致。
- **ITER-TAC-008-03**：给定升级失败或旧应用回连，当执行兼容/恢复演练，则不会半应用结构；兼容窗口及恢复限制有证据，不用修改旧脚本修复校验和。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**先停新增写入；按已演练前滚修复或隔离备份恢复；禁止直接删除表/历史。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-009 安装操作与来源记录最小持久化

- 阶段 / 状态 / 负责人：R1 / PLANNED / 后端 / 数据。
- 风险 / 相对规模：高 / M。
- 来源：ITER-FR-012 / ITER-AC-012、ITER-FR-013 / ITER-AC-013、ITER-FR-014 / ITER-AC-014、ITER-FR-019 / ITER-AC-019；非功能：ITER-NFR-001、ITER-NFR-002、ITER-NFR-003、ITER-NFR-006、ITER-NFR-009。
- 硬依赖：ITER-DEV-005、ITER-DEV-006；外部门禁：无新增外部输入；仍需满足依赖和当前授权范围。
- 可并行规划组：PG-04；同阶段候选：ITER-DEV-007、ITER-DEV-008。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-06、SCR-07、SCR-08、SCR-09、SCR-10、SCR-16。
- 页面承接：工程/契约任务，产物为文档或支撑能力；FR 中的审计宿主不表示新增验收产品页面。相关原入口：/admin-jobs、/admin-parameters、/admin-templates。
- 允许修改：[src/main/java/org/haoemr/platform](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/platform)；[src/main/java/org/haoemr/audit](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/audit)；[src/main/resources/db/migration](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/resources/db/migration)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 仅补齐 G-API 已确认缺失的安装操作、预检引用和条目来源持久化。
2. 加入机构范围、版本/编码唯一性、状态转换与不可变证据约束。
3. 明确业务事务回滚后独立记录失败，以及进程中断后查询事实的方法。

**接口与数据影响：**最小追加迁移及契约生成；已有领域表只按审定方案增量关联。

**验收标准**

- **ITER-TAC-009-01**：给定同机构包版本并发，当直接并发写入和命令调用，则数据库唯一性阻止重复效果；不同机构互不串写。
- **ITER-TAC-009-02**：给定配置事务失败，当回滚并查询操作，则配置无残留，失败证据可保存；证据保存也失败时不能声称已回滚记录完整。
- **ITER-TAC-009-03**：给定旧来源缺失，当读取条目来源，则标为来源未知，不能补造官方原哈希或默认为未修改。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**关闭安装入口保留历史记录；兼容旧读取；数据恢复遵循 G-MIG。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-010 受控首次引导到原管理员登录

- 阶段 / 状态 / 负责人：R1 / PLANNED / 后端 / 运维。
- 风险 / 相对规模：高 / M。
- 来源：ITER-FR-004 / ITER-AC-004；非功能：ITER-NFR-003、ITER-NFR-009。
- 硬依赖：ITER-DEV-008、ITER-DEV-006；外部门禁：无新增外部输入；仍需满足依赖和当前授权范围。
- 可并行规划组：PG-05；同阶段候选：ITER-DEV-012。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-01。
- 页面承接：/admin。
- 允许修改：[src/main/java/org/haoemr/organization](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/organization)；[src/main/java/org/haoemr/authorization](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/authorization)；[src/main/java/org/haoemr/security](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/security)；[deploy](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/deploy)；[scripts](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/scripts)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 实现审定部署入口，绑定机构及首个已有身份，不新增匿名网页注册。
2. 复用组织/人员服务和唯一约束；校验启动前提并记录执行证据。
3. 输出下一步进入原管理工作台的结果和失败恢复说明。

**接口与数据影响：**按 G-API 复用身份命令与契约；只增加必要引导审计。

**验收标准**

- **ITER-TAC-010-01**：给定必要生产配置已具备，当首次受控引导并登录，则管理员绑定目标机构，在原管理工作台可继续配置，无临床样例。
- **ITER-TAC-010-02**：给定两个并发引导和重复执行，当执行相同目标，则仅一份机构/绑定结果；重复不重置管理员、口令或医院资料。
- **ITER-TAC-010-03**：给定来源未授权或必要连接缺失，当执行引导，则明确拒绝；无临时全员管理员、关闭认证或开发身份降级。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**停止引导入口；已创建身份通过原治理流程停用，不删除审计。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-011 原管理工作台初始化检查与组织补齐

- 阶段 / 状态 / 负责人：R1 / PLANNED / 前端 / 后端。
- 风险 / 相对规模：中 / M。
- 来源：ITER-FR-005 / ITER-AC-005、ITER-FR-006 / ITER-AC-006、ITER-FR-007 / ITER-AC-007；非功能：ITER-NFR-003、ITER-NFR-009。
- 硬依赖：ITER-DEV-010、ITER-DEV-006；外部门禁：无新增外部输入；仍需满足依赖和当前授权范围。
- 可并行规划组：PG-06；同阶段候选：ITER-DEV-013。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-01、SCR-02、SCR-03、SCR-14。
- 页面承接：/admin、/admin-org。
- 允许修改：[web/src/vue/views/AdminWorkspacePage.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/views/AdminWorkspacePage.vue)；[web/src/vue/views/OrganizationAdministrationPage.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/views/OrganizationAdministrationPage.vue)；[src/main/java/org/haoemr/organization/OrganizationAdministrationService.java](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/organization/OrganizationAdministrationService.java)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 新增管理工作台折叠检查区，按现有组织及依赖证据计算缺项。
2. 定位原组织树及原新增节点表单，沿用层级/有效期/权限，不复制资料草稿。
3. 重新登录后重新读取已保存状态，区分模块安装与临床可用。

**接口与数据影响：**读取契约最小扩展；组织写接口原则上不新增。

**验收标准**

- **ITER-TAC-011-01**：给定已存在机构但缺科室，当从检查项进入原组织树补齐，则节点按原规则保存并可查询；机构与管理员不重复创建。
- **ITER-TAC-011-02**：给定检查未执行或某模块依赖缺失，当查看汇总，则显示未检查/缺项、责任入口和证据时间，不把全系统标绿或停用。
- **ITER-TAC-011-03**：给定组织节点已保存后退出，当重新认证进入管理工作台，则进度由原记录恢复；未保存表单沿用原退出保护，无第二份机构草稿。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**隐藏新增检查区可恢复原首页；保留已创建组织节点与原维护入口。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-012 参数页内配置包目录与详情

- 阶段 / 状态 / 负责人：R1 / PLANNED / 前端 / 后端。
- 风险 / 相对规模：中 / M。
- 来源：ITER-FR-008 / ITER-AC-008、ITER-FR-009 / ITER-AC-009；非功能：ITER-NFR-001、ITER-NFR-002、ITER-NFR-003、ITER-NFR-006、ITER-NFR-009。
- 硬依赖：ITER-DEV-006、ITER-DEV-007；外部门禁：无新增外部输入；仍需满足依赖和当前授权范围。
- 可并行规划组：PG-05；同阶段候选：ITER-DEV-010。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-04、SCR-05。
- 页面承接：/admin-parameters。
- 允许修改：[web/src/vue/views/AdminParametersPage.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/views/AdminParametersPage.vue)；[web/src/vue/components/AdminActionDialog.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/components/AdminActionDialog.vue)；[src/main/java/org/haoemr/platform](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/platform)；[contracts/openapi.json](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/contracts/openapi.json)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 在原参数页增加模块初始化页内标签，保持参数/品牌视图和原路由。
2. 接真实包元数据读取及受控文件选择，列表筛选和详情共用原管理组件。
3. 展示来源与完整性、版本依赖、许可、条目概要及草稿语义。

**接口与数据影响：**按契约读取包元数据；不将包内容存为任意参数 JSON。

**验收标准**

- **ITER-TAC-012-01**：给定角色有读取权限且目录为空/筛选无匹配，当打开页内标签，则分别显示空目录或清除筛选；不填充假目录。
- **ITER-TAC-012-02**：给定可信和不可信包并存，当打开详情，则完整性和来源两项单独展示，未知来源无继续安装绕过按钮。
- **ITER-TAC-012-03**：给定使用原参数或品牌入口，当切换模块初始化再返回，则原功能可用，主菜单和二级导航未增加，依赖不被偷偷选中。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**停用新增页内视图；原参数/品牌入口保留，不影响已安装配置。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-013 服务端预检与有效快照

- 阶段 / 状态 / 负责人：R1 / PLANNED / 后端 / 前端 / QA。
- 风险 / 相对规模：高 / M。
- 来源：ITER-FR-010 / ITER-AC-010；非功能：ITER-NFR-001、ITER-NFR-002、ITER-NFR-003、ITER-NFR-006、ITER-NFR-009。
- 硬依赖：ITER-DEV-009、ITER-DEV-012；外部门禁：无新增外部输入；仍需满足依赖和当前授权范围。
- 可并行规划组：PG-06；同阶段候选：ITER-DEV-011。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-06。
- 页面承接：/admin-parameters。
- 允许修改：[src/main/java/org/haoemr/platform](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/platform)；[web/src/vue/views/AdminParametersPage.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/views/AdminParametersPage.vue)；[contracts/openapi.json](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/contracts/openapi.json)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 实现格式、来源、大小、租户、版本和依赖校验，返回逐项阻断和差异统计。
2. 预检绑定目标机构、包哈希、当前配置版本和到期时间，明确时钟与版本规则。
3. 接入原管理弹窗，在提交安装时服务端重新校验快照而非仅禁用前端按钮。

**接口与数据影响：**先更新 OpenAPI/生成物；默认 15 分钟仅在 G-API 固定后采用。

**验收标准**

- **ITER-TAC-013-01**：给定合法首包和授权机构，当执行预检，则返回新增/更新/保留/冲突数量及快照，配置写入为 0。
- **ITER-TAC-013-02**：给定损坏/越界/递归压缩/超限/缺依赖/跨机构/不兼容包，当执行预检，则逐项可定位错误且零配置写入，原始秘密和栈不回显。
- **ITER-TAC-013-03**：给定预检后修改机构/包/目标版本或超过有效期，当强行提交安装，则服务端拒绝旧快照并要求重新预检，不接受前端伪造通过。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**回退预检新增版本时关闭安装入口；过期预检不迁移为有效。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-014 三方差异与医院修改决策

- 阶段 / 状态 / 负责人：R1 / PLANNED / 前端 / 后端。
- 风险 / 相对规模：高 / M。
- 来源：ITER-FR-011 / ITER-AC-011、ITER-FR-019 / ITER-AC-019；非功能：ITER-NFR-001、ITER-NFR-002、ITER-NFR-003、ITER-NFR-006、ITER-NFR-009。
- 硬依赖：ITER-DEV-013；外部门禁：无新增外部输入；仍需满足依赖和当前授权范围。
- 可并行规划组：PG-07；同阶段候选：无。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-09、SCR-10、SCR-16。
- 页面承接：/admin-jobs、/admin-parameters、/admin-templates。
- 允许修改：[web/src/vue/views/AdminParametersPage.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/views/AdminParametersPage.vue)；[web/src/vue/components/AdminActionDialog.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/components/AdminActionDialog.vue)；[src/main/java/org/haoemr/platform](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/platform)；[src/main/java/org/haoemr/clinical/DocumentTemplateService.java](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/clinical/DocumentTemplateService.java)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 以原包、医院当前版本、新包构建三方差异，来源未知独立标记。
2. 实现默认保留、逐项决定及依赖完整性检查，不覆盖已发布文书模板。
3. 决策后重新生成预检，展示实际新增/保留计数及对应条目。

**接口与数据影响：**复用模板版本；差异决策关联预检，不新增审批域。

**验收标准**

- **ITER-TAC-014-01**：给定医院修改了模板，当比较新包，则原包/医院/候选三列可核对，默认保留医院内容。
- **ITER-TAC-014-02**：给定来源未知或必需依赖仍冲突，当尝试确认，则保持阻断；不把跳过单项误当包完整可装。
- **ITER-TAC-014-03**：给定逐项决策已完成，当重新预检并更改目标版本，则有效决策对应新快照；后续环境变化重新失效，发布版本未被改写。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**撤销未提交决策；已安装恢复通过任务 020 创建新版本。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-015 首包原子安装纵向闭环

- 阶段 / 状态 / 负责人：R1 / PLANNED / 后端 / 前端 / QA。
- 风险 / 相对规模：高 / L。
- 来源：ITER-FR-012 / ITER-AC-012；非功能：ITER-NFR-001、ITER-NFR-002、ITER-NFR-003、ITER-NFR-006、ITER-NFR-009。
- 硬依赖：ITER-DEV-009、ITER-DEV-013、ITER-DEV-014；外部门禁：无新增外部输入；仍需满足依赖和当前授权范围。
- 可并行规划组：PG-08；同阶段候选：无。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-06、SCR-07。
- 页面承接：/admin-jobs、/admin-parameters。
- 允许修改：[src/main/java/org/haoemr/platform](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/platform)；[src/main/java/org/haoemr/clinical/DocumentTemplateService.java](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/clinical/DocumentTemplateService.java)；[web/src/vue/views/AdminParametersPage.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/views/AdminParametersPage.vue)；[src/main/java/org/haoemr/audit](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/audit)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 在确认弹窗核对机构/包版本/数量/草稿后发起真实安装命令。
2. 通过原模板领域用例在单一业务事务导入首个小包，记录来源和审计 Outbox。
3. 实现请求级与长期包版本幂等，返回可查操作编号并定位原通知任务页。

**接口与数据影响：**原子事务必须核对原服务传播方式；不以并行调用多个独立事务冒充原子安装。

**验收标准**

- **ITER-TAC-015-01**：给定有效快照和合法首包，当确认安装，则仅生成对应数量 DRAFT，UI/API/来源记录一致；无自动发布、患者或外部业务任务。
- **ITER-TAC-015-02**：给定双击、相同键重试及换键重装同版本，当并发提交，则只产生一次配置效果；返回原结果或明确重复语义，冲突不吞为成功。
- **ITER-TAC-015-03**：给定写到中间条目抛错，当结束事务并核对数据库，则全部配置写入回滚，原已发布内容不变；失败记录独立保留。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**关闭安装写入口保留记录；已成功内容按原版本治理停用/恢复，不物理删除。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-016 断线、进程中断与安装恢复

- 阶段 / 状态 / 负责人：R1 / PLANNED / 后端 / 前端 / QA。
- 风险 / 相对规模：高 / L。
- 来源：ITER-FR-013 / ITER-AC-013；非功能：ITER-NFR-001、ITER-NFR-002、ITER-NFR-003、ITER-NFR-006、ITER-NFR-009。
- 硬依赖：ITER-DEV-015；外部门禁：无新增外部输入；仍需满足依赖和当前授权范围。
- 可并行规划组：PG-09；同阶段候选：ITER-DEV-021。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-07、SCR-08。
- 页面承接：/admin-jobs。
- 允许修改：[src/main/java/org/haoemr/platform](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/platform)；[web/src/vue/views/AdminJobsPage.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/views/AdminJobsPage.vue)；[src/main/java/org/haoemr/administration/AdministrationRuntimeService.java](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/administration/AdministrationRuntimeService.java)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 处理提交成功后响应丢失、提交前故障、执行进程中断及证据不可写窗口。
2. 以持久操作和事务事实查询恢复结果，未确认时保留 RECOVERY_REQUIRED。
3. 确认失败回滚后关联原操作重新预检，关闭详情不得取消已执行任务。

**接口与数据影响：**恢复状态沿 G-API；安装状态不映射成通用任务部分成功/可随时取消。

**验收标准**

- **ITER-TAC-016-01**：给定服务端完成但浏览器断线，当刷新并查询原操作，则找回原成功结果且无第二次安装；关闭详情不取消任务。
- **ITER-TAC-016-02**：给定进程在事务中或提交后中断，当重启并对账，则依据持久事实确认成功或失败；不能凭超时判失败。
- **ITER-TAC-016-03**：给定暂无法确认回滚，当用户尝试重试，则禁止盲目重装；待对账后关联原操作重试，失败编号和历史可追溯。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**停止新安装并由既有运维对账；不清除未决操作或强改状态。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-017 原通知任务页承接安装记录

- 阶段 / 状态 / 负责人：R1 / PLANNED / 前端 / 后端。
- 风险 / 相对规模：中 / M。
- 来源：ITER-FR-013 / ITER-AC-013、ITER-FR-014 / ITER-AC-014；非功能：ITER-NFR-001、ITER-NFR-002、ITER-NFR-003、ITER-NFR-006、ITER-NFR-009。
- 硬依赖：ITER-DEV-016；外部门禁：无新增外部输入；仍需满足依赖和当前授权范围。
- 可并行规划组：PG-10；同阶段候选：无。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-07、SCR-08、SCR-09。
- 页面承接：/admin-jobs。
- 允许修改：[web/src/vue/views/AdminJobsPage.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/views/AdminJobsPage.vue)；[web/src/vue/components/AdminActionDialog.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/components/AdminActionDialog.vue)；[src/main/java/org/haoemr/administration/AdministrationRuntimeService.java](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/administration/AdministrationRuntimeService.java)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 增加配置安装记录页内标签，接真实分页/筛选与操作详情。
2. 显示机构、发起人、版本、时间、阶段、结果、原操作关联和下一处理入口。
3. 保持原运行记录/任务定义及其重试、取消、部分成功语义。

**接口与数据影响：**安装查询契约复用运行展示宿主，不新增通用任务引擎。

**验收标准**

- **ITER-TAC-017-01**：给定有成功/失败/未知记录，当筛选并打开详情，则数量、顺序、分页和详情与后端一致；刷新仍可查询。
- **ITER-TAC-017-02**：给定安装已执行或结果未知，当查看操作按钮，则不出现中途取消和盲重试；已回滚才允许重新预检。
- **ITER-TAC-017-03**：给定原通用任务存在，当切回原标签执行既有用例，则原任务定义及运行行为不变；安装成功不会显示业务已上线。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**移除新增标签仍可通过原安装查询接口恢复；记录不删除。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-018 导入来源定位与原模板审批发布

- 阶段 / 状态 / 负责人：R1 / PLANNED / 临床后端 / 前端 / QA。
- 风险 / 相对规模：高 / M。
- 来源：ITER-FR-015 / ITER-AC-015；非功能：ITER-NFR-001、ITER-NFR-002、ITER-NFR-003、ITER-NFR-006、ITER-NFR-009。
- 硬依赖：ITER-DEV-015、ITER-DEV-017；外部门禁：无新增外部输入；仍需满足依赖和当前授权范围。
- 可并行规划组：PG-11；同阶段候选：ITER-DEV-019。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-08、SCR-16。
- 页面承接：/admin-jobs、/admin-templates。
- 允许修改：[web/src/vue/views/DocumentTemplateAdministrationPage.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/views/DocumentTemplateAdministrationPage.vue)；[src/main/java/org/haoemr/clinical/DocumentTemplateService.java](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/clinical/DocumentTemplateService.java)；[src/main/resources/db/migration](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/resources/db/migration)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 从安装详情跳转原模板列表并按来源定位，来源条件只作为查询不是授权。
2. 复用原预览、版本修订与审批发布，核对创建者身份映射。
3. 补齐服务端和数据库对自审、越权及版本冲突的防护证据。

**接口与数据影响：**优先仅来源筛选增量；不新增提交/待审/待发布状态，不篡改创建者绕过自审。

**验收标准**

- **ITER-TAC-018-01**：给定安装成功得到草稿，当从结果定位模板并预览，则只显示有权限的来源条目，实际模板字段/版本可核对。
- **ITER-TAC-018-02**：给定模板创建者或越权管理员，当直接调用审批接口，则自审/越权被服务端及既有 DB 门禁拒绝，状态保持 DRAFT。
- **ITER-TAC-018-03**：给定另一授权管理员，当执行原审批发布，则DRAFT 到 PUBLISHED；临床目录按原规则生效，草稿失败不使旧发布版失效。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**恢复旧版应用兼容读取；已发布内容通过原新版本机制调整。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-019 复用管理审计与安装证据导出

- 阶段 / 状态 / 负责人：R1 / PLANNED / 后端 / 前端 / QA。
- 风险 / 相对规模：中 / M。
- 来源：ITER-FR-014 / ITER-AC-014；非功能：ITER-NFR-001、ITER-NFR-002、ITER-NFR-003、ITER-NFR-006、ITER-NFR-009。
- 硬依赖：ITER-DEV-015、ITER-DEV-017；外部门禁：无新增外部输入；仍需满足依赖和当前授权范围。
- 可并行规划组：PG-11；同阶段候选：ITER-DEV-018。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-08、SCR-09。
- 页面承接：/admin-jobs。
- 允许修改：[web/src/vue/views/AdminAuditPage.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/views/AdminAuditPage.vue)；[src/main/java/org/haoemr/audit/AuditEventService.java](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/audit/AuditEventService.java)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 将安装、失败、恢复及来源关联映射到现有动作/对象筛选。
2. 沿用原授权导出，明确字段白名单和失败证据保留。
3. 检查业务命令、审计、Outbox 的一致性及外部投递边界。

**接口与数据影响：**扩展既有事件分类与查询，不平行建审计表。

**验收标准**

- **ITER-TAC-019-01**：给定同操作有预检/安装/失败/重试事件，当原管理审计筛选，则事件关联操作者/机构/版本/时间/原操作，失败记录不可用户删除。
- **ITER-TAC-019-02**：给定导出获授权结果，当检查文件与日志，则仅白名单字段，无口令、密钥、患者正文或跨机构记录。
- **ITER-TAC-019-03**：给定安装导入完成，当核对审计及 Outbox，则既有证据链完整，未自动触发模型/临床写入或外部通知。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**回退新增筛选映射；不可变历史证据继续保留。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-020 包升级与恢复医院版本

- 阶段 / 状态 / 负责人：R1 / PLANNED / 后端 / 前端 / QA。
- 风险 / 相对规模：高 / M。
- 来源：ITER-FR-019 / ITER-AC-019；非功能：ITER-NFR-001、ITER-NFR-002、ITER-NFR-003、ITER-NFR-006、ITER-NFR-009。
- 硬依赖：ITER-DEV-014、ITER-DEV-018；外部门禁：无新增外部输入；仍需满足依赖和当前授权范围。
- 可并行规划组：PG-12；同阶段候选：ITER-DEV-022。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-09、SCR-10、SCR-16。
- 页面承接：/admin-jobs、/admin-parameters、/admin-templates。
- 允许修改：[src/main/java/org/haoemr/platform](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/platform)；[src/main/java/org/haoemr/clinical/DocumentTemplateService.java](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/clinical/DocumentTemplateService.java)；[web/src/vue/views/AdminParametersPage.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/views/AdminParametersPage.vue)；[web/src/vue/views/DocumentTemplateAdministrationPage.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/views/DocumentTemplateAdministrationPage.vue)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 在同一导入流程承接版本升级，保留业务稳定编码和原来源。
2. 按三方决策创建新草稿，拒绝静默覆盖医院修改或未知来源。
3. 恢复旧内容时创建新版本并走原审批，不提供破坏性卸载。

**接口与数据影响：**沿用模板版本语义；不得引入删除历史的反向迁移。

**验收标准**

- **ITER-TAC-020-01**：给定旧包已被医院修改且业务引用，当导入新版，则默认保留修改和历史引用，差异及新草稿数量与预检相符。
- **ITER-TAC-020-02**：给定选择恢复历史模板内容，当完成恢复与审批，则生成可追踪新版本并由另一授权人发布，不把旧历史直接改成新内容。
- **ITER-TAC-020-03**：给定升级缺依赖或中途失败，当检查原业务，则已发布原版本继续可用；无部分配置残留或物理删除。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**回退候选应用并保留兼容来源记录；内容通过再发布恢复。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-021 正式与演示交付入口分离

- 阶段 / 状态 / 负责人：R1 / PLANNED / 后端 / 运维 / QA。
- 风险 / 相对规模：高 / M。
- 来源：ITER-FR-017 / ITER-AC-017；非功能：ITER-NFR-001、ITER-NFR-002、ITER-NFR-003、ITER-NFR-006、ITER-NFR-009。
- 硬依赖：ITER-DEV-008、ITER-DEV-010、ITER-DEV-015；外部门禁：无新增外部输入；仍需满足依赖和当前授权范围。
- 可并行规划组：PG-09；同阶段候选：ITER-DEV-016。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-04、SCR-05。
- 页面承接：/admin-parameters。
- 允许修改：[src/main/java/org/haoemr/development](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/development)；[src/main/resources](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/resources)；[deploy](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/deploy)；[scripts](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/scripts)；[samples/data](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/samples/data)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 明确正式构建和隔离演示启动配置；合成数据仅原受控演示入口。
2. 在后端拒绝正式环境 DEMO 包和混用 profile，保留去敏拒绝证据。
3. 回归演示环境原有临床示例，禁止用演示成功证明干净安装。

**接口与数据影响：**部署配置增量；不降低 ProductionEnvironmentPostProcessor 检查。

**验收标准**

- **ITER-TAC-021-01**：给定正式构建和正式环境，当上传 DEMO 包或混用开发 profile，则服务端拒绝或启动拒绝，未产生合成账号/患者/模型结果。
- **ITER-TAC-021-02**：给定隔离演示环境，当执行原合成导入，则可演示原业务并有合成标识，不能指向医院库。
- **ITER-TAC-021-03**：给定默认交付入口，当新部署及升级，则不自动执行 SyntheticDataImporter 或清理现有数据。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**回退启动脚本不删除演示/医院记录；正式模式继续 fail closed。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-022 原临床工作台空态补齐

- 阶段 / 状态 / 负责人：R1 / PLANNED / 前端 / 临床后端。
- 风险 / 相对规模：中 / M。
- 来源：ITER-FR-016 / ITER-AC-016；非功能：ITER-NFR-001、ITER-NFR-002、ITER-NFR-003、ITER-NFR-006、ITER-NFR-009。
- 硬依赖：ITER-DEV-011、ITER-DEV-018、ITER-DEV-021；外部门禁：无新增外部输入；仍需满足依赖和当前授权范围。
- 可并行规划组：PG-12；同阶段候选：ITER-DEV-020。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-11。
- 页面承接：/outpatient。
- 允许修改：[web/src/vue/views/OutpatientWorkspacePage.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/views/OutpatientWorkspacePage.vue)；[web/src/vue/views/InpatientWorkspacePage.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/views/InpatientWorkspacePage.vue)；[web/src/vue/views/RecordEditorPage.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/views/RecordEditorPage.vue)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 在原工作台区分未配置、无就诊、无模板、无权限和请求失败。
2. 按当前角色给原登记/配置负责人入口，保持身份条及原布局。
3. 检查页面是否硬编码依赖演示租户、默认患者或样例模板并定向移除。

**接口与数据影响：**默认复用已有列表/权限接口；不重建医生系统。

**验收标准**

- **ITER-TAC-022-01**：给定新机构无临床数据，当医生进入原门诊/住院工作台，则显示尚无就诊且合法入口可用，无报错或自动造患者。
- **ITER-TAC-022-02**：给定有就诊但无可用模板，当创建草稿，则明确模板缺项并定位原责任入口，不使用未发布或假模板。
- **ITER-TAC-022-03**：给定无权限或数据请求失败，当进入工作台，则分别展示拒绝/失败，不能冒充空态；既有非空机构工作流不退化。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**回退空态组件增量，保留业务数据；不重新引入演示兜底。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-023 安装安全、并发及性能专项验收

- 阶段 / 状态 / 负责人：R1 / PLANNED / QA / 安全 / 后端。
- 风险 / 相对规模：高 / M。
- 来源：ITER-FR-009 / ITER-AC-009、ITER-FR-010 / ITER-AC-010、ITER-FR-012 / ITER-AC-012、ITER-FR-013 / ITER-AC-013、ITER-FR-014 / ITER-AC-014、ITER-FR-017 / ITER-AC-017、ITER-FR-018 / ITER-AC-018；非功能：ITER-NFR-001、ITER-NFR-002、ITER-NFR-003、ITER-NFR-006、ITER-NFR-007、ITER-NFR-009。
- 硬依赖：ITER-DEV-016、ITER-DEV-019、ITER-DEV-020、ITER-DEV-021；外部门禁：无新增外部输入；仍需满足依赖和当前授权范围。
- 可并行规划组：PG-13；同阶段候选：ITER-DEV-024。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-03、SCR-04、SCR-05、SCR-06、SCR-07、SCR-08、SCR-09、SCR-15。
- 页面承接：工程/契约任务，产物为文档或支撑能力；FR 中的审计宿主不表示新增验收产品页面。相关原入口：/admin、/admin-audit、/admin-jobs、/admin-parameters。
- 允许修改：[src/test](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/test)；[web/scripts](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/scripts)；[scripts](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/scripts)；[docs/process/testing](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/docs/process/testing)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 固定设备、数据量、网络、并发、包上限及测试轮次，区分建议指标与确认阈值。
2. 跑非法包、越权、晚到响应、长期幂等和事务中断矩阵，记录数据库指纹。
3. 测预检/安装延迟、资源上限和日志敏感字段，收集不通过项并回到对应任务。

**接口与数据影响：**新增测试夹具；性能调整不得引入新架构，阈值变更须留决策。

**验收标准**

- **ITER-TAC-023-01**：给定G-API 固定限制和测试硬件，当运行最大合法包及超限包，则最大合法包可控处理、超限拒绝；无资源失控和配置残留。
- **ITER-TAC-023-02**：给定30 条目/10 MB 输入/5 并发的约定场景，当重复采样并计算分位，则报告样本数、p95 和环境；建议预检≤5秒/安装≤30秒需先确认，未达阈值不通过。
- **ITER-TAC-023-03**：给定恶意/跨租户/中断/重复矩阵，当逐项验证，则零越权、零重复配置、可核对回滚和失败审计，秘密扫描无命中。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**仅清理隔离夹具；发现数据问题停新安装并按任务 016 对账。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-024 原界面与导航回归验收

- 阶段 / 状态 / 负责人：R1 / PLANNED / 前端 / UI / QA。
- 风险 / 相对规模：中 / M。
- 来源：ITER-FR-005 / ITER-AC-005、ITER-FR-006 / ITER-AC-006、ITER-FR-008 / ITER-AC-008、ITER-FR-009 / ITER-AC-009、ITER-FR-010 / ITER-AC-010、ITER-FR-011 / ITER-AC-011、ITER-FR-012 / ITER-AC-012、ITER-FR-013 / ITER-AC-013、ITER-FR-014 / ITER-AC-014、ITER-FR-015 / ITER-AC-015、ITER-FR-016 / ITER-AC-016；非功能：ITER-NFR-001、ITER-NFR-002、ITER-NFR-003、ITER-NFR-004、ITER-NFR-005、ITER-NFR-006、ITER-NFR-009。
- 硬依赖：ITER-DEV-011、ITER-DEV-012、ITER-DEV-017、ITER-DEV-018、ITER-DEV-019、ITER-DEV-022；外部门禁：无新增外部输入；仍需满足依赖和当前授权范围。
- 可并行规划组：PG-13；同阶段候选：ITER-DEV-023。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-02、SCR-03、SCR-04、SCR-05、SCR-06、SCR-07、SCR-08、SCR-09、SCR-10、SCR-11、SCR-14、SCR-16。
- 页面承接：/admin、/admin-jobs、/admin-org、/admin-parameters、/admin-templates、/outpatient。
- 允许修改：[web/src/vue/components/ClinicalShell.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/components/ClinicalShell.vue)；[web/src/main.ts](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/main.ts)；[web/src/styles.css](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/styles.css)；[web/src/prototype.css](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/prototype.css)；[web/src/align-prototype.css](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/align-prototype.css)；[web/src/vue/vue-shell.css](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/vue-shell.css)；[web/scripts/verify-system-administration.mjs](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/scripts/verify-system-administration.mjs)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 在同候选真实登录应用记录原宿主与增量页 DOM/计算样式，核对原导航及入口。
2. 覆盖四视口、200% 缩放、键盘弹窗、焦点恢复和主要按钮对比度。
3. 回归原组织、参数/品牌、任务、模板、审计以及未改临床页面的关键入口。

**接口与数据影响：**不准以视觉验收为由替换原 CSS 或设计体系；发现既有缺陷另列范围。

**验收标准**

- **ITER-TAC-024-01**：给定当前登录应用与任务 001 基线，当核对主/二级导航，则15/13 项名称顺序不变，新根路由 0；新增仅页内区块/标签/弹窗。
- **ITER-TAC-024-02**：给定1440/1280/768/390 四视口与 200% 缩放，当执行导入/记录/审批核心动作，则页面无横向溢出和遮挡，宽差异表仅容器横滚；触控主目标≥44px。
- **ITER-TAC-024-03**：给定键盘和计算样式检查，当打开/关闭弹窗并遍历控件，则焦点可见/受约束/恢复；正文对比目标≥4.5:1；未改全局壳层样式，差异仅限增量组件。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**回退增量样式/DOM；恢复原引用顺序，禁止整体覆盖现有 CSS。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-025 R1 全链验收与安装交付文档

- 阶段 / 状态 / 负责人：R1 / PLANNED / QA / 运维 / 产品负责人。
- 风险 / 相对规模：中 / M。
- 来源：ITER-FR-001 / ITER-AC-001、ITER-FR-003 / ITER-AC-003、ITER-FR-004 / ITER-AC-004、ITER-FR-005 / ITER-AC-005、ITER-FR-006 / ITER-AC-006、ITER-FR-007 / ITER-AC-007、ITER-FR-008 / ITER-AC-008、ITER-FR-009 / ITER-AC-009、ITER-FR-010 / ITER-AC-010、ITER-FR-011 / ITER-AC-011、ITER-FR-012 / ITER-AC-012、ITER-FR-013 / ITER-AC-013、ITER-FR-014 / ITER-AC-014、ITER-FR-015 / ITER-AC-015、ITER-FR-016 / ITER-AC-016、ITER-FR-017 / ITER-AC-017、ITER-FR-018 / ITER-AC-018、ITER-FR-019 / ITER-AC-019；非功能：ITER-NFR-001、ITER-NFR-002、ITER-NFR-003、ITER-NFR-006、ITER-NFR-009。
- 硬依赖：ITER-DEV-003、ITER-DEV-007、ITER-DEV-008、ITER-DEV-010、ITER-DEV-011、ITER-DEV-018、ITER-DEV-019、ITER-DEV-020、ITER-DEV-021、ITER-DEV-022、ITER-DEV-023、ITER-DEV-024；外部门禁：G-R1：正式包来源、迁移、权限、全部端到端证据齐备。
- 可并行规划组：PG-14；同阶段候选：无。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-01、SCR-02、SCR-03、SCR-04、SCR-05、SCR-06、SCR-07、SCR-08、SCR-09、SCR-10、SCR-11、SCR-14、SCR-15、SCR-16。
- 页面承接：工程/契约任务，产物为文档或支撑能力；FR 中的审计宿主不表示新增验收产品页面。相关原入口：/admin、/admin-audit、/admin-jobs、/admin-org、/admin-parameters、/admin-templates、/outpatient。
- 允许修改：[docs/process/testing](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/docs/process/testing)；[docs/process/planning](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/docs/process/planning)；[deploy](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/deploy)；[scripts](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/scripts)；[README.md](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/README.md)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 冻结同一候选版本，分别执行空库全链和旧库升级全链，汇总必测场景证据。
2. 让未参与实现的执行者依文档完成部署、引导、导入、审批和恢复。
3. 形成兼容矩阵、配置说明、包清单、已知限制与停止条件；仅对实测命令更新交付说明。

**接口与数据影响：**交付文档与测试为主；新的缺陷归属原任务，不临时扩大范围。

**验收标准**

- **ITER-TAC-025-01**：给定受控新装环境和正式认可首包，当按文档走引导→原组织→参数导入→任务→模板审批→工作台，则全链 UI/API/DB 一致且临床数据仍为 0，安装不等于自动启用。
- **ITER-TAC-025-02**：给定旧版本隔离副本有医院修改/业务历史，当升级并演练冲突恢复，则结构/约束对齐，医院数据、历史版本和校验和保留，无自动清库。
- **ITER-TAC-025-03**：给定所有 R1 必需检查，当汇总放行记录，则同候选证据齐全，阻断项为 0；未执行不算通过；正式来源未定只能标测试候选可验收。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**不放行不完整候选；保留上一兼容版本和演练过的恢复路径。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-026 原就诊工作流与患者切换保护

- 阶段 / 状态 / 负责人：R2 / PLANNED / 前端 / 临床后端。
- 风险 / 相对规模：中 / M。
- 来源：ITER-FR-020 / ITER-AC-020；非功能：ITER-NFR-003、ITER-NFR-008、ITER-NFR-009。
- 硬依赖：ITER-DEV-025、ITER-DEV-002；外部门禁：无新增外部输入；仍需满足依赖和当前授权范围。
- 可并行规划组：PG-15；同阶段候选：ITER-DEV-030。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-11、SCR-12。
- 页面承接：/opd-record、/outpatient。
- 允许修改：[web/src/vue/views/OutpatientWorkspacePage.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/views/OutpatientWorkspacePage.vue)；[web/src/vue/views/RecordEditorPage.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/views/RecordEditorPage.vue)；[web/src/vue/stores/clinical-context.ts](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/stores/clinical-context.ts)；[web/src/vue/components/RecordPatientStrip.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/components/RecordPatientStrip.vue)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 复核当前已有患者条和切换保护，在现有工作台完成缺口修复。
2. 实现保存并切换/放弃/留在当前页，保存失败不得切换。
3. 患者/就诊/租约变化时使旧质控、候选与晚到响应失效。

**接口与数据影响：**复用上下文租约和原保存命令，不新建患者状态源。

**验收标准**

- **ITER-TAC-026-01**：给定当前文书有未保存修改，当点击另一患者，则出现三种明确选择，身份条持续可见且不先切换数据。
- **ITER-TAC-026-02**：给定选择保存并切换且保存失败，当观察原文书，则留在当前患者并保留输入、错误和重试入口。
- **ITER-TAC-026-03**：给定旧患者的质控或候选响应延迟，当切换后响应到达，则不能覆盖新上下文或保留可采用按钮。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**回退本次切换增量；不清空已保存草稿/审计。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-027 模板溯源与草稿可靠保存

- 阶段 / 状态 / 负责人：R2 / PLANNED / 临床后端 / 前端。
- 风险 / 相对规模：高 / M。
- 来源：ITER-FR-021 / ITER-AC-021；非功能：ITER-NFR-003、ITER-NFR-008、ITER-NFR-009。
- 硬依赖：ITER-DEV-026；外部门禁：无新增外部输入；仍需满足依赖和当前授权范围。
- 可并行规划组：PG-16；同阶段候选：ITER-DEV-031。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-12。
- 页面承接：/opd-record。
- 允许修改：[web/src/vue/views/RecordEditorPage.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/views/RecordEditorPage.vue)；[web/src/vue/views/OutpatientRecordPage.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/views/OutpatientRecordPage.vue)；[src/main/java/org/haoemr/clinical](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/clinical)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 复用已发布模板选用流程，记录模板和文书版本关联。
2. 后台刷新不覆盖 dirty 输入；保存校验预期版本并区分保存中/成功/失败。
3. 冲突提供比较与恢复，不偷偷刷新 rowVersion 后覆盖别人的内容。

**接口与数据影响：**现有临床命令优先；缺来源字段时先兼容扩展契约并审定迁移。

**验收标准**

- **ITER-TAC-027-01**：给定当前就诊可用已发布模板，当应用并保存，则草稿记录模板版本、保存时间及内容哈希，未发布模板不可选。
- **ITER-TAC-027-02**：给定编辑未保存且列表后台刷新，当释放刷新响应，则输入和未保存标记不丢失，成功提示仅由实际保存回执触发。
- **ITER-TAC-027-03**：给定另一编辑者已更新版本，当提交旧版内容，则明确版本冲突且保留输入，比较后重新提交；不覆盖对方版本。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**停止新增来源写入仍可读旧记录；内容修复采用版本机制。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-028 内嵌质控与保存解耦完善

- 阶段 / 状态 / 负责人：R2 / PLANNED / 前端 / 临床后端 / QA。
- 风险 / 相对规模：高 / M。
- 来源：ITER-FR-022 / ITER-AC-022；非功能：ITER-NFR-003、ITER-NFR-008、ITER-NFR-009。
- 硬依赖：ITER-DEV-027；外部门禁：无新增外部输入；仍需满足依赖和当前授权范围。
- 可并行规划组：PG-17；同阶段候选：ITER-DEV-032。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-12。
- 页面承接：/opd-record。
- 允许修改：[web/src/vue/components/RecordQcPreview.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/components/RecordQcPreview.vue)；[web/src/vue/components/EncounterRecordQcPanel.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/components/EncounterRecordQcPanel.vue)；[web/src/vue/record-qc.test.ts](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/record-qc.test.ts)；[web/src/vue/record-qc-automation.test.ts](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/record-qc-automation.test.ts)；[src/main/java/org/haoemr/clinical](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/clinical)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 复现现有保存后质控、历史读取和定位缺口，只修已确认问题。
2. 保存与规则调用解耦；结果绑定文书版本及哈希。
3. 保留历史可查，当前问题仅定位真实存在字段，修改后旧结果失效。

**接口与数据影响：**不得改临床签署状态机；结果版本字段按既有契约复用。

**验收标准**

- **ITER-TAC-028-01**：给定规则服务超时或失败，当保存当前正文，则正文先独立保存成功，规则失败单独提示，不丢草稿。
- **ITER-TAC-028-02**：给定检查通过后正文改变，当查看结果并尝试签署，则旧检查显示已失效；签署继续遵守原门禁，旧通过不充当当前通过。
- **ITER-TAC-028-03**：给定历史问题字段已不存在，当查看历史并点击定位，则历史明确所属版本，不伪造当前发现或跳到错误字段；覆盖全部七种状态。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**回退展示增量，保留历史质控证据和原签署硬门禁。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-029 医生试用基线与效率验收

- 阶段 / 状态 / 负责人：R2 / PLANNED / 产品 / 临床代表 / QA。
- 风险 / 相对规模：中 / M。
- 来源：ITER-FR-023 / ITER-AC-023；非功能：ITER-NFR-003、ITER-NFR-008、ITER-NFR-009。
- 硬依赖：ITER-DEV-026、ITER-DEV-027、ITER-DEV-028；外部门禁：G-USER：指定试用医生及任务定义到位。
- 可并行规划组：PG-18；同阶段候选：ITER-DEV-033。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-15。
- 页面承接：工程/契约任务，产物为文档或支撑能力；FR 中的审计宿主不表示新增验收产品页面。相关原入口：/admin-audit。
- 允许修改：[docs/process/testing](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/docs/process/testing)；[web/src/vue/views/OutpatientWorkspacePage.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/views/OutpatientWorkspacePage.vue)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 在首次测试前固定合成病例、任务起止点、成功条件及协助记录方法。
2. 招募 3–5 名指定医生完成同任务前后测试并记录失败和卡点。
3. 汇总中位数、范围、无协助成功率及修改建议，不建设新统计主菜单。

**接口与数据影响：**默认仅观察表；必要埋点需先批准事件白名单，不另建分析系统。

**验收标准**

- **ITER-TAC-029-01**：给定医生已知晓且病例为合成，当记录任务基线，则有开始/结束定义、任务成功条件、环境和有效样本规则。
- **ITER-TAC-029-02**：给定实施后重复同任务，当汇总结果，则逐样本保留耗时/协助/失败，不剔除不利数据制造提升。
- **ITER-TAC-029-03**：给定提议耗时降低 20%，当评审试用结果，则标明是否达到已确认目标；小样本只报告观察，数据无患者正文。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**停止新增采集并按保留规则处理样本，业务功能不受影响。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-030 AI 用例、来源与采用契约决策

- 阶段 / 状态 / 负责人：R2 / PLANNED / AI 负责人 / 临床 / 架构。
- 风险 / 相对规模：高 / M。
- 来源：ITER-FR-024 / ITER-AC-024、ITER-FR-025 / ITER-AC-025、ITER-FR-026 / ITER-AC-026、ITER-FR-027 / ITER-AC-027；非功能：ITER-NFR-003、ITER-NFR-008、ITER-NFR-009。
- 硬依赖：ITER-DEV-025；外部门禁：G-AI：用例边界先定；真实调用另需模型、知识授权与预算，发布另需评测门槛。
- 可并行规划组：PG-15；同阶段候选：ITER-DEV-026。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-12、SCR-13、SCR-14。
- 页面承接：工程/契约任务，产物为文档或支撑能力；FR 中的审计宿主不表示新增验收产品页面。相关原入口：/admin、/ai-assistant、/opd-record。
- 允许修改：[src/main/java/org/haoemr/agent/MedicalAgentHarnessService.java](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/agent/MedicalAgentHarnessService.java)；[src/main/java/org/haoemr/model](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/model)；[src/main/java/org/haoemr/knowledge](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/knowledge)；[web/src/vue/components/GlobalAiAssistantDialog.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/components/GlobalAiAssistantDialog.vue)；[contracts/openapi.json](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/contracts/openapi.json)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 核对原 Eva、模型路由、知识权限、候选存储和采用用例，列明缺口。
2. 固定摘要/草稿/检索用例、模型版本、知识授权、预算/超时、评测集和门槛。
3. 定义文书版本绑定、逐项采用与撤销、模型不可用和敏感数据边界。

**接口与数据影响：**公共接口及数据边界未知先决策；扩展按 OpenAPI 生成。

**验收标准**

- **ITER-TAC-030-01**：给定已有 Eva 与模型治理，当核对可复用能力，则不新增 AI 助手系统、平行模型网关或候选事实表。
- **ITER-TAC-030-02**：给定缺真实模型/来源授权/预算/评测阈值，当评审实施输入，则记录责任人和缺项；仅夹具验证可继续，真实调用与发布被门禁阻塞。
- **ITER-TAC-030-03**：给定候选需要采用，当评审契约，则采用经现有领域权限/版本校验，仅形成草稿；签署/处方无自动调用。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**撤回未采用用例设计；保留原 Eva 功能。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-031 原 Eva 内带依据检索

- 阶段 / 状态 / 负责人：R2 / PLANNED / AI / 知识后端 / 前端。
- 风险 / 相对规模：中 / M。
- 来源：ITER-FR-025 / ITER-AC-025；非功能：ITER-NFR-003、ITER-NFR-008、ITER-NFR-009。
- 硬依赖：ITER-DEV-030；外部门禁：无新增外部输入；仍需满足依赖和当前授权范围。
- 可并行规划组：PG-16；同阶段候选：ITER-DEV-027。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-13。
- 页面承接：/ai-assistant。
- 允许修改：[src/main/java/org/haoemr/knowledge](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/knowledge)；[web/src/vue/components/GlobalAiAssistantDialog.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/components/GlobalAiAssistantDialog.vue)；[web/src/vue/views/AiAssistantPage.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/views/AiAssistantPage.vue)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 复用知识检索和授权，把文档/版本/章节/片段/范围接入原证据区域。
2. 来源打开时再次检查可访问性，区分无命中、来源失效、服务失败。
3. 复核当前上下文和数据最小化，不把包说明冒充临床依据。

**接口与数据影响：**复用检索/引用契约，补充字段须兼容；禁止新增无授权爬取来源。

**验收标准**

- **ITER-TAC-031-01**：给定授权知识库有命中，当检索并打开引用，则能定位真实文档版本/章节/片段，展示适用范围与有效性。
- **ITER-TAC-031-02**：给定知识无命中或来源不可访问，当发起检索/打开，则明确相应原因，不伪造链接、内容或引用。
- **ITER-TAC-031-03**：给定用户切换机构或知识授权撤销，当读取旧引用，则后端拒绝且前端不暴露缓存片段，旧响应不回填。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**关闭新增证据呈现或检索用例，保留原知识目录。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-032 原 Eva 候选逐项采用与撤销

- 阶段 / 状态 / 负责人：R2 / PLANNED / AI / 临床后端 / 前端。
- 风险 / 相对规模：高 / M。
- 来源：ITER-FR-024 / ITER-AC-024；非功能：ITER-NFR-003、ITER-NFR-008、ITER-NFR-009。
- 硬依赖：ITER-DEV-027、ITER-DEV-030、ITER-DEV-031；外部门禁：无新增外部输入；仍需满足依赖和当前授权范围。
- 可并行规划组：PG-17；同阶段候选：ITER-DEV-028。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-12、SCR-13。
- 页面承接：/ai-assistant、/opd-record。
- 允许修改：[src/main/java/org/haoemr/agent/MedicalAgentHarnessService.java](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/agent/MedicalAgentHarnessService.java)；[web/src/vue/components/GlobalAiAssistantDialog.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/components/GlobalAiAssistantDialog.vue)；[web/src/vue/use-eva-clinical-context.ts](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/use-eva-clinical-context.ts)；[web/src/vue/views/RecordEditorPage.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/views/RecordEditorPage.vue)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 在既有候选通道生成摘要/草稿，绑定患者/就诊/文书版本/模型与知识来源。
2. 逐项采用前由领域用例重新校验授权与版本，记录采用事件。
3. 支持撤销本次采用且保留医生后续编辑；发生冲突交由用户比较，不整页替换。

**接口与数据影响：**沿用候选与临床事实物理分层；新增来源或采用字段先走兼容契约。

**验收标准**

- **ITER-TAC-032-01**：给定同患者同就诊同文书版本且用例可用，当生成并采用单条，则只有选择的内容进入可编辑草稿，来源/采用记录可查，无自动签署或医嘱。
- **ITER-TAC-032-02**：给定患者未变但文书版本已变，当采用旧候选，则服务端拒绝过期候选，提示重新核验/生成。
- **ITER-TAC-032-03**：给定采用后医生又编辑，当撤销本次采用，则保留与本次采用无关的后续编辑，冲突显式比较，不静默覆盖。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**停用用例但保留已采用草稿/审计；不得自动撤销医生内容。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-033 模型失败与手工继续闭环

- 阶段 / 状态 / 负责人：R2 / PLANNED / AI / 前端 / QA。
- 风险 / 相对规模：中 / M。
- 来源：ITER-FR-026 / ITER-AC-026；非功能：ITER-NFR-003、ITER-NFR-008、ITER-NFR-009。
- 硬依赖：ITER-DEV-032；外部门禁：无新增外部输入；仍需满足依赖和当前授权范围。
- 可并行规划组：PG-18；同阶段候选：ITER-DEV-029。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-13。
- 页面承接：/ai-assistant。
- 允许修改：[web/src/vue/components/GlobalAiAssistantDialog.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/components/GlobalAiAssistantDialog.vue)；[src/main/java/org/haoemr/agent](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/agent)；[src/main/java/org/haoemr/model](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/model)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 处理无可发布模型、超时、失败、预算不足及取消请求。
2. 给原编辑器手工继续入口；恢复后重试不重复采用。
3. 记录去敏错误编号，保持正文保存与模型调用独立。

**接口与数据影响：**复用错误与预算契约；不得绕过模型发布条件。

**验收标准**

- **ITER-TAC-033-01**：给定无可发布模型/超时/预算不足，当请求辅助，则显示真实原因和手工继续，无样例候选或测试模型兜底。
- **ITER-TAC-033-02**：给定模型服务不可用，当编辑并保存正文，则原保存、质控及签署门禁照常工作，不被 AI 等待锁住。
- **ITER-TAC-033-03**：给定请求响应丢失后重试，当采用返回候选，则不自动重复插入已采用内容，旧上下文响应作废。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**停新增用例调用；保留原手工路径与候选历史。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-034 独立评测与原模型发布门禁

- 阶段 / 状态 / 负责人：R2 / PLANNED / 模型管理员 / 独立评测人员。
- 风险 / 相对规模：高 / M。
- 来源：ITER-FR-027 / ITER-AC-027；非功能：ITER-NFR-003、ITER-NFR-009。
- 硬依赖：ITER-DEV-031、ITER-DEV-032、ITER-DEV-033；外部门禁：G-AI-RELEASE：真实版本独立评测通过；未定输入不得默认通过。
- 可并行规划组：PG-19；同阶段候选：无。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-13、SCR-14。
- 页面承接：工程/契约任务，产物为文档或支撑能力；FR 中的审计宿主不表示新增验收产品页面。相关原入口：/admin、/ai-assistant。
- 允许修改：[web/src/vue/views/ModelEvaluationPage.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/views/ModelEvaluationPage.vue)；[web/src/vue/views/ModelDeploymentPage.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/views/ModelDeploymentPage.vue)；[src/main/java/org/haoemr/model](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/model)；[evals](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/evals)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 沿用原评测与发布链，固定评测集/版本/用例/知识版本及成本统计。
2. 执行证据支持、来源错配、过期采用、错误类型、时延及费用评测。
3. 将通过结果定位回原模型治理；缺证据/阈值的候选不放行。

**接口与数据影响：**评测阈值 G-AI 固定；不新增平行发布审批。

**验收标准**

- **ITER-TAC-034-01**：给定正式候选模型和已确认评测集，当独立人员执行评测，则报告绑定实际模型/用例/版本，错误逐项可追踪且费用可核对。
- **ITER-TAC-034-02**：给定测试模型或缺来源/阈值，当请求发布，则原服务端治理拒绝，不用设计原型或测试分数替代正式证据。
- **ITER-TAC-034-03**：给定模型或知识版本变化，当引用旧评测发布，则旧证据失效或要求重新评估，原模型治理页面可见理由。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**停用问题模型/路由按原流程回退到已验收版本；无可用版本则手工继续。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-035 单院单科试点范围与真实前提冻结

- 阶段 / 状态 / 负责人：R3 / PLANNED / 试点负责人 / 院方 / 运维。
- 风险 / 相对规模：中 / M。
- 来源：ITER-FR-028 / ITER-AC-028；非功能：ITER-NFR-003、ITER-NFR-009、ITER-NFR-010。
- 硬依赖：ITER-DEV-025；外部门禁：G-PILOT：院方、环境、责任人、范围及恢复目标明确。
- 可并行规划组：PG-15；同阶段候选：无。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-14。
- 页面承接：工程/契约任务，产物为文档或支撑能力；FR 中的审计宿主不表示新增验收产品页面。相关原入口：/admin。
- 允许修改：[docs/process/planning](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/docs/process/planning)；[web/src/vue/views/AdminWorkspacePage.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/views/AdminWorkspacePage.vue)；[web/src/vue/views/AdminAuthPage.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/views/AdminAuthPage.vue)；[web/src/vue/views/IntegrationPage.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/views/IntegrationPage.vue)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 指定机构/科室/业务链、责任人、时间窗口及停止条件，AI 是否纳入明确选定。
2. 按既有入口列出认证、签名、存储、选定接口及模型的真实前提。
3. 确认负载、RPO/RTO、备份责任和恢复窗口，建立环境与证据索引。

**接口与数据影响：**默认检查汇总及文档，不新增试点系统或接口平台。

**验收标准**

- **ITER-TAC-035-01**：给定机构/科室和范围已指定，当冻结试点清单，则每个必需项有责任人、真实环境和验收用例，不以全院范围扩张。
- **ITER-TAC-035-02**：给定部分依赖只有连通结果，当查看原管理检查项，则连通与业务验收分列，未验证不能一键全通过。
- **ITER-TAC-035-03**：给定范围含 AI 或医生效率改动，当生成放行条件，则分别加入 034 或 029 的证据；不选 AI 不阻塞基础安装候选。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**撤销未执行试点计划；原业务保持，已接入项通过原配置停用。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-036 选定真实接口与异常恢复

- 阶段 / 状态 / 负责人：R3 / PLANNED / 集成 / 后端 / QA。
- 风险 / 相对规模：高 / M。
- 来源：ITER-FR-029 / ITER-AC-029；非功能：ITER-NFR-003、ITER-NFR-009、ITER-NFR-010。
- 硬依赖：ITER-DEV-035；外部门禁：指定测试端授权、消息规范及秘密引用已提供。
- 可并行规划组：PG-16；同阶段候选：ITER-DEV-037。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-14。
- 页面承接：/admin。
- 允许修改：[src/main/java/org/haoemr/integration/IntegrationMessageService.java](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/integration/IntegrationMessageService.java)；[web/src/vue/views/IntegrationPage.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/views/IntegrationPage.vue)；[web/src/vue/views/IntegrationMessagesPage.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/views/IntegrationMessagesPage.vue)；[web/src/vue/views/IntegrationMessageDetailPage.vue](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/web/src/vue/views/IntegrationMessageDetailPage.vue)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 只对试点选定接口接线或补缺，复用现有发送、回执、重放和对账。
2. 逐项执行成功、超时、重复回执、乱序/迟到和失败恢复。
3. 保留外部真实回执去敏证据，秘密通过既有配置引用。

**接口与数据影响：**适配字段变化先更新既有契约；不增加新协议总线或无关接口。

**验收标准**

- **ITER-TAC-036-01**：给定真实测试端和回执约定可用，当发送业务消息，则存在实际外部回执可核对，内部投影/Outbox 写入不算送达。
- **ITER-TAC-036-02**：给定同一回执重复或迟到，当接收并对账，则不重复产生业务效果；乱序处理符合约定且有审计。
- **ITER-TAC-036-03**：给定外部超时或失败，当原接口页定位并受控重放，则可追踪原消息和恢复结果，不产生重复入账或伪成功。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**停用选定连接，保留待处理消息并对账；不可删除失败回执。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-037 完整备份恢复与实测目标

- 阶段 / 状态 / 负责人：R3 / PLANNED / 运维 / 数据 / QA。
- 风险 / 相对规模：高 / M。
- 来源：ITER-FR-030 / ITER-AC-030；非功能：ITER-NFR-003、ITER-NFR-009、ITER-NFR-010。
- 硬依赖：ITER-DEV-035、ITER-DEV-008；外部门禁：G-RECOVERY：真实恢复实测通过，不以原脚本存在替代。
- 可并行规划组：PG-16；同阶段候选：ITER-DEV-036。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-14、SCR-15。
- 页面承接：工程/契约任务，产物为文档或支撑能力；FR 中的审计宿主不表示新增验收产品页面。相关原入口：/admin、/admin-audit。
- 允许修改：[scripts/backup-restore-verify.sh](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/scripts/backup-restore-verify.sh)；[deploy](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/deploy)；[docs/process/testing](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/docs/process/testing)；[src/main/java/org/haoemr/archive](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/src/main/java/org/haoemr/archive)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 基于原脚本补齐数据库、对象文件、审计、配置和密钥引用的恢复清单。
2. 在专用恢复环境演练，禁止现有脚本固定恢复库名称误伤其它工作。
3. 核对业务内容/版本/引用/对象哈希/审计链/权限并测量恢复点与时间。

**接口与数据影响：**后续脚本修改需要隔离目标参数及保护；本轮不执行恢复。

**验收标准**

- **ITER-TAC-037-01**：给定独立恢复环境及院方目标已定，当从指定备份恢复，则记录备份时间、恢复点、实际时长，达到确认 RPO/RTO 才通过。
- **ITER-TAC-037-02**：给定数据库与对象存储均有引用，当完成恢复后业务核验，则关键记录/哈希/对象可读性/审计/授权一致，行数相同不能单独证明完整。
- **ITER-TAC-037-03**：给定演练失败或密钥引用不可用，当执行停止策略，则不切换正式流量，不回显秘密；失败原因和恢复负责人明确。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**仅清理本演练库和副本；正式数据不覆盖，真实灾备切换单独授权。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-038 试点业务验收与放行包

- 阶段 / 状态 / 负责人：R3 / PLANNED / 院方 / 试点负责人 / QA。
- 风险 / 相对规模：高 / M。
- 来源：ITER-FR-028 / ITER-AC-028、ITER-FR-029 / ITER-AC-029、ITER-FR-030 / ITER-AC-030；非功能：ITER-NFR-003、ITER-NFR-009、ITER-NFR-010。
- 硬依赖：ITER-DEV-035、ITER-DEV-036、ITER-DEV-037；外部门禁：G-R3：选定范围证据与恢复能力齐备。
- 可并行规划组：PG-17；同阶段候选：无。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-14、SCR-15。
- 页面承接：工程/契约任务，产物为文档或支撑能力；FR 中的审计宿主不表示新增验收产品页面。相关原入口：/admin、/admin-audit。
- 允许修改：[docs/process/testing](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/docs/process/testing)；[docs/process/planning](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/docs/process/planning)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 在选定真实测试环境验证认证、签名、存储、接口和业务主链。
2. 按范围纳入医生效率或 AI 评测证据，记录未纳入模块与已知限制。
3. 形成试点放行/拒绝结论、停止条件和负责人，不直接部署。

**接口与数据影响：**仅验收文档；发现缺陷回到对应领域任务。

**验收标准**

- **ITER-TAC-038-01**：给定所选范围全部必需证据具备，当执行院方验收，则每项有环境/版本/时间/执行人和实际证据，缺项保持阻塞。
- **ITER-TAC-038-02**：给定签名或存储仍为拒绝式适配器，当执行实际签署/读取，则不能通过真实业务验收；配置检查通过不替代实际调用。
- **ITER-TAC-038-03**：给定恢复和接口演练已完成，当审查放行包，则明确问题等级、回滚能力和责任人；验收通过只允许申请部署，不代表已上线。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**不放行或缩小经确认范围；保留原已验收版本。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-039 候选本地提交与版本冻结

- 阶段 / 状态 / 负责人：交付操作 / PLANNED / 研发负责人。
- 风险 / 相对规模：中 / S。
- 来源：ITER-FR-001 / ITER-AC-001；非功能：ITER-NFR-003、ITER-NFR-009。
- 硬依赖：ITER-DEV-025；外部门禁：实施与本地提交授权；选定版本门禁。
- 可并行规划组：PG-15；同阶段候选：无。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-15。
- 页面承接：工程/契约任务，产物为文档或支撑能力；FR 中的审计宿主不表示新增验收产品页面。相关原入口：/admin-audit。
- 允许修改：.git（仅未来显式提交）；[docs/process/testing](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/docs/process/testing)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 明确本次交付选 R1、R2 或 R3，并收齐对应退出条件。
2. 逐文件核对任务范围和用户原有修改，形成实际变更清单。
3. 仅在获得实施及提交授权后创建本地提交，保存候选 SHA 与构建哈希。

**接口与数据影响：**纯版本操作；本轮用户明确先不动代码，因此不执行。

**验收标准**

- **ITER-TAC-039-01**：给定选定 R1 且 G-R1 通过，当审查候选，则只包含本期允许变更，原用户改动不被混入或回退。
- **ITER-TAC-039-02**：给定选定 R2 或 R3，当冻结版本，则附加对应 029/034 或 038 门禁；无需为了 R1 候选强等未选后续功能。
- **ITER-TAC-039-03**：给定已获本地提交授权，当提交并核对，则记录唯一候选 SHA、清晰变更范围与证据；未 push、未部署。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**后续新增纠正提交，禁止 reset 丢失用户改动。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-040 推送候选与远端检查

- 阶段 / 状态 / 负责人：交付操作 / PLANNED / 研发负责人 / CI。
- 风险 / 相对规模：中 / S。
- 来源：ITER-FR-001 / ITER-AC-001；非功能：ITER-NFR-003、ITER-NFR-009。
- 硬依赖：ITER-DEV-039；外部门禁：远端推送授权。
- 可并行规划组：PG-16；同阶段候选：无。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-15。
- 页面承接：工程/契约任务，产物为文档或支撑能力；FR 中的审计宿主不表示新增验收产品页面。相关原入口：/admin-audit。
- 允许修改：.git（仅未来授权推送）；[docs/process/testing](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/docs/process/testing)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 确认远端、目标分支和推送范围，与本地提交授权分开。
2. 获得推送授权后推送候选并等待必需 CI。
3. 把远端检查绑定候选 SHA，失败回到本地修复后重新冻结。

**接口与数据影响：**无业务接口/数据库变化。

**验收标准**

- **ITER-TAC-040-01**：给定只有本地提交授权，当准备推送，则保持未推送，不能将本地提交等同远端操作许可。
- **ITER-TAC-040-02**：给定目标远端及分支已授权，当推送候选，则仅发送审定提交，无强推或不相关历史改写。
- **ITER-TAC-040-03**：给定远端检查完成，当汇总结果，则只引用该 SHA 的结果；CI 通过不代表已迁移或部署。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**停止后续发布；需要撤回代码时用审定纠正提交，不强改共享历史。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-041 目标环境数据库迁移执行

- 阶段 / 状态 / 负责人：交付操作 / PLANNED / 数据 / 运维。
- 风险 / 相对规模：高 / M。
- 来源：ITER-FR-018 / ITER-AC-018、ITER-FR-030 / ITER-AC-030；非功能：ITER-NFR-001、ITER-NFR-002、ITER-NFR-003、ITER-NFR-006、ITER-NFR-009、ITER-NFR-010。
- 硬依赖：ITER-DEV-040、ITER-DEV-037、ITER-DEV-038；外部门禁：目标数据库迁移授权，生产仅在 G-R3 之后。
- 可并行规划组：PG-18；同阶段候选：无。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-03、SCR-14、SCR-15。
- 页面承接：工程/契约任务，产物为文档或支撑能力；FR 中的审计宿主不表示新增验收产品页面。相关原入口：/admin、/admin-audit。
- 允许修改：[deploy](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/deploy)；[scripts](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/scripts)；[docs/process/testing](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/docs/process/testing)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 单独确认目标环境、备份/恢复点、兼容窗口及迁移计划授权。
2. 核对候选 SQL 与目标 Flyway 历史，仅执行审定增量。
3. 执行结构与关键数据对账，结果单独登记；不在此任务替换应用。

**接口与数据影响：**执行已审定迁移；无迁移差异时有比对证据方可标 N/A。

**验收标准**

- **ITER-TAC-041-01**：给定生产目标未获授权或恢复未通过，当准备迁移，则保持未执行，不因推送/验收通过自动迁库。
- **ITER-TAC-041-02**：给定目标及窗口明确且备份可用，当运行审定迁移，则校验和/结构/约束正确，医院记录不丢失，审计可追踪。
- **ITER-TAC-041-03**：给定迁移或对账失败，当按恢复方案处理，则停止应用切换，按已演练前滚或恢复路径处理，不盲删新表。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**按 G-RECOVERY 和 G-MIG；恢复需保护窗口后新增事实，不能宣称一键无损。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-042 目标环境应用部署与冒烟

- 阶段 / 状态 / 负责人：交付操作 / PLANNED / 运维 / QA。
- 风险 / 相对规模：高 / M。
- 来源：ITER-FR-001 / ITER-AC-001、ITER-FR-028 / ITER-AC-028；非功能：ITER-NFR-003、ITER-NFR-009、ITER-NFR-010。
- 硬依赖：ITER-DEV-041；外部门禁：目标环境部署及流量切换授权。
- 可并行规划组：PG-19；同阶段候选：无。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-14、SCR-15。
- 页面承接：工程/契约任务，产物为文档或支撑能力；FR 中的审计宿主不表示新增验收产品页面。相关原入口：/admin、/admin-audit。
- 允许修改：[deploy](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/deploy)；[docs/process/testing](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/docs/process/testing)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 独立确认部署环境、版本、配置、流量窗口及部署授权。
2. 部署与已迁移结构兼容的原单体及原 Vue 构建，不做架构升级。
3. 执行认证、菜单、模板读取/发布权限、关键临床及接口冒烟，确认监控。

**接口与数据影响：**部署操作与数据库迁移分别记录；无新客户端平台。

**验收标准**

- **ITER-TAC-042-01**：给定推送和迁移已完成但未获部署授权，当准备切换，则保持旧应用服务，不自动切流。
- **ITER-TAC-042-02**：给定部署授权齐备，当替换目标应用并冒烟，则实际 SHA/构建哈希正确，原导航/风格及必需业务行为通过。
- **ITER-TAC-042-03**：给定关键冒烟失败，当按预案停止和回退，则停止扩大流量；应用回退兼容当前 Schema，未重复恢复已写数据。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**按已验证兼容版本切回应用；数据异常转任务 041 的对账恢复。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。

## ITER-DEV-043 上线观察与移交

- 阶段 / 状态 / 负责人：交付操作 / PLANNED / 运维 / 院方 / 试点负责人。
- 风险 / 相对规模：高 / S。
- 来源：ITER-FR-023 / ITER-AC-023、ITER-FR-028 / ITER-AC-028、ITER-FR-029 / ITER-AC-029、ITER-FR-030 / ITER-AC-030；非功能：ITER-NFR-003、ITER-NFR-008、ITER-NFR-009、ITER-NFR-010。
- 硬依赖：ITER-DEV-042、ITER-DEV-038；外部门禁：试点观察授权及停止预案已定。
- 可并行规划组：PG-20；同阶段候选：无。同组只是拓扑层级，依赖满足且修改文件不冲突才可并行。
- 输入：共用输入 + 所有硬依赖任务输出；交互编号 SCR-14、SCR-15。
- 页面承接：工程/契约任务，产物为文档或支撑能力；FR 中的审计宿主不表示新增验收产品页面。相关原入口：/admin、/admin-audit。
- 允许修改：[docs/process/testing](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/docs/process/testing)；[docs/process/planning](/Users/liuhaoxian/Downloads/我的/AI项目/openEMR2026/docs/process/planning)；对应目录下直接相关测试。范围外文件需先更新任务卡解释原因。
- 禁止项：改原架构/设计体系、增加主菜单/系统管理二级导航/新根路由、平行服务、未经授权外部写入；不得覆盖用户原有改动。

**实施动作**

1. 按已确认观察窗口跟踪错误、延迟、队列、接口对账及医生任务结果。
2. 达到停止条件时执行已授权预案并联系指定负责人。
3. 归档版本、配置引用、备份恢复、问题清单、培训与维护入口。

**接口与数据影响：**仅观察和交接；新增需求另开后续计划。

**验收标准**

- **ITER-TAC-043-01**：给定已部署且观察窗口开始，当记录指标，则指标来自实际环境，不以模拟数据替代；责任人和采样口径可查。
- **ITER-TAC-043-02**：给定发生跨机构暴露/签署错误/数据不一致或约定严重故障，当触发停止条件，则停止扩围并按预案隔离/回退/对账，保留事实证据。
- **ITER-TAC-043-03**：给定窗口结束且院方复核，当交接，则结论与未解决项透明，归档范围和联系人；不自动扩大到全院。

**验证证据：**每项验收保存实际环境、候选版本、执行动作及结果。工程决策任务提供复用/兼容/缺项记录；业务任务提供浏览器主路径及异常、真实响应、数据库版本/计数/约束与审计对应证据。具体复用命令见验收手册，不能以本原型模拟通过替代。

**安全与隐私：**读取及写入校验机构/角色/资源范围；输入、日志、导出不包含凭据和非必要患者正文。涉及真实模型、外部端点及环境操作只在对应授权和门禁满足后执行。

**回滚：**按已授权停止预案执行；不删除故障审计或诊疗事实。

**DoD 与交接：**本卡 3 项验收及通用完成定义全部满足、证据可追溯、无未关闭阻断缺陷，才可标 VERIFIED；否则按实际记录失败或缺项。输出变更清单/契约与迁移说明（适用时）/验收记录/恢复说明，交给依赖本任务的后续卡。外部门禁未满足时不得把测试候选声明为正式可用。
