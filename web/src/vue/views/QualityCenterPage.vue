<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed } from 'vue';
import { issueConfigurationLease, listConfigurations } from '../../api/config';
import { listPractitionerCredentials } from '../../api/credentials';
import { issueInfectionLease, listInfectionMonitoringEvents } from '../../api/quality';
import { issueSpecialtySupportLease, loadSpecialtySupportAssessments } from '../../clinical-api';
import { toClinicalIssue } from '../clinical-error';

// —— 四域数据源与模块页同源，页面只做只读聚合，不在此处写业务对象 ——
const qcLeaseQuery = useQuery({ queryKey: ['quality-center', 'dept-qc', 'lease'], queryFn: issueConfigurationLease, retry: false, staleTime: 5 * 60_000 });
const qcItemsQuery = useQuery({
  queryKey: ['quality-center', 'dept-qc'],
  queryFn: () => listConfigurations(qcLeaseQuery.data.value!, 'DEPARTMENT_QC_CASE'),
  enabled: computed(() => Boolean(qcLeaseQuery.data.value)), retry: false, staleTime: 0,
});
const ratingLeaseQuery = useQuery({ queryKey: ['quality-center', 'rating', 'lease'], queryFn: issueSpecialtySupportLease, retry: false, staleTime: 5 * 60_000 });
const ratingItemsQuery = useQuery({
  queryKey: ['quality-center', 'rating'],
  queryFn: () => loadSpecialtySupportAssessments(ratingLeaseQuery.data.value!),
  enabled: computed(() => Boolean(ratingLeaseQuery.data.value)), retry: false, staleTime: 0,
});
const infectionLeaseQuery = useQuery({ queryKey: ['quality-center', 'infection', 'lease'], queryFn: () => issueInfectionLease('INFECTION_MONITORING'), retry: false, staleTime: 5 * 60_000 });
const infectionItemsQuery = useQuery({
  queryKey: ['quality-center', 'infection'],
  queryFn: () => listInfectionMonitoringEvents(infectionLeaseQuery.data.value!),
  enabled: computed(() => Boolean(infectionLeaseQuery.data.value)), retry: false, staleTime: 0,
});
const credentialsQuery = useQuery({ queryKey: ['quality-center', 'credentials', 'grants'], queryFn: listPractitionerCredentials, retry: false, staleTime: 0 });

const qcItems = computed(() => qcItemsQuery.data.value ?? []);
const ratingItems = computed(() => ratingItemsQuery.data.value ?? []);
const infectionItems = computed(() => infectionItemsQuery.data.value ?? []);
const credentials = computed(() => credentialsQuery.data.value ?? []);

// —— 院科质控：与 DepartmentQcPage 同口径 ——
const qcPayload = (item: (typeof qcItems.value)[number]) => (item.payload ?? {}) as Record<string, unknown>;
const qcOpen = computed(() => qcItems.value.filter((item) => item.status !== 'ARCHIVED' && String(qcPayload(item).workflow_status) !== 'CLOSED'));
const qcBlocking = computed(() => qcOpen.value.filter((item) => String(qcPayload(item).severity) === 'BLOCKING'));
const qcOverdue = computed(() => qcOpen.value.filter((item) => {
  const due = qcPayload(item).due_at;
  return Boolean(due) && new Date(String(due)).getTime() < Date.now();
}));
const qcClosedRate = computed(() => qcItems.value.length ? ((qcItems.value.length - qcOpen.value.length) / qcItems.value.length * 100).toFixed(1) : '100.0');

// —— 评级取证：与 QualityRatingOverviewPage 同口径 ——
const ratingMapped = computed(() => new Set(ratingItems.value.map((item) => item.clinical_scope_code)).size);
const ratingReady = computed(() => ratingItems.value.filter((item) => ['GENERAL_AVAILABLE', 'BASIC_CLOSED_LOOP'].includes(item.support_level)));
const ratingPending = computed(() => ratingItems.value.filter((item) => ['PACK_PENDING', 'UNSUPPORTED'].includes(item.support_level)));
const ratingGaps = computed(() => ratingItems.value.filter((item) => item.missing_safety_gates.length));

// —— 院感事件：与 InfectionEventsOverviewPage 同口径 ——
const infectionPending = computed(() => infectionItems.value.filter((item) => item.status === 'REPORTED'));
const infectionConfirmed = computed(() => infectionItems.value.filter((item) => item.status === 'CONFIRMED'));
const infectionReviewed = computed(() => infectionItems.value.filter((item) => item.status !== 'REPORTED'));

