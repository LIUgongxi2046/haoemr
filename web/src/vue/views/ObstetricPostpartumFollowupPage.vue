<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref } from 'vue';
import type { ObstetricPostpartumFollowupWire } from '../../generated/contracts';
import { createObstetricPostpartumFollowup, issueSpecialtyEncounterLease, issueSpecialtyPatientLease, listObstetricPostpartumFollowups } from '../../api/specialty-layers';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';

const patientLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'obgyn-followup', 'patient-lease'],
  queryFn: () => issueSpecialtyPatientLease('OBGYN_POSTPARTUM'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const patientLease = computed(() => patientLeaseQuery.data.value);

const encounterLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'obgyn-followup', 'encounter-lease'],
  queryFn: () => issueSpecialtyEncounterLease('OBGYN_POSTPARTUM'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const encounterLease = computed(() => encounterLeaseQuery.data.value);

const recordsQuery = useQuery({
  queryKey: ['specialty-layers', 'obgyn-followup', 'records'],
  queryFn: () => listObstetricPostpartumFollowups(patientLease.value!),
  enabled: () => Boolean(patientLease.value),
  retry: false,
});

const issue = computed(() => (patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value)
  ? toClinicalIssue(patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value) : null);
const records = computed(() => recordsQuery.data.value ?? []);
const anyPending = computed(() => patientLeaseQuery.isPending.value || encounterLeaseQuery.isPending.value || recordsQuery.isPending.value);

const form = reactive({
  followupDate: '',
  lochiaStatus: 'NORMAL' as ObstetricPostpartumFollowupWire['lochia_status'],
  woundHealing: 'GOOD' as ObstetricPostpartumFollowupWire['wound_healing'],
  complications: '',
  recordedAt: new Date().toISOString(),
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
  if (!lease || busy.value || !form.followupDate) return;
  busy.value = 'create'; notice.value = '';
  try {
    await createObstetricPostpartumFollowup(lease, {
      followup_date: form.followupDate,
      lochia_status: form.lochiaStatus,
      wound_healing: form.woundHealing,
      complications: form.complications.trim() || null,
      recorded_at: form.recordedAt,
    });
    form.followupDate = ''; form.complications = '';
    notice.value = '产后随访已创建，恶露状态与伤口愈合情况已留痕。';
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
        <p class="eyebrow">专科其余层 / 妇产 · 随访</p>
        <h1>产后随访</h1>
        <p>登记随访日期、恶露状态、伤口愈合与并发症。</p>
      </div>
      <div class="admin-inline-tools">
        <button class="button secondary" :disabled="Boolean(busy)" @click="reload">刷新</button>
      </div>
    </div>

    <ClinicalPageState v-if="anyPending" kind="loading" message="正在读取产后随访" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reload" />

    <template v-else>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>

      <div class="admin-layout">
        <section class="admin-panel">
          <header>
            <div><h2>产后随访台账</h2><p>当前患者产后随访档案。</p></div>
          </header>
          <div v-if="records.length === 0" class="admin-empty" role="status">暂无产后随访，可在右侧新增。</div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>随访日期</th><th>恶露状态</th><th>伤口愈合</th><th>并发症</th><th>记录时间</th></tr></thead>
              <tbody>
                <tr v-for="record in records" :key="record.followup_id">
                  <td>{{ formatDate(record.followup_date) }}</td>
                  <td>{{ record.lochia_status === 'NORMAL' ? '正常' : '异常' }}</td>
                  <td>{{ record.wound_healing === 'GOOD' ? '良好' : '有并发症' }}</td>
                  <td>{{ record.complications ?? '—' }}</td>
                  <td>{{ formatDate(record.recorded_at) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="admin-panel admin-form-panel">
          <header><div><h2>新增产后随访</h2><p>随访日期、恶露状态与伤口愈合必填。</p></div></header>
          <form class="admin-form" @submit.prevent="createRecord">
            <label><span>随访日期</span><input v-model="form.followupDate" type="date" required /></label>
            <label><span>恶露状态</span><select v-model="form.lochiaStatus">
              <option value="NORMAL">正常</option><option value="ABNORMAL">异常</option>
            </select></label>
            <label><span>伤口愈合</span><select v-model="form.woundHealing">
              <option value="GOOD">良好</option><option value="COMPLICATED">有并发症</option>
            </select></label>
            <label><span>记录时间</span><input v-model="form.recordedAt" type="datetime-local" required /></label>
            <label><span>并发症</span><textarea v-model="form.complications" rows="3" placeholder="可选" /></label>
            <button class="button primary full" :disabled="Boolean(busy)">{{ busy === 'create' ? '正在创建…' : '创建产后随访' }}</button>
          </form>
        </section>
      </div>
    </template>
  </section>
</template>
