# U01-V2 Vue 3 核心临床纵切对等测试报告

- 日期：2026-08-20
- 对应任务：`U01-V2`
- S008/S009 状态：`VERIFIED`（本地）；U01 总任务仍 `IN_PROGRESS`
- 数据边界：仅本机合成数据库与开发身份；无真实患者数据

## 1. 交付结果

- `outpatient`、`opd-record`、`record`、`record-qc`、`record-sign` 已由原生 Vue 页面接管，形成“门诊入口 → 病历编辑 → 病历中心 → 确定性质控 → 签署证据”的连续用户任务。
- 所有页面复用唯一 Vue Router、Pinia 临床上下文和 TanStack Vue Query 缓存；没有新建第二份患者上下文或手写业务契约。
- 门诊工作台与病历中心保持不同一级归属；未知路由继续 fail-closed。
- 病历保存形成不可变版本；确定性质控只读取服务端规则；AI 只生成候选，接受后仍需医生保存并重新质控。
- 签署由服务端复核内容版本、规则结果和处置意见。合成 CA 未接入时真实返回 `PENDING_CA_EVIDENCE`，页面明确显示未取得 CA 证据，不伪造有效签名。
- 签署后编辑字段为只读，保存、AI 与再次签署禁用；病历中心同步显示 `SIGNED` 和内容指纹。
- 对应 React 路由入口已从 legacy adapter 移除；lazy legacy leaf 从 13 个降至 9 个，React chunk 从 109.98KB 降至 85.56KB gzip。

## 2. 自动验证

| 门禁 | 结果 |
|---|---|
| Contract test/check | PASS，3/3，91 outputs |
| Vue/React unit | PASS，13 files / 37 tests |
| Strict typecheck | PASS，`vue-tsc -b` |
| React 新页阻断 | PASS，20 baseline / 0 unexpected |
| Production build | PASS；Vue shell 60.59KB gzip，legacy leaf 85.56KB gzip lazy chunk |
| 生产包开发身份/患者文案扫描 | PASS |
| Playwright 194 路由 | PASS，194/194 H1、唯一一级激活、无横向溢出、0 console issue |
| 未知深链 | PASS，不回落临床页、不出现患者内容 |
| 根 `scripts/verify.sh` | PASS：V1–V22 迁移/恢复、35 Java tests、100 AI eval、15 payload/12 surface、安全与追踪均通过 |

## 3. 真实浏览器临床闭环

1. 从 `#/outpatient` 进入 `#/opd-record`，读取真实租约和当前合成病历。
2. 运行确定性质控，服务端命中治疗与随访计划缺失警告，页面显示 1 项待处理问题。
3. 进入 `#/record-qc` 与 `#/record-sign`，阻断数为 0；提交医生警告处置意见后签署当前版本。
4. 服务端创建签名记录并返回 `PENDING_CA_EVIDENCE`；治理页显示 1 条签名、内容哈希和证据水印。
5. 返回 `#/opd-record`，病历为只读；返回 `#/record`，状态为 `SIGNED`。

浏览器证据：`artifacts/playwright/u01-v2-record-sign.png`。

## 4. 测试发现与处置

- 签署后警告已闭环，但首次页面文案仍显示“本次运行未发现规则问题”，与历史命中数 1 冲突。现改为“当前无未闭环规则问题 / 本次共命中 1 项，均已处置”，并把指标改名为“未闭环警告”。
- 第一次完整门禁使用了通用 `JAVA_HOME`，仓库脚本按约定拒绝并要求 `HAOEMR_JAVA_HOME`。使用专用变量重跑后全绿；代码与数据门禁没有被绕过。

## 5. 未关闭边界

- 住院、医嘱、诊断、结果、任务、版本/diff、病案 9 个既有纵切仍由 lazy React leaf 承载，进入 `U01-V3`。
- 当前开发身份、租户、患者和就诊来自 dev-synthetic 引导；正式 OIDC、组织权限、生产患者选择和 CA/KMS 不在本批伪造完成。
- 194 个路由可达不代表 194 项业务已实现；未实施路由继续显示 `NOT_AVAILABLE`。
- 真实医院发布仍受 S009/S010/S011 以及 DR-002/004/006/008/009 门禁约束。
