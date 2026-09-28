# 现有页面内的增量交互规格

v0.2 · CREATED。原 16 个 SCR 编号保留作追溯，取消“16 个独立设计屏幕”的产品含义。本轮仅在 6 个既有管理页面展示增量，临床与 Eva 保持原界面。

## SCR-01 管理工作台内初始化检查

- 既有路由：/admin。
- 呈现：区块。不新增一级或二级导航。
- 角色：机构管理员。
- 追溯：ITER-FR-004,ITER-FR-007；ITER-AC-004,ITER-AC-007。
- 组件：ClinicalShell 原壳层；原页面 page-head / card-head / card-body / table；弹层复用 AdminActionDialog。
- 状态：保留既有加载/空/错误/权限；新增安装状态只在包导入及任务详情内显示。
- 返回：返回宿主页面并保留筛选/上下文；不跳回独立安装首页。
- 样式：按 main.ts 的现有 CSS 顺序继承，新增样式仅作用增量区块，不覆盖全局视觉令牌。
## SCR-02 组织树与原新增组织弹窗

- 既有路由：/admin-org。
- 呈现：已有页面/弹窗。不新增一级或二级导航。
- 角色：机构管理员。
- 追溯：ITER-FR-005,ITER-FR-007；ITER-AC-005,ITER-AC-007。
- 组件：ClinicalShell 原壳层；原页面 page-head / card-head / card-body / table；弹层复用 AdminActionDialog。
- 状态：保留既有加载/空/错误/权限；新增安装状态只在包导入及任务详情内显示。
- 返回：返回宿主页面并保留筛选/上下文；不跳回独立安装首页。
- 样式：按 main.ts 的现有 CSS 顺序继承，新增样式仅作用增量区块，不覆盖全局视觉令牌。

## SCR-03 初始化检查的模块就绪项

- 既有路由：/admin。
- 呈现：区块。不新增一级或二级导航。
- 角色：机构管理员。
- 追溯：ITER-FR-006,ITER-FR-018；ITER-AC-006,ITER-AC-018。
- 组件：ClinicalShell 原壳层；原页面 page-head / card-head / card-body / table；弹层复用 AdminActionDialog。
- 状态：保留既有加载/空/错误/权限；新增安装状态只在包导入及任务详情内显示。
- 返回：返回宿主页面并保留筛选/上下文；不跳回独立安装首页。
- 样式：按 main.ts 的现有 CSS 顺序继承，新增样式仅作用增量区块，不覆盖全局视觉令牌。

## SCR-04 参数开关/模块初始化

- 既有路由：/admin-parameters。
- 呈现：页内标签。不新增一级或二级导航。
- 角色：机构管理员。
- 追溯：ITER-FR-008,ITER-FR-017；ITER-AC-008,ITER-AC-017。
- 组件：ClinicalShell 原壳层；原页面 page-head / card-head / card-body / table；弹层复用 AdminActionDialog。
- 状态：保留既有加载/空/错误/权限；新增安装状态只在包导入及任务详情内显示。
- 返回：返回宿主页面并保留筛选/上下文；不跳回独立安装首页。
- 样式：按 main.ts 的现有 CSS 顺序继承，新增样式仅作用增量区块，不覆盖全局视觉令牌。

## SCR-05 模块初始化/包详情

- 既有路由：/admin-parameters。
- 呈现：管理弹窗。不新增一级或二级导航。
- 角色：机构管理员。
- 追溯：ITER-FR-009,ITER-FR-017；ITER-AC-009,ITER-AC-017。
- 组件：ClinicalShell 原壳层；原页面 page-head / card-head / card-body / table；弹层复用 AdminActionDialog。
- 状态：保留既有加载/空/错误/权限；新增安装状态只在包导入及任务详情内显示。
- 返回：返回宿主页面并保留筛选/上下文；不跳回独立安装首页。
- 样式：按 main.ts 的现有 CSS 顺序继承，新增样式仅作用增量区块，不覆盖全局视觉令牌。

## SCR-06 模块初始化/预检

