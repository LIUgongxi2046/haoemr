<script setup lang="ts">
// 临床业务门户 —— 三域卡片门户。DOM 结构与类名严格对齐高保真原型
// prototype/app/app.js `clinical()`（见 DEVELOPMENT_PRINCIPLES.md §六：复用原型类名，勿另写一套）。
import { useQuery } from '@tanstack/vue-query';
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import { clinicalContext, issueInpatientLease, issueWardLease, loadInpatientOverview, loadInpatientWorklist } from '../../clinical-api';
import { issueEmergencyFacilityLease, issueEmergencyLease, listEmergencyCoordinationCases, listEmergencyIdentityVerifications, listEmergencyObservations, listEmergencyResuscitations, listEmergencyTriageAssessments, listWaitingQueue } from '../../api/emergency';
import { loadOutpatientWorkspaceSnapshot } from '../../api/outpatient-workspace';
import { toClinicalIssue } from '../clinical-error';
import { aiMedicalAssistantRoster, aiMedicalAssistantRosterSummary } from '../ai-medical-assistant-roster';

const router = useRouter();
const emergencyFacilityLease = useQuery({ queryKey: ['clinical-portal', 'emergency', 'facility-lease'], queryFn: () => issueEmergencyFacilityLease('CLINICAL_PORTAL_EMERGENCY'), retry: false, staleTime: 5 * 60_000, gcTime: 0 });
const emergencyPatientLease = useQuery({ queryKey: ['clinical-portal', 'emergency', 'patient-lease'], queryFn: () => issueEmergencyLease('CLINICAL_PORTAL_EMERGENCY'), retry: false, staleTime: 5 * 60_000, gcTime: 0 });
const emergencyQueue = useQuery({ queryKey: ['clinical-portal', 'emergency', 'queue'], queryFn: () => listWaitingQueue(emergencyFacilityLease.data.value!), enabled: () => Boolean(emergencyFacilityLease.data.value), retry: false });
const emergencyTriages = useQuery({ queryKey: ['clinical-portal', 'emergency', 'triage'], queryFn: () => listEmergencyTriageAssessments(emergencyPatientLease.data.value!), enabled: () => Boolean(emergencyPatientLease.data.value), retry: false });
const emergencyResuscitations = useQuery({ queryKey: ['clinical-portal', 'emergency', 'resuscitation'], queryFn: () => listEmergencyResuscitations(emergencyPatientLease.data.value!), enabled: () => Boolean(emergencyPatientLease.data.value), retry: false });
const emergencyObservations = useQuery({ queryKey: ['clinical-portal', 'emergency', 'observation'], queryFn: () => listEmergencyObservations(emergencyPatientLease.data.value!), enabled: () => Boolean(emergencyPatientLease.data.value), retry: false });
const emergencyCoordination = useQuery({ queryKey: ['clinical-portal', 'emergency', 'coordination'], queryFn: () => listEmergencyCoordinationCases(emergencyPatientLease.data.value!), enabled: () => Boolean(emergencyPatientLease.data.value), retry: false });
const emergencyIdentity = useQuery({ queryKey: ['clinical-portal', 'emergency', 'identity'], queryFn: () => listEmergencyIdentityVerifications(emergencyPatientLease.data.value!), enabled: () => Boolean(emergencyPatientLease.data.value), retry: false });
const outpatientFacilityLease = useQuery({ queryKey: ['clinical-portal', 'outpatient', 'facility-lease'], queryFn: () => issueEmergencyFacilityLease('CLINICAL_PORTAL_OUTPATIENT'), retry: false, staleTime: 5 * 60_000, gcTime: 0 });
const outpatientQueue = useQuery({ queryKey: ['clinical-portal', 'outpatient', 'queue'], queryFn: () => listWaitingQueue(outpatientFacilityLease.data.value!), enabled: () => Boolean(outpatientFacilityLease.data.value), retry: false });
const outpatientSnapshot = useQuery({
  queryKey: ['clinical-portal', 'outpatient', 'snapshot', clinicalContext.patientId, clinicalContext.encounterId],
  queryFn: () => loadOutpatientWorkspaceSnapshot(clinicalContext.patientId, clinicalContext.encounterId),
  retry: false, staleTime: 0, gcTime: 0,
});
const inpatientDashboard = useQuery({
  queryKey: ['clinical-portal', 'inpatient', 'dashboard'],
  queryFn: async () => {
    const [encounterLease, wardLease] = await Promise.all([issueInpatientLease(), issueWardLease()]);
    const [overview, worklist] = await Promise.all([loadInpatientOverview(encounterLease), loadInpatientWorklist(wardLease)]);
    return { overview, worklist };
  },
  retry: false, staleTime: 0, gcTime: 0,
});

