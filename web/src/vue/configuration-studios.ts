export type ConfigurationFieldKind = 'text' | 'textarea' | 'list' | 'number' | 'boolean' | 'select';

export interface ConfigurationFieldDefinition {
  key: string;
  label: string;
  kind: ConfigurationFieldKind;
  placeholder: string;
  defaultValue: string;
  minimum?: number;
  maximum?: number;
  options?: Array<{ label: string; value: string }>;
}

export interface ConfigurationStudioDefinition {
  routeId: string;
  configType: string;
  title: string;
  subtitle: string;
  keyPlaceholder: string;
  previewTitle: string;
  safetyNote: string;
  fields: ConfigurationFieldDefinition[];
}

const field = (
  key: string,
  label: string,
  kind: ConfigurationFieldKind,
  defaultValue: string,
  placeholder = defaultValue,
  limits: Pick<ConfigurationFieldDefinition, 'minimum' | 'maximum' | 'options'> = {},
): ConfigurationFieldDefinition => ({ key, label, kind, defaultValue, placeholder, ...limits });

export const configurationStudios: Readonly<Record<string, ConfigurationStudioDefinition>> = Object.freeze({
  workflow: {
    routeId: 'workflow', configType: 'WORKFLOW', title: '流程与状态设计器',
    subtitle: '节点、分支终态、受保护节点和超时升级统一版本化', keyPlaceholder: 'inpatient-consult-v1',
    previewTitle: '流程画布', safetyNote: '签署、审计节点受保护，无终态分支不得发布。',
    fields: [field('nodes', '节点', 'list', '发起,科室接收,专家意见,签署,审计,完成'), field('edges', '连线', 'list', '发起->科室接收,科室接收->专家意见,专家意见->签署,签署->审计,审计->完成'), field('protected_nodes', '受保护节点', 'list', '签署,审计'), field('timeout_policy', '超时升级', 'textarea', '2 小时提醒，4 小时升级到科主任')],
  },
  'form-designer': {
    routeId: 'form-designer', configType: 'FORM_TEMPLATE', title: '表单与病历模板设计器', subtitle: '字段、分组、术语映射、打印预览与历史版本', keyPlaceholder: 'opd-record-v1', previewTitle: '表单预览', safetyNote: '已发布模板不覆盖旧病历绑定的版本。',
    fields: [field('fields', '字段', 'list', '主诉,现病史,既往史,体格检查,诊断'), field('groups', '分组', 'list', '病史,检查,诊断'), field('terminology_mapping', '术语映射', 'textarea', 'diagnosis -> ICD-10; symptom -> WS 445.2'), field('print_template', '打印模板', 'text', 'A4-门诊病历-v1')],
  },
  'rule-center': {
    routeId: 'rule-center', configType: 'RULE', title: '规则、时限与提示设计器', subtitle: '区分医疗硬规则、机构规则、提醒与 AI 建议', keyPlaceholder: 'pediatric-dose-v1', previewTitle: '命中路径', safetyNote: '机构规则不能降级数据库医疗硬门。',
    fields: [field('conditions', '条件组', 'list', '年龄<14,药品类型=处方,体重已记录'), field('actions', '动作', 'list', '校验体重剂量,阻断超范围处方'), field('rule_layer', '规则层级', 'text', 'CLINICAL_HARD_GATE'), field('sample_case', '测试病例', 'textarea', '6 岁，20kg，阿莫西林克拉维酸处方')],
  },
  'scope-designer': {
    routeId: 'scope-designer', configType: 'SCOPE', title: '角色、职责与数据范围设计器', subtitle: '组织、岗位、患者关系、临时授权和职责分离', keyPlaceholder: 'cross-department-review-v1', previewTitle: '权限模拟', safetyNote: '临时授权必须到期，作者与审批人不得为同一人。',
    fields: [field('roles', '角色', 'list', '作者,审批人,跨科医生'), field('data_scopes', '数据范围', 'list', '本科就诊,授权患者,脱敏汇总'), field('separation_of_duties', '职责分离', 'textarea', '作者!=审批人；跨科医生只读'), field('temporary_grant_hours', '临时授权小时', 'number', '4', '1-24', { minimum: 1, maximum: 24 })],
  },
  'agent-compose': {
    routeId: 'agent-compose', configType: 'AGENT_COMPOSITION', title: '医助团队与能力工具编排', subtitle: '配置治理台账：运行时实际读取已发布的医助 release 与 composition，不会自动消费本页草稿或发布项', keyPlaceholder: 'clinical-summary-agent-v1', previewTitle: '医助协作关系图', safetyNote: '本页用于治理审阅，不直接改变 AI医助 EVA 运行编排；医助不直接写入临床结果。',
    fields: [field('agents', '医助团队', 'list', 'clinical-summary-agent@1'), field('skills', '医助能力', 'list', 'summarize-record@3,retrieve-evidence@2'), field('tools', '医助工具', 'list', 'record.read@2,timeline.read@1'), field('budget_tokens', '生成额度', 'number', '6000', '100-20000', { minimum: 100, maximum: 20000 }), field('stop_conditions', '停止条件', 'textarea', '缺少授权来源；超过处理上限；患者或就诊发生变化'), field('compensation', '异常处置', 'textarea', '取消未执行操作，转交人工复核并完成对账')],
  },
  'agent-context': {
    routeId: 'agent-context', configType: 'AGENT_CONTEXT', title: '医助诊疗范围策略', subtitle: '配置治理台账：AI医助 EVA 运行时以上下文租约、已发布医助 release 与 AI 工作策略为准，不会自动消费本页条目', keyPlaceholder: 'opd-context-v1', previewTitle: '诊疗范围预览', safetyNote: '本页用于治理审阅；患者或就诊发生变化时，运行中的上下文租约仍会立即失效。',
    fields: [field('data_sources', '数据源', 'list', 'DOCUMENT_VERSION,OBSERVATION,ORDER,RULE'), field('allowed_fields', '允许字段', 'list', '主诉,现病史,诊断,医嘱,结果'), field('time_window_hours', '时间窗小时', 'number', '72', '1-720', { minimum: 1, maximum: 720 }), field('redaction_policy', '脱敏策略', 'textarea', '隐藏身份证号、电话和住址；仅保留最小必要字段'), field('freshness_minutes', '新鲜度分钟', 'number', '5', '1-60', { minimum: 1, maximum: 60 })],
  },
  'agent-evals': {
    routeId: 'agent-evals', configType: 'AGENT_EVAL', title: '医助评测与发布审核', subtitle: '通过临床用例、质量指标、对抗测试和版本差异决定是否发布', keyPlaceholder: 'clinical-agent-eval-v1', previewTitle: '发布审核', safetyNote: '临床用例未达标、对抗测试失败或版本无法追溯时禁止发布。',
    fields: [field('target_agent', '目标医助编码', 'text', 'CLINICAL_ASSISTANT'), field('dataset_version', '临床用例集版本', 'text', 'clinical-ai-golden-v1'), field('case_count', '用例数', 'number', '100', '1-10000', { minimum: 1, maximum: 10000 }), field('pass_threshold', '通过标准', 'number', '0.95', '0-1', { minimum: 0, maximum: 1 }), field('measured_score', '实测分值', 'number', '0.00', '0-1', { minimum: 0, maximum: 1 }), field('release_gate', '发布门禁', 'select', 'BLOCKED', undefined, { options: [{ label: '阻断', value: 'BLOCKED' }, { label: '通过', value: 'PASSED' }] }), field('red_team_profile', '对抗测试方案', 'textarea', '越权诱导、指令注入、未经医生确认的临床操作、敏感数据泄露')],
  },
  'config-release': {
    routeId: 'config-release', configType: 'CONFIG_RELEASE', title: '配置差异、审批与灰度发布', subtitle: '受管发布台账：留存验证证据、职责分离、灰度范围、失败补偿和回退；不会自动下发配置或制品', keyPlaceholder: 'release-2026-08-v1', previewTitle: '发布记录预览', safetyNote: '本页不执行发布。作者不能批准自己，失败版本不得进入 ACTIVE；实际变更须由对应业务配置或交付流程执行。',
    fields: [field('diff_summary', '差异摘要', 'textarea', '新增门诊会诊超时升级节点'), field('validation_evidence', '验证证据', 'list', 'schema-pass,simulation-pass,security-pass'), field('rollout_scope', '灰度范围', 'list', '总院门诊,心内科'), field('rollback_plan', '回退计划', 'textarea', '恢复上一 ACTIVE 版本并保留审计证据')],
  },
  'config-upgrade': {
    routeId: 'config-upgrade', configType: 'CONFIG_UPGRADE', title: '配置包升级与冲突处理', subtitle: '受管升级台账：留存兼容检查、冲突决议、迁移预演与恢复点；不会自动执行迁移或覆盖生效配置', keyPlaceholder: 'package-upgrade-v1', previewTitle: '升级记录预览', safetyNote: '本页不执行升级。破坏性字段删除必须阻断，预演与实际 checksum 必须一致；实际迁移须走专用交付与恢复流程。',
    fields: [field('package_version', '配置包版本', 'text', '2026.08.1'), field('compatibility', '兼容结论', 'textarea', '兼容 V166；不允许删除已引用字段'), field('conflicts', '冲突决议', 'list', '保留本地科室范围,接受产品新规则'), field('recovery_point', '恢复点', 'text', 'config-checkpoint-before-2026.08.1')],
  },
  'admin-master-data': {
    routeId: 'admin-master-data', configType: 'MASTER_DATA', title: '医院主数据管理', subtitle: '药品、耗材、项目、检验检查、设备和床位使用专用数据结构与权威来源', keyPlaceholder: 'department-codes-v1', previewTitle: '主数据树', safetyNote: '已被临床事实引用的值只能停用，不得物理删除。',
    fields: [field('code_system', '编码体系', 'text', 'HAOEMR-DEPARTMENT'), field('hierarchy', '层级', 'list', '医院>院区>科室>病区'), field('effective_period', '有效期', 'text', '2026-01-01/2099-12-31'), field('import_policy', '导入策略', 'textarea', '重复编码阻断；逐项回传结果；已引用值仅停用')],
  },
  'admin-parameters': {
    routeId: 'admin-parameters', configType: 'PARAMETER', title: '系统参数与功能开关', subtitle: '带数据类型、适用范围、继承关系、依赖、风险和版本的受控配置，不允许随意覆盖', keyPlaceholder: 'clinical-ai-enabled-v1', previewTitle: '参数继承', safetyNote: '密码、密钥等敏感值只能引用受保护的环境变量或配置文件，高风险参数需双人审批。',
    fields: [field('value_type', '数据类型', 'text', 'INTEGER'), field('configured_value', '实际生效值', 'text', '15'), field('scope', '适用范围', 'text', 'ORGANIZATION'), field('inheritance', '范围继承顺序', 'textarea', 'FACILITY -> ORGANIZATION -> GLOBAL'), field('secret_reference', '敏感值引用（非敏感参数填写受控占位引用）', 'text', 'env://HAOEMR_PARAMETER_NOT_SECRET'), field('effective_at', '计划生效时间', 'text', '2026-08-25T00:00:00+08:00')],
  },
  'admin-jobs': {
    routeId: 'admin-jobs', configType: 'JOB', title: '通知调度与批量任务', subtitle: '后台任务逐项结果、重复提交保护、取消和业务对账；部分成功不伪装成全部完成', keyPlaceholder: 'notification-reconcile-v1', previewTitle: '批次执行图', safetyNote: '成功项不重复，只重试失败项，每项保留事务事件记录。',
    fields: [field('job_kind', '任务类型', 'text', 'ADMIN_GOVERNANCE_AGENT'), field('schedule', '执行时间', 'text', '0 */5 * * * *'), field('batch_size', '每批处理数量', 'number', '100', '1-10000', { minimum: 1, maximum: 10000 }), field('retry_policy', '失败重试规则', 'textarea', '只重试失败检查项；1m/5m/15m；最多 3 次'), field('reconciliation_rule', '业务对账规则', 'textarea', '已处理项必须等于成功项与失败项之和'), field('notification_channels', '结果通知渠道', 'list', '站内信,短信,邮件'), field('channel_owner', '通知责任人', 'text', '信息中心运维组')],
  },
  backup: {
    routeId: 'backup', configType: 'BACKUP', title: '备份恢复与完整性报告', subtitle: '备份台账、checksum、恢复演练、RPO/RTO 与保留', keyPlaceholder: 'synthetic-backup-v1', previewTitle: '恢复演练', safetyNote: '仅针对合成库执行，报告必须包含版本和 checksum。',
    fields: [field('repository', '备份仓库', 'text', 'file:///private/tmp/haoemr-backups'), field('retention_days', '保留天数', 'number', '30', '1-3650', { minimum: 1, maximum: 3650 }), field('rpo_minutes', 'RPO 分钟', 'number', '15', '0-1440', { minimum: 0, maximum: 1440 }), field('rto_minutes', 'RTO 分钟', 'number', '60', '1-10080', { minimum: 1, maximum: 10080 }), field('checksum_policy', 'Checksum 策略', 'text', 'SHA-256 + row-count fingerprint')],
  },
  install: {
    routeId: 'install', configType: 'INSTALL', title: '安装向导与首次健康检查', subtitle: '环境预检、分步安装、断点恢复和安全清理', keyPlaceholder: 'site-install-v1', previewTitle: '安装步骤', safetyNote: '缺 JDK/DB/OIDC 等前置必须失败关闭，重试不重复创建。',
    fields: [field('prerequisites', '环境前置', 'list', 'JDK21,PostgreSQL18,OIDC,KMS,ObjectStorage'), field('database_profile', '数据库配置', 'text', 'postgresql-production'), field('identity_profile', '身份配置', 'text', 'oidc-mfa-production'), field('resume_step', '断点步骤', 'text', 'PRECHECK')],
  },
  operations: {
    routeId: 'operations', configType: 'OPERATION', title: '生产运行、灾备与停机续运', subtitle: '服务健康、事件、维护窗、积压、恢复步骤与证据', keyPlaceholder: 'operations-policy-v1', previewTitle: '运行拓扑', safetyNote: '任何依赖失败都必须显示影响和恢复动作，不硬编码“一切正常”。',
    fields: [field('health_checks', '健康检查', 'list', 'database,outbox,identity,storage,integrations'), field('maintenance_window', '维护窗', 'text', '周日 02:00-04:00 Asia/Shanghai'), field('downtime_mode', '停机续运', 'textarea', '临床只读；本地草稿；恢复后对账'), field('recovery_steps', '恢复步骤', 'list', '隔离故障,恢复依赖,重放Outbox,业务对账,解除降级')],
  },
  'release-gates': {
    routeId: 'release-gates', configType: 'RELEASE_GATE', title: 'Release 门禁与制品发布', subtitle: '候选版本、契约、迁移、测试、安全、备份和回滚证据', keyPlaceholder: 'release-candidate-v1', previewTitle: 'GO / NO-GO', safetyNote: '缺任一 P0 门禁或 commit/制品/DB 版本不一致时必须 NO-GO。',
    fields: [field('candidate_commit', '候选 commit', 'text', 'UNCOMMITTED-LOCAL-CANDIDATE'), field('required_gates', '必需门禁', 'list', 'contracts,migrations,backend-tests,frontend-tests,browser-194,security,backup-restore'), field('artifact_checksum', '制品 checksum', 'text', 'sha256:pending-build'), field('rollback_entry', '回滚入口', 'textarea', '恢复上一候选制品，数据库只追加 forward-fix')],
  },
});

export function configurationStudio(routeId: string): ConfigurationStudioDefinition {
  const definition = configurationStudios[routeId];
  if (!definition) throw new Error(`Unknown configuration studio: ${routeId}`);
  return definition;
}
