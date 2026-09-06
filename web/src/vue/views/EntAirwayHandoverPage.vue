<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref } from 'vue';
import type { EntAirwayRiskHandoverWire } from '../../generated/contracts';
import { createEntAirwayRiskHandover, issueSpecialtyEncounterLease, issueSpecialtyPatientLease, listEntAirwayRiskHandovers } from '../../api/specialty-layers';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';
import { localDateTimeInputValue } from '../date-input';

const patientLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'ent-care', 'patient-lease'],
  queryFn: () => issueSpecialtyPatientLease('ENT_AIRWAY_HANDOVER'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const patientLease = computed(() => patientLeaseQuery.data.value);

const encounterLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'ent-care', 'encounter-lease'],
  queryFn: () => issueSpecialtyEncounterLease('ENT_AIRWAY_HANDOVER'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const encounterLease = computed(() => encounterLeaseQuery.data.value);

const recordsQuery = useQuery({
  queryKey: ['specialty-layers', 'ent-care', 'records'],
  queryFn: () => listEntAirwayRiskHandovers(patientLease.value!),
  enabled: () => Boolean(patientLease.value),
  retry: false,
});

const issue = computed(() => (patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value)
  ? toClinicalIssue(patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value) : null);
const records = computed(() => recordsQuery.data.value ?? []);
const anyPending = computed(() => patientLeaseQuery.isPending.value || encounterLeaseQuery.isPending.value || recordsQuery.isPending.value);

const form = reactive({
  airwayRiskLevel: 'MODERATE' as EntAirwayRiskHandoverWire['airway_risk_level'],
  airwayPrecautions: '',
  toProviderId: '',
  handedOverAt: localDateTimeInputValue(),
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
  if (!lease || busy.value || !form.airwayPrecautions.trim() || !form.toProviderId.trim()) return;
  busy.value = 'create'; notice.value = '';
  try {
    await createEntAirwayRiskHandover(lease, {
      airway_risk_level: form.airwayRiskLevel,
      airway_precautions: form.airwayPrecautions.trim(),
      to_provider_id: form.toProviderId.trim(),
      handed_over_at: form.handedOverAt,
    });
    form.airwayPrecautions = ''; form.toProviderId = '';
    notice.value = '气道风险交接已创建，风险等级与气道注意事项已留痕。';
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
        <p class="eyebrow">专科其余层 / 耳鼻喉 · 照护</p>
        <h1>气道风险交接</h1>
        <p>登记气道风险等级、注意事项与接诊人员。</p>
      </div>
      <div class="admin-inline-tools">
        <button class="button secondary" :disabled="Boolean(busy)" @click="reload">刷新</button>
      </div>
    </div>

    <ClinicalPageState v-if="anyPending" kind="loading" message="正在读取气道风险交接" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reload" />

    <template v-else>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>

      <div class="admin-layout">
        <section class="admin-panel">
          <header>
            <div><h2>气道风险交接台账</h2><p>当前患者气道风险交接档案。</p></div>
          </header>
          <div v-if="records.length === 0" class="admin-empty" role="status">暂无气道风险交接，可在右侧新增。</div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>风险等级</th><th>气道注意事项</th><th>接诊提供者ID</th><th>交接时间</th></tr></thead>
              <tbody>
                <tr v-for="record in records" :key="record.handover_id">
                  <td>{{ record.airway_risk_level === 'HIGH' ? '高' : '中' }}</td>
                  <td>{{ record.airway_precautions }}</td>
                  <td>{{ record.to_provider_id }}</td>
                  <td>{{ formatDate(record.handed_over_at) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="admin-panel admin-form-panel">
          <header><div><h2>新增气道风险交接</h2><p>风险等级、气道注意事项与接诊提供者ID必填。</p></div></header>
          <form class="admin-form" @submit.prevent="createRecord">
            <label><span>风险等级</span><select v-model="form.airwayRiskLevel">
              <option value="MODERATE">中</option><option value="HIGH">高</option>
            </select></label>
            <label><span>气道注意事项</span><textarea v-model="form.airwayPrecautions" rows="3" required /></label>
            <label><span>接诊提供者ID</span><input v-model="form.toProviderId" required placeholder="UUID" /></label>
            <label><span>交接时间</span><input v-model="form.handedOverAt" type="datetime-local" required /></label>
            <button class="button primary full" :disabled="Boolean(busy)">{{ busy === 'create' ? '正在创建…' : '创建气道风险交接' }}</button>
          </form>
        </section>
      </div>
    </template>
  </section>
</template>
