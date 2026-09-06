<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref, watch } from 'vue';
import { RouterLink } from 'vue-router';
import type { ConfigurationItemWire, ConfigurationLifecycleRequestWire } from '../../generated/contracts';
import {
  authorizeConfigurationScope, defineConfiguration, evaluateConfigurationRules, issueConfigurationLease,
  listConfigurationRuntimeExecutions, listConfigurations, startConfigurationWorkflow,
  transitionConfiguration, transitionConfigurationWorkflow, updateConfiguration,
  validateConfigurationForm, type ConfigurationRuntimeExecution,
} from '../../api/config';
import { toClinicalIssue } from '../clinical-error';
import { buildWorkflowLayout, type WorkflowEdgeRecord, type WorkflowNodeRecord } from '../workflow-layout';
import BusinessActionDialog from './BusinessActionDialog.vue';

type Mode = 'workflow' | 'form' | 'rule' | 'scope';
type Row = Record<string, any>;
type DialogKind = null | 'config-create' | 'config-edit' | 'config-delete' | 'row-edit' | 'row-delete';

const props = defineProps<{ mode: Mode }>();
const definitions = {
  workflow: { type: 'WORKFLOW', eyebrow: '业务配置 / 流程编排', title: '流程与状态设计器', subtitle: '配置节点、分支、角色、时限、升级、退回与补偿，并预览草稿结构与展示顺序。', key: 'inpatient-consult-v1', name: '住院跨科会诊闭环流程' },
  form: { type: 'FORM_TEMPLATE', eyebrow: '业务配置 / 文档结构', title: '表单与病历模板设计器', subtitle: '设计字段、分组、布局、计算、可见性、签名与术语映射，并保留历史版本。', key: 'opd-record-v1', name: '门诊结构化病历模板' },
  rule: { type: 'RULE', eyebrow: '业务配置 / 规则治理', title: '规则、时限与提示设计器', subtitle: '按平台硬门、机构规则、提醒与 AI 建议分层，支持配置校验与草稿结构预演。', key: 'clinical-safety-rules-v1', name: '临床安全与时限规则集' },
  scope: { type: 'SCOPE', eyebrow: '业务配置 / 权限模型', title: '角色、职责与数据范围设计器', subtitle: '组合组织、科室、患者关系、班次、资源动作、职责分离和临时授权。', key: 'clinical-scope-v1', name: '临床角色与数据范围策略' },
} as const;
const definition = computed(() => definitions[props.mode]);
const deepModule = computed(() => ({ workflow: 'workflow', form: 'form-designer', rule: 'rule-center', scope: 'scope-designer' } as const)[props.mode]);
const leaseQuery = useQuery({ queryKey: ['business-config', 'lease'], queryFn: issueConfigurationLease, retry: false, staleTime: 300000, gcTime: 0 });
const itemsQuery = useQuery({ queryKey: computed(() => ['business-config', definition.value.type]), queryFn: () => listConfigurations(leaseQuery.data.value!, definition.value.type), enabled: () => Boolean(leaseQuery.data.value), retry: false });
const items = computed(() => itemsQuery.data.value ?? []);
const selected = ref<ConfigurationItemWire | null>(null);
const payload = ref<Record<string, any>>({});
const tab = ref<'design' | 'validate' | 'simulate' | 'runtime' | 'versions'>('design');
const busy = ref('');
const notice = ref('');
const dialogKind = ref<DialogKind>(null);
const rowKey = ref('');
const rowIndex = ref(-1);
const rowDraft = ref<Row>({});
const selectedWorkflowNodeId = ref('');
const workflowZoom = ref(80);
const workflowTableMode = ref(false);
const runtimeExecution = ref<ConfigurationRuntimeExecution | null>(null);
const runtimeHistory = ref<ConfigurationRuntimeExecution[]>([]);
const runtimeFactsText = ref('{}');
const runtimeEventCode = ref('');
const configDraft = reactive({ key: '', name: '', description: '' });
const issue = computed(() => (leaseQuery.error.value ?? itemsQuery.error.value) ? toClinicalIssue(leaseQuery.error.value ?? itemsQuery.error.value) : null);
const rows = (key: string): Row[] => Array.isArray(payload.value[key]) ? payload.value[key] : [];
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T;
const editable = computed(() => selected.value?.status === 'DRAFT');

function defaults(mode: Mode): Record<string, any> {
  const china = { schema_version: 3, china_compliance: { profile: 'CN_MEDICAL_PRODUCTION_2026', data_element_standard: 'WS/T 363.1-2023', electronic_record_dataset: 'WS 445.1-2014~WS 445.17-2014', diagnosis_code_system: '国家临床版 ICD-10（年度受控版本）', procedure_code_system: '国家临床版 ICD-9-CM-3（年度受控版本）', signature_policy: '可靠电子签名 + CA 证书 + 可信时间戳 + 签名验真', retention_policy: '门急诊病历不少于15年；住院病历不少于30年；归档后更正留痕', minimum_necessary: true, effective_from: '2026-09-01T00:00:00+08:00', review_due: '2027-03-01T00:00:00+08:00' } };
  if (mode === 'workflow') return { ...china, description: '覆盖申请、接收、意见、签署、审计、终态、退回与超时升级。', nodes: [
    { id: 'start', name: '发起申请', type: 'START', owner: '经治医生', minutes: 15 }, { id: 'receive', name: '科室接收', type: 'TASK', owner: '目标科室', minutes: 30 },
    { id: 'opinion', name: '专家意见', type: 'TASK', owner: '会诊专家', minutes: 120 }, { id: 'sign', name: '数字签署', type: 'SIGN', owner: '会诊专家', minutes: 20, protected: true },
    { id: 'audit', name: '审计留痕', type: 'AUDIT', owner: '系统', minutes: 1, protected: true }, { id: 'complete', name: '完成', type: 'END', owner: '系统', minutes: 1, terminal: true, protected: true },
  ], edges: [
    { from: 'start', to: 'receive', condition: '申请已提交', event_code: 'SUBMIT', guard: { fact_path: 'events.start.completed', operator: 'EQ', expected: true } }, { from: 'receive', to: 'opinion', condition: '科室接收', event_code: 'ACCEPT', guard: { fact_path: 'events.receive.completed', operator: 'EQ', expected: true } }, { from: 'opinion', to: 'sign', condition: '意见完成', event_code: 'COMPLETE_OPINION', guard: { fact_path: 'events.opinion.completed', operator: 'EQ', expected: true } },
    { from: 'sign', to: 'audit', condition: '签名有效', event_code: 'SIGN', guard: { fact_path: 'events.sign.completed', operator: 'EQ', expected: true } }, { from: 'audit', to: 'complete', condition: '审计成功', event_code: 'AUDIT_PASS', guard: { fact_path: 'events.audit.completed', operator: 'EQ', expected: true } }, { from: 'receive', to: 'start', condition: '资料不全', event_code: 'RETURN', guard: { fact_path: 'events.receive.returned', operator: 'EQ', expected: true }, compensation: true },
  ], protected_nodes: ['sign', 'audit', 'complete'], timeout_policy: '30 分钟提醒，120 分钟升级科主任，240 分钟升级医务处', synthetic_case: { case_id: 'SYN-CONSULT-20260826-01', patient: '赵明远（合成）' } };
  if (mode === 'form') return { ...china, description: '结构化门诊病历，支持条件、计算、签署、打印和术语映射。', groups: [{ id: 'history', name: '病史采集', columns: 2 }, { id: 'assessment', name: '评估与计划', columns: 2 }], fields: [
    { id: 'chief_complaint', label: '主诉', type: 'TEXTAREA', group: 'history', required: true, terminology: 'WS/T-363-2023+SNOMED-MAPPING' }, { id: 'pain_score', label: '疼痛评分', type: 'NUMBER', group: 'history', required: true, validation: '0 <= value <= 10' },
    { id: 'risk_level', label: '胸痛风险分层', type: 'CALCULATED', group: 'assessment', required: true, protected: true, calculation: 'pain_score >= 7 ? HIGH : MEDIUM' }, { id: 'diagnosis', label: '诊断', type: 'CODE', group: 'assessment', required: true, protected: true, terminology: 'ICD-10-NATIONAL-CLINICAL-2026' },
    { id: 'signature', label: '医生签名', type: 'SIGNATURE', group: 'assessment', required: true, protected: true },
  ], terminology_mapping: [{ field: 'diagnosis', system: 'ICD-10-NATIONAL-CLINICAL-2026' }, { field: 'chief_complaint', system: 'WS/T-363-2023+SNOMED-MAPPING' }], print_template: 'A4-门诊病历-v3', sample_values: { chief_complaint: '胸痛 2 小时', pain_score: 8, diagnosis: 'I20.0 不稳定型心绞痛', signature: 'CA-SIGNATURE-REFERENCE' } };
  if (mode === 'rule') return { ...china, description: '平台硬门、机构规则、提醒和 AI 建议分层治理。', rule_layer: 'MIXED', rules: [
    { id: 'allergy-block', name: '严重过敏处方阻断', layer: 'PLATFORM_HARD', priority: 1000, condition: '严重过敏且成分命中', fact_path: 'allergy-block', operator: 'EQ', expected: true, action: '阻断处方并要求替代药', action_code: 'BLOCK_AND_CREATE_REVIEW_TASK', evidence: '国家药品不良反应监测规范', evidence_meta: { authority: '国家卫生健康委员会/国家药监局', version: '2026受控版', review_due: '2027-03-01' }, exception: '禁止例外', enabled: true },
    { id: 'pediatric-dose', name: '儿科体重剂量校验', layer: 'INSTITUTION_HARD', priority: 800, condition: '年龄<14 且已录入体重', fact_path: 'patient.age_years', operator: 'LT', expected: 14, action: '超出 mg/kg 范围时阻断', action_code: 'BLOCK_AND_REQUIRE_PHARMACIST_REVIEW', evidence: '院内儿科用药目录 2026.2', evidence_meta: { authority: '医院药事管理与药物治疗学委员会', version: '2026.2', review_due: '2027-02-01' }, human_approval_required: true, exception: '药师与上级医师双签', enabled: true },
    { id: 'consult-timeout', name: '会诊超时升级', layer: 'REMINDER', priority: 500, condition: '会诊等待>=120分钟', fact_path: 'task.wait_minutes', operator: 'GTE', expected: 120, action: '提醒科主任并升级任务', action_code: 'ESCALATE_CONSULT_TASK', evidence: '医疗质量安全核心制度要点', evidence_meta: { authority: '国家卫生健康委员会/医院医务处', version: '2026受控版', review_due: '2027-03-01' }, human_approval_required: true, exception: '急救处理中可延后并留痕', enabled: true },
    { id: 'ai-summary', name: 'AI 病情摘要建议', layer: 'AI_ADVICE', priority: 100, condition: '资料完整度>=80%', fact_path: 'document.completeness', operator: 'GTE', expected: 80, action: '生成带来源的摘要候选', action_code: 'CREATE_AI_DRAFT_FOR_HUMAN_REVIEW', evidence: 'clinical-ai-golden-v1', evidence_meta: { authority: '医院人工智能治理委员会', version: 'golden-v1', review_due: '2027-03-01' }, human_approval_required: true, exception: '仅供人工确认', enabled: true },
  ], conditions: ['过敏命中', '年龄与体重', '会诊等待时长'], actions: ['阻断', '升级', '提醒', '建议'], sample_case: { case_id: 'SYN-RULE-20260826-01', age: 6, weight_kg: 20, allergy: '青霉素严重过敏', order: '阿莫西林克拉维酸钾' } };
  return { ...china, description: '按角色、科室、患者关系、班次与资源状态计算最终权限。', roles: ['经治医生', '会诊医生', '护士长', '病案管理员'], data_scopes: ['本科患者', '会诊授权患者', '值班期间', '脱敏汇总'], permissions: [
    { role: '经治医生', resource: '病历草稿', action: '读写', scope: '本科患者', role_code: 'ROLE-ATTENDING', resource_code: 'RESOURCE-RECORD-DRAFT', action_code: 'ACTION-READ-WRITE', scope_code: 'SCOPE-CARE-RELATIONSHIP', relationship_required: true, shift_required: true, approval_required: false, effect: 'ALLOW', temporary_hours: 0, sod: '签署人不能批准本人更正' },
    { role: '会诊医生', resource: '病历全文', action: '只读', scope: '会诊授权患者', role_code: 'ROLE-CONSULTANT', resource_code: 'RESOURCE-MEDICAL-RECORD', action_code: 'ACTION-READ', scope_code: 'SCOPE-CONSULT-AUTHORIZED', relationship_required: true, shift_required: true, approval_required: false, effect: 'ALLOW', temporary_hours: 4, sod: '禁止导出' },
    { role: '会诊医生', resource: '批量导出', action: '导出', scope: '全部患者', role_code: 'ROLE-CONSULTANT', resource_code: 'RESOURCE-BULK-EXPORT', action_code: 'ACTION-EXPORT', scope_code: 'SCOPE-ALL-PATIENTS', relationship_required: false, shift_required: false, approval_required: true, effect: 'DENY', temporary_hours: 0, sod: '保护性拒绝优先' },
    { role: '护士长', resource: '护理记录', action: '审核', scope: '本病区', role_code: 'ROLE-NURSE-MANAGER', resource_code: 'RESOURCE-NURSING-RECORD', action_code: 'ACTION-REVIEW', scope_code: 'SCOPE-ASSIGNED-WARD', relationship_required: true, shift_required: true, approval_required: false, effect: 'ALLOW', temporary_hours: 0, sod: '作者与审核人分离' },
  ], separation_of_duties: '作者!=审批人；授权申请人!=批准人；紧急访问须事后复核', temporary_grant_hours: 4, deny_overrides_allow: true, minimum_necessary: true, simulation: { role: '会诊医生', resource: '病历全文', action: '只读', scope: '会诊授权患者', expected: 'ALLOW' } };
}

