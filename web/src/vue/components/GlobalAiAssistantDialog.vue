<script setup lang="ts">
import EvaMessageContent from './EvaMessageContent.vue';
import { useQuery } from '@tanstack/vue-query';
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { publishEvaConversation, type EvaChatMessage as ChatMessage, type EvaConversationTransfer } from '../eva-conversation-handoff';
import { issueAiLease, listModelDeployments } from '../../api/ai-platform';
import { cancelMedicalAgentRun, createMedicalAgentRun, getMedicalAgentRun, issueMedicalAgentCatalogLease, issueMedicalAgentRunLease, listMedicalAgentCatalog, resolveMedicalAgentRouting, retryMedicalAgentRun } from '../../api/medical-agents';
import type { MedicalAgentFamilyWire, MedicalAgentReleaseWire, MedicalAgentRunWire } from '../../generated/contracts';
import { toClinicalIssue } from '../clinical-error';
import { doctorFacingAiText, doctorFacingTeamName } from '../medical-ai-terminology';
import { medicalAgentRunStateLabel, presentMedicalAgentEvents, presentMedicalAgentResult } from '../medical-agent-run-presenter';
import { evaDefaultPatientContexts, hasEvaPatientContext, useEvaClinicalContext, type EvaPatientContext } from '../use-eva-clinical-context';
import { evaReviewBridge } from '../eva-review-bridge';
import { activatePatientReview, canReviewPatientRecord } from '../eva-patient-review';
import EvaComposerControls from './EvaComposerControls.vue';
import AgentCandidateReview from './AgentCandidateReview.vue';
import EvaPatientPicker from './EvaPatientPicker.vue';
import EvaStagePicker from './EvaStagePicker.vue';
import XiaonanAgentTeamRail from './XiaonanAgentTeamRail.vue';

type AuthorizationLevel = 'READ_ONLY' | 'STANDARD' | 'EXTENDED';
type ContextScope = 'RECORDS' | 'ORDERS' | 'RESULTS' | 'TASKS' | 'ATTACHMENTS' | 'CONFIGURATION';
interface TaskEvent { id: string; label: string; detail: string; status: 'running' | 'done' | 'waiting' | 'failed' }

const props = defineProps<{ open: boolean; mode: 'center' | 'side'; routeId: string; contextLabel: string; patientId: string | null; encounterId: string | null; taskId: string | null }>();
const emit = defineEmits<{ close: []; 'mode-change': [mode: 'center' | 'side'] }>();
const routePatientContext: EvaPatientContext = {
  patientId: props.patientId ?? '', encounterId: props.encounterId ?? '', patientName: '当前患者',
  patientSummary: props.contextLabel, label: '当前就诊', scene: props.contextLabel.includes('住院') ? '住院' : props.contextLabel.includes('急诊') ? '急诊' : '门诊',
};
const initialContext = evaDefaultPatientContexts.find((item) => item.patientId === props.patientId && item.encounterId === props.encounterId)
  ?? (hasEvaPatientContext(routePatientContext) ? routePatientContext : evaDefaultPatientContexts[0]);
const patient = useEvaClinicalContext(initialContext);
const hasPatientContext = computed(() => hasEvaPatientContext(patient.current.value));
const rootElement = ref<HTMLDialogElement | HTMLElement | null>(null);
const centerWidth = ref<number | null>(null);
const messages = ref<ChatMessage[]>([]);
const router = useRouter();
let transferred = false;
let pendingRun: EvaConversationTransfer['pendingRun'] = null;
onBeforeUnmount(() => { transferred = true; });
const draft = ref('');
const busy = ref(false);
const notice = ref('');
const selectedAgentCode = ref('');
const selectedStageCode = ref('');
const selectedModelId = ref('');
const authorizationLevel = ref<AuthorizationLevel>('STANDARD');
const contextScopes = ref<ContextScope[]>(['RECORDS', 'ORDERS', 'RESULTS', 'TASKS', 'CONFIGURATION']);
const teamCollapsed = ref(false);
const stagePickerCollapsed = ref(false);
const sidePatientOpen = ref(false);
// 医生是否在医助团队里手动选择了主医助/子医助；未手动选择时由后端按
// source_route 动态编排（单一事实来源在后端 AgentOrchestrator）。
const userOverride = ref(false);

