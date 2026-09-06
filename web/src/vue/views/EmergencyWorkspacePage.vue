<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed } from 'vue';
import { clinicalContext, setEmergencyClinicalContext } from '../../clinical-api';
import type { WaitingQueueEntryWire } from '../../generated/contracts';
import {
  issueEmergencyFacilityLease,
  issueEmergencyLease,
  listEmergencyNursingNotes,
  listEmergencyObservations,
  listEmergencyPreadmissions,
  listEmergencyResuscitations,
  listEmergencyTriageAssessments,
  listEncounterDomainSwitches,
  listShiftHandovers,
  listWaitingQueue,
} from '../../api/emergency';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import AgentInlineReview from '../components/AgentInlineReview.vue';
import { toClinicalIssue } from '../clinical-error';

const facilityLease = useQuery({
  queryKey: ['emergency', 'facility-lease'],
  queryFn: () => issueEmergencyFacilityLease('EMERGENCY_BOARD'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const patientLease = useQuery({
  queryKey: ['emergency', 'patient-lease'],
  queryFn: () => issueEmergencyLease('EMERGENCY_WORKFLOW'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});

const preadmissions = useQuery({
  queryKey: ['emergency', 'preadmissions'],
  queryFn: () => listEmergencyPreadmissions(facilityLease.data.value!),
  enabled: () => Boolean(facilityLease.data.value), retry: false,
});
const waitingQueue = useQuery({
  queryKey: ['emergency', 'waiting-queue'],
  queryFn: () => listWaitingQueue(facilityLease.data.value!),
  enabled: () => Boolean(facilityLease.data.value), retry: false,
});
const triages = useQuery({
  queryKey: ['emergency', 'triages'],
  queryFn: () => listEmergencyTriageAssessments(patientLease.data.value!),
  enabled: () => Boolean(patientLease.data.value), retry: false,
});
const observations = useQuery({
  queryKey: ['emergency', 'observations'],
  queryFn: () => listEmergencyObservations(patientLease.data.value!),
  enabled: () => Boolean(patientLease.data.value), retry: false,
});
const resuscitations = useQuery({
  queryKey: ['emergency', 'resuscitations'],
  queryFn: () => listEmergencyResuscitations(patientLease.data.value!),
  enabled: () => Boolean(patientLease.data.value), retry: false,
});
const nursingNotes = useQuery({
  queryKey: ['emergency', 'nursing-notes'],
  queryFn: () => listEmergencyNursingNotes(patientLease.data.value!),
  enabled: () => Boolean(patientLease.data.value), retry: false,
});
const domainSwitches = useQuery({
  queryKey: ['emergency', 'domain-switches'],
  queryFn: () => listEncounterDomainSwitches(patientLease.data.value!),
  enabled: () => Boolean(patientLease.data.value), retry: false,
});
const handovers = useQuery({
  queryKey: ['emergency', 'workspace', 'handovers'],
  queryFn: () => listShiftHandovers(facilityLease.data.value!, clinicalContext.inpatientWardId),
  enabled: () => Boolean(facilityLease.data.value), retry: false,
});

const queries = [facilityLease, patientLease, preadmissions, waitingQueue, triages, observations, resuscitations, nursingNotes, domainSwitches, handovers];
const pending = computed(() => queries.some((q) => q.isPending.value));
const issue = computed(() => {
  const failed = queries.find((q) => q.error.value);
  return failed ? toClinicalIssue(failed.error.value) : null;
});

const unregistered = computed(() => (preadmissions.data.value ?? []).filter((p) => p.status === 'UNREGISTERED'));
const queue = computed(() => waitingQueue.data.value ?? []);
const triageList = computed(() => (triages.data.value ?? []).filter((item) => !item.voided_at));
const observationList = computed(() => (observations.data.value ?? []).filter((item) => !item.voided_at));
const resuscitationList = computed(() => (resuscitations.data.value ?? []).filter((item) => !item.voided_at));
const noteList = computed(() => (nursingNotes.data.value ?? []).filter((item) => !item.voided_at));
const switchList = computed(() => domainSwitches.data.value ?? []);
const handoverList = computed(() => (handovers.data.value ?? []).filter((item) => !item.voided_at));
const highRiskNotes = computed(() => noteList.value.filter((n) => n.risk_flag).length);
const waitingList = computed(() => queue.value.filter((entry) => entry.status === 'WAITING'));
const activeResuscitations = computed(() => resuscitationList.value.filter((item) => item.status === 'IN_PROGRESS'));
const pendingObservations = computed(() => observationList.value.filter((item) => item.disposition === 'PENDING'));
const draftHandovers = computed(() => handoverList.value.filter((item) => item.status === 'DRAFT'));
const currentQueueEntry = computed(() => queue.value.find((entry) => entry.patient_id === clinicalContext.emergencyPatientId && entry.encounter_id === clinicalContext.emergencyEncounterId) ?? null);
const currentTriage = computed(() => triageList.value[0] ?? null);
const currentNursingRisk = computed(() => noteList.value.find((item) => item.risk_flag) ?? null);

const agentPatientId = computed(() => clinicalContext.emergencyPatientId);
const agentEncounterId = computed(() => clinicalContext.emergencyEncounterId);
const emergencySummaryObjective = computed(() => '汇总当前急诊就诊的分诊与诊疗进展，输出摘要候选，仅供医生审阅。');

function shortId(value: string) { return `…${value.slice(-8)}`; }
function formatTime(value: string | null | undefined) {
  return value ? new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value)) : '—';
}
function ageFromBirthDate(value: string | null | undefined) {
  if (!value) return '年龄未知';
  const birth = new Date(value);
  const now = new Date();
  let age = now.getFullYear() - birth.getFullYear();
  if (now.getMonth() < birth.getMonth() || (now.getMonth() === birth.getMonth() && now.getDate() < birth.getDate())) age -= 1;
  return `${Math.max(age, 0)}岁`;
}
function sexLabel(value: string | null | undefined) {
  return ({ MALE: '男', FEMALE: '女', M: '男', F: '女', UNKNOWN: '未知' } as Record<string, string>)[value ?? ''] ?? (value || '未知');
}
function queueStatusLabel(value: string) {
  return ({ WAITING: '候诊', CALLED: '已叫号', IN_CONSULTATION: '诊疗中', COMPLETED: '已完成', SKIPPED: '过号' } as Record<string, string>)[value] ?? value;
}
function queueTone(value: string) {
  return value === 'IN_CONSULTATION' ? 'green' : value === 'CALLED' ? 'amber' : value === 'SKIPPED' ? 'red' : 'blue';
}

function triageLabel(level: string | null | undefined) {
  return ({ LEVEL_1: 'Ⅰ级', LEVEL_2: 'Ⅱ级', LEVEL_3: 'Ⅲ级', LEVEL_4: 'Ⅳ级' } as Record<string, string>)[level ?? ''] ?? (level ?? '—');
}
function dispositionLabel(value: string | null | undefined) {
  return ({ DISCHARGED: '离院', ADMITTED: '入院', TRANSFERRED: '转科' } as Record<string, string>)[value ?? ''] ?? (value ?? '—');
}
function outcomeLabel(value: string | null | undefined) {
  return ({ ROSC: '恢复自主循环', DEATH: '死亡', TRANSFERRED: '转科' } as Record<string, string>)[value ?? ''] ?? (value ?? '—');
}

function switchEmergencyPatient(entry: WaitingQueueEntryWire) {
  if (entry.patient_id === clinicalContext.emergencyPatientId && entry.encounter_id === clinicalContext.emergencyEncounterId) return;
  setEmergencyClinicalContext(entry.patient_id, entry.encounter_id);
  window.location.reload();
}

async function reload() {
  await Promise.all([preadmissions.refetch(), waitingQueue.refetch(), triages.refetch(), observations.refetch(), resuscitations.refetch(), nursingNotes.refetch(), domainSwitches.refetch(), handovers.refetch()]);
}
</script>

<template>
  <section data-page-root class="content vue-native-page emergency-workspace-page">
    <div class="page-head">
      <div class="page-title">
        <h1>急诊工作台</h1>
        <p>抢救区 · 白班 · 急诊域实时状态 · 各环节向下钻取到独立工作页</p>
      </div>
      <div class="head-actions">
        <RouterLink class="btn" to="/er-handoff">急诊交接</RouterLink>
        <RouterLink class="btn" to="/er-observation">留观清单</RouterLink>
        <RouterLink class="btn danger" to="/er-record">启动抢救记录</RouterLink>
      </div>
    </div>

    <ClinicalPageState v-if="pending" kind="loading" message="正在汇总急诊域实时状态" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reload" />

    <template v-else>
      <section class="patient-strip emergency-context emergency-patient-strip" aria-label="当前急诊患者上下文">
        <div><div class="patient-name">{{ currentQueueEntry?.patient_display_name ?? '当前急诊患者' }}</div><div class="meta">{{ sexLabel(currentQueueEntry?.patient_sex_code) }} · {{ ageFromBirthDate(currentQueueEntry?.patient_birth_date) }} · 患者 {{ shortId(clinicalContext.emergencyPatientId) }}</div></div>
        <div class="divider"></div>
        <div><b>急诊序号 {{ currentQueueEntry ? `#${currentQueueEntry.sequence_no}` : '—' }} · {{ queueStatusLabel(currentQueueEntry?.status ?? 'WAITING') }}</b><div class="meta">{{ formatTime(currentTriage?.triaged_at) }} 分诊 · 当前就诊 {{ shortId(clinicalContext.emergencyEncounterId) }}</div></div>
        <div class="divider"></div>
        <div class="emergency-chief-complaint"><b>{{ currentTriage?.chief_complaint ?? '待补充主诉' }}</b><div class="meta">急诊医学科 · 腕带状态请以护理执行页的真实核验记录为准</div></div>
        <span class="risk red">{{ triageLabel(currentTriage?.triage_level) }} · {{ currentTriage?.immediate_action_required ? '立即处置' : '持续评估' }}</span>
        <span v-if="highRiskNotes" class="risk red">高危护理 {{ highRiskNotes }}</span>
        <RouterLink class="btn sm emergency-switch-patient" to="/er-triage">切换急诊患者</RouterLink>
      </section>

      <div class="metric-grid emergency-metrics">
        <div class="metric"><div class="name">待预检分诊</div><div class="value">{{ waitingList.length }}</div><div class="trend danger-text">院区候诊共 {{ queue.length }} 人</div></div>
        <div class="metric"><div class="name">抢救 / 需立即处置</div><div class="value danger-text">{{ activeResuscitations.length }} / {{ triageList.filter((item) => item.immediate_action_required).length }}</div><div class="trend">当前患者实时事实</div></div>
        <div class="metric"><div class="name">急会诊临期</div><div class="value">{{ draftHandovers.length }}</div><div class="trend warning-text">待完成交接 {{ draftHandovers.length }} 项</div></div>
        <div class="metric"><div class="name">留观待去向</div><div class="value">{{ pendingObservations.length }}</div><div class="trend">未登记预入院 {{ unregistered.length }} 人</div></div>
      </div>

      <AgentInlineReview agent-code="ENCOUNTER_SUMMARIZER" stage-code="TRIAGE" :objective="emergencySummaryObjective" :patient-id="agentPatientId" :encounter-id="agentEncounterId" target-type="ENCOUNTER" :target-id="agentEncounterId" title="AI 急诊就诊摘要候选" source-route="emergency" />

      <div class="grid emergency-grid">
        <aside class="card scroll-card emergency-queue-rail" aria-label="急诊队列">
          <section class="emergency-queue-section" aria-labelledby="emergency-waiting-title"><div class="card-head" id="emergency-waiting-title">急诊队列 <span class="sub">按状态 / 到达顺序</span></div>
          <div v-if="!queue.length" class="clinical-empty-state compact"><strong>今日暂无候诊患者</strong><span>急诊签到后，患者会按分诊级别和到达顺序进入此队列。</span><RouterLink to="/appointment-registration">进入签到与挂号</RouterLink></div>
          <div v-else class="emergency-queue-list">
            <div v-for="entry in queue" :key="entry.waiting_queue_entry_id" class="queue-item" :class="{ active: entry.patient_id === currentQueueEntry?.patient_id }">
              <div class="queue-title"><span class="dot" :class="queueTone(entry.status)"></span>{{ entry.patient_display_name }} · #{{ entry.sequence_no }}<span class="status" :class="queueTone(entry.status)">{{ queueStatusLabel(entry.status) }}</span></div>
              <div class="queue-meta">{{ sexLabel(entry.patient_sex_code) }} · {{ ageFromBirthDate(entry.patient_birth_date) }}<span>叫号 {{ formatTime(entry.called_at) }}</span></div>
              <button class="btn sm" type="button" :disabled="entry.patient_id === currentQueueEntry?.patient_id" @click="switchEmergencyPatient(entry)">{{ entry.patient_id === currentQueueEntry?.patient_id ? '当前患者' : '切换患者' }}</button>
            </div>
          </div>
          <div class="notice info emergency-queue-notice"><div class="notice-title">预检分诊可追溯</div>保留分诊级别、判定依据和全部改级事实；当前另有 {{ unregistered.length }} 名先救治后补登患者。</div></section>
        </aside>

        <section class="card scroll-card emergency-timeline-card">
          <div class="card-head">时间关键诊疗轴 <span class="status red">{{ activeResuscitations.length ? '抢救计时中' : '当前患者' }}</span></div>
          <div class="card-body">
            <div class="event-row">
              <div class="event-time">{{ formatTime(currentTriage?.triaged_at) }}</div><span class="dot red"></span>
              <div><b>分诊完成</b><p>{{ triageLabel(currentTriage?.triage_level) }} · {{ currentTriage?.chief_complaint ?? '待补主诉' }}</p></div>
              <RouterLink class="btn sm" to="/er-triage">查看来源</RouterLink>
            </div>
            <div class="event-row">
              <div class="event-time">{{ formatTime(resuscitationList[0]?.started_at) }}</div><span class="dot red"></span>
              <div><b>抢救记录</b><p>{{ resuscitationList.length }} 条 · 结局 {{ outcomeLabel(resuscitationList[0]?.outcome) }}</p></div>
              <RouterLink class="btn sm" to="/er-record">查看来源</RouterLink>
            </div>
            <div class="event-row">
              <div class="event-time">{{ formatTime(noteList[0]?.recorded_at) }}</div><span class="dot amber"></span>
              <div><b>急诊护理</b><p>{{ noteList[0]?.intervention ?? `${noteList.length} 条护理事实` }}</p></div>
              <RouterLink class="btn sm" to="/er-nursing">查看来源</RouterLink>
            </div>
            <div class="event-row">
              <div class="event-time">{{ formatTime(observationList[0]?.observation_started_at) }}</div><span class="dot blue"></span>
              <div><b>留观去向</b><p>{{ observationList.length }} 条 · {{ dispositionLabel(observationList[0]?.disposition) }}</p></div>
              <RouterLink class="btn sm" to="/er-observation">查看来源</RouterLink>
            </div>
            <div class="event-row">
              <div class="event-time">{{ formatTime(switchList[0]?.switched_at) }}</div><span class="dot green"></span>
              <div><b>急会诊与交接</b><p>{{ handoverList.length }} 条交接 · {{ switchList.length }} 条域切换</p></div>
              <RouterLink class="btn sm" to="/er-handoff">查看来源</RouterLink>
            </div>
            <div class="section-title emergency-summary-title">急诊病历与处置摘要</div>
            <div class="clinical-summary emergency-clinical-summary">
              <div><span>主诉 / 分诊</span><b>{{ currentTriage?.chief_complaint ?? '待补充' }} · {{ triageLabel(currentTriage?.triage_level) }}</b></div>
              <div><span>护理风险</span><b :class="{ 'danger-text': highRiskNotes }">{{ currentNursingRisk?.assessment ?? '当前无高危护理记录' }}</b></div>
              <div><span>抢救状态</span><b>{{ activeResuscitations.length ? '抢救进行中' : outcomeLabel(resuscitationList[0]?.outcome) }}</b></div>
              <div><span>待确认去向</span><b>{{ pendingObservations.length }} 项 · {{ dispositionLabel(observationList[0]?.disposition) }}</b></div>
            </div>
            <div class="notice hard"><div class="notice-title">时间事实不可回写覆盖</div>事件发生时间与系统记录时间分别留痕；补录必须填写原因并保留审计链。</div>
          </div>
          <div class="footer-actions emergency-footer-actions"><span class="save-state">● 急诊实时数据已同步</span><RouterLink class="btn" to="/er-handoff">急会诊</RouterLink><RouterLink class="btn" to="/er-observation">转住院</RouterLink><RouterLink class="btn danger" to="/er-record">记录抢救事件</RouterLink></div>
        </section>

        <aside class="card scroll-card emergency-right-rail" aria-label="抢救护理与去向侧栏">
          <div class="card-head">抢救、护理与去向</div>
          <div class="card-body">
            <div class="notice hard"><div class="notice-title">阻断 · {{ currentNursingRisk ? '高危护理评估待确认' : pendingObservations.length ? '留观去向待确认' : '急诊闭环持续监测' }}</div>{{ currentNursingRisk?.assessment ?? (pendingObservations.length ? `${pendingObservations.length} 项留观尚未形成最终去向。` : '当前无新增阻断项，仍需持续评估。') }}</div>
            <div class="section-title emergency-team-title">团队任务</div>
            <RouterLink class="queue-item emergency-task-row" to="/er-handoff"><div class="queue-title">急会诊与交接<span class="status" :class="draftHandovers.length ? 'amber' : 'green'">{{ draftHandovers.length ? `${draftHandovers.length} 项待完成` : '已确认' }}</span></div></RouterLink>
            <RouterLink class="queue-item emergency-task-row" to="/er-record"><div class="queue-title">抢救记录<span class="status" :class="activeResuscitations.length ? 'blue' : 'green'">{{ activeResuscitations.length ? '执行中' : '已记录' }}</span></div></RouterLink>
            <RouterLink class="queue-item emergency-task-row" to="/er-nursing"><div class="queue-title">危重护理处置<span class="status" :class="highRiskNotes ? 'red' : 'green'">{{ highRiskNotes ? `${highRiskNotes} 项高危` : '已确认' }}</span></div></RouterLink>
            <RouterLink class="queue-item emergency-task-row" to="/er-observation"><div class="queue-title">留观去向<span class="status" :class="pendingObservations.length ? 'amber' : 'green'">{{ pendingObservations.length ? `${pendingObservations.length} 项待定` : '已闭环' }}</span></div></RouterLink>
            <div class="section-title emergency-disposition-title">去向闭环</div>
            <div class="stepper compact emergency-disposition-stepper">
              <div class="step" :class="resuscitationList.length ? 'done' : 'active'"><i></i>急诊抢救</div>
              <div class="step" :class="handoverList.some((item) => item.status === 'COMPLETED') ? 'done' : draftHandovers.length ? 'active' : ''"><i></i>急会诊</div>
              <div class="step" :class="observationList.some((item) => item.disposition !== 'PENDING') ? 'done' : pendingObservations.length ? 'active' : ''"><i></i>去向确认</div>
              <div class="step" :class="switchList.length ? 'done' : ''"><i></i>接收闭环</div>
            </div>
            <div class="notice info"><div class="notice-title">交接门禁</div>患者、管路、用药、未回结果、未完任务、接收人和交接时间必须核验后才可结束急诊就诊。</div>
          </div>
        </aside>
      </div>
    </template>
  </section>
</template>