interface DomainModule { title: string; desc: string; to: string }
interface DomainCard {
  type: 'outpatient' | 'emergency' | 'inpatient';
  eyebrow: string;
  symbol: string;
  title: string;
  desc: string;
  to: string;
  metrics: [string, string][];
  alerts: [string, string, string][];
  flow: string[];
  modules: DomainModule[];
  footer: [string, string][];
}

const baseDomains: DomainCard[] = [
  {
    type: 'outpatient',
    eyebrow: 'OUTPATIENT CARE',
    symbol: '门',
    title: '门诊诊疗',
    desc: '把预约、接诊、病历、医嘱、结果和随访串成一次门诊闭环，让每次诊疗都有清晰的下一步。',
    to: 'outpatient',
    metrics: [],
    alerts: [],
    flow: ['预约挂号', '到诊分诊', '问诊病历', '诊断医嘱', '结果处置', '签署终诊'],
    modules: [
      { title: '预约挂号与队列', desc: '号源、到诊、分诊、叫号、过号', to: 'appointment-registration' },
      { title: '门诊病历与质控', desc: '主诉、病史、查体、诊断、签署', to: 'record' },
      { title: '医嘱处方与执行', desc: '药品、检验、检查、治疗、审方', to: 'opd-orders' },
      { title: '结果与危急值', desc: '趋势、报告更正、确认与处置', to: 'opd-results' },
      { title: '会诊转诊', desc: '申请、受理、意见、时限', to: 'opd-consult' },
      { title: '随访与终诊', desc: '教育、复诊、随访、结束就诊', to: 'opd-followup' },
    ],
    footer: [],
  },
  {
    type: 'emergency',
    eyebrow: 'EMERGENCY CARE',
    symbol: '急',
    title: '急诊诊疗',
    desc: '围绕时间关键事件组织分诊、抢救、留观与交接，在高压场景下让责任、时限和去向一目了然。',
    to: 'emergency',
    metrics: [],
    alerts: [],
    flow: ['院前 / 到院', '预检分诊', '抢救 / 诊室', '急诊医嘱', '会诊交接', '去向闭环'],
    modules: [
      { title: '预检分诊与分区', desc: '级别、依据、立即处置、动态复评', to: 'er-triage' },
      { title: '急诊病历与抢救', desc: '时间轴、抢救记录、医嘱结果', to: 'er-record' },
      { title: '急诊护理与输液', desc: '生命体征、执行、异常', to: 'er-nursing' },
      { title: '急会诊与交接班', desc: '时限、责任、未完任务', to: 'er-handoff' },
      { title: '留观与转住院', desc: '观察记录、去向、接收', to: 'er-observation' },
      { title: '时间关键质控', desc: '双时间、超时、补录原因', to: 'er-record' },
    ],
    footer: [],
  },
  {
    type: 'inpatient',
    eyebrow: 'INPATIENT CARE',
    symbol: '住',
    title: '住院诊疗',
    desc: '覆盖从入院接管到出院归档的连续住院管理，把病程、医嘱、查房、协同与病案闭环放在同一工作站。',
    to: 'inpatient',
    metrics: [],
    alerts: [],
    flow: ['入院接管', '入院 / 首程', '医嘱执行', '病程查房', '会诊手术', '出院归档'],
    modules: [
      { title: '在院患者与任务', desc: '床位、医疗组、风险、责任与时限', to: 'inpatient' },
      { title: '住院总览', desc: '诊断、医嘱、体征、结果、护理摘要', to: 'inpatient-overview' },
      { title: '病历文书与查房', desc: '入院、首程、病程、三级查房、审签', to: 'inpatient-course' },
      { title: '医嘱用药与执行', desc: '长期/临时医嘱、给药、停止与重整', to: 'ip-orders' },
      { title: '会诊手术与协同', desc: '会诊、手术、转科、交班、事件文书', to: 'ip-consult' },
      { title: '出院病案闭环', desc: '出院记录、首页、质控、整改、归档', to: 'inpatient-discharge' },
    ],
    footer: [],
  },
];

