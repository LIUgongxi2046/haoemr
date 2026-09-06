<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import type { InpatientDocumentRuleWire, InpatientDocumentTaskWire, InpatientWorklistItemWire } from '../../generated/contracts';
import {
  createInpatientClinicalEvent, createInpatientDocumentTask,
  getInpatientSyntheticActor, inpatientSyntheticActors,
  issueInpatientLease, issueWardLease, loadInpatientDocumentRules, loadInpatientOverview,
  loadInpatientWorklist, rejectInpatientDocumentReview, selectInpatientContext,
  startInpatientDocumentTask,
  type InpatientSyntheticActorKey,
} from '../../clinical-api';
import { developmentCopy } from '../../development-copy';
import AgentInlineReview from '../components/AgentInlineReview.vue';
import BusinessActionDialog from '../components/BusinessActionDialog.vue';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import InpatientPrototypeRail from '../components/InpatientPrototypeRail.vue';
import { toClinicalIssue } from '../clinical-error';

type ClinicalEventType = 'CONSULTATION_REQUESTED' | 'PREOPERATIVE_DECISION' | 'OPERATION_COMPLETED'
  | 'RESCUE_COMPLETED' | 'TRANSFUSION_COMPLETED' | 'CRITICAL_ILLNESS_DECLARED' | 'DEATH_CONFIRMED';

const inpatient = useQuery({
  queryKey: ['clinical', 'inpatient-workspace'],
  queryFn: async () => {
    const [encounterLease, wardLease] = await Promise.all([issueInpatientLease(), issueWardLease()]);
    const [overview, worklist, documentRules] = await Promise.all([
      loadInpatientOverview(encounterLease), loadInpatientWorklist(wardLease), loadInpatientDocumentRules(wardLease),
    ]);
    return { encounterLease, wardLease, overview, worklist, documentRules };
  },
  retry: false, staleTime: 0, gcTime: 0,
});
const router = useRouter();

const busy = ref<string | null>(null);
const notice = ref('');
const eventOpen = ref(false);
const dailyCourseOpen = ref(false);
const eventType = ref<ClinicalEventType>('RESCUE_COMPLETED');
const eventSummary = ref('');
const rejectionTaskId = ref<string | null>(null);
const startTaskId = ref<string | null>(null);
const rejectionReason = ref('');
const selectedActorKey = ref<InpatientSyntheticActorKey>(getInpatientSyntheticActor()?.key ?? 'AUTHOR');
const issue = computed(() => inpatient.error.value ? toClinicalIssue(inpatient.error.value) : null);
const overview = computed(() => inpatient.data.value?.overview);
const admission = computed(() => overview.value?.admission);
const agentPatientId = computed(() => admission.value?.patient_id ?? null);
const agentEncounterId = computed(() => admission.value?.encounter_id ?? null);
const inpatientDailyObjective = computed(() => '汇总当前住院就诊的日常诊疗进展，输出摘要候选，仅供医生审阅。');
const rules = computed(() => inpatient.data.value?.documentRules ?? []);
const pendingCount = computed(() => overview.value?.document_tasks.filter((task) => task.task_state !== 'COMPLETED').length ?? 0);
const overdueCount = computed(() => overview.value?.document_tasks.filter((task) => task.task_state === 'OVERDUE').length ?? 0);
const activeActor = computed(() => inpatientSyntheticActors.find((actor) => actor.key === selectedActorKey.value));
const rejectionTask = computed(() => overview.value?.document_tasks.find((task) => task.task_id === rejectionTaskId.value) ?? null);
const startTaskTarget = computed(() => overview.value?.document_tasks.find((task) => task.task_id === startTaskId.value) ?? null);

async function execute(label: string, action: () => Promise<void>) {
  if (busy.value) return;
  busy.value = label; notice.value = '';
  try { await action(); await inpatient.refetch(); }
  catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = null; }
}


