<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, ref, watch } from 'vue';
import type { WaitingQueueEntryWire } from '../../generated/contracts';
import { clinicalContext } from '../../clinical-api';
import { callWaitingQueueEntry, issueEmergencyFacilityLease, listWaitingQueue } from '../../api/emergency';
import { loadOutpatientWorkspaceSnapshot } from '../../api/outpatient-workspace';
import { developmentCopy } from '../../development-copy';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import BusinessActionDialog from '../components/BusinessActionDialog.vue';
import { clinicalCodeLabel } from '../clinical-display';
import { toClinicalIssue } from '../clinical-error';
import { useClinicalContextStore } from '../stores/clinical-context';

const contextStore = useClinicalContextStore();
const selectedPatientId = ref(clinicalContext.patientId);
const selectedEncounterId = ref(clinicalContext.encounterId);
const queueFilter = ref<'ALL' | WaitingQueueEntryWire['status']>('ALL');
const busy = ref('');
const notice = ref('');
const queueSearch = ref('');
const callTarget = ref<WaitingQueueEntryWire | null>(null);

const facilityLease = useQuery({ queryKey: ['outpatient', 'facility-lease'], queryFn: () => issueEmergencyFacilityLease('OUTPATIENT_QUEUE'), retry: false, staleTime: 5 * 60_000, gcTime: 0 });
const queueQuery = useQuery({ queryKey: ['outpatient', 'queue'], queryFn: () => listWaitingQueue(facilityLease.data.value!), enabled: () => Boolean(facilityLease.data.value), retry: false });
const snapshotQuery = useQuery({
  queryKey: computed(() => ['outpatient', 'workspace', selectedPatientId.value, selectedEncounterId.value]),
  queryFn: () => loadOutpatientWorkspaceSnapshot(selectedPatientId.value, selectedEncounterId.value),
  retry: false, staleTime: 0, gcTime: 0,
});

watch(() => snapshotQuery.data.value, (next) => {
  if (!next) return;
  contextStore.replaceFromLease(next.lease);
  const document = next.documents.find((item) => item.status !== 'VOID') ?? next.documents[0];
  if (document) contextStore.activateDocument(document.document_id);
}, { immediate: true });

const queue = computed(() => queueQuery.data.value ?? []);
const filteredQueue = computed(() => {
  const keyword = queueSearch.value.trim().toLowerCase();
  return queue.value.filter((entry) => (queueFilter.value === 'ALL' || entry.status === queueFilter.value)
    && (!keyword || `${entry.patient_display_name} ${entry.sequence_no}`.toLowerCase().includes(keyword)));
});
const snapshot = computed(() => snapshotQuery.data.value);
const documents = computed(() => snapshot.value?.documents ?? []);
const currentDocument = computed(() => documents.value.find((item) => item.status !== 'VOID') ?? documents.value[0]);
const diagnoses = computed(() => snapshot.value?.diagnoses ?? []);
const orders = computed(() => snapshot.value?.orders ?? []);
const results = computed(() => snapshot.value?.results ?? []);
const timeline = computed(() => snapshot.value?.timeline.items ?? []);
const criticalValues = computed(() => results.value.flatMap((item) => item.critical_values).filter((item) => item.state !== 'DISPOSED'));
const activeOrders = computed(() => orders.value.filter((item) => !['COMPLETED', 'CANCELLED', 'STOPPED'].includes(item.status)));
const provisionalDiagnoses = computed(() => diagnoses.value.filter((item) => item.status === 'PROVISIONAL'));
const partialTimeline = computed(() => snapshot.value?.timeline.completeness === 'PARTIAL');
const selectedQueueEntry = computed(() => queue.value.find((entry) => entry.patient_id === selectedPatientId.value && entry.encounter_id === selectedEncounterId.value));
const patientLabel = computed(() => selectedQueueEntry.value?.patient_display_name ?? developmentCopy.patientName);
const waitingCount = computed(() => queue.value.filter((entry) => entry.status === 'WAITING').length);
const completedCount = computed(() => queue.value.filter((entry) => entry.status === 'COMPLETED').length);
const primaryDiagnosis = computed(() => diagnoses.value.find((item) => item.diagnosis_role === 'PRIMARY'));
const issue = computed(() => {
  const failed = [facilityLease, queueQuery, snapshotQuery].find((query) => query.error.value);
  return failed ? toClinicalIssue(failed.error.value) : null;
});

