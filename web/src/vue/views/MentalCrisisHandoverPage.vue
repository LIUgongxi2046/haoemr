<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref } from 'vue';
import type { MentalHealthCrisisHandoverWire } from '../../generated/contracts';
import { createMentalHealthCrisisHandover, issueSpecialtyEncounterLease, issueSpecialtyPatientLease, listMentalHealthCrisisHandovers } from '../../api/specialty-layers';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';
import { localDateTimeInputValue } from '../date-input';

const patientLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'mental-care', 'patient-lease'],
  queryFn: () => issueSpecialtyPatientLease('MENTAL_CRISIS_HANDOVER'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const patientLease = computed(() => patientLeaseQuery.data.value);

const encounterLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'mental-care', 'encounter-lease'],
  queryFn: () => issueSpecialtyEncounterLease('MENTAL_CRISIS_HANDOVER'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const encounterLease = computed(() => encounterLeaseQuery.data.value);

const recordsQuery = useQuery({
  queryKey: ['specialty-layers', 'mental-care', 'records'],
  queryFn: () => listMentalHealthCrisisHandovers(patientLease.value!),
  enabled: () => Boolean(patientLease.value),
  retry: false,
});

const issue = computed(() => (patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value)
  ? toClinicalIssue(patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value) : null);
const records = computed(() => recordsQuery.data.value ?? []);
const anyPending = computed(() => patientLeaseQuery.isPending.value || encounterLeaseQuery.isPending.value || recordsQuery.isPending.value);

const form = reactive({
  toProviderId: '',
  crisisReason: '',
  riskLevel: 'LOW' as MentalHealthCrisisHandoverWire['risk_level'],
  protectiveMeasures: '',
  handedOverAt: localDateTimeInputValue(),
});
const busy = ref('');
const notice = ref('');

function riskLevelLabel(value: string) {
  const map: Record<string, string> = {
    NONE: '无', LOW: '低', MODERATE: '中', HIGH: '高', IMMINENT: '即刻',
  };
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
  if (!lease || busy.value || !form.toProviderId.trim() || !form.crisisReason.trim()) return;
  busy.value = 'create'; notice.value = '';
  try {
    await createMentalHealthCrisisHandover(lease, {
      to_provider_id: form.toProviderId.trim(),
      crisis_reason: form.crisisReason.trim(),
      risk_level: form.riskLevel,
      protective_measures: form.protectiveMeasures.trim() || null,
      handed_over_at: form.handedOverAt,
    });
    form.toProviderId = ''; form.crisisReason = ''; form.protectiveMeasures = '';
    notice.value = '危机交接已创建，风险等级与保护措施已留痕（受限数据）。';
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
        <p class="eyebrow">专科其余层 / 精神心理 · 照护</p>
        <h1>心理危机交接</h1>
        <p>接诊提供者、危机原因、风险等级与保护措施登记；数据分类为受限（RESTRICTED）。</p>
      </div>
      <div class="admin-inline-tools">
        <button class="button secondary" :disabled="Boolean(busy)" @click="reload">刷新</button>
      </div>
    </div>

    <ClinicalPageState v-if="anyPending" kind="loading" message="正在读取危机交接" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reload" />

    <template v-else>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>

      <div class="admin-layout">
        <section class="admin-panel">
          <header>
            <div><h2>危机交接台账</h2><p>当前患者心理危机交接档案。</p></div>
          </header>
          <div v-if="records.length === 0" class="admin-empty" role="status">暂无危机交接，可在右侧新增。</div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>接诊提供者</th><th>危机原因</th><th>风险等级</th><th>保护措施</th><th>交接时间</th></tr></thead>
              <tbody>
                <tr v-for="record in records" :key="record.crisis_handover_id">
                  <td>{{ record.to_provider_id }}</td>
                  <td>{{ record.crisis_reason }}</td>
                  <td>{{ riskLevelLabel(record.risk_level) }}</td>
                  <td>{{ record.protective_measures ?? '—' }}</td>
                  <td>{{ formatDate(record.handed_over_at) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="admin-panel admin-form-panel">
          <header><div><h2>新增危机交接</h2><p>接诊提供者ID与危机原因必填。</p></div></header>
          <form class="admin-form" @submit.prevent="createRecord">
            <label><span>接诊提供者ID</span><input v-model="form.toProviderId" required placeholder="UUID" /></label>
            <label><span>危机原因</span><textarea v-model="form.crisisReason" rows="3" required /></label>
            <label><span>风险等级</span><select v-model="form.riskLevel">
              <option value="LOW">低</option><option value="MODERATE">中</option>
              <option value="HIGH">高</option><option value="IMMINENT">即刻</option>
            </select></label>
            <label><span>交接时间</span><input v-model="form.handedOverAt" type="datetime-local" required /></label>
            <label><span>保护措施</span><textarea v-model="form.protectiveMeasures" rows="3" placeholder="可选" /></label>
            <button class="button primary full" :disabled="Boolean(busy)">{{ busy === 'create' ? '正在创建…' : '创建危机交接' }}</button>
          </form>
        </section>
      </div>
    </template>
  </section>
</template>
