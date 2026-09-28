<script setup lang="ts">
import EvaMessageContent from '../components/EvaMessageContent.vue';
import { useQuery } from '@tanstack/vue-query';
import AgentCandidateReview from '../components/AgentCandidateReview.vue';
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { evaConversationTransfer, takeEvaConversation, type EvaChatMessage as ChatMessage, type EvaConversationTransfer } from '../eva-conversation-handoff';
import { useRoute, useRouter } from 'vue-router';

import { issueAiLease, listModelDeployments } from '../../api/ai-platform';
import { activatePatientReview, canReviewPatientRecord } from '../eva-patient-review';
import { evaReviewBridge } from '../eva-review-bridge';
import { cancelMedicalAgentRun, createMedicalAgentRun, getMedicalAgentRun, issueMedicalAgentCatalogLease, issueMedicalAgentRunLease, listMedicalAgentCatalog, listMedicalAgentRuns, retryMedicalAgentRun } from '../../api/medical-agents';
import type { MedicalAgentFamilyWire, MedicalAgentReleaseWire, MedicalAgentRunWire } from '../../generated/contracts';
import AdminConfirmDialog from '../components/AdminConfirmDialog.vue';
import EvaComposerControls from '../components/EvaComposerControls.vue';
import EvaPatientPicker from '../components/EvaPatientPicker.vue';
import EvaStagePicker from '../components/EvaStagePicker.vue';
import XiaonanAgentTeamRail from '../components/XiaonanAgentTeamRail.vue';
import { toClinicalIssue } from '../clinical-error';
import { isEvaWorkspaceLoading } from '../eva-workspace-state';
import { doctorFacingAiText, doctorFacingTeamName } from '../medical-ai-terminology';
import { medicalAgentRunStateLabel, presentMedicalAgentEvents, presentMedicalAgentResult } from '../medical-agent-run-presenter';
import { evaDefaultPatientContexts, hasEvaPatientContext, useEvaClinicalContext, type EvaPatientContext } from '../use-eva-clinical-context';

type AuthorizationLevel = 'READ_ONLY' | 'STANDARD' | 'EXTENDED';
type ContextScope = 'RECORDS' | 'ORDERS' | 'RESULTS' | 'TASKS' | 'ATTACHMENTS' | 'CONFIGURATION';
interface TaskEvent { id: string; label: string; detail: string; status: 'running' | 'done' | 'waiting' | 'failed' }

const route = useRoute();
const router = useRouter();
const messages = ref<ChatMessage[]>([]);
const draft = ref(typeof route.query.objective === 'string' ? route.query.objective : '');
const busy = ref(false);
const pendingHandoffScroll = ref<string | null>(null);
let conversationVersion = 0;
function releaseConversation() { conversationVersion += 1; busy.value = false; pendingHandoffScroll.value = null; }
onBeforeUnmount(releaseConversation);
const notice = ref('');
const clearConversationOpen = ref(false);
const teamCollapsed = ref(false);
const stagePickerCollapsed = ref(false);
const selectedMainAgentCode = ref(typeof route.query.agent_code === 'string' ? route.query.agent_code : '');
const selectedStageCode = ref(typeof route.query.stage_code === 'string' ? route.query.stage_code : '');
const selectedModelId = ref('');
const authorizationLevel = ref<AuthorizationLevel>('STANDARD');
const contextScopes = ref<ContextScope[]>(['RECORDS', 'ORDERS', 'RESULTS', 'TASKS', 'CONFIGURATION']);
const composer = ref<HTMLTextAreaElement | null>(null);
const threadEl = ref<HTMLElement | null>(null);
const shellEl = ref<HTMLElement | null>(null);
const threadHeight = ref(0);
const reconnecting = ref(false);
const requestedPatientId = typeof route.query.patient_id === 'string' ? route.query.patient_id : '';
const requestedEncounterId = typeof route.query.encounter_id === 'string' ? route.query.encounter_id : '';
const requestedPatientContext: EvaPatientContext = {
  patientId: requestedPatientId, encounterId: requestedEncounterId, patientName: '当前患者',
  patientSummary: '从诊疗页面带入', label: '当前就诊', scene: '诊疗',
};
const initialPatientContext = evaDefaultPatientContexts.find((item) =>
  item.patientId === requestedPatientId && item.encounterId === requestedEncounterId)
  ?? (hasEvaPatientContext(requestedPatientContext) ? requestedPatientContext : undefined);
const patient = useEvaClinicalContext(initialPatientContext);
const hasPatientContext = computed(() => hasEvaPatientContext(patient.current.value));