function selectItem(item: ConfigurationItemWire) {
  selected.value = item;
  const stored = clone(item.payload) as Record<string, any>;
  const legacy = Number(stored.schema_version ?? 1) < 3
    || (props.mode === 'workflow' && typeof stored.nodes?.[0] === 'string')
    || (props.mode === 'form' && typeof stored.fields?.[0] === 'string')
    || (props.mode === 'rule' && !Array.isArray(stored.rules))
    || (props.mode === 'scope' && !Array.isArray(stored.permissions));
  payload.value = legacy ? { ...defaults(props.mode), description: stored.description ?? defaults(props.mode).description, migrated_from_schema: stored.schema_version ?? 1 } : stored;
  tab.value = 'design';
  notice.value = legacy ? '已将旧版配置迁移为中国医疗生产 Schema v3 草稿；保存、校验并重新审批后才能进入运行时。' : '';
  runtimeExecution.value = null;
  runtimeHistory.value = [];
  runtimeFactsText.value = JSON.stringify(runtimeFacts(), null, 2);
}

watch(items, (value) => {
  if (!value.length) { selected.value = null; payload.value = {}; return; }
  const current = value.find(item => item.config_id === selected.value?.config_id) ?? value[0];
  if (!selected.value || current.config_id !== selected.value.config_id) selectItem(current);
}, { immediate: true });
watch(() => props.mode, () => { selected.value = null; payload.value = {}; tab.value = 'design'; dialogKind.value = null; });

const workflowLayout = computed(() => buildWorkflowLayout(
  rows('nodes') as WorkflowNodeRecord[],
  rows('edges') as WorkflowEdgeRecord[],
));
const selectedWorkflowNode = computed(() => rows('nodes').find(node => String(node.id) === selectedWorkflowNodeId.value) ?? rows('nodes')[0] ?? null);
const selectedWorkflowNodeIndex = computed(() => selectedWorkflowNode.value ? rows('nodes').indexOf(selectedWorkflowNode.value) : -1);
watch(() => rows('nodes').map(node => String(node.id)).join('|'), () => {
  if (!rows('nodes').some(node => String(node.id) === selectedWorkflowNodeId.value)) selectedWorkflowNodeId.value = String(rows('nodes')[0]?.id ?? '');
}, { immediate: true });

function changeWorkflowZoom(delta: number) {
  workflowZoom.value = Math.min(125, Math.max(55, workflowZoom.value + delta));
}

function resetWorkflowLayout() {
  workflowZoom.value = 80;
  selectedWorkflowNodeId.value = String(rows('nodes')[0]?.id ?? '');
  notice.value = '已根据当前节点与迁移关系重新计算流程图布局。';
}

function assignPath(target: Record<string, unknown>, path: string, value: unknown) {
  const parts = path.split('.').filter(Boolean);
  let cursor = target;
  parts.forEach((part, index) => {
    if (index === parts.length - 1) { cursor[part] = value; return; }
    const next = cursor[part];
    cursor[part] = next && typeof next === 'object' && !Array.isArray(next) ? next : {};
    cursor = cursor[part] as Record<string, unknown>;
  });
}

function runtimeFacts(): Record<string, unknown> {
  if (props.mode === 'form') return clone(payload.value.sample_values ?? {});
  if (props.mode === 'rule') {
    const rule = rows('rules').find(item => item.enabled !== false);
    if (!rule) return {};
    const facts: Record<string, unknown> = {};
    assignPath(facts, String(rule.fact_path ?? rule.id), rule.expected ?? true);
    return facts;
  }
  if (props.mode === 'scope') {
    const permission = rows('permissions').find(item => item.effect === 'ALLOW') ?? rows('permissions')[0];
    return permission ? { role: permission.role, resource: permission.resource, action: permission.action, scope: permission.scope, patient_relationship_verified: true, active_shift_verified: true } : {};
  }
  const currentNode = runtimeExecution.value?.current_node ?? rows('nodes').find(item => item.type === 'START')?.id;
  const event = rows('edges').find(item => item.from === currentNode);
  const facts: Record<string, unknown> = {};
  if (event?.guard?.fact_path) assignPath(facts, String(event.guard.fact_path), event.guard.expected ?? true);
  return facts;
}

function resetRuntimeFacts() {
  runtimeFactsText.value = JSON.stringify(runtimeFacts(), null, 2);
  const events = runtimeExecution.value?.output_payload?.available_events;
  runtimeEventCode.value = Array.isArray(events) ? String((events[0] as Record<string, unknown> | undefined)?.event_code ?? '') : '';
}

async function refreshRuntimeHistory() {
  const lease = leaseQuery.data.value;
  if (!lease || !selected.value) return;
  try { runtimeHistory.value = await listConfigurationRuntimeExecutions(lease, definition.value.type, selected.value.config_key); }
  catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
}

