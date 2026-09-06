<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import type { InpatientDocumentTaskWire } from '../../generated/contracts';
import {
  createInpatientDocumentTask, dischargeInpatient, getInpatientSyntheticActor, inpatientSyntheticActors,
  issueInpatientLease, issueWardLease, loadInpatientDocumentRules, loadInpatientDocumentVersions,
  loadInpatientOverview, startInpatientDocumentTask, type InpatientSyntheticActorKey,
} from '../../clinical-api';
import AgentInlineReview from '../components/AgentInlineReview.vue';
import BusinessActionDialog from '../components/BusinessActionDialog.vue';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import InpatientPrototypeRail from '../components/InpatientPrototypeRail.vue';
import { toClinicalIssue } from '../clinical-error';

const route = useRoute();
const router = useRouter();
const routeId = computed(() => String(route.name ?? 'inpatient-overview'));
const selectedActorKey = ref<InpatientSyntheticActorKey>(getInpatientSyntheticActor()?.key ?? 'AUTHOR');
const selectedDocumentId = ref(typeof route.query.document_id === 'string' ? route.query.document_id : '');
const taskState = ref<'ALL' | InpatientDocumentTaskWire['task_state']>('ALL');
const notice = ref('');
const busy = ref(false);
const dischargeDiagnosis = ref('');
const dispositionCode = ref<'HOME' | 'TRANSFER_TO_FACILITY' | 'DEATH' | 'OTHER'>('HOME');
const createOpen = ref(false);
const dischargeOpen = ref(false);
const selectedRuleCode = ref('IP-DAILY-COURSE');
const occurredAt = ref(new Date().toISOString().slice(0, 16));
const startTaskId = ref('');

const journey = useQuery({
  queryKey: ['clinical', 'inpatient-journey', selectedActorKey],
  queryFn: async () => {
    const [lease, wardLease] = await Promise.all([issueInpatientLease(), issueWardLease()]);
    const [overview, rules] = await Promise.all([loadInpatientOverview(lease), loadInpatientDocumentRules(wardLease)]);
    return { lease, overview, rules };
  },
  retry: false, staleTime: 0, gcTime: 0,
});

const overview = computed(() => journey.data.value?.overview);
const admission = computed(() => overview.value?.admission);
const issue = computed(() => journey.error.value ? toClinicalIssue(journey.error.value) : null);
const activeActor = computed(() => inpatientSyntheticActors.find((actor) => actor.key === selectedActorKey.value));
const pendingTasks = computed(() => overview.value?.document_tasks.filter((task) => !['COMPLETED', 'WAIVED'].includes(task.task_state)) ?? []);
const completedTasks = computed(() => overview.value?.document_tasks.filter((task) => task.task_state === 'COMPLETED') ?? []);
const documentTasks = computed(() => overview.value?.document_tasks.filter((task) => task.working_document_id || task.completed_document_id) ?? []);
const filteredTasks = computed(() => overview.value?.document_tasks.filter((task) => taskState.value === 'ALL' || task.task_state === taskState.value) ?? []);
const creatableRules = computed(() => journey.data.value?.rules.filter((rule) => ['DAILY', 'MANUAL'].includes(rule.trigger_type)) ?? []);
const selectedTask = computed(() => documentTasks.value.find((task) => (task.completed_document_id || task.working_document_id) === selectedDocumentId.value));
const startTaskTarget = computed(() => overview.value?.document_tasks.find((task) => task.task_id === startTaskId.value) ?? null);
const agentPatientId = computed(() => admission.value?.patient_id ?? null);
const agentEncounterId = computed(() => admission.value?.encounter_id ?? null);
const firstCourseObjective = computed(() => '基于当前住院就诊起草首次病程记录候选，仅供医生审阅后采用。');
const dischargeEducationObjective = computed(() => '基于当前住院就诊生成出院用药指导与健康宣教候选，仅供医生审阅后采用。');

watch(documentTasks, (tasks) => {
  if (!selectedDocumentId.value && tasks.length) selectedDocumentId.value = tasks[0].completed_document_id || tasks[0].working_document_id || '';
}, { immediate: true });
watch(() => route.query.document_id, (value) => {
  if (typeof value === 'string') selectedDocumentId.value = value;
});

