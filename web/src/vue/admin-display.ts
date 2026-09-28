const codeLabels: Readonly<Record<string, string>> = Object.freeze({
  ACTIVE: '已生效', INACTIVE: '已停用', DISABLED: '已停用', LOCKED: '已锁定',
  DRAFT: '草稿', PUBLISHED: '已发布', RETIRED: '已退役', ARCHIVED: '已归档',
  PENDING_APPROVAL: '待审批', APPROVED: '已批准', ALLOW: '允许', DENY: '拒绝',
  ORGANIZATION: '医疗机构', FACILITY: '院区', DEPARTMENT: '科室', WARD: '病区', BED: '床位', GLOBAL: '全系统',
  ROLE: '业务角色', PRIVILEGED: '高权角色', WORKGROUP: '工作组',
  CLINICIAN: '临床医师', NURSE: '护士', CLINICAL_ADMIN: '临床管理员', SYSTEM_ADMIN: '系统管理员',
  SECURITY_ADMIN: '安全管理员', SECURITY_AUDITOR: '安全审计员', AUTHORIZATION_ADMIN: '授权管理员',
  CONFIG_AUTHOR: '配置创建人', CONFIG_APPROVER: '配置审批人', RESEARCHER: '科研人员',
  PHYSICIAN: '医师', ATTENDING_PHYSICIAN: '主治医师', CHIEF_PHYSICIAN: '主任医师',
  REGISTERED_NURSE: '注册护士', NURSE_MANAGER: '护士长', PHARMACIST: '药师',
  LAB_TECHNICIAN: '检验技师', RADIOLOGIST: '影像医师', REGISTRAR: '挂号与入院登记员',
  CLINICAL_CONTEXT: '临床访问上下文', CLINICAL_DOCUMENT: '临床病历', DOCUMENT: '病历文书',
  PATIENT: '患者主档', ENCOUNTER: '就诊记录', ORDER: '医嘱', RESULT: '检验检查结果',
  WORKFORCE_PERSON: '人员主档', RESEARCH_DATASET: '科研数据集', CONFIGURATION: '系统配置', APP_USER: '系统用户',
  NURSING_RECORD: '护理记录', MEDICATION_ORDER: '药品医嘱', LAB_RESULT: '检验结果',
  IMAGING_RESULT: '影像报告', INPATIENT_ADMISSION: '入院登记',
  CONFIGURATION_PACK: '配置包安装', CONFIG_PACK_PRECHECKED: '预检配置包',
  CONFIG_PACK_INSTALL_STARTED: '开始安装配置包', CONFIG_PACK_INSTALLED: '配置包导入草稿',
  CONFIG_PACK_INSTALL_FAILED: '配置包安装失败', CONFIG_PACK_RECOVERED: '确认安装回滚',
  AUDIT_EVENT: '审计事件', CONFIG_ITEM: '配置项', PATIENT_IDENTITY: '患者身份信息',
  LEASE_ISSUE: '建立限时访问授权', READ: '查看', CREATE: '新增', UPDATE: '修改',
  WRITE: '书写', WRITE_DRAFT: '起草', SIGN: '签署', EXPORT: '导出', MANAGE: '管理',
  REVIEW: '审核', VERIFY: '核验', DISPENSE: '调剂', ARCHIVE: '归档', PUBLISH: '发布', READ_CONTENT: '查看正文',
  CARE_TEAM: '当前照护团队', DIRECT_CARE: '直接诊疗', CARE_DELIVERY: '诊疗服务',
  REGISTRATION: '登记业务', MEDICAL_RECORDS: '病案管理', AUDIT: '安全审计',
  DOCUMENT_DRAFT: '病历起草', ADMINISTRATION: '系统管理', SECONDARY_USE: '二次利用', RESEARCH: '科研',
  BOOLEAN: '是/否', STRING: '文本', NUMBER: '数值', INTEGER: '整数', OBJECT: '结构化对象', ARRAY: '列表',
  INTEGER_SECONDS: '整数（秒）', INTEGER_MINUTES: '整数（分钟）', INTEGER_YEARS: '整数（年）',
  INTEGER_MILLISECONDS: '整数（毫秒）',
  MANUAL: '手动执行', NOT_VALIDATED: '未校验', VALID: '校验通过', INVALID: '校验失败',
  MASTER_DATA: '医院主数据', PARAMETER: '系统参数', JOB: '后台任务', ROLE_CATALOG: '角色目录',
  FORM_TEMPLATE: '表单与病历模板', BUSINESS_RULE: '业务规则', AGENT_COMPOSITION: '医助团队编排',
  AGENT_CONTEXT: '医助诊疗范围', AGENT_EVAL: '医助评测', AI_ASSISTANT_POLICY: '医助工作策略',
  LOGIN_SUCCEEDED: '登录成功', LOGIN_FAILED: '登录失败', LOGOUT_SUCCEEDED: '退出成功',
  ORGANIZATION_UNIT_CREATED: '新增组织单元', ORGANIZATION_UNIT_DEACTIVATED: '停用组织单元',
  WORKFORCE_PERSON_ONBOARDED: '开通人员与授权', WORKFORCE_ACCOUNT_DISABLED: '停用人员账号',
  WORKFORCE_ACCOUNT_DEACTIVATED: '停用人员账号', WORKFORCE_ROLE_ENDED: '结束角色授权',
  DICTIONARY_ITEM_CREATED: '新增字典项', DICTIONARY_ITEM_DEACTIVATED: '停用字典项',
  AUTHORIZATION_POLICY_CREATED: '创建权限策略', AUTHORIZATION_POLICY_DRAFTED: '创建权限策略草稿',
  AUTHORIZATION_POLICY_PUBLISHED: '发布权限策略', AUTHORIZATION_SIMULATED: '模拟权限决策',
  CONFIGURATION_DEFINED: '创建配置草稿', CONFIGURATION_UPDATED: '更新配置草稿',
  CONFIGURATION_PUBLISHED: '发布配置', CONFIGURATION_ROLLED_BACK: '回退配置',
  CONTEXT_LEASE_ISSUED: '签发访问上下文', DOCUMENT_DRAFT_CREATED: '创建病历草稿',
  DOCUMENT_QUALITY_CHECKED: '执行病历质量检查', PROMPT_RELEASE_PUBLISHED: '发布提示词版本',
  DOCUMENT_TEMPLATE_CREATED: '创建文书模板', DOCUMENT_TEMPLATE_VERSION_CREATED: '创建文书模板版本',
  DOCUMENT_TEMPLATE_PUBLISHED: '发布文书模板', DOCUMENT_TEMPLATE_DEACTIVATED: '停用文书模板',
  AUTHORIZATION_POLICY: '权限策略', DICTIONARY_ITEM: '字典条目', ORGANIZATION_UNIT: '组织单元',
  CONFIGURATION_ITEM: '配置项', DOCUMENT_TEMPLATE: '文书模板', EMERGENCY_ACCESS_GRANT: '紧急访问授权',
  CONTEXT_LEASE: '访问上下文', PROMPT_RELEASE: '提示词版本',
  OPD_NOTE: '门诊病历', IPD_ADMISSION_NOTE: '住院入院记录', IPD_COURSE_NOTE: '住院病程记录',
  DISCHARGE_SUMMARY: '出院记录', NURSING_NOTE: '护理记录', SURGERY_NOTE: '手术记录',
});

