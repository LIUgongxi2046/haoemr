# HaoEMR 顶栏按钮级修复与复测报告

## 1. 结论

- 本轮范围结论：**GO**。首页共享顶栏的品牌入口、医院选择、角色选择、全局搜索、AI医助小南、操作指引、通知和用户登录/账户入口均已形成真实交互；桌面和移动端按钮级专项测试 19/19 通过。
- 全局回归：194 路由 × 2 视口共 388/388 通过，0 finding；桌面和移动路由功能审计均为 194/194，0 console/HTTP/overflow failure。
- 总体生产发布建议仍沿用综合复测报告的 **NO_GO**：真实 DeepSeek 制品/硬件/阈值和像素视觉门禁等外部证据仍未补齐，本轮顶栏修复不改变这些结论。

## 2. 缺陷与修复

| 缺陷 | 修复结果 | 验证 |
|---|---|---|
| 医院、角色只是静态文本，无法操作 | 改为带选中状态的可访问菜单；移动端在账户面板提供等价选择器 | 桌面菜单和移动选择器均实点切换 |
| 顶栏搜索没有提交动作 | 改为 search form，提交后进入患者主索引并携带 `q` 查询参数 | 输入“张三”后路由和查询参数均正确 |
| 帮助按钮无行为 | 新增原生 modal 操作指引、标准 SVG 图标、视口安全布局及患者主索引/登录上下文/AI医助小南 快捷入口 | 桌面/移动均实点打开、跳转、启动 AI 和关闭 |
| 通知按钮无行为 | 新增 SVG 铃铛、分类图标、全部/未读筛选、单条/全部已读、业务跳转和空状态 | 单条未读 3→2、任务跳转、全部已读及空状态均验证 |
| 用户头像不是按钮 | 改为账户入口，提供工作上下文、登录、账号权限与锁定入口 | 实点进入 `/login-context` |
| 品牌仍是旧 `+` 标记且可能被 flex 压缩 | 接入用户提供的 1254×1254 品牌图，固定 38×38、禁止 flex shrink、保持 1:1 比例；产品名保持 `HaoEMR` | 两视口读取 natural/rendered ratio 并截图核验 |
| 核心专科工作台暂不应展示 | 从一级导航隐藏该菜单，专科直达路由归入“临床业务门户”高亮 | 专项断言菜单不存在；两视口 194 路由主导航均唯一 |
| 原测试只点击 AI，不覆盖其他顶栏控件 | 新增独立 Playwright 按钮级门禁 | 19/19 PASS |

医院与角色切换是当前演示会话的工作上下文选择；生产权限仍由服务端 ContextLease 独立复核，前端选择不会扩大授权。

## 3. 执行结果

| 层级 | 命令 | 结果 |
|---|---|---|
| Web 单元 | `npm --prefix web test` | **23/23 PASS**，8 files |
| 生产构建 | `npm --prefix web run build` | **PASS**，577 modules；主 chunk 538.99 kB，既有 P2 警告保留 |
| 顶栏按钮专项 | `npm --prefix web run test:ui:topbar` | **19/19 PASS**；1280×800 + 390×844 |
| 全量 UI | `npm --prefix web run test:ui:comprehensive` | **388/388 PASS**；0 findings |
| 路由功能桌面 | `1280×800 test:routes:browser` | **194/194 PASS**；0 failure/console/HTTP |
| 路由功能移动 | `390×844 test:routes:browser` | **194/194 PASS**；0 failure/console/HTTP |

首次扩大回归发现品牌副标题行高为 1.1，触发 194 个共享 `LINE_HEIGHT_BELOW_1_2` finding；修正为 1.25 后完整分母重跑为 388/388，未通过弱化断言制造绿灯。

## 4. 按钮覆盖清单

- 桌面：HaoEMR 品牌首页、医院菜单、角色菜单、搜索提交、AI医助小南、指引快捷动作、通知筛选/单条已读/业务跳转/清空、用户账户/登录、专科菜单隐藏、顶栏无溢出及 Logo 比例。
- 移动：品牌首页、AI医助小南、指引快捷动作、通知完整落入视口及全部状态、账户菜单内医院/角色切换、用户登录入口、专科菜单隐藏、顶栏无溢出及 Logo 比例。
- 本门禁只自动点击共享顶栏的安全操作；不会无条件点击 194 个业务页面中的创建、签署、停用或临床写入按钮，避免把有副作用的业务动作当作无状态巡检。

## 5. 证据与校验和

- Logo：`web/public/brand/haonan-medical-ai-logo.png`
- 按钮审计：`output/playwright/topbar-interactions-audit.json`
- 截图：`output/playwright/topbar-1280x800.png`、`output/playwright/topbar-390x844.png`
- 消息中心：`output/playwright/notification-center-1280x800.png`、`output/playwright/notification-center-390x844.png`
- 操作指引：`output/playwright/operation-guide-1280x800.png`、`output/playwright/operation-guide-390x844.png`
- 全量 UI：`output/playwright/comprehensive-ui-audit.json`
- 路由：`artifacts/playwright-ci/route-audit-topbar-optimized-1280x800.json`、`artifacts/playwright-ci/route-audit-topbar-optimized-390x844.json`

SHA-256：

- Logo：`15a0cd2af80ec148a1f2354a7bcaa0cb236148871249a3cbfdf2ded2bb177e1f`
- 按钮审计：`fe244b410ec2dd593391f2ce204864ff86727d5f854dae2698e8be26f637fa6c`
- 桌面截图：`b64ec62f59a9a66269974a088b5b01337fad59e9f8ee70526c55f2eba0ccc828`
- 移动截图：`e0cf804289347c07454df361d06dfc9036e930786c36bc2e660594d767ec0dc7`
- 桌面/移动消息中心：`f591e7c8254e2a944065842ca32e789790c0652b1273905d1101e42dba76df1f` / `a1958d7f15d22be21ab40a933232e2ccc397a0cd0dda49619063d3c3be21b27f`
- 桌面/移动操作指引：`1c4aca24b33f2202d3ae898934e2c621a4bd73036ae35b7f819b8a34696e2791` / `1f215d8ea1a3e2b8c95fe577a3eeaa41aff776cceda1ab65b6f2efeed6e6a48f`
- 全量 UI：`39ec24d7c7fbf20178daf274a0ab4886a41c425aa04a13fa3006fb3c4e84e537`
- 桌面/移动路由：`ed6b2276a72397c567f8a00a416f0cc220763db8dd6655d35fca52c16836e99e`
