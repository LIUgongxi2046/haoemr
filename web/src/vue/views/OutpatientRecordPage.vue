<script setup lang="ts">
import AgentAdoptionUndo from '../components/AgentAdoptionUndo.vue';
import { captureAgentAdoption, type AgentAdoptionSnapshot } from '../agent-adoption-undo';
import { captureResponseScope } from '../clinical-response-scope';
import { useUnsavedLeave } from '../composables/use-unsaved-leave';
import UnsavedChangesDialog from '../components/UnsavedChangesDialog.vue';
import { useQueryClient } from '@tanstack/vue-query';
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import type { DocumentVersionWire } from '../../generated/contracts';

import {
  ClinicalApiError,
  loadCurrentDocument,
  runQualityChecks,
  saveDocumentDraft,
  signDocument,
} from '../../clinical-api';
import { developmentCopy } from '../../development-copy';
import {
  clearSecureDocumentDraft,
  clinicalJsonSnapshot,
  clinicalContextFingerprint,
  loadSecureDocumentDraft,
  saveSecureDocumentDraft,
  type SecureDocumentDraft,
} from '../../secure-draft-cache';
import AgentInlineReview from '../components/AgentInlineReview.vue';
import AgentCandidateReview from '../components/AgentCandidateReview.vue';
import { useAgentDraftHandoff } from '../composables/use-agent-draft-handoff';
import type { MedicalAgentRunWire } from '../../generated/contracts';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';
import { useCurrentDocument } from '../composables/use-current-document';
import { useRecordQcSnapshot } from '../composables/use-record-qc-snapshot';
import { useRecordQcAutomation } from '../composables/use-record-qc-automation';
import EncounterRecordQcPanel from '../components/EncounterRecordQcPanel.vue';
import RecordQcPanel from '../components/RecordQcPanel.vue';

const sectionFields = [
  ['chief_complaint', '主诉', 3],
  ['present_illness', '现病史', 6],
  ['assessment', '诊断与评估', 3],
  ['treatment_plan', '治疗与随访计划', 3],
] as const;
const configuredAutosaveDelay = Number(import.meta.env.VITE_AUTOSAVE_DELAY_MS);
const autosaveDelayMs = import.meta.env.DEV && Number.isFinite(configuredAutosaveDelay)
  ? Math.min(5000, Math.max(100, configuredAutosaveDelay))
  : 800;

const current = useCurrentDocument();
const queryClient = useQueryClient();
const sections = ref<Record<string, unknown>>({});
const assistantOpen = ref(true);
const agentReview = ref<InstanceType<typeof AgentInlineReview> | null>(null);
const busy = ref<string | null>(null);
const notice = ref('');
const warningDisposition = ref('已核对警告，不影响本次签署');
const saveState = ref<'idle' | 'dirty' | 'autosaving' | 'saved' | 'offline' | 'conflict' | 'error' | 'signed'>('idle');
const lastSavedAt = ref('');
const secureStorageId = ref('');
const contextFingerprint = ref('');
const recoveryDraft = ref<SecureDocumentDraft | null>(null);
const conflictRemote = ref<DocumentVersionWire | null>(null);
const conflictLocal = ref<Record<string, unknown>>({});
const conflictBaseVersionId = ref('');
const editGeneration = ref(0);
const issue = computed(() => current.error.value ? toClinicalIssue(current.error.value) : null);
const document = computed(() => current.data.value?.document);
const hasUnsavedChanges = computed(() => ['dirty', 'autosaving', 'offline', 'conflict', 'error'].includes(saveState.value));
const qc = useRecordQcSnapshot(() => current.data.value, () => hasUnsavedChanges.value);
const qcAuto = useRecordQcAutomation({ context: () => current.data.value, dirty: () => hasUnsavedChanges.value,
  run: ({ lease, document }) => runQualityChecks(lease, document, 'POST_SAVE'), refresh: qc.refresh });
