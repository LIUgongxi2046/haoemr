<script setup lang="ts">
import { isOpenClinicalTask } from '../pending-work';
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref, watch } from 'vue';
import { RouterLink, useRoute, useRouter } from 'vue-router';
import type {
  ClinicalTaskCollaboratorWire, ClinicalTaskNotificationWire, ClinicalTaskTeamQueueWire, ClinicalTaskWire, ConfigurationItemWire,
} from '../../generated/contracts';
import {
  clinicalContext, collaborateClinicalTask, commandClinicalTask, createClinicalTaskNotification,
  enqueueClinicalTask, getClinicalTaskDetail, issueClinicalTaskLease, issueContextLease, issueInpatientLease,
  listEligibleClinicalTaskCollaborators,
  listClinicalTaskNotifications, listClinicalTasks, listClinicalTaskTeamQueue,
  loadInpatientPathwayWorkspace, recoverClinicalTaskNotifications, transitionClinicalTaskNotification,
  transitionClinicalTaskTeamQueue,
} from '../../clinical-api';
import type { ClinicalTaskMode } from '../../clinical-api';
import {
  defineConfiguration, issueConfigurationLease, listConfigurations, transitionConfiguration, updateConfiguration,
} from '../../api/config';
import AdminActionDialog from '../components/AdminActionDialog.vue';
import BusinessActionDialog from '../components/BusinessActionDialog.vue';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';

type ViewKey = 'overview' | 'team' | 'collaboration' | 'notifications' | 'pathway' | 'rules';
type ConfigKind = 'CLINICAL_TASK_RULE' | 'CLINICAL_PATHWAY';
type CollaborationAction = 'delegations' | 'transfers' | 'escalations';

const route = useRoute();
const router = useRouter();
const taskViews: ViewKey[] = ['overview', 'team', 'collaboration', 'notifications', 'pathway', 'rules'];
const activeView = computed<ViewKey>(() => {
  const requestedView = typeof route.query.view === 'string' ? route.query.view : 'overview';
  return taskViews.includes(requestedView as ViewKey) ? requestedView as ViewKey : 'overview';
});
const isConfigurationView = computed(() => activeView.value === 'pathway' || activeView.value === 'rules');
const taskModes: ClinicalTaskMode[] = ['outpatient', 'emergency', 'inpatient'];
const initialTaskMode = typeof route.query.mode === 'string' && taskModes.includes(route.query.mode as ClinicalTaskMode)
  ? route.query.mode as ClinicalTaskMode : 'inpatient';
const mode = ref<ClinicalTaskMode>(initialTaskMode);
const busy = ref<string | null>(null);
const notice = ref('');
const risk = ref<'ALL' | ClinicalTaskWire['risk_level']>('ALL');
const search = ref('');
const includeExpired = ref(false);
const sourceFilter = computed(() => typeof route.query.source === 'string' ? route.query.source : '');
const selectedTaskId = ref('');
const taskDialog = ref<'process' | 'collaborate' | 'enqueue' | 'notification' | null>(null);
const queueDialog = ref<{ action: 'claim' | 'complete' | 'withdraw'; item: ClinicalTaskTeamQueueWire } | null>(null);
const notificationDialog = ref<{ action: 'deliver' | 'fail' | 'recover'; item?: ClinicalTaskNotificationWire } | null>(null);
const configDialog = ref<'create' | 'edit' | 'archive' | null>(null);
const selectedConfig = ref<ConfigurationItemWire | null>(null);
const collaboration = reactive({
  action: 'delegations' as CollaborationAction,
  targetUserId: '', validUntil: defaultDelegationUntil(), reason: '临床班次与时限要求',
});
const notificationForm = reactive({ kind: 'CREATED' as ClinicalTaskNotificationWire['kind'], channel: 'IN_APP' as ClinicalTaskNotificationWire['channel'], scheduledAt: '' });
const failureReason = ref('院内消息通道暂时不可用，已转入可恢复队列。');
const configForm = reactive({
  kind: 'CLINICAL_TASK_RULE' as ConfigKind, key: '', name: '', specialty: '心血管内科', diagnosis: 'I50.9',
  taskType: '危急值处置', riskLevel: 'CRITICAL', dueMinutes: 15, escalationMinutes: 5,
  versionNo: 1, admissionCriteria: '主要诊断符合标准路径，已排除绝对禁忌证并完成患者知情。',
});

const tasksQuery = useQuery({
  queryKey: ['clinical', 'tasks-center', mode, includeExpired, computed(() => [clinicalContext.userId, clinicalContext.roleId, clinicalContext.facilityId, clinicalContext.patientId, clinicalContext.encounterId, clinicalContext.emergencyPatientId, clinicalContext.emergencyEncounterId, clinicalContext.inpatientPatientId, clinicalContext.inpatientEncounterId])],
  queryFn: async () => {
    const lease = await issueClinicalTaskLease(mode.value);
    return { lease, tasks: await listClinicalTasks(lease, mode.value, includeExpired.value) };
  }, enabled: () => !isConfigurationView.value, retry: false, staleTime: 0, gcTime: 0,
});
const queueQuery = useQuery({
  queryKey: ['clinical', 'task-team-queue'],
  queryFn: async () => {
    const lease = await issueContextLease(null, null, 'CLINICAL_TASK_WORKFLOW');
    return { lease, items: await listClinicalTaskTeamQueue(lease) };
  }, enabled: () => !isConfigurationView.value, retry: false, staleTime: 0, gcTime: 0,
});
const pathwayQuery = useQuery({
  queryKey: ['clinical', 'task-center-pathway'],
  queryFn: async () => {
    const lease = await issueInpatientLease();
    return { lease, workspace: await loadInpatientPathwayWorkspace(lease) };
  }, enabled: () => !isConfigurationView.value, retry: false, staleTime: 0, gcTime: 0,
});
const configQuery = useQuery({
  queryKey: ['clinical', 'task-center-config'],
  queryFn: async () => {
    const lease = await issueConfigurationLease();
    const [rules, pathways] = await Promise.all([
      listConfigurations(lease, 'CLINICAL_TASK_RULE'), listConfigurations(lease, 'CLINICAL_PATHWAY'),
    ]);
    return { lease, rules, pathways };
  }, enabled: isConfigurationView, retry: false, staleTime: 0, gcTime: 0,
});
const notificationsQuery = useQuery({
  queryKey: ['clinical', 'task-notifications', mode, selectedTaskId],
  queryFn: async () => {
    if (!tasksQuery.data.value || !selectedTaskId.value) return [];
    return listClinicalTaskNotifications(tasksQuery.data.value.lease, mode.value, selectedTaskId.value);
  }, enabled: computed(() => !isConfigurationView.value && Boolean(tasksQuery.data.value && selectedTaskId.value)), retry: false, staleTime: 0, gcTime: 0,
});
const collaboratorsQuery = useQuery({
  queryKey: ['clinical', 'task-collaborators', mode],
  queryFn: async () => {
    if (!tasksQuery.data.value) return [];
    return listEligibleClinicalTaskCollaborators(tasksQuery.data.value.lease, mode.value);
  }, enabled: computed(() => !isConfigurationView.value && Boolean(tasksQuery.data.value)), retry: false, staleTime: 0, gcTime: 0,
});
const detailQuery = useQuery({
  queryKey: ['clinical', 'task-detail', mode, selectedTaskId],
  queryFn: async () => {
    if (!tasksQuery.data.value || !selectedTaskId.value) return null;
    return getClinicalTaskDetail(tasksQuery.data.value.lease, mode.value, selectedTaskId.value);
  }, enabled: computed(() => !isConfigurationView.value && Boolean(tasksQuery.data.value && selectedTaskId.value)), retry: false, staleTime: 0, gcTime: 0,
});

