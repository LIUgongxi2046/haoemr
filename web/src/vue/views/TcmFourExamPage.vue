<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref } from 'vue';
import type { TcmFourExaminationsWire } from '../../generated/contracts';
import { createTcmFourExaminations, issueSpecialtyEncounterLease, issueSpecialtyPatientLease, listTcmFourExaminations } from '../../api/specialty-layers';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';

const patientLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'tcm-evidence', 'patient-lease'],
  queryFn: () => issueSpecialtyPatientLease('TCM_FOUR_EXAM'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const patientLease = computed(() => patientLeaseQuery.data.value);

const encounterLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'tcm-evidence', 'encounter-lease'],
  queryFn: () => issueSpecialtyEncounterLease('TCM_FOUR_EXAM'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const encounterLease = computed(() => encounterLeaseQuery.data.value);

const recordsQuery = useQuery({
  queryKey: ['specialty-layers', 'tcm-evidence', 'records'],
  queryFn: () => listTcmFourExaminations(patientLease.value!),
  enabled: () => Boolean(patientLease.value),
  retry: false,
});

const issue = computed(() => (patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value)
  ? toClinicalIssue(patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value) : null);
const records = computed(() => recordsQuery.data.value ?? []);
const anyPending = computed(() => patientLeaseQuery.isPending.value || encounterLeaseQuery.isPending.value || recordsQuery.isPending.value);

const form = reactive({
  inspection: '',
  auscultation: '',
  inquiry: '',
  palpation: '',
  examinedAt: new Date().toISOString(),
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
  if (!lease || busy.value || !form.inspection.trim() || !form.auscultation.trim()
    || !form.inquiry.trim() || !form.palpation.trim()) return;
  busy.value = 'create'; notice.value = '';
  try {
    await createTcmFourExaminations(lease, {
      inspection: form.inspection.trim(),
      auscultation: form.auscultation.trim(),
      inquiry: form.inquiry.trim(),
      palpation: form.palpation.trim(),
      examined_at: form.examinedAt,
    });
    form.inspection = ''; form.auscultation = ''; form.inquiry = ''; form.palpation = '';
    notice.value = '四诊记录已创建，望、闻、问、切已留痕。';
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
        <p class="eyebrow">专科其余层 / 中医 · 证据</p>
        <h1>四诊记录</h1>
        <p>登记望、闻、问、切四诊信息与检查时间。</p>
      </div>
      <div class="admin-inline-tools">
        <button class="button secondary" :disabled="Boolean(busy)" @click="reload">刷新</button>
      </div>
    </div>

    <ClinicalPageState v-if="anyPending" kind="loading" message="正在读取四诊记录" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reload" />

    <template v-else>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>

      <div class="admin-layout">
        <section class="admin-panel">
          <header>
            <div><h2>四诊记录台账</h2><p>当前患者中医四诊档案。</p></div>
          </header>
          <div v-if="records.length === 0" class="admin-empty" role="status">暂无四诊记录，可在右侧新增。</div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>望诊</th><th>闻诊</th><th>问诊</th><th>切诊</th><th>检查时间</th></tr></thead>
              <tbody>
                <tr v-for="record in records" :key="record.exam_id">
                  <td>{{ record.inspection }}</td>
                  <td>{{ record.auscultation }}</td>
                  <td>{{ record.inquiry }}</td>
                  <td>{{ record.palpation }}</td>
                  <td>{{ formatDate(record.examined_at) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="admin-panel admin-form-panel">
          <header><div><h2>新增四诊记录</h2><p>望、闻、问、切四诊信息必填。</p></div></header>
          <form class="admin-form" @submit.prevent="createRecord">
            <label><span>望诊</span><textarea v-model="form.inspection" rows="2" required placeholder="望神色形态、舌象等" /></label>
            <label><span>闻诊</span><textarea v-model="form.auscultation" rows="2" required placeholder="闻声息、气味等" /></label>
            <label><span>问诊</span><textarea v-model="form.inquiry" rows="2" required placeholder="问寒热、汗、饮食、二便等" /></label>
            <label><span>切诊</span><textarea v-model="form.palpation" rows="2" required placeholder="切脉象、按胸腹等" /></label>
            <label><span>检查时间</span><input v-model="form.examinedAt" type="datetime-local" required /></label>
            <button class="button primary full" :disabled="Boolean(busy)">{{ busy === 'create' ? '正在创建…' : '创建四诊记录' }}</button>
          </form>
        </section>
      </div>
    </template>
  </section>
</template>