const qcFields = Object.fromEntries(sectionFields.map(([key, label]) => [key, label]));
function focusQcField(field: string) { window.document.getElementById(`record-field-${field}`)?.focus(); }
const saveStateLabel = computed(() => ({
  idle: '正在准备安全草稿', dirty: '有未保存更改', autosaving: '正在自动保存…', saved: '已自动保存',
  offline: '离线 · 加密草稿已保留', conflict: '版本冲突 · 禁止覆盖', error: '保存失败 · 草稿已保留', signed: '已签署只读',
})[saveState.value]);
const conflictFields = computed(() => {
  const remoteSections = conflictRemote.value?.sections ?? {};
  return sectionFields.filter(([field]) =>
    String(conflictLocal.value[field] ?? '') !== String(remoteSections[field] ?? ''));
});
const agentPatientId = computed(() => current.data.value?.lease.patient_id ?? null);
const agentEncounterId = computed(() => current.data.value?.lease.encounter_id ?? null);
const pendingAgentRunId = ref<string | null>(null);
const lastAdoption = ref<AgentAdoptionSnapshot | null>(null);
function undoAdoption(next: Record<string, unknown>, allRestored: boolean) {
  if (!document.value || document.value.status !== 'DRAFT' || busy.value || saveState.value === 'conflict') return;
  sections.value = next;
  if (allRestored) pendingAgentRunId.value = null;
  lastAdoption.value = null;
  markDirty();
  notice.value = '已撤销到编辑区，并保留未选择恢复的后续修改；请核对保存结果。';
}
const handoff = useAgentDraftHandoff(() => ({ patientId: agentPatientId.value, encounterId: agentEncounterId.value }));
watch(() => document.value?.document_id, () => { pendingAgentRunId.value = null; lastAdoption.value = null; });
function adoptAgentDraft(draft: Record<string, string>, candidate: MedicalAgentRunWire) {
  if (!document.value || document.value.status !== 'DRAFT' || busy.value
    || candidate.patient_id !== agentPatientId.value || candidate.encounter_id !== agentEncounterId.value
    || (candidate.target_type === 'DOCUMENT' && candidate.target_id !== document.value.document_id)) {
    notice.value = '候选或文书状态已变化，请在当前患者的可编辑草稿中重新运行医助。'; return;
  }
  if (hasUnsavedChanges.value) { notice.value = '请先保存现有修改，再审阅并带入医助候选。'; return; }
  const allowedDraft = Object.fromEntries(Object.entries(draft).filter(([field]) => sectionFields.some(([key]) => key === field)));
  if (!Object.keys(allowedDraft).length) { notice.value = '候选中没有当前编辑器支持的字段。'; return; }
  lastAdoption.value = captureAgentAdoption(document.value.document_id, candidate.run_id, sections.value, allowedDraft);
  sections.value = { ...sections.value, ...allowedDraft };
  pendingAgentRunId.value = candidate.run_id;
  markDirty();
  notice.value = '已带入医生确认的候选段落；保存时将核验来源版本并保留医助来源记录。';
}

const outpatientDraftObjective = computed(() => '基于当前门诊就诊信息起草病历文书候选，仅供医生审阅后采用，不自动写入。');

let debounceTimer: ReturnType<typeof setTimeout> | undefined;
let maximumTimer: ReturnType<typeof setTimeout> | undefined;
let saveInFlight = false;
let composing = false;

