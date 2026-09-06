<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref } from 'vue';

import { createMentalHealthEvidence, issueSpecialtyEncounterLease, issueSpecialtyPatientLease, listMentalHealthEvidences } from '../../api/specialty-layers';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';
import { localDateTimeInputValue } from '../date-input';

const patientLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'mental-evidence', 'patient-lease'],
  queryFn: () => issueSpecialtyPatientLease('MENTAL_EVIDENCE'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const patientLease = computed(() => patientLeaseQuery.data.value);
const encounterLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'mental-evidence', 'encounter-lease'],
  queryFn: () => issueSpecialtyEncounterLease('MENTAL_EVIDENCE'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const encounterLease = computed(() => encounterLeaseQuery.data.value);
const itemsQuery = useQuery({
  queryKey: ['specialty-layers', 'mental-evidence', 'items'],
  queryFn: () => listMentalHealthEvidences(patientLease.value!),
  enabled: () => Boolean(patientLease.value), retry: false,
});
const issue = computed(() => (patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? itemsQuery.error.value)
  ? toClinicalIssue(patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? itemsQuery.error.value) : null);
const items = computed(() => itemsQuery.data.value ?? []);
const riskCount = computed(() => items.value.filter((i) => i.risk_flag).length);

const form = reactive({ assessment: '', intervention: '', risk_flag: false, recorded_at: localDateTimeInputValue() });
const busy = ref(false);
const notice = ref('');

function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short', hour12: false }).format(new Date(value));
}

async function reload() {
  notice.value = '';
  await itemsQuery.refetch();
}

async function createNote() {
  const lease = encounterLease.value;
  if (!lease || busy.value || !form.assessment.trim() || !form.intervention.trim()) return;
  busy.value = true; notice.value = '';
  try {
    await createMentalHealthEvidence(lease, {
      assessment: form.assessment.trim(),
      intervention: form.intervention.trim(),
      risk_flag: form.risk_flag,
      recorded_at: new Date(form.recorded_at).toISOString(),
    });
    notice.value = form.risk_flag ? '诊疗证据已保存并标记高风险。' : '诊疗证据已保存。';
    form.assessment = ''; form.intervention = ''; form.risk_flag = false;
    await itemsQuery.refetch();
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = false; }
}
</script>

<template>
  <section data-page-root class="content admin-content vue-native-page">
    <div class="page-heading admin-heading">
      <div>
        <p class="eyebrow">专科中心 / 精神心理</p>
        <h1>精神心理诊疗证据</h1>
        <p>记录量表、精神检查、危机风险与干预证据；高风险结果保留为受限风险标记，复核与随访须由临床人员发起。</p>
      </div>
      
    </div>

    <ClinicalPageState v-if="patientLeaseQuery.isPending.value || encounterLeaseQuery.isPending.value || itemsQuery.isPending.value" kind="loading" message="正在读取精神心理诊疗证据" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reload" />

    <template v-else>
      <section class="admin-metrics" aria-label="诊疗证据统计">
        <article><span>诊疗证据</span><strong>{{ items.length }}</strong><small>当前患者</small></article>
        <article><span>高危记录</span><strong>{{ riskCount }}</strong><small>risk_flag</small></article>
      </section>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>

      <div class="admin-layout">
        <section class="admin-panel">
          <header><div><h2>诊疗证据台账</h2><p>评估、处置和结论分项记录；高风险标记不自动创建复核。</p></div><button class="button secondary" @click="itemsQuery.refetch()">刷新</button></header>
          <div v-if="!items.length" class="admin-empty" role="status">暂无诊疗证据，可在右侧新增首条证据。</div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>记录时间</th><th>证据评估</th><th>处置与结论</th><th>风险</th></tr></thead>
              <tbody>
                <tr v-for="item in items" :key="item.note_id">
                  <td>{{ formatDate(item.recorded_at) }}</td>
                  <td>{{ item.assessment }}</td>
                  <td>{{ item.intervention }}</td>
                  <td><span class="admin-status" :class="item.risk_flag ? 'danger' : 'muted'">{{ item.risk_flag ? '高危' : '常规' }}</span></td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="admin-panel admin-form-panel">
          <header><div><h2>新增诊疗证据</h2><p>证据评估、处置与结论均为必填。</p></div></header>
          <form class="admin-form" @submit.prevent="createNote">
            <label><span>证据评估</span><textarea v-model="form.assessment" rows="3" required placeholder="记录检查、量表、影像或专科评估结果" /></label>
            <label><span>处置与结论</span><textarea v-model="form.intervention" rows="3" required placeholder="记录处置、结论、复核要求与随访计划" /></label>
            <label><span>记录时间</span><input v-model="form.recorded_at" type="datetime-local" required /></label>
            <label class="risk-confirm"><input v-model="form.risk_flag" type="checkbox" /><span>存在危险信号（保存风险标记，后续交接或复核须由临床人员发起）</span></label>
            <button class="button primary full" :disabled="busy || !form.assessment.trim() || !form.intervention.trim()">{{ busy ? '正在保存…' : '保存诊疗证据' }}</button>
          </form>
        </section>
      </div>
    </template>
  </section>
</template>