// —— 临床资质：与 CredentialsOverviewPage 同口径 ——
const credentialNow = computed(() => Date.now());
const credentialActive = computed(() => credentials.value.filter((item) => item.status === 'ACTIVE' && (!item.valid_until || new Date(item.valid_until).getTime() > credentialNow.value)));
const credentialExpiring = computed(() => credentialActive.value.filter((item) => item.valid_until && new Date(item.valid_until).getTime() <= credentialNow.value + 30 * 86400_000));
const credentialBlocked = computed(() => credentials.value.filter((item) => item.status === 'REVOKED' || item.status === 'SUSPENDED' || item.status === 'EXPIRED' || (item.valid_until && new Date(item.valid_until).getTime() <= credentialNow.value)));

const issue = computed(() => {
  const firstError = [qcLeaseQuery.error.value, qcItemsQuery.error.value, ratingLeaseQuery.error.value, ratingItemsQuery.error.value,
    infectionLeaseQuery.error.value, infectionItemsQuery.error.value, credentialsQuery.error.value].find(Boolean);
  return firstError ? toClinicalIssue(firstError).message : '';
});
async function retryAll() {
  await Promise.allSettled([qcLeaseQuery.refetch(), ratingLeaseQuery.refetch(), infectionLeaseQuery.refetch()]);
  await Promise.allSettled([qcItemsQuery.refetch(), ratingItemsQuery.refetch(), infectionItemsQuery.refetch(), credentialsQuery.refetch()]);
}
const anyPending = computed(() => qcLeaseQuery.isPending.value || qcItemsQuery.isPending.value || ratingLeaseQuery.isPending.value || ratingItemsQuery.isPending.value
  || infectionLeaseQuery.isPending.value || infectionItemsQuery.isPending.value || credentialsQuery.isPending.value);
const loading = computed(() => anyPending.value && !issue.value);

// —— 四域实时卡片（主指标 + 次指标 + 进入域页）——
const domainCards = computed(() => [
  {
    icon: '质', to: '/department-qc', title: '院科病历质控', description: '抽查、缺陷、整改与复核',
    primary: { label: '阻断缺陷', value: anyPending.value ? '…' : qcBlocking.value.length, danger: qcBlocking.value.length > 0 },
    facts: `待整改 ${anyPending.value ? '…' : qcOpen.value.length} · 逾期 ${anyPending.value ? '…' : qcOverdue.value.length} · 闭环 ${anyPending.value ? '…' : `${qcClosedRate.value}%`}`,
  },
  {
    icon: '级', to: '/quality-rating', title: '评级取证', description: '功能、范围、质量与证据快照',
    primary: { label: '证据缺口', value: anyPending.value ? '…' : ratingGaps.value.length, danger: ratingGaps.value.length > 0 },
    facts: `已建档 ${anyPending.value ? '…' : ratingMapped.value} 个范围 · 达标 ${anyPending.value ? '…' : ratingReady.value.length} 项`,
  },
  {
    icon: '感', to: '/infection-events', title: '院感与不良事件', description: '线索、排除、上报与闭环',
    primary: { label: '待人工复核', value: anyPending.value ? '…' : infectionPending.value.length, danger: infectionPending.value.length > 0 },
    facts: `已确认 ${anyPending.value ? '…' : infectionConfirmed.value.length} · 已完成复核 ${anyPending.value ? '…' : infectionReviewed.value.length}`,
  },
  {
    icon: '权', to: '/credentials', title: '临床资质', description: '处方、手术、技术和临时授权',
    primary: { label: '30 日内到期', value: anyPending.value ? '…' : credentialExpiring.value.length, danger: credentialExpiring.value.length > 0 },
    facts: `有效授权 ${anyPending.value ? '…' : credentialActive.value.length} · 阻断/临期 ${anyPending.value ? '…' : credentialBlocked.value.length}`,
  },
]);