watch(() => current.data.value?.document.document_id, async (nextDocumentId, previousDocumentId, onCleanup) => {
  let stale = false; onCleanup(() => { stale = true; });
  const next = current.data.value?.document;
  if (!next) return;
  clearAutosaveTimers();
  // A context change must not delete the previous document's recoverable encrypted draft.
  secureStorageId.value = ''; contextFingerprint.value = '';
  sections.value = { ...next.sections };
  editGeneration.value = 0;
  conflictRemote.value = null;
  recoveryDraft.value = null;
  saveState.value = next.status === 'SIGNED' ? 'signed' : 'saved';
  const fingerprint = await clinicalContextFingerprint([
    current.data.value?.lease.tenant_id ?? '', current.data.value?.lease.patient_id ?? '',
    current.data.value?.lease.encounter_id ?? '', next.document_id,
  ]);
  if (stale) return;
  contextFingerprint.value = fingerprint;
  secureStorageId.value = `document-${fingerprint}`;
  if (next.status !== 'DRAFT') {
    await clearSecureCopyQuietly(secureStorageId.value);
    return;
  }
  try {
    const recovered = await loadSecureDocumentDraft(secureStorageId.value, fingerprint);
    if (!stale && recovered && JSON.stringify(recovered.sections) !== JSON.stringify(next.sections)) recoveryDraft.value = recovered;
  } catch {
    saveState.value = 'error';
    notice.value = '当前浏览器无法启用会话级加密草稿，已停止自动保存；请检查浏览器存储策略后重试。';
  }
}, { immediate: true });

onMounted(() => {
  window.addEventListener('online', handleOnline);
  window.addEventListener('offline', handleOffline);
  window.addEventListener('beforeunload', handleBeforeUnload);
});
onBeforeUnmount(() => {
  clearAutosaveTimers();
  window.removeEventListener('online', handleOnline);
  window.removeEventListener('offline', handleOffline);
  window.removeEventListener('beforeunload', handleBeforeUnload);
});

async function execute(label: string, action: () => Promise<void>) {
  busy.value = label;
  notice.value = '';
  try {
    await action();
  } catch (error) {
    const next = toClinicalIssue(error);
    notice.value = `${next.code}：${next.message}`;
  } finally {
    busy.value = null;
  }
}

function replaceCurrentDocument(nextDocument: NonNullable<typeof document.value>) {
  if (!current.data.value) return;
  if (current.data.value.document.document_id !== nextDocument.document_id) return;
  queryClient.setQueryData(current.contextKey.value, {
    lease: current.data.value.lease,
    document: nextDocument,
  });
}

function save() {
  void persistDraft(true);
}

function checkQuality() {
  const snapshot = current.data.value;
  if (!snapshot || busy.value || hasUnsavedChanges.value) return;
  qcAuto.cancel();
  const isCurrent = qc.captureContext();
  void execute('quality', async () => {
    try {
      const result = await runQualityChecks(snapshot.lease, snapshot.document);
      if (!isCurrent()) return;
      await qc.refresh();
      if (!isCurrent()) return;
      notice.value = result.length === 0 ? '本次确定性规则检查未发现问题，签署仍需服务端校验' : `发现 ${result.length} 项问题，请在右侧核对`;
    } catch (error) { if (isCurrent()) throw error; }
  });
}

function askAi() {
  void execute('ai', async () => {
    assistantOpen.value = true;
    await nextTick();
    const run = await agentReview.value?.runAgent();
    notice.value = run?.state === 'WAITING_FOR_REVIEW'
      ? 'AI医助 EVA 文书候选只供人工复核，不会自动写入病历。'
      : run?.failure_code === 'MEDICAL_AGENT_MODEL_UNAVAILABLE'
        ? '尚未生成文书候选：当前没有已发布且连接可用的真实模型；病历仍可手工完成。'
        : run
          ? `尚未生成文书候选：请查看上方运行记录${run.failure_code ? `（${run.failure_code}）` : ''}后转人工处理或修复后重试；病历仍可手工完成。`
          : '尚未生成文书候选：未能创建 AI医助 EVA 运行，请检查上方提示后转人工处理；病历仍可手工完成。';
  });
}

function sign() {
  const snapshot = current.data.value;
  if (!snapshot) return;
  void execute('sign', async () => {
    const evidence = await signDocument(snapshot.lease, snapshot.document, warningDisposition.value);
    notice.value = `签署证据已创建：${evidence.signature_status}`;
    if (secureStorageId.value) await clearSecureCopyQuietly(secureStorageId.value);
    await current.refetch();
    await queryClient.invalidateQueries({ queryKey: ['clinical', 'record-governance'] });
  });
}