const agentQuery = useQuery({ queryKey: ['global-eva', 'agents'], queryFn: async () => listMedicalAgentCatalog(await issueMedicalAgentCatalogLease()), enabled: computed(() => props.open), retry: false, staleTime: 5 * 60_000, gcTime: 0 });
const modelLeaseQuery = useQuery({ queryKey: ['global-eva', 'model-lease'], queryFn: () => issueAiLease('AI_ASSISTANT_MODEL_SELECTION'), enabled: computed(() => props.open), retry: false, staleTime: 5 * 60_000, gcTime: 0 });
const modelsQuery = useQuery({ queryKey: ['global-eva', 'models'], queryFn: () => listModelDeployments(modelLeaseQuery.data.value!), enabled: computed(() => props.open && Boolean(modelLeaseQuery.data.value)), retry: false, staleTime: 60_000 });
const runLeaseQuery = useQuery({ queryKey: computed(() => ['global-eva', 'run-lease', patient.current.value.patientId, patient.current.value.encounterId]), queryFn: () => issueMedicalAgentRunLease(hasPatientContext.value ? patient.current.value.patientId : null, hasPatientContext.value ? patient.current.value.encounterId : null), enabled: computed(() => props.open), retry: false, staleTime: 5 * 60_000, gcTime: 0 });
const routingQuery = useQuery({ queryKey: computed(() => ['global-eva', 'routing', props.routeId]), queryFn: async () => resolveMedicalAgentRouting(await issueMedicalAgentCatalogLease(), props.routeId), enabled: computed(() => props.open && Boolean(props.routeId)), retry: false, staleTime: 5 * 60_000, gcTime: 0 });

const agents = computed(() => agentQuery.data.value ?? []);
const availableModels = computed(() => (modelsQuery.data.value ?? []).filter((model) => model.status === 'ACTIVE'
  && model.evaluation_status === 'APPROVED' && model.connection_status === 'READY' && model.release_evidence_ready
  && !model.test_only && model.endpoint_url?.includes('.example/') !== true));
const modelAvailabilityNotice = computed(() => {
  if (modelsQuery.isPending.value || modelsQuery.isError.value || availableModels.value.length) return '';
  return '当前没有可供 AI医助 EVA 使用的合规模型。请由模型管理员完成真实模型登记、连接检测与独立评测后再试。';
});
const selectedAgent = computed(() => agents.value.find((family) => family.main_agent.agent_code === selectedAgentCode.value));
const selectedChild = computed(() => selectedAgent.value?.child_agents.find((child) => child.stage_code === selectedStageCode.value));
const selectedModel = computed(() => availableModels.value.find((model) => model.model_deployment_id === selectedModelId.value));
const issue = computed(() => { const error = agentQuery.error.value ?? modelLeaseQuery.error.value ?? modelsQuery.error.value ?? runLeaseQuery.error.value; return error ? toClinicalIssue(error) : null; });
const childAgentCount = computed(() => agents.value.reduce((count, family) => count + family.child_agents.length, 0));

watch([() => props.open, () => props.mode], async ([open, mode]) => { await nextTick(); const element = rootElement.value; if (!(element instanceof HTMLDialogElement)) return; if (mode === 'center' && open && !element.open) element.showModal(); if (!open && element.open) element.close(); }, { immediate: true });
watch([() => props.open, () => props.mode], ([open, mode]) => { if (!open) return; teamCollapsed.value = mode === 'side'; }, { immediate: true });
watch([agents, routingQuery.data, () => props.routeId, () => props.open], ([next, routing, routeId, open]) => {
  if (!open || !next.length) return;
  if (userOverride.value) return;
  const family = next.find((item) => item.main_agent.agent_code === routing?.main_agent_code) ?? next[0];
  selectedAgentCode.value = family.main_agent.agent_code;
  const child = family.child_agents.find((item) => item.stage_code === routing?.stage_code) ?? family.child_agents[0];
  selectedStageCode.value = child?.stage_code ?? '';
}, { immediate: true });
watch(() => props.routeId, () => { userOverride.value = false; });
watch(selectedAgent, (agent) => { if (agent && !agent.child_agents.some((child) => child.stage_code === selectedStageCode.value)) selectedStageCode.value = agent.child_agents[0]?.stage_code ?? ''; });
watch(availableModels, (next) => { if (next.length && !next.some((model) => model.model_deployment_id === selectedModelId.value)) selectedModelId.value = next[0].model_deployment_id; }, { immediate: true });
watch(() => props.open, (open) => {
  if (!open) return;
  const payload = evaReviewBridge.payload;
  if (!payload) return;
  if (payload.objective) messages.value.push({ id: crypto.randomUUID(), role: 'user', text: payload.objective });
  if (payload.patient) patient.current.value = { ...payload.patient };
  if (payload.result) messages.value.push({ id: crypto.randomUUID(), role: 'assistant', text: payload.result, run: payload.run, runId: payload.run?.run_id, runState: payload.run?.state, rowVersion: payload.run?.row_version });
  evaReviewBridge.payload = null;
}, { immediate: true });

