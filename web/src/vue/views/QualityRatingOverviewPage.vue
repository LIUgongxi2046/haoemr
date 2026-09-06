<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import { issueSpecialtySupportLease, loadSpecialtySupportAssessments } from '../../clinical-api';
import QualityGovernanceOverview from '../components/QualityGovernanceOverview.vue';
import { toClinicalIssue } from '../clinical-error';
import { downloadQualityCsv, type QualityOverviewMetric, type QualityOverviewRow } from '../quality-overview';

const router = useRouter();
const leaseQuery = useQuery({ queryKey: ['quality-overview', 'rating', 'lease'], queryFn: issueSpecialtySupportLease, retry: false, staleTime: 5 * 60_000 });
const dataQuery = useQuery({ queryKey: ['quality-overview', 'rating'], queryFn: () => loadSpecialtySupportAssessments(leaseQuery.data.value!), enabled: () => Boolean(leaseQuery.data.value), retry: false, staleTime: 0 });
const items = computed(() => dataQuery.data.value ?? []);
const mappedProjectCount = computed(() => new Set(items.value.map((item) => item.clinical_scope_code)).size);
const ready = computed(() => items.value.filter((item) => ['GENERAL_AVAILABLE', 'BASIC_CLOSED_LOOP'].includes(item.support_level)));
const pending = computed(() => items.value.filter((item) => ['PACK_PENDING', 'UNSUPPORTED'].includes(item.support_level)));
const gaps = computed(() => items.value.filter((item) => item.missing_safety_gates.length));
const metrics = computed<QualityOverviewMetric[]>(() => [
  { label: '已建档评价范围', value: `${mappedProjectCount.value}/39`, hint: '按临床范围编码去重，不伪造满分' },
  { label: '有效应用达标', value: ready.value.length, hint: '可下钻到业务对象' },
  { label: '待整改', value: pending.value.length, hint: '可下钻到业务对象' },
  { label: '证据缺口', value: gaps.value.length, hint: '可下钻到业务对象' },
]);
const coverage = computed(() => items.value.length ? (ready.value.length / items.value.length * 100).toFixed(1) : '0.0');
const rows = computed<QualityOverviewRow[]>(() => [
  { object: '高风险待办', keyData: String(gaps.value.length), progress: `${pending.value.length} 项待整改`, status: gaps.value.length ? '需处理' : '正常', tone: gaps.value.length ? 'red' : 'green' },
  { object: '证据覆盖率', keyData: `${coverage.value}%`, progress: `${mappedProjectCount.value} 个已建档评价范围`, status: Number(coverage.value) >= 90 ? '正常' : '降级', tone: Number(coverage.value) >= 90 ? 'green' : 'yellow' },
  { object: '本期达标率', keyData: `${coverage.value}%`, progress: '目标 ≥96%', status: Number(coverage.value) >= 96 ? '达标' : '观察', tone: Number(coverage.value) >= 96 ? 'green' : 'blue' },
]);
const issue = computed(() => leaseQuery.error.value ?? dataQuery.error.value ? toClinicalIssue(leaseQuery.error.value ?? dataQuery.error.value).message : '');
function exportItems() { downloadQualityCsv(`评级取证清单-${new Date().toISOString().slice(0, 10)}.csv`, ['科室', '临床范围', '支持等级', '证据哈希', '缺失安全门', '到期时间'], items.value.map((item) => [item.department_id, item.clinical_scope_code, item.support_level, item.evidence_bundle_hash, item.missing_safety_gates.join('|'), item.expires_at])); }
</script>

<template><QualityGovernanceOverview title="医疗质量与电子病历评级看板" description="39 项评价的功能、应用范围、四维数据质量和证据快照" :metrics="metrics" :rows="rows" :warning="`发现 ${gaps.length} 项需要人工核验的高风险差异`" safety-text="系统保留原对象与处理证据，不以页面操作直接覆盖临床事实。" export-label="导出清单" primary-label="新建评级评估" detail-label="进入处理详情" :loading="leaseQuery.isPending.value || dataQuery.isPending.value" :error="issue" @export="exportItems" @primary="router.push('/quality-rating/assessments?create=1')" @detail="router.push('/quality-rating/assessments')" @retry="dataQuery.refetch()" /></template>
