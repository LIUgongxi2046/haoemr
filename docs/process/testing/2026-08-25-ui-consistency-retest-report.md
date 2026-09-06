# HaoEMR 全功能菜单 UI 一致性复核报告

## 结论

本轮以路由契约中的全部 194 个功能菜单为固定分母，不以门诊工作台示例为修复边界。页面级“领域 / 流程”眉题已从标准页头统一移除，页头与顶部二级导航之间建立统一留白，桌面与移动视口的标题、页头操作和交互尺寸已完成全量复核。

最终结果：194/194 个菜单、388/388 个双视口页面通过，P0=0、P1=0、P2=0。

## 整改内容

1. 标准 `page-heading` 与兼容 `page-head` 两类页头统一隐藏重复的页面级眉题；卡片内部用于质控、证据、任务和状态分组的语义标签继续保留。
2. 所有带 `data-page-root` 的页面在二级导航下统一增加 16px 桌面留白和 12px 紧凑视口留白，页头与后续内容保持 18px 节奏。
3. 临床业务门户补齐标准页面根容器，使其纳入同一套留白、标题和自动化审计规则。
4. 统一任务中心的“门诊 / 住院”切换按钮最小高度由 32px 调整为 34px，与页头操作的最低交互尺寸一致。
5. 新增可复用的全路由一致性门禁，自动检查页面根节点、H1、重复页头眉题、顶部留白和页头按钮尺寸。
6. 复测发现门诊、预约挂号和急诊共用的合成候诊队列存在契约错误：3 条已报到预约缺少就诊 ID。已补齐对应急诊就诊上下文，并增加后端回归测试，避免 UI 因 `CONTRACT_MISMATCH` 落入错误页。
7. 数据审计器已区分“整页无数据”和“复杂页面中的局部子清单为空”；合法局部空态继续保留说明和下一步动作，不再误报为整页缺陷。

## 自动化结果

| 门禁 | 结果 | 证据 |
| --- | ---: | --- |
| UI 一致性双视口 | 194 路由 / 388 视口 / P0=P1=P2=0 | `output/playwright/ui-consistency-audit.json` |
| 综合 UI 双视口 | 194 路由 / 388 视口 / 0 发现 | `output/playwright/comprehensive-ui-audit.json` |
| 全菜单数据与交互 | 194/194 有数据，194/194 有可用交互，0 发现 | `output/playwright/page-data-function-audit.json` |
| 门诊复杂数据 | 5/5 | `output/playwright/outpatient-data-function-audit.json` |
| 按钮级清单 | 2346/2346 有可访问名称，140 次安全点击，0 发现 | `output/playwright/button-level-coverage.json` |
| 顶部交互 | 19/19 | `output/playwright/topbar-interactions-audit.json` |
| Web 单元测试 | 11 文件 / 42 测试通过 | `npm test` |
| Web 类型检查与生产构建 | 通过 | `npm run build` |
| 预约候诊专项后端测试 | 通过 | `AppointmentSchedulingApiTest` |
| 后端完整测试 | 615/615 通过 | `scripts/with-java21.sh ./gradlew test` |

## 视觉证据

- `output/playwright/outpatient-workspace-1440x1000.png`：门诊工作台页头无重复蓝色眉题，预约挂号、刷新快照、进入病历与标题保持统一顶部留白和基线。
- `output/playwright/opd-diagnosis-1440x1000.png`、`opd-orders-1440x1000.png`、`opd-results-1440x1000.png`、`opd-consult-1440x1000.png`：门诊二级工作区复杂数据与筛选操作正常。

## 边界

- 页面级眉题由统一样式规则从视觉与可访问树中移除，源组件仍可保留领域文案，便于后续结构迁移和审计。
- 急诊交接页存在一个合法的局部空子清单；整页已有 39 条数据，并提供新增下一步，因此不属于“功能菜单无测试数据”。
- 本轮只修改本地开发合成环境与前端一致性规则，未部署或迁移生产数据。