- 既有路由：/admin-parameters。
- 呈现：管理弹窗。不新增一级或二级导航。
- 角色：机构管理员。
- 追溯：ITER-FR-010,ITER-FR-012；ITER-AC-010,ITER-AC-012。
- 组件：ClinicalShell 原壳层；原页面 page-head / card-head / card-body / table；弹层复用 AdminActionDialog。
- 状态：保留既有加载/空/错误/权限；新增安装状态只在包导入及任务详情内显示。
- 返回：返回宿主页面并保留筛选/上下文；不跳回独立安装首页。
- 样式：按 main.ts 的现有 CSS 顺序继承，新增样式仅作用增量区块，不覆盖全局视觉令牌。

## SCR-07 通知任务/配置安装记录/进度

- 既有路由：/admin-jobs。
- 呈现：页内标签/详情弹窗。不新增一级或二级导航。
- 角色：机构管理员。
- 追溯：ITER-FR-012,ITER-FR-013；ITER-AC-012,ITER-AC-013。
- 组件：ClinicalShell 原壳层；原页面 page-head / card-head / card-body / table；弹层复用 AdminActionDialog。
- 状态：保留既有加载/空/错误/权限；新增安装状态只在包导入及任务详情内显示。
- 返回：返回宿主页面并保留筛选/上下文；不跳回独立安装首页。
- 样式：按 main.ts 的现有 CSS 顺序继承，新增样式仅作用增量区块，不覆盖全局视觉令牌。

## SCR-08 配置安装记录/结果

- 既有路由：/admin-jobs。
- 呈现：详情弹窗。不新增一级或二级导航。
- 角色：机构管理员。
- 追溯：ITER-FR-013,ITER-FR-014,ITER-FR-015；ITER-AC-013,ITER-AC-014,ITER-AC-015。
- 组件：ClinicalShell 原壳层；原页面 page-head / card-head / card-body / table；弹层复用 AdminActionDialog。
- 状态：保留既有加载/空/错误/权限；新增安装状态只在包导入及任务详情内显示。
- 返回：返回宿主页面并保留筛选/上下文；不跳回独立安装首页。
- 样式：按 main.ts 的现有 CSS 顺序继承，新增样式仅作用增量区块，不覆盖全局视觉令牌。

## SCR-09 配置安装记录

- 既有路由：/admin-jobs。
- 呈现：页内标签。不新增一级或二级导航。
- 角色：管理员/审计人员。
- 追溯：ITER-FR-014,ITER-FR-019；ITER-AC-014,ITER-AC-019。
- 组件：ClinicalShell 原壳层；原页面 page-head / card-head / card-body / table；弹层复用 AdminActionDialog。
- 状态：保留既有加载/空/错误/权限；新增安装状态只在包导入及任务详情内显示。
- 返回：返回宿主页面并保留筛选/上下文；不跳回独立安装首页。
- 样式：按 main.ts 的现有 CSS 顺序继承，新增样式仅作用增量区块，不覆盖全局视觉令牌。

## SCR-10 模块初始化/差异处置

- 既有路由：/admin-parameters。
- 呈现：管理弹窗。不新增一级或二级导航。
- 角色：管理员/模板维护人员。
- 追溯：ITER-FR-011,ITER-FR-019；ITER-AC-011,ITER-AC-019。
- 组件：ClinicalShell 原壳层；原页面 page-head / card-head / card-body / table；弹层复用 AdminActionDialog。
- 状态：保留既有加载/空/错误/权限；新增安装状态只在包导入及任务详情内显示。
- 返回：返回宿主页面并保留筛选/上下文；不跳回独立安装首页。
- 样式：按 main.ts 的现有 CSS 顺序继承，新增样式仅作用增量区块，不覆盖全局视觉令牌。

## SCR-11 原门诊工作台空态

- 既有路由：/outpatient。
- 呈现：既有页面增量要求，不重绘。不新增一级或二级导航。
- 角色：医生。
- 追溯：ITER-FR-002,ITER-FR-016,ITER-FR-020；ITER-AC-002,ITER-AC-016,ITER-AC-020。
- 组件：ClinicalShell 原壳层；原页面 page-head / card-head / card-body / table；弹层复用 AdminActionDialog。
- 状态：保留既有加载/空/错误/权限；新增安装状态只在包导入及任务详情内显示。
- 返回：返回宿主页面并保留筛选/上下文；不跳回独立安装首页。
- 样式：按 main.ts 的现有 CSS 顺序继承，新增样式仅作用增量区块，不覆盖全局视觉令牌。

## SCR-12 原病历编辑器内嵌质控

