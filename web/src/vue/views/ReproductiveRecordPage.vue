<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref } from 'vue';
import type { ArtCycleRecordWire } from '../../generated/contracts';
import { createArtCycleRecord, issueSpecialtyEncounterLease, issueSpecialtyPatientLease, listArtCycleRecords } from '../../api/specialty';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';
import { localDateTimeInputValue } from '../date-input';

const patientLeaseQuery = useQuery({
  queryKey: ['specialty', 'reproductive-record', 'patient-lease'],
  queryFn: () => issueSpecialtyPatientLease('REPRODUCTIVE_RECORD'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const patientLease = computed(() => patientLeaseQuery.data.value);

const encounterLeaseQuery = useQuery({
  queryKey: ['specialty', 'reproductive-record', 'encounter-lease'],
  queryFn: () => issueSpecialtyEncounterLease('REPRODUCTIVE_RECORD'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const encounterLease = computed(() => encounterLeaseQuery.data.value);

const recordsQuery = useQuery({
  queryKey: ['specialty', 'reproductive-record', 'records'],
  queryFn: () => listArtCycleRecords(patientLease.value!),
  enabled: () => Boolean(patientLease.value),
  retry: false,
});

const issue = computed(() => (patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value)
  ? toClinicalIssue(patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value) : null);
const records = computed(() => recordsQuery.data.value ?? []);
const anyPending = computed(() => patientLeaseQuery.isPending.value || encounterLeaseQuery.isPending.value || recordsQuery.isPending.value);

const form = reactive({
  cycleType: 'IVF' as ArtCycleRecordWire['cycle_type'],
  cycleNumber: 1,
  ethicsConsentDate: localDateTimeInputValue(),
  consentDocumentId: '',
  partnerPatientId: '',
});
const busy = ref('');
const notice = ref('');

function formatDate(value: string | null | undefined) {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium' }).format(new Date(value)) : '—';
}

function statusLabel(status: string) {
  return status === 'ACTIVE' ? '进行中' : status === 'COMPLETED' ? '已完成' : '已取消';
}

async function reload() {
  notice.value = '';
  await recordsQuery.refetch();
}

async function createRecord() {
  const lease = encounterLease.value;
  if (!lease || busy.value || !form.ethicsConsentDate.trim()) return;
  busy.value = 'create'; notice.value = '';
  try {
    await createArtCycleRecord(lease, {
      cycle_type: form.cycleType,
      cycle_number: form.cycleNumber,
      ethics_consent_date: form.ethicsConsentDate,
      consent_document_id: form.consentDocumentId.trim() || null,
      partner_patient_id: form.partnerPatientId.trim() || null,
    });
    form.cycleNumber = 1; form.consentDocumentId = ''; form.partnerPatientId = '';
    notice.value = '辅助生殖周期记录已创建，伦理同意与同意书编号已留痕。';
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
        <p class="eyebrow">专科记录 / 生殖</p>
        <h1>辅助生殖周期记录</h1>
        <p>IVF/ICSI/IUI/FET 等周期登记，伦理同意日期与同意书编号留痕。</p>
      </div>
      <div class="admin-inline-tools">
        <button class="button secondary" :disabled="Boolean(busy)" @click="reload">刷新</button>
      </div>
    </div>

    <ClinicalPageState v-if="anyPending" kind="loading" message="正在读取辅助生殖周期记录" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reload" />

    <template v-else>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>

      <div class="admin-layout">
        <section class="admin-panel">
          <header>
            <div><h2>周期记录台账</h2><p>当前患者辅助生殖周期档案。</p></div>
          </header>
          <div v-if="records.length === 0" class="admin-empty" role="status">暂无周期记录，可在右侧新增。</div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>周期ID</th><th>周期类型</th><th>序号</th><th>伦理同意日期</th><th>伴侣患者</th><th>同意书</th><th>状态</th></tr></thead>
              <tbody>
                <tr v-for="record in records" :key="record.cycle_id">
                  <td><code>{{ record.cycle_id }}</code></td>
                  <td>{{ record.cycle_type }}</td>
                  <td>{{ record.cycle_number }}</td>
                  <td>{{ formatDate(record.ethics_consent_date) }}</td>
                  <td><code>{{ record.partner_patient_id ? `…${record.partner_patient_id.slice(-8)}` : '—' }}</code></td>
                  <td><code>{{ record.consent_document_id ? `…${record.consent_document_id.slice(-8)}` : '—' }}</code></td>
                  <td><span class="admin-status" :class="record.status.toLowerCase()">{{ statusLabel(record.status) }}</span></td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="admin-panel admin-form-panel">
          <header><div><h2>新增周期记录</h2><p>周期类型、序号与伦理同意日期必填。</p></div></header>
          <form class="admin-form" @submit.prevent="createRecord">
            <label><span>周期类型</span><select v-model="form.cycleType">
              <option value="IVF">体外受精（IVF）</option>
              <option value="ICSI">卵胞浆内单精子注射（ICSI）</option>
              <option value="IUI">宫腔内人工授精（IUI）</option>
              <option value="FET">冻融胚胎移植（FET）</option>
              <option value="OTHER">其他</option>
            </select></label>
            <label><span>周期序号</span><input v-model.number="form.cycleNumber" type="number" min="1" required /></label>
            <label><span>伦理同意日期</span><input v-model="form.ethicsConsentDate" type="datetime-local" required /></label>
            <label><span>同意书编号</span><input v-model="form.consentDocumentId" maxlength="36" placeholder="可选 UUID" /></label>
            <label><span>伴侣患者 ID</span><input v-model="form.partnerPatientId" maxlength="36" placeholder="可选 UUID" /></label>
            <button class="button primary full" :disabled="Boolean(busy)">{{ busy === 'create' ? '正在创建…' : '创建周期记录' }}</button>
          </form>
        </section>
      </div>
    </template>
  </section>
</template>
