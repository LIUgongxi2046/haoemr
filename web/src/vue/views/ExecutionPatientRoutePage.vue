<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, defineAsyncComponent, provide, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { issueExecutionWorklistLease, listExecutionWorklist } from '../../api/execution-center';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';
import {
  activateExecutionPatient,
  executionPatientSelectionKey,
  executionRouteConfigs,
  filterExecutionPatientRows,
  mapExecutionWorklist,
  type ExecutionPatientRow,
} from '../execution-patient-flow';

const detailComponents = {
  'care-operations': defineAsyncComponent(() => import('./CareOperationsPage.vue')),
  billing: defineAsyncComponent(() => import('./BillingPage.vue')),
  'outpatient-pharmacy': defineAsyncComponent(() => import('./OutpatientPharmacyPage.vue')),
  'inpatient-pharmacy': defineAsyncComponent(() => import('./InpatientPharmacyPage.vue')),
  'lab-workbench': defineAsyncComponent(() => import('./LabWorkbenchPage.vue')),
  'pathology-workbench': defineAsyncComponent(() => import('./PathologyWorkbenchPage.vue')),
  'imaging-workbench': defineAsyncComponent(() => import('./ImagingWorkbenchPage.vue')),
  'therapy-workbench': defineAsyncComponent(() => import('./TherapyWorkbenchPage.vue')),
  'surgery-schedule': defineAsyncComponent(() => import('./SurgerySchedulePage.vue')),
  'anesthesia-workbench': defineAsyncComponent(() => import('./AnesthesiaWorkbenchPage.vue')),
  transfusion: defineAsyncComponent(() => import('./TransfusionPage.vue')),
  'device-monitoring': defineAsyncComponent(() => import('./DeviceMonitoringPage.vue')),
} as const;

import { clinicalContext } from '../../clinical-api';
const route = useRoute();
const routeId = computed(() => String(route.meta.contractId ?? route.name ?? 'care-operations'));
const config = computed(() => executionRouteConfigs[routeId.value] ?? executionRouteConfigs['care-operations']);
const detailComponent = computed(() => detailComponents[routeId.value as keyof typeof detailComponents]);
const selected = ref<ExecutionPatientRow | null>(null);
const search = ref('');
const status = ref('ALL');

provide(executionPatientSelectionKey, {
  selected,
  config,
  returnToList: () => { selected.value = null; },
});

watch(routeId, () => {
  selected.value = null;
  search.value = '';
  status.value = 'ALL';
});

const queueQuery = useQuery({
  queryKey: computed(() => ['execution-patient-flow', routeId.value, config.value.domain, clinicalContext.userId, clinicalContext.roleId, clinicalContext.facilityId]),
  queryFn: async () => {
    const lease = await issueExecutionWorklistLease(config.value.domain);
    return mapExecutionWorklist(await listExecutionWorklist(lease, config.value.domain));
  },
  retry: false,
  staleTime: 30_000,
  gcTime: 0,
});

const rows = computed(() => queueQuery.data.value ?? []);
const statusOptions = computed(() => Array.from(new Set(rows.value.map((row) => row.status))));
const filteredRows = computed(() => filterExecutionPatientRows(rows.value, search.value, status.value));
const waitingCount = computed(() => rows.value.filter((row) => row.pendingCount > 0).length);
const waitingTaskCount = computed(() => rows.value.reduce((sum, row) => sum + row.pendingCount, 0));
const overdueCount = computed(() => rows.value.reduce((sum, row) => sum + row.overdueCount, 0));
const completedCount = computed(() => rows.value.filter((row) => ['COMPLETED', 'READY'].includes(row.status)).length);
const issue = computed(() => queueQuery.error.value ? toClinicalIssue(queueQuery.error.value) : null);

function statusLabel(value: string) {
  return ({
    WAITING: '候诊', CALLED: '已叫号', IN_CONSULTATION: '诊中', COMPLETED: '已完成', SKIPPED: '已过号',
    OVERDUE: '有超时', PENDING: '有待办', READY: '待接收', CRITICAL: '危急优先',
  } as Record<string, string>)[value] ?? value;
}

function sexLabel(value: string | null) {
  return ({ M: '男', F: '女', MALE: '男', FEMALE: '女' } as Record<string, string>)[value ?? ''] ?? '—';
}

function ageLabel(birthDate: string | null) {
  if (!birthDate) return '—';
  const birth = new Date(birthDate);
  if (Number.isNaN(birth.getTime())) return '—';
  const now = new Date();
  let age = now.getFullYear() - birth.getFullYear();
  if (now.getMonth() < birth.getMonth() || (now.getMonth() === birth.getMonth() && now.getDate() < birth.getDate())) age -= 1;
  return `${Math.max(age, 0)}岁`;
}

function selectPatient(row: ExecutionPatientRow) {
  activateExecutionPatient(row);
  selected.value = row;
}
</script>

