<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, ref } from 'vue';
import type { ConfigurationItemWire, MetricSnapshotWire } from '../../generated/contracts';
import {
  issueConfigurationLease,
  listConfigurations,
} from '../../api/config';
import {
  issueGovernanceLease,
  listHistoricalMigrationBatches,
  listSourceSystems,
} from '../../api/governance';
import {
  issueDataLease,
  listDataQualityFindings,
  listDataQualityRules,
  listResearchCohorts,
  listResearchDatasetRequests,
} from '../../api/data';
import {
  computeMetricSnapshots,
  issueMetricLease,
  listMetricSnapshots,
} from '../../api/metrics';
import { toClinicalIssue } from '../clinical-error';

// 数据中心/机构-院区级上下文（无患者）：每一类下游台账都只读真实持久化数据，
// 指标数字一律来自服务端记录，不在页面硬编码。
const configLeaseQuery = useQuery({
  queryKey: ['data-center', 'overview', 'config-lease'],
  queryFn: issueConfigurationLease,
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const governanceLeaseQuery = useQuery({
  queryKey: ['data-center', 'overview', 'governance-lease'],
  queryFn: () => issueGovernanceLease('MIGRATION_ADMIN'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const qualityLeaseQuery = useQuery({
  queryKey: ['data-center', 'overview', 'quality-lease'],
  queryFn: () => issueDataLease('DATA_QUALITY_ADMIN'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const cohortLeaseQuery = useQuery({
  queryKey: ['data-center', 'overview', 'cohort-lease'],
  queryFn: () => issueDataLease('COHORT_BUILDER_ADMIN'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const datasetLeaseQuery = useQuery({
  queryKey: ['data-center', 'overview', 'dataset-lease'],
  queryFn: () => issueDataLease('RESEARCH_DATASET_ADMIN'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const metricLeaseQuery = useQuery({
  queryKey: ['data-center', 'overview', 'metric-lease'],
  queryFn: issueMetricLease,
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});

const connectorsQuery = useQuery({
  queryKey: computed(() => ['data-center', 'overview', 'integration', 'connectors', configLeaseQuery.data.value?.lease_id]),
  queryFn: () => listConfigurations(configLeaseQuery.data.value!, 'INTEGRATION_CONNECTOR'),
  enabled: () => Boolean(configLeaseQuery.data.value), retry: false,
});
const incidentsQuery = useQuery({
  queryKey: computed(() => ['data-center', 'overview', 'integration', 'incidents', configLeaseQuery.data.value?.lease_id]),
  queryFn: () => listConfigurations(configLeaseQuery.data.value!, 'INTEGRATION_INCIDENT'),
  enabled: () => Boolean(configLeaseQuery.data.value), retry: false,
});
const devicesQuery = useQuery({
  queryKey: computed(() => ['data-center', 'overview', 'devices', configLeaseQuery.data.value?.lease_id]),
  queryFn: () => listConfigurations(configLeaseQuery.data.value!, 'DEVICE_CATALOG'),
  enabled: () => Boolean(configLeaseQuery.data.value), retry: false,
});
const projectsQuery = useQuery({
  queryKey: computed(() => ['data-center', 'overview', 'projects', configLeaseQuery.data.value?.lease_id]),
  queryFn: () => listConfigurations(configLeaseQuery.data.value!, 'RESEARCH_PROJECT'),
  enabled: () => Boolean(configLeaseQuery.data.value), retry: false,
});
const sourcesQuery = useQuery({
  queryKey: computed(() => ['data-center', 'overview', 'sources', governanceLeaseQuery.data.value?.lease_id]),
  queryFn: () => listSourceSystems(governanceLeaseQuery.data.value!),
  enabled: () => Boolean(governanceLeaseQuery.data.value), retry: false,
});
const batchesQuery = useQuery({
  queryKey: computed(() => ['data-center', 'overview', 'batches', governanceLeaseQuery.data.value?.lease_id]),
  queryFn: () => listHistoricalMigrationBatches(governanceLeaseQuery.data.value!),
  enabled: () => Boolean(governanceLeaseQuery.data.value), retry: false,
});
const rulesQuery = useQuery({
  queryKey: computed(() => ['data-center', 'overview', 'rules', qualityLeaseQuery.data.value?.lease_id]),
  queryFn: () => listDataQualityRules(qualityLeaseQuery.data.value!),
  enabled: () => Boolean(qualityLeaseQuery.data.value), retry: false,
});
const findingsQuery = useQuery({
  queryKey: computed(() => ['data-center', 'overview', 'findings', qualityLeaseQuery.data.value?.lease_id]),
  queryFn: () => listDataQualityFindings(qualityLeaseQuery.data.value!),
  enabled: () => Boolean(qualityLeaseQuery.data.value), retry: false,
});
const cohortsQuery = useQuery({
  queryKey: computed(() => ['data-center', 'overview', 'cohorts', cohortLeaseQuery.data.value?.lease_id]),
  queryFn: () => listResearchCohorts(cohortLeaseQuery.data.value!),
  enabled: () => Boolean(cohortLeaseQuery.data.value), retry: false,
});
const requestsQuery = useQuery({
  queryKey: computed(() => ['data-center', 'overview', 'requests', datasetLeaseQuery.data.value?.lease_id]),
  queryFn: () => listResearchDatasetRequests(datasetLeaseQuery.data.value!),
  enabled: () => Boolean(datasetLeaseQuery.data.value), retry: false,
});
const coreFactsQuery = useQuery({
  queryKey: computed(() => ['data-center', 'overview', 'core-facts', metricLeaseQuery.data.value?.lease_id]),
  queryFn: () => listMetricSnapshots(metricLeaseQuery.data.value!, 'DATA_CENTER'),
  enabled: () => Boolean(metricLeaseQuery.data.value), retry: false,
});

const refreshing = ref(false);
const computingFacts = ref(false);
const coreFactNotice = ref('');

const overviewError = computed(() => [configLeaseQuery, governanceLeaseQuery, qualityLeaseQuery, cohortLeaseQuery, datasetLeaseQuery, metricLeaseQuery, connectorsQuery, incidentsQuery, devicesQuery, projectsQuery, sourcesQuery, batchesQuery, rulesQuery, findingsQuery, cohortsQuery, requestsQuery, coreFactsQuery].find(query => query.error.value)?.error.value);
const booting = computed(() => !overviewError.value && [
  configLeaseQuery, governanceLeaseQuery, qualityLeaseQuery, cohortLeaseQuery,
  datasetLeaseQuery, metricLeaseQuery,
].some((query) => query.isPending.value));

const overviewReady = computed(() => [
  connectorsQuery, incidentsQuery, devicesQuery, projectsQuery, sourcesQuery, batchesQuery,
  rulesQuery, findingsQuery, cohortsQuery, requestsQuery, coreFactsQuery,
].every((query) => !query.isPending.value));

function issueText(error: unknown): string | null {
  if (error == null) return null;
  const issue = toClinicalIssue(error);
  return `${issue.code}：${issue.message}`;
}

function text(item: ConfigurationItemWire, key: string): string {
  const value = item.payload?.[key];
  return typeof value === 'string' && value.trim() ? value.trim() : '';
}
function number(item: ConfigurationItemWire, key: string): number {
  const value = Number(item.payload?.[key] ?? 0);
  return Number.isFinite(value) ? value : 0;
}
function integer(value: number): string {
  return new Intl.NumberFormat('zh-CN').format(value);
}
/** 大数字按国内医院信息习惯缩写为“万/亿”，完整数值保留在数值行供核对。 */
function compact(value: number): string {
  if (!Number.isFinite(value)) return '—';
  const abs = Math.abs(value);
  const trimZero = (text: string) => text.replace(/\.0$/, '');
  if (abs >= 100_000_000) return `${trimZero((value / 100_000_000).toFixed(2))} 亿`;
  if (abs >= 10_000) return `${trimZero((value / 10_000).toFixed(1))} 万`;
  return integer(value);
}

// ── 集成交换 ────────────────────────────────────────────────
const connectors = computed(() => (connectorsQuery.data.value ?? []).filter((item) => item.status === 'ACTIVE'));
const openIncidents = computed(() => (incidentsQuery.data.value ?? []).filter((item) => item.status === 'ACTIVE'));

// ── 历史迁移 ────────────────────────────────────────────────
const sources = computed(() => sourcesQuery.data.value ?? []);
const registeredSourceCodes = computed(() => new Set(sources.value.map((source) => source.source_code)));
const batches = computed(() => (batchesQuery.data.value ?? [])
  .filter((batch) => registeredSourceCodes.value.has(batch.source_system)));
const activeSources = computed(() => sources.value.filter((source) => source.connection_status === 'ACTIVE').length);
const totalRecords = computed(() => batches.value.reduce((sum, batch) => sum + batch.record_count, 0));
const mismatchBatches = computed(() => batches.value.filter((batch) => batch.mismatch_count > 0));

// ── 数据质量 ────────────────────────────────────────────────
const rules = computed(() => (rulesQuery.data.value ?? []).filter((rule) => rule.status === 'ACTIVE'));
const findings = computed(() => findingsQuery.data.value ?? []);
const openFindings = computed(() => findings.value.filter(finding => finding.status !== 'CLOSED'));
const blockingFindings = computed(() => findings.value
  .filter((finding) => finding.status !== 'CLOSED' && finding.severity === 'BLOCKING'));
const warningFindings = computed(() => findings.value
  .filter((finding) => finding.status !== 'CLOSED' && finding.severity === 'WARNING'));

// ── 设备接入 ────────────────────────────────────────────────
const publishedDevices = computed(() => (devicesQuery.data.value ?? []).filter((item) => item.status === 'ACTIVE'));
function calibrationDaysLeft(raw: unknown): number | null {
  if (typeof raw !== 'string' || !raw.trim()) return null;
  const due = new Date(raw.trim().length === 10 ? `${raw.trim()}T00:00:00` : raw.trim());
  if (Number.isNaN(due.getTime())) return null;
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  return Math.round((due.getTime() - today.getTime()) / 86_400_000);
}
const deviceAttention = computed(() => publishedDevices.value.filter((device) => {
  const dueLeft = calibrationDaysLeft(device.payload?.calibration_due);
  const clockOffset = Math.abs(number(device, 'clock_offset_seconds'));
  return (dueLeft !== null && dueLeft <= 30) || clockOffset > 30;
}));

// ── 科研统计 ────────────────────────────────────────────────
const activeProjects = computed(() => (projectsQuery.data.value ?? []).filter((item) => item.status === 'ACTIVE'));
const activeCohorts = computed(() => (cohortsQuery.data.value ?? []).filter((cohort) => cohort.status === 'ACTIVE'));
const requestedRequests = computed(() => (requestsQuery.data.value ?? [])
  .filter((request) => request.status === 'REQUESTED'));

// ── 核心临床数据规模指标快照（DATA_CENTER） ──────────────────
const coreFactNames = ['患者主档案', '就诊事实', '已签署病历', '医嘱事实'] as const;
const coreFacts = computed<MetricSnapshotWire[]>(() => coreFactNames
  .map((name) => (coreFactsQuery.data.value ?? []).find((snapshot) => snapshot.metric_name === name))
  .filter((snapshot): snapshot is MetricSnapshotWire => Boolean(snapshot)));
const coreFactDate = computed(() => {
  const first = coreFacts.value[0];
  if (!first?.computed_at) return null;
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(first.computed_at));
});

// ── 跨域需关注汇总 ───────────────────────────────────────────
const attentionTotal = computed(() => openIncidents.value.length
  + mismatchBatches.value.length + openFindings.value.length
  + deviceAttention.value.length + requestedRequests.value.length);
const attentionChips = computed(() => [
  { label: '集成对账差异', count: openIncidents.value.length, to: '/integration-messages' },
  { label: '迁移差异批次', count: mismatchBatches.value.length, to: '/migration' },
  { label: '数据质量待处理', count: openFindings.value.length, to: '/data-quality' },
  { label: '设备校准超限', count: deviceAttention.value.length, to: '/devices' },
  { label: '科研申请待审', count: requestedRequests.value.length, to: '/research-dataset' },
]);

interface OverviewAttentionItem { label: string; caption: string; tone: 'red' | 'amber' | 'green' }
interface DomainCard {
  key: string;
  icon: string;
  title: string;
  subtitle: string;
  to: string;
  loading: boolean;
  error: string | null;
  retry: () => void;
  absent: boolean;
  absentHint: string;
  rows: Array<[string, string, 'danger' | 'amber' | 'ok' | 'plain']>;
  attention: OverviewAttentionItem[];
  attentionMore: number;
  links: Array<{ label: string; to: string }>;
}

function toAttention(item: ConfigurationItemWire, fallback: string, tone: OverviewAttentionItem['tone']): OverviewAttentionItem {
  const caption = text(item, 'clinical_impact') || text(item, 'description') || fallback;
  return { label: item.display_name, caption, tone };
}
function trimCaption(caption: string): string {
  return caption.length > 40 ? `${caption.slice(0, 40)}…` : caption;
}

const domainCards = computed<DomainCard[]>(() => {
  const integrationLoading = connectorsQuery.isPending.value || incidentsQuery.isPending.value;
  const integrationError = issueText(connectorsQuery.error.value ?? incidentsQuery.error.value);
  const integrationAttention = openIncidents.value.slice(0, 3).map((incident) => toAttention(incident, '开放对账差异', 'red'));
  const integration: DomainCard = {
    key: 'integration', icon: '接', title: '集成交换', subtitle: '业务系统互联与对账差异',
    to: '/integration', loading: integrationLoading, error: integrationError,
    retry: () => { void refreshAll(); },
    absent: connectors.value.length === 0 && openIncidents.value.length === 0,
    absentHint: '暂无已发布集成连接器或对账差异，可进入集成交换新建并发布连接器。',
    rows: [
      ['集成连接器', `${connectors.value.length} 个`, 'plain'],
      ['开放对账差异', `${openIncidents.value.length} 项`, openIncidents.value.length ? 'danger' : 'ok'],
    ] as Array<[string, string, 'danger' | 'amber' | 'ok' | 'plain']>,
    attention: integrationAttention.map((item) => ({ ...item, caption: trimCaption(item.caption) })),
    attentionMore: Math.max(0, openIncidents.value.length - 3),
    links: [{ label: '异常消息台账', to: '/integration-messages' }],
  };

  const migrationLoading = sourcesQuery.isPending.value || batchesQuery.isPending.value;
  const migrationError = issueText(sourcesQuery.error.value ?? batchesQuery.error.value);
  const migration: DomainCard = {
    key: 'migration', icon: '迁', title: '历史迁移', subtitle: '历史数据迁移与对账上线',
    to: '/migration', loading: migrationLoading, error: migrationError,
    retry: () => { void refreshAll(); },
    absent: sources.value.length === 0 && batches.value.length === 0,
    absentHint: '暂无迁移源系统与批次，进入历史迁移完成源系统盘点后开始迁移。',
    rows: [
      ['纳入迁移源系统', `${sources.value.length} 个（激活 ${activeSources.value}）`, 'plain'],
      ['历史迁移批次', `${batches.value.length} 个`, 'plain'],
      ['累计迁移记录', `${integer(totalRecords.value)} 条`, 'plain'],
      ['差异未清零批次', `${mismatchBatches.value.length} 个`, mismatchBatches.value.length ? 'danger' : 'ok'],
    ] as Array<[string, string, 'danger' | 'amber' | 'ok' | 'plain']>,
    attention: [], attentionMore: 0, links: [],
  };

  const qualityLoading = rulesQuery.isPending.value || findingsQuery.isPending.value;
  const qualityError = issueText(rulesQuery.error.value ?? findingsQuery.error.value);
  const qualityAttention = [...blockingFindings.value, ...warningFindings.value].slice(0, 3).map((finding): OverviewAttentionItem => ({
    label: `${finding.severity === 'BLOCKING' ? '阻断' : '警告'} · ${finding.reason_code}`,
    caption: trimCaption(finding.reason_detail),
    tone: finding.severity === 'BLOCKING' ? 'red' : 'amber',
  }));
  const quality: DomainCard = {
    key: 'quality', icon: '质', title: '数据质量', subtitle: '质量规则与问题整改闭环',
    to: '/data-quality', loading: qualityLoading, error: qualityError,
    retry: () => { void refreshAll(); },
    absent: rules.value.length === 0 && findings.value.length === 0,
    absentHint: '暂无质量规则与问题记录，进入数据质量登记规则并运行质量核查扫描。',
    rows: [
      ['在用质量规则', `${rules.value.length} 条`, 'plain'],
      ['未关闭阻断问题', `${blockingFindings.value.length} 项`, blockingFindings.value.length ? 'danger' : 'ok'],
      ['未关闭警告问题', `${warningFindings.value.length} 项`, warningFindings.value.length ? 'amber' : 'ok'],
    ] as Array<[string, string, 'danger' | 'amber' | 'ok' | 'plain']>,
    attention: qualityAttention, attentionMore: Math.max(0, blockingFindings.value.length + warningFindings.value.length - 3),
    links: [],
  };

  const deviceLoading = devicesQuery.isPending.value;
  const deviceError = issueText(devicesQuery.error.value);
  const deviceAttentionItems = deviceAttention.value.slice(0, 3).map((device): OverviewAttentionItem => {
    const dueLeft = calibrationDaysLeft(device.payload?.calibration_due);
    const caption = dueLeft !== null && dueLeft <= 30
      ? (dueLeft < 0 ? '校准已逾期' : `${dueLeft} 天后需校准`) : '设备时钟偏差超过 30 秒';
    return { label: device.display_name, caption, tone: 'amber' };
  });
  const devices: DomainCard = {
    key: 'devices', icon: '备', title: '设备接入', subtitle: '医疗设备接入与可信状态',
    to: '/devices', loading: deviceLoading, error: deviceError,
    retry: () => { void refreshAll(); },
    absent: publishedDevices.value.length === 0,
    absentHint: '暂无已接入的医疗设备，进入设备接入登记设备身份、网关与绑定策略。',
    rows: [
      ['已接入医疗设备', `${publishedDevices.value.length} 台`, 'plain'],
      ['校准到期/时钟超限', `${deviceAttention.value.length} 台`, deviceAttention.value.length ? 'danger' : 'ok'],
    ] as Array<[string, string, 'danger' | 'amber' | 'ok' | 'plain']>,
    attention: deviceAttentionItems, attentionMore: Math.max(0, deviceAttention.value.length - 3), links: [],
  };

  const researchLoading = projectsQuery.isPending.value || cohortsQuery.isPending.value || requestsQuery.isPending.value;
  const researchError = issueText(projectsQuery.error.value ?? cohortsQuery.error.value ?? requestsQuery.error.value);
  const research: DomainCard = {
    key: 'research', icon: '研', title: '科研统计', subtitle: '临床科研项目与数据申请',
    to: '/research', loading: researchLoading, error: researchError,
    retry: () => { void refreshAll(); },
    absent: activeProjects.value.length === 0 && activeCohorts.value.length === 0,
    absentHint: '暂无在研项目或活动队列，进入科研统计提交科研项目申请。',
    rows: [
      ['在研科研项目', `${activeProjects.value.length} 项`, 'plain'],
      ['活动研究队列', `${activeCohorts.value.length} 个`, 'plain'],
      ['待审批数据申请', `${requestedRequests.value.length} 项`, requestedRequests.value.length ? 'danger' : 'ok'],
    ] as Array<[string, string, 'danger' | 'amber' | 'ok' | 'plain']>,
    attention: requestedRequests.value.slice(0, 3).map((request): OverviewAttentionItem => ({
      label: request.purpose,
      caption: '科研数据申请，需独立审批后方可导出',
      tone: 'red',
    })),
    attentionMore: Math.max(0, requestedRequests.value.length - 3),
    links: [
      { label: '队列构建', to: '/cohort-builder' },
      { label: '数据交付', to: '/research-dataset' },
    ],
  };

  return [integration, migration, quality, devices, research];
});

async function computeCoreFacts() {
  const lease = metricLeaseQuery.data.value;
  if (!lease || computingFacts.value) return;
  computingFacts.value = true;
  coreFactNotice.value = '';
  try {
    await computeMetricSnapshots(lease, 'DATA_CENTER');
    await coreFactsQuery.refetch();
    coreFactNotice.value = '已按登记口径重算 DATA_CENTER 快照，来源、公式与审计事件随快照固化。';
  } catch (error) {
    const next = toClinicalIssue(error);
    coreFactNotice.value = `${next.code}：${next.message}`;
  } finally {
    computingFacts.value = false;
  }
}

async function refreshAll() {
  if (refreshing.value) return;
  refreshing.value = true;
  try {
    await Promise.allSettled([
      configLeaseQuery.refetch(), governanceLeaseQuery.refetch(), qualityLeaseQuery.refetch(),
      cohortLeaseQuery.refetch(), datasetLeaseQuery.refetch(), metricLeaseQuery.refetch(),
    ]);
    await Promise.allSettled([
      ...(configLeaseQuery.data.value
        ? [connectorsQuery.refetch(), incidentsQuery.refetch(), devicesQuery.refetch(), projectsQuery.refetch()]
        : []),
      ...(governanceLeaseQuery.data.value ? [sourcesQuery.refetch(), batchesQuery.refetch()] : []),
      ...(qualityLeaseQuery.data.value ? [rulesQuery.refetch(), findingsQuery.refetch()] : []),
      ...(cohortLeaseQuery.data.value ? [cohortsQuery.refetch()] : []),
      ...(datasetLeaseQuery.data.value ? [requestsQuery.refetch()] : []),
      ...(metricLeaseQuery.data.value ? [coreFactsQuery.refetch()] : []),
    ]);
  } finally {
    refreshing.value = false;
  }
}
</script>

<template>
  <section data-page-root class="content vue-native-page data-overview-page">
    <div class="page-head">
      <div class="page-title">
        <h1>数据总览</h1>
        <p>查看集成交换、历史迁移、数据质量、设备接入和科研统计的运行情况。</p>
      </div>
      <div class="head-actions">
        <button class="btn" type="button" :disabled="refreshing" @click="refreshAll">{{ refreshing ? '刷新中…' : '刷新数据' }}</button>
      </div>
    </div>

    <div v-if="booting" class="overview-state">正在核对数据中心各域数据，请稍候…</div>

    <template v-else>
      <section id="data-attention" class="overview-alert" aria-label="跨域需关注事项">
        <span v-if="overviewError" role="alert">待办统计暂不可用：{{ issueText(overviewError) }}。请刷新重试。</span>
        <template v-else-if="overviewReady">
          <template v-if="attentionTotal === 0">
            <b>当前状态</b>
            <span class="all-clear">各域台账暂无开放阻断与待办，运行正常</span>
          </template>
          <template v-else>
            <b>当前需关注</b>
            <RouterLink v-for="chip in attentionChips" :key="chip.label" :to="chip.to" class="alert-chip" :class="{ hot: chip.count > 0 }">
              {{ chip.label }}<em>{{ chip.count }}</em>
            </RouterLink>
          </template>
        </template>
        <span v-else>正在汇总各域台账，请稍候…</span>
      </section>

      <section class="card core-facts-card" aria-label="核心临床数据规模指标快照">
        <div class="card-head">
          全院核心临床数据规模
          <span class="core-facts-actions">
            <span class="sub">口径取自患者主索引（MPI）、就诊、病历与医嘱等核心对象；来源与公式随快照固化{{ coreFactDate ? ` · 最近计算 ${coreFactDate}` : '' }}</span>
            <button class="btn sm" type="button" :disabled="computingFacts || Boolean(metricLeaseQuery.error.value)" @click="computeCoreFacts">{{ computingFacts ? '计算中…' : '按登记口径计算' }}</button>
          </span>
        </div>
        <p v-if="coreFactNotice" class="core-fact-note" role="status">{{ coreFactNotice }}</p>
        <div v-if="coreFactsQuery.isPending.value" class="card-body">正在读取核心数据规模快照…</div>
        <div v-else-if="coreFactsQuery.error.value" class="card-body overview-error" role="status">指标快照读取失败：{{ issueText(coreFactsQuery.error.value) }}</div>
        <div v-else-if="coreFacts.length === 0" class="card-body">尚未生成核心数据规模指标快照，点击“按登记口径计算”由服务端按固化口径生成并写入审计链。</div>
        <div v-else class="card-body">
          <div class="metric-grid core-metrics" aria-label="核心临床数据规模">
            <article v-for="fact in coreFacts" :key="fact.snapshot_id" class="metric"
              :title="`来源 ${String(fact.dimension?.source ?? '—')} · ${String(fact.dimension?.formula ?? '人工记录')}`">
              <div class="name">{{ fact.metric_name }}<small v-if="fact.dimension?.source"> · {{ String(fact.dimension.source) }}</small></div>
              <div class="value">{{ compact(fact.metric_value ?? 0) }}</div>
              <div class="trend">完整 {{ integer(fact.metric_value ?? 0) }} {{ fact.unit ?? '' }}</div>
            </article>
          </div>
        </div>
      </section>

      <section class="domain-layout" aria-label="数据中心五个专业域数据台账">
        <article v-for="domain in domainCards" :key="domain.key" class="card domain-card">
          <header class="domain-card-head">
            <span class="domain-icon" aria-hidden="true">{{ domain.icon }}</span>
            <div class="domain-card-title"><b>{{ domain.title }}</b><small>{{ domain.subtitle }}</small></div>
            <RouterLink class="btn sm" :to="domain.to">进入 →</RouterLink>
          </header>
          <div class="domain-card-body">
            <div v-if="domain.loading" class="domain-hint">正在读取{{ domain.title }}数据…</div>
            <div v-else-if="domain.error" class="domain-hint error" role="status">加载失败：{{ domain.error }}<button class="btn sm" type="button" @click="domain.retry">重试</button></div>
            <template v-else-if="domain.absent">
              <div class="domain-empty">{{ domain.absentHint }}</div>
            </template>
            <template v-else>
              <div v-for="[label, value, tone] in domain.rows" :key="label" class="folder-row">
                <span>{{ label }}</span><strong :class="{ danger: tone === 'danger', amber: tone === 'amber', ok: tone === 'ok' }">{{ value }}</strong>
              </div>
              <div v-if="domain.attention.length" class="domain-attention">
                <b>需关注</b>
                <ul>
                  <li v-for="item in domain.attention" :key="item.label">
                    <em class="dot" :class="item.tone" aria-hidden="true"></em>
                    <span><strong>{{ item.label }}</strong><small>{{ item.caption }}</small></span>
                  </li>
                  <li v-if="domain.attentionMore > 0" class="more">另有 {{ domain.attentionMore }} 项，进入台账查看</li>
                </ul>
              </div>
              <div v-if="domain.links.length" class="domain-links">
                <RouterLink v-for="link in domain.links" :key="link.to" :to="link.to">{{ link.label }} →</RouterLink>
              </div>
            </template>
          </div>
        </article>
      </section>

      <p class="data-safeguard-note">数据访问不扩大权限：患者级病历调阅与科研数据导出须回到业务页面，完成身份核验、用途审查与独立审批后执行；本页仅汇总机构级治理指标。</p>
    </template>
  </section>
</template>

<style scoped>
.data-overview-page { display: grid; align-content: start; gap: 14px; min-width: 0; }
.overview-state { padding: 15px; color: var(--muted); border: 1px solid var(--line); border-radius: var(--r); background: #fff; }
.overview-alert { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 12px; padding: 9px 13px; color: #31445c; border: 1px solid #d7e2ef; border-left: 3px solid var(--blue); border-radius: 10px; background: #f8fbff; }
.overview-alert > b { font-size: 12px; }
.overview-alert .alert-chip { display: inline-flex; align-items: baseline; gap: 5px; padding: 3px 9px; color: #526a84; border: 1px solid #ccd9e7; border-radius: 999px; background: #fff; text-decoration: none; font-size: 11px; white-space: nowrap; }
.overview-alert .alert-chip em { color: #74869a; font-style: normal; font-weight: 800; }
.overview-alert .alert-chip.hot { color: #b93a45; border-color: #eeb9bd; background: #fff3f4; }
.overview-alert .alert-chip.hot em { color: #b93a45; }
.overview-alert .all-clear { color: var(--green); font-size: 11px; font-weight: 700; white-space: nowrap; }
.data-safeguard-note { margin: 0; padding: 0 2px; color: var(--muted); font-size: 10px; line-height: 1.7; overflow-wrap: anywhere; }
.core-facts-card .card-head { flex-wrap: wrap; row-gap: 8px; }
.core-facts-actions { margin-left: auto; display: inline-flex; align-items: center; gap: 10px; min-width: 0; flex-wrap: wrap; justify-content: flex-end; }
.core-facts-actions .sub { margin-left: 0; max-width: min(46ch, 60vw); overflow-wrap: anywhere; }
.core-fact-note { margin: 0; padding: 8px 15px 0; color: var(--muted); font-size: 10px; overflow-wrap: anywhere; }
.core-metrics { grid-template-columns: repeat(4, minmax(0, 1fr)); }
.core-metrics .metric { min-width: 0; overflow: hidden; }
.core-metrics .metric .name { display: flex; align-items: baseline; gap: 4px; overflow: hidden; }
.core-metrics .metric .name small { color: #8ba0b8; font-size: 9px; }
.core-metrics .metric .value { font-size: 21px; line-height: 1.2; white-space: nowrap; overflow-wrap: normal; }
.core-metrics .metric .trend { overflow-wrap: anywhere; }
.overview-error, .domain-hint { color: var(--red); font-size: 11px; line-height: 1.6; }
.domain-hint { display: flex; align-items: center; justify-content: space-between; gap: 8px; min-height: 72px; }
.domain-hint.error { color: var(--red); }
.domain-layout { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px; align-items: stretch; min-width: 0; }
.domain-card { display: flex; flex-direction: column; min-width: 0; overflow: hidden; }
.domain-card-head { display: flex; align-items: center; gap: 10px; padding: 13px 15px; border-bottom: 1px solid var(--line); min-width: 0; }
.domain-icon { flex: 0 0 auto; display: grid; place-items: center; width: 30px; height: 30px; border-radius: 8px; color: var(--blue); background: var(--blue-50); font-weight: 900; }
.domain-card-title { min-width: 0; flex: 1 1 auto; }
.domain-card-title b { display: block; font-size: 13px; }
.domain-card-title small { display: block; margin-top: 2px; overflow: hidden; color: var(--muted); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.domain-card-head .btn { flex: 0 0 auto; margin-left: auto; white-space: nowrap; }
.domain-card-body { flex: 1; display: flex; flex-direction: column; padding: 6px 15px 13px; min-width: 0; }
.domain-card-body .folder-row { min-width: 0; gap: 10px; }
.domain-card-body .folder-row span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.domain-card-body .folder-row strong { flex: 0 0 auto; white-space: nowrap; }
.domain-card-body .folder-row strong.danger { color: var(--red); }
.domain-card-body .folder-row strong.amber { color: var(--amber); }
.domain-card-body .folder-row strong.ok { color: var(--green); }
.domain-card-body .folder-row strong { font-weight: 750; }
.domain-empty { padding: 14px 2px; color: var(--muted); font-size: 11px; line-height: 1.7; overflow-wrap: anywhere; }
.domain-attention { margin-top: 10px; border-top: 1px dashed #dfe7ef; padding-top: 8px; min-width: 0; }
.domain-attention > b { display: block; margin-bottom: 5px; color: var(--muted); font-size: 9px; letter-spacing: .06em; }
.domain-attention ul { margin: 0; padding: 0; list-style: none; }
.domain-attention li { display: flex; gap: 6px; padding: 4px 0; min-width: 0; }
.domain-attention li span { min-width: 0; flex: 1 1 auto; }
.domain-attention strong { display: block; overflow: hidden; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.domain-attention small { display: block; margin-top: 1px; overflow: hidden; color: var(--muted); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.domain-attention .dot { flex: 0 0 auto; width: 6px; height: 6px; margin-top: 4px; border-radius: 50%; }
.domain-attention .dot.red { background: var(--red); }
.domain-attention .dot.amber { background: var(--amber); }
.domain-attention .dot.green { background: var(--green); }
.domain-attention li.more { color: var(--muted); font-size: 9px; white-space: nowrap; }
.domain-links { display: flex; flex-wrap: wrap; gap: 10px; margin-top: auto; padding-top: 10px; min-width: 0; }
.domain-links a { color: var(--blue); font-size: 10px; font-weight: 700; text-decoration: none; white-space: nowrap; }

@media (max-width: 1100px) { .core-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 700px) {
  .page-head { height: auto; min-height: 0; flex-direction: column; align-items: stretch; gap: 10px; margin-bottom: 14px; }
  .head-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-left: 0; }
  .head-actions .btn { flex: 1 1 140px; width: auto; min-height: 36px; text-align: center; }
  .core-metrics { grid-template-columns: minmax(0, 1fr); }
}
</style>