const catalogLeaseQuery = useQuery({ queryKey: ['eva', 'catalog-lease'], queryFn: issueMedicalAgentCatalogLease, retry: false, staleTime: 5 * 60_000, gcTime: 0 });
const catalogQuery = useQuery({ queryKey: ['eva', 'catalog'], queryFn: () => listMedicalAgentCatalog(catalogLeaseQuery.data.value!), enabled: computed(() => Boolean(catalogLeaseQuery.data.value)), retry: false, staleTime: 5 * 60_000, gcTime: 0 });
const modelLeaseQuery = useQuery({ queryKey: ['eva', 'model-lease'], queryFn: () => issueAiLease('AI_ASSISTANT_MODEL_SELECTION'), retry: false, staleTime: 5 * 60_000, gcTime: 0 });
const modelsQuery = useQuery({ queryKey: ['eva', 'models'], queryFn: () => listModelDeployments(modelLeaseQuery.data.value!), enabled: computed(() => Boolean(modelLeaseQuery.data.value)), retry: false, staleTime: 60_000 });
const runLeaseQuery = useQuery({ queryKey: computed(() => ['eva', 'run-lease', patient.current.value.patientId, patient.current.value.encounterId]), queryFn: () => issueMedicalAgentRunLease(hasPatientContext.value ? patient.current.value.patientId : null, hasPatientContext.value ? patient.current.value.encounterId : null), enabled: true, retry: false, staleTime: 5 * 60_000, gcTime: 0 });
const historyQuery = useQuery({
  queryKey: computed(() => ['eva', 'history', patient.current.value.patientId, patient.current.value.encounterId]),
  queryFn: () => listMedicalAgentRuns(runLeaseQuery.data.value!, patient.current.value.patientId, patient.current.value.encounterId),
  enabled: computed(() => hasPatientContext.value && Boolean(runLeaseQuery.data.value)),
  retry: false, staleTime: 30_000, gcTime: 0,
});
const history = computed(() => historyQuery.data.value ?? []);

const families = computed(() => catalogQuery.data.value ?? []);
const availableModels = computed(() => (modelsQuery.data.value ?? []).filter((model) => model.status === 'ACTIVE'
  && model.evaluation_status === 'APPROVED' && model.connection_status === 'READY'
  && !model.test_only && model.endpoint_url?.includes('.example/') !== true));
const modelAvailabilityNotice = computed(() => {
  if (modelsQuery.isPending.value || modelsQuery.isError.value || availableModels.value.length) return '';
  return '当前没有可供 AI医助 EVA 使用的合规模型。请由模型管理员完成真实模型登记、连接检测和发布后再试。';
});
const selectedFamily = computed(() => families.value.find((family) => family.main_agent.agent_code === selectedMainAgentCode.value));
const availableStages = computed(() => selectedFamily.value?.child_agents ?? []);
const selectedChild = computed(() => availableStages.value.find((child) => child.stage_code === selectedStageCode.value));
const selectedModel = computed(() => availableModels.value.find((model) => model.model_deployment_id === selectedModelId.value));
const loading = computed(() => isEvaWorkspaceLoading({
  catalogLeasePending: catalogLeaseQuery.isPending.value,
  catalogLeaseReady: Boolean(catalogLeaseQuery.data.value),
  catalogPending: catalogQuery.isPending.value,
  modelLeasePending: modelLeaseQuery.isPending.value,
  modelLeaseReady: Boolean(modelLeaseQuery.data.value),
  modelsPending: modelsQuery.isPending.value,
  runLeaseEnabled: true,
  runLeasePending: runLeaseQuery.isPending.value,
}));
const issue = computed(() => {
  const error = catalogLeaseQuery.error.value ?? catalogQuery.error.value ?? modelLeaseQuery.error.value ?? modelsQuery.error.value ?? runLeaseQuery.error.value;
  return error ? toClinicalIssue(error) : null;
});

watch(families, (next) => { if (next.length && !next.some((family) => family.main_agent.agent_code === selectedMainAgentCode.value)) selectedMainAgentCode.value = next[0].main_agent.agent_code; }, { immediate: true });
watch(availableStages, (next) => { if (next.length && !next.some((child) => child.stage_code === selectedStageCode.value)) selectedStageCode.value = next[0].stage_code; }, { immediate: true });
watch(availableModels, (next) => { if (next.length && !next.some((model) => model.model_deployment_id === selectedModelId.value)) selectedModelId.value = next[0].model_deployment_id; }, { immediate: true });

function clinicianAgentName(name: string) { return doctorFacingTeamName(name); }
function scopeLabel(scope: ContextScope) { return ({ RECORDS: '病历文书', ORDERS: '医嘱执行', RESULTS: '检查检验', TASKS: '任务随访', ATTACHMENTS: '病历附件', CONFIGURATION: '知识与规则' } as Record<ContextScope, string>)[scope]; }
function lastDoneEvent(message: ChatMessage) {
  const progressed = (message.events ?? []).filter((event) => event.status !== 'waiting');
  return progressed[progressed.length - 1]?.label ?? '';
}
function authorizationLabel(level: AuthorizationLevel) { return ({ READ_ONLY: '只读', STANDARD: '标准', EXTENDED: '扩展' } as Record<AuthorizationLevel, string>)[level]; }

async function reconnectWorkspace() {
  if (reconnecting.value) return;
  reconnecting.value = true;
  try {
    await Promise.all([
      catalogLeaseQuery.refetch(),
      modelLeaseQuery.refetch(),
      runLeaseQuery.refetch(),
    ]);
    await Promise.all([
      catalogLeaseQuery.data.value ? catalogQuery.refetch() : Promise.resolve(),
      modelLeaseQuery.data.value ? modelsQuery.refetch() : Promise.resolve(),
    ]);
  } finally {
    reconnecting.value = false;
  }
}