function updateSection(field: string, event: Event) {
  sections.value = {
    ...sections.value,
    [field]: (event.target as HTMLTextAreaElement).value,
  };
  markDirty();
}

function compositionStart() {
  composing = true;
}

function compositionEnd() {
  composing = false;
  scheduleAutosave();
}

function markDirty() {
  if (document.value?.status !== 'DRAFT' || saveState.value === 'conflict') return;
  editGeneration.value += 1;
  saveState.value = navigator.onLine ? 'dirty' : 'offline';
  void persistSecureCopy();
  scheduleAutosave();
}

function scheduleAutosave(delay = autosaveDelayMs) {
  if (composing || saveState.value === 'conflict' || document.value?.status !== 'DRAFT') return;
  if (debounceTimer) clearTimeout(debounceTimer);
  debounceTimer = setTimeout(() => void persistDraft(false), delay);
  maximumTimer ??= setTimeout(() => void persistDraft(false), 5000);
}

function clearAutosaveTimers() {
  if (debounceTimer) clearTimeout(debounceTimer);
  if (maximumTimer) clearTimeout(maximumTimer);
  debounceTimer = undefined;
  maximumTimer = undefined;
}

async function persistSecureCopy(): Promise<boolean> {
  const snapshot = current.data.value;
  if (!snapshot || !secureStorageId.value || !contextFingerprint.value) return false;
  try {
    await saveSecureDocumentDraft(secureStorageId.value, {
      contextFingerprint: contextFingerprint.value,
      documentId: snapshot.document.document_id,
      baseVersionId: snapshot.document.document_version_id,
      baseRowVersion: snapshot.document.row_version,
      sections: clinicalJsonSnapshot(sections.value),
      updatedAt: new Date().toISOString(),
    });
    return true;
  } catch {
    clearAutosaveTimers();
    saveState.value = 'error';
    notice.value = '会话级加密草稿写入失败，系统已停止自动保存以避免误判；请先复制当前内容并检查浏览器存储策略。';
    return false;
  }
}

async function clearSecureCopyQuietly(storageId: string): Promise<void> {
  try { await clearSecureDocumentDraft(storageId); } catch { /* cleanup must not block the clinical workflow */ }
}

async function persistDraft(manual: boolean) {
  const snapshot = current.data.value;
  if (!snapshot || snapshot.document.status !== 'DRAFT' || saveInFlight || saveState.value === 'conflict') return;
  if (!hasUnsavedChanges.value) {
    if (manual) notice.value = '当前内容已保存，无需生成重复版本';
    return;
  }
  if (!navigator.onLine) {
    saveState.value = 'offline';
    await persistSecureCopy();
    return;
  }
  clearAutosaveTimers();
  saveInFlight = true;
  saveState.value = 'autosaving';
  const isCurrent = captureResponseScope();
  const startedGeneration = editGeneration.value;
  const submittedSections = clinicalJsonSnapshot(sections.value);
  try {
    const next = await saveDocumentDraft(snapshot.lease, snapshot.document, submittedSections, pendingAgentRunId.value);
    if (!isCurrent() || current.data.value?.document.document_id !== snapshot.document.document_id) return;
    pendingAgentRunId.value = null;
    replaceCurrentDocument(next);
    lastSavedAt.value = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(new Date());
    if (editGeneration.value === startedGeneration) {
      saveState.value = 'saved';
      await nextTick();
      if (current.data.value?.document.document_version_id === next.document_version_id) qcAuto.afterSave();
      if (secureStorageId.value) await clearSecureCopyQuietly(secureStorageId.value);
      if (manual) notice.value = `草稿已保存为不可变版本 v${next.version_no}`;
    } else {
      saveState.value = 'dirty';
      await persistSecureCopy();
      scheduleAutosave();
    }
  } catch (error) {
    if (!isCurrent() || current.data.value?.document.document_id !== snapshot.document.document_id) return;
    await persistSecureCopy();
    if (!isCurrent()) return;
    if (error instanceof ClinicalApiError && error.code === 'VERSION_CONFLICT') {
      conflictLocal.value = clinicalJsonSnapshot(sections.value);
      conflictBaseVersionId.value = snapshot.document.document_version_id;
      try { const remote = await loadCurrentDocument(snapshot.lease); if (!isCurrent()) return; conflictRemote.value = remote; } catch { if (!isCurrent()) return; conflictRemote.value = null; }
      saveState.value = 'conflict';
      notice.value = '检测到其他工作站已保存新版本；本地草稿仍在加密缓存中，系统不会覆盖。';
    } else {
      saveState.value = navigator.onLine ? 'error' : 'offline';
      notice.value = navigator.onLine ? '自动保存失败，本地加密草稿已保留；请重试。' : '网络已断开，本地加密草稿已保留。';
    }
  } finally {
    saveInFlight = false;
  }
}

