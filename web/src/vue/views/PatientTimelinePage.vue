<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import type { PatientTimelineItemWire, PatientTimelineWire } from '../../generated/contracts';
import { clinicalContext, issuePatientTimelineLease, loadPatientTimeline } from '../../clinical-api';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue, type ClinicalIssue } from '../clinical-error';

const sourceOptions = [
  ['ENCOUNTER', '就诊'], ['DOCUMENT', '文书'], ['DIAGNOSIS', '诊断'],
  ['ORDER', '医嘱'], ['RESULT', '结果'], ['TASK', '任务'], ['DEVICE', '设备'],
] as const;
const filters = reactive({
  patientId: clinicalContext.patientId,
  from: '', to: '', statuses: '',
  types: sourceOptions.map(([value]) => value) as string[],
});
const snapshot = ref<PatientTimelineWire | null>(null);
const items = ref<PatientTimelineItemWire[]>([]);
const loading = ref(false);
const loadingMore = ref(false);
const issue = ref<ClinicalIssue | null>(null);

const sourceLabel = Object.fromEntries(sourceOptions) as Record<string, string>;
const partialSources = computed(() => snapshot.value?.source_statuses.filter((source) => source.state === 'PARTIAL') ?? []);
const sourceSummary = computed(() => sourceOptions.map(([source, label]) => {
  const state = snapshot.value?.source_statuses.find((entry) => entry.source === source);
  return { source, label, selected: filters.types.includes(source), state, visible: items.value.filter((item) => item.item_type === source).length };
}));

function iso(value: string, end = false) {
  if (!value) return undefined;
  const date = new Date(`${value}T${end ? '23:59:59.999' : '00:00:00'}`);
  return date.toISOString();
}
function date(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short', hour12: false }).format(new Date(value));
}
function shortId(value: string) { return `${value.slice(0, 8)}…${value.slice(-4)}`; }
function toggleType(source: string) {
  filters.types = filters.types.includes(source) ? filters.types.filter((item) => item !== source) : [...filters.types, source];
}
function requestFilters(cursor?: string) {
  return {
    from: iso(filters.from), to: iso(filters.to, true), types: filters.types,
    statuses: filters.statuses.split(',').map((value) => value.trim()).filter(Boolean),
    cursor, limit: 50,
  };
}
async function refresh() {
  if (!filters.patientId || !filters.types.length || loading.value) return;
  loading.value = true; issue.value = null;
  try {
    const lease = await issuePatientTimelineLease(filters.patientId);
    snapshot.value = await loadPatientTimeline(lease, filters.patientId, requestFilters());
    items.value = snapshot.value.items;
  } catch (error) { issue.value = toClinicalIssue(error); snapshot.value = null; items.value = []; }
  finally { loading.value = false; }
}
async function loadMore() {
  const cursor = snapshot.value?.next_cursor;
  if (!cursor || loadingMore.value) return;
  loadingMore.value = true;
  try {
    const lease = await issuePatientTimelineLease(filters.patientId);
    const next = await loadPatientTimeline(lease, filters.patientId, requestFilters(cursor));
    items.value = [...items.value, ...next.items]; snapshot.value = next;
  } catch (error) { issue.value = toClinicalIssue(error); }
  finally { loadingMore.value = false; }
}
onMounted(refresh);
</script>