function clinicianAgentName(name: string) { return doctorFacingTeamName(name); }
function scopeLabel(scope: ContextScope) { return ({ RECORDS: '病历文书', ORDERS: '医嘱执行', RESULTS: '检查检验', TASKS: '任务随访', ATTACHMENTS: '病历附件', CONFIGURATION: '知识与规则' } as Record<ContextScope, string>)[scope]; }
function requestClose() { emit('close'); }
function changeMode(mode: 'center' | 'side') { if (mode !== props.mode) emit('mode-change', mode); }
function cancel(event: Event) { event.preventDefault(); requestClose(); }
function closed() { if (props.open) requestClose(); }
function selectAgent(agent: MedicalAgentFamilyWire) { selectedAgentCode.value = agent.main_agent.agent_code; userOverride.value = true; }
function selectStage(child: MedicalAgentReleaseWire) { selectedStageCode.value = child.stage_code; draft.value = doctorFacingAiText(child.question_examples[0] ?? child.current_action); userOverride.value = true; }

function initialEvents(child: MedicalAgentReleaseWire): TaskEvent[] { return [
  { id: crypto.randomUUID(), label: 'AI医助 EVA 规划当前页面任务', detail: props.contextLabel, status: 'done' },
  { id: crypto.randomUUID(), label: '读取已选诊疗信息', detail: contextScopes.value.map(scopeLabel).join('、'), status: 'running' },
  { id: crypto.randomUUID(), label: `安排${doctorFacingAiText(child.display_name)}`, detail: doctorFacingAiText(child.current_action), status: 'waiting' },
  { id: crypto.randomUUID(), label: '执行模型与工具调用', detail: selectedModel.value?.display_name ?? '机构默认模型', status: 'waiting' },
] }
function mapEvents(run: MedicalAgentRunWire): TaskEvent[] { return presentMedicalAgentEvents(run, `${selectedModel.value?.display_name ?? '机构默认模型'} · ${contextScopes.value.map(scopeLabel).join('、')}`); }
const terminalRunStates = new Set<MedicalAgentRunWire['state']>(['WAITING_FOR_REVIEW', 'COMPLETED', 'PARTIAL', 'BLOCKED', 'FAILED', 'CANCELLED']);
const retryableRunStates = new Set<MedicalAgentRunWire['state']>(['PARTIAL', 'BLOCKED', 'FAILED', 'CANCELLED']);
const delay = (duration: number) => new Promise((resolve) => window.setTimeout(resolve, duration));

function applyRun(message: ChatMessage, run: MedicalAgentRunWire, fallbackChildName = '诊疗环节医助') {
  message.processing = !terminalRunStates.has(run.state);
  message.run = run; message.events = mapEvents(run); message.runId = run.run_id; message.runState = run.state; message.rowVersion = run.row_version;
  message.text = terminalRunStates.has(run.state)
    ? `${presentMedicalAgentResult(run, 'markdown')}\n\n参与医助：${run.child_runs.map((item) => doctorFacingAiText(item.display_name)).join('、') || doctorFacingAiText(fallbackChildName)}。`
    : presentMedicalAgentResult(run, 'markdown');
}

async function pollRun(message: ChatMessage, lease: NonNullable<typeof runLeaseQuery.data.value>, patientId: string | null, encounterId: string | null, fallbackChildName: string) {
  for (let poll = 0; poll < 300; poll += 1) {
    if (transferred || !message.runId || terminalRunStates.has(message.runState!)) return;
    await delay(800);
    if (transferred) return;
    const run = await getMedicalAgentRun(lease, patientId, encounterId, message.runId);
    if (transferred) return;
    applyRun(message, run, fallbackChildName);
  }
  message.text = '任务仍在后台处理，运行记录已保存。';
}

