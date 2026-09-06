<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, nextTick, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import type { DocumentVersionWire, InpatientDocumentTaskWire } from '../../generated/contracts';
import {
  getInpatientSyntheticActor, inpatientSyntheticActors, issueInpatientLease,
  loadInpatientDocument, loadInpatientDocumentGovernance, loadInpatientOverview,
  runInpatientDocumentQuality, saveInpatientDocumentDraft,
  signInpatientDocument, type InpatientSyntheticActorKey,
} from '../../clinical-api';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import BusinessActionDialog from '../components/BusinessActionDialog.vue';
import { toClinicalIssue } from '../clinical-error';
import RecordQcPanel from '../components/RecordQcPanel.vue';
import EncounterRecordQcPanel from '../components/EncounterRecordQcPanel.vue';
import { useRecordQcAutomation } from '../composables/use-record-qc-automation';
import { useRecordQcSnapshot } from '../composables/use-record-qc-snapshot';

const route = useRoute();
const documentId = computed(() => typeof route.query.document_id === 'string' ? route.query.document_id : '');
const routeId = computed(() => String(route.name ?? 'inpatient-doc-editor'));
const selectedActorKey = ref<InpatientSyntheticActorKey>(getInpatientSyntheticActor()?.key ?? 'AUTHOR');
const sections = ref<Record<string, string>>({});
const baseline = ref('');
const draftBase = ref<DocumentVersionWire | null>(null);
const busy = ref('');
const notice = ref('');
const commandError = ref('');
const warningDisposition = ref('');
const saveOpen = ref(false);
const qualityOpen = ref(false);
const signOpen = ref(false);

const editorQuery = useQuery({
  queryKey: ['clinical', 'inpatient-document-editor', documentId],
  queryFn: async () => {
    const lease = await issueInpatientLease();
    const overview = await loadInpatientOverview(lease);
    const resolvedDocumentId = documentId.value || overview.document_tasks
      .map((item) => item.working_document_id || item.completed_document_id)
      .find((item): item is string => Boolean(item)) || '';
    if (!resolvedDocumentId) return { kind: 'missing' as const, overview };
    const document = await loadInpatientDocument(lease, resolvedDocumentId);
    const governance = await loadInpatientDocumentGovernance(lease, document);
    return { kind: 'ready' as const, lease, document, overview, governance };
  },
  retry: false, staleTime: 0, gcTime: 0,
});

watch(() => editorQuery.data.value, (data, previous) => {
  if (data?.kind !== 'ready') return;
  if (previous?.kind === 'ready' && previous.document.document_id === data.document.document_id
    && JSON.stringify(sections.value) !== baseline.value) return;
  draftBase.value = data.document;
  const next = Object.fromEntries(Object.entries(data.document.sections ?? {}).map(([key, value]) => [key, typeof value === 'string' ? value : JSON.stringify(value)]));
  sections.value = next; baseline.value = JSON.stringify(next);
}, { immediate: true });

const issue = computed(() => editorQuery.error.value ? toClinicalIssue(editorQuery.error.value) : null);
const data = computed(() => editorQuery.data.value?.kind === 'ready' ? editorQuery.data.value : null);
const task = computed(() => data.value?.overview.document_tasks.find((item) =>
  item.working_document_id === data.value?.document.document_id || item.completed_document_id === data.value?.document.document_id));
const activeActor = computed(() => inpatientSyntheticActors.find((actor) => actor.key === selectedActorKey.value));
const dirty = computed(() => JSON.stringify(sections.value) !== baseline.value);
const qc = useRecordQcSnapshot(() => data.value ?? undefined, () => dirty.value,
  ({ lease, document }) => loadInpatientDocumentGovernance(lease, document));
const qcAuto = useRecordQcAutomation({ context: () => data.value,
  dirty: () => dirty.value || selectedActorKey.value !== 'AUTHOR',
  run: ({ lease, document }) => runInpatientDocumentQuality(lease, document, 'POST_SAVE'), refresh: qc.refresh });
const qcFields = computed(() => Object.fromEntries(Object.keys(sections.value).map(key => [key, fieldLabel(key)])));
function focusQcField(field: string) { window.document.getElementById(`inpatient-record-field-${field}`)?.focus(); }
// The legacy signing summary must use the same pending/error boundary as the embedded panel.
const currentQualityRun = computed(() => !qcAuto.running.value && !qcAuto.error.value
  && !qc.loading.value && !qc.error.value && !dirty.value ? qc.snapshot.value?.quality_run : null);