function initialEvents(agent: MedicalAgentFamilyWire, child: MedicalAgentReleaseWire): TaskEvent[] {
  return [
    { id: crypto.randomUUID(), label: 'AI医助 EVA 正在规划任务', detail: `${clinicianAgentName(agent.main_agent.display_name)} · ${doctorFacingAiText(child.display_name)}`, status: 'done' },
    { id: crypto.randomUUID(), label: '读取已授权诊疗信息', detail: contextScopes.value.map(scopeLabel).join('、'), status: 'running' },
    { id: crypto.randomUUID(), label: `安排${doctorFacingAiText(child.display_name)}`, detail: doctorFacingAiText(child.current_action), status: 'waiting' },
    { id: crypto.randomUUID(), label: '调用模型与院内工具', detail: `${selectedModel.value?.display_name ?? '机构默认模型'} · ${authorizationLabel(authorizationLevel.value)}授权`, status: 'waiting' },
    { id: crypto.randomUUID(), label: '汇总并核对结果', detail: '完成后直接呈现在本次对话中', status: 'waiting' },
  ];
}

function mapRunEvents(run: MedicalAgentRunWire): TaskEvent[] {
  return presentMedicalAgentEvents(run, `${selectedModel.value?.display_name ?? '机构默认模型'} · ${contextScopes.value.map(scopeLabel).join('、')}`);
}

const terminalRunStates = new Set<MedicalAgentRunWire['state']>(['WAITING_FOR_REVIEW', 'COMPLETED', 'PARTIAL', 'BLOCKED', 'FAILED', 'CANCELLED']);
const retryableRunStates = new Set<MedicalAgentRunWire['state']>(['PARTIAL', 'BLOCKED', 'FAILED', 'CANCELLED']);
const delay = (duration: number) => new Promise((resolve) => window.setTimeout(resolve, duration));

async function openPatientRecord(message: ChatMessage) {
  if (!canReviewPatientRecord(message, patient.current.value)) return;
  const path = activatePatientReview(patient.current.value);
  if (!path) return;
  const index = messages.value.indexOf(message);
  const question = messages.value.slice(0, index).reverse().find(item => item.role === 'user');
  evaReviewBridge.payload = { objective: message.run?.objective ?? question?.text ?? '', result: message.text,
    run: message.run, patient: { ...patient.current.value } };
  evaReviewBridge.armed = true;
  try { await router.push(path); }
  catch (error) { notice.value = toClinicalIssue(error).message; evaReviewBridge.payload = null; }
  finally { evaReviewBridge.armed = false; }
}

function applyRun(message: ChatMessage, run: MedicalAgentRunWire, fallbackChildName = '诊疗环节医助') {
  message.processing = !terminalRunStates.has(run.state);
  message.run = run;
  message.runId = run.run_id;
  message.runState = run.state;
  message.rowVersion = run.row_version;
  message.events = mapRunEvents(run);
  if (terminalRunStates.has(run.state)) {
    const participants = run.child_runs.map((item) => doctorFacingAiText(item.display_name)).join('、') || doctorFacingAiText(fallbackChildName);
    message.text = `${presentMedicalAgentResult(run, 'markdown')}\n\n参与医助：${participants}。本次读取 ${contextScopes.value.map(scopeLabel).join('、')}，使用${authorizationLabel(authorizationLevel.value)}授权。`;
    if (run.state === 'WAITING_FOR_REVIEW') {
      message.text += '\n\n以上为 AI 候选结果，不会自动写入病历、医嘱或任务。请核对后点击下方「打开患者病历复核」进入患者病历处理。';
    }
  } else {
    message.text = presentMedicalAgentResult(run, 'markdown');
  }
  if (!message.eventsUserToggled) message.eventsCollapsed = terminalRunStates.has(run.state);
  if (terminalRunStates.has(run.state)) void historyQuery.refetch();
}

async function pollRun(message: ChatMessage, lease: NonNullable<typeof runLeaseQuery.data.value>, patientId: string | null, encounterId: string | null, fallbackChildName: string, version = conversationVersion) {
  for (let poll = 0; poll < 300; poll += 1) {
    if (version !== conversationVersion || !message.runId || terminalRunStates.has(message.runState!)) return;
    await delay(800);
    if (version !== conversationVersion) return;
    const run = await getMedicalAgentRun(lease, patientId, encounterId, message.runId);
    if (version !== conversationVersion) return;
    applyRun(message, run, fallbackChildName);
  }
  message.text = '任务仍在后台处理，运行记录已保存，稍后可继续查看。';
}

