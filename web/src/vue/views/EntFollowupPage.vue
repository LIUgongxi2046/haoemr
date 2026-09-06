<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref } from 'vue';
import type { EntFollowupRecordWire } from '../../generated/contracts';
import { createEntFollowupRecord, issueSpecialtyEncounterLease, issueSpecialtyPatientLease, listEntAirwayRiskHandovers, listEntFollowupRecords } from '../../api/specialty-layers';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';
import { localDateTimeInputValue } from '../date-input';

const patientLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'ent-followup', 'patient-lease'],
  queryFn: () => issueSpecialtyPatientLease('ENT_FOLLOWUP'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const patientLease = computed(() => patientLeaseQuery.data.value);

const encounterLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'ent-followup', 'encounter-lease'],
  queryFn: () => issueSpecialtyEncounterLease('ENT_FOLLOWUP'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const encounterLease = computed(() => encounterLeaseQuery.data.value);

const recordsQuery = useQuery({
  queryKey: ['specialty-layers', 'ent-followup', 'records'],
  queryFn: () => listEntFollowupRecords(patientLease.value!),
  enabled: () => Boolean(patientLease.value),
  retry: false,
});
const handoversQuery = useQuery({ queryKey: ['specialty-layers', 'ent-followup', 'handovers'], queryFn: () => listEntAirwayRiskHandovers(patientLease.value!), enabled: () => Boolean(patientLease.value), retry: false });

const issue = computed(() => (patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value ?? handoversQuery.error.value)
  ? toClinicalIssue(patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value ?? handoversQuery.error.value) : null);
const records = computed(() => recordsQuery.data.value ?? []);
const anyPending = computed(() => patientLeaseQuery.isPending.value || encounterLeaseQuery.isPending.value || recordsQuery.isPending.value || handoversQuery.isPending.value);

const form = reactive({
  airwayHandoverId: '',
  followupReason: '',
  scheduledDate: '',
  attended: false,
  noShowReason: '',
  outcomeNote: '',
  recordedAt: localDateTimeInputValue(),
});
const busy = ref('');
const notice = ref('');

function formatDate(value: string | null | undefined) {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium' }).format(new Date(value)) : '—';
}

async function reload() {
  notice.value = '';
  await Promise.all([recordsQuery.refetch(), handoversQuery.refetch()]);
}

async function createRecord() {
  const lease = encounterLease.value;
  if (!lease || busy.value || !form.airwayHandoverId || !form.followupReason.trim() || !form.scheduledDate) return;
  busy.value = 'create'; notice.value = '';
  try {
    await createEntFollowupRecord(lease, {
      airway_handover_id: form.airwayHandoverId,
      followup_reason: form.followupReason.trim(),
      scheduled_date: new Date(form.scheduledDate).toISOString(),
      attended: form.attended,
      no_show_reason: form.noShowReason.trim() || null,
      outcome_note: form.outcomeNote.trim() || null,
      recorded_at: form.recordedAt,
    });
    form.followupReason = ''; form.scheduledDate = ''; form.attended = false;
    form.noShowReason = ''; form.outcomeNote = '';
    notice.value = '随访记录已创建，随访原因与是否到访已留痕。';
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
        <p class="eyebrow">专科其余层 / 耳鼻喉 · 随访</p>
        <h1>随访记录</h1>
        <p>登记随访原因、预约日期、是否到访与结局备注。</p>
      </div>
      <div class="admin-inline-tools">
        <button class="button secondary" :disabled="Boolean(busy)" @click="reload">刷新</button>
      </div>
    </div>

    <ClinicalPageState v-if="anyPending" kind="loading" message="正在读取随访记录" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reload" />

    <template v-else>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>

      <div class="admin-layout">
        <section class="admin-panel">
          <header>
            <div><h2>随访记录台账</h2><p>当前患者耳鼻喉随访档案。</p></div>
          </header>
          <div v-if="records.length === 0" class="admin-empty" role="status">暂无随访记录，可在右侧新增。</div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>随访原因</th><th>预约日期</th><th>是否到访</th><th>未到访原因</th><th>结局备注</th></tr></thead>
              <tbody>
                <tr v-for="record in records" :key="record.followup_id">
                  <td>{{ record.followup_reason }}</td>
                  <td>{{ formatDate(record.scheduled_date) }}</td>
                  <td>{{ record.attended ? '是' : '否' }}</td>
                  <td>{{ record.no_show_reason ?? '—' }}</td>
                  <td>{{ record.outcome_note ?? '—' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="admin-panel admin-form-panel">
          <header><div><h2>新增随访记录</h2><p>必须关联当前患者同一次就诊的气道风险交接。</p></div></header>
          <form class="admin-form" @submit.prevent="createRecord">
            <label><span>气道风险交接</span><select v-model="form.airwayHandoverId" required><option value="" disabled>请选择气道风险交接</option><option v-for="item in handoversQuery.data.value ?? []" :key="item.handover_id" :value="item.handover_id">{{ item.handover_id }} · {{ item.airway_risk_level }}</option></select></label>
            <label><span>随访原因</span><input v-model="form.followupReason" required /></label>
            <label><span>预约日期</span><input v-model="form.scheduledDate" type="date" required /></label>
            <label><span>记录时间</span><input v-model="form.recordedAt" type="datetime-local" required /></label>
            <label class="checkbox"><input v-model="form.attended" type="checkbox" />已到访</label>
            <label><span>未到访原因</span><input v-model="form.noShowReason" placeholder="可选" /></label>
            <label><span>结局备注</span><textarea v-model="form.outcomeNote" rows="3" placeholder="可选" /></label>
            <button class="button primary full" :disabled="Boolean(busy)">{{ busy === 'create' ? '正在创建…' : '创建随访记录' }}</button>
          </form>
        </section>
      </div>
    </template>
  </section>
</template>