function selectPatient(entry: WaitingQueueEntryWire) {
  if (busy.value) return;
  contextStore.activateOutpatient({
    patientId: entry.patient_id,
    encounterId: entry.encounter_id,
    patientDisplayName: entry.patient_display_name,
    documentId: null,
  });
  selectedPatientId.value = entry.patient_id;
  selectedEncounterId.value = entry.encounter_id;
  notice.value = `已切换至候诊 #${entry.sequence_no}，正在加载该患者的信息。`;
}

async function callPatient(entry: WaitingQueueEntryWire) {
  const lease = facilityLease.data.value;
  if (!lease || busy.value || entry.status !== 'WAITING') return;
  busy.value = entry.waiting_queue_entry_id; notice.value = '';
  try {
    const updated = await callWaitingQueueEntry(lease, entry);
    notice.value = `候诊 #${updated.sequence_no} 已叫号；状态 ${clinicalCodeLabel(updated.status)}，版本 v${updated.row_version}。`;
    await queueQuery.refetch();
    callTarget.value = null;
  } catch (error) {
    const next = toClinicalIssue(error);
    notice.value = next.code === 'VERSION_CONFLICT'
      ? '队列已被其他工作站更新，请刷新后重试。'
      : `${next.code}：${next.message}`;
  } finally { busy.value = ''; }
}

function beginCall(entry: WaitingQueueEntryWire) {
  if (entry.status !== 'WAITING' || busy.value) return;
  callTarget.value = entry;
}

function beginNextPatient() {
  const next = queue.value.find((entry) => entry.status === 'WAITING');
  if (!next) {
    notice.value = '当前没有待叫号患者。';
    return;
  }
  callTarget.value = next;
}

async function refreshWorkspace() {
  await Promise.all([queueQuery.refetch(), snapshotQuery.refetch()]);
}

function date(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value));
}

function age(birthDate?: string) {
  if (!birthDate) return '—';
  const birth = new Date(birthDate);
  const now = new Date();
  let years = now.getFullYear() - birth.getFullYear();
  if (now.getMonth() < birth.getMonth() || (now.getMonth() === birth.getMonth() && now.getDate() < birth.getDate())) years -= 1;
  return `${Math.max(0, years)}岁`;
}

function sexLabel(code?: string) {
  return code === 'M' || code === 'MALE' || code === '1' ? '男' : code === 'F' || code === 'FEMALE' || code === '2' ? '女' : '未说明';
}

function sectionText(key: string, fallback: string) {
  const value = currentDocument.value?.sections?.[key];
  return typeof value === 'string' && value.trim() ? value : fallback;
}

function printWorkspace() {
  window.print();
}
</script>

