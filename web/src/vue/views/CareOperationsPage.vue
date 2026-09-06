<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref } from 'vue';
import type { NursingBedsideNoteWire, NursingCarePlanWire, ShiftHandoverWire, VitalSignRecordWire } from '../../generated/contracts';
import { clinicalContext, issueContextLease } from '../../clinical-api';
import { developmentCopy } from '../../development-copy';
import {
  administerMedication, closeNursingDischarge, completeNursingCarePlan, completeShiftHandover,
  createNursingBedsideNote, createNursingCarePlan, createShiftHandover, createShiftHandoverPatient,
  issueExecutionLease, issueExecutionPatientLease, listMedicationAdministrations, listNursingBedsideNotes, listNursingCarePlans,
  listNursingDischargeClosures, listShiftHandoverPatients, listShiftHandovers, listVitalSigns, recordVitalSigns,
} from '../../api/execution';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import AdminActionDialog from '../components/AdminActionDialog.vue';
import ExecutionPatientContextBar from '../components/ExecutionPatientContextBar.vue';
import { toClinicalIssue } from '../clinical-error';

type Tab = 'vitals' | 'care-plans' | 'medications' | 'handovers' | 'discharge' | 'bedside';
const tabs: { key: Tab; label: string }[] = [
  { key: 'vitals', label: '生命体征' },
  { key: 'care-plans', label: '护理计划' },
  { key: 'medications', label: '给药执行' },
  { key: 'handovers', label: '交接班' },
  { key: 'discharge', label: '出院闭环' },
  { key: 'bedside', label: '床旁记录' },
];
const collaborationModules = [
  { id: 'billing', icon: '¥', title: '费用与结算', description: '临床来源、价格版本、收费入账与冲正留痕' },
  { id: 'outpatient-pharmacy', icon: '药', title: '门诊药房', description: '处方审核、调剂发药、退药与库存追溯' },
  { id: 'inpatient-pharmacy', icon: '剂', title: '住院药房', description: '摆药、配液、发药退药与床旁给药协同' },
  { id: 'lab-workbench', icon: '检', title: '检验工作台', description: '申请、采样、标本、结果、危急值和报告闭环' },
  { id: 'pathology-workbench', icon: '病', title: '病理工作台', description: '标本接收、取材制片、诊断复核与报告签发' },
  { id: 'imaging-workbench', icon: '影', title: '检查影像', description: '预约准备、检查执行、图像交付与报告签发' },
  { id: 'therapy-workbench', icon: '治', title: '治疗执行', description: '治疗排程、患者与医嘱核对、执行及不良事件' },
  { id: 'surgery-schedule', icon: '术', title: '围手术期', description: '手术排程、三方安全核查、状态流转与完成' },
  { id: 'anesthesia-workbench', icon: '麻', title: '麻醉复苏', description: '术前评估、麻醉事件轴、连续监护与复苏去向' },
  { id: 'transfusion', icon: '血', title: '输血全链', description: '血制品核对、双人核验、输注及不良反应闭环' },
  { id: 'device-monitoring', icon: '监', title: '设备监护', description: '患者设备绑定、遥测趋势、时钟质量与告警确认' },
] as const;
const activeTab = ref<Tab>('vitals');
const notice = ref('');
const busy = ref('');
const createDialogOpen = ref(false);
const patientDialogOpen = ref(false);
const discontinueTarget = ref<NursingCarePlanWire | null>(null);
const discontinueReason = ref('');
const actionTitle = computed(() => ({ vitals: '记录生命体征', 'care-plans': '建立护理计划', medications: '执行给药', handovers: '建立交接班', discharge: '完成出院闭环', bedside: '创建床旁记录' })[activeTab.value]);

