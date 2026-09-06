# 嵌入式病历质控 B0-SAFE 实施记录

日期：2026-09-06。状态：局部实现并取得针对性验证证据；全量回归仍未通过，不是 19 项完整交付或上线许可。

## 本批范围和不变项

复用已有 `GET /documents/{id}/governance?document_version_id=...`，未修改 OpenAPI、数据库、后端质控规则或签署接口。

- `RecordQcPanel.vue`：中文严重度、级别筛选、字段定位、规则/运行证据；区分未运行、通过、警告/阻断、修改失效和读取失败。
- `record-qc.ts`：只接受精确 `sections.<field>` 且存在于当前页面的章节；核验版本和内容哈希；不猜测字符偏移。
- `use-record-qc-snapshot.ts`：独立治理证据读取，不刷新/替换正文；租约、用户、患者、就诊、版本变化以及编辑后丢弃旧异步响应；不持久化临床内容。
- 门诊页：嵌入现有右侧面板，保留加密缓存、自动保存、冲突恢复和签署动作。
- 通用编辑器：编辑区内直接呈现质控，原质控跳转按钮改为滚动到本页面板；保存与签署动作保留。
- 住院文书页：右侧展示单文书问题并定位章节，保留四级审签、签名证据和原有操作确认。

没有新增菜单。旧质控深链和现有导航暂时保留兼容，尚未实施 QC-014。没有自动 AI 检查、AI 扣分或自动修正文书。字符级高亮和正文引用快照未在本切片实现。

工作区本来存在大量更名及业务改动；本批仅编辑上述相关页面、新增组件/辅助代码/测试和计划文档。没有提交、推送、部署或回滚其他改动。

## 回归证据

| 检查 | 实际结果 | 说明 |
|---|---|---|
| 改动前 `npm test -- --reporter=dot` | 185 通过 / 4 失败 | 数据中心文案断言 1 项；壳层 `useQueryClient` mock 缺失 3 项 |
| 改动前 `npm run build` | 失败 | `pending-work.ts` 使用不存在的 `status` 字段；不在本批文件范围 |
| 新增 `record-qc.test.ts` | 15 / 15 通过 | 精确路径、脏版本失效、跨患者成功/失败晚到响应、重复读取、重试、卸载、真实组件 SSR、转义 |
| 全量 Vitest 再跑 | 210 通过 / 1 失败 | 仍为数据中心文案断言；共享工作区期间测试数量变为 211，不把其他改动导致的改善归功于本批 |
| 再跑 `npm run build` | 失败 | 仍是 `pending-work.ts` 的类型错误；此处行号因其他改动从 70 变为 93 |
| 最终 `npm run build` | 通过 | 共享工作区后续变化后，no-react、vue-tsc 与 Vite 完整构建均通过；本批未修改 `pending-work.ts` |
| 最终全量 Vitest | 217 通过 / 1 失败（218 项） | 唯一剩余失败仍为数据中心原有文案断言；不是全绿 |
| 单独 `vite build` 到隔离 output 目录 | 通过 | 1324 modules；只证明打包成功，不能代替类型门禁 |
| `test:ui:record-writes` | 34 项业务操作成功，15 条 HTTP 异常，脚本整体失败 | 新建、编辑、保存、质控、来源证据、签署、更正、版本差异和作废均返回成功；异常是跨岗位外围 worklist/credentials/assets/job-runs 的 403，没有将这些异常过滤掉 |
| `test:ui:inpatient-nested`（base URL 4177） | 3 项检查通过 / 1 项受阻 | 5 个页面 1440px/390px 无横向溢出；其中住院编辑器当时为无文书空态，脚本按条件跳过正文保存弹窗，不将这部分计为真实文书验证；药房闭环缺少已签署药品医嘱 |
| `test:ui:patient-switch` | 未通过 | 在患者队列出现前超时，未进入切换断言；不能声称真实患者切换 E2E 已通过 |
| Playwright 实际通用编辑器 | 通过 | 真实质控返回治疗计划警告；点击定位后焦点为 `record-field-treatment_plan`，路由不变；本地修改后旧定位消失且检查按钮禁用（未保存该测试输入） |
| Playwright 实际门诊页面 | 通过 | 重入页面读取已保存的真实警告；定位、级别筛选、1440/1280/390px 无横向溢出 |
| 浏览器单请求故障注入 | 通过 | 仅拦截治理证据 GET 返回 503，显示错误而非通过；保存按钮仍可用；移除拦截并重试后恢复真实发现；没有模拟正文或签署写入 |
| 真实住院单文书读写 | 通过（不包含签署） | 新建合成首次病程草稿并运行原质控接口：7 条阻断、1 条警告；缺少 assessment 章节的警告降级为不可定位，未伪造字段位置 |
| 真实住院定位及门禁 UI | 通过 | 诊断依据定位到 `inpatient-record-field-diagnostic_basis`；阻断时作者签名仍禁用；四角色进度区保留；有文书状态下 1440/390px 无横向溢出 |

测试原始文件位于仓库 `output/playwright/`：

- `embedded-record-qc-vitest.json`
- `embedded-record-qc-vitest-final.json`
- `embedded-qc-editor-locate.png`、`embedded-qc-editor-dirty.png`
- `embedded-qc-opd-1440.png`、`embedded-qc-opd-1280.png`、`embedded-qc-opd-390.png`、`embedded-qc-opd-read-error.png`
- `embedded-qc-inpatient-1440.png`、`embedded-qc-inpatient-390.png`
- `record-center-write-regression/record-center-write-regression.json`
- `inpatient-nested-crud/inpatient-nested-crud-acceptance.json`

真实写入测试只在本机项目 `dev-synthetic` 开发服务中使用合成账号运行，留下带 `E2E-20260906100726` 标识的测试文书/来源/医嘱及对应审计证据。保留这些测试数据和证据，未清库。

另外在合成住院患者既有首次病程任务上建立了测试草稿 `af7c24aa-3797-4dad-96bb-04849fd6c78f` 并执行质控；保持空白、未签署，不执行出院、审签流转或任务豁免。该任务从未开始变为处理中，这是验证所产生的开发数据变化，不是生产数据修改。

## 后续门禁

本批无数据迁移和后端写逻辑变更，局部撤回只需按本轮补丁移除面板接线和新增文件；由于页面还有用户改动，不允许整文件还原到 Git HEAD。

正式发布前仍需解决剩余全量测试失败、跨岗位外围请求权限异常，补齐患者切换 E2E 和住院四角色全链验证，再实施工作台聚合及专科接线。既不能宣称“保证所有功能不受影响”，也不能因为局部测试通过而跳过发布门禁。
