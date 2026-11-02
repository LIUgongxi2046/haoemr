任务：AI-20261101，医学 Skill 能力更新及 DeepSeek 本地接入。

状态：ACCEPTED（能力配置、执行指令及获授权连接测试范围）。状态来源为本记录。文档有效性：CURRENT。核验日期：2026-11-01。

用户要求更新项目内全部医学 Skill，并仅授权使用 DeepSeek。沿用本地 `dev-synthetic` 环境，模拟数据用于演示。密钥使用项目的 ManagedModelSecretStore 保存，不进入源码和报告。

检索范围包括 docs/development、MedicalAgentSkills、MedicalAgentModelGateway、MedicalAgentHarnessService、SkillRegistryService 和现有测试。开发目录尚无相关逐任务记录。当前运行能力包括 58 个医学子医助工作流，治理目录包含 24 项 Skill，两者分别维护执行流程和依赖登记。

验收要求：

- AC-01：DeepSeek 模型经过实际连接测试，在本地目录显示可用。
- AC-02：全部 58 个工作流具备明确输入、执行步骤、证据要求、输出要求及人工交接条件；执行时读取更新后的能力内容。
- AC-03：目录中的 24 项 Skill 与执行能力关联，更新版本后依赖仍可解析。
- AC-04：真实项目能力资源与获授权的无患者资料 DeepSeek 调用验证有效输出；单元检查验证能力覆盖和执行指令。

当前实现入口：模型配置 /model-deployments；Skill 目录 /skill-registry；医学工作流 MedicalAgentSkills.instructions；执行证据 MedicalAgentHarnessService。

约束：保持已有权限、上下文、候选结果与人工采用流程；禁止自行生成诊断、剂量或执行完成事实。所有专业计算依赖完整输入及已发布规则。未知数据进入 gaps，风险与人工处理要求进入 warnings。

实现：MedicalAgentSkills 读取 skills-v1.json 中的任务定义和 skill-profiles-v2.json 中的专业执行要求，按 agent_code 合并为不可变的 2.0.0 能力。启动时校验每项输入、步骤、输出、交接和目录关联，拒绝重复、缺失及未注册的工作流。运行指令和 MedicalAgentHarnessService 的贡献证据使用同一份有效能力。

14 类专业要求包括临床摘要、文书、质控、结果、协作、院感、编码收费、资源安排、科研、宣教、血糖、心血管、中医与重症。24 项目录能力均有运行使用方，使用原版本发布接口更新并重新读取确认。

验证环境：commit 4c69e70630e083eea617b5e7d9d138acc2f614d0 加当前未提交变更，JDK 21、PostgreSQL 18、本地 8089 后端与 4177 前端。

| 验收 | 操作与结果 | 证据 |
| --- | --- | --- |
| AC-01 | 官方目录查询返回两个模型；后端连接测试均 READY，目录状态 ACTIVE / APPROVED | .local-preview/provider-models.json、deepseek-configured.json |
| AC-02 | 58 个工作流的版本、输入、步骤、输出与交接覆盖测试通过 | MedicalAgentSkillCoverageTest；.local-preview/skill-coverage-green.log |
| AC-03 | 24 项目录能力已发布 2.0.0，重新读取确认；全部具有运行使用方 | .local-preview/medical-skills-published.json；MedicalAgentSkillCoverageTest |
| AC-04 | 真实资源测试及 DeepSeek 无患者资料连接测试共 4 项通过，0 跳过；确认 LIVE_MODEL、真实 request_id、token 用量、空 facts 和非空 gaps | .local-preview/medical-skills-verification.log；build/test-results/test/TEST-org.haoemr.agent.MedicalAgentLiveSkillTest.xml |

后端已重新启动。数据库复核确认两个模型均为 READY / APPROVED，24 项活动 Skill 均为 2.0.0，未解析的 Skill 依赖数量为零。浏览器 DOM 检查 models、model-connection、skill-catalog 三个页面通过；页面显示两个 DeepSeek 模型与 Skill 2.0.0。证据位于 .local-preview/ai-upgrade-ui.json 和 ai-upgrade-ui.log。

项目模拟资料的 DeepSeek 处理授权及完整 EVA 验证见 [EVA 开发记录](EVA-20261101-processing-approval.md)。项目原有审批与驻留检查继续生效；真实患者资料不在模拟演示授权范围。本次证据不构成临床有效性验证。

数据库备份已更新为 .local-preview/interview-demo-20261101.dump。密钥继续由 ManagedModelSecretStore 保存在本地受限目录，备份中的模型配置仅包含密钥引用。