- 既有路由：/opd-record。
- 呈现：既有页面增量要求，不重绘。不新增一级或二级导航。
- 角色：医生。
- 追溯：ITER-FR-002,ITER-FR-020,ITER-FR-021,ITER-FR-022,ITER-FR-024；ITER-AC-002,ITER-AC-020,ITER-AC-021,ITER-AC-022,ITER-AC-024。
- 组件：ClinicalShell 原壳层；原页面 page-head / card-head / card-body / table；弹层复用 AdminActionDialog。
- 状态：保留既有加载/空/错误/权限；新增安装状态只在包导入及任务详情内显示。
- 返回：返回宿主页面并保留筛选/上下文；不跳回独立安装首页。
- 样式：按 main.ts 的现有 CSS 顺序继承，新增样式仅作用增量区块，不覆盖全局视觉令牌。

## SCR-13 原 Eva 面板及临床辅助入口

- 既有路由：/ai-assistant。
- 呈现：既有面板增量要求，不重绘。不新增一级或二级导航。
- 角色：医生。
- 追溯：ITER-FR-024,ITER-FR-025,ITER-FR-026,ITER-FR-027；ITER-AC-024,ITER-AC-025,ITER-AC-026,ITER-AC-027。
- 组件：ClinicalShell 原壳层；原页面 page-head / card-head / card-body / table；弹层复用 AdminActionDialog。
- 状态：保留既有加载/空/错误/权限；新增安装状态只在包导入及任务详情内显示。
- 返回：返回宿主页面并保留筛选/上下文；不跳回独立安装首页。
- 样式：按 main.ts 的现有 CSS 顺序继承，新增样式仅作用增量区块，不覆盖全局视觉令牌。

## SCR-14 初始化检查→原认证/集成/模型入口

- 既有路由：/admin。
- 呈现：汇总链接。不新增一级或二级导航。
- 角色：管理员/试点负责人。
- 追溯：ITER-FR-006,ITER-FR-027,ITER-FR-028,ITER-FR-029,ITER-FR-030；ITER-AC-006,ITER-AC-027,ITER-AC-028,ITER-AC-029,ITER-AC-030。
- 组件：ClinicalShell 原壳层；原页面 page-head / card-head / card-body / table；弹层复用 AdminActionDialog。
- 状态：保留既有加载/空/错误/权限；新增安装状态只在包导入及任务详情内显示。
- 返回：返回宿主页面并保留筛选/上下文；不跳回独立安装首页。
- 样式：按 main.ts 的现有 CSS 顺序继承，新增样式仅作用增量区块，不覆盖全局视觉令牌。

## SCR-15 原审计查询；工程结果留文档

- 既有路由：/admin-audit。
- 呈现：既有查询/工程文档。不新增一级或二级导航。
- 角色：评审人员。
- 追溯：ITER-FR-001,ITER-FR-002,ITER-FR-003,ITER-FR-018,ITER-FR-023,ITER-FR-030；ITER-AC-001,ITER-AC-002,ITER-AC-003,ITER-AC-018,ITER-AC-023,ITER-AC-030。
- 组件：ClinicalShell 原壳层；原页面 page-head / card-head / card-body / table；弹层复用 AdminActionDialog。
- 状态：保留既有加载/空/错误/权限；新增安装状态只在包导入及任务详情内显示。
- 返回：返回宿主页面并保留筛选/上下文；不跳回独立安装首页。
- 样式：按 main.ts 的现有 CSS 顺序继承，新增样式仅作用增量区块，不覆盖全局视觉令牌。

## SCR-16 原模板列表、详情和审批发布

- 既有路由：/admin-templates。
- 呈现：既有页面。不新增一级或二级导航。
- 角色：模板维护/独立审核发布人员。
- 追溯：ITER-FR-015,ITER-FR-019；ITER-AC-015,ITER-AC-019。
- 组件：ClinicalShell 原壳层；原页面 page-head / card-head / card-body / table；弹层复用 AdminActionDialog。
- 状态：保留既有加载/空/错误/权限；新增安装状态只在包导入及任务详情内显示。
- 返回：返回宿主页面并保留筛选/上下文；不跳回独立安装首页。
- 样式：按 main.ts 的现有 CSS 顺序继承，新增样式仅作用增量区块，不覆盖全局视觉令牌。
