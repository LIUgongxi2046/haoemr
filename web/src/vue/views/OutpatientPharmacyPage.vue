<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref } from 'vue';
import type { PharmacyDispensingWire } from '../../generated/contracts';
import { developmentCopy } from '../../development-copy';
import { issueExecutionLease, issueExecutionPatientLease, listPharmacyDispensings, preparePharmacyDispensing, transitionPharmacyDispensing } from '../../api/execution';
import { loadOutpatientWorkspaceSnapshot } from '../../api/outpatient-workspace';
import { clinicalContext } from '../../clinical-api';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import AdminActionDialog from '../components/AdminActionDialog.vue';
import ExecutionPatientContextBar from '../components/ExecutionPatientContextBar.vue';
import { toClinicalIssue } from '../clinical-error';

const statusLabels: Record<PharmacyDispensingWire['status'], string> = {
  PREPARED: '已调配', VERIFIED: '已核验', DISPENSED: '已发药',
};
const leaseQuery = useQuery({
  queryKey: ['execution', 'outpatient-pharmacy', 'lease'],
  queryFn: () => issueExecutionPatientLease('PHARMACY_WORKFLOW'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const writeLeaseQuery = useQuery({
  queryKey: ['execution', 'outpatient-pharmacy', 'write-lease'],
  queryFn: () => issueExecutionLease('PHARMACY_WORKFLOW'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const dispensingsQuery = useQuery({
  queryKey: ['execution', 'outpatient-pharmacy', 'dispensings'],
  queryFn: () => listPharmacyDispensings(leaseQuery.data.value!),
  enabled: () => Boolean(leaseQuery.data.value),
  retry: false,
});
const prescriptionsQuery = useQuery({
  queryKey: ['execution', 'outpatient-pharmacy', 'prescriptions', clinicalContext.patientId, clinicalContext.encounterId],
  queryFn: () => loadOutpatientWorkspaceSnapshot(clinicalContext.patientId, clinicalContext.encounterId),
  retry: false, staleTime: 0, gcTime: 0,
});
const issue = computed(() => {
  const error = leaseQuery.error.value ?? writeLeaseQuery.error.value ?? dispensingsQuery.error.value ?? prescriptionsQuery.error.value;
  return error ? toClinicalIssue(error) : null;
});
const dispensings = computed(() => dispensingsQuery.data.value ?? []);
const preparedCount = computed(() => dispensings.value.filter((d) => d.status === 'PREPARED').length);
const dispensedCount = computed(() => dispensings.value.filter((d) => d.status === 'DISPENSED').length);
const prescriptions = computed(() => (prescriptionsQuery.data.value?.orders ?? [])
  .filter((order) => ['SIGNED', 'ACTIVE', 'IN_PROGRESS'].includes(order.status))
  .flatMap((order) => order.items
    .filter((item) => item.item_type === 'MEDICATION' && ['ACTIVE', 'IN_PROGRESS'].includes(item.item_state))
    .map((item) => {
      const preparedQuantity = dispensings.value
        .filter((dispensing) => dispensing.order_item_id === item.order_item_id && !dispensing.voided_at)
        .reduce((total, dispensing) => total + dispensing.quantity, 0);
      return {
        key: `${order.order_id}:${item.order_item_id}`,
        orderId: order.order_id,
        itemId: item.order_item_id,
        displayName: item.display_name,
        drugCode: item.catalog_code,
        quantityUnit: item.quantity_unit,
        remainingQuantity: Math.max(0, item.requested_quantity - preparedQuantity),
      };
    }))
  .filter((item) => item.remainingQuantity > 0));
const selectedPrescription = computed(() => prescriptions.value.find((item) => item.key === form.prescriptionKey) ?? null);

const form = reactive({ prescriptionKey: '', batchNumber: '', quantity: 1 });
const busy = ref('');
const notice = ref('');
const createDialogOpen = ref(false);

function formatDate(value: string | null | undefined) {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—';
}

async function reload() { notice.value = ''; await dispensingsQuery.refetch(); }

async function prepare() {
  const lease = writeLeaseQuery.data.value;
  const prescription = selectedPrescription.value;
  if (!lease || !prescription || busy.value || !form.batchNumber.trim() || form.quantity <= 0 || form.quantity > prescription.remainingQuantity) return;
  busy.value = 'prepare'; notice.value = '';
  try {
    await preparePharmacyDispensing(lease, {
      order_id: prescription.orderId, order_item_id: prescription.itemId,
      drug_code: prescription.drugCode, batch_number: form.batchNumber.trim(),
      quantity: form.quantity, quantity_unit: prescription.quantityUnit,
      prepared_at: new Date().toISOString(),
    });
    form.prescriptionKey = ''; form.batchNumber = '';
    createDialogOpen.value = false;
    notice.value = '已按签署处方完成调配，等待第二人审方核验后方可发药。';
    await Promise.all([dispensingsQuery.refetch(), prescriptionsQuery.refetch()]);
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}

async function transition(dispensing: PharmacyDispensingWire, action: 'VERIFY' | 'DISPENSE') {
  const lease = writeLeaseQuery.data.value;
  if (!lease || busy.value) return;
  busy.value = `${action}:${dispensing.dispensing_id}`; notice.value = '';
  try {
    await transitionPharmacyDispensing(lease, dispensing, action);
    notice.value = action === 'VERIFY' ? '已第二人核验，可发药。' : '已双人核验后发药，出箱事件已同步。';
    await dispensingsQuery.refetch();
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}
</script>

<template>
  <section data-page-root class="content vue-native-page">
    <div class="page-heading">
      <div><p class="eyebrow">诊疗执行 / 药房</p><h1>门诊药房审方与调剂</h1><p>已签署处方 → 调配 → 第二人审方核验 → 发药；未核验不可发药。</p></div>
      <div class="toolbar-actions"><button class="button secondary" :disabled="Boolean(busy)" @click="reload">刷新</button><button class="button primary" :disabled="prescriptions.length === 0" @click="createDialogOpen = true">开始调配</button></div>
    </div>
    <ExecutionPatientContextBar />
    <section class="patient-strip"><div class="patient-avatar">{{ developmentCopy.patientAvatar }}</div><div><strong>{{ developmentCopy.outpatientPatientName }}</strong><span>门诊发药双人核验</span></div><dl><div><dt>发药前提</dt><dd>第二人核验</dd></div><div><dt>批次</dt><dd>强制填写</dd></div></dl><span class="lease-badge">当前患者 / 当前就诊</span></section>
    <div v-if="notice" class="inline-notice" :class="{ error: notice.includes('：') }" role="status">{{ notice }}</div>

    <ClinicalPageState v-if="leaseQuery.isPending.value || writeLeaseQuery.isPending.value || dispensingsQuery.isPending.value || prescriptionsQuery.isPending.value" kind="loading" message="正在读取门诊处方与调剂台账" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reload" />

    <template v-else>
      <section class="admin-metrics" aria-label="门诊药房统计">
        <article><span>调剂笔数</span><strong>{{ dispensings.length }}</strong><small>当前患者</small></article>
        <article><span>待调配处方项</span><strong>{{ prescriptions.length }}</strong><small>已签署且剩余可调配</small></article>
        <article><span>待核验</span><strong>{{ preparedCount }}</strong></article>
        <article><span>已发药</span><strong>{{ dispensedCount }}</strong></article>
      </section>

      <section class="admin-panel">
          <header><div><h2>调剂台账</h2><p>状态机：PREPARED → VERIFIED → DISPENSED，每步写入审计链。</p></div><button class="button secondary" @click="dispensingsQuery.refetch()">刷新</button></header>
          <div v-if="dispensings.length === 0" class="empty-state"><span>药</span><p>当前患者暂无调剂记录</p><small>{{ prescriptions.length ? '请选择已签署处方项开始调配。' : '暂无可调配的已签署处方；请先在门诊医嘱完成处方签署。' }}</small></div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>药品</th><th>批次</th><th>数量</th><th>调配人 / 核验人</th><th>状态</th><th>时间</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="dispensing in dispensings" :key="dispensing.dispensing_id">
                  <td><strong><code>{{ dispensing.drug_code }}</code></strong><small>…{{ dispensing.dispensing_id.slice(-8) }}</small></td>
                  <td>{{ dispensing.batch_number }}</td>
                  <td>{{ dispensing.quantity }} {{ dispensing.quantity_unit }}</td>
                  <td><small>调配 …{{ dispensing.dispensed_by.slice(-8) }}</small><small>核验 {{ dispensing.verified_by ? `…${dispensing.verified_by.slice(-8)}` : '—' }}</small></td>
                  <td><span class="admin-status" :class="dispensing.status.toLowerCase()">{{ statusLabels[dispensing.status] }}</span></td>
                  <td>{{ formatDate(dispensing.dispensed_at ?? dispensing.verified_at ?? dispensing.prepared_at) }}</td>
                  <td class="admin-actions">
                    <button v-if="dispensing.status === 'PREPARED'" class="task-action" :disabled="Boolean(busy)" @click="transition(dispensing, 'VERIFY')">第二人核验</button>
                    <button v-if="dispensing.status === 'VERIFIED'" class="task-action" :disabled="Boolean(busy)" @click="transition(dispensing, 'DISPENSE')">发药</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
      </section>

      <AdminActionDialog v-model:open="createDialogOpen" title="按签署处方调配" description="只能选择当前患者已签署、尚有可调配数量的药品处方项；调配后需第二人审方核验。" eyebrow="诊疗执行 / 门诊药房" :busy="busy === 'prepare'">
        <form class="admin-form" @submit.prevent="prepare">
          <label><span>已签署处方项</span><select v-model="form.prescriptionKey" required><option value="" disabled>选择可调配处方项</option><option v-for="item in prescriptions" :key="item.key" :value="item.key">{{ item.displayName }}（{{ item.drugCode }}，剩余 {{ item.remainingQuantity }} {{ item.quantityUnit }}）</option></select></label>
          <label><span>批次号</span><input v-model="form.batchNumber" maxlength="64" required placeholder="例：BATCH-2026-0812" /></label>
          <label><span>数量</span><input v-model.number="form.quantity" type="number" min="0.01" :max="selectedPrescription?.remainingQuantity" step="0.01" required /></label>
          <p v-if="selectedPrescription" class="form-hint">处方单位：{{ selectedPrescription.quantityUnit }}；本次最多可调配 {{ selectedPrescription.remainingQuantity }} {{ selectedPrescription.quantityUnit }}。</p>
          <button class="button primary full" :disabled="!selectedPrescription || form.quantity > selectedPrescription.remainingQuantity || Boolean(busy)">{{ busy === 'prepare' ? '正在调配…' : '按处方调配并待核验' }}</button>
        </form>
      </AdminActionDialog>
    </template>
  </section>
</template>