function metricRows(labels: string[], value: string): [string, string][] {
  return labels.map((label) => [label, value] as [string, string]);
}

const currentTriage = computed(() => (emergencyTriages.data.value ?? []).find((item) => item.status === 'ACTIVE' && !item.voided_at));
const activeResuscitationCount = computed(() => (emergencyResuscitations.data.value ?? []).filter((item) => item.status === 'IN_PROGRESS' && !item.voided_at).length);
const activeObservationCount = computed(() => (emergencyObservations.data.value ?? []).filter((item) => item.status === 'OBSERVING' && !item.voided_at).length);
const waitingCount = computed(() => (emergencyQueue.data.value ?? []).filter((item) => item.status === 'WAITING').length);
const openCoordination = computed(() => (emergencyCoordination.data.value ?? []).filter((item) => !['COMPLETED', 'VOIDED'].includes(item.status)));
const overdueCoordinationCount = computed(() => openCoordination.value.filter((item) => new Date(item.due_at).getTime() < Date.now()).length);
const recentIdentityMatched = computed(() => {
  const latest = emergencyIdentity.data.value?.[0];
  return Boolean(latest?.outcome === 'MATCHED' && Date.now() - new Date(latest.verified_at).getTime() <= 30 * 60_000);
});
const emergencyDataUnavailable = computed(() => [emergencyQueue, emergencyTriages, emergencyResuscitations, emergencyObservations, emergencyCoordination, emergencyIdentity].some((query) => Boolean(query.error.value)));
const outpatientDataUnavailable = computed(() => [outpatientFacilityLease, outpatientQueue, outpatientSnapshot].some((query) => Boolean(query.error.value)));
const outpatientLoading = computed(() => [outpatientFacilityLease, outpatientQueue, outpatientSnapshot].some((query) => query.isPending.value));
const inpatientDataUnavailable = computed(() => Boolean(inpatientDashboard.error.value));
const inpatientIssue = computed(() => inpatientDashboard.error.value ? toClinicalIssue(inpatientDashboard.error.value) : null);
const inpatientLoading = computed(() => inpatientDashboard.isPending.value);
const outpatientQueueEntries = computed(() => outpatientQueue.data.value ?? []);
const outpatientDocuments = computed(() => outpatientSnapshot.data.value?.documents ?? []);
const outpatientResults = computed(() => outpatientSnapshot.data.value?.results ?? []);
const outpatientCriticalValues = computed(() => outpatientResults.value.flatMap((result) => result.critical_values).filter((critical) => critical.state !== 'DISPOSED'));
const outpatientMetrics = computed<[string, string][]>(() => {
  const labels = ['当日队列', '已到诊', '候诊', '诊疗中', '当前结果报告', '当前待签病历'];
  if (outpatientLoading.value) return metricRows(labels, '…');
  if (outpatientDataUnavailable.value) return metricRows(labels, '—');
  const queue = outpatientQueueEntries.value;
  return [
    ['当日队列', String(queue.length)],
    ['已到诊', String(queue.filter((entry) => ['CALLED', 'IN_CONSULTATION', 'COMPLETED'].includes(entry.status)).length)],
    ['候诊', String(queue.filter((entry) => entry.status === 'WAITING').length)],
    ['诊疗中', String(queue.filter((entry) => entry.status === 'IN_CONSULTATION').length)],
    ['当前结果报告', String(outpatientResults.value.length)],
    ['当前待签病历', String(outpatientDocuments.value.filter((document) => !['SIGNED', 'VOID'].includes(document.status)).length)],
  ];
});
const outpatientAlerts = computed<[string, string, string][]>(() => {
  if (outpatientLoading.value) return [['门诊数据', '加载中', 'amber']];
  if (outpatientDataUnavailable.value) return [['门诊数据', '不可用', 'red']];
  const waiting = outpatientQueueEntries.value.filter((entry) => entry.status === 'WAITING').length;
  return [
    ['危急值', String(outpatientCriticalValues.value.length), outpatientCriticalValues.value.length ? 'red' : 'green'],
    ['候诊', String(waiting), waiting ? 'amber' : 'green'],
    ['待签病历', String(outpatientDocuments.value.filter((document) => !['SIGNED', 'VOID'].includes(document.status)).length), outpatientDocuments.value.some((document) => !['SIGNED', 'VOID'].includes(document.status)) ? 'amber' : 'green'],
  ];
});
const inpatientMetrics = computed<[string, string][]>(() => {
  const labels = ['病区在院', '当前待完成文书', '当前逾期文书', '病区逾期文书', '今日新入院', '当前病人待完成任务'];
  if (inpatientLoading.value) return metricRows(labels, '…');
  if (inpatientDataUnavailable.value) return metricRows(labels, '—');
  const dashboard = inpatientDashboard.data.value!;
  const currentPending = dashboard.overview.document_tasks.filter((task) => task.task_state !== 'COMPLETED').length;
  const currentOverdue = dashboard.overview.document_tasks.filter((task) => task.task_state === 'OVERDUE').length;
  return [
    ['病区在院', String(dashboard.worklist.length)],
    ['当前待完成文书', String(currentPending)],
    ['当前逾期文书', String(currentOverdue)],
    ['病区逾期文书', String(dashboard.worklist.reduce((sum, item) => sum + item.overdue_task_count, 0))],
    ['今日新入院', String(dashboard.worklist.filter((item) => new Date(item.admitted_at).toDateString() === new Date().toDateString()).length)],
    ['当前病人待完成任务', String(dashboard.worklist.find((item) => item.patient_id === clinicalContext.inpatientPatientId)?.pending_task_count ?? 0)],
  ];
});
const inpatientAlerts = computed<[string, string, string][]>(() => {
  if (inpatientLoading.value) return [['住院数据', '加载中', 'amber']];
  if (inpatientDataUnavailable.value) return [['住院数据', `不可用（${inpatientIssue.value?.code ?? 'UNKNOWN'}）`, 'red']];
  const dashboard = inpatientDashboard.data.value!;
  const overdue = dashboard.worklist.reduce((sum, item) => sum + item.overdue_task_count, 0);
  const pending = dashboard.worklist.reduce((sum, item) => sum + item.pending_task_count, 0);
  return [
    ['病区逾期文书', String(overdue), overdue ? 'red' : 'green'],
    ['病区待完成任务', String(pending), pending ? 'amber' : 'green'],
    ['当前住院实例', dashboard.overview.admission.status, 'green'],
  ];
});
const triageLabel = (value?: string) => ({ LEVEL_1: 'Ⅰ(A)', LEVEL_2: 'Ⅱ(B)', LEVEL_3: 'Ⅲ(C)', LEVEL_4: 'Ⅳ(D)' } as Record<string, string>)[value ?? ''] ?? '待分诊';
const domains = computed<DomainCard[]>(() => baseDomains.map((domain) => {
  if (domain.type === 'outpatient') return {
    ...domain, metrics: outpatientMetrics.value, alerts: outpatientAlerts.value,
    footer: [['当前上下文', `门诊患者 …${clinicalContext.patientId.slice(-8)} · 就诊 …${clinicalContext.encounterId.slice(-8)}`], ['数据范围', '当日队列 + 当前患者诊疗快照']],
  };
  if (domain.type === 'inpatient') return {
    ...domain, metrics: inpatientMetrics.value, alerts: inpatientAlerts.value,
    footer: [['当前上下文', `住院患者 …${clinicalContext.inpatientPatientId.slice(-8)} · 就诊 …${clinicalContext.inpatientEncounterId.slice(-8)}`], ['数据范围', inpatientIssue.value ? `当前岗位无法读取病区工作列表（${inpatientIssue.value.code}），未使用旧值或 0 回退` : '当前病区工作列表 + 当前住院实例']],
  };
  return {
    ...domain,
    metrics: emergencyDataUnavailable.value
      ? [['当前分诊', '不可用'], ['需立即处置', '—'], ['院区候诊', '—'], ['当前留观', '—'], ['开放协同', '—'], ['待去向', '—']]
      : [['当前分诊', triageLabel(currentTriage.value?.triage_level)], ['需立即处置', currentTriage.value?.immediate_action_required ? '1' : '0'], ['院区候诊', String(waitingCount.value)], ['当前留观', String(activeObservationCount.value)], ['开放协同', String(openCoordination.value.length)], ['待去向', String(activeObservationCount.value)]],
    alerts: emergencyDataUnavailable.value
      ? [['急诊数据', '加载失败', 'red']]
      : [['身份核验', recentIdentityMatched.value ? '有效' : '待核', recentIdentityMatched.value ? 'green' : 'red'], ['抢救未闭环', String(activeResuscitationCount.value), activeResuscitationCount.value ? 'red' : 'green'], ['协同已逾期', String(overdueCoordinationCount.value), overdueCoordinationCount.value ? 'amber' : 'green']],
    footer: [['当前上下文', `急诊患者 …${clinicalContext.emergencyPatientId.slice(-8)} · 就诊 …${clinicalContext.emergencyEncounterId.slice(-8)}`], ['数据边界', '院区候诊队列 + 当前急诊患者事实']],
  };
}));
const dataUpdatedAt = computed(() => {
  if (outpatientLoading.value || inpatientLoading.value || emergencyFacilityLease.isPending.value) return '加载中';
  return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date());
});

