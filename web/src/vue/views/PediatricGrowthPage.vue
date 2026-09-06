<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref } from 'vue';
import type { PediatricGrowthRecordWire } from '../../generated/contracts';
import { createPediatricGrowthRecord, issueSpecialtyEncounterLease, issueSpecialtyPatientLease, listPediatricGrowthRecords } from '../../api/specialty-layers';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';
import { localDateTimeInputValue } from '../date-input';

const patientLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'pediatrics-care', 'patient-lease'],
  queryFn: () => issueSpecialtyPatientLease('PEDIATRICS_GROWTH'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const patientLease = computed(() => patientLeaseQuery.data.value);

const encounterLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'pediatrics-care', 'encounter-lease'],
  queryFn: () => issueSpecialtyEncounterLease('PEDIATRICS_GROWTH'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const encounterLease = computed(() => encounterLeaseQuery.data.value);

const recordsQuery = useQuery({
  queryKey: ['specialty-layers', 'pediatrics-care', 'records'],
  queryFn: () => listPediatricGrowthRecords(patientLease.value!),
  enabled: () => Boolean(patientLease.value),
  retry: false,
});

const issue = computed(() => (patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value)
  ? toClinicalIssue(patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value) : null);
const records = computed(() => recordsQuery.data.value ?? []);
const anyPending = computed(() => patientLeaseQuery.isPending.value || encounterLeaseQuery.isPending.value || recordsQuery.isPending.value);

const form = reactive({
  heightCm: 0,
  weightKg: 0,
  headCircumferenceCm: '',
  measuredAt: localDateTimeInputValue(),
});
const busy = ref('');
const notice = ref('');

function formatDate(value: string | null | undefined) {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—';
}

async function reload() {
  notice.value = '';
  await recordsQuery.refetch();
}

async function createRecord() {
  const lease = encounterLease.value;
  if (!lease || busy.value) return;
  busy.value = 'create'; notice.value = '';
  try {
    await createPediatricGrowthRecord(lease, {
      height_cm: form.heightCm,
      weight_kg: form.weightKg,
      head_circumference_cm: String(form.headCircumferenceCm).trim() === '' ? null : Number(form.headCircumferenceCm),
      measured_at: form.measuredAt,
    });
    form.heightCm = 0; form.weightKg = 0; form.headCircumferenceCm = '';
    notice.value = '生长发育记录已创建，身高、体重与头围已留痕。';
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
        <p class="eyebrow">专科其余层 / 儿科 · 照护</p>
        <h1>生长发育记录</h1>
        <p>登记身高、体重、头围与测量时间。</p>
      </div>
      <div class="admin-inline-tools">
        <button class="button secondary" :disabled="Boolean(busy)" @click="reload">刷新</button>
      </div>
    </div>

    <ClinicalPageState v-if="anyPending" kind="loading" message="正在读取生长发育记录" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reload" />

    <template v-else>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>

      <div class="admin-layout">
        <section class="admin-panel">
          <header>
            <div><h2>生长发育台账</h2><p>当前患者儿科生长发育档案。</p></div>
          </header>
          <div v-if="records.length === 0" class="admin-empty" role="status">暂无生长发育记录，可在右侧新增。</div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>身高(cm)</th><th>体重(kg)</th><th>头围(cm)</th><th>测量时间</th></tr></thead>
              <tbody>
                <tr v-for="record in records" :key="record.growth_record_id">
                  <td>{{ record.height_cm }}</td>
                  <td>{{ record.weight_kg }}</td>
                  <td>{{ record.head_circumference_cm ?? '—' }}</td>
                  <td>{{ formatDate(record.measured_at) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="admin-panel admin-form-panel">
          <header><div><h2>新增生长发育记录</h2><p>身高与体重必填，头围可选。</p></div></header>
          <form class="admin-form" @submit.prevent="createRecord">
            <label><span>身高（cm）</span><input v-model.number="form.heightCm" type="number" min="0" step="0.1" required /></label>
            <label><span>体重（kg）</span><input v-model.number="form.weightKg" type="number" min="0" step="0.1" required /></label>
            <label><span>头围（cm）</span><input v-model.number="form.headCircumferenceCm" type="number" min="0" step="0.1" placeholder="可选" /></label>
            <label><span>测量时间</span><input v-model="form.measuredAt" type="datetime-local" required /></label>
            <button class="button primary full" :disabled="Boolean(busy)">{{ busy === 'create' ? '正在创建…' : '创建生长发育记录' }}</button>
          </form>
        </section>
      </div>
    </template>
  </section>
</template>