const pagePending = computed(() => isConfigurationView.value ? configQuery.isPending.value : tasksQuery.isPending.value);
const issue = computed(() => {
  const error = isConfigurationView.value ? configQuery.error.value : tasksQuery.error.value;
  return error ? toClinicalIssue(error) : null;
});
function reloadPage() {
  if (isConfigurationView.value) void configQuery.refetch();
  else void tasksQuery.refetch();
}
const tasks = computed(() => tasksQuery.data.value?.tasks ?? []);
const selectedTask = computed(() => tasks.value.find((task) => task.task_id === selectedTaskId.value) ?? tasks.value[0] ?? null);
const collaborators = computed<ClinicalTaskCollaboratorWire[]>(() => collaboratorsQuery.data.value ?? []);
const selectedCollaborator = computed(() => collaborators.value.find((item) => item.user_id === collaboration.targetUserId) ?? null);
const collaborationActions = computed(() => selectedTask.value ? allowedCollaborationActions(selectedTask.value) : []);
const filtered = computed(() => tasks.value.filter((task) => {
  const needle = search.value.trim().toLowerCase();
  return (risk.value === 'ALL' || task.risk_level === risk.value)
    && (!sourceFilter.value || task.source_type === sourceFilter.value)
    && (!needle || `${task.title} ${task.task_type} ${task.source_type} ${task.business_state}`.toLowerCase().includes(needle));
}));
const openTasks = computed(() => tasks.value.filter(isOpenClinicalTask));
const highRisk = computed(() => openTasks.value.filter((task) => ['CRITICAL', 'HIGH'].includes(task.risk_level)));
const overdue = computed(() => openTasks.value.filter(isOverdue));
const pathwayInstance = computed(() => pathwayQuery.data.value?.workspace.instance ?? null);
const currentStage = computed(() => pathwayInstance.value?.stages.find((stage) => stage.stage_code === pathwayInstance.value?.current_stage_code));
const configKindLabel = computed(() => configForm.kind === 'CLINICAL_PATHWAY' ? '临床路径版本' : '任务规则');
const activeAgentContext = computed(() => mode.value === 'inpatient'
  ? { patientId: clinicalContext.inpatientPatientId, encounterId: clinicalContext.inpatientEncounterId }
  : mode.value === 'emergency'
    ? { patientId: clinicalContext.emergencyPatientId, encounterId: clinicalContext.emergencyEncounterId }
    : { patientId: clinicalContext.patientId, encounterId: clinicalContext.encounterId });
const selectedTaskAgentRoute = computed(() => ({
  path: '/ai-assistant',
  query: {
    patient_id: activeAgentContext.value.patientId,
    encounter_id: activeAgentContext.value.encounterId,
    target_type: 'TASK',
    target_id: selectedTask.value?.task_id ?? '',
    objective: selectedTask.value
      ? `请只读核对任务「${selectedTask.value.title}」的来源、责任、时限、规则命中与通知证据，输出人工处置候选建议，不得自动完成临床任务。`
      : '',
  },
}));

watch(tasks, (items) => {
  if (!items.some((item) => item.task_id === selectedTaskId.value)) selectedTaskId.value = items[0]?.task_id ?? '';
}, { immediate: true });
watch(() => route.query.mode, (requestedMode) => {
  mode.value = typeof requestedMode === 'string' && taskModes.includes(requestedMode as ClinicalTaskMode)
    ? requestedMode as ClinicalTaskMode : 'inpatient';
}, { immediate: true });
watch(mode, (nextMode) => {
  if (route.query.mode !== nextMode) void router.replace({ query: { ...route.query, mode: nextMode } });
});
watch(collaborators, (items) => {
  if (!items.some((item) => item.user_id === collaboration.targetUserId)) collaboration.targetUserId = items[0]?.user_id ?? '';
}, { immediate: true });
watch(activeView, () => {
  notice.value = '';
  taskDialog.value = null;
  queueDialog.value = null;
  notificationDialog.value = null;
  configDialog.value = null;
});

function chooseView(view: ViewKey) {
  notice.value = '';
  void router.push({ name: 'clinical-tasks', query: { ...route.query, view } });
}
function riskLabel(value: ClinicalTaskWire['risk_level']) { return value === 'CRITICAL' ? '危急' : value === 'HIGH' ? '高风险' : '常规'; }
function stateLabel(value: string) { return ({ PENDING: '待处理', ASSIGNED: '已分派', DELIVERED: '已送达', VIEWED: '已查看', CLAIMED: '已接手', IN_PROGRESS: '处理中', COMPLETED: '已完成', WITHDRAWN: '已撤回', EXPIRED: '已过期', ESCALATED: '已升级', ENQUEUED: '已入队', FAILED: '投递失败' } as Record<string, string>)[value] || value; }
function pathwayRuleCount(item: ConfigurationItemWire, key: string) { const value = (item.payload as Record<string, unknown> | undefined)?.[key]; return Array.isArray(value) ? value.length : 0; }
function pathwayRequiredTaskCount(item: ConfigurationItemWire) { const stages = (item.payload as { stages?: Array<{ tasks?: Array<{ required?: boolean } | unknown[]> }> } | undefined)?.stages ?? []; return stages.flatMap((stage) => stage.tasks ?? []).filter((task) => Array.isArray(task) ? task[4] !== false : task.required !== false).length; }
function isAssignee(task: ClinicalTaskWire) { return !task.assigned_user_id || task.assigned_user_id === clinicalContext.userId; }
function allowedCollaborationActions(task: ClinicalTaskWire): CollaborationAction[] {
  if (task.claimed_by !== clinicalContext.userId) return [];
  if (task.state === 'CLAIMED') return ['delegations', 'transfers', 'escalations'];
  if (task.state === 'IN_PROGRESS') return ['escalations'];
  return [];
}
function collaborationActionLabel(action: typeof collaboration.action) {
  return action === 'delegations' ? '限时委托' : action === 'transfers' ? '转派责任' : '风险升级';
}
function canCollaborate(task: ClinicalTaskWire) { return allowedCollaborationActions(task).length > 0; }
function openCollaboration(task = selectedTask.value) {
  if (!task) return;
  selectedTaskId.value = task.task_id;
  const actions = allowedCollaborationActions(task);
  if (!actions.length) return;
  if (!actions.includes(collaboration.action)) collaboration.action = actions[0];
  taskDialog.value = 'collaborate';
}
function isOverdue(task: ClinicalTaskWire) { return Boolean(task.due_at && new Date(task.due_at).getTime() < Date.now() && !['COMPLETED', 'WITHDRAWN', 'EXPIRED'].includes(task.state)); }
function formatDate(value?: string | null) { return value ? new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value)) : '按来源业务'; }
function taskTitle(id: string) { return tasks.value.find((task) => task.task_id === id)?.title ?? `任务 …${id.slice(-8)}`; }
function defaultDelegationUntil() {
  const date = new Date(Date.now() + 8 * 60 * 60 * 1000);
  return new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
}

async function run(key: string, action: () => Promise<void>, success: string) {
  if (busy.value) return;
  busy.value = key; notice.value = '';
  try { await action(); notice.value = success; }
  catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = null; }
}

