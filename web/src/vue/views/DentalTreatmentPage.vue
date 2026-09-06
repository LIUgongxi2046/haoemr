<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref } from 'vue';
import type { DentalTreatmentRecordWire } from '../../generated/contracts';
import { createDentalTreatmentRecord, issueSpecialtyEncounterLease, issueSpecialtyPatientLease, listDentalTreatmentRecords } from '../../api/specialty-layers';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';
import { localDateTimeInputValue } from '../date-input';

const patientLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'dental-treatment', 'patient-lease'],
  queryFn: () => issueSpecialtyPatientLease('DENTAL_TREATMENT'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const patientLease = computed(() => patientLeaseQuery.data.value);

const encounterLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'dental-treatment', 'encounter-lease'],
  queryFn: () => issueSpecialtyEncounterLease('DENTAL_TREATMENT'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const encounterLease = computed(() => encounterLeaseQuery.data.value);

const recordsQuery = useQuery({
  queryKey: ['specialty-layers', 'dental-treatment', 'records'],
  queryFn: () => listDentalTreatmentRecords(patientLease.value!),
  enabled: () => Boolean(patientLease.value),
  retry: false,
});

const issue = computed(() => (patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value)
  ? toClinicalIssue(patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value) : null);
const records = computed(() => recordsQuery.data.value ?? []);
const anyPending = computed(() => patientLeaseQuery.isPending.value || encounterLeaseQuery.isPending.value || recordsQuery.isPending.value);

const form = reactive({
  toothNotation: '',
  treatmentType: 'FILLING' as DentalTreatmentRecordWire['treatment_type'],
  materialBatch: '',
  treatedAt: localDateTimeInputValue(),
});
const busy = ref('');
const notice = ref('');

function treatmentTypeLabel(value: string) {
  const map: Record<string, string> = {
    FILLING: '充填', EXTRACTION: '拔除', ROOT_CANAL: '根管治疗', CROWN: '冠修复', CLEANING: '洁治', OTHER: '其他',
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
  if (!lease || busy.value || !form.toothNotation.trim()) return;
  busy.value = 'create'; notice.value = '';
  try {
    await createDentalTreatmentRecord(lease, {
      tooth_notation: form.toothNotation.trim(),
      treatment_type: form.treatmentType,
      material_batch: form.materialBatch.trim() || null,
      treated_at: form.treatedAt,
    });
    form.toothNotation = ''; form.materialBatch = '';
    notice.value = '口腔治疗记录已创建，牙位与治疗类型已留痕。';
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
        <p class="eyebrow">专科其余层 / 口腔 · 治疗</p>
        <h1>口腔治疗记录</h1>
        <p>登记牙位、治疗类型、材料批次与治疗时间。</p>
      </div>
      <div class="admin-inline-tools">
        <button class="button secondary" :disabled="Boolean(busy)" @click="reload">刷新</button>
      </div>
    </div>

    <ClinicalPageState v-if="anyPending" kind="loading" message="正在读取口腔治疗记录" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reload" />

    <template v-else>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>

      <div class="admin-layout">
        <section class="admin-panel">
          <header>
            <div><h2>口腔治疗台账</h2><p>当前患者口腔治疗档案。</p></div>
          </header>
          <div v-if="records.length === 0" class="admin-empty" role="status">暂无口腔治疗记录，可在右侧新增。</div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>牙位</th><th>治疗类型</th><th>材料批次</th><th>治疗时间</th><th>操作者ID</th></tr></thead>
              <tbody>
                <tr v-for="record in records" :key="record.dental_treatment_record_id">
                  <td>{{ record.tooth_notation }}</td>
                  <td>{{ treatmentTypeLabel(record.treatment_type) }}</td>
                  <td>{{ record.material_batch ?? '—' }}</td>
                  <td>{{ formatDate(record.treated_at) }}</td>
                  <td>{{ record.performed_by }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="admin-panel admin-form-panel">
          <header><div><h2>新增口腔治疗记录</h2><p>牙位与治疗类型必填。</p></div></header>
          <form class="admin-form" @submit.prevent="createRecord">
            <label><span>牙位</span><input v-model="form.toothNotation" required placeholder="例：16 或 36" /></label>
            <label><span>治疗类型</span><select v-model="form.treatmentType">
              <option value="FILLING">充填</option>
              <option value="EXTRACTION">拔除</option>
              <option value="ROOT_CANAL">根管治疗</option>
              <option value="CROWN">冠修复</option>
              <option value="CLEANING">洁治</option>
              <option value="OTHER">其他</option>
            </select></label>
            <label><span>治疗时间</span><input v-model="form.treatedAt" type="datetime-local" required /></label>
            <label><span>材料批次</span><input v-model="form.materialBatch" placeholder="可选" /></label>
            <button class="button primary full" :disabled="Boolean(busy)">{{ busy === 'create' ? '正在创建…' : '创建口腔治疗记录' }}</button>
          </form>
        </section>
      </div>
    </template>
  </section>
</template>