function handleOffline() {
  if (document.value?.status === 'DRAFT' && hasUnsavedChanges.value) saveState.value = 'offline';
}

function handleOnline() {
  if (saveState.value === 'offline' || saveState.value === 'error') {
    saveState.value = 'dirty';
    scheduleAutosave(100);
  }
}

function handleBeforeUnload(event: BeforeUnloadEvent) {
  if (!hasUnsavedChanges.value) return;
  event.preventDefault();
  event.returnValue = '';
}

function restoreSecureDraft() {
  if (!recoveryDraft.value) return;
  sections.value = clinicalJsonSnapshot(recoveryDraft.value.sections);
  recoveryDraft.value = null;
  markDirty();
  notice.value = '加密草稿已恢复到编辑区，将基于当前服务端版本重新保存。';
}

async function discardSecureDraft() {
  recoveryDraft.value = null;
  if (secureStorageId.value) await clearSecureCopyQuietly(secureStorageId.value);
}

function chooseConflictField(field: string, source: 'local' | 'remote') {
  const value = source === 'local' ? conflictLocal.value[field] : (conflictRemote.value?.sections ?? {})[field];
  sections.value = { ...sections.value, [field]: value ?? '' };
}

function remoteConflictValue(field: string) {
  return String((conflictRemote.value?.sections ?? {})[field] ?? '—');
}

function continueAfterConflict() {
  if (!conflictRemote.value) return;
  replaceCurrentDocument(conflictRemote.value);
  conflictRemote.value = null;
  conflictBaseVersionId.value = '';
  editGeneration.value += 1;
  saveState.value = 'dirty';
  void persistSecureCopy();
  scheduleAutosave(100);
  notice.value = '已基于当前服务端版本继续，所选字段将生成新的不可变草稿版本。';
}

async function useRemoteVersion() {
  if (!conflictRemote.value) return;
  sections.value = { ...(conflictRemote.value.sections ?? {}) };
  replaceCurrentDocument(conflictRemote.value);
  conflictRemote.value = null;
  conflictBaseVersionId.value = '';
  saveState.value = 'saved';
  if (secureStorageId.value) await clearSecureCopyQuietly(secureStorageId.value);
  notice.value = '已采用服务器当前版本，本地冲突草稿已清除。';
}
const leave = useUnsavedLeave({ dirty: () => hasUnsavedChanges.value, busy: () => Boolean(busy.value) || saveState.value === 'autosaving', save: () => persistDraft(true), discard: async () => {
  clearAutosaveTimers();
  if (secureStorageId.value) await clearSecureDocumentDraft(secureStorageId.value);
  sections.value = { ...current.data.value?.document.sections }; recoveryDraft.value = null; conflictRemote.value = null; pendingAgentRunId.value = null; saveState.value = 'saved';
} });
</script>