async function processTask(action: 'views' | 'claims') {
  if (!selectedTask.value || !tasksQuery.data.value) return;
  await run(`task:${action}`, async () => {
    await commandClinicalTask(tasksQuery.data.value!.lease, mode.value, selectedTask.value!, action);
    taskDialog.value = null; await tasksQuery.refetch();
  }, action === 'views' ? '已查看通知；任务终态仍由来源业务确认。' : '已接手任务并写入责任轨迹。');
}
async function submitCollaboration() {
  if (!selectedTask.value || !tasksQuery.data.value || !collaboration.targetUserId) return;
  if (!allowedCollaborationActions(selectedTask.value).includes(collaboration.action)) {
    notice.value = '当前任务状态不允许该责任操作，请刷新后重试。';
    return;
  }
  await run('collaborate', async () => {
    const validUntil = collaboration.action === 'delegations' ? new Date(collaboration.validUntil).toISOString() : null;
    await collaborateClinicalTask(tasksQuery.data.value!.lease, mode.value, selectedTask.value!, collaboration.action, collaboration.targetUserId, collaboration.reason.trim(), validUntil);
    taskDialog.value = null; await Promise.all([tasksQuery.refetch(), detailQuery.refetch()]);
  }, collaboration.action === 'delegations' ? `已限时委托给${selectedCollaborator.value?.display_name ?? '目标人员'}。` : collaboration.action === 'transfers' ? '已转派且保留原责任链。' : '已升级到协作人员。');
}
async function submitEnqueue() {
  if (!selectedTask.value || !queueQuery.data.value) return;
  await run('enqueue', async () => {
    await enqueueClinicalTask(queueQuery.data.value!.lease, selectedTask.value!.task_id);
    taskDialog.value = null; await queueQuery.refetch();
  }, '任务已进入心血管内科团队队列。');
}
async function submitQueueAction() {
  if (!queueDialog.value || !queueQuery.data.value) return;
  const { action, item } = queueDialog.value;
  const endpoint = action === 'claim' ? 'claims' : action === 'complete' ? 'completions' : 'withdrawals';
  await run(`queue:${action}`, async () => {
    await transitionClinicalTaskTeamQueue(queueQuery.data.value!.lease, item, endpoint);
    queueDialog.value = null; await queueQuery.refetch();
  }, action === 'withdraw' ? '队列项已可审计撤回，不删除临床任务事实。' : action === 'claim' ? '已从团队队列领取任务。' : '队列协作已闭环。');
}
async function submitNotification() {
  if (!selectedTask.value || !tasksQuery.data.value) return;
  await run('notification:create', async () => {
    await createClinicalTaskNotification(tasksQuery.data.value!.lease, mode.value, {
      taskId: selectedTask.value!.task_id, recipientUserId: clinicalContext.collaboratorUserId,
      kind: notificationForm.kind, channel: notificationForm.channel,
      scheduledAt: notificationForm.scheduledAt ? new Date(notificationForm.scheduledAt).toISOString() : null,
    });
    taskDialog.value = null; await notificationsQuery.refetch();
  }, '任务通知已进入实际投递队列。');
}
async function submitNotificationAction() {
  if (!notificationDialog.value || !tasksQuery.data.value || !selectedTask.value) return;
  const action = notificationDialog.value.action;
  await run(`notification:${action}`, async () => {
    if (action === 'recover') await recoverClinicalTaskNotifications(tasksQuery.data.value!.lease, mode.value, selectedTask.value!.task_id);
    else await transitionClinicalTaskNotification(tasksQuery.data.value!.lease, mode.value, notificationDialog.value!.item!, action === 'deliver' ? 'deliveries' : 'failures', failureReason.value.trim());
    notificationDialog.value = null; await notificationsQuery.refetch();
  }, action === 'recover' ? '失败通知已恢复为待投递。' : action === 'deliver' ? '通知已送达，不等同于业务完成。' : '投递失败原因已留痕。');
}

function resetConfigForm(kind: ConfigKind, item?: ConfigurationItemWire) {
  configForm.kind = kind;
  configForm.key = item?.config_key ?? (kind === 'CLINICAL_PATHWAY' ? `hf-standard-v${(configQuery.data.value?.pathways.length ?? 0) + 2}` : `task-sla-${Date.now().toString().slice(-6)}`);
  configForm.name = item?.display_name ?? (kind === 'CLINICAL_PATHWAY' ? '心力衰竭标准临床路径' : '危急值逐级升级规则');
  const payload = item?.payload as Record<string, unknown> | undefined;
  configForm.specialty = String(payload?.specialty_code ?? '心血管内科');
  configForm.diagnosis = String(payload?.diagnosis_code ?? 'I50.9');
  configForm.taskType = String(payload?.task_type ?? '危急值处置');
  configForm.riskLevel = String(payload?.risk_level ?? 'CRITICAL');
  configForm.dueMinutes = Number(payload?.due_minutes ?? 15);
  configForm.escalationMinutes = Number(payload?.escalation_minutes ?? 5);
  configForm.versionNo = Number(payload?.version_no ?? 1);
  configForm.admissionCriteria = String(payload?.admission_criteria ?? '主要诊断符合标准路径，已排除绝对禁忌证并完成患者知情。');
}
function openCreateConfig(kind: ConfigKind) { selectedConfig.value = null; resetConfigForm(kind); configDialog.value = 'create'; }
function openEditConfig(item: ConfigurationItemWire) { selectedConfig.value = item; resetConfigForm(item.config_type as ConfigKind, item); configDialog.value = item.status === 'DRAFT' ? 'edit' : 'create'; if (item.status !== 'DRAFT') { configForm.key = `${item.config_key}-v${item.row_version + 1}`; configForm.versionNo += 1; } }
function openArchiveConfig(item: ConfigurationItemWire) { selectedConfig.value = item; configDialog.value = 'archive'; }
function configPayload() {
  return configForm.kind === 'CLINICAL_PATHWAY' ? {
    schema_version: 1, pathway_code: configForm.key.trim().toUpperCase(), specialty_code: configForm.specialty.trim(), diagnosis_code: configForm.diagnosis.trim(), version_no: configForm.versionNo,
    admission_criteria: configForm.admissionCriteria.trim(),
    entry_rules: ['主要诊断与路径病种匹配', '完成严重度、禁忌证和患者意愿评估'],
    exclusion_rules: ['主要诊断发生变更', '存在需个体化治疗的重大禁忌证'],
    stages: [
      { code: 'ADMISSION_ASSESSMENT', name: '入院评估', days: '0-1', tasks: [{ code: 'ADMISSION_NOTE', name: '入院记录与首次病程', source_type: 'DOCUMENT_TASK', source_key: 'DOC.ADMISSION', required: true }, { code: 'BASELINE_TESTS', name: '基线检查检验', source_type: 'ORDER_ITEM', source_key: 'ORDER.BASELINE', required: true }] },
      { code: 'DIAGNOSIS_TREATMENT', name: '诊断治疗与监测', days: '1-7', tasks: [{ code: 'TREATMENT_PLAN', name: '诊疗计划记录', source_type: 'DOCUMENT_TASK', source_key: 'DOC.TREATMENT_PLAN', required: true }, { code: 'REASSESSMENT', name: '疗效与风险再评估', source_type: 'DOCUMENT_TASK', source_key: 'DOC.REASSESSMENT', required: true }] },
      { code: 'DISCHARGE_PREPARATION', name: '稳定与出院准备', days: '3-14', tasks: [{ code: 'DISCHARGE_RECORD', name: '出院记录与用药重整', source_type: 'DOCUMENT_TASK', source_key: 'DOC.DISCHARGE', required: true }, { code: 'FOLLOWUP_PLAN', name: '随访计划', source_type: 'DOCUMENT_TASK', source_key: 'DOC.FOLLOWUP', required: true }] },
    ],
    variance_rules: ['禁忌证', '资源不可用', '患者拒绝', '诊断变更', '必做任务失败', '其他需审核原因'],
    completion_rules: ['所有必做任务有权威来源证据', '不存在未审核变异', '出院与随访计划完整'],
    exit_rules: ['诊断变更或重大禁忌证经独立审核后退出', '退出保留原版本执行证据，重入创建新实例'],
    publication_scope: '江城大学附属医院本部', version_immutable_after_publish: true,
  } : {
    schema_version: 1, task_type: configForm.taskType.trim(), risk_level: configForm.riskLevel,
    due_minutes: configForm.dueMinutes, escalation_minutes: configForm.escalationMinutes,
    assignment_strategy: '患者主管医师 → 医疗组 → 科主任', completion_source: '权威业务对象终态',
    channels: ['IN_APP', 'OUTBOX'], applies_to: ['门诊', '急诊', '住院'], enabled: true,
  };
}
async function submitConfigDialog() {
  if (!configQuery.data.value || !configDialog.value) return;
  const operation = configDialog.value;
  await run('config', async () => {
    const lease = configQuery.data.value!.lease;
    if (operation === 'archive' && selectedConfig.value) {
      await transitionConfiguration(lease, selectedConfig.value.config_id, { action: 'ARCHIVE', expected_version: selectedConfig.value.row_version, reason: '停用当前配置并保留历史流程与审计证据' });
    } else if (operation === 'edit' && selectedConfig.value) {
      await updateConfiguration(lease, selectedConfig.value.config_id, { display_name: configForm.name.trim(), payload: configPayload(), expected_version: selectedConfig.value.row_version });
    } else {
      await defineConfiguration(lease, { config_type: configForm.kind, config_key: configForm.key.trim(), display_name: configForm.name.trim(), payload: configPayload() });
    }
    configDialog.value = null; await configQuery.refetch();
  }, operation === 'archive' ? '配置已归档停用，新流程不再引用，历史证据保留。' : '配置草案已保存；通过校验、独立审批与发布后影响新流程。');
}
function lifecycleReason(action: 'VALIDATE' | 'SUBMIT' | 'APPROVE' | 'PUBLISH') {
  return ({
    VALIDATE: '上线前执行完整配置契约校验',
    SUBMIT: '提交临床与质控独立审批流程',
    APPROVE: '独立审批人核对任务时限、升级规则与影响范围',
    PUBLISH: '审批完成后发布配置，仅影响后续新任务',
  } as const)[action];
}
function lifecycleSuccess(action: 'VALIDATE' | 'SUBMIT' | 'APPROVE' | 'PUBLISH') {
  return ({
    VALIDATE: '配置校验已执行。',
    SUBMIT: '配置已提交独立审批。',
    APPROVE: '独立审批已完成，可发布到后续新流程。',
    PUBLISH: '配置已发布，仅影响后续新流程。',
  } as const)[action];
}
async function lifecycle(item: ConfigurationItemWire, action: 'VALIDATE' | 'SUBMIT' | 'APPROVE' | 'PUBLISH') {
  if (!configQuery.data.value) return;
  await run(`config:${action}`, async () => {
    await transitionConfiguration(configQuery.data.value!.lease, item.config_id, { action, expected_version: item.row_version, reason: lifecycleReason(action) });
    await configQuery.refetch();
  }, lifecycleSuccess(action));
}
</script>

