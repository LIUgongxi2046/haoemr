import type { SpecialtyExecutionDomain } from '../api/execution-center';

export interface SpecialtyExecutionField {
  key: string;
  label: string;
  type?: 'text' | 'number' | 'boolean' | 'datetime-local' | 'textarea';
  placeholder?: string;
  help?: string;
}

export interface SpecialtyExecutionWorkbenchDefinition {
  domain: SpecialtyExecutionDomain;
  title: string;
  subtitle: string;
  entityLabel: string;
  stages: string[];
  fields: SpecialtyExecutionField[];
  agentObjective: string;
}

const identityFields: SpecialtyExecutionField[] = [
  { key: 'patient_identifier_one', label: '患者标识一', placeholder: '门诊号 / 住院号', help: '不得使用姓名作为唯一核对依据' },
  { key: 'patient_identifier_two', label: '患者标识二', placeholder: '腕带号 / 出生日期', help: '必须与标识一来自不同标识体系' },
  { key: 'patient_verification_method', label: '双标识核对方式', placeholder: '腕带扫码 + 口述出生日期' },
];

export const specialtyExecutionWorkbenches: Record<SpecialtyExecutionDomain, SpecialtyExecutionWorkbenchDefinition> = {
  PATHOLOGY: {
    domain: 'PATHOLOGY', title: '病理标本到诊断签署', entityLabel: '病理病例',
    subtitle: '按病理申请、标本接收、取材、包埋、制片、诊断复核与签署形成可追溯闭环',
    stages: ['草稿申请', '接收待执行', '取材制片中', '诊断待复核', '复核完成'],
    fields: [...identityFields,
      { key: 'accession_number', label: '病理号', placeholder: 'BL202608310001' },
      { key: 'lab_specimen_id', label: '关联组织标本 ID', placeholder: '从病理标本队列选择的 UUID' },
      { key: 'specimen_type', label: '标本类型', placeholder: '手术切除标本 / 活检 / 细胞学' },
      { key: 'specimen_site', label: '取材部位', placeholder: '左肺上叶' },
      { key: 'fixative', label: '固定液与固定时间', placeholder: '10%中性福尔马林，固定 12 小时' },
      { key: 'grossing_summary', label: '大体取材记录', type: 'textarea' },
      { key: 'slide_evidence', label: '蜡块/切片证据', placeholder: 'A1-A4 / HE-01~04' },
    ], agentObjective: '只读核对病理申请、标本身份、取材制片和诊断证据的缺项与矛盾，不生成最终诊断，不执行签署。',
  },
  THERAPY: {
    domain: 'THERAPY', title: '治疗排程与执行闭环', entityLabel: '治疗任务',
    subtitle: '基于已签署治疗医嘱记录疗程、场次、双核对、执行结果和不良事件',
    stages: ['草稿任务', '排程待执行', '治疗执行中', '结果待复核', '疗次完成'],
    fields: [...identityFields,
      { key: 'therapy_code', label: '治疗项目编码', placeholder: 'REHAB-PT-001' },
      { key: 'order_execution_task_id', label: '关联治疗医嘱执行任务 ID', placeholder: '从治疗执行队列选择的 UUID' },
      { key: 'course_number', label: '疗程号', type: 'number', placeholder: '1' },
      { key: 'session_number', label: '本疗程第几次', type: 'number', placeholder: '1' },
      { key: 'verification_method', label: '医嘱与项目核对', placeholder: '医嘱条码 + 治疗卡双核对' },
      { key: 'treatment_parameters', label: '治疗参数', type: 'textarea', placeholder: '部位、剂量/强度、时长、体位' },
      { key: 'adverse_event', label: '不良事件与处置', type: 'textarea' },
    ], agentObjective: '只读核对治疗医嘱、排程、禁忌证、参数和既往不良事件，输出人工复核清单，不自动开始或完成治疗。',
  },
  ANESTHESIA: {
    domain: 'ANESTHESIA', title: '麻醉评估、术中记录与复苏', entityLabel: '麻醉病例',
    subtitle: '覆盖术前访视、ASA 分级、知情同意、麻醉计划、术中事件和 PACU 去向',
    stages: ['术前评估草稿', '评估就绪', '麻醉执行中', '复苏待复核', '麻醉记录完成'],
    fields: [...identityFields,
      { key: 'surgical_procedure_id', label: '关联手术申请/排程号', placeholder: '手术业务号' },
      { key: 'asa_class', label: 'ASA 分级', placeholder: 'ASA II' },
      { key: 'anesthesia_method', label: '麻醉方式', placeholder: '全身麻醉 / 椎管内麻醉' },
      { key: 'fasting_confirmed', label: '禁食禁饮确认', type: 'boolean' },
      { key: 'consent_confirmed', label: '麻醉知情同意确认', type: 'boolean' },
      { key: 'airway_plan', label: '气道评估与方案', type: 'textarea' },
      { key: 'intraoperative_monitoring', label: '术中监护与异常处置', type: 'textarea', placeholder: '生命体征趋势、关键事件及处置；完成前必填' },
      { key: 'pacu_disposition', label: '复苏评分与去向', type: 'textarea' },
    ], agentObjective: '只读核对术前评估、ASA、禁食、同意书、过敏史和术中事件，提示缺项；不得生成麻醉医嘱或确认复苏去向。',
  },
  DEVICE_MONITORING: {
    domain: 'DEVICE_MONITORING', title: '设备绑定、趋势与告警处置', entityLabel: '监护任务',
    subtitle: '记录设备身份、双标识绑定、时钟质量、监测参数、告警确认与升级责任人',
    stages: ['绑定草稿', '绑定待启用', '连续监测中', '告警/数据待复核', '监护任务完成'],
    fields: [...identityFields,
      { key: 'device_id', label: '设备资产编号', placeholder: 'ICU-MON-01' },
      { key: 'device_type', label: '设备类型', placeholder: '床旁监护仪 / 呼吸机' },
      { key: 'binding_verified', label: '患者绑定已复核', type: 'boolean' },
      { key: 'clock_offset_seconds', label: '设备时钟偏移（秒）', type: 'number', placeholder: '0' },
      { key: 'alarm_policy', label: '告警策略版本', placeholder: 'ICU-ADULT-v3' },
      { key: 'monitoring_parameters', label: '监测参数与采样频率', type: 'textarea' },
      { key: 'alarm_disposition', label: '告警确认/升级/关闭记录', type: 'textarea' },
    ], agentObjective: '只读分析设备绑定、时间同步、缺测、趋势和告警记录，只输出数据质量与处置建议，不自动关闭告警。',
  },
};