<template>
  <section data-page-root class="content vue-native-page outpatient-workspace-page">
    <div class="page-heading outpatient-heading"><div><p class="eyebrow">临床业务门户 / 门诊</p><h1>门诊医生工作台</h1><p>今日门诊 · 已接诊 {{ completedCount }} 人 · 候诊 {{ waitingCount }} 人</p></div><div class="toolbar-actions"><RouterLink class="button secondary" to="/opd-record">本次门诊病历</RouterLink><RouterLink class="button secondary" to="/patient-timeline">跨域：全院病历中心</RouterLink><button class="button secondary" type="button" @click="printWorkspace">打印清单</button><button class="button primary" type="button" @click="beginNextPatient">下一位患者</button></div></div>
    <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>
    <ClinicalPageState v-if="facilityLease.isPending.value || queueQuery.isPending.value || snapshotQuery.isPending.value" kind="loading" message="正在加载候诊队列与患者诊疗快照" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="refreshWorkspace" />
    <template v-else-if="snapshot">
      <section class="patient-strip outpatient-patient-strip" aria-label="当前患者上下文"><div class="patient-avatar">{{ patientLabel.slice(0, 1) }}</div><div class="patient-identity"><strong>{{ patientLabel }}</strong><span>{{ sexLabel(selectedQueueEntry?.patient_sex_code) }} · {{ age(selectedQueueEntry?.patient_birth_date) }} · 患者 …{{ selectedPatientId.slice(-8) }}</span></div><dl><div><dt>门诊号</dt><dd>OP…{{ selectedEncounterId.slice(-8) }}</dd></div><div><dt>就诊状态</dt><dd>{{ selectedQueueEntry ? clinicalCodeLabel(selectedQueueEntry.status) : '诊疗中' }}</dd></div><div><dt>临床摘要</dt><dd>{{ primaryDiagnosis?.diagnosis_text ?? '待完成诊断' }}</dd></div></dl><div class="patient-risk-tags"><span v-if="criticalValues.length" class="danger">危急值 {{ criticalValues.length }} 项</span><span v-if="provisionalDiagnoses.length" class="warning">待确认诊断 {{ provisionalDiagnoses.length }}</span><span v-if="!criticalValues.length && !provisionalDiagnoses.length" class="safe">无未闭环高风险</span></div><button class="button secondary switch-patient" type="button" @click="queueSearch = ''; queueFilter = 'ALL'">切换门诊患者</button></section>
      <div class="outpatient-dashboard">
        <aside class="outpatient-queue admin-panel scroll-card"><header><div><h2>候诊队列</h2><p>{{ filteredQueue.length }} / {{ queue.length }} 人</p></div></header><div class="queue-filters"><input v-model="queueSearch" type="search" placeholder="筛选患者" aria-label="筛选患者" /><select v-model="queueFilter" aria-label="候诊状态筛选"><option value="ALL">全部状态</option><option value="WAITING">候诊</option><option value="CALLED">已叫号</option><option value="IN_CONSULTATION">接诊中</option><option value="COMPLETED">已完成</option></select></div><div v-if="!filteredQueue.length" class="admin-empty">当前筛选下暂无候诊患者。</div><div v-else class="queue-list"><article v-for="entry in filteredQueue" :key="entry.waiting_queue_entry_id" :data-queue-sequence="entry.sequence_no" :class="{ active: entry.patient_id === selectedPatientId && entry.encounter_id === selectedEncounterId }"><button class="queue-patient" data-select-outpatient-patient :aria-label="`选择候诊 ${entry.sequence_no} 号患者 ${entry.patient_display_name}`" @click="selectPatient(entry)"><span class="queue-name"><i :class="entry.status.toLowerCase()" /> <b>{{ entry.patient_display_name }}</b><time>{{ entry.queue_date.slice(-5) }}</time></span><span>{{ clinicalCodeLabel(entry.status) }} · #{{ entry.sequence_no }}</span><small>{{ sexLabel(entry.patient_sex_code) }} · {{ age(entry.patient_birth_date) }} · 就诊 …{{ entry.encounter_id.slice(-8) }}</small></button><button v-if="entry.status === 'WAITING'" class="task-action" :disabled="Boolean(busy)" @click="beginCall(entry)">叫号</button></article></div></aside>
        <section class="outpatient-center">
          <section class="admin-panel encounter-editor scroll-card"><nav class="encounter-tabs"><RouterLink class="active" to="/outpatient">本次病历</RouterLink><RouterLink to="/opd-diagnosis">诊断</RouterLink><RouterLink to="/opd-orders">医嘱处方</RouterLink><RouterLink to="/opd-results">结果</RouterLink></nav><div class="encounter-body"><section class="previsit-summary"><strong>✦ AI 诊前摘要 <span>{{ snapshot.timeline.source_statuses.filter((item) => item.state === 'AVAILABLE').length }} 条授权来源</span></strong><p>已聚合 {{ timeline.length }} 条时间线事实、{{ diagnoses.length }} 条诊断、{{ activeOrders.length }} 条活动医嘱和 {{ results.length }} 份结果；摘要仅供医生核对。</p></section><h3>门诊病历</h3><div class="record-fields"><label><span>主诉 <em>*</em></span><textarea readonly rows="1" :value="sectionText('chief_complaint', '待在门诊病历中录入')" /></label><label><span>现病史 <em>*</em></span><textarea readonly rows="3" :value="sectionText('present_illness', '待在门诊病历中录入')" /></label><label><span>体格检查</span><textarea readonly rows="2" :value="sectionText('physical_exam', '暂无已保存体格检查')" /></label><label><span>诊断</span><textarea readonly rows="1" :value="primaryDiagnosis?.diagnosis_text ?? '待建立主诊断'" /></label></div><div v-if="provisionalDiagnoses.length || criticalValues.length" class="signing-warning"><strong>⚠ 签署前提示</strong><p v-if="provisionalDiagnoses.length">{{ provisionalDiagnoses.length }} 条初步诊断待确认。</p><p v-if="criticalValues.length">{{ criticalValues.length }} 项危急值尚未完成处置。</p></div></div><footer><span class="save-state">● {{ currentDocument ? `服务端已保存 v${currentDocument.version_no}` : '尚未建立当次病历' }}</span><div class="toolbar-actions"><RouterLink class="button secondary" to="/opd-record">{{ currentDocument ? '继续编辑' : '新建病历' }}</RouterLink><RouterLink class="button secondary" to="/record-qc">预览质控</RouterLink><RouterLink class="button primary" to="/record-sign">提交并签署</RouterLink></div></footer></section>
        </section>
        <aside class="patient-context admin-panel scroll-card"><header><div><h2>患者上下文</h2><p>服务端当前快照</p></div></header><div class="context-body"><h3>待办与风险</h3><article v-if="criticalValues.length" class="context-alert danger"><strong>! 未闭环危急值 {{ criticalValues.length }} 项</strong><p>开立新处方前请先完成复读、评估和处置。</p><RouterLink to="/opd-results">立即处置</RouterLink></article><article v-if="provisionalDiagnoses.length" class="context-alert warning"><strong>待确认诊断 {{ provisionalDiagnoses.length }} 条</strong><p>初步诊断仍会阻止部分签署与终诊流程。</p><RouterLink to="/opd-diagnosis">核对诊断</RouterLink></article><article class="context-alert ai"><strong>✦ AI 用药提示</strong><p>{{ activeOrders.some((order) => order.items.some((item) => item.item_type === 'MEDICATION')) ? '已检测到药品医嘱，签署前必须通过服务端确定性用药安全规则。' : '当前无药品医嘱；AI 不会自动开立处方。' }}</p></article><div v-if="!criticalValues.length && !provisionalDiagnoses.length" class="context-clear">当前无未闭环确定性风险。</div><h3>近期时间线</h3><ol class="context-timeline"><li v-for="item in timeline.slice(0, 6)" :key="`${item.item_type}-${item.resource_id}`"><time>{{ date(item.occurred_at) }}</time><div><strong>{{ item.title }}</strong><p>{{ item.summary || clinicalCodeLabel(item.status) }}</p></div></li></ol><div v-if="!timeline.length" class="admin-empty">当前没有授权时间线资料。</div><RouterLink class="button secondary full" to="/patient-timeline">查看完整时间线</RouterLink></div></aside>
      </div>
      <BusinessActionDialog :open="Boolean(callTarget)" title="叫号并切换患者" description="确认后将更新候诊状态，并载入该患者的信息。" eyebrow="门诊工作台" confirm-label="确认叫号" :busy="Boolean(busy)" @cancel="callTarget = null" @confirm="callTarget && callPatient(callTarget)"><p v-if="callTarget" class="dialog-warning">#{{ callTarget.sequence_no }} · {{ callTarget.patient_display_name }} · {{ sexLabel(callTarget.patient_sex_code) }} {{ age(callTarget.patient_birth_date) }}</p></BusinessActionDialog>
    </template>
  </section>