const leaseQuery = useQuery({
  queryKey: ['execution', 'care-operations', 'lease'],
  queryFn: () => issueExecutionLease('CARE_OPERATIONS'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const lease = computed(() => leaseQuery.data.value);
const patientLeaseQuery = useQuery({
  queryKey: ['execution', 'care-operations', 'patient-lease'],
  queryFn: () => issueExecutionPatientLease('CARE_OPERATIONS'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const patientLease = computed(() => patientLeaseQuery.data.value);
const wardLeaseQuery = useQuery({
  queryKey: ['execution', 'care-operations', 'ward-lease'],
  queryFn: () => issueContextLease(null, null, 'CARE_OPERATIONS_WARD'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const wardLease = computed(() => wardLeaseQuery.data.value);

const vitalsQuery = useQuery({
  queryKey: ['execution', 'care-operations', 'vitals'],
  queryFn: () => listVitalSigns(lease.value!),
  enabled: () => Boolean(lease.value), retry: false,
});
const carePlansQuery = useQuery({
  queryKey: ['execution', 'care-operations', 'care-plans'],
  queryFn: () => listNursingCarePlans(lease.value!),
  enabled: () => Boolean(lease.value), retry: false,
});
const medicationsQuery = useQuery({
  queryKey: ['execution', 'care-operations', 'medications'],
  queryFn: () => listMedicationAdministrations(lease.value!),
  enabled: () => Boolean(lease.value), retry: false,
});
const handoversQuery = useQuery({
  queryKey: ['execution', 'care-operations', 'handovers'],
  queryFn: () => listShiftHandovers(wardLease.value!),
  enabled: () => Boolean(wardLease.value), retry: false,
});
const dischargeQuery = useQuery({
  queryKey: ['execution', 'care-operations', 'discharge'],
  queryFn: () => listNursingDischargeClosures(patientLease.value!),
  enabled: () => Boolean(patientLease.value), retry: false,
});
const bedsideQuery = useQuery({
  queryKey: ['execution', 'care-operations', 'bedside'],
  queryFn: () => listNursingBedsideNotes(patientLease.value!),
  enabled: () => Boolean(patientLease.value), retry: false,
});
const selectedHandoverId = ref('');
const handoverPatientsQuery = useQuery({
  queryKey: ['execution', 'care-operations', 'handover-patients', selectedHandoverId],
  queryFn: () => listShiftHandoverPatients(wardLease.value!, selectedHandoverId.value),
  enabled: () => Boolean(wardLease.value && selectedHandoverId.value), retry: false,
});

const vitals = computed(() => vitalsQuery.data.value ?? []);
const carePlans = computed(() => carePlansQuery.data.value ?? []);
const medications = computed(() => medicationsQuery.data.value ?? []);
const handovers = computed(() => handoversQuery.data.value ?? []);
const discharges = computed(() => dischargeQuery.data.value ?? []);
const bedside = computed(() => bedsideQuery.data.value ?? []);
const handoverPatients = computed(() => handoverPatientsQuery.data.value ?? []);

const firstError = computed(() => [leaseQuery.error.value, patientLeaseQuery.error.value, wardLeaseQuery.error.value, vitalsQuery.error.value, carePlansQuery.error.value, medicationsQuery.error.value, handoversQuery.error.value, dischargeQuery.error.value, bedsideQuery.error.value].find(Boolean));
const issue = computed(() => firstError.value ? toClinicalIssue(firstError.value) : null);
const anyPending = computed(() => leaseQuery.isPending.value || patientLeaseQuery.isPending.value || wardLeaseQuery.isPending.value || vitalsQuery.isPending.value || carePlansQuery.isPending.value || medicationsQuery.isPending.value || handoversQuery.isPending.value || dischargeQuery.isPending.value || bedsideQuery.isPending.value);

function formatDate(value: string | null | undefined) {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—';
}

function selectTab(tab: Tab) { activeTab.value = tab; notice.value = ''; createDialogOpen.value = false; }

async function run(key: string, action: () => Promise<void>, success: string) {
  if (!lease.value || busy.value) return;
  busy.value = key; notice.value = '';
  try { await action(); notice.value = success; }
  catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}

// 生命体征
const vitalForm = reactive({ source: 'MANUAL' as VitalSignRecordWire['source'], temperature: 36.5, pulse: 72, respiration: 16, systolicBp: 120, diastolicBp: 80, spo2: 98 });
function submitVitals() {
  void run('vitals', async () => {
    await recordVitalSigns(lease.value!, {
      source: vitalForm.source, recorded_at: new Date().toISOString(),
      temperature: vitalForm.temperature, pulse: vitalForm.pulse, respiration: vitalForm.respiration,
      systolic_bp: vitalForm.systolicBp, diastolic_bp: vitalForm.diastolicBp, spo2: vitalForm.spo2,
    });
    await vitalsQuery.refetch();
    createDialogOpen.value = false;
  }, '生命体征已记录，采集来源与时间留痕。');
}

// 护理计划
type Priority = NursingCarePlanWire['priority'];
const planForm = reactive({ nursingProblem: '', goal: '', intervention: '', priority: 'MEDIUM' as Priority });
function submitCarePlan() {
  if (!planForm.nursingProblem.trim() || !planForm.goal.trim() || !planForm.intervention.trim()) return;
  void run('care-plan', async () => {
    await createNursingCarePlan(lease.value!, {
      nursing_problem: planForm.nursingProblem.trim(), goal: planForm.goal.trim(),
      intervention: planForm.intervention.trim(), priority: planForm.priority,
    });
    planForm.nursingProblem = ''; planForm.goal = ''; planForm.intervention = '';
    await carePlansQuery.refetch();
    createDialogOpen.value = false;
  }, '护理计划已建立，可完成后评估并关闭。');
}
function completePlan(plan: NursingCarePlanWire) {
  void run(`plan:${plan.care_plan_id}`, async () => {
    await completeNursingCarePlan(lease.value!, plan, 'COMPLETED', '护理目标达成，计划关闭。');
    await carePlansQuery.refetch();
  }, '护理计划已完成并评估关闭。');
}
function discontinuePlan() {
  const plan = discontinueTarget.value;
  if (!plan || !discontinueReason.value.trim()) return;
  void run(`plan:${plan.care_plan_id}`, async () => {
    await completeNursingCarePlan(lease.value!, plan, 'DISCONTINUED', discontinueReason.value.trim());
    discontinueTarget.value = null; discontinueReason.value = '';
    await carePlansQuery.refetch();
  }, '护理计划已终止，原因与审计证据已保留。');
}

// 给药执行
const medForm = reactive({ executionTaskId: '', drugCode: '', doseValue: 1, doseUnit: '片', routeCode: 'PO', verifiedBy: clinicalContext.collaboratorUserId, verificationNote: '' });
function submitMedication() {
  if (!medForm.executionTaskId.trim() || !medForm.drugCode.trim() || medForm.doseValue <= 0 || !medForm.routeCode.trim() || !medForm.verifiedBy.trim()) return;
  void run('medication', async () => {
    await administerMedication(lease.value!, {
      execution_task_id: medForm.executionTaskId.trim(), drug_code: medForm.drugCode.trim(),
      dose_value: medForm.doseValue, dose_unit: medForm.doseUnit.trim(),
      route_code: medForm.routeCode.trim(), administered_at: new Date().toISOString(),
      verified_by: medForm.verifiedBy.trim(), verification_note: medForm.verificationNote.trim() || null,
    });
    medForm.executionTaskId = ''; medForm.drugCode = ''; medForm.verificationNote = '';
    await medicationsQuery.refetch();
    createDialogOpen.value = false;
  }, '给药已双人核验并执行，关联医嘱执行任务。');
}

// 交接班
const handoverForm = reactive({ shiftFrom: '', shiftTo: '', incomingUserId: clinicalContext.collaboratorUserId, summary: '' });
const handoverPatientForm = reactive({ patientId: clinicalContext.patientId, summary: '', riskFlag: false });
function submitHandover() {
  if (!handoverForm.shiftFrom.trim() || !handoverForm.shiftTo.trim() || !handoverForm.incomingUserId.trim() || !handoverForm.summary.trim()) return;
  void run('handover', async () => {
    await createShiftHandover(wardLease.value!, {
      shift_from: handoverForm.shiftFrom.trim(), shift_to: handoverForm.shiftTo.trim(),
      incoming_user_id: handoverForm.incomingUserId.trim(), handover_summary: handoverForm.summary.trim(),
    });
    handoverForm.shiftFrom = ''; handoverForm.shiftTo = ''; handoverForm.summary = '';
    await handoversQuery.refetch();
    createDialogOpen.value = false;
  }, '交接班已建立，可添加患者或完成交接。');
}
function completeHandover(handover: ShiftHandoverWire) {
  void run(`handover:${handover.handover_id}`, async () => {
    await completeShiftHandover(wardLease.value!, handover);
    await handoversQuery.refetch();
  }, '交接班已完成。');
}
function selectHandover(id: string) { selectedHandoverId.value = id; }
function submitHandoverPatient() {
  if (!selectedHandoverId.value || !handoverPatientForm.patientId.trim() || !handoverPatientForm.summary.trim()) return;
  void run('handover-patient', async () => {
    await createShiftHandoverPatient(wardLease.value!, {
      handover_id: selectedHandoverId.value, patient_id: handoverPatientForm.patientId.trim(),
      summary: handoverPatientForm.summary.trim(), risk_flag: handoverPatientForm.riskFlag,
    });
    handoverPatientForm.summary = ''; handoverPatientForm.riskFlag = false;
    patientDialogOpen.value = false;
    await handoverPatientsQuery.refetch();
  }, '患者已加入交接班，风险标志显式记录。');
}

// 出院闭环
function submitDischarge() {
  void run('discharge', async () => {
    await closeNursingDischarge(patientLease.value!);
    await dischargeQuery.refetch();
    createDialogOpen.value = false;
  }, '护理出院闭环已完成。');
}

// 床旁记录
type BedsideType = NursingBedsideNoteWire['note_type'];
const bedsideForm = reactive({ noteType: 'NURSING_NOTE' as BedsideType, deviceId: '', content: '' });
function submitBedside() {
  if (!bedsideForm.deviceId.trim() || !bedsideForm.content.trim()) return;
  void run('bedside', async () => {
    const now = new Date().toISOString();
    await createNursingBedsideNote(patientLease.value!, {
      note_type: bedsideForm.noteType, recorded_at: now, synced_at: now,
      device_id: bedsideForm.deviceId.trim(), content: bedsideForm.content.trim(),
    });
    bedsideForm.deviceId = ''; bedsideForm.content = '';
    await bedsideQuery.refetch();
    createDialogOpen.value = false;
  }, '床旁记录已创建并同步。');
}
</script>

<template>
  <section data-page-root class="content vue-native-page">
    <div class="page-heading care-overview-heading">
      <div><p class="eyebrow">诊疗执行</p><h1>诊疗执行中心</h1><p>收费、药学、医技、治疗、手麻、输血与设备执行的统一入口。</p></div>
      <div class="toolbar-actions"><RouterLink class="button secondary" to="/clinical">角色工作台</RouterLink></div>
    </div>
    <ExecutionPatientContextBar />
    <section class="hub-hero care-overview-hero">
      <div><span>诊疗执行中心</span><h2>让医嘱从开立到执行、结果与费用形成同一条可对账证据链</h2><p>每个专业工作台保留自身资质、核查与状态机；中心聚合任务和风险，不使用统一“完成”绕过专业核验。</p></div>
    </section>
    <nav class="hub-module-grid care-module-grid" aria-label="诊疗执行专业工作台">
      <RouterLink v-for="module in collaborationModules" :key="module.id" class="hub-module" :to="`/${module.id}`">
        <span>{{ module.icon }}</span><b>{{ module.title }}</b><p>{{ module.description }}</p><i>进入 →</i>
      </RouterLink>
    </nav>
    <div class="care-nursing-heading"><div><p class="eyebrow">护理协同执行</p><h2>当前就诊护理操作</h2></div><p>生命体征、护理计划、给药执行、交接班、出院闭环与床旁记录。</p></div>
    <section class="patient-strip"><div class="patient-avatar">{{ developmentCopy.patientAvatar }}</div><div><strong>{{ developmentCopy.outpatientPatientName }}</strong><span>当前就诊护理协同</span></div><dl><div><dt>给药</dt><dd>双人核验</dd></div><div><dt>交接班</dt><dd>科室级</dd></div></dl><span class="lease-badge">当前患者 / 当前就诊</span></section>
    <div v-if="notice" class="inline-notice" :class="{ error: notice.includes('：') }" role="status">{{ notice }}</div>

    <div class="toolbar-actions care-tabs" role="tablist" aria-label="护理协同分区">
      <button v-for="tab in tabs" :key="tab.key" class="button" :class="activeTab === tab.key ? 'primary' : 'secondary'" role="tab" :aria-selected="activeTab === tab.key" @click="selectTab(tab.key)">{{ tab.label }}</button>
      <button class="button primary care-create-action" :disabled="Boolean(busy)" @click="createDialogOpen = true">{{ actionTitle }}</button>
    </div>

    <ClinicalPageState v-if="anyPending" kind="loading" message="正在建立护理协同上下文" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="leaseQuery.refetch()" />

    <template v-else>
      <!-- 生命体征 -->
      <section v-if="activeTab === 'vitals'" class="care-tab-content">
        <section class="admin-panel">
          <header><div><h2>生命体征</h2><p>来源 MANUAL / DEVICE 显式登记，含体温、脉搏、呼吸、血压与血氧。</p></div></header>
          <div v-if="vitals.length === 0" class="empty-state"><span>体</span><p>暂无生命体征记录</p><small>点击“记录生命体征”开始录入</small></div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>时间</th><th>来源</th><th>体温</th><th>脉搏</th><th>呼吸</th><th>血压</th><th>血氧</th></tr></thead>
              <tbody>
                <tr v-for="vital in vitals" :key="vital.vital_sign_record_id">
                  <td>{{ formatDate(vital.recorded_at) }}</td>
                  <td>{{ vital.source }}</td>
                  <td>{{ vital.temperature ?? '—' }}</td>
                  <td>{{ vital.pulse ?? '—' }}</td>
                  <td>{{ vital.respiration ?? '—' }}</td>
                  <td>{{ vital.systolic_bp ?? '—' }} / {{ vital.diastolic_bp ?? '—' }}</td>
                  <td>{{ vital.spo2 ?? '—' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </section>

      <!-- 护理计划 -->
      <section v-else-if="activeTab === 'care-plans'" class="care-tab-content">
        <section class="admin-panel">
          <header><div><h2>护理计划</h2><p>护理问题、目标与干预；完成后评估关闭。</p></div></header>
          <div v-if="carePlans.length === 0" class="empty-state"><span>护</span><p>暂无护理计划</p><small>点击“建立护理计划”开始</small></div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>问题 / 目标</th><th>干预</th><th>优先级</th><th>状态</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="plan in carePlans" :key="plan.care_plan_id">
                  <td><strong>{{ plan.nursing_problem }}</strong><small>{{ plan.goal }}</small></td>
                  <td>{{ plan.intervention }}</td>
                  <td>{{ plan.priority }}</td>
                  <td><span class="admin-status" :class="plan.status.toLowerCase()">{{ plan.status === 'ACTIVE' ? '执行中' : plan.status === 'COMPLETED' ? '已完成' : '已停止' }}</span></td>
                  <td class="admin-actions"><button v-if="plan.status === 'ACTIVE'" class="task-action" :disabled="Boolean(busy)" @click="completePlan(plan)">完成</button><button v-if="plan.status === 'ACTIVE'" class="task-action danger" :disabled="Boolean(busy)" @click="discontinueTarget = plan">终止</button></td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </section>

      <!-- 给药执行 -->
      <section v-else-if="activeTab === 'medications'" class="care-tab-content">
        <section class="admin-panel">
          <header><div><h2>给药执行</h2><p>关联医嘱执行任务，双人核验后执行。</p></div></header>
          <div v-if="medications.length === 0" class="empty-state"><span>药</span><p>暂无给药执行记录</p><small>点击“执行给药”开始录入</small></div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>药品</th><th>剂量</th><th>途径</th><th>执行 / 核验</th><th>时间</th></tr></thead>
              <tbody>
                <tr v-for="med in medications" :key="med.administration_id">
                  <td><strong><code>{{ med.drug_code }}</code></strong><small>执行 …{{ med.execution_task_id.slice(-8) }}</small></td>
                  <td>{{ med.dose_value }} {{ med.dose_unit }}</td>
                  <td>{{ med.route_code }}</td>
                  <td><small>执行 …{{ med.administered_by.slice(-8) }}</small><small>核验 …{{ med.verified_by.slice(-8) }}</small></td>
                  <td>{{ formatDate(med.administered_at) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </section>

      <!-- 交接班 -->
      <section v-else-if="activeTab === 'handovers'" class="care-tab-content">
        <section class="admin-panel">
          <header><div><h2>交接班</h2><p>科室级交接，含交班/接班班次与交接摘要。</p></div></header>
          <div v-if="handovers.length === 0" class="empty-state"><span>交</span><p>暂无交接班记录</p><small>点击“建立交接班”开始</small></div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>班次</th><th>接班医生</th><th>摘要</th><th>状态</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="handover in handovers" :key="handover.handover_id">
                  <td><strong>{{ handover.shift_from }} → {{ handover.shift_to }}</strong><small>…{{ handover.handover_id.slice(-8) }}</small></td>
                  <td><code>…{{ handover.incoming_user_id.slice(-8) }}</code></td>
                  <td>{{ handover.handover_summary }}</td>
                  <td><span class="admin-status" :class="handover.status.toLowerCase()">{{ handover.status === 'DRAFT' ? '草稿' : '已完成' }}</span></td>
                  <td class="admin-actions">
                    <button class="task-action" :disabled="Boolean(busy)" @click="selectHandover(handover.handover_id)">患者</button>
                    <button v-if="handover.status === 'DRAFT'" class="task-action" :disabled="Boolean(busy)" @click="completeHandover(handover)">完成</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <section v-if="selectedHandoverId" class="admin-panel">
            <header><div><h2>交接患者</h2><p>交接班 …{{ selectedHandoverId.slice(-8) }} 的患者清单。</p></div><div class="toolbar-actions"><button class="button primary" @click="patientDialogOpen = true">添加患者</button><button class="button secondary" @click="selectedHandoverId = ''">关闭</button></div></header>
            <div v-if="handoverPatients.length === 0" class="empty-state"><span>患</span><p>该交接暂无患者</p><small>点击“添加患者”录入</small></div>
            <div v-else class="admin-table-wrap">
              <table class="admin-table">
                <thead><tr><th>患者</th><th>摘要</th><th>风险</th></tr></thead>
                <tbody>
                  <tr v-for="patient in handoverPatients" :key="patient.shift_handover_patient_id">
                    <td><code>…{{ patient.patient_id.slice(-8) }}</code></td>
                    <td>{{ patient.summary }}</td>
                    <td>{{ patient.risk_flag ? '有风险' : '无' }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>
        </section>
      </section>

      <!-- 出院闭环 -->
      <section v-else-if="activeTab === 'discharge'" class="care-tab-content">
        <section class="admin-panel">
          <header><div><h2>出院闭环</h2><p>护理出院闭环关闭记录，关闭后不可撤销。</p></div></header>
          <div v-if="discharges.length === 0" class="empty-state"><span>出</span><p>暂无出院闭环记录</p><small>完成护理出院闭环后记录在此</small></div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>闭环</th><th>关闭人</th><th>关闭时间</th></tr></thead>
              <tbody>
                <tr v-for="closure in discharges" :key="closure.closure_id">
                  <td><code>…{{ closure.closure_id.slice(-8) }}</code></td>
                  <td><code>…{{ closure.closed_by.slice(-8) }}</code></td>
                  <td>{{ formatDate(closure.closed_at) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </section>

      <!-- 床旁记录 -->
      <section v-else class="care-tab-content">
        <section class="admin-panel">
          <header><div><h2>床旁记录</h2><p>床旁设备同步的体征、出入量与护理笔记。</p></div></header>
          <div v-if="bedside.length === 0" class="empty-state"><span>床</span><p>暂无床旁记录</p><small>点击“创建床旁记录”开始</small></div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>类型</th><th>设备</th><th>内容</th><th>记录时间</th><th>同步时间</th></tr></thead>
              <tbody>
                <tr v-for="note in bedside" :key="note.note_id">
                  <td>{{ note.note_type }}</td>
                  <td>{{ note.device_id }}</td>
                  <td>{{ note.content }}</td>
                  <td>{{ formatDate(note.recorded_at) }}</td>
                  <td>{{ formatDate(note.synced_at) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </section>
    </template>

    <AdminActionDialog v-model:open="createDialogOpen" :title="actionTitle" :description="activeTab === 'discharge' ? '确认护理出院准备全部完成后关闭闭环。' : '填写完整执行信息，提交后立即写入当前患者业务流程。'" eyebrow="诊疗执行 / 护理操作" size="large" :busy="Boolean(busy)">
      <form v-if="activeTab === 'vitals'" class="admin-form" @submit.prevent="submitVitals">
        <label><span>来源</span><select v-model="vitalForm.source"><option value="MANUAL">手工</option><option value="DEVICE">设备</option></select></label>
        <label><span>体温（℃）</span><input v-model.number="vitalForm.temperature" type="number" step="0.1" /></label>
        <label><span>脉搏（次/分）</span><input v-model.number="vitalForm.pulse" type="number" /></label>
        <label><span>呼吸（次/分）</span><input v-model.number="vitalForm.respiration" type="number" /></label>
        <label><span>收缩压</span><input v-model.number="vitalForm.systolicBp" type="number" /></label>
        <label><span>舒张压</span><input v-model.number="vitalForm.diastolicBp" type="number" /></label>
        <label><span>血氧（%）</span><input v-model.number="vitalForm.spo2" type="number" /></label>
        <button class="button primary full" :disabled="Boolean(busy)">{{ busy === 'vitals' ? '正在记录…' : '记录体征' }}</button>
      </form>
      <form v-else-if="activeTab === 'care-plans'" class="admin-form" @submit.prevent="submitCarePlan">
        <label><span>护理问题</span><input v-model="planForm.nursingProblem" maxlength="256" required /></label>
        <label><span>目标</span><input v-model="planForm.goal" maxlength="256" required /></label>
        <label><span>干预措施</span><textarea v-model="planForm.intervention" rows="3" required /></label>
        <label><span>优先级</span><select v-model="planForm.priority"><option value="HIGH">高</option><option value="MEDIUM">中</option><option value="LOW">低</option></select></label>
        <button class="button primary full" :disabled="Boolean(busy)">{{ busy === 'care-plan' ? '正在建立…' : '建立计划' }}</button>
      </form>
      <form v-else-if="activeTab === 'medications'" class="admin-form" @submit.prevent="submitMedication">
        <label><span>执行任务 ID</span><input v-model="medForm.executionTaskId" maxlength="36" required placeholder="UUID" /></label>
        <label><span>药品编码</span><input v-model="medForm.drugCode" maxlength="64" required /></label>
        <label><span>剂量</span><input v-model.number="medForm.doseValue" type="number" min="0.01" step="0.01" required /></label>
        <label><span>剂量单位</span><input v-model="medForm.doseUnit" maxlength="16" required /></label>
        <label><span>给药途径</span><input v-model="medForm.routeCode" maxlength="32" required /></label>
        <label><span>第二核验人 ID</span><input v-model="medForm.verifiedBy" maxlength="36" required placeholder="UUID" /></label>
        <label><span>核验备注</span><input v-model="medForm.verificationNote" maxlength="256" placeholder="可选" /></label>
        <button class="button primary full" :disabled="Boolean(busy)">{{ busy === 'medication' ? '正在执行…' : '双人核验并给药' }}</button>
      </form>
      <form v-else-if="activeTab === 'handovers'" class="admin-form" @submit.prevent="submitHandover">
        <label><span>交班班次</span><input v-model="handoverForm.shiftFrom" maxlength="32" required placeholder="例：白班" /></label>
        <label><span>接班班次</span><input v-model="handoverForm.shiftTo" maxlength="32" required placeholder="例：夜班" /></label>
        <label><span>接班医生 ID</span><input v-model="handoverForm.incomingUserId" maxlength="36" required placeholder="UUID" /></label>
        <label><span>交接摘要</span><textarea v-model="handoverForm.summary" rows="3" required /></label>
        <button class="button primary full" :disabled="Boolean(busy)">{{ busy === 'handover' ? '正在建立…' : '建立交接班' }}</button>
      </form>
      <form v-else-if="activeTab === 'discharge'" class="admin-form" @submit.prevent="submitDischarge">
        <div class="admin-confirm-impact"><strong>关闭后不可撤销</strong><p>系统将保留关闭人、关闭时间和患者上下文。</p></div>
        <button class="button primary full" :disabled="Boolean(busy)">{{ busy === 'discharge' ? '正在关闭…' : '确认完成护理出院闭环' }}</button>
      </form>
      <form v-else class="admin-form" @submit.prevent="submitBedside">
        <label><span>类型</span><select v-model="bedsideForm.noteType"><option value="VITAL_SIGNS">生命体征</option><option value="INTAKE_OUTPUT">出入量</option><option value="NURSING_NOTE">护理笔记</option></select></label>
        <label><span>设备 ID</span><input v-model="bedsideForm.deviceId" maxlength="64" required placeholder="例：BEDSIDE-01" /></label>
        <label><span>内容</span><textarea v-model="bedsideForm.content" rows="3" required /></label>
        <button class="button primary full" :disabled="Boolean(busy)">{{ busy === 'bedside' ? '正在同步…' : '创建床旁记录' }}</button>
      </form>
    </AdminActionDialog>

    <AdminActionDialog v-model:open="patientDialogOpen" title="添加交接患者" description="患者摘要与风险标志会进入科室交接班记录。" eyebrow="诊疗执行 / 交接班" :busy="busy === 'handover-patient'">
      <form class="admin-form" @submit.prevent="submitHandoverPatient">
        <label><span>患者 ID</span><input v-model="handoverPatientForm.patientId" maxlength="36" required placeholder="UUID" /></label>
        <label><span>摘要</span><input v-model="handoverPatientForm.summary" maxlength="256" required /></label>
        <label class="checkbox"><input v-model="handoverPatientForm.riskFlag" type="checkbox" />风险标志</label>
        <button class="button primary full" :disabled="Boolean(busy)">{{ busy === 'handover-patient' ? '正在添加…' : '添加患者' }}</button>
      </form>
    </AdminActionDialog>

    <AdminActionDialog :open="Boolean(discontinueTarget)" title="终止护理计划" description="计划不会物理删除，将转为已终止并保留原因和审计证据。" eyebrow="诊疗执行 / 高风险操作" tone="danger" :busy="busy.startsWith('plan:')" @update:open="discontinueTarget = $event ? discontinueTarget : null">
      <form class="admin-form" @submit.prevent="discontinuePlan">
        <label><span>终止原因（必填）</span><textarea v-model="discontinueReason" rows="3" required placeholder="说明计划停止原因" /></label>
        <button class="button danger full" :disabled="!discontinueReason.trim() || Boolean(busy)">{{ busy.startsWith('plan:') ? '正在终止…' : '确认终止计划' }}</button>
      </form>
    </AdminActionDialog>
  </section>
</template>

<style scoped>
.care-overview-heading{align-items:flex-end}.care-overview-heading p:last-child{margin:5px 0 0;color:var(--muted)}
.care-overview-hero h2{color:#fff}.care-module-grid{margin-bottom:22px}.care-module-grid .hub-module{text-decoration:none}
.care-nursing-heading{display:flex;align-items:flex-end;justify-content:space-between;gap:20px;margin:0 0 12px;padding-top:18px;border-top:1px solid var(--line)}
.care-nursing-heading h2,.care-nursing-heading p{margin-bottom:0}.care-nursing-heading>p{max-width:620px;color:var(--muted);font-size:12px;text-align:right;line-height:1.6}
.care-tabs{align-items:center;flex-wrap:wrap;margin-bottom:14px;gap:8px}.care-create-action{margin-left:auto}.care-tab-content>.admin-panel{width:100%}
@media(max-width:760px){.care-overview-heading,.care-nursing-heading{align-items:stretch;flex-direction:column}.care-overview-heading .toolbar-actions{flex-wrap:wrap}.care-nursing-heading>p{text-align:left}.care-overview-hero{grid-template-columns:1fr}.care-overview-hero .hub-score{padding-top:14px;border-top:1px solid #ffffff30;border-left:0}}
</style>