const versions = useQuery({
  queryKey: computed(() => ['clinical', 'inpatient-document-versions', selectedDocumentId.value, journey.data.value?.lease.lease_id]),
  queryFn: () => loadInpatientDocumentVersions(journey.data.value!.lease, selectedDocumentId.value),
  enabled: computed(() => routeId.value === 'inpatient-doc-versions' && Boolean(journey.data.value && selectedDocumentId.value)),
  retry: false, staleTime: 0, gcTime: 0,
});
const versionsIssue = computed(() => versions.error.value ? toClinicalIssue(versions.error.value) : null);


async function submitDischarge() {
  if (!journey.data.value || !dischargeDiagnosis.value.trim() || busy.value) return;
  busy.value = true; notice.value = '';
  try {
    await dischargeInpatient(journey.data.value.lease, journey.data.value.overview, dischargeDiagnosis.value.trim(), dispositionCode.value);
    dischargeOpen.value = false;
    notice.value = '出院门禁已通过，床位和住院状态已同事务更新。';
    await journey.refetch();
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = false; }
}

async function createCourse(startImmediately: boolean) {
  const current = journey.data.value;
  const rule = current?.rules.find((candidate) => candidate.rule_code === selectedRuleCode.value);
  if (!current || !rule || busy.value || selectedActorKey.value !== 'AUTHOR') return;
  busy.value = true; notice.value = '';
  try {
    const eventAt = new Date(occurredAt.value).toISOString();
    const task = await createInpatientDocumentTask(current.lease, current.overview, rule.rule_code, eventAt, `MANUAL:${rule.rule_code}:${eventAt}:${crypto.randomUUID()}`);
    if (startImmediately) {
      const document = await startInpatientDocumentTask(current.lease, task, rule);
      await router.push({ path: '/inpatient-doc-editor', query: { document_id: document.document_id } });
      return;
    }
    createOpen.value = false;
    notice.value = `已创建「${rule.display_name}」任务，可在任务列表开始书写。`;
    await journey.refetch();
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = false; }
}

async function startTask(task: InpatientDocumentTaskWire) {
  const current = journey.data.value;
  if (!current || busy.value || selectedActorKey.value !== 'AUTHOR') return;
  busy.value = true; notice.value = '';
  try {
    const rule = current.rules.find((candidate) => candidate.document_type_code === task.document_type_code);
    const document = await startInpatientDocumentTask(current.lease, task, rule);
    await router.push({ path: '/inpatient-doc-editor', query: { document_id: document.document_id } });
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = false; }
}

function documentLabel(task: InpatientDocumentTaskWire) {
  return journey.data.value?.rules.find((rule) => rule.document_type_code === task.document_type_code)?.display_name ?? task.document_type_code;
}
function formatDate(value?: string | null) {
  if (!value) return '—';
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value));
}
</script>

