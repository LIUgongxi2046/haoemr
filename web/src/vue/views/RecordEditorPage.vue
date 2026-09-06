<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { runQualityChecks, saveDocumentDraft, signDocument } from '../../clinical-api';
import AgentInlineReview from '../components/AgentInlineReview.vue';
import AgentCandidateReview from '../components/AgentCandidateReview.vue';
import { useAgentDraftHandoff } from '../composables/use-agent-draft-handoff';
import type { DocumentVersionWire, MedicalAgentRunWire } from '../../generated/contracts';
import BusinessActionDialog from '../components/BusinessActionDialog.vue';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import RecordPatientStrip from '../components/RecordPatientStrip.vue';
import { toClinicalIssue } from '../clinical-error';
import { useCurrentDocument } from '../composables/use-current-document';
import { useRecordQcSnapshot } from '../composables/use-record-qc-snapshot';
import RecordQcPanel from '../components/RecordQcPanel.vue';
import EncounterRecordQcPanel from '../components/EncounterRecordQcPanel.vue';
import { useRecordQcAutomation } from '../composables/use-record-qc-automation';

const sectionFields = [
  ['chief_complaint', '主诉', 3],
  ['present_illness', '现病史', 6],
  ['past_history', '既往史', 4],
  ['allergy_history', '过敏史', 3],
  ['physical_exam', '体格检查', 5],
  ['auxiliary_exam', '辅助检查', 5],
  ['assessment', '诊断与评估', 3],
  ['treatment_plan', '治疗计划', 4],
  ['followup_plan', '复诊与随访计划', 3],
] as const;

const current = useCurrentDocument();
const sections = ref<Record<string, unknown>>({});
const draftBase = ref<DocumentVersionWire | null>(null);
const busy = ref<string | null>(null);
const notice = ref('');
const savedAt = ref('');
const saveState = ref<'idle' | 'dirty' | 'saving' | 'saved' | 'conflict' | 'signed'>('idle');
const saveDialogOpen = ref(false);
const signDialogOpen = ref(false);
const activeSection = ref('present_illness');

const issue = computed(() => current.error.value ? toClinicalIssue(current.error.value) : null);
const document = computed(() => current.data.value?.document);
const qcDirty = computed(() => ['dirty', 'saving', 'conflict'].includes(saveState.value));
const qc = useRecordQcSnapshot(() => current.data.value, () => qcDirty.value);
const qcAuto = useRecordQcAutomation({ context: () => current.data.value, dirty: () => qcDirty.value,
  run: ({ lease, document }) => runQualityChecks(lease, document, 'POST_SAVE'), refresh: qc.refresh });
const findings = qc.findings;
const qcFields = Object.fromEntries(sectionFields.map(([key, label]) => [key, label]));
const qcPanel = ref<HTMLElement | null>(null);
function showQuality() { qcPanel.value?.scrollIntoView({ block: 'nearest' }); }
const agentPatientId = computed(() => current.data.value?.lease.patient_id ?? null);
const agentEncounterId = computed(() => current.data.value?.lease.encounter_id ?? null);
const pendingAgentRunId = ref<string | null>(null);
const handoff = useAgentDraftHandoff(() => ({ patientId: agentPatientId.value, encounterId: agentEncounterId.value }));
watch(() => document.value?.document_id, () => { pendingAgentRunId.value = null; });
function adoptAgentDraft(draft: Record<string, string>, candidate: MedicalAgentRunWire) {
  if (!document.value || document.value.status !== 'DRAFT' || busy.value
    || candidate.patient_id !== agentPatientId.value || candidate.encounter_id !== agentEncounterId.value
    || (candidate.target_type === 'DOCUMENT' && candidate.target_id !== document.value.document_id)) {
    notice.value = '候选或文书状态已变化，请在当前患者的可编辑草稿中重新运行医助。'; return;
  }
  if (saveState.value === 'dirty' || saveState.value === 'conflict') { notice.value = '请先保存现有修改，再审阅并带入医助候选。'; return; }
  sections.value = { ...sections.value, ...draft };
  pendingAgentRunId.value = candidate.run_id;
  saveState.value = 'dirty';
  notice.value = '已带入医生确认的候选段落；保存时将核验来源版本并保留医助来源记录。';
}

const agentDraftObjective = computed(() => '基于当前病历起草或完善文书内容候选，仅供医生审阅后采用，不自动写入。');
const filledSections = computed(() => Object.values(sections.value).filter((value) => String(value ?? '').trim()).length);
const saveStateLabel = computed(() => ({
  idle: '尚未编辑', dirty: '有未保存更改', saving: '正在保存…', saved: '已保存',
  conflict: '版本冲突 · 禁止覆盖', signed: '已签署只读',
} as Record<string, string>)[saveState.value]);