async function executeRuntime() {
  const lease = leaseQuery.data.value;
  if (!lease || !selected.value || busy.value) return;
  if (selected.value.status !== 'ACTIVE') { notice.value = '只有完成校验、独立审批并发布的 ACTIVE 配置才能进入真实运行时。'; return; }
  let facts: Record<string, unknown>;
  try {
    const parsed = JSON.parse(runtimeFactsText.value || '{}');
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('facts must be an object');
    facts = parsed as Record<string, unknown>;
  } catch { notice.value = '运行事实必须是有效 JSON 对象。'; return; }
  busy.value = 'runtime'; notice.value = '';
  try {
    if (props.mode === 'workflow') {
      runtimeExecution.value = runtimeExecution.value?.state === 'ACTIVE'
        ? await transitionConfigurationWorkflow(lease, runtimeExecution.value, runtimeEventCode.value, facts)
        : await startConfigurationWorkflow(lease, selected.value.config_key, facts);
    } else if (props.mode === 'form') runtimeExecution.value = await validateConfigurationForm(lease, selected.value.config_key, facts);
    else if (props.mode === 'rule') runtimeExecution.value = await evaluateConfigurationRules(lease, selected.value.config_key, facts);
    else runtimeExecution.value = await authorizeConfigurationScope(lease, selected.value.config_key, facts);
    notice.value = `运行时已执行并写入证据：${runtimeExecution.value.configuration_watermark}`;
    await refreshRuntimeHistory(); resetRuntimeFacts();
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}

watch([() => selected.value?.config_id, () => leaseQuery.data.value?.lease_id], () => {
  if (selected.value && leaseQuery.data.value) void refreshRuntimeHistory();
});

function openConfigCreate() {
  configDraft.key = `${definition.value.key}-${Date.now().toString().slice(-6)}`;
  configDraft.name = definition.value.name;
  configDraft.description = String(defaults(props.mode).description);
  dialogKind.value = 'config-create';
}
function openConfigEdit() {
  if (!selected.value || !editable.value) return;
  configDraft.key = selected.value.config_key;
  configDraft.name = selected.value.display_name;
  configDraft.description = String(payload.value.description ?? '');
  dialogKind.value = 'config-edit';
}
function openConfigDelete() { if (selected.value) dialogKind.value = 'config-delete'; }

function defaultRow(key: string, preset = ''): Row {
  const count = rows(key).length + 1;
  if (key === 'nodes') return { id: `${preset.toLowerCase() || 'task'}_${count}`, name: preset === 'BRANCH' ? '条件分支' : preset === 'COMPENSATION' ? '异常补偿' : '人工任务', type: preset === 'COMPENSATION' ? 'TASK' : (preset || 'TASK'), owner: '质控医生', minutes: 60, compensation: preset === 'COMPENSATION' };
  if (key === 'edges') return { from: rows('nodes')[0]?.id ?? 'start', to: rows('nodes')[1]?.id ?? '', condition: '满足业务条件', event_code: `EVENT_${count}`, guard: { fact_path: `events.event_${count}`, operator: 'EQ', expected: true }, compensation: false };
  if (key === 'groups') return { id: `group_${count}`, name: '新分组', columns: 2 };
  if (key === 'fields') return { id: `custom_${count}`, label: '新字段', type: 'TEXT', group: rows('groups')[0]?.id ?? 'assessment', required: false, terminology: '' };
  if (key === 'rules') return { id: `rule-${count}`, name: '新提醒规则', layer: 'REMINDER', priority: 300, condition: '请输入条件', fact_path: `rule-${count}`, operator: 'EQ', expected: true, action: '创建待办', action_code: 'CREATE_REVIEW_TASK', evidence: '待补充', evidence_meta: { authority: '医院授权委员会', version: '草稿', review_due: '待审批' }, human_approval_required: true, exception: '允许关闭', enabled: true };
  return { role: '经治医生', resource: '临床文档', action: '只读', scope: '本科患者', role_code: 'ROLE-ATTENDING', resource_code: 'RESOURCE-CLINICAL-DOCUMENT', action_code: 'ACTION-READ', scope_code: 'SCOPE-CARE-RELATIONSHIP', relationship_required: true, shift_required: true, approval_required: false, effect: 'ALLOW', temporary_hours: 0, sod: '无' };
}
function openRowCreate(key: string, preset = '') {
  if (!editable.value) { notice.value = '仅草稿版本允许新增、编辑或删除；请先复制为新草稿。'; return; }
  rowKey.value = key; rowIndex.value = -1; rowDraft.value = defaultRow(key, preset); dialogKind.value = 'row-edit';
}
function openRowEdit(key: string, index: number) {
  if (!editable.value) { notice.value = '已进入生效或审批流程的版本不可直接编辑。'; return; }
  rowKey.value = key; rowIndex.value = index; rowDraft.value = clone(rows(key)[index]); dialogKind.value = 'row-edit';
}
function openRowDelete(key: string, index: number) {
  const row = rows(key)[index];
  if (!editable.value) { notice.value = '已进入生效或审批流程的版本不可直接删除。'; return; }
  if (row?.protected) { notice.value = '受保护对象不能删除，只能通过新版本停用。'; return; }
  if (key === 'groups' && rows('fields').some(field => field.group === row.id)) { notice.value = '该分组仍被字段引用，请先移动或删除相关字段。'; return; }
  rowKey.value = key; rowIndex.value = index; rowDraft.value = clone(row); dialogKind.value = 'row-delete';
}

async function persistPayload(message: string) {
  const lease = leaseQuery.data.value;
  if (!lease || !selected.value || !editable.value) return;
  const result = await updateConfiguration(lease, selected.value.config_id, { display_name: selected.value.display_name, payload: payload.value, expected_version: selected.value.row_version });
  await itemsQuery.refetch(); selectItem(result); notice.value = `${message}，已写入数据库 · v${result.row_version}`;
}

async function confirmDialog() {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || !dialogKind.value) return;
  busy.value = 'dialog'; notice.value = '';
  try {
    if (dialogKind.value === 'config-create') {
      const nextPayload = defaults(props.mode); nextPayload.description = configDraft.description.trim();
      const result = await defineConfiguration(lease, { config_type: definition.value.type, config_key: configDraft.key.trim(), display_name: configDraft.name.trim(), payload: nextPayload });
      await itemsQuery.refetch(); selectItem(result); notice.value = '新配置已创建并写入数据库。';
    } else if (dialogKind.value === 'config-edit' && selected.value) {
      payload.value.description = configDraft.description.trim();
      const result = await updateConfiguration(lease, selected.value.config_id, { display_name: configDraft.name.trim(), payload: payload.value, expected_version: selected.value.row_version });
      await itemsQuery.refetch(); selectItem(result); notice.value = `配置基本信息已更新 · v${result.row_version}`;
    } else if (dialogKind.value === 'config-delete' && selected.value) {
      await transitionConfiguration(lease, selected.value.config_id, { action: 'ARCHIVE', expected_version: selected.value.row_version, reason: '业务配置停用归档并保留版本审计证据' });
      selected.value = null; payload.value = {}; await itemsQuery.refetch(); notice.value = '配置已归档停用，不再进入运行时生效集合。';
    } else if (dialogKind.value === 'row-edit') {
      const list = [...rows(rowKey.value)];
      if (rowIndex.value < 0) list.push(clone(rowDraft.value)); else list.splice(rowIndex.value, 1, clone(rowDraft.value));
      payload.value = { ...payload.value, [rowKey.value]: list };
      await persistPayload(rowIndex.value < 0 ? '对象已新建' : '对象已编辑');
    } else if (dialogKind.value === 'row-delete') {
      const list = [...rows(rowKey.value)]; list.splice(rowIndex.value, 1); payload.value = { ...payload.value, [rowKey.value]: list };
      await persistPayload('对象已删除');
    }
    dialogKind.value = null;
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}

async function saveDraft() {
  if (!selected.value || !editable.value || busy.value) return;
  busy.value = 'save';
  try { await persistPayload('完整草稿已保存'); }
  catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}
async function lifecycle(action: ConfigurationLifecycleRequestWire['action']) {
  const lease = leaseQuery.data.value;
  if (!lease || !selected.value || busy.value) return;
  busy.value = action;
  try {
    const reasons: Record<string, string> = { VALIDATE: '执行领域静态校验和安全门检查', SUBMIT: '提交独立审批并固定当前候选版本', APPROVE: '独立审批人核对差异与运行证据', PUBLISH: '发布为配置运行时当前生效版本', ROLLBACK: '运行异常回退到上一有效配置版本' };
    const result = await transitionConfiguration(lease, selected.value.config_id, { action, expected_version: selected.value.row_version, reason: reasons[action] ?? '业务配置生命周期状态变更操作' });
    await itemsQuery.refetch(); selectItem(result); notice.value = action === 'PUBLISH' ? '配置已发布；配置运行时将按版本水印执行，新实例不会读取草稿。' : `当前状态：${statusLabel(result.status)}`; tab.value = action === 'VALIDATE' ? 'validate' : 'versions';
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}

const validation = computed(() => {
  const errors: string[] = [], warnings: string[] = [];
  const compliance = payload.value.china_compliance as Record<string, unknown> | undefined;
  if (Number(payload.value.schema_version ?? 0) < 3) errors.push('中国医疗生产运行配置必须使用 Schema v3。');
  if (!compliance || compliance.profile !== 'CN_MEDICAL_PRODUCTION_2026') errors.push('缺少中国医疗生产合规配置。');
  if (!String(compliance?.data_element_standard ?? '').includes('WS/T 363')) errors.push('未绑定 WS/T 363 数据元标准。');
  if (!String(compliance?.electronic_record_dataset ?? '').includes('WS 445')) errors.push('未绑定 WS 445 电子病历基本数据集。');
  if (!String(compliance?.signature_policy ?? '').includes('CA') || !String(compliance?.signature_policy ?? '').includes('时间戳')) errors.push('可靠电子签名必须包含 CA 与可信时间戳。');
  if (compliance?.minimum_necessary !== true) errors.push('未启用最小必要数据原则。');
  if (props.mode === 'workflow') {
    const nodes = rows('nodes'), ids = nodes.map(node => String(node.id));
    if (new Set(ids).size !== ids.length) errors.push('节点 ID 重复。');
    if (!nodes.some(node => node.type === 'START')) errors.push('缺少起始节点。');
    if (!nodes.some(node => node.terminal || node.type === 'END')) errors.push('流程没有终态。');
    rows('edges').forEach(edge => {
      if (!ids.includes(String(edge.from)) || !ids.includes(String(edge.to))) errors.push(`连线 ${edge.from} → ${edge.to} 引用不存在节点。`);
      if (!edge.event_code) errors.push(`连线 ${edge.from} → ${edge.to} 缺少事件编码。`);
      if (!edge.guard?.fact_path || !edge.guard?.operator) errors.push(`连线 ${edge.from} → ${edge.to} 缺少结构化迁移条件。`);
    });
    nodes.filter(node => node.type !== 'START' && !node.owner).forEach(node => errors.push(`${node.name} 缺少责任角色。`));
    if (!nodes.some(node => node.protected && node.type === 'SIGN')) errors.push('缺少受保护签署节点。');
  } else if (props.mode === 'form') {
    const fields = rows('fields'), ids = fields.map(field => String(field.id));
    if (new Set(ids).size !== ids.length) errors.push('字段 ID 重复。');
    fields.filter(field => !field.label || !field.group).forEach(field => errors.push(`${field.id || '字段'} 缺少名称或分组。`));
    fields.filter(field => field.type === 'CALCULATED' && !field.calculation).forEach(field => errors.push(`${field.label} 缺少计算表达式。`));
    fields.filter(field => field.type === 'CODE' && !field.terminology).forEach(field => errors.push(`${field.label} 缺少中国受控术语系统。`));
    if (!fields.some(field => field.type === 'SIGNATURE' && field.required && field.protected)) errors.push('缺少必填且受保护的可靠电子签名字段。');
    warnings.push('已发布或被病历引用的受保护字段只能停用，不能删除。');
  } else if (props.mode === 'rule') {
    rows('rules').filter(rule => String(rule.layer).includes('HARD') && !rule.evidence).forEach(rule => errors.push(`${rule.name} 是硬规则但缺少证据。`));
    rows('rules').filter(rule => !rule.fact_path || !rule.operator || !rule.action_code).forEach(rule => errors.push(`${rule.name} 缺少结构化事实、操作符或动作编码。`));
    rows('rules').filter(rule => String(rule.layer).includes('HARD') && (!rule.evidence_meta?.authority || !rule.evidence_meta?.version || !rule.evidence_meta?.review_due)).forEach(rule => errors.push(`${rule.name} 缺少受控证据元数据。`));
    rows('rules').filter(rule => rule.layer === 'AI_ADVICE' && String(rule.action).includes('阻断')).forEach(rule => errors.push(`${rule.name}：AI 建议不能直接阻断。`));
    warnings.push('平台硬门优先级最高，机构规则不得降级。');
  } else {
    rows('permissions').filter(permission => permission.effect === 'ALLOW' && permission.scope === '全部患者' && !permission.temporary_hours).forEach(permission => errors.push(`${permission.role} 的 ${permission.action} 是无范围高权限。`));
    rows('permissions').filter(permission => Number(permission.temporary_hours) > 24).forEach(permission => errors.push(`${permission.role} 临时授权超过 24 小时。`));
    rows('permissions').filter(permission => !permission.role_code || !permission.resource_code || !permission.action_code || !permission.scope_code).forEach(permission => errors.push(`${permission.role} · ${permission.resource} 缺少主数据编码。`));
    if (payload.value.deny_overrides_allow !== true) errors.push('职责范围必须启用 DENY 优先。');
    warnings.push('职责分离与保护性 DENY 不能被临时授权绕过。');
  }
  return { errors, warnings };
});
const simulation = computed(() => {
  const source = props.mode === 'workflow' ? rows('nodes') : props.mode === 'form' ? rows('fields') : props.mode === 'rule' ? rows('rules') : rows('permissions');
  return source.map((row) => ({ title: row.name ?? row.label ?? `${row.role} · ${row.resource}`, detail: row.condition ?? row.type ?? `${row.action} / ${row.scope}`, result: props.mode === 'workflow' ? row.type ?? '节点' : props.mode === 'form' ? (row.required ? '必填字段' : '选填字段') : props.mode === 'rule' ? (row.enabled ? '规则已启用' : '规则未启用') : row.effect ?? '未配置效果' }));
});
const runtimeImpact = computed(() => {
  if (!selected.value) return '请选择一项配置。';
  const prefix = selected.value.status === 'ACTIVE' ? '配置运行时可执行' : '候选版本尚未进入运行时';
  if (props.mode === 'workflow') return `${prefix}：${rows('nodes').length} 个节点、${rows('edges').length} 条迁移和超时升级策略。`;
  if (props.mode === 'form') return `${prefix}：${rows('fields').filter(field => field.required).length} 个必填字段及签署/术语约束。`;
  if (props.mode === 'rule') return `${prefix}：${rows('rules').filter(rule => rule.enabled).length} 条启用规则按优先级执行。`;
  return `${prefix}：${rows('permissions').length} 条职责范围参与最终授权判定。`;
});
const runtimeEvents = computed<Row[]>(() => Array.isArray(runtimeExecution.value?.output_payload?.available_events)
  ? runtimeExecution.value!.output_payload.available_events as Row[] : []);
const statusLabel = (status: string) => ({ DRAFT: '草稿', PENDING_APPROVAL: '待审批', APPROVED: '已批准', ACTIVE: '已发布', ARCHIVED: '已归档' } as Record<string, string>)[status] ?? status;
const rowLabel = (row: Row) => row.name ?? row.label ?? row.id ?? `${row.role} · ${row.resource}`;
const dialogTitle = computed(() => dialogKind.value === 'config-create' ? `新建${definition.value.title}` : dialogKind.value === 'config-edit' ? '编辑配置基本信息' : dialogKind.value === 'config-delete' ? '删除并归档配置' : dialogKind.value === 'row-delete' ? `删除${rowLabel(rowDraft.value)}` : rowIndex.value < 0 ? '新建业务对象' : `编辑${rowLabel(rowDraft.value)}`);
</script>

<template>
  <section data-page-root class="content vue-native-page bc-page">
    <div class="page-head"><div class="page-title"><p class="eyebrow">{{ definition.eyebrow }}</p><h1>{{ definition.title }}</h1><p>{{ definition.subtitle }}</p></div><div class="head-actions"><button class="btn" @click="tab='versions'">版本与继承</button><button class="btn" :disabled="!selected||Boolean(busy)" @click="lifecycle('VALIDATE')">静态校验</button><button class="btn primary" @click="openConfigCreate">新建配置</button></div></div>
    <div v-if="notice" class="bc-notice">{{ notice }}</div><div v-if="issue" class="bc-state error">{{ issue.code }}：{{ issue.message }}</div>
    <div class="runtime-impact" :class="{ active:selected?.status==='ACTIVE' }"><b>{{ selected?.status==='ACTIVE'?'当前运行时版本':'运行时影响' }}</b><span>{{ runtimeImpact }}</span></div>
    <div class="bc-shell"><aside class="ledger"><header><div><b>配置台账</b><small>{{ definition.type }} · {{ items.length }} 项</small></div><button title="新建配置" @click="openConfigCreate">＋</button></header><button v-for="item in items" :key="item.config_id" class="ledger-item" :class="{active:selected?.config_id===item.config_id}" @click="selectItem(item)"><b>{{ item.display_name }}</b><code>{{ item.config_key }}</code><span>{{ statusLabel(item.status) }} · v{{ item.row_version }}</span></button><div v-if="!items.length&&!itemsQuery.isPending.value" class="ledger-empty"><b>暂无配置</b><span>通过“新建配置”载入真实业务流程模板。</span></div><div v-if="leaseQuery.isPending.value||itemsQuery.isPending.value" class="ledger-loading">正在同步数据库版本…</div><div class="safety"><b>发布安全门</b><p>发布前完成校验与独立审批，保留版本和审计记录。</p></div></aside>
      <section v-if="selected" class="designer"><div class="meta"><div><span>配置名称</span><b>{{ selected.display_name }}</b></div><details class="technical-details"><summary>技术详情</summary><p>配置键：<code>{{ selected.config_key }}</code></p><p>Schema v{{ payload.schema_version ?? 1 }}</p><RouterLink class="task-action" :to="`/business-config/${deepModule}/${selected.config_id}`">查看配置详情</RouterLink></details><div class="meta-actions"><button class="task-action" :disabled="!editable" @click="openConfigEdit">编辑</button><button class="task-action danger" @click="openConfigDelete">删除</button></div></div><nav><button :class="{active:tab==='design'}" @click="tab='design'">设计</button><button :class="{active:tab==='validate'}" @click="tab='validate'">校验 <i v-if="validation.errors.length">{{ validation.errors.length }}</i></button><button :class="{active:tab==='simulate'}" @click="tab='simulate'">草稿结构预演</button><button :class="{active:tab==='runtime'}" @click="tab='runtime';resetRuntimeFacts();refreshRuntimeHistory()">运行排查</button><button :class="{active:tab==='versions'}" @click="tab='versions'">版本</button></nav>
        <div v-if="tab==='design'" class="stage" :class="{ 'workflow-stage':mode==='workflow' }">
          <template v-if="mode==='workflow'">
            <aside class="tools workflow-tools"><b>节点组件</b><button @click="openRowCreate('nodes','TASK')">＋ 人工任务</button><button @click="openRowCreate('nodes','BRANCH')">◇ 条件分支</button><button @click="openRowCreate('nodes','COMPENSATION')">↺ 补偿节点</button><button @click="openRowCreate('edges')">＋ 状态迁移</button><small>点击节点查看属性；新增、编辑与删除均通过弹窗完成。</small></aside>
            <section class="canvas workflow-canvas">
              <div class="canvas-head workflow-toolbar"><div><b>可视化流程画布</b><span>{{ rows('nodes').length }} 节点 · {{ rows('edges').length }} 迁移</span></div><div><button title="缩小流程图" @click="changeWorkflowZoom(-10)">－</button><span>{{ workflowZoom }}%</span><button title="放大流程图" @click="changeWorkflowZoom(10)">＋</button><button @click="resetWorkflowLayout">自动布局</button><button @click="workflowTableMode=!workflowTableMode">{{ workflowTableMode?'流程图模式':'表格模式' }}</button></div></div>
              <div v-if="!workflowTableMode" class="workflow-graph-scroll">
                <div class="workflow-graph-frame" :style="{width:`${workflowLayout.width*workflowZoom/100}px`,height:`${workflowLayout.height*workflowZoom/100}px`}">
                  <div class="workflow-graph" :style="{width:`${workflowLayout.width}px`,height:`${workflowLayout.height}px`,transform:`scale(${workflowZoom/100})`}">
                    <div v-for="edge in workflowLayout.edges" :key="`edge-${edge.index}`" class="workflow-connector" :class="{compensation:edge.data.compensation}" :title="String(edge.data.condition??'状态迁移')" :style="{left:`${edge.x}px`,top:`${edge.y}px`,width:`${edge.width}px`,transform:`rotate(${edge.angle}deg)`}" />
                    <span v-for="edge in workflowLayout.edges.filter(item=>item.data.compensation)" :key="`edge-label-${edge.index}`" class="workflow-edge-label" :style="{left:`${edge.labelX}px`,top:`${edge.labelY}px`}">退回</span>
                    <article v-for="layoutNode in workflowLayout.nodes" :key="layoutNode.id" class="workflow-node" :class="{active:selectedWorkflowNodeId===layoutNode.id,protected:layoutNode.data.protected,terminal:layoutNode.data.terminal}" :style="{left:`${layoutNode.x}px`,top:`${layoutNode.y}px`}" @click="selectedWorkflowNodeId=layoutNode.id">
                      <small>{{ layoutNode.data.type }}</small><b>{{ layoutNode.data.name }}</b><span>{{ layoutNode.data.owner }}</span><em>{{ layoutNode.data.minutes }} 分钟</em><footer><button @click.stop="openRowEdit('nodes',layoutNode.index)">编辑</button><button :disabled="layoutNode.data.protected" @click.stop="openRowDelete('nodes',layoutNode.index)">删除</button></footer>
                    </article>
                  </div>
                </div>
              </div>
              <div v-else class="workflow-table-wrap"><table><thead><tr><th>节点</th><th>类型</th><th>责任角色</th><th>时限</th><th>操作</th></tr></thead><tbody><tr v-for="(node,index) in rows('nodes')" :key="node.id"><td><b>{{ node.name }}</b><code>{{ node.id }}</code></td><td>{{ node.type }}</td><td>{{ node.owner }}</td><td>{{ node.minutes }} 分钟</td><td><button @click="openRowEdit('nodes',index)">编辑</button><button :disabled="node.protected" @click="openRowDelete('nodes',index)">删除</button></td></tr></tbody></table></div>
              <details class="workflow-transitions"><summary>状态迁移（{{ rows('edges').length }}）</summary><div class="edges"><div class="section-title"><b>迁移条件与补偿路径</b><button @click="openRowCreate('edges')">新增迁移</button></div><article v-for="(edge,index) in rows('edges')" :key="`${edge.from}-${edge.to}-${index}`"><p><code>{{ edge.from }}</code><span>到</span><code>{{ edge.to }}</code><em>{{ edge.condition }}</em><i v-if="edge.compensation">退回 / 补偿</i></p><footer><button @click="openRowEdit('edges',index)">编辑</button><button @click="openRowDelete('edges',index)">删除</button></footer></article></div></details>
            </section>
            <aside class="workflow-inspector"><header><div><span>节点属性</span><b>{{ selectedWorkflowNode?.name??'未选择节点' }}</b></div><button v-if="selectedWorkflowNodeIndex>=0" :disabled="!editable" @click="openRowEdit('nodes',selectedWorkflowNodeIndex)">编辑</button></header><dl v-if="selectedWorkflowNode"><div><dt>节点 ID</dt><dd><code>{{ selectedWorkflowNode.id }}</code></dd></div><div><dt>类型</dt><dd>{{ selectedWorkflowNode.type }}</dd></div><div><dt>责任角色</dt><dd>{{ selectedWorkflowNode.owner }}</dd></div><div><dt>办理时限</dt><dd>{{ selectedWorkflowNode.minutes }} 分钟</dd></div><div><dt>保护状态</dt><dd>{{ selectedWorkflowNode.protected?'受保护，不允许直接删除':'普通节点' }}</dd></div></dl><section><b>校验摘要</b><p :class="{passed:!validation.errors.length}">{{ validation.errors.length?`${validation.errors.length} 个阻断项`:'结构校验通过' }}</p><small>含终态、签署、责任角色、迁移引用与安全门检查。</small><button @click="tab='validate'">查看全部校验</button></section><section><b>运行时边界</b><small>候选草稿不直接影响临床流程；发布后运行时按版本读取。</small></section></aside>
          </template>
          <template v-else-if="mode==='form'"><aside class="tools"><b>模板组件</b><button @click="openRowCreate('fields')">＋ 新建字段</button><button @click="openRowCreate('groups')">▦ 新建分组</button><small>字段、分组及删除确认均采用弹窗。</small></aside><section class="canvas"><div class="canvas-head"><b>A4 表单画布</b></div><div class="form-groups"><article v-for="(group,groupIndex) in rows('groups')" :key="group.id"><header><div><b>{{ group.name }}</b><span>{{ group.columns }} 列</span></div><div><button @click="openRowEdit('groups',groupIndex)">编辑分组</button><button @click="openRowDelete('groups',groupIndex)">删除</button></div></header><div class="field-grid"><section v-for="field in rows('fields').filter(item=>item.group===group.id)" :key="field.id"><b>{{ field.label }} <i v-if="field.required">*</i></b><span>{{ field.type }}</span><em>{{ field.terminology }}</em><footer><button @click="openRowEdit('fields',rows('fields').indexOf(field))">编辑</button><button :disabled="field.protected" @click="openRowDelete('fields',rows('fields').indexOf(field))">删除</button></footer></section></div></article></div></section></template>
          <template v-else-if="mode==='rule'"><aside class="tools"><b>规则分层</b><button @click="openRowCreate('rules')">＋ 新建规则</button><span>平台硬门</span><span>机构规则</span><span>提醒</span><span>AI 建议</span><small>硬门优先，AI 建议不能直接产生临床副作用。</small></aside><section class="canvas"><div class="canvas-head"><b>规则优先级与命中路径</b></div><div class="rule-list"><article v-for="(rule,index) in rows('rules')" :key="rule.id"><header><span>{{ rule.layer }}</span><b>{{ rule.priority }}</b></header><h3>{{ rule.name }}</h3><p><b>条件</b>{{ rule.condition }}</p><p><b>动作</b>{{ rule.action }}</p><p><b>证据</b>{{ rule.evidence }}</p><footer><em>{{ rule.enabled?'启用':'停用' }}</em><div><button @click="openRowEdit('rules',index)">编辑</button><button :disabled="rule.protected" @click="openRowDelete('rules',index)">删除</button></div></footer></article></div></section></template>
          <template v-else><aside class="tools"><b>授权模型</b><button @click="openRowCreate('permissions')">＋ 新建职责范围</button><span>组织与科室</span><span>患者关系</span><span>班次与临时授权</span><small>保护性拒绝优先，授权最长 24 小时。</small></aside><section class="canvas"><div class="canvas-head"><b>最终权限矩阵</b></div><div class="scope-table"><article v-for="(permission,index) in rows('permissions')" :key="`${permission.role}-${permission.resource}-${index}`"><div><b>{{ permission.role }}</b><span>{{ permission.resource }}</span></div><p>{{ permission.action }}</p><p>{{ permission.scope }}</p><em :class="permission.effect.toLowerCase()">{{ permission.effect }}</em><small>{{ permission.temporary_hours?`${permission.temporary_hours} 小时临时授权`:'长期职责' }}</small><footer><button @click="openRowEdit('permissions',index)">编辑</button><button @click="openRowDelete('permissions',index)">删除</button></footer></article></div></section></template>
        </div>
        <section v-else-if="tab==='validate'" class="result-panel"><header><div><p class="eyebrow">静态分析</p><h2>{{ validation.errors.length?'发现阻断项':'本地领域校验通过' }}</h2></div><button class="btn" :disabled="Boolean(busy)" @click="lifecycle('VALIDATE')">写入校验证据</button></header><ul v-if="validation.errors.length" class="errors"><li v-for="error in validation.errors" :key="error">{{ error }}</li></ul><ul class="warnings"><li v-for="warning in validation.warnings" :key="warning">{{ warning }}</li></ul></section>
        <section v-else-if="tab==='simulate'" class="result-panel"><header><div><p class="eyebrow">仅限设计期</p><h2>草稿结构预演</h2></div><span>不写入运行证据，不代表临床命中</span></header><p class="version-note">此处只检查配置结构和展示顺序。需要证明配置被执行，请进入“运行排查”并查看数据库执行 ID、版本水印与输出。</p><div class="simulation"><article v-for="(step,index) in simulation" :key="`${step.title}-${index}`"><span>{{ String(index+1).padStart(2,'0') }}</span><div><b>{{ step.title }}</b><p>{{ step.detail }}</p></div><em>配置：{{ step.result }}</em></article></div></section>
        <section v-else-if="tab==='runtime'" class="result-panel runtime-console"><header><div><p class="eyebrow">数据库执行证据</p><h2>{{ mode==='workflow'?'流程实例':mode==='form'?'模板校验':mode==='rule'?'规则判定':'职责授权判定' }}</h2></div><span>{{ selected.status==='ACTIVE'?'ACTIVE 版本可执行':'未发布，运行时失败关闭' }}</span></header><details :key="selected.config_id" class="technical-details"><summary>展开运行调试与技术记录</summary><div class="runtime-grid"><section><h3>运行事实</h3><label v-if="mode==='workflow'&&runtimeExecution?.state==='ACTIVE'">迁移事件<select v-model="runtimeEventCode"><option v-for="event in runtimeEvents" :key="event.event_code" :value="event.event_code">{{ event.label }} · {{ event.event_code }}</option></select></label><label>JSON 事实<textarea v-model="runtimeFactsText" rows="12" spellcheck="false" /></label><div class="lifecycle-actions"><button class="btn" @click="resetRuntimeFacts">重置样例</button><button class="btn primary" :disabled="selected.status!=='ACTIVE'||busy==='runtime'" @click="executeRuntime">{{ busy==='runtime'?'执行中…':mode==='workflow'&&runtimeExecution?.state==='ACTIVE'?'执行迁移':'执行并留证' }}</button></div></section><section class="runtime-result"><h3>最近一次结果</h3><template v-if="runtimeExecution"><dl><div><dt>执行 ID</dt><dd><code>{{ runtimeExecution.execution_id }}</code></dd></div><div><dt>状态</dt><dd><b>{{ runtimeExecution.state }}</b></dd></div><div><dt>配置水印</dt><dd><code>{{ runtimeExecution.configuration_watermark }}</code></dd></div><div><dt>实例版本</dt><dd>v{{ runtimeExecution.row_version }}</dd></div><div v-if="runtimeExecution.current_node"><dt>当前节点</dt><dd>{{ runtimeExecution.current_node }}</dd></div></dl><pre>{{ JSON.stringify(runtimeExecution.output_payload,null,2) }}</pre></template><p v-else class="version-note">尚未执行。这里不会用前端固定结果冒充规则或流程命中。</p></section></div><section class="runtime-history"><header><h3>执行历史</h3><button class="btn" @click="refreshRuntimeHistory">刷新</button></header><div v-if="runtimeHistory.length" class="runtime-table"><table><thead><tr><th>时间</th><th>操作</th><th>状态</th><th>版本水印</th><th>执行 ID</th></tr></thead><tbody><tr v-for="item in runtimeHistory" :key="item.execution_id"><td>{{ new Date(item.created_at).toLocaleString('zh-CN',{hour12:false}) }}</td><td>{{ item.operation }}</td><td>{{ item.state }}</td><td><code>{{ item.configuration_watermark }}</code></td><td><code>{{ item.execution_id }}</code></td></tr></tbody></table></div><p v-else class="version-note">没有数据库运行记录。</p></section></details></section>
        <section v-else class="result-panel"><header><div><p class="eyebrow">生命周期与运行时</p><h2>v{{ selected.row_version }} · {{ statusLabel(selected.status) }}</h2></div><span>{{ selected.updated_at?new Date(selected.updated_at).toLocaleString('zh-CN',{hour12:false}):'' }}</span></header><p class="version-note">保存形成不可变修订；配置运行时只执行已校验、已独立审批的 ACTIVE 版本；每次执行绑定版本水印并写入审计与 Outbox。归档不会删除历史执行证据。</p><div class="lifecycle-actions"><button v-if="selected.status==='DRAFT'" class="btn" :disabled="Boolean(busy)" @click="saveDraft">保存草稿</button><button v-if="selected.status==='DRAFT'&&selected.validation_state==='VALID'" class="btn primary" :disabled="Boolean(busy)" @click="lifecycle('SUBMIT')">提交审批</button><button v-if="selected.status==='PENDING_APPROVAL'" class="btn primary" :disabled="Boolean(busy)" @click="lifecycle('APPROVE')">独立审批</button><button v-if="selected.status==='APPROVED'" class="btn primary" :disabled="Boolean(busy)" @click="lifecycle('PUBLISH')">发布到配置运行时</button><button v-if="selected.status==='ACTIVE'" class="btn" :disabled="Boolean(busy)" @click="lifecycle('ROLLBACK')">回退上一版本</button><button class="btn danger" :disabled="Boolean(busy)" @click="openConfigDelete">删除 / 归档</button></div></section>
      </section><section v-else class="designer-empty"><b>暂无可用配置</b><p>新建后会直接写入数据库草稿，并可继续校验、审批、发布和运行时生效。</p><button class="btn primary" @click="openConfigCreate">新建第一项配置</button></section></div>

    <BusinessActionDialog :open="Boolean(dialogKind)" :title="dialogTitle" :description="dialogKind==='config-delete'||dialogKind==='row-delete'?'删除操作会保留审计与版本证据，并从当前工作流中移除。':'所有字段在确认后一次性写入，取消不会修改当前配置。'" :confirm-label="dialogKind==='config-delete'||dialogKind==='row-delete'?'确认删除':'确认保存'" :danger="dialogKind==='config-delete'||dialogKind==='row-delete'" :busy="busy==='dialog'" width="wide" @cancel="dialogKind=null" @confirm="confirmDialog">
      <template v-if="dialogKind==='config-create'||dialogKind==='config-edit'"><div class="dialog-grid"><label>配置名称<input v-model="configDraft.name" required maxlength="120" autofocus /></label><label>唯一键<input v-model="configDraft.key" required maxlength="120" :disabled="dialogKind==='config-edit'" /></label></div><label>业务说明<textarea v-model="configDraft.description" required rows="4" /></label></template>
      <p v-else-if="dialogKind==='config-delete'" class="dialog-warning">将删除“{{ selected?.display_name }}”的当前使用入口。配置状态会变为归档，已产生的病历、任务、审计及历史版本不会被物理删除。</p>
      <p v-else-if="dialogKind==='row-delete'" class="dialog-warning">确认从当前草稿删除“{{ rowLabel(rowDraft) }}”？确认后会立即生成新的数据库修订版本。</p>
      <template v-else-if="dialogKind==='row-edit'&&rowKey==='nodes'"><div class="dialog-grid"><label>节点 ID<input v-model="rowDraft.id" required /></label><label>节点类型<select v-model="rowDraft.type"><option>START</option><option>TASK</option><option>BRANCH</option><option>SIGN</option><option>AUDIT</option><option>END</option></select></label><label>节点名称<input v-model="rowDraft.name" required /></label><label>责任角色<input v-model="rowDraft.owner" required /></label><label>办理时限（分钟）<input v-model.number="rowDraft.minutes" type="number" min="1" /></label></div><label class="dialog-check"><input v-model="rowDraft.compensation" type="checkbox" />异常退回 / 补偿节点</label></template>
      <template v-else-if="dialogKind==='row-edit'&&rowKey==='edges'"><div class="dialog-grid"><label>来源节点<select v-model="rowDraft.from"><option v-for="node in rows('nodes')" :key="node.id" :value="node.id">{{ node.name }}（{{ node.id }}）</option></select></label><label>目标节点<select v-model="rowDraft.to"><option v-for="node in rows('nodes')" :key="node.id" :value="node.id">{{ node.name }}（{{ node.id }}）</option></select></label><label>事件编码<input v-model="rowDraft.event_code" required placeholder="如 CONSULT_ACCEPTED" /></label><label>迁移说明<input v-model="rowDraft.condition" required /></label><label>事实路径<input v-model="rowDraft.guard.fact_path" required placeholder="events.receive.completed" /></label><label>操作符<select v-model="rowDraft.guard.operator"><option>EQ</option><option>NE</option><option>GT</option><option>GTE</option><option>LT</option><option>LTE</option><option>PRESENT</option></select></label><label>期望值<input v-model="rowDraft.guard.expected" required /></label></div><label class="dialog-check"><input v-model="rowDraft.compensation" type="checkbox" />这是退回或补偿路径</label></template>
      <template v-else-if="dialogKind==='row-edit'&&rowKey==='groups'"><div class="dialog-grid"><label>分组 ID<input v-model="rowDraft.id" required /></label><label>分组名称<input v-model="rowDraft.name" required /></label><label>布局列数<select v-model.number="rowDraft.columns"><option :value="1">1 列</option><option :value="2">2 列</option><option :value="3">3 列</option></select></label></div></template>
      <template v-else-if="dialogKind==='row-edit'&&rowKey==='fields'"><div class="dialog-grid"><label>字段 ID<input v-model="rowDraft.id" required /></label><label>显示名称<input v-model="rowDraft.label" required /></label><label>字段类型<select v-model="rowDraft.type"><option>TEXT</option><option>TEXTAREA</option><option>NUMBER</option><option>CODE</option><option>CALCULATED</option><option>SIGNATURE</option></select></label><label>所属分组<select v-model="rowDraft.group"><option v-for="group in rows('groups')" :key="group.id" :value="group.id">{{ group.name }}</option></select></label><label>术语系统<input v-model="rowDraft.terminology" placeholder="国家临床版 ICD-10 / WS/T 363 映射" /></label><label>计算 / 校验表达式<input v-model="rowDraft.calculation" /></label></div><div class="dialog-check-row"><label class="dialog-check"><input v-model="rowDraft.required" type="checkbox" />必填字段</label><label class="dialog-check"><input v-model="rowDraft.protected" type="checkbox" />受保护字段</label></div></template>
      <template v-else-if="dialogKind==='row-edit'&&rowKey==='rules'"><div class="dialog-grid"><label>规则 ID<input v-model="rowDraft.id" required /></label><label>规则名称<input v-model="rowDraft.name" required /></label><label>规则层级<select v-model="rowDraft.layer"><option>PLATFORM_HARD</option><option>INSTITUTION_HARD</option><option>REMINDER</option><option>AI_ADVICE</option></select></label><label>优先级<input v-model.number="rowDraft.priority" type="number" min="0" /></label><label>事实路径<input v-model="rowDraft.fact_path" required /></label><label>操作符<select v-model="rowDraft.operator"><option>EQ</option><option>NE</option><option>GT</option><option>GTE</option><option>LT</option><option>LTE</option><option>CONTAINS</option><option>PRESENT</option></select></label><label>期望值<input v-model="rowDraft.expected" /></label><label>动作编码<input v-model="rowDraft.action_code" required /></label></div><label>命中条件<textarea v-model="rowDraft.condition" required /></label><label>执行动作<textarea v-model="rowDraft.action" required /></label><div class="dialog-grid"><label>证据来源<input v-model="rowDraft.evidence" /></label><label>证据权威机构<input v-model="rowDraft.evidence_meta.authority" /></label><label>证据版本<input v-model="rowDraft.evidence_meta.version" /></label><label>复核到期日<input v-model="rowDraft.evidence_meta.review_due" type="date" /></label><label>例外策略<input v-model="rowDraft.exception" /></label></div><div class="dialog-check-row"><label class="dialog-check"><input v-model="rowDraft.enabled" type="checkbox" />启用规则</label><label class="dialog-check"><input v-model="rowDraft.human_approval_required" type="checkbox" />需要人工最终确认</label></div></template>
      <template v-else-if="dialogKind==='row-edit'&&rowKey==='permissions'"><div class="dialog-grid"><label>角色<input v-model="rowDraft.role" required /></label><label>角色主数据编码<input v-model="rowDraft.role_code" required /></label><label>资源<input v-model="rowDraft.resource" required /></label><label>资源编码<input v-model="rowDraft.resource_code" required /></label><label>动作<input v-model="rowDraft.action" required /></label><label>动作编码<input v-model="rowDraft.action_code" required /></label><label>数据范围<input v-model="rowDraft.scope" required /></label><label>范围编码<input v-model="rowDraft.scope_code" required /></label><label>授权效果<select v-model="rowDraft.effect"><option>ALLOW</option><option>DENY</option></select></label><label>临时授权（小时）<input v-model.number="rowDraft.temporary_hours" type="number" min="0" max="24" /></label></div><div class="dialog-check-row"><label class="dialog-check"><input v-model="rowDraft.relationship_required" type="checkbox" />校验患者诊疗关系</label><label class="dialog-check"><input v-model="rowDraft.shift_required" type="checkbox" />校验在岗班次</label><label class="dialog-check"><input v-model="rowDraft.approval_required" type="checkbox" />需要额外审批</label></div><label>职责分离约束<input v-model="rowDraft.sod" required /></label></template>
    </BusinessActionDialog>
  </section>
</template>

<style scoped>
.technical-details { min-width: 0; padding: 8px 0; }.technical-details summary { cursor: pointer; color: #42627c; font-weight: 600; }.technical-details p { overflow-wrap: anywhere; }.technical-details[open] > summary { margin-bottom: 12px; }
.bc-page{color:#17283a;min-width:0}.page-head{display:flex;align-items:flex-start;justify-content:space-between;gap:20px;margin-bottom:14px}.page-title{min-width:0}.page-title h1{margin:2px 0 4px;font-size:23px}.page-title>p:last-child{margin:0;color:#647488;font-size:12px}.eyebrow{margin:0;color:#2673ad;font-size:10px;font-weight:700}.head-actions,.meta-actions,.lifecycle-actions{display:flex;flex-wrap:wrap;gap:8px}.bc-notice,.bc-state,.runtime-impact{margin-bottom:12px;padding:11px 13px;border:1px solid #bbd9ec;border-radius:8px;background:#eef7fd;color:#19567e;font-size:12px}.runtime-impact{display:flex;gap:10px;align-items:center;border-color:#d9e2e9;background:#f8fafb;color:#536477}.runtime-impact.active{border-color:#afd9bf;background:#edf9f2;color:#236a42}.runtime-impact b{white-space:nowrap}.bc-shell{display:grid;grid-template-columns:220px minmax(0,1fr);min-height:680px;border:1px solid #d9e2e9;border-radius:11px;overflow:hidden;background:#fff}.ledger{display:flex;flex-direction:column;gap:8px;padding:13px;border-right:1px solid #d9e2e9;background:#f7f9fb}.ledger header{display:flex;justify-content:space-between;gap:10px}.ledger header>div{display:grid;gap:3px}.ledger header small,.ledger-empty span{color:#687789;font-size:10px}.ledger header>button{width:34px;height:34px;border:1px solid #cbd8e1;border-radius:8px;background:#fff;color:#1769aa;font-size:20px}.ledger-item{display:grid;gap:5px;padding:11px;border:1px solid transparent;border-radius:8px;background:transparent;text-align:left;color:inherit;overflow-wrap:anywhere}.ledger-item.active{border-color:#9fc6df;background:#fff}.ledger-item code{color:#627287;font-size:10px}.ledger-item span{font-size:10px;color:#50718a}.ledger-empty{display:grid;gap:6px;padding:18px 12px;text-align:center}.ledger-loading{padding:12px;color:#647488;font-size:11px}.safety{margin-top:auto;padding-top:14px;border-top:1px solid #dce5eb}.safety p{margin:6px 0 0;color:#657487;font-size:10px;line-height:1.6}.designer{min-width:0;overflow:hidden}.designer-empty{display:grid;place-content:center;justify-items:center;gap:10px;padding:30px;text-align:center}.designer-empty p{max-width:480px;color:#657487;font-size:12px}.meta{display:grid;grid-template-columns:minmax(160px,1fr) minmax(180px,1fr) auto auto;gap:12px;align-items:end;padding:13px 14px;border-bottom:1px solid #dce5eb}.meta>div:not(.meta-actions){display:grid;gap:5px;min-width:0}.meta span{color:#657487;font-size:10px}.meta b,.meta code{font-size:12px;overflow-wrap:anywhere}.meta>em{align-self:center;padding:5px 8px;border-radius:10px;background:#f0f4f7;color:#607083;font-size:9px;font-style:normal}.designer>nav{display:flex;gap:4px;padding:0 14px;border-bottom:1px solid #dce5eb;overflow-x:auto}.designer>nav button{padding:11px 13px;border:0;border-bottom:2px solid transparent;background:transparent;color:#536477;white-space:nowrap}.designer>nav button.active{border-color:#1769aa;color:#1769aa;font-weight:700}.designer>nav i{padding:1px 5px;border-radius:8px;background:#b63232;color:#fff;font-style:normal}.stage{display:grid;grid-template-columns:138px minmax(0,1fr);min-height:550px;background:#fbfcfd}.tools{display:flex;flex-direction:column;gap:9px;padding:12px;border-right:1px solid #dce5eb;background:#f7f9fb;min-width:0}.tools button,.tools>span{padding:9px;border:1px solid #d1dce4;border-radius:7px;background:#fff;text-align:left;color:#27394b}.tools small{margin-top:6px;color:#657487;font-size:10px;line-height:1.5}.canvas{min-width:0;padding:12px;background-image:radial-gradient(#dce5eb .7px,transparent .7px);background-size:13px 13px}.canvas-head,.section-title{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-bottom:10px;padding:10px 11px;border:1px solid #dce5eb;border-radius:8px;background:#fff}.canvas-head button,.section-title button,.flow footer button,.edges footer button,.form-groups button,.rule-list button,.scope-table button{border:0;background:transparent;color:#1769aa;font-size:10px;cursor:pointer}.flow{display:grid;grid-template-columns:repeat(auto-fit,minmax(145px,1fr));gap:10px}.flow>article{display:grid;gap:5px;padding:11px;border:1px solid #cfdbe4;border-radius:8px;background:#fff;box-shadow:0 2px 5px rgba(34,58,78,.05)}.flow>article.protected{border-color:#dec788;background:#fffaf0}.flow>article.terminal{border-color:#acd5bc;background:#f2faf5}.flow small,.flow span,.flow em{font-size:9px;color:#667588}.flow em{font-style:normal}.flow footer,.edges footer,.field-grid footer,.rule-list footer,.scope-table footer{display:flex;justify-content:flex-end;gap:9px;margin-top:5px;padding-top:7px;border-top:1px solid #edf1f4}.edges{margin-top:10px;padding:11px;border:1px solid #dce5eb;border-radius:9px;background:#fff}.edges .section-title{margin:0 0 4px;padding:4px 0;border:0}.edges>article{display:grid;grid-template-columns:minmax(0,1fr) auto;align-items:center;gap:10px;padding:8px 0;border-bottom:1px solid #edf1f4}.edges p{display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin:0;font-size:10px}.edges em{color:#526477;font-style:normal}.edges i{color:#a15d24;font-style:normal}.form-groups{display:grid;gap:12px}.form-groups>article{padding:12px;border:1px solid #dce5eb;border-radius:9px;background:#fff}.form-groups>article>header{display:flex;justify-content:space-between;gap:12px;margin-bottom:10px}.form-groups header>div{display:flex;gap:9px;align-items:center}.form-groups header span{color:#657487;font-size:10px}.field-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:9px}.field-grid>section{display:grid;gap:5px;padding:10px;border:1px solid #d8e2e9;border-radius:7px;min-width:0}.field-grid span,.field-grid em{font-size:9px;color:#657487}.field-grid em{font-style:normal}.field-grid i{color:#b63232}.rule-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.rule-list>article{padding:12px;border:1px solid #dce5eb;border-radius:9px;background:#fff;min-width:0}.rule-list header{display:flex;justify-content:space-between;color:#1769aa;font-size:10px}.rule-list h3{margin:9px 0;font-size:13px}.rule-list p{display:grid;grid-template-columns:40px minmax(0,1fr);gap:8px;margin:6px 0;color:#657487;font-size:10px;overflow-wrap:anywhere}.rule-list p b{color:#25384b}.rule-list footer{justify-content:space-between}.rule-list footer em{font-size:10px;color:#28764a;font-style:normal}.scope-table{display:grid;gap:8px}.scope-table>article{display:grid;grid-template-columns:minmax(110px,1.2fr) minmax(52px,.7fr) minmax(100px,1fr) auto minmax(100px,1fr) auto;gap:10px;align-items:center;padding:10px;border:1px solid #dce5eb;border-radius:8px;background:#fff;min-width:0}.scope-table>article>div{display:grid;gap:3px;min-width:0}.scope-table span,.scope-table p,.scope-table small{margin:0;color:#657487;font-size:10px;overflow-wrap:anywhere}.scope-table em{padding:4px 7px;border-radius:9px;font-size:9px;font-style:normal}.scope-table em.allow{background:#e8f7ef;color:#267249}.scope-table em.deny{background:#fdeaea;color:#a43131}.scope-table footer{margin:0;padding:0;border:0}.result-panel{display:grid;gap:14px;padding:18px}.result-panel>header{display:flex;justify-content:space-between;gap:14px;align-items:flex-start}.result-panel h2{margin:3px 0 0}.result-panel>header>span{color:#657487;font-size:11px}.result-panel ul{margin:0;padding:14px 14px 14px 32px;border-radius:8px;font-size:12px}.errors{background:#fff0f0;color:#972e2e}.warnings{background:#fff8e8;color:#795d1d}.simulation{display:grid;gap:8px}.simulation article{display:grid;grid-template-columns:34px minmax(0,1fr) auto;gap:10px;align-items:center;padding:11px;border:1px solid #dce5eb;border-radius:8px}.simulation article>span{color:#1769aa;font-weight:700}.simulation p{margin:4px 0 0;color:#657487;font-size:10px}.simulation em{color:#28764a;font-size:10px;font-style:normal}.version-note{margin:0;padding:14px;border-radius:8px;background:#f2f6f8;color:#536477;font-size:12px;line-height:1.6}.task-action{padding:6px 9px;border:1px solid #cbd8e1;border-radius:6px;background:#fff;color:#1769aa}.task-action.danger,.btn.danger{color:#a43131}.task-action:disabled{opacity:.45}.btn{padding:9px 13px;border:1px solid #cbd8e1;border-radius:7px;background:#fff;color:#294052}.btn.primary{border-color:#1769aa;background:#1769aa;color:#fff}

.workflow-stage{grid-template-columns:126px minmax(390px,1fr) 202px;min-height:570px}.workflow-canvas{padding:10px;overflow:hidden}.workflow-toolbar{align-items:flex-start}.workflow-toolbar>div{display:flex;align-items:center;gap:7px;min-width:0}.workflow-toolbar>div:first-child{display:grid;gap:2px}.workflow-toolbar>div:first-child span{color:#657487;font-size:9px}.workflow-toolbar>div:last-child{justify-content:flex-end;flex-wrap:wrap}.workflow-toolbar>div:last-child>span{min-width:34px;color:#526477;font-size:9px;text-align:center}.workflow-toolbar button{padding:5px 7px;border:1px solid #d5dfe6;border-radius:6px;background:#fff;white-space:nowrap}.workflow-graph-scroll{height:500px;border:1px solid #d8e2e9;border-radius:8px;background-color:#fbfcfe;background-image:radial-gradient(#cdd7e2 1px,transparent 1px);background-size:18px 18px;overflow:auto;overscroll-behavior:contain}.workflow-graph-frame{position:relative;min-width:100%;min-height:100%}.workflow-graph{position:absolute;inset:0;transform-origin:top left}.workflow-node{position:absolute;z-index:2;display:grid;width:154px;min-height:88px;gap:3px;padding:8px 9px;border:1px solid #aebfd0;border-radius:9px;background:#fff;box-shadow:0 4px 13px rgba(22,58,95,.08);cursor:pointer}.workflow-node.active{border:2px solid #1769aa;box-shadow:0 0 0 3px #dbeaff}.workflow-node.protected{border-color:#e1a23b;background:#fffaf0}.workflow-node.terminal{border-color:#77bf92;background:#f2faf5}.workflow-node small,.workflow-node span,.workflow-node em{color:#667588;font-size:9px}.workflow-node em{font-style:normal}.workflow-node footer{display:flex;justify-content:flex-end;gap:8px;margin-top:4px;padding-top:5px;border-top:1px solid #edf1f4}.workflow-node footer button{padding:0;border:0;background:transparent;color:#1769aa;font-size:9px}.workflow-node footer button:disabled{color:#9ba7b2}.workflow-connector{position:absolute;z-index:1;height:2px;background:#8fa4b8;transform-origin:left center;pointer-events:none}.workflow-connector::after{position:absolute;right:-4px;top:-8px;color:#8fa4b8;font-size:16px;content:'›'}.workflow-connector.compensation{height:1px;background:#d0913d}.workflow-connector.compensation::after{color:#b57525}.workflow-edge-label{position:absolute;z-index:3;padding:2px 5px;border:1px solid #edc378;border-radius:8px;background:#fffaf1;color:#8d5b18;font-size:8px;transform:translate(-50%,-50%);pointer-events:none}.workflow-inspector{display:flex;flex-direction:column;gap:12px;padding:12px;border-left:1px solid #dce5eb;background:#fff;min-width:0}.workflow-inspector header{display:flex;justify-content:space-between;gap:8px;align-items:flex-start}.workflow-inspector header>div{display:grid;gap:4px;min-width:0}.workflow-inspector header span,.workflow-inspector small{color:#657487;font-size:9px;line-height:1.5}.workflow-inspector header b{font-size:12px;overflow-wrap:anywhere}.workflow-inspector button{padding:5px 7px;border:1px solid #cbd8e1;border-radius:6px;background:#fff;color:#1769aa;font-size:9px}.workflow-inspector dl{display:grid;gap:8px;margin:0}.workflow-inspector dl>div{display:grid;gap:3px;padding-bottom:7px;border-bottom:1px solid #edf1f4}.workflow-inspector dt{color:#657487;font-size:9px}.workflow-inspector dd{margin:0;font-size:10px;overflow-wrap:anywhere}.workflow-inspector section{display:grid;gap:7px;padding:10px;border:1px solid #dce5eb;border-radius:8px;background:#f9fbfc}.workflow-inspector section p{margin:0;color:#a43131;font-size:10px}.workflow-inspector section p.passed{color:#267249}.workflow-table-wrap{height:500px;border:1px solid #d8e2e9;border-radius:8px;background:#fff;overflow:auto}.workflow-table-wrap table{width:100%;border-collapse:collapse;font-size:10px}.workflow-table-wrap th,.workflow-table-wrap td{padding:10px;border-bottom:1px solid #e5ebef;text-align:left;white-space:nowrap}.workflow-table-wrap td:first-child{display:grid;gap:3px}.workflow-table-wrap td button{margin-right:8px;border:0;background:transparent;color:#1769aa}.workflow-transitions{margin-top:9px;border:1px solid #dce5eb;border-radius:8px;background:#fff}.workflow-transitions summary{padding:9px 11px;color:#294052;font-size:10px;font-weight:700;cursor:pointer}.workflow-transitions .edges{margin:0;border:0;border-top:1px solid #e1e8ed;border-radius:0}.workflow-transitions[open]{max-height:330px;overflow:auto}

.runtime-console{min-width:0}.runtime-grid{display:grid;grid-template-columns:minmax(260px,.8fr) minmax(320px,1.2fr);gap:14px}.runtime-grid>section,.runtime-history{min-width:0;padding:13px;border:1px solid #dce5eb;border-radius:9px;background:#fff}.runtime-grid h3,.runtime-history h3{margin:0 0 10px;font-size:13px}.runtime-grid label{display:grid;gap:5px;margin-bottom:10px;color:#536477;font-size:10px}.runtime-grid textarea,.runtime-grid select{width:100%;box-sizing:border-box;border:1px solid #cbd8e1;border-radius:7px;background:#fbfcfd;color:#26394b;padding:9px;font:11px/1.55 ui-monospace,SFMono-Regular,Menlo,monospace;resize:vertical}.runtime-result dl{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;margin:0 0 10px}.runtime-result dl>div{display:grid;gap:3px;min-width:0;padding:8px;border-radius:7px;background:#f6f9fb}.runtime-result dt{color:#687789;font-size:9px}.runtime-result dd{margin:0;font-size:10px;overflow-wrap:anywhere}.runtime-result pre{max-height:260px;margin:0;padding:10px;border-radius:7px;background:#152536;color:#dcecf7;font-size:10px;line-height:1.5;white-space:pre-wrap;overflow:auto}.runtime-history>header{display:flex;align-items:center;justify-content:space-between;gap:10px}.runtime-table{max-width:100%;overflow:auto}.runtime-table table{width:100%;border-collapse:collapse;font-size:10px}.runtime-table th,.runtime-table td{padding:8px;border-bottom:1px solid #e5ebef;text-align:left;white-space:nowrap}.runtime-table code{font-size:9px}

.dialog-check-row{display:flex;flex-wrap:wrap;gap:8px 16px;margin-top:8px}.dialog-check{display:flex;align-items:center;gap:7px;min-height:30px}
.bc-shell{min-height:clamp(590px,calc(100vh - 220px),680px)}
.stage{min-height:clamp(470px,calc(100vh - 350px),550px)}
.workflow-stage{min-height:clamp(500px,calc(100vh - 330px),570px)}
.workflow-graph-scroll{position:relative;height:clamp(410px,calc(100vh - 400px),500px);contain:paint}
.workflow-inspector{position:relative;z-index:1}
.workflow-table-wrap{height:clamp(410px,calc(100vh - 400px),500px)}

@media(max-width:1220px){.bc-shell{grid-template-columns:190px minmax(0,1fr)}.workflow-stage{grid-template-columns:118px minmax(330px,1fr) 190px}.workflow-toolbar>div:last-child button:nth-last-child(2){display:none}.scope-table>article{grid-template-columns:1fr .7fr 1fr auto}.scope-table>article small{display:none}}
@media(max-width:1050px){.meta{grid-template-columns:1fr 1fr}.scope-table>article{grid-template-columns:1fr 1fr 1fr}.stage{grid-template-columns:126px minmax(0,1fr)}.workflow-stage{grid-template-columns:118px minmax(330px,1fr)}.workflow-inspector{grid-column:1/-1;display:grid;grid-template-columns:1fr 1.4fr 1fr;align-items:start;border-top:1px solid #dce5eb;border-left:0}.workflow-inspector header{grid-column:1}.workflow-inspector dl{grid-column:2;grid-template-columns:repeat(2,1fr)}.workflow-inspector section{grid-column:3}.workflow-inspector section:last-child{display:none}}
@media(max-width:760px){.page-head{display:grid}.bc-shell{grid-template-columns:1fr}.ledger{max-height:260px;border-right:0;border-bottom:1px solid #d9e2e9}.stage,.workflow-stage,.runtime-grid{grid-template-columns:1fr}.tools{border-right:0;border-bottom:1px solid #dce5eb}.workflow-tools{display:grid;grid-template-columns:repeat(2,minmax(0,1fr))}.workflow-tools>b,.workflow-tools small{grid-column:1/-1}.workflow-inspector{display:grid;grid-template-columns:1fr;border-left:0}.workflow-inspector header,.workflow-inspector dl,.workflow-inspector section{grid-column:1}.workflow-graph-scroll,.workflow-table-wrap{height:440px}.field-grid,.rule-list{grid-template-columns:1fr}.meta{grid-template-columns:1fr}.scope-table>article{grid-template-columns:1fr 1fr}.runtime-impact{align-items:flex-start}.head-actions{justify-content:flex-start}.runtime-result dl{grid-template-columns:1fr}}
</style>
