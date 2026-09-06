<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref } from 'vue';
import type { NeonatalWristbandVerificationWire } from '../../generated/contracts';
import { createNeonatalWristbandVerification, issueSpecialtyEncounterLease, issueSpecialtyPatientLease, listNeonatalWristbandVerifications } from '../../api/specialty-layers';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';
import { localDateTimeInputValue } from '../date-input';

const patientLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'neonatal-care', 'patient-lease'],
  queryFn: () => issueSpecialtyPatientLease('NEONATAL_WRISTBAND'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const patientLease = computed(() => patientLeaseQuery.data.value);

const encounterLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'neonatal-care', 'encounter-lease'],
  queryFn: () => issueSpecialtyEncounterLease('NEONATAL_WRISTBAND'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const encounterLease = computed(() => encounterLeaseQuery.data.value);

const recordsQuery = useQuery({
  queryKey: ['specialty-layers', 'neonatal-care', 'records'],
  queryFn: () => listNeonatalWristbandVerifications(patientLease.value!),
  enabled: () => Boolean(patientLease.value),
  retry: false,
});

const issue = computed(() => (patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value)
  ? toClinicalIssue(patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value) : null);
const records = computed(() => recordsQuery.data.value ?? []);
const anyPending = computed(() => patientLeaseQuery.isPending.value || encounterLeaseQuery.isPending.value || recordsQuery.isPending.value);

const form = reactive({
  motherPatientId: '',
  wristbandCode: '',
  specimenCode: '',
  witnessedBy: '',
  verifiedAt: localDateTimeInputValue(),
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
  const lease = patientLease.value;
  if (!lease || busy.value || !form.motherPatientId.trim() || !form.wristbandCode.trim()
    || !form.specimenCode.trim() || !form.witnessedBy.trim()) return;
  busy.value = 'create'; notice.value = '';
  try {
    await createNeonatalWristbandVerification(lease, {
      mother_patient_id: form.motherPatientId.trim(),
      wristband_code: form.wristbandCode.trim(),
      specimen_code: form.specimenCode.trim(),
      witnessed_by: form.witnessedBy.trim(),
      verified_at: form.verifiedAt,
    });
    form.motherPatientId = ''; form.wristbandCode = ''; form.specimenCode = ''; form.witnessedBy = '';
    notice.value = '腕带核对已创建，腕带码与标本码双人核对已留痕。';
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
        <p class="eyebrow">专科其余层 / 新生儿 · 照护</p>
        <h1>腕带核对</h1>
        <p>母亲、腕带码与标本码双人核对登记；按患者上下文留痕审计链。</p>
      </div>
      <div class="admin-inline-tools">
        <button class="button secondary" :disabled="Boolean(busy)" @click="reload">刷新</button>
      </div>
    </div>

    <ClinicalPageState v-if="anyPending" kind="loading" message="正在读取腕带核对" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reload" />

    <template v-else>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>

      <div class="admin-layout">
        <section class="admin-panel">
          <header>
            <div><h2>腕带核对台账</h2><p>当前患者新生儿腕带核对档案。</p></div>
          </header>
          <div v-if="records.length === 0" class="admin-empty" role="status">暂无腕带核对，可在右侧新增。</div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>母亲ID</th><th>腕带码</th><th>标本码</th><th>核对者ID</th><th>见证者ID</th><th>核对时间</th></tr></thead>
              <tbody>
                <tr v-for="record in records" :key="record.verification_id">
                  <td>{{ record.mother_patient_id }}</td>
                  <td>{{ record.wristband_code }}</td>
                  <td>{{ record.specimen_code }}</td>
                  <td>{{ record.verified_by }}</td>
                  <td>{{ record.witnessed_by }}</td>
                  <td>{{ formatDate(record.verified_at) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="admin-panel admin-form-panel">
          <header><div><h2>新增腕带核对</h2><p>母亲ID、腕带码、标本码与见证者ID必填。</p></div></header>
          <form class="admin-form" @submit.prevent="createRecord">
            <label><span>母亲ID</span><input v-model="form.motherPatientId" required placeholder="UUID" /></label>
            <label><span>腕带码</span><input v-model="form.wristbandCode" required /></label>
            <label><span>标本码</span><input v-model="form.specimenCode" required /></label>
            <label><span>见证者ID</span><input v-model="form.witnessedBy" required placeholder="UUID" /></label>
            <label><span>核对时间</span><input v-model="form.verifiedAt" type="datetime-local" required /></label>
            <button class="button primary full" :disabled="Boolean(busy)">{{ busy === 'create' ? '正在创建…' : '创建腕带核对' }}</button>
          </form>
        </section>
      </div>
    </template>
  </section>
</template>