function createDailyCourse() {
  const data = inpatient.data.value; if (!data) return;
  void execute('daily', async () => {
    const occurredAt = new Date().toISOString();
    await createInpatientDocumentTask(data.encounterLease, data.overview, 'IP-DAILY-COURSE', occurredAt, `DAILY:${occurredAt}:${crypto.randomUUID()}`);
    dailyCourseOpen.value = false;
    notice.value = '已创建新的日常病程任务';
  });
}
function createClinicalEvent() {
  const data = inpatient.data.value; if (!data || !eventSummary.value.trim()) return;
  void execute('event', async () => {
    await createInpatientClinicalEvent(data.encounterLease, data.overview, eventType.value, eventSummary.value.trim());
    eventSummary.value = ''; eventOpen.value = false; notice.value = '临床事件与对应文书任务已同事务创建';
  });
}
function startTask(task: InpatientDocumentTaskWire) {
  const data = inpatient.data.value; if (!data) return;
  void execute(`start:${task.task_id}`, async () => {
    const rule = data.documentRules.find((candidate) => candidate.document_type_code === task.document_type_code);
    await startInpatientDocumentTask(data.encounterLease, task, rule); startTaskId.value = null; notice.value = '住院文书草稿已建立';
  });
}
function beginReject(task: InpatientDocumentTaskWire) { rejectionTaskId.value = task.task_id; rejectionReason.value = ''; }
function rejectTask(task: InpatientDocumentTaskWire) {
  const data = inpatient.data.value; const level = task.next_signature_level;
  if (!data || !level || level === 'AUTHOR' || !rejectionReason.value.trim()) return;
  void execute(`reject:${task.task_id}`, async () => {
    await rejectInpatientDocumentReview(data.encounterLease, task, level, rejectionReason.value.trim());
    rejectionTaskId.value = null; rejectionReason.value = ''; notice.value = '审签已退回，必须形成新版本后重新进入审签';
  });
}
async function openPatient(item: InpatientWorklistItemWire) {
  if (busy.value) return;
  selectInpatientContext(item);
  await router.push('/inpatient-overview');
}
function documentLabel(code: string, documentRules: InpatientDocumentRuleWire[]) { return documentRules.find((rule) => rule.document_type_code === code)?.display_name || code; }
function signatureLabel(level: 'AUTHOR' | 'ATTENDING' | 'CHIEF' | 'MEDICAL_RECORDS') { return { AUTHOR: '作者签名', ATTENDING: '主治审签', CHIEF: '主任审签', MEDICAL_RECORDS: '病案确认' }[level]; }
function signatureProgress(task: InpatientDocumentTaskWire) {
  if (task.review_status === 'COMPLETED') return `审签完成 · ${signatureLabel(task.required_signature_level)}`;
  if (task.review_status === 'REJECTED') return '已退回修改';
  if (task.next_signature_level) return `待${signatureLabel(task.next_signature_level)} · 终审${signatureLabel(task.required_signature_level)}`;
  return `终审${signatureLabel(task.required_signature_level)}`;
}
function canActOnTask(task: InpatientDocumentTaskWire) {
  if (!task.working_document_id) return selectedActorKey.value === 'AUTHOR';
  if (task.review_status === 'REJECTED' || task.next_signature_level === 'AUTHOR') return selectedActorKey.value === 'AUTHOR';
  return task.next_signature_level === selectedActorKey.value;
}
function formatDate(value: string) { return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value)); }
</script>

