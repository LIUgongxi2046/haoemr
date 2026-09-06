<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref } from 'vue';
import type { ObstetricAntenatalExamWire } from '../../generated/contracts';
import { createObstetricAntenatalExam, issueSpecialtyEncounterLease, issueSpecialtyPatientLease, listObstetricAntenatalExams } from '../../api/specialty-layers';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';
import { localDateTimeInputValue } from '../date-input';

const patientLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'obgyn-evidence', 'patient-lease'],
  queryFn: () => issueSpecialtyPatientLease('OBGYN_ANTENATAL'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const patientLease = computed(() => patientLeaseQuery.data.value);

const encounterLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'obgyn-evidence', 'encounter-lease'],
  queryFn: () => issueSpecialtyEncounterLease('OBGYN_ANTENATAL'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const encounterLease = computed(() => encounterLeaseQuery.data.value);

const recordsQuery = useQuery({
  queryKey: ['specialty-layers', 'obgyn-evidence', 'records'],
  queryFn: () => listObstetricAntenatalExams(patientLease.value!),
  enabled: () => Boolean(patientLease.value),
  retry: false,
});

const issue = computed(() => (patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value)
  ? toClinicalIssue(patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value) : null);
const records = computed(() => recordsQuery.data.value ?? []);
const anyPending = computed(() => patientLeaseQuery.isPending.value || encounterLeaseQuery.isPending.value || recordsQuery.isPending.value);

const form = reactive({
  gestationalWeeks: 0,
  fundalHeightCm: '',
  fetalHeartRate: '',
  systolicBp: 0,
  diastolicBp: 0,
  proteinuria: 'NEGATIVE' as ObstetricAntenatalExamWire['proteinuria'],
  preeclampsiaRisk: false,
  examinedAt: localDateTimeInputValue(),
});
const busy = ref('');
const notice = ref('');

function proteinuriaLabel(value: string) {
  const map: Record<string, string> = { NEGATIVE: '阴性', TRACE: '微量', POSITIVE: '阳性' };
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
  if (!lease || busy.value) return;
  busy.value = 'create'; notice.value = '';
  try {
    await createObstetricAntenatalExam(lease, {
      gestational_weeks: form.gestationalWeeks,
      fundal_height_cm: String(form.fundalHeightCm).trim() === '' ? null : Number(form.fundalHeightCm),
      fetal_heart_rate: String(form.fetalHeartRate).trim() === '' ? null : Number(form.fetalHeartRate),
      systolic_bp: form.systolicBp,
      diastolic_bp: form.diastolicBp,
      proteinuria: form.proteinuria,
      preeclampsia_risk: form.preeclampsiaRisk,
      examined_at: form.examinedAt,
    });
    form.gestationalWeeks = 0; form.fundalHeightCm = ''; form.fetalHeartRate = '';
    form.systolicBp = 0; form.diastolicBp = 0; form.preeclampsiaRisk = false;
    notice.value = '产前检查已创建，孕周、血压与子痫前期风险已留痕。';
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
        <p class="eyebrow">专科其余层 / 妇产 · 证据</p>
        <h1>产前检查</h1>
        <p>登记孕周、宫高、胎心、血压、蛋白尿与子痫前期风险。</p>
      </div>
      <div class="admin-inline-tools">
        <button class="button secondary" :disabled="Boolean(busy)" @click="reload">刷新</button>
      </div>
    </div>

    <ClinicalPageState v-if="anyPending" kind="loading" message="正在读取产前检查" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reload" />

    <template v-else>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>

      <div class="admin-layout">
        <section class="admin-panel">
          <header>
            <div><h2>产前检查台账</h2><p>当前患者产前检查档案。</p></div>
          </header>
          <div v-if="records.length === 0" class="admin-empty" role="status">暂无产前检查，可在右侧新增。</div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>孕周</th><th>宫高(cm)</th><th>胎心</th><th>收缩压</th><th>舒张压</th><th>蛋白尿</th><th>子痫风险</th><th>检查时间</th></tr></thead>
              <tbody>
                <tr v-for="record in records" :key="record.exam_id">
                  <td>{{ record.gestational_weeks }}</td>
                  <td>{{ record.fundal_height_cm ?? '—' }}</td>
                  <td>{{ record.fetal_heart_rate ?? '—' }}</td>
                  <td>{{ record.systolic_bp }}</td>
                  <td>{{ record.diastolic_bp }}</td>
                  <td>{{ proteinuriaLabel(record.proteinuria) }}</td>
                  <td>{{ record.preeclampsia_risk ? '是' : '否' }}</td>
                  <td>{{ formatDate(record.examined_at) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="admin-panel admin-form-panel">
          <header><div><h2>新增产前检查</h2><p>孕周、收缩压与舒张压必填。</p></div></header>
          <form class="admin-form" @submit.prevent="createRecord">
            <label><span>孕周</span><input v-model.number="form.gestationalWeeks" type="number" min="0" required /></label>
            <label><span>宫高（cm）</span><input v-model.number="form.fundalHeightCm" type="number" step="0.1" placeholder="可选" /></label>
            <label><span>胎心（次/分）</span><input v-model.number="form.fetalHeartRate" type="number" min="0" placeholder="可选" /></label>
            <label><span>收缩压</span><input v-model.number="form.systolicBp" type="number" min="0" required /></label>
            <label><span>舒张压</span><input v-model.number="form.diastolicBp" type="number" min="0" required /></label>
            <label><span>蛋白尿</span><select v-model="form.proteinuria">
              <option value="NEGATIVE">阴性</option><option value="TRACE">微量</option><option value="POSITIVE">阳性</option>
            </select></label>
            <label><span>检查时间</span><input v-model="form.examinedAt" type="datetime-local" required /></label>
            <label class="checkbox"><input v-model="form.preeclampsiaRisk" type="checkbox" />子痫前期风险</label>
            <button class="button primary full" :disabled="Boolean(busy)">{{ busy === 'create' ? '正在创建…' : '创建产前检查' }}</button>
          </form>
        </section>
      </div>
    </template>
  </section>
</template>