<template>
  <section data-page-root class="content vue-native-page inpatient-journey-page">
    <div class="page-heading"><div><p class="eyebrow">住院 / 患者全程</p><h1>{{ routeId === 'inpatient-overview' ? '住院患者总览' : routeId === 'inpatient-course' ? '住院病程与文书中心' : routeId === 'inpatient-doc-versions' ? '住院文书版本与查房证据' : '出院病历与病案归档闭环' }}</h1><p>{{ routeId === 'inpatient-overview' ? '查看患者当前住院情况和待处理事项。' : routeId === 'inpatient-course' ? '查看住院病程、文书和相关处理记录。' : routeId === 'inpatient-doc-versions' ? '查看文书版本变化和查房留痕。' : '核对出院病历，完成归档前检查。' }}</p></div><RouterLink class="button secondary" to="/inpatient">返回住院工作站</RouterLink></div>
    <ClinicalPageState v-if="journey.isPending.value" kind="loading" message="正在加载患者住院信息" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="journey.refetch()" />
    <template v-else-if="overview && admission">
      <section class="patient-strip" aria-label="住院患者上下文"><div class="patient-avatar">{{ overview.patient_display_name.slice(0, 1) }}</div><div><strong>{{ overview.patient_display_name }}</strong><span>当前住院上下文 · 所有动作重新校验授权</span></div><dl><div><dt>状态</dt><dd>{{ admission.status }}</dd></div><div><dt>病区床位</dt><dd>{{ overview.ward_display_name }} · {{ overview.bed_label }}床</dd></div><div><dt>当前岗位</dt><dd>{{ activeActor?.roleLabel ?? '当前登录岗位' }}</dd></div></dl><span class="lease-badge">…{{ admission.admission_id.slice(-8) }}</span></section>
      <div v-if="notice" class="inline-notice" :class="{ error: notice.includes('：') }" role="status">{{ notice }}</div>
      <AgentInlineReview v-if="routeId === 'inpatient-course'" agent-code="DOCUMENT_DRAFTER" stage-code="FIRST_COURSE" :objective="firstCourseObjective" :patient-id="agentPatientId" :encounter-id="agentEncounterId" target-type="ENCOUNTER" :target-id="agentEncounterId" title="AI 首次病程记录候选" source-route="inpatient-course" />
      <AgentInlineReview v-else-if="routeId === 'inpatient-discharge'" agent-code="PATIENT_EDUCATION" stage-code="MEDICATION_GUIDE" :objective="dischargeEducationObjective" :patient-id="agentPatientId" :encounter-id="agentEncounterId" target-type="ENCOUNTER" :target-id="agentEncounterId" title="AI 出院宣教候选" source-route="inpatient-discharge" />

      <template v-if="routeId === 'inpatient-overview'">
        <div class="prototype-secondary-grid"><div class="prototype-primary-stack">
        <div class="inpatient-metrics"><article><span>住院天数</span><strong>{{ Math.max(1, Math.ceil((Date.now() - new Date(admission.admitted_at).getTime()) / 86400000)) }}</strong><small>入院 {{ formatDate(admission.admitted_at) }}</small></article><article><span>待完成文书</span><strong>{{ pendingTasks.length }}</strong><small>含超时与审签中</small></article><article><span>已完成文书</span><strong>{{ completedTasks.length }}</strong><small>签名证据可追溯</small></article><article><span>数据水印</span><strong>{{ overview.data_watermark.slice(0, 8) }}</strong><small>刷新后重新校验</small></article></div>
        <div class="journey-grid"><RouterLink to="/inpatient-course"><b>病程与文书</b><span>{{ pendingTasks.length }} 项待办，进入任务、书写和审签</span></RouterLink><RouterLink to="/ip-orders"><b>医嘱与执行</b><span>长期/临时医嘱、用药安全和执行状态</span></RouterLink><RouterLink to="/ip-results"><b>检查检验</b><span>结果、危急值和来源证据</span></RouterLink><RouterLink to="/ip-consult"><b>会诊与协同</b><span>申请、接诊、意见签署和闭环确认</span></RouterLink><RouterLink to="/ip-pathway"><b>临床路径</b><span>版本固定、真实来源任务、变异审核</span></RouterLink><RouterLink to="/inpatient-discharge"><b>出院闭环</b><span>出院门禁、诊断、去向和病案交接</span></RouterLink></div>
        <section class="editor-card"><div class="card-toolbar"><div><p class="eyebrow">时限优先</p><h2>最近住院文书任务</h2></div><RouterLink to="/inpatient-course">查看全部</RouterLink></div><div class="task-table-wrap"><table class="task-table"><thead><tr><th>文书</th><th>截止</th><th>状态</th></tr></thead><tbody><tr v-for="task in overview.document_tasks.slice(0, 8)" :key="task.task_id"><td><strong>{{ documentLabel(task) }}</strong><small>{{ task.document_type_code }}</small></td><td>{{ formatDate(task.due_at) }}</td><td><span class="task-state" :class="task.task_state.toLowerCase()">{{ task.task_state }}</span></td></tr></tbody></table></div></section>
        </div><InpatientPrototypeRail mode="overview" :patient-name="overview.patient_display_name" :bed-label="overview.bed_label" :pending-count="pendingTasks.length" :completed-count="completedTasks.length" :overdue-count="overview.document_tasks.filter((task) => task.task_state === 'OVERDUE').length" /></div>
      </template>

      <template v-else-if="routeId === 'inpatient-course'">
        <div class="prototype-secondary-grid prototype-course-grid"><aside class="card prototype-document-tree"><div class="card-head">病程文书目录 <span>{{ filteredTasks.length }}</span></div><div class="prototype-document-list"><button v-for="task in filteredTasks" :key="task.task_id" type="button" :class="{ active: (task.completed_document_id || task.working_document_id) === selectedDocumentId }" @click="selectedDocumentId = task.completed_document_id || task.working_document_id || ''"><strong>{{ documentLabel(task) }}</strong><small>{{ formatDate(task.due_at) }}</small><span class="task-state" :class="task.task_state.toLowerCase()">{{ task.task_state }}</span></button></div></aside><div class="prototype-primary-stack">
        <section class="editor-card"><div class="card-toolbar"><div><p class="eyebrow">任务驱动</p><h2>病程、查房与事件型文书</h2></div><div class="toolbar-actions"><label class="compact-filter">任务状态<select v-model="taskState"><option value="ALL">全部</option><option value="PENDING">待开始</option><option value="IN_PROGRESS">处理中</option><option value="OVERDUE">已超时</option><option value="COMPLETED">已完成</option><option value="WAIVED">已豁免</option></select></label><button class="button primary" type="button" :disabled="admission.status !== 'ADMITTED' || selectedActorKey !== 'AUTHOR'" @click="createOpen = true">新增病程文书</button></div></div>
          <BusinessActionDialog :open="createOpen" title="新增病程文书任务" description="先形成时限任务，再建立草稿；创建后会立即影响文书待办与审签流程。" confirm-label="仅创建任务" :busy="busy" width="wide" @cancel="createOpen = false" @confirm="createCourse(false)"><div class="dialog-grid"><label>文书类型<select v-model="selectedRuleCode"><option v-for="rule in creatableRules" :key="rule.rule_code" :value="rule.rule_code">{{ rule.display_name }} · {{ rule.category_code }}</option></select></label><label>事件/记录时间<input v-model="occurredAt" type="datetime-local" required /></label></div><p class="dialog-warning">创建并开始书写会直接进入结构化编辑页。</p><template #leading-actions><button class="btn" type="button" :disabled="busy" @click="createCourse(true)">创建并开始书写</button></template></BusinessActionDialog>
          <BusinessActionDialog :open="Boolean(startTaskTarget)" title="建立住院病历草稿" description="建立后任务进入处理中，并打开结构化编辑器；后续每次修改都会追加不可变版本。" confirm-label="确认建立并书写" :busy="busy" @cancel="startTaskId = ''" @confirm="startTaskTarget && startTask(startTaskTarget)"><p v-if="startTaskTarget" class="dialog-warning">{{ documentLabel(startTaskTarget) }} · 截止 {{ formatDate(startTaskTarget.due_at) }}</p></BusinessActionDialog>
          <div class="task-table-wrap">
            <table class="task-table">
              <thead><tr><th>文书类型</th><th>截止时间</th><th>审签进度</th><th>动作</th></tr></thead>
              <tbody><tr v-for="task in filteredTasks" :key="task.task_id">
                <td><strong>{{ documentLabel(task) }}</strong><small>{{ task.document_type_code }}</small></td>
                <td>{{ formatDate(task.due_at) }}</td>
                <td><span class="task-state" :class="task.task_state.toLowerCase()">{{ task.task_state }}</span><small>{{ task.current_signature_level ?? '尚未签署' }} → {{ task.next_signature_level ?? '完成' }}</small></td>
                <td><RouterLink v-if="task.working_document_id && task.task_state !== 'COMPLETED'" :to="{ path: '/inpatient-doc-editor', query: { document_id: task.working_document_id } }">进入书写/审签</RouterLink><RouterLink v-else-if="task.completed_document_id || task.working_document_id" :to="{ path: '/inpatient-doc-versions', query: { document_id: task.completed_document_id || task.working_document_id } }">查看版本证据</RouterLink><button v-else class="task-action" type="button" :disabled="busy || selectedActorKey !== 'AUTHOR'" @click="startTaskId = task.task_id">{{ selectedActorKey !== 'AUTHOR' ? '等待作者建稿' : '开始书写' }}</button></td>
              </tr></tbody>
            </table>
            <div v-if="!filteredTasks.length" class="clinical-empty-state rich" role="status"><strong>当前筛选下没有文书任务</strong><p>可切换任务状态查看历史，或新增一份日常病程、查房记录等手工文书任务。</p><button class="button primary" type="button" @click="taskState = 'ALL'; createOpen = true">新增病程文书</button></div>
          </div></section>
        </div><InpatientPrototypeRail mode="course" :patient-name="overview.patient_display_name" :bed-label="overview.bed_label" :pending-count="pendingTasks.length" :completed-count="completedTasks.length" :overdue-count="overview.document_tasks.filter((task) => task.task_state === 'OVERDUE').length" /></div>
      </template>

      <template v-else-if="routeId === 'inpatient-doc-versions'">
        <section class="editor-card"><div class="card-toolbar"><div><p class="eyebrow">不可变证据</p><h2>文书版本时间轴</h2></div><label class="compact-filter">选择文书<select v-model="selectedDocumentId"><option v-for="task in documentTasks" :key="task.task_id" :value="task.completed_document_id || task.working_document_id || ''">{{ documentLabel(task) }} · {{ task.task_state }}</option></select></label></div><ClinicalPageState v-if="versions.isPending.value" kind="loading" message="正在读取不可变版本链" /><ClinicalPageState v-else-if="versionsIssue" kind="error" :code="versionsIssue.code" :message="versionsIssue.message" @retry="versions.refetch()" /><div v-else-if="versions.data.value?.length" class="version-evidence-list"><article v-for="version in versions.data.value" :key="version.document_version_id"><div><span>v{{ version.version_no }}</span><strong>{{ version.status }}</strong></div><dl><div><dt>创建时间</dt><dd>{{ formatDate(version.created_at) }}</dd></div><div><dt>模板版本</dt><dd>v{{ version.template_version_no }}</dd></div><div><dt>内容哈希</dt><dd><code>{{ version.content_hash.slice(0, 24) }}…</code></dd></div><div><dt>字段数</dt><dd>{{ Object.keys(version.sections ?? {}).length }}</dd></div></dl><RouterLink :to="{ path: '/inpatient-doc-editor', query: { document_id: version.document_id } }">查看当前文书与签名</RouterLink></article></div><div v-else class="clinical-empty-state" role="status">当前住院尚无可读取的文书版本</div></section>
        <aside v-if="selectedTask" class="evidence-note"><strong>查房证据边界</strong><p>{{ documentLabel(selectedTask) }} 的所有版本只追加、不覆盖；签名、退回与更正证据由治理接口独立保存。</p></aside>
      </template>

      <template v-else>
        <div class="discharge-workspace"><section class="editor-card"><div class="card-toolbar"><div><p class="eyebrow">服务端门禁</p><h2>{{ admission.status === 'DISCHARGED' ? '出院已完成' : '提交出院办理' }}</h2></div><span class="state-chip" :class="admission.status === 'DISCHARGED' ? 'signed' : 'draft'">{{ admission.status }}</span></div><div v-if="admission.status === 'DISCHARGED'" class="discharge-complete"><strong>患者已完成出院</strong><p>出院时间 {{ formatDate(admission.discharged_at) }}；床位释放、审计与 Outbox 已同事务提交。</p><RouterLink to="/archive-assets">进入病案归档就绪度</RouterLink></div><div v-else class="discharge-complete discharge-ready-action"><strong>出院操作将影响床位、在院状态和病案待归档队列</strong><p>系统会在提交时重新校验必需文书、岗位权限与并发版本。</p><button class="button primary" type="button" :disabled="busy || selectedActorKey !== 'AUTHOR' || pendingTasks.length > 0" @click="dischargeOpen = true">{{ pendingTasks.length > 0 ? `尚有 ${pendingTasks.length} 项必需文书未完成` : selectedActorKey !== 'AUTHOR' ? '请切换作者岗位办理出院' : '打开出院办理' }}</button></div><BusinessActionDialog :open="dischargeOpen" title="校验并确认出院" description="必需文书必须全部完成；确认后将释放床位、更新住院状态并生成审计与 Outbox 证据。" confirm-label="执行出院门禁" :busy="busy" width="wide" @cancel="dischargeOpen = false" @confirm="submitDischarge"><label>出院诊断<textarea v-model="dischargeDiagnosis" required rows="5" maxlength="2000" placeholder="填写主要出院诊断及必要的并存诊断" /></label><label>出院去向<select v-model="dispositionCode"><option value="HOME">回家</option><option value="TRANSFER_TO_FACILITY">转其他医疗机构</option><option value="DEATH">死亡</option><option value="OTHER">其他</option></select></label><p class="dialog-warning">必需病历任务不得通过出院操作一键豁免；服务端会再次失败关闭校验。</p></BusinessActionDialog></section><InpatientPrototypeRail mode="discharge" :patient-name="overview.patient_display_name" :bed-label="overview.bed_label" :pending-count="pendingTasks.length" :completed-count="completedTasks.length" /></div>
      </template>
    </template>
  </section>
</template>