// —— 跨四域重点队列（表格式），每行可直达对应台账 ——
const queueRows = computed(() => [
  {
    domain: '院科质控', object: '阻断缺陷', keyData: anyPending.value ? '…' : `${qcBlocking.value.length} 项阻断`,
    progress: `开放 ${anyPending.value ? '…' : qcOpen.value.length} · 逾期 ${anyPending.value ? '…' : qcOverdue.value.length} · 闭环 ${anyPending.value ? '…' : `${qcClosedRate.value}%`}`,
    status: qcBlocking.value.length ? '需处理' : '正常', tone: qcBlocking.value.length ? 'red' : 'green', to: '/department-qc/cases',
  },
  {
    domain: '评级取证', object: '证据缺口', keyData: anyPending.value ? '…' : `${ratingGaps.value.length} 项缺口`,
    progress: `已建档 ${anyPending.value ? '…' : ratingMapped.value} 个范围 · 达标 ${anyPending.value ? '…' : ratingReady.value.length} 项`,
    status: ratingGaps.value.length ? '需处理' : '正常', tone: ratingGaps.value.length ? 'red' : 'green', to: '/quality-rating/assessments',
  },
  {
    domain: '院感事件', object: '待人工复核线索', keyData: anyPending.value ? '…' : `${infectionPending.value.length} 条待复核`,
    progress: `已确认 ${anyPending.value ? '…' : infectionConfirmed.value.length} · 已完成复核 ${anyPending.value ? '…' : infectionReviewed.value.length}`,
    status: infectionPending.value.length ? '需处理' : '正常', tone: infectionPending.value.length ? 'yellow' : 'green', to: '/infection-events/clues',
  },
  {
    domain: '临床资质', object: '30 日内到期授权', keyData: anyPending.value ? '…' : `${credentialExpiring.value.length} 份临期`,
    progress: `有效授权 ${anyPending.value ? '…' : credentialActive.value.length} · 阻断/临期 ${anyPending.value ? '…' : credentialBlocked.value.length}`,
    status: credentialExpiring.value.length ? '需处理' : '正常', tone: credentialExpiring.value.length ? 'yellow' : 'green', to: '/credentials/grants',
  },
]);
</script>

<template>
  <section data-page-root class="content vue-native-page quality-center-page">
    <div class="page-head">
      <div class="page-title"><h1>医疗质量中心</h1><p>集中查看病历质量、评级材料、院感事件和临床资质。</p></div>
      <div class="head-actions"><RouterLink class="btn" to="/quality-center/initiatives">角色工作台</RouterLink></div>
    </div>

    <div v-if="loading" class="quality-center-state" role="status">正在读取四域实时质量数据…</div>
    <div v-else-if="issue" class="quality-center-state error" role="alert"><b>实时聚合数据读取失败</b><span>{{ issue }}</span><button class="btn sm" type="button" @click="retryAll">重新加载</button></div>

    <template v-else>
      <section class="quality-domain-grid" aria-label="四域质量实时状态">
        <RouterLink v-for="card in domainCards" :key="card.to" class="quality-domain-card" :to="card.to">
          <header><span class="quality-domain-icon">{{ card.icon }}</span><div><b>{{ card.title }}</b><p>{{ card.description }}</p></div><i>进入 →</i></header>
          <div class="quality-domain-primary" :class="{ danger: card.primary.danger }">
            <small>{{ card.primary.label }}</small>
            <strong>{{ card.primary.value }}</strong>
          </div>
          <footer>{{ card.facts }}</footer>
        </RouterLink>
      </section>

      <div class="quality-cross-links" aria-label="跨域业务入口">
        <RouterLink class="quality-cross-link" to="/record"><b>病历中心</b><span>进入患者病历创作、来源、审签和版本</span></RouterLink>
        <RouterLink class="quality-cross-link" to="/archive-assets"><b>病案资产中心</b><span>进入目录、扫描、验真、借阅和长期保存</span></RouterLink>
      </div>

      <div id="quality-pending-queue" class="quality-center-queue card">
        <header><b>跨四域重点队列</b><span>实时业务数据 · 与各工作台同源</span></header>
        <div class="quality-center-table-wrap">
          <table class="quality-center-table">
            <thead><tr><th>域</th><th>对象</th><th>关键事实</th><th>进度/来源</th><th>状态</th><th></th></tr></thead>
            <tbody>
              <tr v-for="row in queueRows" :key="`${row.domain}-${row.object}`">
                <td><b>{{ row.domain }}</b></td>
                <td>{{ row.object }}</td>
                <td>{{ row.keyData }}</td>
                <td>{{ row.progress }}</td>
                <td><span class="quality-status" :class="row.tone">{{ row.status }}</span></td>
                <td class="quality-row-action"><RouterLink :to="row.to">进入台账 →</RouterLink></td>
              </tr>
            </tbody>
          </table>
        </div>
        <footer class="quality-center-note">
          <span>本页指标均由院科质控、评级取证、院感事件、临床资质四个真实台账实时计算并只读汇总，改动请回到对应工作台处理，不在总览页伪造或写入任何完成度。</span>
        </footer>
      </div>
    </template>
  </section>