<template>
  <section v-if="!selected" data-page-root data-execution-patient-list class="content vue-native-page execution-queue-page">
    <div class="page-head execution-page-head">
      <div class="page-title">
        <div class="eyebrow">诊疗执行中心 · 患者任务下转</div>
        <h1>{{ config.title }}</h1>
        <p>{{ config.subtitle }}</p>
      </div>
      <button class="queue-button secondary" type="button" :disabled="queueQuery.isFetching.value" @click="queueQuery.refetch()">
        {{ queueQuery.isFetching.value ? '刷新中…' : '刷新列表' }}
      </button>
    </div>

    <ClinicalPageState v-if="queueQuery.isPending.value" kind="loading" message="正在加载当前院区可下转患者队列" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="queueQuery.refetch()" />
    <template v-else>
      <div class="queue-metrics" aria-label="患者执行队列指标">
        <article><span>当前队列患者</span><strong>{{ rows.length }}</strong><small>来自当前授权范围</small></article>
        <article><span>待执行任务</span><strong>{{ waitingTaskCount }}</strong><small>当前队列 {{ waitingTaskCount }} 项 · {{ waitingCount }} 位患者待处理</small></article>
        <article><span>超时任务</span><strong :class="{ danger: overdueCount > 0 }">{{ overdueCount }}</strong><small>需优先下转处理</small></article>
        <article><span>已完成队列患者</span><strong>{{ completedCount }}</strong><small>支持返回查看留痕</small></article>
      </div>

      <section class="queue-card">
        <div class="queue-toolbar">
          <div>
            <h2>{{ config.title }}患者列表</h2>
            <p>先检索并确认患者，再进入该患者的执行工作台。</p>
          </div>
          <div class="queue-filters">
            <label>
              <span>患者检索</span>
              <input v-model="search" type="search" placeholder="姓名 / 患者ID / 就诊ID" />
            </label>
            <label>
              <span>执行状态</span>
              <select v-model="status">
                <option value="ALL">全部状态</option>
                <option v-for="option in statusOptions" :key="option" :value="option">{{ statusLabel(option) }}</option>
              </select>
            </label>
          </div>
        </div>

        <div class="queue-table-wrap">
          <table class="queue-table">
            <colgroup>
              <col class="col-sequence" />
              <col class="col-patient" />
              <col class="col-visit" />
              <col class="col-location" />
              <col class="col-task" />
              <col class="col-count" />
              <col class="col-status" />
              <col class="col-action" />
            </colgroup>
            <thead><tr><th>序号</th><th>患者</th><th>就诊类型</th><th>位置</th><th>待执行项目</th><th>任务数</th><th>状态</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="row in filteredRows" :key="row.key" data-execution-patient-row>
                <td><span class="sequence">{{ row.sequenceNo ?? '—' }}</span></td>
                <td><div class="patient-cell"><strong>{{ row.patientDisplayName }}</strong><span>{{ sexLabel(row.sexCode) }} · {{ ageLabel(row.birthDate) }}</span><small>{{ row.patientId }}</small></div></td>
                <td><span class="visit-chip" :class="row.visitType === '住院' ? 'inpatient' : 'outpatient'">{{ row.visitType }}</span></td>
                <td>{{ row.location }}</td>
                <td><strong>{{ row.taskLabel }}</strong><small class="encounter-id">就诊 {{ row.encounterId }}</small></td>
                <td>{{ row.pendingCount }}</td>
                <td><span class="status-chip" :class="row.status.toLowerCase()">{{ statusLabel(row.status) }}</span></td>
                <td><button class="queue-button primary" type="button" data-select-execution-patient @click="selectPatient(row)">选择患者并下转</button></td>
              </tr>
              <tr v-if="filteredRows.length === 0"><td class="empty-cell" colspan="8">当前专业队列没有符合条件的真实任务；请先签署相应医嘱或创建业务申请</td></tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>
  </section>

  <section v-else data-execution-patient-detail class="execution-detail-shell">
    <Suspense>
      <component :is="detailComponent" :key="selected.key" />
      <template #fallback>
        <div class="detail-loading">
          <ClinicalPageState kind="loading" :message="`正在打开${config.title}工作台并校验患者上下文`" />
        </div>
      </template>
    </Suspense>
  </section>
</template>