watch(() => current.data.value?.document, (doc, previous) => {
  if (!doc) return;
  // QC/counter refreshes must not replace an in-progress local draft. Keep its
  // original row version too, so remote edits still produce a server conflict.
  if (previous?.document_id === doc.document_id && qcDirty.value) return;
  draftBase.value = doc;
  sections.value = { ...(doc.sections ?? {}) };
  savedAt.value = '';
  saveState.value = doc.status === 'SIGNED' ? 'signed' : 'idle';
}, { immediate: true });

function updateSection(field: string, event: Event) {
  sections.value = { ...sections.value, [field]: (event.target as HTMLTextAreaElement).value };
  saveState.value = document.value?.status === 'DRAFT' ? 'dirty' : saveState.value;
}

function save() {
  if (saveState.value === 'dirty') saveDialogOpen.value = true;
}

async function confirmSave() {
  const snapshot = current.data.value;
  if (!snapshot || busy.value || snapshot.document.status !== 'DRAFT') return;
  busy.value = 'save'; notice.value = ''; saveState.value = 'saving';
  const submitted = { ...sections.value };
  try {
    const saved = await saveDocumentDraft(snapshot.lease, draftBase.value ?? snapshot.document, submitted, pendingAgentRunId.value);
    pendingAgentRunId.value = null;
    saveDialogOpen.value = false;
    await current.refetch();
    await nextTick();
    if (current.data.value?.document.document_id !== saved.document_id) return;
    draftBase.value = saved;
    saveState.value = JSON.stringify(sections.value) === JSON.stringify(submitted) ? 'saved' : 'dirty';
    if (current.data.value?.document.document_version_id === saved.document_version_id) qcAuto.afterSave();
    savedAt.value = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(new Date());
    notice.value = saveState.value === 'dirty' ? '提交内容已保存；保存期间继续输入的内容仍待保存。' : '草稿已保存为新的不可变版本';
  } catch (error) {
    const next = toClinicalIssue(error);
    saveState.value = next.code === 'VERSION_CONFLICT' ? 'conflict' : 'dirty';
    notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = null; }
}

function checkQuality() {
  const snapshot = current.data.value;
  if (!snapshot || busy.value || qcDirty.value) return;
  qcAuto.cancel();
  const isCurrent = qc.captureContext();
  busy.value = 'quality'; notice.value = '';
  runQualityChecks(snapshot.lease, snapshot.document)
    .then(async (result) => {
      if (!isCurrent()) return;
      await qc.refresh();
      if (!isCurrent()) return;
      notice.value = result.length === 0 ? '本次确定性规则检查未发现问题，签署仍需服务端校验' : `发现 ${result.length} 项问题，请在当前文书质控中核对`;
      showQuality();
    })
    .catch((error) => { if (!isCurrent()) return; const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; })
    .finally(() => { busy.value = null; });
}

function sign() {
  signDialogOpen.value = true;
}

function focusSection(field: string) {
  activeSection.value = field;
  window.document.getElementById(`record-field-${field}`)?.focus();
}

function outlineState(field: string) {
  if (activeSection.value === field) return ['当前', 'blue'] as const;
  return String(sections.value[field] ?? '').trim() ? ['已填写', 'green'] as const : ['待完善', 'amber'] as const;
}

function confirmSign() {
  const snapshot = current.data.value;
  if (!snapshot || busy.value) return;
  busy.value = 'sign'; notice.value = '';
  signDocument(snapshot.lease, snapshot.document, '医生在专注编辑器中复核后签署')
    .then(async (evidence) => {
      notice.value = `签署证据已创建：${evidence.signature_status}`;
      signDialogOpen.value = false;
      await current.refetch();
    })
    .catch((error) => { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; })
    .finally(() => { busy.value = null; });
}
</script>