</template>

<style scoped>
.quality-center-page{min-width:0}
.quality-center-state{display:flex;align-items:center;gap:12px;padding:18px;border:1px solid var(--line);border-radius:10px;background:#fff;color:var(--muted);font-size:12px}
.quality-center-state.error{border-color:#f1b7b7;background:#fff7f7;color:#b4232f}
.quality-center-state .btn{margin-left:auto}
.quality-domain-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin-bottom:12px}
.quality-domain-card{display:block;border:1px solid var(--line);background:#fff;border-radius:11px;padding:14px;color:inherit;text-decoration:none;transition:.18s;box-shadow:var(--shadow);min-width:0}
.quality-domain-card:hover{border-color:#86b2e5;transform:translateY(-2px);box-shadow:0 8px 18px #163a5f12}
.quality-domain-card header{display:flex;gap:9px;align-items:center}
.quality-domain-icon{width:30px;height:30px;border-radius:8px;background:var(--blue-50);color:var(--blue);display:grid;place-items:center;font-weight:900;flex:0 0 auto}
.quality-domain-card header>div{min-width:0;flex:1}
.quality-domain-card header b{display:block;font-size:13px}
.quality-domain-card header p{margin:2px 0 0;font-size:10px;color:var(--muted);line-height:1.4;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.quality-domain-card header i{font-style:normal;color:var(--blue);font-size:10px;font-weight:700;flex:0 0 auto}
.quality-domain-primary{display:flex;align-items:baseline;justify-content:space-between;gap:8px;margin:14px 0 10px;padding-top:12px;border-top:1px solid #edf1f4}
.quality-domain-primary small{color:var(--muted);font-size:11px}
.quality-domain-primary strong{font-size:26px;font-weight:800;color:var(--ink);font-variant-numeric:tabular-nums}
.quality-domain-primary.danger strong{color:var(--red)}
.quality-domain-card footer{font-size:10px;color:var(--muted-2);line-height:1.5;overflow-wrap:anywhere}
.quality-cross-links{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;margin-bottom:12px}
.quality-cross-link{display:flex;align-items:center;gap:12px;border:1px dashed var(--line);background:#fbfcfe;border-radius:10px;padding:10px 14px;color:inherit;text-decoration:none;min-width:0}
.quality-cross-link:hover{border-color:#86b2e5;background:#f6faff}
.quality-cross-link b{color:var(--blue);font-size:12px;flex:0 0 auto}
.quality-cross-link span{font-size:10px;color:var(--muted);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;min-width:0}
.quality-center-queue{overflow:hidden}
.quality-center-queue>header{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:12px 15px;border-bottom:1px solid var(--line)}
.quality-center-queue>header span{padding:4px 8px;border-radius:12px;background:#f1f5f9;color:var(--muted);font-size:9px;white-space:nowrap}
.quality-center-table-wrap{overflow-x:auto}
.quality-center-table{width:100%;border-collapse:collapse;min-width:620px}
.quality-center-table th,.quality-center-table td{padding:11px 13px;border-bottom:1px solid #edf1f4;text-align:left;font-size:11px;white-space:nowrap}
.quality-center-table th{background:#f8fafc;color:var(--muted);font-size:9px}
.quality-center-table td a{color:var(--blue);text-decoration:none;font-weight:700}
.quality-status{display:inline-flex;padding:4px 8px;border-radius:10px;font-size:9px;font-weight:700}
.quality-status.red{background:#fff0f0;color:#b4232f}
.quality-status.yellow{background:#fff7df;color:#946200}
.quality-status.green{background:#eaf8ef;color:#18794e}
.quality-center-note{padding:10px 15px;background:#f8fafc;color:var(--muted-2);font-size:10px;line-height:1.6}
@media(max-width:1240px){.quality-domain-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}
@media(max-width:640px){.quality-domain-grid{grid-template-columns:minmax(0,1fr)}.quality-cross-links{grid-template-columns:minmax(0,1fr)}.page-head{height:auto;flex-direction:column;align-items:stretch}.head-actions{display:flex;flex-wrap:wrap;margin-left:0}}
</style>