const qualityPassed = computed(() => currentQualityRun.value?.outcome === 'PASSED');
const qualityWarning = computed(() => currentQualityRun.value?.outcome === 'WARNING');
const qualityAcceptable = computed(() => qualityPassed.value || qualityWarning.value);
const canEdit = computed(() => selectedActorKey.value === 'AUTHOR' && task.value
  && ['AUTHOR', null].includes(task.value.next_signature_level ?? null)
  && task.value.review_status !== 'COMPLETED');
const canSign = computed(() => task.value?.next_signature_level === selectedActorKey.value
  && qualityAcceptable.value && !dirty.value && task.value.review_status !== 'COMPLETED'
  && (!qualityWarning.value || warningDisposition.value.trim().length >= 4));
const stages: Array<{ key: InpatientSyntheticActorKey; label: string }> = [
  { key: 'AUTHOR', label: '作者签名' }, { key: 'ATTENDING', label: '主治审签' },
  { key: 'CHIEF', label: '主任审签' }, { key: 'MEDICAL_RECORDS', label: '病案确认' },
];

async function execute(label: string, action: () => Promise<void>) {
  if (busy.value) return;
  busy.value = label; notice.value = ''; commandError.value = '';
  try { await action(); }
  catch (error) { commandError.value = `${toClinicalIssue(error).code}：${toClinicalIssue(error).message}`; }
  finally { busy.value = ''; }
}



async function save() {
  const current = data.value;
  if (!current || !canEdit.value || !dirty.value) return;
  await execute('save', async () => {
    const submitted = { ...sections.value };
    const saved = await saveInpatientDocumentDraft(current.lease, draftBase.value ?? current.document, submitted);
    saveOpen.value = false;
    await editorQuery.refetch(); notice.value = '已生成新的不可变住院病历版本；旧审签证据不会沿用。';
    await nextTick();
    if (data.value?.document.document_id !== saved.document_id) return;
    draftBase.value = saved;
    baseline.value = JSON.stringify(submitted);
    if (data.value?.document.document_version_id === saved.document_version_id) qcAuto.afterSave();
  });
}

async function qualityCheck() {
  const current = data.value;
  if (!current || dirty.value || selectedActorKey.value !== 'AUTHOR') return;
  qcAuto.cancel();
  await execute('quality', async () => {
    const findings = await runInpatientDocumentQuality(current.lease, current.document);
    qualityOpen.value = false;
    await editorQuery.refetch();
    await qc.refresh();
    notice.value = findings.some((item) => item.severity === 'BLOCKING') ? '质控发现阻断项，请修订并重新保存。' : '确定性质控已通过，可执行当前层级签名。';
  });
}

async function sign() {
  const current = data.value;
  if (!current || !canSign.value) return;
  await execute('sign', async () => {
    await signInpatientDocument(current.lease, current.document, selectedActorKey.value, warningDisposition.value);
    signOpen.value = false;
    warningDisposition.value = '';
    await editorQuery.refetch(); notice.value = task.value?.review_status === 'COMPLETED' ? '四角色审签完成，住院文书任务已闭环。' : '本级签名已固化，文书已流转到下一审签岗位。';
  });
}

function stageState(stage: InpatientSyntheticActorKey, currentTask?: InpatientDocumentTaskWire) {
  if (!currentTask) return 'pending';
  const order = stages.map((item) => item.key);
  const completedIndex = currentTask.current_signature_level ? order.indexOf(currentTask.current_signature_level) : -1;
  const index = order.indexOf(stage);
  if (index <= completedIndex) return 'complete';
  if (currentTask.next_signature_level === stage) return 'current';
  if (index > order.indexOf(currentTask.required_signature_level)) return 'not-required';
  return 'pending';
}

function fieldLabel(key: string) {
  return ({ case_summary: '病例摘要', diagnostic_basis: '诊断依据', treatment_course: '诊疗经过', quality_conclusion: '病案质量结论', chief_complaint: '主诉', present_illness: '现病史', assessment: '临床评估', treatment_plan: '诊疗计划', differential_diagnosis: '鉴别诊断', communication: '沟通记录' } as Record<string, string>)[key]
    ?? key.replaceAll('_', ' ');
}
function formatDate(value: string) { return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'short', hour12: false }).format(new Date(value)); }
</script>