async function send() {
  const text = draft.value.trim(); const lease = runLeaseQuery.data.value; const agent = selectedAgent.value; const child = selectedChild.value ?? agent?.child_agents[0];
  if (!text || !lease || !agent || !child || !selectedModelId.value || busy.value) return;
  busy.value = true; notice.value = ''; const responseId = crypto.randomUUID();
  messages.value.push({ id: crypto.randomUUID(), role: 'user', text }); messages.value.push({ id: responseId, role: 'assistant', text: '', processing: true, events: initialEvents(child) }); draft.value = '';
  try {
    const patientId = hasPatientContext.value ? patient.current.value.patientId : null; const encounterId = hasPatientContext.value ? patient.current.value.encounterId : null;
    const promise = createMedicalAgentRun(lease, { patientId, encounterId, mainAgentCode: userOverride.value ? agent.main_agent.agent_code : null, stageCode: userOverride.value ? child.stage_code : null, sourceRoute: props.routeId, targetType: patientId ? 'ENCOUNTER' : null, targetId: encounterId ?? null, objective: text, modelDeploymentId: selectedModelId.value, authorizationLevel: authorizationLevel.value, contextScopes: contextScopes.value });
    pendingRun = { messageId: responseId, promise };
    const run = await promise;
    if (transferred) return;
    const response = messages.value.find((item) => item.id === responseId)!; applyRun(response, run, child.display_name);
    await pollRun(response, lease, patientId, encounterId, child.display_name);
  } catch (error) { if (transferred) return; const next = toClinicalIssue(error); const response = messages.value.find((item) => item.id === responseId)!; response.text = `任务未完成：${next.message}`; response.events = (response.events ?? []).map((event) => event.status === 'done' ? event : { ...event, status: event.status === 'running' ? 'failed' : 'waiting' }); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = false; pendingRun = null; }
}
async function cancelRun(message: ChatMessage) {
  const lease = runLeaseQuery.data.value; if (!lease || !message.runId || !['QUEUED', 'RUNNING'].includes(message.runState ?? '')) return;
  try { const patientId = hasPatientContext.value ? patient.current.value.patientId : null; const encounterId = hasPatientContext.value ? patient.current.value.encounterId : null; const latest = await getMedicalAgentRun(lease, patientId, encounterId, message.runId); if (!['QUEUED', 'RUNNING'].includes(latest.state)) { applyRun(message, latest); return; } applyRun(message, await cancelMedicalAgentRun(lease, patientId, encounterId, latest.run_id, latest.row_version)); }
  catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
}
async function retryRun(message: ChatMessage) {
  if (!message.runId || !message.runState || !retryableRunStates.has(message.runState) || busy.value) return; busy.value = true; message.processing = true; notice.value = '';
  const patientId = hasPatientContext.value ? patient.current.value.patientId : null;
  const encounterId = hasPatientContext.value ? patient.current.value.encounterId : null;
  const promise = (async () => {
    const lease = await issueMedicalAgentRunLease(patientId, encounterId);
    const latest = await getMedicalAgentRun(lease, patientId, encounterId, message.runId!);
    return retryMedicalAgentRun(lease, patientId, encounterId, latest.run_id, latest.row_version);
  })();
  pendingRun = { messageId: message.id, promise };
  try {
    const run = await promise;
    if (transferred) return;
    applyRun(message, run);
    const lease = await issueMedicalAgentRunLease(patientId, encounterId);
    await pollRun(message, lease, patientId, encounterId, '诊疗环节医助');
  }
  catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = false; pendingRun = null; }
}
async function openPatientRecord(message: ChatMessage) {
  if (!canReviewPatientRecord(message, patient.current.value)) return;
  const path = activatePatientReview(patient.current.value);
  if (!path) return;
  // Keep this mounted conversation and its result actions alongside the record.
  changeMode('side');
  evaReviewBridge.armed = true;
  try { await router.push(path); }
  catch (error) { notice.value = toClinicalIssue(error).message; }
  finally { evaReviewBridge.armed = false; }
}

async function openFullWorkspace() {
  transferred = true;
  if (messages.value.length || draft.value.trim()) publishEvaConversation({
    messages: messages.value, patient: patient.current.value, draft: draft.value,
    agentCode: selectedAgentCode.value, stageCode: selectedStageCode.value,
    modelId: selectedModelId.value, authorizationLevel: authorizationLevel.value,
    contextScopes: contextScopes.value, pendingRun,
  });
  await router.push('/ai-assistant');
  requestClose();
}
function newTask() { messages.value = []; draft.value = ''; notice.value = '已创建空白医助任务。'; }
function resetPatient() { messages.value = []; draft.value = ''; notice.value = '已切换患者并创建空白任务。'; }
async function selectSearchPatient(value: Parameters<typeof patient.selectPatient>[0]) { await patient.selectPatient(value); resetPatient(); }
function selectEncounter(value: Parameters<typeof patient.selectEncounter>[0]) { patient.selectEncounter(value); resetPatient(); }
function selectDefault(value: Parameters<typeof patient.selectDefault>[0]) { patient.selectDefault(value); resetPatient(); }
function unbindPatient() { patient.unbind(); messages.value = []; draft.value = ''; }
function startWidthResize(event: PointerEvent) {
  const el = rootElement.value;
  if (!el) return;
  event.preventDefault();
  const startX = event.clientX;
  const startWidth = el.getBoundingClientRect().width;
  const isSide = props.mode === 'side';
  const minWidth = isSide ? 320 : 560;
  const maxWidth = Math.max(minWidth, window.innerWidth - (isSide ? 160 : 32));
  const onMove = (move: PointerEvent) => {
    const next = Math.min(maxWidth, Math.max(minWidth, startWidth - (move.clientX - startX)));
    if (props.mode === 'center') {
      centerWidth.value = next;
    } else {
      document.documentElement.style.setProperty('--assistant-side-width', `${next}px`);
    }
  };
  const onUp = () => {
    window.removeEventListener('pointermove', onMove);
    window.removeEventListener('pointerup', onUp);
  };
  window.addEventListener('pointermove', onMove);
  window.addEventListener('pointerup', onUp);
}
</script>