async function send() {
  const text = draft.value.trim();
  const lease = runLeaseQuery.data.value;
  const agent = selectedFamily.value;
  const child = selectedChild.value ?? availableStages.value[0];
  if (!text || !lease || !agent || !child || !selectedModelId.value || busy.value) return;
  const version = conversationVersion;
  busy.value = true; notice.value = '';
  const responseId = crypto.randomUUID();
  messages.value.push({ id: crypto.randomUUID(), role: 'user', text });
  messages.value.push({ id: responseId, role: 'assistant', text: '', processing: true, agentName: 'AI医助 EVA', events: initialEvents(agent, child), eventsCollapsed: true, modelName: selectedModel.value?.display_name });
  draft.value = '';
  await nextTick();
  if (version !== conversationVersion) return;
  try {
    const patientId = hasPatientContext.value ? patient.current.value.patientId : null;
    const encounterId = hasPatientContext.value ? patient.current.value.encounterId : null;
    const taskTargetId = typeof route.query.target_id === 'string' ? route.query.target_id : '';
    const run = await createMedicalAgentRun(lease, {
      patientId, encounterId, mainAgentCode: agent.main_agent.agent_code, stageCode: child.stage_code,
      targetType: patientId ? (route.query.target_type === 'TASK' && taskTargetId ? 'TASK' : 'ENCOUNTER') : null,
      targetId: patientId ? (route.query.target_type === 'TASK' && taskTargetId ? taskTargetId : encounterId) : null,
      objective: text, modelDeploymentId: selectedModelId.value,
      authorizationLevel: authorizationLevel.value, contextScopes: contextScopes.value,
    });
    if (version !== conversationVersion) return;
    const message = messages.value.find((item) => item.id === responseId)!;
    applyRun(message, run, child.display_name);
    await pollRun(message, lease, patientId, encounterId, child.display_name, version);
  } catch (error) {
    const next = toClinicalIssue(error);
    if (version !== conversationVersion) return;
    const message = messages.value.find((item) => item.id === responseId)!;
    message.text = `任务未完成：${next.message}`;
    message.events = (message.events ?? []).map((event) => event.status === 'done' ? event : { ...event, status: event.status === 'running' ? 'failed' : 'waiting' });
    notice.value = `${next.code}：${next.message}`;
  } finally { if (version === conversationVersion) busy.value = false; }
}