</template>

<style scoped>
.outpatient-workspace-page{min-width:0;max-width:100%;box-sizing:border-box}.outpatient-heading{align-items:flex-start;margin-bottom:12px}.outpatient-patient-strip{margin-bottom:14px;gap:14px}.patient-identity{min-width:174px}.outpatient-patient-strip dl{flex:1}.patient-risk-tags{display:flex;flex-wrap:wrap;gap:6px}.patient-risk-tags span{padding:5px 8px;border-radius:6px;font-size:11px;font-weight:700}.patient-risk-tags .danger{color:#b42318;background:#fff0f0}.patient-risk-tags .warning{color:#9a5b08;background:#fff5df}.patient-risk-tags .safe{color:#067647;background:#ecfdf3}.switch-patient{white-space:nowrap}.outpatient-dashboard{display:grid;grid-template-columns:260px minmax(0,1fr) 292px;gap:14px;align-items:stretch;min-width:0}.scroll-card{height:685px;min-height:0;overflow:auto}.outpatient-queue{min-width:0}.outpatient-queue header{position:sticky;top:0;z-index:2;background:#fff}.queue-filters{position:sticky;top:57px;z-index:2;display:grid;grid-template-columns:minmax(0,1fr) 98px;gap:8px;padding:10px 12px;border-bottom:1px solid var(--line);background:#fff}.queue-filters input,.queue-filters select{min-width:0;padding:8px;border:1px solid var(--line);border-radius:7px;background:#fff}.queue-list{display:grid}.queue-list article{display:grid;grid-template-columns:minmax(0,1fr) auto;align-items:center;gap:8px;padding:12px;border-bottom:1px solid var(--line)}.queue-list article.active{background:#eef6ff;box-shadow:inset 3px 0 #1677ff}.queue-patient{display:grid;min-width:0;gap:4px;padding:0;border:0;background:transparent;text-align:left;cursor:pointer}.queue-name{display:grid;grid-template-columns:auto minmax(0,1fr) auto;align-items:center;gap:6px}.queue-name i{width:7px;height:7px;border-radius:50%;background:#98a2b3}.queue-name i.waiting{background:#f79009}.queue-name i.called,.queue-name i.in_consultation{background:#12b76a}.queue-name time{font-size:11px;font-weight:700;color:#667085}.queue-patient span,.queue-patient small{overflow:hidden;text-overflow:ellipsis;font-size:11px;color:#667085;white-space:nowrap}.outpatient-center{min-width:0}.encounter-editor{display:flex;flex-direction:column;overflow:hidden}.encounter-tabs{display:flex;gap:4px;padding:8px;border-bottom:1px solid var(--line);overflow-x:auto}.encounter-tabs a{padding:9px 14px;border-radius:7px;color:#526175;text-decoration:none;white-space:nowrap}.encounter-tabs a.active{color:#1264d1;background:#edf5ff;font-weight:700}.encounter-body{flex:1;overflow:auto;padding:20px}.previsit-summary{padding:13px;border:1px solid #91caff;border-radius:8px;background:#eaf6ff}.previsit-summary strong{display:block;color:#215b84}.previsit-summary strong span{margin-left:6px;padding:2px 6px;border-radius:4px;color:#6941c6;background:#f1ebff;font-size:11px}.previsit-summary p{margin:6px 0 0;color:#22577a;font-size:12px;line-height:1.6}.encounter-body h3{margin:18px 0 12px}.record-fields{display:grid;gap:10px}.record-fields label{display:grid;grid-template-columns:128px minmax(0,1fr);align-items:start;gap:12px}.record-fields label>span{padding-top:10px;color:#536273;font-size:12px}.record-fields em{color:#d92d20}.record-fields textarea{width:100%;box-sizing:border-box;resize:none;padding:10px 12px;border:1px solid var(--line);border-radius:7px;background:#fff;color:#172033;font:inherit;line-height:1.55}.signing-warning{margin-top:12px;padding:12px;border:1px solid #f4c46f;border-radius:8px;background:#fff8e8}.signing-warning strong{color:#8a5100}.signing-warning p{margin:5px 0 0;color:#7a4d0b;font-size:12px}.encounter-editor footer{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:12px 14px;border-top:1px solid var(--line);background:#fff}.save-state{color:#078a55;font-size:12px}.patient-context{min-width:0}.patient-context>header{position:sticky;top:0;z-index:2;background:#fff}.context-body{padding:14px}.context-body h3{margin:0 0 10px;font-size:13px}.context-body h3:not(:first-child){margin-top:16px}.context-alert{display:grid;gap:5px;margin-bottom:9px;padding:11px;border-radius:8px;border:1px solid #d8dee8}.context-alert p{margin:0;font-size:12px;line-height:1.5}.context-alert a{font-size:12px}.context-alert.danger{border-color:#fda29b;background:#fff1f1;color:#9b1c1c}.context-alert.warning{border-color:#f4c46f;background:#fff8e8;color:#7a4d0b}.context-alert.ai{border-color:#c7b9ff;background:#f5f1ff;color:#5737a4}.context-clear{padding:10px;border-radius:8px;color:#067647;background:#ecfdf3;font-size:12px}.context-timeline{display:grid;gap:0;margin:0 0 14px;padding:0;list-style:none}.context-timeline li{position:relative;display:grid;grid-template-columns:72px minmax(0,1fr);gap:9px;padding:0 0 13px 14px;border-left:2px solid #ddebfa}.context-timeline li::before{position:absolute;top:3px;left:-5px;width:8px;height:8px;border-radius:50%;background:#cce4ff;content:''}.context-timeline time{font-size:10px;color:#667085}.context-timeline strong{font-size:12px}.context-timeline p{margin:3px 0 0;color:#667085;font-size:11px;line-height:1.45}.full{width:100%;justify-content:center}@media(max-width:1280px){.outpatient-dashboard{grid-template-columns:240px minmax(450px,1fr)}.patient-context{grid-column:1/-1;height:auto;max-height:420px}.context-body{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px 16px}.context-body h3,.context-body>.full{grid-column:1/-1}}@media(max-width:820px){.outpatient-dashboard{grid-template-columns:minmax(0,1fr)}.scroll-card{height:auto;max-height:none}.outpatient-patient-strip{display:grid;grid-template-columns:auto minmax(0,1fr);align-items:start}.outpatient-patient-strip dl,.patient-risk-tags,.switch-patient{grid-column:1/-1;width:100%;box-sizing:border-box}.patient-risk-tags span{white-space:normal}.record-fields label{grid-template-columns:1fr;gap:4px}.record-fields label>span{padding-top:0}.encounter-editor footer{align-items:flex-start;flex-direction:column}.context-body{display:block}.toolbar-actions{max-width:100%;flex-wrap:wrap}}@media print{.outpatient-heading .toolbar-actions,.switch-patient,.outpatient-queue .task-action{display:none}.outpatient-dashboard{grid-template-columns:240px 1fr}.patient-context{display:none}.scroll-card{height:auto;overflow:visible}}
</style>