const portalBottom = [
  { to: 'clinical-tasks', title: '统一任务与临床路径', desc: '把危急值、会诊、审签、时限、路径变异与出院整改纳入同一责任清单。' },
  { to: 'archive-assets', title: '病案资产与 CDA', desc: '沉淀电子文书、签字扫描件、共享文档与归档证据，让病案可查、可用、可追溯。' },
  { to: 'credentials', title: '专科与资质扩展', desc: '把专科能力、移动查房与处方、手术、技术授权统一落实到实际岗位。' },
];

function go(to: string): void {
  void router.push(`/${to}`);
}
</script>

<template>
  <section data-page-root class="content vue-native-page clinical-portal-page">
  <div class="page-head">
    <div class="page-title">
      <h1>临床业务门户</h1>
      <p>进入门诊、急诊和住院工作台 · 本次加载 {{ dataUpdatedAt }}</p>
    </div>
    <div class="head-actions">
      <button class="btn" type="button" data-route-target="clinical-tasks" @click="go('clinical-tasks')">统一任务</button>
      <button class="btn" type="button" data-route-target="workflow" @click="go('workflow')">业务配置</button>
    </div>
  </div>

  <section class="portal-ai-intro" aria-labelledby="portal-ai-title">
    <img src="/brand/ai-medical-assistant-eva.png" alt="" width="52" height="52" />
    <div class="portal-ai-copy">
      <span>AI 临床辅助</span>
      <h2 id="portal-ai-title">AI医助 EVA · {{ aiMedicalAssistantRosterSummary.teamCount }} 个团队协同工作</h2>
      <p>由 {{ aiMedicalAssistantRosterSummary.specialistCount }} 个专科医助覆盖病历、结果、质控、协同与专病支持；基于当前患者、就诊和工作页面给出有来源的建议，最后由医生决定。</p>
    </div>
    <button class="btn primary" type="button" data-route-target="ai-assistant" @click="go('ai-assistant')">打开EVA</button>
    <section class="portal-ai-roster" aria-label="AI医助 EVA 团队名录">
      <strong>{{ aiMedicalAssistantRosterSummary.teamCount }} 个 AI医助团队</strong>
      <span>{{ aiMedicalAssistantRosterSummary.specialistCount }} 个专科医助</span>
      <div><em v-for="team in aiMedicalAssistantRoster" :key="team">{{ team }}</em></div>
    </section>
  </section>

  <div class="domain-split domain-triple">
    <section v-for="d in domains" :key="d.type" class="domain-card" :class="`${d.type}-domain`">
      <div class="domain-hero">
        <div class="domain-symbol">{{ d.symbol }}</div>
        <div>
          <div class="domain-eyebrow">{{ d.eyebrow }}</div>
          <h2>{{ d.title }}</h2>
          <p>{{ d.desc }}</p>
        </div>
        <button class="btn domain-enter" :class="{ primary: d.type === 'outpatient' }" type="button" :data-route-target="d.to" @click="go(d.to)">
          进入{{ d.title }} →
        </button>
      </div>
      <div class="domain-metrics">
        <div v-for="[label, value] in d.metrics" :key="label"><span>{{ label }}</span><b>{{ value }}</b></div>
      </div>
      <div class="domain-alerts">
        <span v-for="[label, count, status] in d.alerts" :key="label" class="status" :class="status">{{ label }} {{ count }}</span>
      </div>
      <div class="domain-flow">
        <b>诊疗主线</b>
        <template v-for="(step, i) in d.flow" :key="step">
          <i v-if="i">›</i><span>{{ step }}</span>
        </template>
      </div>
      <div class="module-map">
        <button v-for="m in d.modules" :key="m.title" type="button" :data-route-target="m.to" @click="go(m.to)"><b>{{ m.title }}</b><small>{{ m.desc }}</small></button>
      </div>
      <div class="domain-footer">
        <div v-for="[label, value] in d.footer" :key="label"><span>{{ label }}</span><b>{{ value }}</b></div>
      </div>
    </section>
  </div>

  <div class="portal-bottom">
    <div
      v-for="item in portalBottom"
      :key="item.to"
      role="button"
      tabindex="0"
      :data-route-target="item.to"
      @click="go(item.to)"
      @keydown.enter="go(item.to)"
    ><b>{{ item.title }}</b><span>{{ item.desc }}</span></div>
  </div>
  </section>
