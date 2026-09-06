<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref } from 'vue';
import type { NeonatalScreeningRecordWire } from '../../generated/contracts';
import { createNeonatalScreeningRecord, issueSpecialtyEncounterLease, issueSpecialtyPatientLease, listNeonatalScreeningRecords } from '../../api/specialty-layers';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';

const patientLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'neonatal-evidence', 'patient-lease'],
  queryFn: () => issueSpecialtyPatientLease('NEONATAL_SCREENING'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const patientLease = computed(() => patientLeaseQuery.data.value);

const encounterLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'neonatal-evidence', 'encounter-lease'],
  queryFn: () => issueSpecialtyEncounterLease('NEONATAL_SCREENING'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const encounterLease = computed(() => encounterLeaseQuery.data.value);

const recordsQuery = useQuery({
  queryKey: ['specialty-layers', 'neonatal-evidence', 'records'],
  queryFn: () => listNeonatalScreeningRecords(patientLease.value!),
  enabled: () => Boolean(patientLease.value),
  retry: false,
});

const issue = computed(() => (patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value)
  ? toClinicalIssue(patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value) : null);
const records = computed(() => recordsQuery.data.value ?? []);
const anyPending = computed(() => patientLeaseQuery.isPending.value || encounterLeaseQuery.isPending.value || recordsQuery.isPending.value);

const form = reactive({
  motherPatientId: '',
  screeningType: 'HEARING' as NeonatalScreeningRecordWire['screening_type'],
  screeningResult: 'PASS' as NeonatalScreeningRecordWire['screening_result'],
  referredTo: '',
  screenedAt: new Date().toISOString(),
});
const busy = ref('');
const notice = ref('');

function screeningTypeLabel(value: string) {
  const map: Record<string, string> = {
    HEARING: '听力筛查', METABOLIC: '代谢病筛查', CONGENITAL_HEART: '先天性心脏病筛查',
  };
  return map[value] ?? value;
}

function screeningResultLabel(value: string) {
  const map: Record<string, string> = { PASS: '通过', REFER: '转诊', PENDING: '待定' };
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
  if (!lease || busy.value || !form.motherPatientId.trim()) return;
  busy.value = 'create'; notice.value = '';
  try {
    await createNeonatalScreeningRecord(lease, {
      mother_patient_id: form.motherPatientId.trim(),
      screening_type: form.screeningType,
      screening_result: form.screeningResult,
      referred_to: form.referredTo.trim() || null,
      screened_at: form.screenedAt,
    });
    form.motherPatientId = ''; form.referredTo = '';
    notice.value = '筛查记录已创建，筛查类型与结果已留痕。';
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
        <p class="eyebrow">专科其余层 / 新生儿 · 证据</p>
        <h1>新生儿筛查记录</h1>
        <p>登记听力、代谢病和先心病的筛查类型与结果。</p>
      </div>
      <div class="admin-inline-tools">
        <button class="button secondary" :disabled="Boolean(busy)" @click="reload">刷新</button>
      </div>
    </div>

    <ClinicalPageState v-if="anyPending" kind="loading" message="正在读取筛查记录" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reload" />

    <template v-else>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>

      <div class="admin-layout">
        <section class="admin-panel">
          <header>
            <div><h2>筛查记录台账</h2><p>当前患者新生儿筛查档案。</p></div>
          </header>
          <div v-if="records.length === 0" class="admin-empty" role="status">暂无筛查记录，可在右侧新增。</div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>母亲ID</th><th>筛查类型</th><th>筛查结果</th><th>转诊至</th><th>筛查时间</th></tr></thead>
              <tbody>
                <tr v-for="record in records" :key="record.screening_id">
                  <td>{{ record.mother_patient_id }}</td>
                  <td>{{ screeningTypeLabel(record.screening_type) }}</td>
                  <td>{{ screeningResultLabel(record.screening_result) }}</td>
                  <td>{{ record.referred_to ?? '—' }}</td>
                  <td>{{ formatDate(record.screened_at) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="admin-panel admin-form-panel">
          <header><div><h2>新增筛查记录</h2><p>母亲ID、筛查类型与结果必填。</p></div></header>
          <form class="admin-form" @submit.prevent="createRecord">
            <label><span>母亲ID</span><input v-model="form.motherPatientId" required placeholder="UUID" /></label>
            <label><span>筛查类型</span><select v-model="form.screeningType">
              <option value="HEARING">听力筛查</option>
              <option value="METABOLIC">代谢病筛查</option>
              <option value="CONGENITAL_HEART">先天性心脏病筛查</option>
            </select></label>
            <label><span>筛查结果</span><select v-model="form.screeningResult">
              <option value="PASS">通过</option><option value="REFER">转诊</option><option value="PENDING">待定</option>
            </select></label>
            <label><span>筛查时间</span><input v-model="form.screenedAt" type="datetime-local" required /></label>
            <label><span>转诊至</span><input v-model="form.referredTo" placeholder="可选" /></label>
            <button class="button primary full" :disabled="Boolean(busy)">{{ busy === 'create' ? '正在创建…' : '创建筛查记录' }}</button>
          </form>
        </section>
      </div>
    </template>
  </section>
</template>
