<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref } from 'vue';
import type { OphthalmologyPreopVerificationWire } from '../../generated/contracts';
import { createOphthalmologyPreopVerification, issueSpecialtyEncounterLease, issueSpecialtyPatientLease, listOphthalmologyPreopVerifications } from '../../api/specialty-layers';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';

const patientLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'ophthalmology-treatment', 'patient-lease'],
  queryFn: () => issueSpecialtyPatientLease('OPHTHALMOLOGY_PREOP'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const patientLease = computed(() => patientLeaseQuery.data.value);

const encounterLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'ophthalmology-treatment', 'encounter-lease'],
  queryFn: () => issueSpecialtyEncounterLease('OPHTHALMOLOGY_PREOP'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const encounterLease = computed(() => encounterLeaseQuery.data.value);

const recordsQuery = useQuery({
  queryKey: ['specialty-layers', 'ophthalmology-treatment', 'records'],
  queryFn: () => listOphthalmologyPreopVerifications(patientLease.value!),
  enabled: () => Boolean(patientLease.value),
  retry: false,
});

const issue = computed(() => (patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value)
  ? toClinicalIssue(patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value) : null);
const records = computed(() => recordsQuery.data.value ?? []);
const anyPending = computed(() => patientLeaseQuery.isPending.value || encounterLeaseQuery.isPending.value || recordsQuery.isPending.value);

const form = reactive({
  surgicalEye: 'OD' as OphthalmologyPreopVerificationWire['surgical_eye'],
  witnessedBy: '',
  verifiedAt: new Date().toISOString(),
});
const busy = ref('');
const notice = ref('');

function surgicalEyeLabel(value: string) {
  const map: Record<string, string> = { OD: '右眼', OS: '左眼', OU: '双眼' };
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
  if (!lease || busy.value || !form.witnessedBy.trim()) return;
  busy.value = 'create'; notice.value = '';
  try {
    await createOphthalmologyPreopVerification(lease, {
      surgical_eye: form.surgicalEye,
      witnessed_by: form.witnessedBy.trim(),
      verified_at: form.verifiedAt,
    });
    form.witnessedBy = '';
    notice.value = '术前核对已创建，手术眼别与双人核对已留痕。';
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
        <p class="eyebrow">专科其余层 / 眼科 · 治疗</p>
        <h1>术前核对</h1>
        <p>登记手术眼别及见证者双人核对结果。</p>
      </div>
      <div class="admin-inline-tools">
        <button class="button secondary" :disabled="Boolean(busy)" @click="reload">刷新</button>
      </div>
    </div>

    <ClinicalPageState v-if="anyPending" kind="loading" message="正在读取术前核对" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reload" />

    <template v-else>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>

      <div class="admin-layout">
        <section class="admin-panel">
          <header>
            <div><h2>术前核对台账</h2><p>当前患者眼科术前核对档案。</p></div>
          </header>
          <div v-if="records.length === 0" class="admin-empty" role="status">暂无术前核对，可在右侧新增。</div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>手术眼别</th><th>核对者ID</th><th>见证者ID</th><th>核对时间</th></tr></thead>
              <tbody>
                <tr v-for="record in records" :key="record.verification_id">
                  <td>{{ surgicalEyeLabel(record.surgical_eye) }}</td>
                  <td>{{ record.verified_by }}</td>
                  <td>{{ record.witnessed_by }}</td>
                  <td>{{ formatDate(record.verified_at) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="admin-panel admin-form-panel">
          <header><div><h2>新增术前核对</h2><p>手术眼别与见证者ID必填。</p></div></header>
          <form class="admin-form" @submit.prevent="createRecord">
            <label><span>手术眼别</span><select v-model="form.surgicalEye">
              <option value="OD">右眼</option><option value="OS">左眼</option><option value="OU">双眼</option>
            </select></label>
            <label><span>见证者ID</span><input v-model="form.witnessedBy" required placeholder="UUID" /></label>
            <label><span>核对时间</span><input v-model="form.verifiedAt" type="datetime-local" required /></label>
            <button class="button primary full" :disabled="Boolean(busy)">{{ busy === 'create' ? '正在创建…' : '创建术前核对' }}</button>
          </form>
        </section>
      </div>
    </template>
  </section>
</template>
