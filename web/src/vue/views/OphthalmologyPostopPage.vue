<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref } from 'vue';
import type { OphthalmologyPostopFollowupWire } from '../../generated/contracts';
import { createOphthalmologyPostopFollowup, issueSpecialtyEncounterLease, issueSpecialtyPatientLease, listOphthalmologyPostopFollowups, listOphthalmologyPreopVerifications } from '../../api/specialty-layers';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';
import { localDateTimeInputValue } from '../date-input';

const patientLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'ophthalmology-followup', 'patient-lease'],
  queryFn: () => issueSpecialtyPatientLease('OPHTHALMOLOGY_POSTOP'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const patientLease = computed(() => patientLeaseQuery.data.value);

const encounterLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'ophthalmology-followup', 'encounter-lease'],
  queryFn: () => issueSpecialtyEncounterLease('OPHTHALMOLOGY_POSTOP'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const encounterLease = computed(() => encounterLeaseQuery.data.value);

const recordsQuery = useQuery({
  queryKey: ['specialty-layers', 'ophthalmology-followup', 'records'],
  queryFn: () => listOphthalmologyPostopFollowups(patientLease.value!),
  enabled: () => Boolean(patientLease.value),
  retry: false,
});
const preopQuery = useQuery({ queryKey: ['specialty-layers', 'ophthalmology-followup', 'preop'], queryFn: () => listOphthalmologyPreopVerifications(patientLease.value!), enabled: () => Boolean(patientLease.value), retry: false });

const issue = computed(() => (patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value ?? preopQuery.error.value)
  ? toClinicalIssue(patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value ?? preopQuery.error.value) : null);
const records = computed(() => recordsQuery.data.value ?? []);
const anyPending = computed(() => patientLeaseQuery.isPending.value || encounterLeaseQuery.isPending.value || recordsQuery.isPending.value || preopQuery.isPending.value);

const form = reactive({
  surgicalEye: 'OD' as OphthalmologyPostopFollowupWire['surgical_eye'],
  preopVerificationId: '',
  followupDate: '',
  iopMmhg: 0,
  complicationNote: '',
  recordedAt: localDateTimeInputValue(),
});
const busy = ref('');
const notice = ref('');

function surgicalEyeLabel(value: string) {
  const map: Record<string, string> = { OD: '右眼', OS: '左眼', OU: '双眼' };
  return map[value] ?? value;
}

function formatDate(value: string | null | undefined) {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium' }).format(new Date(value)) : '—';
}

async function reload() {
  notice.value = '';
  await Promise.all([recordsQuery.refetch(), preopQuery.refetch()]);
}

async function createRecord() {
  const lease = encounterLease.value;
  if (!lease || busy.value || !form.preopVerificationId || !form.followupDate) return;
  busy.value = 'create'; notice.value = '';
  try {
    await createOphthalmologyPostopFollowup(lease, {
      preop_verification_id: form.preopVerificationId,
      surgical_eye: form.surgicalEye,
      followup_date: new Date(form.followupDate).toISOString(),
      iop_mmhg: form.iopMmhg,
      complication_note: form.complicationNote.trim() || null,
      recorded_at: form.recordedAt,
    });
    form.followupDate = ''; form.iopMmhg = 0; form.complicationNote = '';
    notice.value = '术后随访已创建，眼压与并发症备注已留痕。';
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
        <p class="eyebrow">专科其余层 / 眼科 · 随访</p>
        <h1>术后随访</h1>
        <p>登记手术眼别、随访日期、眼压与并发症备注。</p>
      </div>
      <div class="admin-inline-tools">
        <button class="button secondary" :disabled="Boolean(busy)" @click="reload">刷新</button>
      </div>
    </div>

    <ClinicalPageState v-if="anyPending" kind="loading" message="正在读取术后随访" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reload" />

    <template v-else>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>

      <div class="admin-layout">
        <section class="admin-panel">
          <header>
            <div><h2>术后随访台账</h2><p>当前患者眼科术后随访档案。</p></div>
          </header>
          <div v-if="records.length === 0" class="admin-empty" role="status">暂无术后随访，可在右侧新增。</div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>手术眼别</th><th>随访日期</th><th>眼压(mmHg)</th><th>并发症备注</th></tr></thead>
              <tbody>
                <tr v-for="record in records" :key="record.followup_id">
                  <td>{{ surgicalEyeLabel(record.surgical_eye) }}</td>
                  <td>{{ formatDate(record.followup_date) }}</td>
                  <td>{{ record.iop_mmhg }}</td>
                  <td>{{ record.complication_note ?? '—' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="admin-panel admin-form-panel">
          <header><div><h2>新增术后随访</h2><p>必须关联当前患者同一次就诊的术前核对。</p></div></header>
          <form class="admin-form" @submit.prevent="createRecord">
            <label><span>手术眼别</span><select v-model="form.surgicalEye">
              <option value="OD">右眼</option><option value="OS">左眼</option><option value="OU">双眼</option>
            </select></label>
            <label><span>术前核对</span><select v-model="form.preopVerificationId" required><option value="" disabled>请选择同眼别术前核对</option><option v-for="item in (preopQuery.data.value ?? []).filter((item) => item.surgical_eye === form.surgicalEye)" :key="item.verification_id" :value="item.verification_id">{{ item.verification_id }}</option></select></label>
            <label><span>随访日期</span><input v-model="form.followupDate" type="date" required /></label>
            <label><span>眼压（mmHg）</span><input v-model.number="form.iopMmhg" type="number" min="0" step="0.1" required /></label>
            <label><span>记录时间</span><input v-model="form.recordedAt" type="datetime-local" required /></label>
            <label><span>并发症备注</span><textarea v-model="form.complicationNote" rows="3" placeholder="可选" /></label>
            <button class="button primary full" :disabled="Boolean(busy)">{{ busy === 'create' ? '正在创建…' : '创建术后随访' }}</button>
          </form>
        </section>
      </div>
    </template>
  </section>
</template>
