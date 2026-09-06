<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref } from 'vue';
import type { MentalHealthCrisisFollowupWire } from '../../generated/contracts';
import { createMentalHealthCrisisFollowup, issueSpecialtyEncounterLease, issueSpecialtyPatientLease, listMentalHealthCrisisFollowups, listMentalHealthCrisisHandovers } from '../../api/specialty-layers';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';
import { localDateTimeInputValue } from '../date-input';

const patientLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'mental-followup', 'patient-lease'],
  queryFn: () => issueSpecialtyPatientLease('MENTAL_CRISIS_FOLLOWUP'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const patientLease = computed(() => patientLeaseQuery.data.value);

const encounterLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'mental-followup', 'encounter-lease'],
  queryFn: () => issueSpecialtyEncounterLease('MENTAL_CRISIS_FOLLOWUP'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const encounterLease = computed(() => encounterLeaseQuery.data.value);

const recordsQuery = useQuery({
  queryKey: ['specialty-layers', 'mental-followup', 'records'],
  queryFn: () => listMentalHealthCrisisFollowups(patientLease.value!),
  enabled: () => Boolean(patientLease.value),
  retry: false,
});
const handoversQuery = useQuery({ queryKey: ['specialty-layers', 'mental-followup', 'handovers'], queryFn: () => listMentalHealthCrisisHandovers(patientLease.value!), enabled: () => Boolean(patientLease.value), retry: false });

const issue = computed(() => (patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value ?? handoversQuery.error.value)
  ? toClinicalIssue(patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value ?? handoversQuery.error.value) : null);
const records = computed(() => recordsQuery.data.value ?? []);
const anyPending = computed(() => patientLeaseQuery.isPending.value || encounterLeaseQuery.isPending.value || recordsQuery.isPending.value || handoversQuery.isPending.value);

const form = reactive({
  crisisHandoverId: '',
  followupDate: '',
  riskLevel: 'NONE' as MentalHealthCrisisFollowupWire['risk_level'],
  protectiveMeasures: '',
  recordedAt: localDateTimeInputValue(),
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
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium' }).format(new Date(value)) : '—';
}

async function reload() {
  notice.value = '';
  await Promise.all([recordsQuery.refetch(), handoversQuery.refetch()]);
}

async function createRecord() {
  const lease = encounterLease.value;
  if (!lease || busy.value || !form.crisisHandoverId || !form.followupDate) return;
  busy.value = 'create'; notice.value = '';
  try {
    await createMentalHealthCrisisFollowup(lease, {
      crisis_handover_id: form.crisisHandoverId,
      followup_date: new Date(form.followupDate).toISOString(),
      risk_level: form.riskLevel,
      protective_measures: form.protectiveMeasures.trim() || null,
      recorded_at: form.recordedAt,
    });
    form.followupDate = ''; form.protectiveMeasures = '';
    notice.value = '危机随访已创建，风险等级与保护措施已留痕（受限数据）。';
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
        <p class="eyebrow">专科其余层 / 精神心理 · 随访</p>
        <h1>危机随访</h1>
        <p>随访日期、风险等级与保护措施登记；数据分类为受限（RESTRICTED）。</p>
      </div>
      <div class="admin-inline-tools">
        <button class="button secondary" :disabled="Boolean(busy)" @click="reload">刷新</button>
      </div>
    </div>

    <ClinicalPageState v-if="anyPending" kind="loading" message="正在读取危机随访" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reload" />

    <template v-else>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>

      <div class="admin-layout">
        <section class="admin-panel">
          <header>
            <div><h2>危机随访台账</h2><p>当前患者心理危机随访档案。</p></div>
          </header>
          <div v-if="records.length === 0" class="admin-empty" role="status">暂无危机随访，可在右侧新增。</div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>随访日期</th><th>风险等级</th><th>保护措施</th><th>记录时间</th></tr></thead>
              <tbody>
                <tr v-for="record in records" :key="record.followup_id">
                  <td>{{ formatDate(record.followup_date) }}</td>
                  <td>{{ riskLevelLabel(record.risk_level) }}</td>
                  <td>{{ record.protective_measures ?? '—' }}</td>
                  <td>{{ formatDate(record.recorded_at) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="admin-panel admin-form-panel">
          <header><div><h2>新增危机随访</h2><p>必须关联当前患者同一次就诊的危机交接。</p></div></header>
          <form class="admin-form" @submit.prevent="createRecord">
            <label><span>危机交接</span><select v-model="form.crisisHandoverId" required><option value="" disabled>请选择危机交接</option><option v-for="item in handoversQuery.data.value ?? []" :key="item.crisis_handover_id" :value="item.crisis_handover_id">{{ item.crisis_handover_id }} · {{ item.risk_level }}</option></select></label>
            <label><span>随访日期</span><input v-model="form.followupDate" type="date" required /></label>
            <label><span>风险等级</span><select v-model="form.riskLevel">
              <option value="NONE">无</option><option value="LOW">低</option>
              <option value="MODERATE">中</option><option value="HIGH">高</option>
              <option value="IMMINENT">即刻</option>
            </select></label>
            <label><span>记录时间</span><input v-model="form.recordedAt" type="datetime-local" required /></label>
            <label><span>保护措施</span><textarea v-model="form.protectiveMeasures" rows="3" placeholder="可选" /></label>
            <button class="button primary full" :disabled="Boolean(busy)">{{ busy === 'create' ? '正在创建…' : '创建危机随访' }}</button>
          </form>
        </section>
      </div>
    </template>
  </section>
</template>
