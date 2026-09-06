# HaoEMR 50 疾病病例与患者切换复测报告

- 日期：2026-08-25
- 测试模式：S009 `PLAN + IMPLEMENT + EXECUTE + REPORT`
- 数据属性：全部为完全合成测试数据，不对应任何真实患者

## 交付结果

1. 建立 50 个不同主诊断的完整合成病例：门诊 20 例、急诊 15 例、住院 15 例。
2. 每例包含人口学信息、主诉、现病史、既往史、过敏史、体格检查、生命体征、主诊断、证据、诊疗计划、医嘱、检查/检验结果。
3. 门诊病例关联预约与候诊队列；急诊病例关联分诊、护理记录与留观；住院病例关联床位、入院、占床、入院记录与首次病程。
4. 导入使用稳定 UUID 与 upsert，可重复执行；应用以 `dev-synthetic` 模式启动时自动校验并补齐。
5. 修复患者切换的两层授权链：已到诊患者可签发上下文租约；已到诊/暂停/已结束就诊可读取诊断、医嘱和结果，写操作仍只允许进行中就诊。

## 数据库验收

| 验收项 | 实测结果 |
|---|---:|
| `SYNTHETIC-50` 就诊 | 50 |
| 不同诊断编码 | 50 |
| 门诊 / 急诊 / 住院 | 20 / 15 / 15 |
| 临床文书 | 65 |
| 医嘱 | 50 |
| 检查/检验结果 | 50 |
| 非 `M/F` 合成性别编码 | 0 |

## 自动化复测

| 范围 | 结果 |
|---|---|
| 50 病例 JSON 结构、数量、唯一性与临床详细度 | PASS |
| PostgreSQL 导入完整性 | PASS |
| ARRIVED 患者租约 + 诊断/医嘱/结果读取 | PASS |
| Playwright 连续切换 #101–#106 | 6/6 PASS，0 个失败 API |
| 前端 Vitest | 12 个文件、44 项测试全部 PASS |
| 前端生产构建 | PASS |
| 后端全量测试首轮 | 621 项中 619 PASS；2 项合成数据兼容假设已修正 |
| 全量首轮剩余 2 项定向回归 | 2/2 PASS |
| 服务恢复后患者切换复测 | 6/6 PASS，0 个失败 API |
| 最终服务健康 | 前端 HTTP 200；后端 HTTP 200 / `READY` |

## 可复现证据

- 病例目录：`samples/data/synthetic-50-disease-cases-v1.json`
- 数据库导入测试：`SyntheticDiseaseCaseImportTest`
- 患者切换 API 回归：`ContextLeaseApiTest`
- 浏览器结果：`output/playwright/patient-switching-audit.json`
- 浏览器截图：`output/playwright/patient-switching-1440x1000.png`
