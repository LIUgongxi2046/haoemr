export type SimulationWorkbenchId =
  | 'admin-auth' | 'ai-capture' | 'model-connection' | 'model-routing'
  | 'devices' | 'device-monitoring' | 'integration-connectors' | 'integration-messages'
  | 'archive-scan' | 'archive-preservation' | 'malware-scan' | 'cda-validation'
  | 'pathology-workbench' | 'anesthesia-workbench' | 'therapy-workbench'
  | 'direct-report' | 'empi';

export interface SimulationWorkbenchDefinition {
  id: SimulationWorkbenchId;
  title: string;
  subtitle: string;
  systemType: string;
  interfaceCode?: string;
  entityLabel: string;
  entityKey: string;
  defaultEntity: string;
  steps: string[];
  safeguards: string[];
  resultFocus: string[];
}

export const mockInterfaceSubmenus: ReadonlyArray<readonly [SimulationWorkbenchId | 'mock-interfaces', string]> = [
  ['mock-interfaces', '接口总览'],
  ['admin-auth', '身份认证'],
  ['ai-capture', '运行证据'],
  ['model-connection', '模型连接'],
  ['model-routing', '模型路由'],
  ['devices', '设备目录'],
  ['device-monitoring', '设备监测'],
  ['integration-connectors', '集成连接器'],
  ['integration-messages', '消息追踪'],
  ['archive-scan', '病案扫描'],
  ['archive-preservation', '长期保存'],
  ['malware-scan', '恶意扫描'],
  ['cda-validation', 'CDA 校验'],
  ['pathology-workbench', '病理系统'],
  ['anesthesia-workbench', '麻醉系统'],
  ['therapy-workbench', '治疗执行'],
  ['direct-report', '传染病直报'],
  ['empi', '患者主索引'],
];