async function cancelRun(message: ChatMessage) {
  const lease = runLeaseQuery.data.value;
  if (!lease || !message.runId || !message.runState || !['QUEUED', 'RUNNING'].includes(message.runState)) return;
  try {
    const patientId = hasPatientContext.value ? patient.current.value.patientId : null;
    const encounterId = hasPatientContext.value ? patient.current.value.encounterId : null;
    const latest = await getMedicalAgentRun(lease, patientId, encounterId, message.runId);
    if (!['QUEUED', 'RUNNING'].includes(latest.state)) { applyRun(message, latest); return; }
    applyRun(message, await cancelMedicalAgentRun(lease, patientId, encounterId, latest.run_id, latest.row_version));
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
}

async function retryRun(message: ChatMessage) {
  if (!message.runId || !message.runState || !retryableRunStates.has(message.runState) || busy.value) return;
  const version = conversationVersion;
  busy.value = true; message.processing = true; notice.value = '';
  try {
    const patientId = hasPatientContext.value ? patient.current.value.patientId : null;
    const encounterId = hasPatientContext.value ? patient.current.value.encounterId : null;
    const lease = await issueMedicalAgentRunLease(patientId, encounterId);
    const latest = await getMedicalAgentRun(lease, patientId, encounterId, message.runId);
    if (version !== conversationVersion) return;
    const run = await retryMedicalAgentRun(lease, patientId, encounterId, latest.run_id, latest.row_version);
    if (version !== conversationVersion) return;
    applyRun(message, run);
    await pollRun(message, lease, patientId, encounterId, '诊疗环节医助', version);
  } catch (error) { if (version !== conversationVersion) return; const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { if (version === conversationVersion) busy.value = false; }
}

function toggleEvents(message: ChatMessage) {
  message.eventsCollapsed = !message.eventsCollapsed;
  message.eventsUserToggled = true;
}
const threadStyle = computed(() => threadHeight.value > 0 ? { flex: `0 0 ${threadHeight.value}px` } : {});
function startThreadResize(event: PointerEvent) {
  const shell = shellEl.value;
  const thread = threadEl.value;
  if (!shell || !thread) return;
  event.preventDefault();
  const startY = event.clientY;
  const startHeight = thread.offsetHeight;
  const minHeight = 160;
  const maxHeight = Math.max(minHeight, shell.clientHeight - 170);
  const onMove = (move: PointerEvent) => {
    threadHeight.value = Math.min(maxHeight, Math.max(minHeight, startHeight + (move.clientY - startY)));
  };
  const onUp = () => {
    window.removeEventListener('pointermove', onMove);
    window.removeEventListener('pointerup', onUp);
  };
  window.addEventListener('pointermove', onMove);
  window.addEventListener('pointerup', onUp);
}
function loadHistory(run: MedicalAgentRunWire) {
  const userMessage: ChatMessage = { id: crypto.randomUUID(), role: 'user', text: run.objective };
  const assistantMessage: ChatMessage = { id: crypto.randomUUID(), role: 'assistant', text: '', processing: true, agentName: 'AI医助 EVA' };
  applyRun(assistantMessage, run, '诊疗环节医助');
  messages.value.push(userMessage, assistantMessage);
  nextTick(() => { const thread = threadEl.value; if (thread) thread.scrollTop = thread.scrollHeight; });
}
function selectAgent(agent: MedicalAgentFamilyWire) { selectedMainAgentCode.value = agent.main_agent.agent_code; }
function selectStage(child: MedicalAgentReleaseWire) { selectedStageCode.value = child.stage_code; draft.value = doctorFacingAiText(child.question_examples[0] ?? child.current_action); nextTick(() => composer.value?.focus()); }
function newTask() { releaseConversation(); messages.value = []; draft.value = ''; notice.value = '已创建空白医助任务。'; nextTick(() => composer.value?.focus()); }
function clearConversation() { releaseConversation(); messages.value = []; notice.value = ''; clearConversationOpen.value = false; }
function resetForPatient() { releaseConversation(); messages.value = []; draft.value = ''; notice.value = '已切换患者并创建空白任务。'; }
async function selectSearchPatient(value: Parameters<typeof patient.selectPatient>[0]) { await patient.selectPatient(value); resetForPatient(); }
function selectEncounter(value: Parameters<typeof patient.selectEncounter>[0]) { patient.selectEncounter(value); resetForPatient(); }
function selectDefault(value: Parameters<typeof patient.selectDefault>[0]) { patient.selectDefault(value); resetForPatient(); }
function unbindPatient() { releaseConversation(); patient.unbind(); messages.value = []; draft.value = ''; nextTick(() => composer.value?.focus()); }
async function resumeTransferredConversation(transfer: EvaConversationTransfer) {
  releaseConversation();
  const version = conversationVersion;
  patient.current.value = { ...transfer.patient };
  messages.value = transfer.messages.map(message => ({ ...message, eventsCollapsed: true }));
  draft.value = transfer.draft;
  selectedMainAgentCode.value = transfer.agentCode;
  selectedStageCode.value = transfer.stageCode;
  selectedModelId.value = transfer.modelId;
  authorizationLevel.value = transfer.authorizationLevel;
  contextScopes.value = [...transfer.contextScopes];
  notice.value = transfer.messages.length ? '已接续当前聊天。' : '';
  const patientId = hasPatientContext.value ? transfer.patient.patientId : null;
  const encounterId = hasPatientContext.value ? transfer.patient.encounterId : null;
  const active = messages.value.filter(message => message.role === 'assistant' && (
    message.id === transfer.pendingRun?.messageId || message.runState === 'QUEUED' || message.runState === 'RUNNING'
  ));
  busy.value = active.length > 0;
  pendingHandoffScroll.value = active.at(-1)?.id ?? messages.value.at(-1)?.id ?? null;
  try {
    for (const message of active) {
      if (version !== conversationVersion) return;
      message.processing = true;
      if (transfer.pendingRun?.messageId === message.id) {
        const run = await transfer.pendingRun.promise;
        if (version !== conversationVersion) return;
        // Bind updates only to the context that travelled with this conversation.
        if (run.patient_id !== patientId || run.encounter_id !== encounterId) throw new Error('任务与带入的患者或就诊不一致，已停止接续');
        applyRun(message, run);
      }
      if (!message.runId) throw new Error('未取得原任务编号，请在运行记录中核对');
      if (message.run && (message.run.patient_id !== patientId || message.run.encounter_id !== encounterId)) throw new Error('任务与带入的患者或就诊不一致，已停止接续');
      const lease = await issueMedicalAgentRunLease(patientId, encounterId);
      if (version !== conversationVersion) return;
      const latest = await getMedicalAgentRun(lease, patientId, encounterId, message.runId);
      if (version !== conversationVersion) return;
      applyRun(message, latest);
      await pollRun(message, lease, patientId, encounterId, '诊疗环节医助', version);
    }
  } catch (error) {
    if (version !== conversationVersion) return;
    const issue = toClinicalIssue(error);
    notice.value = `聊天已带入，进度更新暂未完成：${issue.message}`;
    for (const message of active) {
      message.processing = false;
      if (!message.runId) message.text = `任务未完成：${issue.message}`;
    }
  } finally {
    if (version === conversationVersion) busy.value = false;
  }
}

watch(evaConversationTransfer, transfer => {
  if (!transfer) return;
  const incoming = takeEvaConversation();
  if (incoming) void resumeTransferredConversation(incoming);
}, { immediate: true });

watch([threadEl, pendingHandoffScroll], ([thread, messageId]) => {
  if (!thread || !messageId) return;
  const message = thread.querySelector<HTMLElement>(`[data-message-id="${CSS.escape(messageId)}"]`);
  if (!message) return;
  thread.scrollTop += message.getBoundingClientRect().top - thread.getBoundingClientRect().top - 8;
  pendingHandoffScroll.value = null;
}, { flush: 'post' });

</script>

<template>
  <section data-page-root class="content vue-native-page xiaonan-harness-page">
    <div class="eva-workbench-titlebar">
      <div class="eva-workbench-brand"><img class="eva-avatar-motion" :data-active="busy" src="/brand/ai-medical-assistant-eva.png" alt="AI医助 EVA" width="48" height="48" /><div><span>临床任务工作台</span><h1>AI医助 EVA</h1><p>把诊疗任务交给医助团队，执行步骤、数据范围与结果都在对话中呈现</p></div></div>
      <div class="head-actions"><button class="btn" type="button" :disabled="messages.length === 0" @click="clearConversationOpen = true">清空任务</button><button class="btn primary" type="button" :disabled="loading" @click="newTask">新建医助任务</button></div>
    </div>
    <div v-if="issue" class="card eva-workspace-error" role="alert"><div class="card-body"><strong>AI医助 EVA 工作台暂时无法连接</strong><p>{{ issue.code }}：{{ issue.message }}</p><button class="btn" type="button" :disabled="reconnecting" @click="reconnectWorkspace">{{ reconnecting ? '正在重新连接…' : '重新连接' }}</button></div></div>
    <div v-else-if="loading" class="card"><div class="card-body">正在连接 AI医助 EVA 工作台…</div></div>

    <section v-else ref="shellEl" class="xiaonan-harness-shell eva-harness-shell">
      <XiaonanAgentTeamRail :agents="families" :selected-agent-code="selectedMainAgentCode" :collapsed="teamCollapsed" :busy="busy" @toggle="teamCollapsed = !teamCollapsed" @select="selectAgent" />
      <section class="eva-harness-main" aria-label="AI医助 EVA 任务对话">
        <header class="eva-session-head"><div><span class="eva-live-dot" aria-hidden="true"></span><div><strong>{{ selectedFamily ? clinicianAgentName(selectedFamily.main_agent.display_name) : 'AI医助 EVA 综合服务' }}</strong><small>{{ selectedFamily?.main_agent.doctor_facing_summary ?? '根据任务自动选择诊疗环节医助' }}</small></div></div><span>{{ messages.length ? `${Math.ceil(messages.length / 2)} 轮任务` : '空白任务' }}</span></header>
        <EvaStagePicker v-if="selectedFamily" :children="selectedFamily.child_agents" :selected-stage-code="selectedStageCode" :collapsed="stagePickerCollapsed" :busy="busy" @update:collapsed="stagePickerCollapsed = $event" @select="selectStage" />
        <section ref="threadEl" class="eva-agent-thread" :style="threadStyle" aria-live="polite">
          <div v-if="messages.length === 0" class="eva-agent-empty"><img class="eva-illustration-motion" src="/brand/ai-medical-assistant-eva-workbench.png" alt="AI医助 EVA 调度诊疗数据、医助团队与系统工具" /><div><strong>交给 AI医助 EVA 一项完整的诊疗任务</strong><p>可从左侧选择医助或示例，也可以直接描述目标。AI医助 EVA 会在回复中展示规划、数据读取、工具调用、子医助协作和结果核对。</p></div></div>
          <article v-for="message in messages" :key="message.id" :data-message-id="message.id" class="eva-agent-message" :class="message.role">
            <header><b>{{ message.role === 'user' ? '医生' : (message.agentName || 'AI医助 EVA') }}</b><span>{{ message.role === 'user' ? '任务' : message.runId ? `任务 …${message.runId.slice(-8)}` : busy ? '正在执行' : '执行结果' }}</span></header>
            <div v-if="message.events?.length" class="eva-events-block">
              <button class="eva-events-toggle" type="button" :aria-expanded="!message.eventsCollapsed" @click="toggleEvents(message)"><span class="eva-events-chevron">{{ message.eventsCollapsed ? '▸' : '▾' }}</span><span class="eva-events-title">执行过程</span><em>{{ message.events.length }} 步{{ message.eventsCollapsed && lastDoneEvent(message) ? ` · ${lastDoneEvent(message)}` : '' }}</em></button>
              <ol v-show="!message.eventsCollapsed" class="eva-inline-events"><li v-for="event in message.events" :key="event.id" :class="event.status"><i>{{ event.status === 'done' ? '✓' : event.status === 'failed' ? '!' : event.status === 'running' ? '•' : '·' }}</i><span><b>{{ event.label }}</b><small>{{ event.detail }}</small></span><em>{{ event.status === 'done' ? '完成' : event.status === 'failed' ? '失败' : event.status === 'running' ? '进行中' : '等待' }}</em></li></ol>
            </div>
            <p v-if="message.role === 'user'">{{ message.text }}</p><EvaMessageContent v-else :text="message.text" :processing="busy && message.processing === true" :phase="message.events?.filter(event => event.status !== 'waiting').at(-1)?.label" />
            <AgentCandidateReview v-if="message.run && terminalRunStates.has(message.run.state)" :run="message.run" />
            <footer v-if="message.role === 'assistant' && message.runId" class="eva-message-actions"><span v-if="message.runState">状态：{{ medicalAgentRunStateLabel(message.runState) }}</span><button v-if="message.runState === 'QUEUED' || message.runState === 'RUNNING'" class="btn" type="button" @click="cancelRun(message)">取消任务</button><button v-else-if="message.runState && retryableRunStates.has(message.runState)" class="btn" type="button" :disabled="busy" @click="retryRun(message)">重新执行</button><button v-if="canReviewPatientRecord(message, patient.current.value)" class="btn primary" type="button" @click="openPatientRecord(message)">打开患者病历复核</button></footer>
          </article>
        </section>
        <div class="eva-thread-resizer" role="separator" aria-orientation="horizontal" aria-label="拖拽调整聊天区域高度" @pointerdown="startThreadResize"></div>
        <form class="eva-agent-composer" @submit.prevent="send">
          <p v-if="modelAvailabilityNotice" class="inline-notice info" role="status">{{ modelAvailabilityNotice }}</p>
          <p v-if="notice" class="inline-notice" :class="notice.includes('失败') || notice.includes('HTTP') ? 'error' : 'info'" role="status">{{ notice }}</p>
          <textarea ref="composer" v-model="draft" :disabled="busy" rows="4" aria-label="向 AI医助 EVA 描述诊疗任务" placeholder="描述需要完成的诊疗任务，Enter 发送，Shift+Enter 换行……" @keydown.enter.exact.prevent="send" />
          <footer><EvaComposerControls v-model:model-id="selectedModelId" v-model:authorization-level="authorizationLevel" v-model:context-scopes="contextScopes" :models="availableModels" :disabled="busy" /><button class="btn primary" type="submit" :disabled="busy || !draft.trim() || !selectedModelId || !runLeaseQuery.data.value">{{ busy ? 'AI医助 EVA 正在执行…' : '发送任务' }}</button></footer>
        </form>
      </section>
      <EvaPatientPicker :current="patient.current.value" :defaults="evaDefaultPatientContexts" :results="patient.results.value" :encounters="patient.encounters.value" :selected-patient-id="patient.selectedPatient.value?.patient_id ?? ''" :searching="patient.searching.value" :loading-encounters="patient.loadingEncounters.value" :notice="patient.notice.value" :history="history" :history-loading="historyQuery.isPending.value || historyQuery.isFetching.value" @search="patient.search" @select-default="selectDefault" @select-patient="selectSearchPatient" @select-encounter="selectEncounter" @select-history="loadHistory" @unbind="unbindPatient" />
    </section>
    <AdminConfirmDialog :open="clearConversationOpen" title="清空当前任务" description="将清空当前页面中的对话与处理步骤，不影响已经写入数据库的医助运行记录。" confirm-label="确认清空" @update:open="clearConversationOpen = $event" @confirm="clearConversation" />
  </section>
</template>

<style scoped>
.xiaonan-harness-page { display: flex; flex-direction: column; height: max(620px,var(--page-min-block-size)); min-height: max(620px,var(--page-min-block-size)); padding-top: 0; }
.eva-workspace-error .card-body { display: grid; justify-items: start; gap: 8px; }.eva-workspace-error p { margin: 0; color: #7a3138; }
.eva-workbench-titlebar { display: flex; flex: 0 0 auto; align-items: center; gap: 14px; min-height: 74px; padding: 10px 4px 12px; }
.eva-workbench-brand { display: flex; align-items: center; gap: 11px; min-width: 0; }.eva-workbench-brand img { flex: 0 0 48px; width: 48px; height: 48px; object-fit: cover; border: 1px solid #d6e2ee; border-radius: 50%; background: #fff; }.eva-workbench-brand > div { min-width: 0; }.eva-workbench-brand span { color: #66809a; font-size: 10px; font-weight: 800; letter-spacing: .5px; }.eva-workbench-brand h1 { margin: 2px 0; color: #203b55; font-size: 22px; }.eva-workbench-brand p { margin: 0; overflow: hidden; color: #6f8295; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }.eva-workbench-titlebar .head-actions { display: flex; gap: 8px; margin-left: auto; }
.eva-harness-shell { display: grid; grid-template-columns: auto minmax(0,1fr) 248px; flex: 1 1 auto; min-height: 0; overflow: hidden; border: 1px solid #cad8e6; border-radius: 14px; background: #fff; box-shadow: 0 10px 32px rgb(23 52 80 / 9%); }
.eva-harness-main { display: flex; flex-direction: column; min-width: 0; min-height: 0; background: #fff; }
.eva-session-head { display: flex; flex: 0 0 auto; align-items: center; justify-content: space-between; gap: 10px; min-height: 56px; padding: 9px 14px; border-bottom: 1px solid #d8e3ef; }.eva-session-head > div { display: flex; align-items: center; gap: 8px; min-width: 0; }.eva-session-head > div > div { display: grid; gap: 2px; min-width: 0; }.eva-session-head strong { color: #2d455d; font-size: 14px; }.eva-session-head small, .eva-session-head > span { color: #758699; font-size: 11px; }.eva-live-dot { width: 9px; height: 9px; flex: 0 0 9px; border-radius: 50%; background: #14a487; box-shadow: 0 0 0 4px #dff6f1; }
.eva-agent-thread { display: grid; flex: 1 1 auto; align-content: start; gap: 12px; min-height: 160px; padding: 16px; overflow-y: auto; background: #fbfcfe; }
.eva-thread-resizer { flex: 0 0 auto; height: 7px; cursor: row-resize; background: #eef2f6; border-top: 1px solid #d8e3ef; border-bottom: 1px solid #d8e3ef; }.eva-thread-resizer:hover, .eva-thread-resizer:active { background: #d8e6f5; }.eva-agent-empty { display: grid; align-self: center; justify-items: center; gap: 8px; max-width: 680px; margin: auto; text-align: center; }.eva-agent-empty img { width: min(100%,520px); max-height: 230px; object-fit: contain; border-radius: 12px; mix-blend-mode: multiply; }.eva-agent-empty strong { color: #29435d; font-size: 17px; }.eva-agent-empty p { max-width: 560px; margin: 0; color: #708195; font-size: 13px; line-height: 1.65; }
.eva-agent-message { min-width: 0; display: grid; gap: 10px; width: min(92%,760px); padding: 13px 15px; border: 1px solid #d6e1eb; border-radius: 12px; background: #fff; }.eva-agent-message.user { justify-self: end; width: min(82%,660px); border-color: #a9cbea; background: #edf6ff; }.eva-agent-message header { display: flex; justify-content: space-between; gap: 8px; }.eva-agent-message header b { color: #185b83; font-size: 13px; }.eva-agent-message header span { color: #8694a2; font-size: 11px; }.eva-agent-message > p { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; color: #3c5268; font-size: 14px; line-height: 1.7; }.eva-running-copy { color: #72869a !important; }
.eva-message-actions { display: flex; align-items: center; justify-content: flex-end; gap: 8px; padding-top: 7px; border-top: 1px solid #edf1f5; }.eva-message-actions span { margin-right: auto; color: #7b8998; font-size: 11px; }.eva-message-actions .btn { min-height: 30px; padding: 4px 10px; font-size: 12px; }
.eva-events-block { display: grid; gap: 6px; }.eva-events-toggle { display: flex; align-items: center; gap: 7px; width: 100%; padding: 8px 11px; border: 1px solid #dbe4ec; border-radius: 9px; background: #f4f7fb; color: #45607a; font: inherit; font-size: 12px; cursor: pointer; }.eva-events-toggle:hover { border-color: #b9cde2; background: #eef4fa; }.eva-events-chevron { width: 12px; color: #6b8098; }.eva-events-title { font-weight: 700; }.eva-events-toggle em { margin-left: auto; color: #8291a0; font-style: normal; font-size: 10px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 55%; }
.eva-inline-events { display: grid; gap: 1px; padding: 1px; margin: 0; overflow: hidden; border: 1px solid #dbe4ec; border-radius: 9px; background: #e7edf3; list-style: none; }.eva-inline-events li { display: grid; grid-template-columns: 24px minmax(0,1fr) auto; align-items: center; gap: 8px; padding: 9px 10px; background: #fff; }.eva-inline-events i { display: grid; place-items: center; width: 22px; height: 22px; color: #fff; border-radius: 50%; background: #8497aa; font-size: 10px; font-style: normal; }.eva-inline-events li.done i { background: #159783; }.eva-inline-events li.running i { background: #1769e0; }.eva-inline-events li.failed i { background: #c43d45; }.eva-inline-events li > span { display: grid; gap: 2px; min-width: 0; }.eva-inline-events b { overflow: hidden; color: #344d65; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }.eva-inline-events small { overflow: hidden; color: #7b8b9a; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }.eva-inline-events em { color: #748596; font-size: 10px; font-style: normal; }
.eva-agent-composer { display: grid; flex: 0 0 auto; gap: 8px; padding: 12px 14px max(12px,env(safe-area-inset-bottom)); border-top: 1px solid #d8e3ef; background: #fff; }.eva-agent-composer textarea { width: 100%; min-height: 86px; max-height: 190px; padding: 11px 12px; resize: vertical; border: 1px solid #bfcfdd; border-radius: 11px; outline: none; font: inherit; font-size: 14px; line-height: 1.6; }.eva-agent-composer textarea:focus { border-color: #4f91d5; box-shadow: 0 0 0 3px rgb(23 105 224 / 10%); }.eva-agent-composer footer { display: flex; align-items: center; gap: 10px; }.eva-agent-composer footer > :first-child { flex: 1 1 auto; min-width: 0; }.eva-agent-composer footer > button { flex: 0 0 auto; min-width: 92px; }
@media (max-width: 1100px) { .eva-harness-shell { grid-template-columns: auto minmax(0,1fr); } :deep(.eva-patient-picker) { grid-column: 1 / -1; } }
@media (max-width: 720px) { .xiaonan-harness-page { height: auto; min-height: calc(100dvh - 58px); } .eva-workbench-titlebar { align-items: flex-start; flex-wrap: wrap; } .eva-workbench-titlebar .head-actions { width: 100%; margin-left: 0; } .eva-harness-shell { grid-template-columns: minmax(0,1fr); min-height: 480px; } .eva-agent-message, .eva-agent-message.user { width: 100%; } .eva-agent-composer footer { align-items: stretch; flex-direction: column; } }
</style>