</template>

<style scoped>
.portal-ai-intro { display: grid; grid-template-columns: 52px minmax(240px,1fr) minmax(180px,.42fr); gap: 14px; align-items: center; padding: 15px 16px; margin-bottom: 14px; border: 1px solid #c8dcf0; border-radius: 12px; background: linear-gradient(135deg,#f3f8ff,#fff); box-shadow: 0 5px 18px rgba(31,79,128,.06); }
.portal-ai-intro > img { width: 52px; height: 52px; border: 1px solid #d8e5f0; border-radius: 14px; background: #fff; object-fit: cover; }
.portal-ai-copy > span { color: #1769a7; font-size: 9px; font-weight: 850; letter-spacing: 1.1px; }
.portal-ai-copy h2 { margin: 3px 0 5px; font-size: 17px; }
.portal-ai-copy p { margin: 0; color: var(--muted); font-size: 10px; line-height: 1.55; }
.portal-ai-roster { display: flex; grid-column: 1 / -1; align-items: center; gap: 7px; min-width: 0; padding-top: 10px; border-top: 1px solid #dce8f3; color: #536f88; font-size: 9px; }
.portal-ai-roster > strong { color: #25557f; font-size: 10px; white-space: nowrap; }
.portal-ai-roster > span { padding-right: 7px; border-right: 1px solid #d3e0ec; white-space: nowrap; }
.portal-ai-roster > div { display: flex; flex: 1; flex-wrap: wrap; gap: 5px; min-width: 0; }
.portal-ai-roster em { padding: 3px 6px; color: #486a87; border-radius: 999px; background: #eaf3fc; font-size: 8px; font-style: normal; white-space: nowrap; }
.portal-ai-intro > button { min-width: 0; justify-self: end; white-space: nowrap; }
.domain-flow { flex-wrap: wrap; row-gap: 5px; min-width: 0; white-space: normal; overflow: visible; }
.domain-flow b { flex: 0 0 100%; margin-right: 0; }
.domain-flow span { max-width: 100%; overflow-wrap: anywhere; }
@media (max-width: 1440px) {
  .portal-ai-intro { grid-template-columns: 52px minmax(0,1fr) auto; }
}
@media (max-width: 720px) {
  .portal-ai-intro { grid-template-columns: 44px minmax(0,1fr); padding: 13px; }
  .portal-ai-intro > img { width: 44px; height: 44px; border-radius: 12px; }
  .portal-ai-roster { align-items: flex-start; flex-wrap: wrap; }
  .portal-ai-roster > span { border-right: 0; }
  .portal-ai-roster > div { flex-basis: 100%; }
  .portal-ai-intro > button { grid-column: 2; justify-self: start; }
}
</style>
