<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref, watchEffect } from 'vue';
import type { ArtEmbryoTransferRecordWire } from '../../generated/contracts';
import { createArtEmbryoTransferRecord, issueSpecialtyEncounterLease, issueSpecialtyPatientLease, listArtEmbryoTransferRecords } from '../../api/specialty-layers';
import { listArtCycleRecords } from '../../api/specialty';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';
import { localDateTimeInputValue } from '../date-input';

const patientLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'reproductive-treatment', 'patient-lease'],
  queryFn: () => issueSpecialtyPatientLease('ART_TRANSFER'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const patientLease = computed(() => patientLeaseQuery.data.value);

const encounterLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'reproductive-treatment', 'encounter-lease'],
  queryFn: () => issueSpecialtyEncounterLease('ART_TRANSFER'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const encounterLease = computed(() => encounterLeaseQuery.data.value);

const recordsQuery = useQuery({
  queryKey: ['specialty-layers', 'reproductive-treatment', 'records'],
  queryFn: () => listArtEmbryoTransferRecords(patientLease.value!),
  enabled: () => Boolean(patientLease.value),
  retry: false,
});
const cyclesQuery = useQuery({
  queryKey: ['specialty-layers', 'reproductive-treatment', 'cycles'],
  queryFn: () => listArtCycleRecords(patientLease.value!),
  enabled: () => Boolean(patientLease.value),
  retry: false,
});
const cycles = computed(() => cyclesQuery.data.value ?? []);

const issue = computed(() => (patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value ?? cyclesQuery.error.value)
  ? toClinicalIssue(patientLeaseQuery.error.value ?? encounterLeaseQuery.error.value ?? recordsQuery.error.value ?? cyclesQuery.error.value) : null);
const records = computed(() => recordsQuery.data.value ?? []);
const anyPending = computed(() => patientLeaseQuery.isPending.value || encounterLeaseQuery.isPending.value || recordsQuery.isPending.value || cyclesQuery.isPending.value);

const form = reactive({
  cycleId: '',
  embryoCount: 0,
  verifierId: '',
  transferredAt: localDateTimeInputValue(),
});
const busy = ref('');
const notice = ref('');

watchEffect(() => {
  if (!form.cycleId && cycles.value[0]) form.cycleId = cycles.value[0].cycle_id;
});

function formatDate(value: string | null | undefined) {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—';
}

async function reload() {
  notice.value = '';
  await recordsQuery.refetch();
}

async function createRecord() {
  const lease = patientLease.value;
  if (!lease || busy.value || !form.cycleId.trim() || !form.verifierId.trim()) return;
  busy.value = 'create'; notice.value = '';
  try {
    await createArtEmbryoTransferRecord(lease, {
      cycle_id: form.cycleId.trim(),
      embryo_count: form.embryoCount,
      verifier_id: form.verifierId.trim(),
      transferred_at: form.transferredAt,
    });
    form.cycleId = ''; form.embryoCount = 0; form.verifierId = '';
    notice.value = '胚胎移植记录已创建，移植枚数与双人核对已留痕。';
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
        <p class="eyebrow">专科其余层 / 生殖 · 治疗</p>
        <h1>胚胎移植记录</h1>
        <p>周期、移植枚数、移植时间与双人核对登记；按患者上下文留痕审计链。</p>
      </div>
      <div class="admin-inline-tools">
        <button class="button secondary" :disabled="Boolean(busy)" @click="reload">刷新</button>
      </div>
    </div>

    <ClinicalPageState v-if="anyPending" kind="loading" message="正在读取胚胎移植记录" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reload" />

    <template v-else>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>

      <div class="admin-layout">
        <section class="admin-panel">
          <header>
            <div><h2>胚胎移植台账</h2><p>当前患者辅助生殖移植档案。</p></div>
          </header>
          <div v-if="records.length === 0" class="admin-empty" role="status">暂无胚胎移植记录，可在右侧新增。</div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>周期ID</th><th>移植枚数</th><th>移植时间</th><th>操作者ID</th><th>核对者ID</th></tr></thead>
              <tbody>
                <tr v-for="record in records" :key="record.embryo_transfer_id">
                  <td>{{ record.cycle_id }}</td>
                  <td>{{ record.embryo_count }}</td>
                  <td>{{ formatDate(record.transferred_at) }}</td>
                  <td>{{ record.operator_id }}</td>
                  <td>{{ record.verifier_id }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="admin-panel admin-form-panel">
          <header><div><h2>新增胚胎移植记录</h2><p>周期ID、移植枚数与核对者ID必填。</p></div></header>
          <form class="admin-form" @submit.prevent="createRecord">
            <label><span>辅助生殖周期</span><select v-model="form.cycleId" required>
              <option value="" disabled>请选择周期</option>
              <option v-for="cycle in cycles" :key="cycle.cycle_id" :value="cycle.cycle_id">
                {{ cycle.cycle_type }} 第 {{ cycle.cycle_number }} 周期 · {{ cycle.status }}
              </option>
            </select></label>
            <p v-if="cycles.length === 0" class="admin-form-hint">当前患者暂无周期，请先在“辅助生殖周期记录”中建档。</p>
            <label><span>移植枚数</span><input v-model.number="form.embryoCount" type="number" min="1" required /></label>
            <label><span>核对者ID</span><input v-model="form.verifierId" required placeholder="UUID" /></label>
            <label><span>移植时间</span><input v-model="form.transferredAt" type="datetime-local" required /></label>
            <button class="button primary full" :disabled="Boolean(busy)">{{ busy === 'create' ? '正在创建…' : '创建胚胎移植记录' }}</button>
          </form>
        </section>
      </div>
    </template>
  </section>
</template>
