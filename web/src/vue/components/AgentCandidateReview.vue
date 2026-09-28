<script setup lang="ts">
import EvaMessageContent from './EvaMessageContent.vue';
import { computed, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useClinicalContextStore } from '../stores/clinical-context';
import type { MedicalAgentRunWire } from '../../generated/contracts';
import { presentMedicalAgentResult } from '../medical-agent-run-presenter';
import { createMedicalAgentReminder } from '../../api/medical-agents';
import { toClinicalIssue } from '../clinical-error';
import BusinessActionDialog from './BusinessActionDialog.vue';
import { getKnowledgeChunkCitation, issueKnowledgeLease } from '../../api/knowledge';
import type { KnowledgeChunkCitationWire } from '../../generated/contracts';
import { clinicalResponseScope } from '../clinical-response-scope';
import { verifyKnowledgeCitation } from '../knowledge-citation';
import { ClinicalApiError } from '../../clinical-api';

const props = defineProps<{ run: MedicalAgentRunWire; allowDocumentAdoption?: boolean }>();
const emit = defineEmits<{ navigate: []; 'adopt-draft': [sections: Record<string, string>, run: MedicalAgentRunWire] }>();
const router = useRouter();
const clinicalStore = useClinicalContextStore();
const notice = ref('');
const reviewing = ref(false);
const reminderDialog = ref(false);
const reminderMessage = ref('');
const creatingReminder = ref(false);
const reminderId = ref<string | null>(null);
const selected = ref<string[]>([]);
const runScope = ref(clinicalResponseScope());
const scopeValid = computed(() => runScope.value === clinicalResponseScope());
const citationOpen = ref(false);
const citationBusy = ref(false);
const citationError = ref('');
const citation = ref<KnowledgeChunkCitationWire | null>(null);
const unavailableSources = ref(new Set<string>());
let citationRequest = 0;
function closeCitation() { citationRequest++; citationOpen.value = false; citationBusy.value = false; citation.value = null; citationError.value = ''; }
async function openCitation(source: Record<string, unknown>) {
  if (!scopeValid.value) return;
  const requestId = ++citationRequest;
  const current = () => requestId === citationRequest && scopeValid.value;
  citationOpen.value = true; citationBusy.value = true; citation.value = null; citationError.value = '';
  try {
    if (typeof source.source_id !== 'string' || typeof source.source_version !== 'string' || typeof source.content_hash !== 'string') {
      throw new ClinicalApiError('KNOWLEDGE_CITATION_INCOMPLETE', '引用缺少版本或内容指纹，无法核对来源，请重新检索。', 409);
    }
    const expected = { source_id: source.source_id, source_version: source.source_version, content_hash: source.content_hash };
    const lease = await issueKnowledgeLease();
    if (!current()) return;
    const result = await getKnowledgeChunkCitation(lease, expected.source_id);
    if (current()) {
      citation.value = verifyKnowledgeCitation(expected, result);
      const next = new Set(unavailableSources.value); next.delete(expected.source_id); unavailableSources.value = next;
    }
  } catch (error) { if (current()) {
    citationError.value = toClinicalIssue(error).message;
    unavailableSources.value = new Set([...unavailableSources.value, String(source.source_id)]);
  } }
  finally { if (current()) citationBusy.value = false; }
}
const labels: Record<string, string> = { chief_complaint: '主诉', present_illness: '现病史', past_history: '既往史', allergy_history: '过敏史', physical_exam: '体格检查', auxiliary_exam: '辅助检查', assessment: '诊断与评估', treatment_plan: '治疗计划', followup_plan: '复诊与随访' };
const ready = computed(() => scopeValid.value && ['WAITING_FOR_REVIEW', 'COMPLETED'].includes(props.run.state) && props.run.output.execution_mode === 'LIVE_MODEL');
const draftSections = computed<Record<string, string>>(() => {
  const draft: Record<string, string> = {};
  for (const child of props.run.child_runs.filter(c => c.state === 'COMPLETED')) {
    const value = child.contribution.draft_sections;
    if (!value || typeof value !== 'object' || Array.isArray(value)) continue;
    for (const [key, text] of Object.entries(value)) if (labels[key] && typeof text === 'string' && text.trim()) draft[key] = text;
  }
  return draft;
});
const references = computed(() => {
  const seen = new Set<string>();
  return props.run.child_runs.flatMap(c => c.source_references).filter(r => {
    const key = `${r.source_type}:${r.source_id}:${r.source_version}`;
    if (seen.has(key)) return false; seen.add(key); return true;
  });
});
watch(() => props.run.run_id, () => { runScope.value = clinicalResponseScope(); unavailableSources.value = new Set(); closeCitation(); reviewing.value = false; reminderDialog.value = false; reminderId.value = null; selected.value = []; notice.value = ''; });
watch(clinicalResponseScope, () => { closeCitation(); reviewing.value = false; reminderDialog.value = false; selected.value = []; });
async function openHandoff(path: string, query: Record<string, string>) {
  if (!ready.value || !props.run.patient_id || !props.run.encounter_id) return;
  if (!await clinicalStore.activateOutpatient({ patientId: props.run.patient_id, encounterId: props.run.encounter_id,
    patientDisplayName: clinicalStore.patientId === props.run.patient_id ? clinicalStore.patientDisplayName : '医助任务对应患者',
    documentId: props.run.target_type === 'DOCUMENT' ? props.run.target_id : null })) return;
  try {
    const failure = await router.push({ path, query });
    if (!failure) emit('navigate');
  } catch (error) { notice.value = toClinicalIssue(error).message; }
}
function reviewDraft() { selected.value = Object.keys(draftSections.value); reviewing.value = true; }
function adopt() {
  if (!ready.value || !props.allowDocumentAdoption || !selected.value.length) return;
  emit('adopt-draft', Object.fromEntries(selected.value.map(key => [key, draftSections.value[key]!])), props.run);
  reviewing.value = false;
}
function reviewReminder() {
  reminderMessage.value = presentMedicalAgentResult(props.run).slice(0, 1800);
  reminderDialog.value = true;
}
async function saveReminder() {
  if (!ready.value || creatingReminder.value || reminderMessage.value.trim().length < 4) return;
  const sourceRun = props.run;
  creatingReminder.value = true;
  try {
    const reminder = await createMedicalAgentReminder(sourceRun, reminderMessage.value.trim());
    if (props.run.run_id !== sourceRun.run_id) return;
    reminderId.value = reminder.reminder_id; reminderDialog.value = false;
    notice.value = '待办提醒已保存并保留医助来源，可在提醒详情转为任务并跟踪处理。';
  } catch (error) { if (props.run.run_id === sourceRun.run_id) notice.value = toClinicalIssue(error).message; }
  finally { creatingReminder.value = false; }
}
async function copy() {
  try { await navigator.clipboard.writeText(presentMedicalAgentResult(props.run)); notice.value = '候选内容已复制，使用前请核对来源与适用范围。'; }
  catch { notice.value = '浏览器未允许复制，请直接选择候选文字复制。'; }
}
</script>