<template>
  <section data-page-root class="content vue-native-page">
    <div class="page-heading"><div><h1>病历 · 专注编辑</h1><p>{{ document ? `草稿 v${document.version_no} · ${document.document_type_code}` : '当前版本' }} · {{ saveStateLabel }}</p></div>
      <div class="toolbar-actions"><RouterLink class="btn" to="/record-sources">来源证据</RouterLink><button class="btn" type="button" :disabled="Boolean(busy) || saveState === 'dirty'" @click="checkQuality">质控 {{ findings.length }}</button><button class="btn primary" type="button" :disabled="Boolean(busy) || document?.status !== 'DRAFT' || saveState === 'dirty'" @click="sign">提交审签</button></div></div>
    <RecordPatientStrip />
    <p v-if="handoff.notice.value" role="status">{{ handoff.notice.value }}</p>
    <AgentCandidateReview v-if="handoff.run.value" :run="handoff.run.value" allow-document-adoption @adopt-draft="adoptAgentDraft" />
    <AgentInlineReview allow-document-adoption @adopt-draft="adoptAgentDraft" v-if="current.data.value" agent-code="DOCUMENT_DRAFTER" stage-code="OUTPATIENT" :objective="agentDraftObjective" :patient-id="agentPatientId" :encounter-id="agentEncounterId" target-type="DOCUMENT" :target-id="document?.document_id" title="AI 病历起草候选" source-route="record-editor" />
    <ClinicalPageState v-if="current.isPending.value" kind="loading" message="正在加载当前病历版本" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="current.refetch()" />
    <div v-else-if="document && current.data.value" class="focus-editor record-real-focus">
      <aside class="focus-outline card"><div class="card-head">文书章节 <span class="sub">{{ Math.round((filledSections / sectionFields.length) * 100) }}%</span></div><button v-for="(item, index) in sectionFields" :key="item[0]" class="outline-item" :class="{ active: activeSection === item[0] }" type="button" @click="focusSection(item[0])"><span>{{ index + 1 }}</span><b>{{ item[1] }}</b><em class="status" :class="outlineState(item[0])[1]">{{ outlineState(item[0])[0] }}</em></button></aside>
      <section class="card focus-canvas" aria-label="专注编辑器"><div class="editor-ribbon"><button class="btn sm" type="button" @click="showQuality">质控问题</button><span class="grow" /><span class="status green">● {{ savedAt || '当前版本已载入' }}</span></div>
        <div ref="qcPanel"><RecordQcPanel :snapshot="qc.snapshot.value" :version-id="document.document_version_id" :content-hash="document.content_hash" :fields="qcFields" :dirty="qcDirty" :loading="qc.loading.value" :error="qc.error.value" :sections="sections" v-model:auto-enabled="qcAuto.enabled.value" :auto-running="qcAuto.running.value" :auto-error="qcAuto.error.value" @retry-auto="qcAuto.afterSave()" @retry="qc.refresh()" @locate="focusSection" /></div>
        <EncounterRecordQcPanel v-if="current.data.value" :lease="current.data.value.lease" :revision="qc.snapshot.value?.data_watermark ?? document.content_hash" />
        <div v-if="notice" class="notice record-editor-notice" role="status">{{ notice }}</div><div v-if="saveState === 'conflict'" class="notice hard" role="alert"><div class="notice-title">版本冲突：系统已禁止覆盖</div>其他工作站已保存新版本，请刷新后基于当前版本继续。</div>
        <article class="document-sheet"><h2>临床病历</h2><p class="document-meta">当前患者 · 当前就诊 · {{ document.document_type_code }}</p><div v-for="field in sectionFields" :key="field[0]" class="doc-section"><b>{{ field[1] }}</b><textarea :id="`record-field-${field[0]}`" :aria-label="String(field[1])" class="field textarea" :class="{ 'warning-field': field[0] === 'treatment_plan', 'source-field': field[0] === 'assessment' }" :value="String(sections[field[0]] ?? '')" :rows="field[2]" :readonly="document.status !== 'DRAFT' || saveState === 'conflict'" @focus="activeSection = field[0]" @input="updateSection(field[0], $event)" /></div></article>
        <div class="footer-actions"><RouterLink class="btn" to="/record">退出编辑</RouterLink><span class="grow" /><button class="btn" type="button" :disabled="Boolean(busy) || saveState !== 'dirty'" @click="save">{{ saveState === 'saving' ? '保存中…' : '保存新版本' }}</button><button class="btn primary" type="button" :disabled="Boolean(busy) || saveState === 'dirty'" @click="checkQuality">签署前检查</button></div></section>
      <aside class="assist-rail"><button type="button" @click="showQuality"><b>{{ findings.length }}</b><span>质控</span></button><RouterLink to="/record-versions"><b>v{{ document.version_no }}</b><span>版本</span></RouterLink><RouterLink to="/pacs-viewer"><b>影</b><span>影像</span></RouterLink></aside>
    </div>
    <BusinessActionDialog :open="saveDialogOpen" title="保存病历编辑" description="编辑会以新的不可变草稿版本保存，旧版本不被覆盖。" eyebrow="病历 / 编辑确认" confirm-label="确认保存新版本" :busy="busy === 'save'" @cancel="saveDialogOpen = false" @confirm="confirmSave"><p class="dialog-warning">当前已填 {{ filledSections }} 个章节；保存后已有质控结果将按新内容重新计算。</p></BusinessActionDialog>
    <BusinessActionDialog :open="signDialogOpen" title="签署当前不可变版本" description="服务端会重新校验质控、版本和签名策略。" eyebrow="病历 / 签署确认" confirm-label="确认签署并留存证据" danger :busy="busy === 'sign'" @cancel="signDialogOpen = false" @confirm="confirmSign"><p class="dialog-warning">签署后正文不可覆盖；修改只能通过更正/补记弹窗生成新版本。</p></BusinessActionDialog>
  </section>
</template>