<template>
  <Teleport to="body">
    <component :is="mode === 'center' ? 'dialog' : 'aside'" v-if="open" ref="rootElement" :open="mode === 'side' ? true : undefined" :class="[mode === 'center' ? 'global-ai-dialog' : 'global-ai-side-panel', 'xiaonan-popup-root', 'eva-popup-root']" :style="mode === 'center' && centerWidth ? { width: `${centerWidth}px` } : undefined" role="dialog" :aria-modal="mode === 'center'" aria-labelledby="global-ai-dialog-title" aria-describedby="global-ai-dialog-context" @cancel="cancel" @close="closed">
      <button class="eva-popup-resizer" type="button" aria-label="拖拽调整窗口宽度" @pointerdown="startWidthResize"></button>
      <div class="eva-popup-shell">
        <header class="eva-popup-header"><img class="global-ai-mascot eva-avatar-motion" :data-active="busy" src="/brand/ai-medical-assistant-eva.png" alt="AI医助 EVA" width="46" height="46" /><div><span>临床任务工作台</span><h2 id="global-ai-dialog-title">AI医助 EVA</h2><p id="global-ai-dialog-context">{{ contextLabel }}<template v-if="taskId"> · 当前任务已连接</template></p></div><b>{{ agents.length }} 组 · {{ childAgentCount }} 位医助</b><nav aria-label="AI医助 EVA 窗口模式"><button type="button" :class="{ active: mode === 'center' }" @click="changeMode('center')">中窗</button><button type="button" :class="{ active: mode === 'side' }" @click="changeMode('side')">右侧窗</button></nav><button class="eva-popup-close" type="button" aria-label="关闭 AI医助 EVA" @click="requestClose">×</button></header>
        <section class="eva-popup-workspace">
          <XiaonanAgentTeamRail :agents="agents" :selected-agent-code="selectedAgentCode" :collapsed="teamCollapsed" :busy="busy" @toggle="teamCollapsed = !teamCollapsed" @select="selectAgent" />
          <section class="eva-popup-main">
            <div v-if="selectedAgent" class="eva-agent-context"><strong>{{ clinicianAgentName(selectedAgent.main_agent.display_name) }}</strong><span>{{ selectedAgent.main_agent.doctor_facing_summary }}</span></div>
            <EvaStagePicker v-if="selectedAgent" :children="selectedAgent.child_agents" :selected-stage-code="selectedStageCode" :collapsed="stagePickerCollapsed" :busy="busy" @update:collapsed="stagePickerCollapsed = $event" @select="selectStage" />
            <section class="eva-popup-thread" aria-live="polite">
              <div v-if="issue" class="eva-popup-empty error">AI医助 EVA 工作台暂时不可用：{{ issue.message }}</div>
              <div v-else-if="messages.length === 0" class="eva-popup-empty illustrated"><img class="eva-illustration-motion" src="/brand/ai-medical-assistant-eva-workbench.png" alt="AI医助 EVA 医疗任务工作台" /><strong>交给 AI医助 EVA 一项完整任务</strong><p>任务规划、诊疗信息、模型、工具和医助处理过程都会在对话里呈现。</p></div>
              <article v-for="message in messages" :key="message.id" class="eva-popup-message" :class="message.role"><header><b>{{ message.role === 'user' ? '医生' : 'AI医助 EVA' }}</b><span>{{ message.role === 'user' ? '任务' : message.runId ? `…${message.runId.slice(-8)}` : '执行中' }}</span></header><details v-if="message.events?.length" class="eva-popup-events"><summary>执行过程 · {{ message.events.length }} 条记录</summary><ol><li v-for="event in message.events" :key="event.id" :class="event.status"><i>{{ event.status === 'done' ? '✓' : event.status === 'failed' ? '!' : event.status === 'running' ? '•' : '·' }}</i><span><b>{{ event.label }}</b><small>{{ event.detail }}</small></span></li></ol></details><p v-if="message.role === 'user'">{{ message.text }}</p><EvaMessageContent v-else :text="message.text" :processing="busy && message.processing === true" :phase="message.events?.filter(event => event.status !== 'waiting').at(-1)?.label" /><AgentCandidateReview v-if="message.run && terminalRunStates.has(message.run.state)" :run="message.run" @navigate="requestClose" /><footer v-if="message.role === 'assistant' && message.runId" class="eva-popup-message-actions"><span>{{ message.runState ? medicalAgentRunStateLabel(message.runState) : '' }}</span><button v-if="message.runState === 'QUEUED' || message.runState === 'RUNNING'" class="btn" type="button" @click="cancelRun(message)">取消</button><button v-else-if="message.runState && retryableRunStates.has(message.runState)" class="btn" type="button" :disabled="busy" @click="retryRun(message)">重试</button><button v-if="canReviewPatientRecord(message, patient.current.value)" class="btn primary" type="button" @click="openPatientRecord(message)">打开患者病历复核</button></footer></article>
            </section>
            <form class="eva-popup-composer" @submit.prevent="send"><p v-if="modelAvailabilityNotice" role="status" class="inline-notice info">{{ modelAvailabilityNotice }}</p><p v-if="notice" role="status" class="inline-notice">{{ notice }}</p><textarea v-model="draft" :disabled="busy" rows="4" aria-label="向 AI医助 EVA 描述诊疗任务" placeholder="描述需要完成的诊疗任务……" @keydown.enter.exact.prevent="send" /><footer><EvaComposerControls v-model:model-id="selectedModelId" v-model:authorization-level="authorizationLevel" v-model:context-scopes="contextScopes" :models="availableModels" :disabled="busy" compact /><div><button class="btn" type="button" @click="newTask">新任务</button><button class="btn" type="button" @click="openFullWorkspace">完整工作台</button><button class="btn primary" type="submit" :disabled="busy || !draft.trim() || !selectedModelId || !runLeaseQuery.data.value">{{ busy ? '执行中…' : '发送' }}</button></div></footer></form>
          </section>
          <div class="eva-patient-slot" :class="{ side: mode === 'side', open: sidePatientOpen }">
            <button v-if="mode === 'side' && !sidePatientOpen" type="button" class="eva-side-patient-bar" :aria-expanded="false" @click="sidePatientOpen = true"><span>当前患者</span><strong>{{ patient.current.value.patientId ? patient.current.value.patientName : '未选择患者' }}</strong><small>{{ patient.current.value.patientSummary }} · {{ patient.current.value.label }}</small><i aria-hidden="true">▴</i></button>
            <EvaPatientPicker v-if="mode !== 'side' || sidePatientOpen" :current="patient.current.value" :defaults="evaDefaultPatientContexts" :results="patient.results.value" :encounters="patient.encounters.value" :selected-patient-id="patient.selectedPatient.value?.patient_id ?? ''" :searching="patient.searching.value" :loading-encounters="patient.loadingEncounters.value" :notice="patient.notice.value" compact @search="patient.search" @select-default="selectDefault" @select-patient="selectSearchPatient" @select-encounter="selectEncounter" @unbind="unbindPatient" />
          </div>
        </section>
      </div>
    </component>
  </Teleport>