<style scoped>
.execution-queue-page { display: grid; min-width: 0; align-content: start; grid-auto-rows: max-content; gap: 18px; }
.execution-page-head { align-items: flex-start; gap: 20px; }
.eyebrow { margin-bottom: 7px; color: #0b6bcb; font-size: 12px; font-weight: 750; letter-spacing: .08em; }
.queue-metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }
.queue-metrics article { min-width: 0; padding: 17px 18px; border: 1px solid #dfe7ef; border-radius: 12px; background: #fff; box-shadow: 0 4px 14px rgba(24, 55, 82, .04); }
.queue-metrics span, .queue-metrics small { display: block; color: #617184; }
.queue-metrics strong { display: block; margin: 7px 0 4px; color: #172b3f; font-size: 27px; line-height: 1; }
.queue-metrics strong.danger { color: #c73b36; }
.queue-card { min-width: 0; overflow: hidden; border: 1px solid #dfe7ef; border-radius: 13px; background: #fff; box-shadow: 0 5px 18px rgba(24, 55, 82, .05); }
.queue-toolbar { display: grid; grid-template-columns: minmax(220px, 1fr) auto; align-items: end; gap: 20px; padding: 18px 20px; border-bottom: 1px solid #e7edf3; }
.queue-toolbar h2 { margin: 0 0 5px; color: #182c40; font-size: 17px; }
.queue-toolbar p { margin: 0; color: #6b7a8a; font-size: 13px; }
.queue-filters { display: grid; grid-template-columns: minmax(230px, 270px) 160px; align-items: end; gap: 12px; }
.queue-filters label { display: grid; min-width: 0; gap: 6px; color: #536579; font-size: 12px; font-weight: 650; }
.queue-filters input, .queue-filters select { width: 100%; height: 36px; min-width: 0; border: 1px solid #cfd9e3; border-radius: 8px; padding: 0 10px; background: #fff; color: #213548; }
.queue-table-wrap { height: clamp(360px, 44vh, 520px); overflow: auto; }
.queue-table { width: 100%; min-width: 1080px; table-layout: fixed; border-collapse: collapse; color: #263a4d; font-size: 13px; }
.queue-table .col-sequence { width: 66px; }
.queue-table .col-patient { width: 180px; }
.queue-table .col-visit { width: 90px; }
.queue-table .col-location { width: 120px; }
.queue-table .col-task { width: 220px; }
.queue-table .col-count { width: 72px; }
.queue-table .col-status { width: 92px; }
.queue-table .col-action { width: 158px; }
.queue-table th { padding: 11px 14px; background: #f6f8fb; color: #5a6c7e; font-size: 12px; font-weight: 700; text-align: left; white-space: nowrap; }
.queue-table td { overflow: hidden; padding: 13px 14px; border-top: 1px solid #edf1f5; vertical-align: middle; }
.queue-table tbody tr:hover { background: #f8fbff; }
.sequence { display: inline-grid; width: 28px; height: 28px; place-items: center; border-radius: 50%; background: #eef5fc; color: #1769aa; font-weight: 750; }
.patient-cell { display: grid; gap: 3px; min-width: 155px; }
.patient-cell strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.patient-cell span, .patient-cell small, .encounter-id { color: #738293; font-size: 11px; }
.patient-cell small, .encounter-id { max-width: 175px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.encounter-id { display: block; margin-top: 4px; }
.visit-chip, .status-chip { display: inline-flex; align-items: center; border-radius: 999px; padding: 4px 9px; font-size: 12px; font-weight: 700; white-space: nowrap; }
.visit-chip.outpatient { background: #eaf4ff; color: #1769aa; }
.visit-chip.inpatient { background: #f1edff; color: #6147b8; }
.status-chip { background: #edf2f7; color: #56687a; }
.status-chip.waiting, .status-chip.pending, .status-chip.called { background: #fff4d8; color: #936719; }
.status-chip.in_consultation { background: #e9f4ff; color: #126ba8; }
.status-chip.completed, .status-chip.ready { background: #e8f7ef; color: #21734b; }
.status-chip.overdue, .status-chip.skipped { background: #ffeded; color: #b43535; }
.queue-button { min-height: 36px; border: 1px solid #bfd0df; border-radius: 8px; padding: 7px 13px; font-weight: 700; cursor: pointer; white-space: nowrap; }
.queue-button.secondary { background: #fff; color: #31506d; }
.queue-button.primary { border-color: #0b6bcb; background: #0b6bcb; color: #fff; }
.queue-button:hover:not(:disabled) { filter: brightness(.97); }
.queue-button:disabled { cursor: not-allowed; opacity: .55; }
.empty-cell { padding: 42px !important; color: #748397; text-align: center; }
.execution-detail-shell { display: grid; min-width: 0; }
.detail-loading { margin: 0 18px; }
.execution-detail-shell :deep(.patient-strip) { display: none; }
@media (max-width: 1050px) {
  .queue-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .queue-toolbar { grid-template-columns: 1fr; align-items: stretch; }
  .queue-filters { grid-template-columns: minmax(230px, 1fr) 160px; }
}
@media (max-width: 1350px) {
  .queue-table { min-width: 880px; }
  .queue-table .col-sequence { width: 52px; }
  .queue-table .col-patient { width: 150px; }
  .queue-table .col-visit { width: 72px; }
  .queue-table .col-location { width: 92px; }
  .queue-table .col-task { width: 190px; }
  .queue-table .col-count { width: 56px; }
  .queue-table .col-status { width: 76px; }
  .queue-table .col-action { width: 142px; }
  .queue-table th, .queue-table td { padding-inline: 10px; }
}
@media (max-width: 700px) {
  .queue-metrics { grid-template-columns: 1fr; }
  .queue-filters { grid-template-columns: 1fr; align-items: stretch; }
}
</style>
