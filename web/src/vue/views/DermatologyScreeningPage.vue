<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref } from 'vue';
import type { DermatologyBiologicScreeningWire } from '../../generated/contracts';
import { createDermatologyBiologicScreening, issueSpecialtyEncounterLease, issueSpecialtyPatientLease, listDermatologyBiologicScreenings } from '../../api/specialty-layers';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';
import { localDateTimeInputValue } from '../date-input';

const patientLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'dermatology-treatment', 'patient-lease'],
  queryFn: () => issueSpecialtyPatientLease('DERMATOLOGY_SCREENING'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const patientLease = computed(() => patientLeaseQuery.data.value);

const encounterLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'dermatology-treatment', 'encounter-lease'],
  queryFn: () => issueSpecialtyEncounterLease('DERMATOLOGY_SCREENING'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const encounterLease = computed(() => encounterLeaseQuery.data.value);

const recordsQuery = useQuery({
  queryKey: ['specialty-layers', 'dermatology-treatment', 'records'],
  queryFn: () => listDermatologyBiologicScreenings(patientLease.value!),
  enabled: () => Boolean(patientLease.value),
  retry: false,
});

const issue = computed(() => (patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value)
  ? toClinicalIssue(patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value) : null);
const records = computed(() => recordsQuery.data.value ?? []);
const anyPending = computed(() => patientLeaseQuery.isPending.value || encounterLeaseQuery.isPending.value || recordsQuery.isPending.value);

const form = reactive({
  biologicName: '',
  tbScreeningResult: 'NEGATIVE' as DermatologyBiologicScreeningWire['tb_screening_result'],
  hepatitisScreeningResult: 'NEGATIVE' as DermatologyBiologicScreeningWire['hepatitis_screening_result'],
  screenedAt: localDateTimeInputValue(),
});
const busy = ref('');
const notice = ref('');

function screeningResultLabel(value: string) {
  const map: Record<string, string> = { NEGATIVE: '阴性', POSITIVE: '阳性', PENDING: '待定' };
  return map[value] ?? value;
}

function formatDate(value: string | null | undefined) {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—';
}

async function reload() {
  notice.value = '';
  await recordsQuery.refetch();
}

async function createRecord() {
  const lease = encounterLease.value;
  if (!lease || busy.value || !form.biologicName.trim()) return;
  busy.value = 'create'; notice.value = '';
  try {
    await createDermatologyBiologicScreening(lease, {
      biologic_name: form.biologicName.trim(),
      tb_screening_result: form.tbScreeningResult,
      hepatitis_screening_result: form.hepatitisScreeningResult,
      screened_at: form.screenedAt,
    });
    form.biologicName = '';
    notice.value = '生物制剂筛查已创建，结核与肝炎筛查结果已留痕。';
    await recordsQuery.refetch();
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = ''; }
}
</script>

<template>
  <section data-page-root class="content admin-content vue-native-page">
    <div class="page-heading admin-heading">
      <div>
        <p class="eyebrow">专科其余层 / 皮肤 · 治疗</p>
        <h1>生物制剂筛查</h1>
        <p>生物制剂名称、结核与肝炎筛查结果登记；可否启用生物制剂由后端据此判定。</p>
      </div>
      <div class="admin-inline-tools">
        <button class="button secondary" :disabled="Boolean(busy)" @click="reload">刷新</button>
      </div>
    </div>

    <ClinicalPageState v-if="anyPending" kind="loading" message="正在读取生物制剂筛查" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reload" />

    <template v-else>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>

      <div class="admin-layout">
        <section class="admin-panel">
          <header>
            <div><h2>生物制剂筛查台账</h2><p>当前患者生物制剂使用前筛查档案。</p></div>
          </header>
          <div v-if="records.length === 0" class="admin-empty" role="status">暂无筛查记录，可在右侧新增。</div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>生物制剂</th><th>结核筛查</th><th>肝炎筛查</th><th>可否启用</th><th>筛查时间</th></tr></thead>
              <tbody>
                <tr v-for="record in records" :key="record.screening_id">
                  <td>{{ record.biologic_name }}</td>
                  <td>{{ screeningResultLabel(record.tb_screening_result) }}</td>
                  <td>{{ screeningResultLabel(record.hepatitis_screening_result) }}</td>
                  <td>{{ record.cleared_for_biologic ? '是' : '否' }}</td>
                  <td>{{ formatDate(record.screened_at) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="admin-panel admin-form-panel">
          <header><div><h2>新增生物制剂筛查</h2><p>生物制剂名称与筛查结果必填。</p></div></header>
          <form class="admin-form" @submit.prevent="createRecord">
            <label><span>生物制剂名称</span><input v-model="form.biologicName" required /></label>
            <label><span>结核筛查结果</span><select v-model="form.tbScreeningResult">
              <option value="NEGATIVE">阴性</option><option value="POSITIVE">阳性</option><option value="PENDING">待定</option>
            </select></label>
            <label><span>肝炎筛查结果</span><select v-model="form.hepatitisScreeningResult">
              <option value="NEGATIVE">阴性</option><option value="POSITIVE">阳性</option><option value="PENDING">待定</option>
            </select></label>
            <label><span>筛查时间</span><input v-model="form.screenedAt" type="datetime-local" required /></label>
            <button class="button primary full" :disabled="Boolean(busy)">{{ busy === 'create' ? '正在创建…' : '创建生物制剂筛查' }}</button>
          </form>
        </section>
      </div>
    </template>
  </section>
</template>
