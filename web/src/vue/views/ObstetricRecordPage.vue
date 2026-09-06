<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref } from 'vue';
import type { ObstetricRecordWire } from '../../generated/contracts';
import { createObstetricRecord, issueSpecialtyEncounterLease, issueSpecialtyPatientLease, listObstetricRecords } from '../../api/specialty';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';

const patientLeaseQuery = useQuery({
  queryKey: ['specialty', 'obgyn-record', 'patient-lease'],
  queryFn: () => issueSpecialtyPatientLease('OBGYN_RECORD'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const patientLease = computed(() => patientLeaseQuery.data.value);

const encounterLeaseQuery = useQuery({
  queryKey: ['specialty', 'obgyn-record', 'encounter-lease'],
  queryFn: () => issueSpecialtyEncounterLease('OBGYN_RECORD'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const encounterLease = computed(() => encounterLeaseQuery.data.value);

const recordsQuery = useQuery({
  queryKey: ['specialty', 'obgyn-record', 'records'],
  queryFn: () => listObstetricRecords(patientLease.value!),
  enabled: () => Boolean(patientLease.value),
  retry: false,
});

const issue = computed(() => (patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value)
  ? toClinicalIssue(patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value) : null);
const records = computed(() => recordsQuery.data.value ?? []);
const anyPending = computed(() => patientLeaseQuery.isPending.value || encounterLeaseQuery.isPending.value || recordsQuery.isPending.value);

const form = reactive({
  gravidity: 0,
  parity: 0,
  gestationalWeeks: 0,
  estimatedDueDate: '',
  bloodGroup: 'A_POS',
  rhFactor: 'POSITIVE',
  highRiskFactors: '',
});
const busy = ref('');
const notice = ref('');

function formatDate(value: string | null | undefined) {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium' }).format(new Date(value)) : '—';
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
    await createObstetricRecord(lease, {
      gravidity: form.gravidity,
      parity: form.parity,
      gestational_weeks: form.gestationalWeeks,
      estimated_due_date: form.estimatedDueDate || null,
      blood_group: form.bloodGroup as ObstetricRecordWire['blood_group'],
      rh_factor: form.rhFactor as ObstetricRecordWire['rh_factor'],
      high_risk_factors: form.highRiskFactors.trim(),
    });
    form.gravidity = 0; form.parity = 0; form.gestationalWeeks = 0;
    form.estimatedDueDate = ''; form.highRiskFactors = '';
    notice.value = '妇产记录已创建，风险因素与预产期已留痕。';
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
        <p class="eyebrow">专科记录 / 妇产</p>
        <h1>妇产记录</h1>
        <p>登记孕产次、孕周、预产期、血型与高危因素。</p>
      </div>
      <div class="admin-inline-tools">
        <button class="button secondary" :disabled="Boolean(busy)" @click="reload">刷新</button>
      </div>
    </div>

    <ClinicalPageState v-if="anyPending" kind="loading" message="正在读取妇产记录" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reload" />

    <template v-else>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>

      <div class="admin-layout">
        <section class="admin-panel">
          <header>
            <div><h2>妇产记录台账</h2><p>当前患者产科档案，状态 ACTIVE / COMPLETED。</p></div>
          </header>
          <div v-if="records.length === 0" class="admin-empty" role="status">暂无妇产记录，可在右侧新增。</div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>孕次</th><th>产次</th><th>孕周</th><th>预产期</th><th>血型</th><th>Rh</th><th>高危因素</th><th>状态</th></tr></thead>
              <tbody>
                <tr v-for="record in records" :key="record.obstetric_record_id">
                  <td>{{ record.gravidity }}</td>
                  <td>{{ record.parity }}</td>
                  <td>{{ record.gestational_weeks }}</td>
                  <td>{{ formatDate(record.estimated_due_date) }}</td>
                  <td>{{ record.blood_group }}</td>
                  <td>{{ record.rh_factor === 'POSITIVE' ? '阳性' : '阴性' }}</td>
                  <td>{{ record.high_risk_factors }}</td>
                  <td><span class="admin-status" :class="record.status.toLowerCase()">{{ record.status === 'ACTIVE' ? '有效' : '已完成' }}</span></td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="admin-panel admin-form-panel">
          <header><div><h2>新增妇产记录</h2><p>孕次、产次、孕周、血型、Rh 与高危因素必填。</p></div></header>
          <form class="admin-form" @submit.prevent="createRecord">
            <label><span>孕次（G）</span><input v-model.number="form.gravidity" type="number" min="0" required /></label>
            <label><span>产次（P）</span><input v-model.number="form.parity" type="number" min="0" required /></label>
            <label><span>孕周</span><input v-model.number="form.gestationalWeeks" type="number" min="0" required /></label>
            <label><span>预产期</span><input v-model="form.estimatedDueDate" type="datetime-local" /></label>
            <label><span>血型</span><select v-model="form.bloodGroup">
              <option value="A_POS">A型阳性</option><option value="A_NEG">A型阴性</option>
              <option value="B_POS">B型阳性</option><option value="B_NEG">B型阴性</option>
              <option value="AB_POS">AB型阳性</option><option value="AB_NEG">AB型阴性</option>
              <option value="O_POS">O型阳性</option><option value="O_NEG">O型阴性</option>
            </select></label>
            <label><span>Rh 因子</span><select v-model="form.rhFactor"><option value="POSITIVE">阳性</option><option value="NEGATIVE">阴性</option></select></label>
            <label><span>高危因素</span><textarea v-model="form.highRiskFactors" rows="3" required /></label>
            <button class="button primary full" :disabled="Boolean(busy)">{{ busy === 'create' ? '正在创建…' : '创建妇产记录' }}</button>
          </form>
        </section>
      </div>
    </template>
  </section>
</template>
