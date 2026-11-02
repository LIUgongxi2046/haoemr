任务：EVA-20261101，恢复本地模拟演示的 EVA 调用。

状态：ACCEPTED。文档有效性：CURRENT。核验日期：2026-11-01。

用户明确允许将项目模拟就诊资料发送至 https://api.deepseek.com，用于 EVA 面试演示及完整验证。范围包括病历、医嘱、检验结果和任务信息，不包含附件及真实患者资料。模型连接和处理授权分别检查。

验收要求：

- AC-01：两个 DeepSeek 模型具有明确标识、可撤销且有效期有限的模拟演示授权。
- AC-02：授权限定 dev-synthetic、项目模拟租户、模拟就诊来源以及 DeepSeek 官方地址；排除附件。正式诊疗审批继续要求完整依据。
- AC-03：EVA 使用实际 DeepSeek 服务完成模拟就诊摘要，保留模型调用与授权证据。
- AC-04：模型配置页面明确显示授权类型、范围与有效期。

实现入口：ModelDataProcessingApprovalService、SyntheticDemoProcessingPolicy、MedicalAgentHarnessService、ModelDeploymentPage。数据库迁移 V393 增加 approval_kind，并按授权类型约束审批字段。模拟授权不填入未确认的 PIA、处理协议、供应商地域或保留期限。创建任务和后台执行均检查授权；模拟数据来源校验同时执行。

运行环境：JDK 21、PostgreSQL 18、dev-synthetic，前端 4177、后端 8089。实际患者上下文必须匹配模拟就诊；没有就诊关联的患者档案不能使用此授权。

| 验收 | 结果 | 证据 |
| --- | --- | --- |
| AC-01 | DeepSeek Flash 和 Pro 已登记 ACTIVE / SYNTHETIC_DEMO，均于北京时间 2026-11-04 23:51 到期；授权保留批准人、审计事件、撤销及版本字段 | .local-preview/eva-verification.log |
| AC-02 | 环境、租户、服务地址、实际数据库匹配及 Skill 覆盖共 7 项测试通过，0 跳过；API 拒绝附件范围、过期授权和不完整的正式审批 | .local-preview/eva-approval-tests.log；.local-preview/verify-eva.mjs |
| AC-03 | 任务 6cda09b9-b778-4230-b8f5-c0ab4480f501 为 WAITING_FOR_REVIEW，子任务 COMPLETED，failure_code 为空；1 次 LIVE_MODEL 调用，8146 tokens，具有真实 request_id 和 external_processing_approval_id | .local-preview/eva-verification.json |
| AC-04 | 浏览器 DOM 检查确认模拟授权标识、有效期及范围说明，无页面异常；vue-tsc --noEmit 通过 | .local-preview/eva-ui-verification.json；.local-preview/eva-typecheck.log |

后端已启动并应用 V393。结果作为医生审核候选保留。数据库备份为 .local-preview/interview-demo-20261101.dump。正式患者资料继续要求 CLINICAL 授权，不属于本次演示授权范围。