<template>
  <section data-page-root class="content vue-native-page task-center-page">
    <div class="page-head task-center-heading">
      <div class="page-title"><h1>{{ activeView === 'pathway' ? '临床路径配置' : activeView === 'rules' ? '任务规则' : '任务中心' }}</h1><p>{{ activeView === 'pathway' || activeView === 'rules' ? '业务配置 · 管理临床路径版本、任务规则与发布审批' : '跨门诊、急诊、住院汇聚风险、责任、时限与路径证据；正文仍在权威来源页实时鉴权' }}</p></div>
      <div v-if="!isConfigurationView" class="head-actions"><button class="button" type="button" :disabled="!selectedTask" @click="chooseView('collaboration')">我的委托</button></div>
    </div>
    <div v-if="!isConfigurationView" class="metric-grid task-center-metrics" aria-label="任务与路径指标"><div class="metric"><div class="name">当前就诊待处理</div><div class="value">{{ openTasks.length }}</div><div class="trend" :class="{ 'danger-text': overdue.length }">{{ overdue.length }} 项已逾期</div></div><div class="metric"><div class="name">危急 / 高风险</div><div class="value danger-text">{{ highRisk.length }}</div><div class="trend">必须回权威来源闭环</div></div><div class="metric"><div class="name">团队队列</div><div class="value">{{ queueQuery.data.value?.items.filter((item) => !['COMPLETED','WITHDRAWN'].includes(item.queue_status)).length ?? 0 }}</div><div class="trend">领取与撤回保留责任链</div></div><div class="metric"><div class="name">当前路径</div><div class="value">{{ pathwayInstance?.completion_percent ?? 0 }}%</div><div class="trend">{{ pathwayInstance ? `${pathwayInstance.display_name} v${pathwayInstance.version_no}` : '待入径评估' }}</div></div></div>
    <div v-if="notice" class="inline-notice" :class="{ error: notice.includes('：') }" role="status">{{ notice }}</div><ClinicalPageState v-if="pagePending" kind="loading" :message="isConfigurationView ? '正在加载业务配置' : '正在汇聚当前工作域任务'" /><ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reloadPage" />
    <template v-else>
      <nav v-if="activeView === 'overview'" class="task-domain-nav" aria-label="任务工作域和智能协作">
        <div role="group" aria-label="任务工作域">
          <button v-for="item in ([['outpatient','门诊'],['emergency','急诊'],['inpatient','住院']] as const)" :key="item[0]" type="button" class="button sm" :class="{ primary: mode === item[0] }" @click="mode = item[0]">{{ item[1] }}</button>
        </div>
        <RouterLink v-if="selectedTask" class="button sm" :to="selectedTaskAgentRoute">交给 AI医助 EVA 核对当前任务</RouterLink>
      </nav>
      <section v-if="activeView === 'overview'" class="task-overview-grid"><div class="card task-table-card"><div class="task-filters"><label>工作域<select v-model="mode"><option value="outpatient">门诊</option><option value="emergency">急诊</option><option value="inpatient">住院</option></select></label><label>风险<select v-model="risk"><option value="ALL">全部风险</option><option value="CRITICAL">危急</option><option value="HIGH">高风险</option><option value="ROUTINE">常规</option></select></label><label class="task-history-filter"><input v-model="includeExpired" type="checkbox" />显示已过期历史</label><label class="task-search">搜索<input v-model="search" placeholder="患者 / 任务 / 路径" /></label><button class="button" @click="tasksQuery.refetch()">筛选并刷新</button></div><div class="task-table-wrap"><table class="table task-center-table"><thead><tr><th>类型</th><th>任务</th><th>业务状态</th><th>当前责任</th><th>时限</th><th>操作</th></tr></thead><tbody><tr v-for="task in filtered" :key="task.task_id" :class="{ selected: selectedTask?.task_id === task.task_id }" @click="selectedTaskId = task.task_id"><td><span class="task-risk" :class="task.risk_level.toLowerCase()">{{ riskLabel(task.risk_level) }}</span></td><td><b>{{ task.title }}</b><small>{{ task.task_type }} · {{ task.source_type }} …{{ task.source_id.slice(-8) }}</small></td><td><span class="task-state" :class="task.state.toLowerCase()">{{ stateLabel(task.state) }}</span><small>{{ task.business_state }}</small></td><td>{{ task.claimed_by ? `已接手 …${task.claimed_by.slice(-6)}` : task.assigned_user_id ? `已分派 …${task.assigned_user_id.slice(-6)}` : '本人或医疗组' }}</td><td :class="{ 'danger-text': isOverdue(task) }">{{ formatDate(task.due_at) }}</td><td><button class="button sm" type="button" @click.stop="selectedTaskId = task.task_id; taskDialog = 'process'">{{ task.state === 'EXPIRED' ? '查看' : '处理' }}</button></td></tr></tbody></table><div v-if="!filtered.length" class="clinical-empty-state">当前筛选范围无可显示任务；可勾选“显示已过期历史”查看到期后的责任与审计证据。</div></div><div class="task-state-notice"><b>任务状态机</b><span>待分派 → 已查看 → 已接手 → 委托/转派/升级 → 由来源业务确认完成。已过期项仅供追溯，不会重新成为有效工作项。</span></div></div><aside class="card current-path-card"><header><div><span>当前住院路径</span><h2>{{ pathwayInstance?.display_name ?? '待入径评估' }}</h2></div><span class="status amber">{{ pathwayInstance ? `v${pathwayInstance.version_no}` : '无实例' }}</span></header><div v-if="pathwayInstance" class="path-card-body"><dl><div><dt>当前阶段</dt><dd>{{ currentStage?.display_name }}</dd></div><div><dt>路径完成度</dt><dd>{{ pathwayInstance.completed_task_count }} / {{ pathwayInstance.required_task_count }} · {{ pathwayInstance.completion_percent }}%</dd></div><div><dt>版本事实</dt><dd>入径时固定 v{{ pathwayInstance.version_no }}</dd></div><div><dt>待审变异</dt><dd>{{ pathwayInstance.variances.filter((item) => item.status === 'REQUESTED').length }} 项</dd></div></dl><h3>{{ currentStage?.display_name }}任务</h3><article v-for="task in currentStage?.tasks" :key="task.pathway_task_id"><span>{{ task.display_name }}</span><b :class="task.state.toLowerCase()">{{ task.state === 'COMPLETED' ? '已完成' : task.state === 'WAIVED' ? '已审批豁免' : '待来源证据' }}</b></article><div class="pathway-rule-note"><b>退出 / 重入规则</b><span>退出不删除既有路径事实；重入创建新实例，不回写旧版本。</span></div><RouterLink class="button primary" to="/ip-pathway">进入路径执行中心</RouterLink></div><div v-else class="clinical-empty-state">当前患者尚未入径，可在住院路径执行中心评估。</div></aside></section>
      <section v-else-if="activeView === 'team'" class="task-module-card card"><header><div><p class="eyebrow">团队队列</p><h2>心血管内科团队队列</h2><p>入队、领取、闭环和撤回都写入证据链；终态临床任务不可入队。</p></div><button class="button primary" :disabled="!selectedTask" @click="taskDialog = 'enqueue'">新建入队</button></header><div class="module-table-wrap"><table class="table"><thead><tr><th>任务</th><th>队列状态</th><th>入队人</th><th>领取人</th><th>入队时间</th><th>操作</th></tr></thead><tbody><tr v-for="item in queueQuery.data.value?.items" :key="item.queue_id"><td><b>{{ taskTitle(item.clinical_task_id) }}</b><small>…{{ item.queue_id.slice(-8) }}</small></td><td><span class="task-state" :class="item.queue_status.toLowerCase()">{{ stateLabel(item.queue_status) }}</span></td><td>…{{ item.enqueued_by.slice(-6) }}</td><td>{{ item.claimed_by ? `…${item.claimed_by.slice(-6)}` : '待领取' }}</td><td>{{ formatDate(item.enqueued_at) }}</td><td><div class="toolbar-actions"><button v-if="item.queue_status === 'ENQUEUED'" class="button sm" @click="queueDialog = { action: 'claim', item }">编辑·领取</button><button v-if="item.queue_status === 'CLAIMED'" class="button sm primary" @click="queueDialog = { action: 'complete', item }">编辑·闭环</button><button v-if="item.queue_status === 'ENQUEUED'" class="button sm danger" @click="queueDialog = { action: 'withdraw', item }">删除·撤回</button></div></td></tr></tbody></table><div v-if="!queueQuery.data.value?.items.length" class="clinical-empty-state">团队队列暂无有效项。</div></div></section>
      <section v-else-if="activeView === 'collaboration'" class="task-module-card card"><header><div><p class="eyebrow">责任协作</p><h2>委托、转派与升级</h2><p>已接手任务可委托、转派或升级；处理中任务只允许风险升级，且不会伪造来源业务终态。</p></div><button class="button primary" :disabled="!selectedTask || !canCollaborate(selectedTask)" @click="openCollaboration()">新建协作</button></header><div class="collaboration-grid"><article v-for="task in tasks.filter(canCollaborate)" :key="task.task_id" :class="task.risk_level.toLowerCase()"><div><span class="task-risk" :class="task.risk_level.toLowerCase()">{{ riskLabel(task.risk_level) }}</span><h3>{{ task.title }}</h3><p>{{ task.business_state }} · 当前责任 …{{ task.claimed_by?.slice(-6) }}</p></div><button class="button" @click="openCollaboration(task)">编辑责任</button></article><div v-if="!tasks.some(canCollaborate)" class="clinical-empty-state">当前无可协作任务；请先在任务总览接手任务。</div></div></section>
      <section v-else-if="activeView === 'notifications'" class="task-module-card card"><header><div><p class="eyebrow">消息送达</p><h2>临床任务消息通知</h2><p>站内消息落库后才能确认送达；院内消息总线必须由外部适配器回执，页面不能人工伪造送达。</p></div><div class="toolbar-actions notification-create-toolbar"><select v-model="selectedTaskId" aria-label="选择任务" :title="selectedTask?.title"><option v-for="task in tasks" :key="task.task_id" :value="task.task_id">{{ task.title }}</option></select><button class="button primary" :disabled="!selectedTask" @click="taskDialog = 'notification'">新建通知</button></div></header><div class="module-table-wrap"><table class="table"><thead><tr><th>类型</th><th>渠道</th><th>状态</th><th>收件人</th><th>计划 / 送达</th><th>操作</th></tr></thead><tbody><tr v-for="item in notificationsQuery.data.value" :key="item.notification_id"><td>{{ item.kind }}</td><td>{{ item.channel === 'IN_APP' ? '站内消息' : '院内消息总线' }}<small v-if="item.channel === 'OUTBOX'">等待适配器回执</small></td><td><span class="task-state" :class="item.status.toLowerCase()">{{ stateLabel(item.status) }}</span><small>尝试 {{ item.attempt_count }} 次</small></td><td>…{{ item.recipient_user_id.slice(-6) }}</td><td>{{ formatDate(item.delivered_at ?? item.scheduled_at) }}</td><td><div class="toolbar-actions"><button v-if="item.status === 'PENDING' && item.channel === 'IN_APP'" class="button sm" @click="notificationDialog = { action: 'deliver', item }">确认站内送达</button><button v-if="item.status === 'PENDING'" class="button sm danger" @click="notificationDialog = { action: 'fail', item }">记录投递失败</button><button v-if="item.status === 'FAILED'" class="button sm" @click="notificationDialog = { action: 'recover', item }">恢复待投递</button></div></td></tr></tbody></table><div v-if="!notificationsQuery.data.value?.length" class="clinical-empty-state">当前任务尚无通知证据。</div></div></section>
      <section v-else-if="activeView === 'pathway'" class="task-module-card card"><header><div><p class="eyebrow">路径配置</p><h2>临床路径配置与发布</h2><p>路径包含入径、阶段必做项、完成门禁、变异审核和退出规则；版本是发布快照，仅影响新入径。</p></div><button class="button primary" @click="openCreateConfig('CLINICAL_PATHWAY')">新建路径配置</button></header><div class="config-card-grid"><article v-for="item in configQuery.data.value?.pathways" :key="item.config_id"><header><div><span>{{ item.config_key }}</span><h3>{{ item.display_name }}</h3></div><span class="status" :class="item.status === 'ACTIVE' ? 'green' : item.status === 'DRAFT' ? 'gray' : 'amber'">{{ item.status }}</span></header><dl><div><dt>专科 / 诊断</dt><dd>{{ item.payload?.specialty_code }} · {{ item.payload?.diagnosis_code }}</dd></div><div><dt>版本</dt><dd>v{{ item.payload?.version_no }} · 配置 v{{ item.row_version }}</dd></div><div><dt>阶段 / 必做任务</dt><dd>{{ Array.isArray(item.payload?.stages) ? item.payload?.stages.length : 0 }} 阶段 · {{ pathwayRequiredTaskCount(item) }} 项</dd></div><div><dt>入径 / 排除规则</dt><dd>{{ pathwayRuleCount(item, 'entry_rules') }} / {{ pathwayRuleCount(item, 'exclusion_rules') }} 条</dd></div><div><dt>变异 / 完成 / 退出</dt><dd>{{ pathwayRuleCount(item, 'variance_rules') }} / {{ pathwayRuleCount(item, 'completion_rules') }} / {{ pathwayRuleCount(item, 'exit_rules') }} 条</dd></div><div><dt>发布范围</dt><dd>{{ item.payload?.publication_scope }}</dd></div></dl><div class="toolbar-actions"><button class="button sm" @click="openEditConfig(item)">{{ item.status === 'DRAFT' ? '编辑' : '复制新版本' }}</button><button v-if="item.status === 'DRAFT'" class="button sm" @click="lifecycle(item, item.validation_state === 'VALID' ? 'SUBMIT' : 'VALIDATE')">{{ item.validation_state === 'VALID' ? '提交审批' : '校验' }}</button><button v-if="item.status === 'PENDING_APPROVAL'" class="button sm" @click="lifecycle(item, 'APPROVE')">独立批准</button><button v-if="item.status === 'APPROVED'" class="button sm primary" @click="lifecycle(item, 'PUBLISH')">发布</button><button class="button sm danger" @click="openArchiveConfig(item)">删除·停用</button></div></article><div v-if="!configQuery.data.value?.pathways.length" class="clinical-empty-state">暂无路径配置。</div></div></section>
      <section v-else class="task-module-card card"><header><div><p class="eyebrow">任务规则</p><h2>三甲医院任务规则与升级时限</h2><p>任务来源、风险、责任、时限、升级和完成判定均版本化管理。</p></div><button class="button primary" @click="openCreateConfig('CLINICAL_TASK_RULE')">新建任务规则</button></header><div class="config-card-grid"><article v-for="item in configQuery.data.value?.rules" :key="item.config_id"><header><div><span>{{ item.config_key }}</span><h3>{{ item.display_name }}</h3></div><span class="status" :class="item.status === 'ACTIVE' ? 'green' : item.status === 'DRAFT' ? 'gray' : 'amber'">{{ item.status }}</span></header><dl><div><dt>任务类型</dt><dd>{{ item.payload?.task_type }}</dd></div><div><dt>风险等级</dt><dd>{{ item.payload?.risk_level }}</dd></div><div><dt>时限 / 升级</dt><dd>{{ item.payload?.due_minutes }} / {{ item.payload?.escalation_minutes }} 分钟</dd></div><div><dt>完成依据</dt><dd>{{ item.payload?.completion_source }}</dd></div></dl><div class="toolbar-actions"><button class="button sm" @click="openEditConfig(item)">{{ item.status === 'DRAFT' ? '编辑' : '复制新版本' }}</button><button v-if="item.status === 'DRAFT'" class="button sm" @click="lifecycle(item, item.validation_state === 'VALID' ? 'SUBMIT' : 'VALIDATE')">{{ item.validation_state === 'VALID' ? '提交审批' : '校验' }}</button><button v-if="item.status === 'PENDING_APPROVAL'" class="button sm" @click="lifecycle(item, 'APPROVE')">独立批准</button><button v-if="item.status === 'APPROVED'" class="button sm primary" @click="lifecycle(item, 'PUBLISH')">发布</button><button class="button sm danger" @click="openArchiveConfig(item)">删除·停用</button></div></article><div v-if="!configQuery.data.value?.rules.length" class="clinical-empty-state">暂无任务规则配置。</div></div></section>
      <section v-if="activeView === 'overview' && selectedTask" class="task-deep-grid" aria-label="任务深层证据">
        <article class="card task-deep-card"><header><span>任务详情</span><b>{{ selectedTask.title }}</b></header><dl><div><dt>工作域</dt><dd>{{ mode === 'outpatient' ? '门诊' : mode === 'emergency' ? '急诊' : '住院' }}</dd></div><div><dt>任务状态</dt><dd>{{ stateLabel(selectedTask.state) }} / {{ selectedTask.business_state }}</dd></div><div><dt>到期时间</dt><dd>{{ formatDate(selectedTask.due_at) }}</dd></div><div><dt>升级时间</dt><dd>{{ formatDate(detailQuery.data.value?.escalation_at) }}</dd></div></dl></article>
        <article class="card task-deep-card"><header><span>任务来源</span><b>{{ selectedTask.source_type }}</b></header><a class="button sm" :href="selectedTask.source_route">打开权威来源页面</a><small>任务中心只编排责任与时限，临床终态由来源业务回写。</small></article>
        <article class="card task-deep-card"><header><span>处理记录</span><b>{{ detailQuery.data.value?.events.length ?? 0 }} 个事件</b></header><div class="task-event-list"><div v-for="event in detailQuery.data.value?.events.slice(-6)" :key="event.task_event_id"><b>{{ event.event_type }}</b><span>{{ formatDate(event.occurred_at) }} · …{{ event.actor_user_id.slice(-6) }}</span><small v-if="event.reason">{{ event.reason }}</small></div></div><p>委托 {{ detailQuery.data.value?.delegations.length ?? 0 }} 次 · 通知 {{ detailQuery.data.value?.notification_count ?? 0 }} 条 · 团队队列 {{ detailQuery.data.value?.queue_count ?? 0 }} 条</p></article>
        <details :key="selectedTask.task_id" class="card task-deep-card task-technical-details"><summary>查看技术详情</summary><dl><div><dt>数据水位</dt><dd>{{ selectedTask.data_watermark }}</dd></div><div><dt>来源标识</dt><dd>{{ selectedTask.source_id }}</dd></div><div><dt>规则版本</dt><dd>{{ detailQuery.data.value?.task_rule_version ?? '来源默认规则' }}</dd></div><div><dt>规则配置</dt><dd>{{ detailQuery.data.value?.task_rule_config_id ?? '未命中已发布规则' }}</dd></div></dl><div class="rule-snapshot"><span v-for="(value, key) in detailQuery.data.value?.rule_snapshot" :key="key"><b>{{ key }}</b>{{ value }}</span></div></details>
      </section>
    </template>
    <AdminActionDialog :open="taskDialog === 'process'" title="处理临床任务" eyebrow="统一任务" :description="selectedTask?.title ?? ''" @update:open="taskDialog = $event ? 'process' : null"><div class="dialog-task-summary"><p><b>业务状态</b>{{ selectedTask?.business_state }}</p><p><b>提示</b>查看或接手任务后，请回到来源页面完成实际处理。</p></div><template #footer><button class="button" @click="taskDialog = null">取消</button><button v-if="selectedTask && ['PENDING','ASSIGNED','DELIVERED'].includes(selectedTask.state) && isAssignee(selectedTask)" class="button" :disabled="Boolean(busy)" @click="processTask('views')">标记查看</button><button v-if="selectedTask && ['PENDING','ASSIGNED','DELIVERED','VIEWED','ESCALATED'].includes(selectedTask.state) && isAssignee(selectedTask)" class="button primary" :disabled="Boolean(busy)" @click="processTask('claims')">接手任务</button><a v-if="selectedTask" class="button primary" :href="selectedTask.source_route">回来源处理</a></template></AdminActionDialog>
    <BusinessActionDialog :open="taskDialog === 'collaborate'" title="新建 / 编辑责任协作" description="仅显示与当前操作人同院区、同科室，且岗位和执业资质均在有效期内的人员。" :busy="busy === 'collaborate'" width="wide" @cancel="taskDialog = null" @confirm="submitCollaboration">
      <div class="dialog-grid"><label>操作<select v-model="collaboration.action"><option v-for="action in collaborationActions" :key="action" :value="action">{{ collaborationActionLabel(action) }}</option></select></label><label>目标人员<select v-model="collaboration.targetUserId" required><option disabled value="">请选择符合资质的人员</option><option v-for="item in collaborators" :key="item.user_id" :value="item.user_id">{{ item.display_name }} · {{ item.position_code }}</option></select></label><label v-if="collaboration.action === 'delegations'">委托截止时间<input v-model="collaboration.validUntil" type="datetime-local" required /></label><label>资格范围<input :value="selectedCollaborator ? `同科室 · ${selectedCollaborator.role_code} · 资质 ${selectedCollaborator.active_credential_count} 项` : '未选择'" readonly /></label></div>
      <label>原因与交接说明<textarea v-model="collaboration.reason" minlength="2" maxlength="1000" rows="4" /></label><p v-if="!collaborators.length" class="dialog-warning">当前没有满足同科室、有效岗位和有效执业资质条件的协作人员，服务端会拒绝绕过式提交。</p>
    </BusinessActionDialog>
    <BusinessActionDialog :open="taskDialog === 'enqueue'" title="新建团队队列项" description="当前任务将进入心血管内科团队队列，终态任务会被服务端拒绝。" :busy="busy === 'enqueue'" @cancel="taskDialog = null" @confirm="submitEnqueue"><p class="dialog-warning">{{ selectedTask?.title }} · {{ selectedTask?.business_state }}</p></BusinessActionDialog>
    <BusinessActionDialog :open="Boolean(queueDialog)" :title="queueDialog?.action === 'withdraw' ? '撤回团队队列项' : queueDialog?.action === 'claim' ? '领取团队任务' : '闭环团队协作'" :description="queueDialog?.action === 'withdraw' ? '撤回代替物理删除，原任务与队列证据继续可追溯。' : '本操作会实际改变团队队列状态。'" :danger="queueDialog?.action === 'withdraw'" :busy="Boolean(busy)" @cancel="queueDialog = null" @confirm="submitQueueAction"><p class="dialog-warning">{{ queueDialog ? taskTitle(queueDialog.item.clinical_task_id) : '' }}</p></BusinessActionDialog>
    <BusinessActionDialog :open="taskDialog === 'notification'" title="新建任务通知" description="通知会进入实际投递状态机，不会把业务任务标记为完成。院内消息总线需部署适配器并返回真实回执。" :busy="busy === 'notification:create'" width="wide" @cancel="taskDialog = null" @confirm="submitNotification"><div class="dialog-grid"><label>通知类型<select v-model="notificationForm.kind"><option value="CREATED">新任务</option><option value="OVERDUE">逾期</option><option value="ESCALATED">已升级</option><option value="EXPIRED">已过期</option></select></label><label>投递渠道<select v-model="notificationForm.channel"><option value="IN_APP">站内消息（可落库确认）</option><option value="OUTBOX">院内消息总线（需适配器回执）</option></select></label></div><label>计划投递时间（留空立即入队）<input v-model="notificationForm.scheduledAt" type="datetime-local" /></label><p v-if="notificationForm.channel === 'OUTBOX'" class="dialog-warning">未配置院内消息适配器时，通知会保持待投递，不会被伪标为已送达。</p></BusinessActionDialog>
    <BusinessActionDialog :open="Boolean(notificationDialog)" :title="notificationDialog?.action === 'deliver' ? '标记通知送达' : notificationDialog?.action === 'fail' ? '记录投递失败' : '恢复失败通知'" :description="notificationDialog?.action === 'fail' ? '失败不会删除记录，可在修复通道后幂等恢复。' : '状态变更和尝试次数会被完整保留。'" :danger="notificationDialog?.action === 'fail'" :busy="Boolean(busy)" @cancel="notificationDialog = null" @confirm="submitNotificationAction"><label v-if="notificationDialog?.action === 'fail'">失败原因<textarea v-model="failureReason" minlength="2" rows="4" /></label><p v-else class="dialog-warning">请确认当前消息通道事实后继续。</p></BusinessActionDialog>
    <BusinessActionDialog :open="Boolean(configDialog)" :title="configDialog === 'archive' ? `停用${configKindLabel}` : `${configDialog === 'edit' ? '编辑' : '新建'}${configKindLabel}`" :description="configDialog === 'archive' ? '停用仅影响新流程，历史任务、路径实例和版本证据保留。' : '新建与编辑均以草案保存，发布后才影响新流程。'" :confirm-label="configDialog === 'archive' ? '确认停用' : '保存草案'" :danger="configDialog === 'archive'" :busy="busy === 'config'" width="wide" @cancel="configDialog = null" @confirm="submitConfigDialog"><template v-if="configDialog !== 'archive'"><div class="dialog-grid"><label>配置键<input v-model="configForm.key" :readonly="configDialog === 'edit'" /></label><label>显示名称<input v-model="configForm.name" /></label></div><template v-if="configForm.kind === 'CLINICAL_TASK_RULE'"><div class="dialog-grid"><label>任务类型<input v-model="configForm.taskType" /></label><label>风险等级<select v-model="configForm.riskLevel"><option value="CRITICAL">危急</option><option value="HIGH">高风险</option><option value="ROUTINE">常规</option></select></label><label>处理时限（分钟）<input v-model.number="configForm.dueMinutes" type="number" min="1" /></label><label>升级提前量（分钟）<input v-model.number="configForm.escalationMinutes" type="number" min="1" /></label></div></template><template v-else><div class="dialog-grid"><label>专科<input v-model="configForm.specialty" /></label><label>主要诊断编码<input v-model="configForm.diagnosis" /></label><label>版本号<input v-model.number="configForm.versionNo" type="number" min="1" /></label><label>发布机构<input value="江城大学附属医院本部" readonly /></label></div><label>入径标准<textarea v-model="configForm.admissionCriteria" rows="4" minlength="4" /></label></template></template><p v-else class="dialog-warning">{{ selectedConfig?.display_name }} 将从新流程选项中移除，已发生的业务事实不会被删除。</p></BusinessActionDialog>
  </section>