<template>
  <section v-if="scopeValid" class="agent-candidate-review" aria-label="医助成果与来源">
    <details v-if="references.length" class="agent-candidate-evidence">
      <summary>查看 {{ references.length }} 条来源依据</summary>
      <ul><li v-for="(source, index) in references" :key="index"><b>{{ source.title || source.source_type }}</b><small>{{ source.source_id }} · 版本 {{ source.source_version }}</small><p v-if="unavailableSources.has(String(source.source_id))">来源暂不可用，原摘录已隐藏，请重新核验。</p><p v-else-if="source.excerpt">{{ source.excerpt }}</p><small v-if="source.content_hash">内容指纹：{{ source.content_hash }}</small><button v-if="source.source_type === 'KNOWLEDGE_CHUNK'" class="btn" type="button" @click="openCitation(source)">核对来源</button></li></ul>
    </details>
    <div v-if="Object.keys(draftSections).length" class="agent-draft-preview">
      <h3>可编辑文书草稿</h3>
      <details v-for="(text, field) in draftSections" :key="field"><summary>{{ labels[field] }}</summary><EvaMessageContent :text="String(text)" /></details>
    </div>
    <div class="agent-candidate-actions">
      <button class="btn" type="button" :disabled="!ready" @click="copy">复制候选内容</button>
      <button v-if="allowDocumentAdoption && Object.keys(draftSections).length" class="btn primary" type="button" :disabled="!ready" @click="reviewDraft">审阅并带入编辑区</button>
      <button v-else-if="ready && run.patient_id && Object.keys(draftSections).length" class="btn primary" type="button" @click="openHandoff('/record-editor', { agent_run: run.run_id })">打开病历编辑区</button>
      <button v-if="run.patient_id && run.encounter_id && ready" class="btn" type="button" :disabled="creatingReminder" @click="reviewReminder">转为待办提醒</button>
      <button v-if="reminderId" class="btn primary" type="button" @click="openHandoff('/ai-reminder-detail', { reminder_id: reminderId })">打开提醒与任务</button>
    </div>
    <p v-if="notice" role="status">{{ notice }}</p>
    <BusinessActionDialog :open="citationOpen" title="核对知识来源" eyebrow="引用核对" confirm-label="完成核对" @confirm="closeCitation" @cancel="closeCitation">
      <p v-if="citationBusy" role="status">正在重新检查来源权限与有效性…</p>
      <p v-else-if="citationError" role="alert">{{ citationError }}</p>
      <template v-else-if="citation">
        <h3>{{ citation.title }}</h3>
        <p>版本 {{ citation.version }} · {{ citation.section_path || '正文' }}</p>
        <p>适用范围：{{ citation.classification === 'PUBLIC' ? '公开知识' : '本机构内部知识' }}；当前版本有效。</p>
        <p>有效期：{{ citation.effective_from || '未限定起始' }} 至 {{ citation.effective_to || '未限定结束' }}</p>
        <p style="white-space:pre-wrap;overflow-wrap:anywhere">{{ citation.text }}</p>
        <small>版本 ID：{{ citation.doc_version_id }} · 内容指纹：{{ citation.content_hash }}</small>
      </template>
    </BusinessActionDialog>
    <BusinessActionDialog :open="reminderDialog" title="确认待办提醒" eyebrow="人工承接" description="请编辑为明确的待办事项。确认后保存提醒与医助来源，随后可转为任务、分派和跟踪；诊疗操作仍需在对应业务页面确认。" confirm-label="保存待办提醒" :confirm-disabled="creatingReminder || !ready || reminderMessage.trim().length < 4" @confirm="saveReminder" @cancel="reminderDialog = false">
      <label>待办内容<textarea v-model="reminderMessage" rows="10" maxlength="2000" style="width:100%" /></label>
    </BusinessActionDialog>
    <BusinessActionDialog :open="reviewing" title="审阅文书候选" eyebrow="医生确认" description="选择要带入的段落；这些段落会替换编辑区对应内容。保存仍执行版本和权限校验，并保留医助来源。" confirm-label="确认带入编辑区" :confirm-disabled="!selected.length || !ready" @confirm="adopt" @cancel="reviewing = false">
      <label v-for="(text, field) in draftSections" :key="field" class="agent-draft-field"><span><input v-model="selected" type="checkbox" :value="field" /> {{ labels[field] }}</span><p>{{ text }}</p></label>
    </BusinessActionDialog>
  </section>
  <p v-else role="status">就诊或身份上下文已变化，请在当前页面重新读取医助任务。</p>
</template>

<style scoped>
.agent-candidate-review { display: grid; gap: 10px; min-width: 0; }
.agent-candidate-evidence li { padding: 8px 0; border-bottom: 1px solid #e2e9f1; }
.agent-candidate-evidence small { display: block; overflow-wrap: anywhere; color: #72849a; }
.agent-candidate-evidence p, .agent-draft-preview p, .agent-draft-field p { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.7; }
.agent-candidate-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.agent-draft-field { display: block; margin-bottom: 12px; }
summary { cursor: pointer; padding: 6px 0; }
</style>