<template>
  <section data-page-root class="content vue-native-page patient-timeline-page">
    <div class="page-heading timeline-heading"><div><p class="eyebrow">病历中心 / 患者全景</p><h1>授权患者纵向时间线</h1><p>汇总该患者及合并前档案的资料；数据暂时无法加载时会明确提示。</p></div><div class="toolbar-actions"><RouterLink class="button secondary" to="/patient-registry">患者查询</RouterLink><button class="button primary" :disabled="loading" @click="refresh">{{ loading ? '正在加载…' : '重新加载' }}</button></div></div>

    <section class="timeline-filter-card" aria-label="时间线筛选"><label><span>患者 ID</span><input v-model="filters.patientId" /></label><label><span>开始日期</span><input v-model="filters.from" type="date" /></label><label><span>结束日期</span><input v-model="filters.to" type="date" /></label><label><span>状态（逗号分隔）</span><input v-model="filters.statuses" placeholder="SIGNED,ACTIVE" /></label><button class="button secondary" @click="refresh">应用筛选</button></section>
    <section class="timeline-source-picker" aria-label="资料类型"><button v-for="([source,label]) in sourceOptions" :key="source" :class="{ active: filters.types.includes(source) }" @click="toggleType(source)"><span>{{ label }}</span><small>{{ source }}</small></button></section>

    <ClinicalPageState v-if="loading && !snapshot" kind="loading" message="正在按数据范围聚合授权病史" />
    <ClinicalPageState v-else-if="issue && !snapshot" kind="error" :code="issue.code" :message="issue.message" @retry="refresh" />
    <template v-else-if="snapshot">
      <section v-if="partialSources.length" class="timeline-partial-alert" role="alert"><strong>⚠ 当前病史不完整</strong><span>{{ partialSources.map((source) => sourceLabel[source.source]).join('、') }}数据源未加载，其他已授权资料仍可阅读。请重试后再做完整性判断。</span><button @click="refresh">重试失败源</button></section>
      <section class="timeline-source-status"><article v-for="entry in sourceSummary" :key="entry.source" :class="entry.state?.state.toLowerCase()"><div><span>{{ entry.label }}</span><small>{{ entry.source }}</small></div><strong v-if="!entry.selected">未选择</strong><strong v-else-if="entry.state?.state === 'PARTIAL'">加载失败</strong><strong v-else-if="entry.visible === 0">已加载 · 无资料</strong><strong v-else>已加载 {{ entry.visible }} 条</strong></article></section>
      <section class="timeline-integrity-strip"><span :class="snapshot.completeness.toLowerCase()">{{ snapshot.completeness === 'COMPLETE' ? '完整快照' : '部分快照' }}</span><code>水位 {{ snapshot.data_watermark.slice(0, 16) }}</code><span>别名档案 {{ snapshot.patient_alias_ids.length }} 份</span><span>生成于 {{ date(snapshot.generated_at) }}</span></section>
      <div class="timeline-layout"><section class="timeline-stream"><article v-for="item in items" :key="`${item.item_type}-${item.resource_id}`"><div class="timeline-marker" :data-type="item.item_type">{{ sourceLabel[item.item_type]?.slice(0,1) }}</div><div class="timeline-item-body"><header><div><span>{{ sourceLabel[item.item_type] }} · {{ item.status }}</span><strong>{{ item.title }}</strong></div><time>{{ date(item.occurred_at) }}</time></header><p v-if="item.summary">{{ item.summary }}</p><footer><code>资源 {{ shortId(item.resource_id) }}</code><code>患者 {{ shortId(item.patient_id) }}</code><span>{{ item.source_system || '本系统' }} · row v{{ item.row_version }}<template v-if="item.version_no"> · 业务 v{{ item.version_no }}</template></span><RouterLink v-if="item.source_route" :to="item.source_route">打开原记录 →</RouterLink></footer></div></article><div v-if="!items.length" class="timeline-empty"><strong>当前筛选范围内没有已授权资料</strong><span v-if="snapshot.completeness === 'COMPLETE'">所选数据源均已成功查询，这是可确认的空状态。</span><span v-else>仍有失败数据源，不能将此视为完整空病史。</span></div><button v-if="snapshot.next_cursor" class="button secondary timeline-more" :disabled="loadingMore" @click="loadMore">{{ loadingMore ? '加载中…' : '加载更早记录' }}</button></section>
        <aside class="timeline-evidence"><h2>当前查看范围</h2><dl><div><dt>患者档案</dt><dd><code>{{ snapshot.patient_id }}</code></dd></div><div><dt>合并前档案</dt><dd><code v-for="alias in snapshot.patient_alias_ids" :key="alias">{{ alias }}</code></dd></div><div><dt>查看权限</dt><dd>按当前岗位确认</dd></div></dl><p>这里只显示您有权查看的资料。</p></aside></div>
    </template>
  </section>
</template>