<template>
  <section data-page-root class="content vue-native-page">
    <div class="page-heading"><div><p class="eyebrow">住院 / 病历创作与分级审签</p><h1>{{ routeId === 'inpatient-doc-qc' ? '住院病历 · 质控与审签' : '住院病历 · 专注编辑' }}</h1><p>{{ routeId === 'inpatient-doc-qc' ? '查看质控问题并完成分级审签。' : '编辑住院病历内容，并保留完整版本记录。' }}</p></div><RouterLink class="button secondary" to="/inpatient">返回住院工作站</RouterLink></div>
    <ClinicalPageState v-if="editorQuery.isPending.value" kind="loading" message="正在核验患者、住院任务、文书版本与审签证据" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="editorQuery.refetch()" />
    <div v-else-if="editorQuery.data.value?.kind === 'missing'" class="record-evidence-empty"><h2>当前患者尚无可编辑住院文书</h2><p>先从病程与文书中选择任务并建立草稿；建立后本页会自动载入最近一份文书。</p><RouterLink class="button primary" to="/inpatient-course">进入病程与文书</RouterLink></div>
    <template v-else-if="data && task">
      <section class="patient-strip" aria-label="住院病历上下文"><div class="patient-avatar">{{ data.overview.patient_display_name.slice(0, 1) }}</div><div><strong>{{ data.overview.patient_display_name }}</strong><span>{{ data.document.document_type_code }}</span></div><dl><div><dt>病区床位</dt><dd>{{ data.overview.ward_display_name }} · {{ data.overview.bed_label }}床</dd></div><div><dt>版本状态</dt><dd>v{{ data.document.version_no }} · {{ data.document.status }}</dd></div><div><dt>当前岗位</dt><dd>{{ activeActor?.roleLabel ?? '住院医师' }}</dd></div></dl><span class="lease-badge">住院号 …{{ task.admission_id.slice(-8) }}</span></section>
      <section class="inpatient-signature-steps" aria-label="四角色审签进度"><article v-for="(stage, index) in stages" :key="stage.key" :class="stageState(stage.key, task)"><span>{{ index + 1 }}</span><div><strong>{{ stage.label }}</strong><small>{{ stageState(stage.key, task) === 'complete' ? '证据已固化' : stageState(stage.key, task) === 'current' ? '当前待办' : stageState(stage.key, task) === 'not-required' ? '本规则不要求' : '等待前序' }}</small></div></article></section>
      <div v-if="notice || commandError" class="legal-action-message" :class="{ error: commandError }" role="status">{{ commandError || notice }}</div>
      <BusinessActionDialog :open="saveOpen" title="保存住院病历新版本" description="保存不会覆盖旧版本；当前正文将形成新的不可变版本，原质控结论和未完成审签不会自动沿用。" confirm-label="确认保存新版本" :busy="busy === 'save'" :confirm-disabled="!dirty" width="wide" @cancel="saveOpen = false" @confirm="save"><p class="dialog-warning">当前 v{{ data.document.version_no }} · {{ task.document_type_code }}；共 {{ Object.keys(sections).length }} 个结构化字段。</p></BusinessActionDialog>
      <BusinessActionDialog :open="qualityOpen" title="运行确定性质控" description="质控结果绑定当前内容哈希，若正文再次变化必须重新质控。" confirm-label="确认运行质控" :busy="busy === 'quality'" @cancel="qualityOpen = false" @confirm="qualityCheck"><p class="dialog-warning">内容哈希：{{ data.document.content_hash.slice(0, 24) }}…</p></BusinessActionDialog>
      <BusinessActionDialog :open="signOpen" :title="`执行${activeActor?.roleLabel ?? selectedActorKey}签名`" description="签名会固化签署人、岗位、时间、内容哈希，并把任务流转到下一审签岗位。" confirm-label="确认签署并流转" :busy="busy === 'sign'" :confirm-disabled="!canSign" width="wide" @cancel="signOpen = false" @confirm="sign"><p class="dialog-warning">签署对象：v{{ data.document.version_no }} · {{ data.document.content_hash.slice(0, 20) }}…</p><label v-if="qualityWarning"><span>警告处置说明（至少 4 字）</span><textarea v-model="warningDisposition" rows="3" maxlength="1000" placeholder="说明已核对的警告及处置结论" /></label></BusinessActionDialog>
      <div class="inpatient-document-layout">
        <section class="inpatient-document-editor">
          <header><div><p class="eyebrow">结构化正文</p><h2>{{ task.document_type_code }}</h2></div><div class="toolbar-actions"><button class="button primary" :disabled="!canEdit || !dirty || Boolean(busy)" @click="saveOpen = true">保存新版本</button></div></header>
          <div v-if="!canEdit" class="document-readonly-banner"><strong>只读审签视图</strong><span>{{ task.next_signature_level === selectedActorKey ? '请核对正文、质控和历史签名后执行本级签署或返回工作站退回。' : `当前待办属于 ${task.next_signature_level ?? '已完成'} 岗位。` }}</span></div>
          <div class="inpatient-section-form"><label v-for="(_, key) in sections" :key="key"><span>{{ fieldLabel(String(key)) }}</span><small>{{ String(key) }}</small><textarea :id="`inpatient-record-field-${key}`" v-model="sections[key]" rows="4" :readonly="!canEdit" /></label></div>
        </section>
        <aside class="inpatient-review-rail">
          <RecordQcPanel :snapshot="qc.snapshot.value" :version-id="data.document.document_version_id" :content-hash="data.document.content_hash" :fields="qcFields" :dirty="dirty" :loading="qc.loading.value" :error="qc.error.value" :sections="sections" v-model:auto-enabled="qcAuto.enabled.value" :auto-running="qcAuto.running.value" :auto-error="qcAuto.error.value" @retry-auto="qcAuto.afterSave()" @retry="qc.refresh()" @locate="focusQcField" />
          <EncounterRecordQcPanel :lease="data.lease" :revision="qc.snapshot.value?.data_watermark ?? data.document.content_hash" />
          <section><header><div><p class="eyebrow">确定性门禁</p><h2>质控与当前动作</h2></div><span :class="['review-outcome', currentQualityRun?.outcome?.toLowerCase() ?? 'missing']">{{ currentQualityRun?.outcome ?? '未运行' }}</span></header><dl><div><dt>文书内容哈希</dt><dd>{{ data.document.content_hash.slice(0, 18) }}…</dd></div><div><dt>质控规则版本</dt><dd>{{ currentQualityRun?.rule_version ?? '—' }}</dd></div><div><dt>阻断问题</dt><dd>{{ currentQualityRun?.blocking_count ?? '—' }}</dd></div><div><dt>下一签名</dt><dd>{{ task.next_signature_level ?? '已完成' }}</dd></div></dl><div class="review-actions"><button class="button secondary full" :disabled="selectedActorKey !== 'AUTHOR' || dirty || Boolean(busy) || task.review_status === 'COMPLETED'" @click="qualityOpen = true">运行确定性质控</button><button class="button primary full" :disabled="!qualityAcceptable || dirty || task.next_signature_level !== selectedActorKey || Boolean(busy)" @click="signOpen = true">{{ task.review_status === 'COMPLETED' ? '审签已完成' : `执行${activeActor?.roleLabel ?? selectedActorKey}签名` }}</button><small v-if="task.review_status === 'COMPLETED'">四级签名链已固化，正文转为只读。</small><small v-else-if="dirty">存在未保存修改，质控与签名已禁用。</small><small v-else-if="!qualityAcceptable">必须先取得绑定当前内容哈希的通过或可处置警告结论。</small><small v-else-if="qualityWarning">质控存在警告，请在签名弹窗记录人工核对和处置说明。</small><small v-else-if="task.next_signature_level !== selectedActorKey">请切换到 {{ task.next_signature_level ?? '任务已完成' }} 对应的真实岗位。</small></div></section>
          <section><header><div><p class="eyebrow">不可变证据</p><h2>签名时间轴</h2></div><span>{{ data.governance.signatures.length }} 份</span></header><div v-if="data.governance.signatures.length === 0" class="legal-empty-state compact"><strong>尚未签名</strong><p>作者签名后，每一级人员、角色、时间与内容哈希将在此固化。</p></div><article v-for="signature in data.governance.signatures" v-else :key="signature.signature_id" class="inpatient-signature-evidence"><span>{{ signature.signature_role }}</span><div><strong>{{ signature.signer_display_name }}</strong><small>{{ formatDate(signature.signed_at) }} · {{ signature.signature_status }}</small><code>{{ signature.content_hash.slice(0, 16) }}…</code></div></article></section>
          <section class="review-safety-note"><h2>审签安全边界</h2><ul><li>不允许跳过前序层级</li><li>要求人员分离时同一人不可连续签署</li><li>退回后必须生成新版本并重新质控</li><li>CA 未回传时不伪造有效证据</li></ul></section>
        </aside>
      </div>
    </template>
  </section>
</template>