<template>
  <UnsavedChangesDialog :open="leave.open.value" :busy="leave.busy.value" :message="leave.message.value" @save="leave.saveAndLeave" @discard="leave.discardAndLeave" @stay="leave.stay" />
  <section data-page-root class="content vue-native-page">
    <div class="page-heading"><div><p class="eyebrow">门诊 / 病历编辑</p><h1>门诊病历工作台</h1><p>记录当前门诊就诊的病历内容，并查看保存状态。</p></div>
      <div class="status-legend" :class="`save-${saveState}`"><span class="dot" :class="saveState === 'saved' || saveState === 'signed' ? 'success' : saveState === 'conflict' || saveState === 'error' ? 'danger' : 'warning'" />{{ saveStateLabel }}<small v-if="lastSavedAt"> · {{ lastSavedAt }}</small></div></div>
    <section class="patient-strip" aria-label="患者上下文"><div class="patient-avatar">{{ developmentCopy.patientAvatar }}</div>
      <div><strong>{{ developmentCopy.patientName }}</strong><span>{{ developmentCopy.contextNotice }}</span></div><dl>
        <div><dt>患者标识</dt><dd>…{{ current.data.value?.lease.patient_id?.slice(-6) }}</dd></div>
        <div><dt>就诊类型</dt><dd>门诊 · 进行中</dd></div><div><dt>数据范围</dt><dd>当前患者 / 当前就诊</dd></div></dl>
      <span v-if="current.data.value" class="lease-badge">已核验</span></section>
    <p v-if="handoff.notice.value" role="status">{{ handoff.notice.value }}</p>
    <AgentAdoptionUndo :snapshot="lastAdoption" :document-id="document?.document_id ?? ''" :sections="sections" :labels="qcFields" :disabled="Boolean(busy) || document?.status !== 'DRAFT' || saveState === 'conflict' || saveState === 'autosaving'" @undo="undoAdoption" />
    <AgentCandidateReview v-if="handoff.run.value" :run="handoff.run.value" allow-document-adoption @adopt-draft="adoptAgentDraft" />
    <AgentInlineReview allow-document-adoption @adopt-draft="adoptAgentDraft" v-if="current.data.value" ref="agentReview" agent-code="DOCUMENT_DRAFTER" stage-code="OUTPATIENT" :objective="outpatientDraftObjective" :patient-id="agentPatientId" :encounter-id="agentEncounterId" target-type="ENCOUNTER" :target-id="agentEncounterId" title="AI 门诊文书起草候选" source-route="opd-record" />
    <ClinicalPageState v-if="current.isPending.value" kind="loading" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="current.refetch()" />
    <div v-else-if="document && current.data.value" class="clinical-grid" :class="{ 'assistant-collapsed': !assistantOpen }">
      <section class="editor-card" aria-label="病历编辑器"><div class="card-toolbar"><div><h2>门诊病历</h2>
        <span class="state-chip" :class="{ signed: document.status === 'SIGNED' }">{{ document.status === 'SIGNED' ? '已签署' : `草稿 v${document.version_no}` }}</span></div>
        <div class="toolbar-actions"><button class="button secondary" :disabled="Boolean(busy) || hasUnsavedChanges" @click="checkQuality">运行质控</button>
          <button class="button ai" :disabled="Boolean(busy) || document.status === 'SIGNED'" @click="askAi">AI 辅助</button>
          <button class="button primary" :disabled="Boolean(busy) || document.status === 'SIGNED' || saveState === 'autosaving' || saveState === 'conflict'" @click="save">{{ saveState === 'autosaving' ? '保存中…' : '立即保存' }}</button></div></div>
        <div v-if="notice" class="notice" role="status">{{ notice }}</div>
        <section v-if="recoveryDraft" class="draft-recovery-banner" aria-label="加密草稿恢复"><div><strong>检测到本会话的未提交加密草稿</strong><span>保存于 {{ new Date(recoveryDraft.updatedAt).toLocaleString('zh-CN') }}；恢复后必须重新审阅差异。</span></div><div><button class="button secondary" @click="discardSecureDraft">丢弃</button><button class="button primary" @click="restoreSecureDraft">恢复到编辑区</button></div></section>
        <section v-if="saveState === 'conflict'" class="draft-conflict-panel" role="alert"><header><div><strong>版本冲突：系统已禁止覆盖</strong><span>本地基于旧版本编辑，服务器已有新版本。请逐字段选择后再基于当前版本继续。</span></div><RouterLink v-if="conflictRemote && conflictBaseVersionId" :to="`/record-diff/${document.document_id}/${conflictBaseVersionId}/${conflictRemote.document_version_id}`">打开完整版本差异</RouterLink></header><article v-for="field in conflictFields" :key="field[0]"><strong>{{ field[1] }}</strong><div><span>本地草稿</span><p>{{ String(conflictLocal[field[0]] ?? '—') }}</p><button @click="chooseConflictField(field[0], 'local')">选择本地</button></div><div><span>服务器当前版</span><p>{{ remoteConflictValue(field[0]) }}</p><button @click="chooseConflictField(field[0], 'remote')">选择服务器</button></div></article><footer><button class="button secondary" @click="useRemoteVersion">全部采用服务器版本</button><button class="button primary" :disabled="!conflictRemote" @click="continueAfterConflict">基于当前版本继续</button></footer></section>
        <div class="document-meta"><span>文书类型：{{ document.document_type_code }}</span><span>内容哈希：{{ document.content_hash.slice(0, 12) }}…</span><span>停止输入 {{ autosaveDelayMs }}ms 自动保存 · 最长 5s</span><span>自动合并：禁用</span></div>
        <form class="record-form" @submit.prevent><label v-for="field in sectionFields" :key="field[0]" class="field-group"><span>{{ field[1] }}</span>
          <textarea :id="`record-field-${field[0]}`" :value="String(sections[field[0]] ?? '')" :rows="field[2]" :readonly="document.status === 'SIGNED' || saveState === 'conflict'" :aria-describedby="`${field[0]}-hint`" @compositionstart="compositionStart" @compositionend="compositionEnd" @input="updateSection(field[0], $event)" />
          <small :id="`${field[0]}-hint`">自动保存形成不可变草稿版本；断网时仅保留本会话加密副本；签署后不可修改。</small></label></form></section>
      <aside class="right-rail" aria-label="质控与 AI 辅助"><section class="side-card">
        <RecordQcPanel :snapshot="qc.snapshot.value" :version-id="document.document_version_id" :content-hash="document.content_hash" :fields="qcFields" :dirty="hasUnsavedChanges" :loading="qc.loading.value" :error="qc.error.value" :sections="sections" v-model:auto-enabled="qcAuto.enabled.value" :auto-running="qcAuto.running.value" :auto-error="qcAuto.error.value" @retry-auto="qcAuto.afterSave()" @retry="qc.refresh()" @locate="focusQcField" />
        <EncounterRecordQcPanel v-if="current.data.value" :lease="current.data.value.lease" :revision="qc.snapshot.value?.data_watermark ?? document.content_hash" />
        <label class="warning-disposition"><span>警告处置说明</span><input v-model="warningDisposition" /></label>
        <button class="button danger full" :disabled="Boolean(busy) || document.status === 'SIGNED' || hasUnsavedChanges" @click="sign">{{ document.status === 'SIGNED' ? '病历已签署' : hasUnsavedChanges ? '请先完成自动保存' : '签署当前版本' }}</button>
        <RouterLink class="record-governance-link" to="/record-qc">查看完整质控与签名证据</RouterLink></section>
        <section v-if="assistantOpen" class="side-card ai-card"><div class="side-card-title"><h2>AI医助 EVA</h2><span class="ai-state">医生确认</span></div>
          <div class="empty-state ai-empty"><span>✦</span><p>AI医助 EVA 不会直接改写病历</p><small>点击“AI 辅助”运行上方的文书起草候选；无合规模型时会明确说明原因。</small></div></section></aside>
    </div>
    <button class="ai-fab" aria-label="打开 AI医助 EVA 病历候选面板" :aria-expanded="assistantOpen" @click="assistantOpen = !assistantOpen"><img src="/brand/ai-medical-assistant-eva.png" alt="" width="32" height="32" /><small>{{ assistantOpen ? '收起 AI医助 EVA' : 'AI医助 EVA' }}</small></button>
  </section>
</template>