export const authorizationRoleOptions = Object.freeze([
  ['CLINICIAN', '临床医师'], ['NURSE', '护士'], ['CLINICAL_ADMIN', '临床管理员'],
  ['SYSTEM_ADMIN', '系统管理员'], ['SECURITY_ADMIN', '安全管理员'], ['RESEARCHER', '科研人员'],
] as const);

export const authorizationResourceOptions = Object.freeze([
  ['CLINICAL_CONTEXT', '临床访问上下文'], ['CLINICAL_DOCUMENT', '临床病历'], ['PATIENT', '患者主档'],
  ['ENCOUNTER', '就诊记录'], ['ORDER', '医嘱'], ['RESULT', '检验检查结果'],
  ['WORKFORCE_PERSON', '人员主档'], ['RESEARCH_DATASET', '科研数据集'], ['CONFIGURATION', '系统配置'],
] as const);

export const authorizationActionOptions = Object.freeze([
  ['LEASE_ISSUE', '建立限时访问授权'], ['READ', '查看'], ['CREATE', '新增'], ['UPDATE', '修改'],
  ['WRITE_DRAFT', '起草'], ['SIGN', '签署'], ['EXPORT', '导出'], ['MANAGE', '管理'],
] as const);

export const authorizationPurposeOptions = Object.freeze([
  ['DOCUMENT_DRAFT', '病历起草'], ['DIRECT_CARE', '直接诊疗'], ['CARE_DELIVERY', '诊疗服务'],
  ['ADMINISTRATION', '系统管理'], ['SECONDARY_USE', '二次利用'], ['RESEARCH', '科研'],
] as const);