<template>
  <section data-page-root class="content vue-native-page">
    <div class="page-head">
      <div class="page-title"><h1>住院医生工作站</h1><p>心内科一病区 · 在管患者实时刷新</p></div>
      <div class="head-actions"><RouterLink class="btn" to="/admission-bed">入院与床位</RouterLink><RouterLink class="btn primary" to="/inpatient-overview">进入患者总览</RouterLink></div>
    </div>
    <ClinicalPageState v-if="inpatient.isPending.value" kind="loading" message="正在加载病区、患者和床位信息" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="inpatient.refetch()" />
    <template v-else-if="overview && admission && inpatient.data.value">
      <section class="patient-strip" aria-label="住院患者上下文"><div class="patient-avatar">{{ overview.patient_display_name.slice(0, 1) }}</div><div><strong>{{ overview.patient_display_name }}</strong><span>{{ developmentCopy.contextNotice }}</span></div><dl><div><dt>住院状态</dt><dd>{{ admission.status }}</dd></div><div><dt>病区床位</dt><dd>{{ overview.ward_display_name }} · {{ overview.bed_label }}床</dd></div><div><dt>当前岗位</dt><dd>{{ activeActor?.roleLabel ?? '住院医师' }} · {{ activeActor?.displayName ?? '当前登录用户' }}</dd></div></dl><span class="lease-badge">当前患者 / 当前住院</span></section>
      <div class="metric-grid" aria-label="住院关键指标">
        <div class="metric"><div class="name">病区在院</div><div class="value">{{ inpatient.data.value.worklist.length }}</div><div class="trend">仅当前岗位授权范围</div></div>
        <div class="metric"><div class="name">待完成文书</div><div class="value">{{ pendingCount }}</div><div class="trend">按国家时限与机构规则</div></div>
        <div class="metric"><div class="name">已超时任务</div><div class="value" :class="{ 'danger-text': overdueCount > 0 }">{{ overdueCount }}</div><div class="trend">{{ overdueCount > 0 ? '需立即处理' : '当前无超时' }}</div></div>
        <div class="metric"><div class="name">数据水印</div><div class="value">{{ overview.data_watermark.slice(0, 8) }}</div><div class="trend">刷新后重新校验</div></div>
      </div>
      <AgentInlineReview agent-code="ENCOUNTER_SUMMARIZER" stage-code="INPATIENT_DAILY" :objective="inpatientDailyObjective" :patient-id="agentPatientId" :encounter-id="agentEncounterId" target-type="ENCOUNTER" :target-id="agentEncounterId" title="AI 住院日常摘要候选" source-route="inpatient" />
      <div class="grid inpatient-grid"><aside class="card scroll-card"><div class="card-head">病区患者 <span class="sub">{{ inpatient.data.value.worklist.length }} 人</span></div><div class="ward-list"><button v-for="item in inpatient.data.value.worklist" :key="item.admission_id" type="button" :class="{ active: item.admission_id === admission.admission_id }" @click="openPatient(item)"><b>{{ item.bed_label }}床 · {{ item.patient_display_name }}</b><span>待办 {{ item.pending_task_count }} · 超时 {{ item.overdue_task_count }}</span></button></div></aside><section class="editor-card inpatient-overview-card"><div class="card-toolbar"><div><h2>单患者住院总览</h2><span class="state-chip signed">{{ admission.status }}</span></div><div class="toolbar-actions"><RouterLink class="button secondary" to="/inpatient-overview">打开完整总览</RouterLink><button class="button secondary" :disabled="Boolean(busy) || admission.status !== 'ADMITTED' || selectedActorKey !== 'AUTHOR'" @click="dailyCourseOpen = true">新增病程</button><button v-if="admission.status === 'ADMITTED'" class="button secondary" :disabled="Boolean(busy) || selectedActorKey !== 'AUTHOR'" @click="eventOpen = true">记录临床事件</button><RouterLink v-if="admission.status === 'ADMITTED'" class="button secondary" to="/inpatient-discharge">办理出院</RouterLink></div></div>
        <div class="inpatient-summary-grid"><div><span>入院时间</span><strong>{{ formatDate(admission.admitted_at) }}</strong></div><div><span>当前病区</span><strong>{{ overview.ward_display_name }}</strong></div><div><span>当前床位</span><strong>{{ overview.bed_label }}床</strong></div><div><span>住院号</span><strong>…{{ admission.admission_id.slice(-8) }}</strong></div></div>
        <div v-if="notice" class="inline-notice" :class="{ error: notice.includes('：') }" role="status">{{ notice }}</div>
        <BusinessActionDialog :open="dailyCourseOpen" title="新增日常病程任务" description="确认后会产生真实待办并纳入文书时限与审签流程。" confirm-label="确认新增病程" :busy="Boolean(busy)" @cancel="dailyCourseOpen = false" @confirm="createDailyCourse"><p class="dialog-warning">当前患者：{{ overview.patient_display_name }} · {{ overview.bed_label }}床</p></BusinessActionDialog>
        <BusinessActionDialog :open="eventOpen && admission.status === 'ADMITTED'" title="记录住院临床事件" description="事件事实与对应文书任务同事务提交；重复来源事件不会重复建任务。" confirm-label="确认并生成文书任务" :busy="Boolean(busy)" width="wide" @cancel="eventOpen = false" @confirm="createClinicalEvent"><label>事件类型<select v-model="eventType"><option value="CONSULTATION_REQUESTED">发起会诊</option><option value="PREOPERATIVE_DECISION">确定手术方案</option><option value="OPERATION_COMPLETED">手术完成</option><option value="RESCUE_COMPLETED">抢救结束</option><option value="TRANSFUSION_COMPLETED">输血完成</option><option value="CRITICAL_ILLNESS_DECLARED">宣布病危/病重</option><option value="DEATH_CONFIRMED">确认死亡</option></select></label><label>事件摘要<textarea v-model="eventSummary" required rows="4" maxlength="1000" placeholder="记录时间、关键事实、参与人员与结果" /></label></BusinessActionDialog>
        <BusinessActionDialog :open="Boolean(rejectionTask)" title="退回住院病历修改" description="退回会撤销当前审签推进，作者必须形成新版本并重新质控；旧版本和签名证据仍保留。" confirm-label="确认退回修改" :busy="Boolean(busy)" :confirm-disabled="rejectionReason.trim().length < 4" danger @cancel="rejectionTaskId = null; rejectionReason = ''" @confirm="rejectionTask && rejectTask(rejectionTask)"><p v-if="rejectionTask" class="dialog-warning">{{ documentLabel(rejectionTask.document_type_code, rules) }} · 当前待 {{ rejectionTask.next_signature_level }}</p><label>退回原因（至少 4 字）<textarea v-model="rejectionReason" aria-label="审签退回原因" maxlength="1000" rows="4" placeholder="填写明确、可执行的修改要求" /></label></BusinessActionDialog>
        <BusinessActionDialog :open="Boolean(startTaskTarget)" title="建立住院病历草稿" description="将依据文书规则创建真实结构化草稿并把任务推进到处理中；后续修改通过版本化保存完成。" confirm-label="确认建立草稿" :busy="Boolean(busy)" @cancel="startTaskId = null" @confirm="startTaskTarget && startTask(startTaskTarget)"><p v-if="startTaskTarget" class="dialog-warning">{{ documentLabel(startTaskTarget.document_type_code, rules) }} · 截止 {{ formatDate(startTaskTarget.due_at) }}</p></BusinessActionDialog>
        <div class="task-table-wrap">
          <table class="task-table">
            <thead><tr><th>住院文书任务</th><th>截止时间</th><th>状态</th><th>动作</th></tr></thead>
            <tbody><tr v-for="task in overview.document_tasks" :key="task.task_id">
              <td><strong>{{ documentLabel(task.document_type_code, rules) }}</strong><small>{{ task.document_type_code }}</small></td>
              <td>{{ formatDate(task.due_at) }}</td>
              <td><span class="task-state" :class="task.task_state.toLowerCase()">{{ task.task_state }}</span><small>{{ signatureProgress(task) }}</small></td>
              <td>
                <RouterLink v-if="task.task_state === 'COMPLETED' && (task.completed_document_id || task.working_document_id)" :to="{ path: '/inpatient-doc-versions', query: { document_id: task.completed_document_id || task.working_document_id } }">查看版本证据</RouterLink>
                <template v-else-if="task.working_document_id">
                  <RouterLink v-if="canActOnTask(task)" :to="{ path: '/inpatient-doc-editor', query: { document_id: task.working_document_id } }">{{ task.review_status === 'IN_REVIEW' && task.next_signature_level !== 'AUTHOR' ? '进入审签' : '继续书写' }}</RouterLink>
                  <span v-else class="review-wait">等待{{ task.next_signature_level ? signatureLabel(task.next_signature_level) : '前序处理' }}</span>
                  <button v-if="canActOnTask(task) && task.review_status === 'IN_REVIEW' && task.next_signature_level && task.next_signature_level !== 'AUTHOR'" class="task-action task-reject-toggle" @click="beginReject(task)">退回修改</button>
                </template>
                <button v-else class="task-action" :disabled="Boolean(busy) || selectedActorKey !== 'AUTHOR'" @click="startTaskId = task.task_id">{{ selectedActorKey !== 'AUTHOR' ? '等待作者建稿' : '开始书写' }}</button>
              </td>
            </tr></tbody>
          </table>
        </div></section>
        <InpatientPrototypeRail mode="worklist" :patient-name="overview.patient_display_name" :bed-label="overview.bed_label" :ward-name="overview.ward_display_name" :pending-count="pendingCount" :overdue-count="overdueCount" />
      </div>
    </template>
  </section>
</template>
