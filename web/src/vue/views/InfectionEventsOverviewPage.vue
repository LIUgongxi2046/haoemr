<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import { issueInfectionLease, listInfectionMonitoringEvents } from '../../api/quality';
import { clinicalContext } from '../../clinical-api';
import AgentInlineReview from '../components/AgentInlineReview.vue';
import QualityGovernanceOverview from '../components/QualityGovernanceOverview.vue';
import { toClinicalIssue } from '../clinical-error';
import { downloadQualityCsv, type QualityOverviewMetric, type QualityOverviewRow } from '../quality-overview';

const router = useRouter();
const leaseQuery = useQuery({ queryKey: ['quality-overview', 'infection', 'lease'], queryFn: () => issueInfectionLease('INFECTION_MONITORING'), retry: false, staleTime: 5 * 60_000 });
const dataQuery = useQuery({ queryKey: ['quality-overview', 'infection'], queryFn: () => listInfectionMonitoringEvents(leaseQuery.data.value!), enabled: () => Boolean(leaseQuery.data.value), retry: false, staleTime: 0 });
const items = computed(() => dataQuery.data.value ?? []);
const pending = computed(() => items.value.filter((item) => item.status === 'REPORTED'));
const confirmed = computed(() => items.value.filter((item) => item.status === 'CONFIRMED'));
const resolved = computed(() => items.value.filter((item) => item.status !== 'REPORTED'));
const complete = computed(() => items.value.length ? (items.value.filter((item) => item.infection_type && item.reported_at).length / items.value.length * 100).toFixed(1) : '100.0');
const metrics = computed<QualityOverviewMetric[]>(() => [
  { label: '待处理', value: pending.value.length, hint: '可下钻到业务对象' },
  { label: '已完成人工复核', value: resolved.value.length, hint: '可下钻到业务对象' },
  { label: '风险阻断', value: confirmed.value.length, hint: '可下钻到业务对象' },
  { label: '数据完整', value: `${complete.value}%`, hint: '可下钻到业务对象' },
]);
const rows = computed<QualityOverviewRow[]>(() => [
  { object: '高风险待办', keyData: String(pending.value.length), progress: `${confirmed.value.length} 项已确认`, status: pending.value.length ? '需处理' : '正常', tone: pending.value.length ? 'red' : 'green' },
  { object: '人工复核率', keyData: `${items.value.length ? (resolved.value.length / items.value.length * 100).toFixed(1) : '100.0'}%`, progress: `${items.value.length} 条线索`, status: pending.value.length ? '降级' : '正常', tone: pending.value.length ? 'yellow' : 'green' },
  { object: '数据完整率', keyData: `${complete.value}%`, progress: '来源：院感线索台账', status: Number(complete.value) >= 99 ? '达标' : '观察', tone: Number(complete.value) >= 99 ? 'green' : 'blue' },
]);
const issue = computed(() => leaseQuery.error.value ?? dataQuery.error.value ? toClinicalIssue(leaseQuery.error.value ?? dataQuery.error.value).message : '');
const agentPatientId = computed(() => clinicalContext.patientId ? clinicalContext.patientId : null);
const agentEncounterId = computed(() => clinicalContext.encounterId ? clinicalContext.encounterId : null);
const infectionCaseObjective = computed(() => '对当前院感线索进行监测研判，输出候选建议，仅供医生审阅。');
function exportItems() { downloadQualityCsv(`院感事件清单-${new Date().toISOString().slice(0, 10)}.csv`, ['线索ID', '感染类型', '病原体', '上报时间', '状态', '版本'], items.value.map((item) => [item.infection_event_id, item.infection_type, item.organism_code, item.reported_at, item.status, item.row_version])); }
</script>

<template>
  <QualityGovernanceOverview title="院感、传染病与不良事件" description="智能线索、人工排除、上报时限、重试和整改闭环" :metrics="metrics" :rows="rows" :warning="`发现 ${pending.length} 项需要人工核验的高风险差异`" safety-text="系统保留原对象与处理证据，不以页面操作直接覆盖临床事实。" export-label="导出清单" primary-label="审核高风险线索" detail-label="进入处理详情" :loading="leaseQuery.isPending.value || dataQuery.isPending.value" :error="issue" @export="exportItems" @primary="router.push('/infection-events/clues?review=1')" @detail="router.push('/infection-events/clues')" @retry="dataQuery.refetch()" />
  <AgentInlineReview agent-code="INFECTION_SURVEILLANCE" stage-code="INFECTION_CASE" :objective="infectionCaseObjective" :patient-id="agentPatientId" :encounter-id="agentEncounterId" target-type="ENCOUNTER" :target-id="agentEncounterId" title="AI 院感监测候选" source-route="infection-events" />
</template>
