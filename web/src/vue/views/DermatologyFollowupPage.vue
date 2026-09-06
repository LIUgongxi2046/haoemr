<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref } from 'vue';
import type { DermatologyBiologicFollowupWire } from '../../generated/contracts';
import { createDermatologyBiologicFollowup, issueSpecialtyEncounterLease, issueSpecialtyPatientLease, listDermatologyBiologicFollowups } from '../../api/specialty-layers';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';

const patientLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'dermatology-followup', 'patient-lease'],
  queryFn: () => issueSpecialtyPatientLease('DERMATOLOGY_FOLLOWUP'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const patientLease = computed(() => patientLeaseQuery.data.value);

const encounterLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'dermatology-followup', 'encounter-lease'],
  queryFn: () => issueSpecialtyEncounterLease('DERMATOLOGY_FOLLOWUP'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const encounterLease = computed(() => encounterLeaseQuery.data.value);

const recordsQuery = useQuery({
  queryKey: ['specialty-layers', 'dermatology-followup', 'records'],
  queryFn: () => listDermatologyBiologicFollowups(patientLease.value!),
  enabled: () => Boolean(patientLease.value),
  retry: false,
});

const issue = computed(() => (patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value)
  ? toClinicalIssue(patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value) : null);
const records = computed(() => recordsQuery.data.value ?? []);
const anyPending = computed(() => patientLeaseQuery.isPending.value || encounterLeaseQuery.isPending.value || recordsQuery.isPending.value);

const form = reactive({
  biologicName: '',
  followupDate: '',
  pasiScore: 0,
  adverseEvent: false,
  adverseEventDescription: '',
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
  if (!lease || busy.value || !form.biologicName.trim() || !form.followupDate) return;
  busy.value = 'create'; notice.value = '';
  try {
    await createDermatologyBiologicFollowup(lease, {
      biologic_name: form.biologicName.trim(),
      followup_date: form.followupDate,
      pasi_score: form.pasiScore,
      adverse_event: form.adverseEvent,
      adverse_event_description: form.adverseEventDescription.trim() || null,
      recorded_at: form.recordedAt,
    });
    form.biologicName = ''; form.followupDate = ''; form.pasiScore = 0;
    form.adverseEvent = false; form.adverseEventDescription = '';
    notice.value = '生物制剂随访已创建，PASI 评分与不良事件已留痕。';
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
        <p class="eyebrow">专科其余层 / 皮肤 · 随访</p>
        <h1>生物制剂随访</h1>
        <p>登记生物制剂名称、随访日期、PASI 评分与不良事件。</p>
      </div>
      <div class="admin-inline-tools">
        <button class="button secondary" :disabled="Boolean(busy)" @click="reload">刷新</button>
      </div>
    </div>

    <ClinicalPageState v-if="anyPending" kind="loading" message="正在读取生物制剂随访" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reload" />

    <template v-else>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>

      <div class="admin-layout">
        <section class="admin-panel">
          <header>
            <div><h2>生物制剂随访台账</h2><p>当前患者生物制剂随访档案。</p></div>
          </header>
          <div v-if="records.length === 0" class="admin-empty" role="status">暂无随访记录，可在右侧新增。</div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>生物制剂</th><th>随访日期</th><th>PASI 评分</th><th>不良事件</th><th>描述</th></tr></thead>
              <tbody>
                <tr v-for="record in records" :key="record.followup_id">
                  <td>{{ record.biologic_name }}</td>
                  <td>{{ formatDate(record.followup_date) }}</td>
                  <td>{{ record.pasi_score }}</td>
                  <td>{{ record.adverse_event ? '是' : '否' }}</td>
                  <td>{{ record.adverse_event_description ?? '—' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="admin-panel admin-form-panel">
          <header><div><h2>新增生物制剂随访</h2><p>生物制剂名称、随访日期与 PASI 评分必填。</p></div></header>
          <form class="admin-form" @submit.prevent="createRecord">
            <label><span>生物制剂名称</span><input v-model="form.biologicName" required /></label>
            <label><span>随访日期</span><input v-model="form.followupDate" type="date" required /></label>
            <label><span>PASI 评分</span><input v-model.number="form.pasiScore" type="number" min="0" step="0.1" required /></label>
            <label><span>记录时间</span><input v-model="form.recordedAt" type="datetime-local" required /></label>
            <label class="checkbox"><input v-model="form.adverseEvent" type="checkbox" />发生不良事件</label>
            <label><span>不良事件描述</span><textarea v-model="form.adverseEventDescription" rows="3" placeholder="可选" /></label>
            <button class="button primary full" :disabled="Boolean(busy)">{{ busy === 'create' ? '正在创建…' : '创建生物制剂随访' }}</button>
          </form>
        </section>
      </div>
    </template>
  </section>
</template>