export const simulationWorkbenches: Record<SimulationWorkbenchId, SimulationWorkbenchDefinition> = {
  'admin-auth': { id: 'admin-auth', title: '认证与 MFA 场景演练', subtitle: 'OIDC + PKCE、MFA 与会话任期的确定性身份模拟', systemType: 'IDENTITY', entityLabel: '合成主体', entityKey: 'subject', defaultEntity: '018f0000-0000-7000-8000-00000000aa04', steps: ['发起 OIDC 授权', '校验 PKCE', '执行 MFA', '建立限时会话'], safeguards: ['不接收真实口令', 'Token 不写日志', '高风险操作仍需 step-up'], resultFocus: ['authenticated', 'mfa', 'token_expires_in_seconds'] },
  'ai-capture': { id: 'ai-capture', title: '医助运行证据采集', subtitle: '读取真实医助任务、模型调用、工具链与审计事件', systemType: 'AI_PLATFORM', entityLabel: '运行 ID', entityKey: 'run_id', defaultEntity: 'latest-medical-agent-run', steps: ['读取任务运行', '汇总模型调用', '下钻工具链', '核对审计事件'], safeguards: ['只读，不生成前端模拟记录', '不展示患者明文', '证据以服务端记录为准'], resultFocus: ['run_id', 'model_request_count', 'tool_call_count'] },
  'model-connection': { id: 'model-connection', title: '模型 Provider 连接与数据边界', subtitle: '验证兼容接口、驻留边界、引用和超时', systemType: 'MODEL', entityLabel: '模型名', entityKey: 'model', defaultEntity: 'MedBase-L-2.1', steps: ['最小化输入', '调用 Provider', '校验结构与引用', '人工采纳或拒绝'], safeguards: ['仅发送允许字段', '模型输出不是临床事实', '无引用结果默认降级'], resultFocus: ['model', 'output_text', 'citations', 'behavior'] },
  'model-routing': { id: 'model-routing', title: '模型路由、主备与人工降级', subtitle: '用成功、降级、不可用场景验证故障切换', systemType: 'MODEL', entityLabel: '路由策略', entityKey: 'route_policy', defaultEntity: 'clinical-summary-primary', steps: ['评估数据级别', '选择主模型', '触发备用路由', '进入人工处理'], safeguards: ['不可用时禁止静默成功', '降级结果显式标识不完整', '不得自动执行临床动作'], resultFocus: ['behavior', '_simulation', 'citations'] },
  devices: { id: 'devices', title: '设备目录、绑定与校准', subtitle: '设备身份、患者绑定、时钟偏移和校准场景', systemType: 'DEVICE', entityLabel: '设备 ID', entityKey: 'device_id', defaultEntity: 'BEDSIDE-MONITOR-01', steps: ['登记设备身份', '双标识绑定患者', '校准设备时钟', '启用遥测'], safeguards: ['绑定前不入患者视图', '时钟偏移超限需复核', '解绑必须留痕'], resultFocus: ['device_id', 'bound_patient', 'device_clock_offset_seconds'] },
  'device-monitoring': { id: 'device-monitoring', title: '设备遥测、趋势与告警', subtitle: '演练趋势接收、质量标记、阈值告警和人工确认', systemType: 'DEVICE', entityLabel: '设备 ID', entityKey: 'device_id', defaultEntity: 'BEDSIDE-MONITOR-01', steps: ['接收遥测', '校验绑定和时钟', '计算趋势', '确认或升级告警'], safeguards: ['缺测不显示为正常', '设备值需来源标识', '告警关闭需责任人'], resultFocus: ['telemetry', 'device_clock_offset_seconds', '_simulation'] },
  'integration-connectors': { id: 'integration-connectors', title: '集成连接器目录与健康', subtitle: 'LIS/PACS/HIS/CA 标准接口、连通性与降级状态', systemType: 'INTEGRATION_', entityLabel: '合成业务键', entityKey: 'encounter_id', defaultEntity: '018f0000-0000-7000-8000-000000000101', steps: ['选择连接器', '校验契约版本', '执行健康调用', '记录适配状态'], safeguards: ['凭据仅用 secret reference', '失败不伪装成空数据', '重试必须幂等'], resultFocus: ['_simulation', 'results', 'report_status', 'settlement_status'] },
  'integration-messages': { id: 'integration-messages', title: '集成消息 Trace 与重试', subtitle: '以确定性业务键演练请求、响应、失败和重试', systemType: 'INTEGRATION_', entityLabel: 'Trace 业务键', entityKey: 'trace_key', defaultEntity: 'synthetic-trace-001', steps: ['建立 Trace', '发送标准消息', '接收确认', '幂等重试/人工处理'], safeguards: ['消息正文脱敏', '相同业务键不重复副作用', '死信只能授权重放'], resultFocus: ['_simulation', 'critical_values', 'claim_id', 'timestamp_token'] },
  'archive-scan': { id: 'archive-scan', title: '纸质病历扫描、OCR 与编目', subtitle: '扫描批次、页序、OCR 复核和完整性校验', systemType: 'ARCHIVE_SCAN', entityLabel: '扫描批次', entityKey: 'batch_id', defaultEntity: 'SCAN-SYNTHETIC-001', steps: ['建立扫描批次', '采集并校验页序', 'OCR 与目录建议', '人工确认入档'], safeguards: ['OCR 不覆盖原图', '缺页必须阻断', '确认前不得归档终态'], resultFocus: ['batch_id', 'pages', 'integrity'] },
  'archive-preservation': { id: 'archive-preservation', title: '病案长期保存与恢复验证', subtitle: '内容哈希、WORM 保留期、抽样恢复和一致性证据', systemType: 'ARCHIVE_STORAGE', entityLabel: '内容引用', entityKey: 'content_ref', defaultEntity: 'synthetic://archive/case-001', steps: ['校验封包哈希', '写入长期保存', '执行保留策略', '抽样恢复比对'], safeguards: ['哈希不一致立即阻断', '保留期不可缩短', '恢复验证只读'], resultFocus: ['storage_ref', 'content_hash', 'retention_years', 'sealed'] },
  'malware-scan': { id: 'malware-scan', title: '入档文件杀毒与隔离阻断', subtitle: 'ClamAV INSTREAM 扫描：干净放行、检出签名即隔离，不落真实业务库', systemType: 'SECURITY_AV', entityLabel: '文件引用', entityKey: 'content_ref', defaultEntity: 'synthetic://scan/eicar-sample-001', steps: ['引用待检文件', '执行 INSTREAM 扫描', '返回干净/检出证据', '检出即隔离阻断'], safeguards: ['检出文件不得入档', '签名保留为审计证据', '引擎不可用不伪装干净'], resultFocus: ['verdict', 'signature', 'engine', 'batch_verdict'] },
  'cda-validation': { id: 'cda-validation', title: 'CDA 结构与语义校验', subtitle: 'XML、根元素、枚举与必填章节的结构/语义校验，失败即隔离整改', systemType: 'DOCUMENT_CDA', entityLabel: '文书引用', entityKey: 'document_id', defaultEntity: 'CDA-SYNTHETIC-001', steps: ['引用 CDA 文书', '执行结构校验', '执行语义校验', '失败隔离整改'], safeguards: ['结构失败不得进入归档终态', '校验结论须人工复核', '校验不影响原件'], resultFocus: ['structural_valid', 'semantic_ok', 'checks', 'engine'] },
  'pathology-workbench': { id: 'pathology-workbench', title: '病理标本到诊断签署', subtitle: '从取材、制片、诊断复核到签署的状态轴', systemType: 'PATHOLOGY', entityLabel: '标本 ID', entityKey: 'specimen_id', defaultEntity: 'PATH-SYNTHETIC-001', steps: ['接收并核对标本', '取材与制片', '病理诊断', '复核签署'], safeguards: ['标本身份不一致阻断', '诊断须独立复核', '签署前保留切片证据'], resultFocus: ['specimen_id', 'stages', 'diagnosis_status'] },
  'anesthesia-workbench': { id: 'anesthesia-workbench', title: '麻醉评估、事件轴与复苏', subtitle: '术前评估、诱导、监护事件和 PACU 去向演练', systemType: 'ANESTHESIA', entityLabel: '就诊 ID', entityKey: 'encounter_id', defaultEntity: '018f0000-0000-7000-8000-000000000101', steps: ['术前评估核验', '麻醉诱导', '连续事件记录', '复苏评分与去向'], safeguards: ['事件轴只追加不覆盖', '异常体征需处置记录', '复苏去向由医生确认'], resultFocus: ['event_axis', 'monitoring', 'recovery_disposition'] },
  'therapy-workbench': { id: 'therapy-workbench', title: '治疗排程、核对与执行', subtitle: '排程、患者/医嘱双核对、执行与不良事件闭环', systemType: 'THERAPY', entityLabel: '治疗任务', entityKey: 'therapy_id', defaultEntity: 'THER-SYNTHETIC-001', steps: ['读取治疗排程', '患者与医嘱核对', '双人确认执行', '记录结果/不良事件'], safeguards: ['核对失败禁止执行', '高风险治疗双签', '不良事件必须升级'], resultFocus: ['therapy_id', 'verification', 'status', 'adverse_event'] },
  'direct-report': { id: 'direct-report', title: '国家传染病/院感直报网关', subtitle: '报告卡号、回执、2/24 小时时限、更正与失败重放', systemType: 'REPORT_GATEWAY', interfaceCode: 'DIRECT_REPORT_GATEWAY', entityLabel: '上报事件', entityKey: 'event_id', defaultEntity: 'EVT-DR-SYNTHETIC-001', steps: ['采集直报要素', '提交报告卡', '接收回执', '更正或幂等重试'], safeguards: ['回执不伪造国家平台已接收', 'PENDING_RETRY 保留事件', '更正必须关联原报告卡'], resultFocus: ['report_card_no', 'receipt_no', 'external_report_state', 'acknowledgement_state'] },
  empi: { id: 'empi', title: '患者主索引 EMPI 查询', subtitle: '登记号/人口学查询候选患者、匹配度与疑似重复', systemType: 'EMPI', interfaceCode: 'EMPI_PATIENT_LOOKUP', entityLabel: '患者登记号', entityKey: 'person_code', defaultEntity: 'P-SYNTHETIC-10001', steps: ['提交登记要素', '返回候选排序', '标记疑似重复', '人工确认不自动合并'], safeguards: ['算法分不直接合并患者', 'PENDING_VERIFICATION 需人工核验', '查询不替代本院授权'], resultFocus: ['candidates', 'best_match', 'possible_duplicate', 'identity_status'] },
};

export function simulationWorkbench(id: SimulationWorkbenchId) {
  return simulationWorkbenches[id];
}