export const auditActionOptions = Object.freeze([
  ['', '全部操作'],
  ...Object.entries(codeLabels).filter(([code]) => /^(CONFIG_PACK_)|_(CREATED|DEACTIVATED|DISABLED|ENDED|PUBLISHED|DEFINED|UPDATED|ROLLED_BACK|SUCCEEDED|FAILED|SIMULATED|DRAFTED)$/.test(code)),
] as ReadonlyArray<readonly [string, string]>);

export const auditResourceOptions = Object.freeze([
  ['', '全部资源'], ['CONFIGURATION_PACK', '配置包安装'], ['AUTHORIZATION_POLICY', '权限策略'], ['WORKFORCE_PERSON', '人员主档'],
  ['ORGANIZATION_UNIT', '组织单元'], ['DICTIONARY_ITEM', '字典条目'], ['CONFIGURATION_ITEM', '配置项'],
  ['DOCUMENT_TEMPLATE', '文书模板'], ['APP_USER', '系统用户'], ['EMERGENCY_ACCESS_GRANT', '紧急访问授权'],
] as const);

export function adminCodeLabel(code: string | null | undefined, empty = '未设置'): string {
  if (!code) return empty;
  return codeLabels[code] ?? `其他类型（${code}）`;
}

export function adminValueLabel(value: unknown): string {
  if (Array.isArray(value)) return value.map(adminValueLabel).join('、');
  if (typeof value === 'boolean') return value ? '是' : '否';
  if (value == null || value === '') return '未设置';
  const text = String(value);
  if (codeLabels[text]) return codeLabels[text];
  if (text === 'Authorization Code + PKCE') return '授权码登录（含客户端安全校验）';
  if (text.includes('OIDC')) return text.replace('OIDC', '开放式统一登录（OIDC）');
  if (text.includes('MFA')) return text.replace('MFA', '多因素认证（MFA）');
  if (/^env:\/\//.test(text)) return `环境变量中的受保护配置（${text.slice(6)}）`;
  if (/^file:\/\//.test(text)) return `受保护配置文件（${text.slice(7)}）`;
  if (text === '0 */5 * * * *') return '每 5 分钟执行一次';
  if (text === '0 */15 * * * *') return '每 15 分钟执行一次';
  if (text === '0 0 2 * * *') return '每天 02:00 执行';
  if (text.includes(' -> ')) {
    const parts = text.split(' -> ');
    return parts.every((item) => Boolean(codeLabels[item]))
      ? parts.map((item) => adminCodeLabel(item)).join(' → ')
      : parts.join(' → ');
  }
  return text;
}

export function documentTypeLabel(code: string): string {
  return codeLabels[code] ?? `自定义文书（${code}）`;
}

const documentFieldLabels: Readonly<Record<string, string>> = Object.freeze({
  chief_complaint: '主诉', present_illness: '现病史', assessment: '诊疗评估', treatment_plan: '诊疗计划',
  admission_diagnosis: '入院诊断', discharge_diagnosis: '出院诊断', operation_record: '手术记录',
  nursing_assessment: '护理评估', course_note: '病程记录',
});

export function documentFieldLabel(code: string): string {
  return documentFieldLabels[code] ?? `自定义字段（${code}）`;
}
