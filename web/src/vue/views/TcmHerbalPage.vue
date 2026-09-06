<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref } from 'vue';
import type { TcmHerbalPrescriptionWire } from '../../generated/contracts';
import { createTcmHerbalPrescription, issueSpecialtyEncounterLease, issueSpecialtyPatientLease, listTcmHerbalPrescriptions } from '../../api/specialty-layers';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';
import { localDateTimeInputValue } from '../date-input';

const patientLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'tcm-treatment', 'patient-lease'],
  queryFn: () => issueSpecialtyPatientLease('TCM_HERBAL'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const patientLease = computed(() => patientLeaseQuery.data.value);

const encounterLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'tcm-treatment', 'encounter-lease'],
  queryFn: () => issueSpecialtyEncounterLease('TCM_HERBAL'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const encounterLease = computed(() => encounterLeaseQuery.data.value);

const recordsQuery = useQuery({
  queryKey: ['specialty-layers', 'tcm-treatment', 'records'],
  queryFn: () => listTcmHerbalPrescriptions(patientLease.value!),
  enabled: () => Boolean(patientLease.value),
  retry: false,
});

const issue = computed(() => (patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value)
  ? toClinicalIssue(patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value) : null);
const records = computed(() => recordsQuery.data.value ?? []);
const anyPending = computed(() => patientLeaseQuery.isPending.value || encounterLeaseQuery.isPending.value || recordsQuery.isPending.value);

const form = reactive({
  formulaName: '',
  herbs: '',
  containsToxicHerb: false,
  toxicHerbPrecautions: '',
  prescribedAt: localDateTimeInputValue(),
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
  if (!lease || busy.value || !form.formulaName.trim() || !form.herbs.trim()) return;
  busy.value = 'create'; notice.value = '';
  try {
    await createTcmHerbalPrescription(lease, {
      formula_name: form.formulaName.trim(),
      herbs: form.herbs.trim(),
      contains_toxic_herb: form.containsToxicHerb,
      toxic_herb_precautions: form.toxicHerbPrecautions.trim() || null,
      prescribed_at: form.prescribedAt,
    });
    form.formulaName = ''; form.herbs = '';
    form.containsToxicHerb = false; form.toxicHerbPrecautions = '';
    notice.value = '草药处方已创建，方名、药味与毒性标识已留痕。';
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
        <p class="eyebrow">专科其余层 / 中医 · 治疗</p>
        <h1>草药处方</h1>
        <p>登记方名、药味、毒性标识与注意事项。</p>
      </div>
      <div class="admin-inline-tools">
        <button class="button secondary" :disabled="Boolean(busy)" @click="reload">刷新</button>
      </div>
    </div>

    <ClinicalPageState v-if="anyPending" kind="loading" message="正在读取草药处方" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reload" />

    <template v-else>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>

      <div class="admin-layout">
        <section class="admin-panel">
          <header>
            <div><h2>草药处方台账</h2><p>当前患者中药处方档案。</p></div>
          </header>
          <div v-if="records.length === 0" class="admin-empty" role="status">暂无草药处方，可在右侧新增。</div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>方名</th><th>药味</th><th>含毒性药材</th><th>毒性注意事项</th><th>处方时间</th></tr></thead>
              <tbody>
                <tr v-for="record in records" :key="record.prescription_id">
                  <td>{{ record.formula_name }}</td>
                  <td>{{ record.herbs }}</td>
                  <td>{{ record.contains_toxic_herb ? '是' : '否' }}</td>
                  <td>{{ record.toxic_herb_precautions ?? '—' }}</td>
                  <td>{{ formatDate(record.prescribed_at) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="admin-panel admin-form-panel">
          <header><div><h2>新增草药处方</h2><p>方名与药味必填。</p></div></header>
          <form class="admin-form" @submit.prevent="createRecord">
            <label><span>方名</span><input v-model="form.formulaName" required /></label>
            <label><span>药味</span><textarea v-model="form.herbs" rows="3" required placeholder="例：当归 10g，川芎 6g…" /></label>
            <label><span>处方时间</span><input v-model="form.prescribedAt" type="datetime-local" required /></label>
            <label class="checkbox"><input v-model="form.containsToxicHerb" type="checkbox" />含毒性药材</label>
            <label><span>毒性注意事项</span><textarea v-model="form.toxicHerbPrecautions" rows="3" placeholder="可选" /></label>
            <button class="button primary full" :disabled="Boolean(busy)">{{ busy === 'create' ? '正在创建…' : '创建草药处方' }}</button>
          </form>
        </section>
      </div>
    </template>
  </section>
</template>