</template>

<style scoped>
:global(.global-ai-dialog.eva-popup-root) { width: min(1160px,calc(100vw - 32px)); height: min(860px,calc(100dvh - 32px)); max-height: none; }:global(.global-ai-side-panel.eva-popup-root) { width: var(--assistant-side-width); }.eva-popup-resizer { position: absolute; left: 0; top: 0; bottom: 0; width: 8px; padding: 0; border: 0; background: transparent; cursor: ew-resize; z-index: 10; }.eva-popup-resizer:hover, .eva-popup-resizer:active { background: linear-gradient(90deg, rgb(23 105 224 / 30%), transparent); }.eva-popup-shell { display: grid; grid-template-rows: auto minmax(0,1fr); width: 100%; height: 100%; min-height: 0; }.eva-popup-header { display: grid; grid-template-columns: 46px minmax(0,1fr) auto auto 34px; align-items: center; gap: 10px; min-height: 72px; padding: 10px 13px; color: #fff; background: linear-gradient(135deg,#193a59,#1769e0); }.eva-popup-header > img { width: 46px; height: 46px; object-fit: cover; border-radius: 50%; background: #fff; box-shadow: 0 0 0 2px rgb(255 255 255 / 28%); }.eva-popup-header > div { display: grid; gap: 2px; min-width: 0; }.eva-popup-header span { color: #bdd8f5; font-size: 10px; font-weight: 800; letter-spacing: .5px; }.eva-popup-header h2 { margin: 1px 0; color: #fff; font-size: 17px; }.eva-popup-header p { margin: 0; overflow: hidden; color: #d9e8f7; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }.eva-popup-header > b { padding: 6px 8px; border: 1px solid rgb(255 255 255 / 24%); border-radius: 999px; background: rgb(255 255 255 / 8%); font-size: 10px; white-space: nowrap; }.eva-popup-header nav { display: flex; gap: 2px; padding: 2px; border: 1px solid rgb(255 255 255 / 22%); border-radius: 8px; }.eva-popup-header button { height: 28px; padding: 0 8px; color: #fff; border: 0; border-radius: 6px; background: transparent; font-size: 11px; cursor: pointer; }.eva-popup-header nav button.active { color: #173e64; background: #fff; font-weight: 800; }.eva-popup-header .eva-popup-close { width: 34px; height: 34px; font-size: 22px; }.eva-popup-workspace { display: grid; grid-template-columns: auto minmax(0,1fr) 220px; min-width: 0; min-height: 0; overflow: hidden; }.eva-popup-main { display: grid; grid-template-rows: auto auto minmax(180px,1fr) auto; min-width: 0; min-height: 0; }.eva-agent-context { display: grid; gap: 2px; padding: 8px 12px; border-bottom: 1px solid #d8e3ef; background: #f7fafd; }.eva-agent-context strong { color: #24628f; font-size: 10px; }.eva-agent-context span { color: #6b7f91; font-size: 8px; line-height: 1.5; }.eva-popup-thread { display: grid; align-content: start; gap: 9px; min-height: 0; padding: 12px; overflow-y: auto; background: #fbfcfe; }.eva-popup-empty { padding: 24px 12px; color: #697d90; font-size: 12px; text-align: center; }.eva-popup-empty.error { color: #a32632; }.eva-popup-empty.illustrated { display: grid; justify-items: center; gap: 6px; max-width: 560px; margin: auto; }.eva-popup-empty img { width: min(100%,420px); max-height: 170px; object-fit: contain; border-radius: 10px; mix-blend-mode: multiply; }.eva-popup-empty strong { color: #29435d; font-size: 14px; }.eva-popup-empty p { margin: 0; line-height: 1.6; }.eva-popup-message { min-width: 0; display: grid; gap: 7px; width: min(100%,720px); padding: 16px; border: 1px solid #d8e3ed; border-radius: 10px; background: #fff; }.eva-popup-message.user { justify-self: end; width: min(78%,560px); border-color: #acd0ee; background: #edf6ff; }.eva-popup-message header { display: flex; justify-content: space-between; gap: 8px; }.eva-popup-message header b { color: #185b83; font-size: 12px; }.eva-popup-message header span { color: #8694a2; font-size: 10px; }.eva-popup-message > p { margin: 0; white-space: pre-wrap; color: #3c5268; font-size: 13px; line-height: 1.65; }.eva-popup-message ol { display: grid; gap: 1px; padding: 1px; margin: 0; border: 1px solid #dce5ed; border-radius: 8px; background: #e8eef3; list-style: none; }.eva-popup-message li { display: grid; grid-template-columns: 20px minmax(0,1fr); align-items: center; gap: 6px; padding: 7px 8px; background: #fff; }.eva-popup-message li i { display: grid; place-items: center; width: 18px; height: 18px; color: #fff; border-radius: 50%; background: #8497aa; font-size: 7px; font-style: normal; }.eva-popup-message li.done i { background: #159783; }.eva-popup-message li.running i { background: #1769e0; }.eva-popup-message li.failed i { background: #c43d45; }.eva-popup-message li span { display: grid; gap: 2px; min-width: 0; }.eva-popup-message li b, .eva-popup-message li small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.eva-popup-message li b { color: #3b5269; font-size: 11px; }.eva-popup-message li small { color: #81909f; font-size: 10px; }.eva-popup-composer { display: grid; gap: 7px; padding: 10px 12px max(10px,env(safe-area-inset-bottom)); border-top: 1px solid #d8e3ef; background: #fff; }.eva-popup-composer textarea { width: 100%; min-height: 70px; max-height: 145px; padding: 9px 10px; resize: vertical; border: 1px solid #bdcddd; border-radius: 9px; outline: none; font: inherit; font-size: 13px; line-height: 1.6; }.eva-popup-composer textarea:focus { border-color: #4f91d5; box-shadow: 0 0 0 3px rgb(23 105 224 / 10%); }.eva-popup-composer footer { display: flex; align-items: center; gap: 7px; }.eva-popup-composer footer > :first-child { flex: 1; min-width: 0; }.eva-popup-composer footer > div { display: flex; gap: 5px; }
.global-ai-side-panel.eva-popup-root .eva-popup-header { min-height: 56px; grid-template-columns: 40px minmax(0,1fr) auto 32px; gap: 7px; }
.global-ai-side-panel.eva-popup-root .eva-popup-header > div > span { display: none; }
.global-ai-side-panel.eva-popup-root .eva-popup-header h2 { font-size: 15px; }
.global-ai-side-panel.eva-popup-root .eva-popup-header > b { display: none; }
.global-ai-side-panel.eva-popup-root .eva-popup-header nav { grid-column: 3; }
.global-ai-side-panel.eva-popup-root .eva-popup-header .eva-popup-close { grid-column: 4; }
.global-ai-side-panel.eva-popup-root .eva-popup-workspace { grid-template-columns: auto minmax(0,1fr); grid-template-rows: minmax(0,1fr) auto; }
.global-ai-side-panel.eva-popup-root :deep(.xiaonan-harness-team-rail) { grid-column: 1; grid-row: 1 / -1; }
.global-ai-side-panel.eva-popup-root .eva-popup-main { grid-column: 2; grid-row: 1; }
.global-ai-side-panel.eva-popup-root .eva-patient-slot.side { grid-column: 2; grid-row: 2; min-width: 0; border-top: 1px solid #d8e3ef; }
.global-ai-side-panel.eva-popup-root .eva-patient-slot.side :deep(.eva-patient-picker) { width: 100%; max-height: 220px; border-left: 0; border-top: 0; }
.eva-patient-slot { min-width: 0; min-height: 0; }.eva-patient-slot :deep(.eva-patient-picker) { width: 100%; height: 100%; }.eva-side-patient-bar { display: flex; align-items: center; gap: 8px; width: 100%; min-height: 42px; padding: 8px 12px; color: inherit; border: 0; background: #f8fafc; text-align: left; cursor: pointer; }.eva-side-patient-bar span { color: #718397; font-size: 10px; font-weight: 800; }.eva-side-patient-bar strong { color: #244764; font-size: 12px; }.eva-side-patient-bar small { overflow: hidden; color: #617a90; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }.eva-side-patient-bar i { margin-left: auto; color: #7d90a3; font-size: 12px; font-style: normal; }
.eva-popup-message-actions { display: flex; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: 6px; padding-top: 6px; border-top: 1px solid #edf1f5; }.eva-popup-message-actions span { margin-right: auto; color: #7b8998; font-size: 10px; }.eva-popup-message-actions .btn { min-height: 28px; padding: 4px 9px; font-size: 11px; }
@media (max-width: 980px) { .eva-popup-workspace { grid-template-columns: auto minmax(0,1fr); overflow-y: auto; } :deep(.eva-patient-picker) { grid-column: 1 / -1; } .eva-popup-header > b { display: none; } }
@media (max-width: 700px) { :global(.global-ai-dialog.eva-popup-root) { width: calc(100vw - 12px); height: calc(100dvh - 12px); } .eva-popup-header { grid-template-columns: 42px minmax(0,1fr) auto 32px; gap: 7px; } .eva-popup-header > b { display: none; } .eva-popup-workspace { grid-template-columns: minmax(0,1fr); } .eva-popup-main { min-height: 560px; } .eva-popup-composer footer { align-items: stretch; flex-direction: column; } }
</style>

<style scoped>
.eva-popup-events { min-width: 0; }
.eva-popup-events summary { padding: 7px 0; color: #7b8da1; font-size: 11px; cursor: pointer; }
.eva-popup-message.assistant { gap: 13px; }
.global-ai-side-panel .eva-popup-message { width: 100%; }
.eva-popup-main, .eva-popup-thread { grid-template-columns: minmax(0, 1fr); }
.eva-popup-thread, .eva-popup-composer { min-width: 0; }
.eva-popup-composer footer { flex-wrap: wrap; }
.eva-popup-composer footer > div { flex-wrap: wrap; }
</style>
