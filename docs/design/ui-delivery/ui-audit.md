# HaoEMR UI 设计审计

## 结论

- 模式：`DESIGN`；视觉方向：A“临床可信蓝”。
- 路由事实分母：194；默认态高保真页面稿：194/194 `VERIFIED`，其中 v0.13 新增 30 张专科深页，并刷新 4 张受影响的门诊/病历/专科总览页。
- PRD/AC：138/138；原型 SCR：175；194 条路由均有需求映射、默认态截图制品和浏览器验证证据。
- 最终资产清单：3（功能图标库 1 + 生成栅格资产 2）；页面—资产关系：205；交互规格状态母版：4，不计入最终视觉资产清单。
- S003-2 结构、制品和 1440x1000 浏览器审计：`PASS`；当前可进入 S004/S005，不可跳过架构与 S006 直接进入 S008。

## 证据

| 审计项 | 结果 | 证据 |
|---|---|---|
| 最终视觉契约 | PASS | `design-system.md` + `tokens.json` |
| 全路由默认态 | 194/194 VERIFIED | `route-design-map.csv` + `screens/*.png` |
| 中文页标题与深链 | 194/194 | 每个 `#route` 的 H1 与 `route-titles.json` 精确一致；扩展路由未回落门户 |
| 唯一一级导航激活 | 194/194 | `browser-verification-v013.json` |
| 全局 AI 入口 | 194/194 | `browser-verification-v013.json` |
| 当前工作站视口横向溢出 | 0/194 | `browser-verification-v013.json` |
| 专科七层导航遮挡 | 0/70 | 两层导航底边均未越过下一层/页标题顶边 |
| 最终功能图标 | PASS | 导航、AI、录音使用 `medical-icon-sprite.svg`；按钮中麦克风 Emoji 为 0 |
| 浏览器控制台/页面错误 | 0/0 | 194 路由回归会话累计检查 |
| 生成栅格资产 | 2/2；可见槽位 11/11 | `image-generation-manifest.csv` + `asset-manifest.csv` + `page-asset-map.csv` |
| S003-1 原型全量回归 | 194/194；专科 70/70；错误 0 | `../prototype/browser-verification-v013.json` |
| 全局状态 | PASS | `states/global-states.svg` |
| 病历核心状态 | PASS | `states/record-states.svg` |
| 核心专科安全状态 | PASS | `states/specialty-safety-states.svg` |
| AI/Agent 安全状态 | PASS | `states/ai-states.svg` |
| SVG/XML 有效性 | PASS | 全部母版与品牌资产经 `xmllint --noout` |
| Token JSON 有效性 | PASS | JSON 解析通过 |
| 交付包结构 | PASS | `audit_ui_delivery.py ui-delivery` |

## 人工视觉复核

已审阅临床门户、病历编辑、妇产专科病历、妇产检查证据、中医随访交接、AI医助小南和系统管理工作台等不同页面族，结论如下：

- 病历正文保持白色纸张与有限阅读宽度，来源/质控/版本收口为窄辅助栏和独立深链，未长期挤压正文。
- 妇产等专科页保留全院统一壳层，但增加专业字段、关系对象、时间点与硬阻断，没有只做“换色换名”。
- 专科检查证据页将专业对象/版本与外部系统降级分栏；生成插图仅承载“单设备隔离、其余链路继续”的状态隐喻，不冒充诊疗事实。
- AI 建议使用独立紫色语义，与系统规则、医生事实和硬阻断明确分层。
- 系统管理使用 standard 密度，保留审批、职责分离、发布与回滚的可见性。
- 所有导航、AI、录音和安全语义最终图标均使用本地 SVG 精灵；趋势字符仅作为合成数据图示，不承担功能入口或唯一状态编码。

## 边界与后续门禁

- 194 张默认态截图均纳入同一路由、Token 和资产契约；本轮 30 张新页和 4 张受影响页面在 1440x1000 工作站视口重新生成。
- 移动床旁/Touch 为 P1，本次交付组件尺寸契约，不将 194 个桌面页面等比压缩为手机界面。
- 深色主题不进入 v1 临床发布范围；PACS 影像画布可局部深色。
- S008 实现时必须使用 `tokens.json` 和业务状态契约，不得仅照截图硬编码。