</template>

<style scoped>
.task-technical-details summary { padding: 14px; cursor: pointer; font-weight: 600; }.task-technical-details > dl,.task-technical-details > .rule-snapshot { margin: 0; padding: 14px; overflow-wrap: anywhere; }
.task-center-page{max-width:1660px;margin:0 auto}.task-center-heading{align-items:flex-start;gap:16px}.task-center-heading .head-actions{display:flex;flex-wrap:wrap;justify-content:flex-end;gap:8px;max-width:min(100%,520px)}.task-center-metrics{margin-bottom:14px}.task-overview-grid{display:grid;grid-template-columns:minmax(0,1fr) 390px;gap:14px}.task-table-card,.task-module-card,.current-path-card{overflow:hidden;border-color:#cedbea}.task-filters{grid-template-columns:145px 145px auto minmax(220px,1fr) auto;margin:0;border:0;border-bottom:1px solid #e2e9f1;border-radius:0}.task-history-filter{display:flex;align-items:center;gap:6px;white-space:nowrap;color:#536b83;font-size:12px}.task-history-filter input{inline-size:16px;block-size:16px}.task-table-wrap,.module-table-wrap{max-width:100%;overflow:auto}.task-center-table{min-width:900px}.table tbody tr.selected{background:#eef6ff}.table td small{display:block;margin-top:5px;color:#74879a}.table .button.sm,.button.sm{min-height:30px;padding:0 9px}.task-state-notice{display:grid;gap:5px;margin:12px;padding:13px 15px;border:1px solid #cfe0f3;border-radius:8px;background:#f3f8fe;color:#49647e;font-size:12px}.task-state-notice b{color:#24496d}.current-path-card>header,.task-module-card>header{display:flex;align-items:flex-start;justify-content:space-between;flex-wrap:wrap;gap:16px;padding:16px 18px;border-bottom:1px solid #e2e9f1;background:#f8fbff}.current-path-card>header>*,.task-module-card>header>*{min-width:0}.current-path-card h2,.task-module-card h2{margin:3px 0 0;color:#263b53;font-size:17px}.task-module-card>header p:not(.eyebrow){margin:6px 0 0;color:#6d8093;font-size:12px}.task-module-card>header .toolbar-actions{max-width:100%;flex-wrap:wrap}.task-module-card>header .notification-create-toolbar{display:grid;grid-template-columns:minmax(0,360px) auto;align-items:center;gap:10px;width:min(480px,100%);flex-wrap:nowrap}.notification-create-toolbar select{width:100%!important;text-overflow:ellipsis}.notification-create-toolbar .button{white-space:nowrap}.path-card-body{display:grid;gap:12px;padding:16px}.path-card-body dl,.config-card-grid dl{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin:0}.path-card-body dl div,.config-card-grid dl div{display:grid;gap:4px}.path-card-body dt,.config-card-grid dt{color:#7a8c9e;font-size:10px}.path-card-body dd,.config-card-grid dd{margin:0;color:#263b53;font-size:11px}.path-card-body h3{margin:4px 0 0;font-size:12px}.path-card-body article{display:flex;justify-content:space-between;gap:12px;padding:9px 0;border-bottom:1px solid #edf1f5;font-size:11px}.path-card-body article b{font-size:10px}.path-card-body article b.completed{color:#25835e}.path-card-body article b.pending{color:#b17013}.pathway-rule-note{display:grid;gap:5px;padding:12px;border:1px solid #ead6ae;border-radius:8px;background:#fff8e9;color:#715c31;font-size:11px}.task-module-card>header select{width:min(280px,100%);min-width:0;min-height:36px;border:1px solid #c7d5e5;border-radius:7px;background:#fff}.collaboration-grid,.config-card-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;padding:16px}.collaboration-grid article,.config-card-grid article{display:flex;align-items:center;justify-content:space-between;gap:14px;padding:15px;border:1px solid #d7e1ea;border-left:4px solid #8299b0;border-radius:9px;background:#fff}.collaboration-grid article.critical{border-left-color:#c63743}.collaboration-grid article.high{border-left-color:#d59a22}.collaboration-grid h3,.config-card-grid h3{margin:7px 0 4px;font-size:13px}.collaboration-grid p{margin:0;color:#718395;font-size:11px}.config-card-grid article{display:grid;align-items:initial}.config-card-grid article>header{display:flex;justify-content:space-between;gap:12px}.config-card-grid article>header span:first-child{color:#718395;font-size:10px}.config-card-grid .toolbar-actions{padding-top:12px;border-top:1px solid #edf1f5}.dialog-task-summary{display:grid;gap:12px}.dialog-task-summary p{display:grid;gap:4px;margin:0;padding:12px;border:1px solid #d9e2e9;border-radius:8px;color:#637488;font-size:12px}.dialog-task-summary b{color:#243a51}.danger-text{color:#b52f39!important;font-weight:700}.button.danger{border-color:#d8a8ab;color:#a33037;background:#fff}.clinical-empty-state{grid-column:1/-1;padding:32px;text-align:center;color:#77899b}
@media(max-width:1120px){.task-overview-grid{grid-template-columns:1fr}.collaboration-grid,.config-card-grid{grid-template-columns:1fr}}@media(max-width:760px){.task-center-heading{display:grid;height:auto;min-height:0;gap:12px;margin-bottom:14px}.task-center-heading .head-actions{display:grid;width:100%;margin-left:0;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.task-center-heading .head-actions .button{min-width:0}.task-center-heading .head-actions .button.primary{grid-column:1/-1}.task-filters{grid-template-columns:1fr 1fr}.task-filters .task-search,.task-filters>button{grid-column:1/-1}.path-card-body dl,.config-card-grid dl{grid-template-columns:1fr}.collaboration-grid article{align-items:flex-start;flex-direction:column}}
.task-deep-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin-top:14px}.task-deep-card{display:grid;align-content:start;gap:12px;min-width:0;padding:15px;overflow:hidden}.task-deep-card>header{display:grid;gap:4px;padding-bottom:10px;border-bottom:1px solid #e3eaf1}.task-deep-card>header span{color:#617d99;font-size:10px;font-weight:700;letter-spacing:.05em}.task-deep-card>header b{overflow-wrap:anywhere;color:#253d55;font-size:13px}.task-deep-card dl{display:grid;gap:8px;margin:0}.task-deep-card dl div{display:grid;grid-template-columns:80px minmax(0,1fr);gap:8px}.task-deep-card dt,.task-deep-card small{color:#7a8c9e;font-size:10px}.task-deep-card dd,.task-deep-card p{margin:0;overflow-wrap:anywhere;color:#3d5369;font-size:11px}.task-event-list{display:grid;gap:7px;max-height:210px;overflow:auto}.task-event-list>div{display:grid;grid-template-columns:auto 1fr;gap:3px 8px;padding:8px;border-radius:7px;background:#f6f9fc;font-size:10px}.task-event-list span{color:#647a90;text-align:right}.task-event-list small{grid-column:1/-1}.rule-snapshot{display:flex;flex-wrap:wrap;gap:6px;max-height:112px;overflow:auto}.rule-snapshot span{display:flex;gap:4px;max-width:100%;padding:5px 7px;border-radius:6px;background:#eef4fa;overflow-wrap:anywhere;color:#526a82;font-size:9px}.task-domain-nav{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:0 0 14px;padding:10px 12px;border:1px solid #d5e0eb;border-radius:9px;background:#f8fbff}.task-domain-nav>div{display:flex;flex-wrap:wrap;gap:8px}.task-domain-nav .button{white-space:nowrap}@media(max-width:1200px){.task-deep-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:760px){.task-deep-grid{grid-template-columns:1fr}.task-domain-nav{align-items:stretch;flex-direction:column}.task-domain-nav>a{width:100%;text-align:center}}
</style>
