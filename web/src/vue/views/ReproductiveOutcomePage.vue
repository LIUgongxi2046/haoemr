<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref, watchEffect } from 'vue';
import type { ArtPregnancyOutcomeWire } from '../../generated/contracts';
import { createArtPregnancyOutcome, issueSpecialtyEncounterLease, issueSpecialtyPatientLease, listArtPregnancyOutcomes } from '../../api/specialty-layers';
import { listArtCycleRecords } from '../../api/specialty';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';

const patientLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'reproductive-followup', 'patient-lease'],
  queryFn: () => issueSpecialtyPatientLease('ART_OUTCOME'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const patientLease = computed(() => patientLeaseQuery.data.value);

const encounterLeaseQuery = useQuery({
  queryKey: ['specialty-layers', 'reproductive-followup', 'encounter-lease'],
  queryFn: () => issueSpecialtyEncounterLease('ART_OUTCOME'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const encounterLease = computed(() => encounterLeaseQuery.data.value);

const recordsQuery = useQuery({
  queryKey: ['specialty-layers', 'reproductive-followup', 'records'],
  queryFn: () => listArtPregnancyOutcomes(patientLease.value!),
  enabled: () => Boolean(patientLease.value),
  retry: false,
});
const cyclesQuery = useQuery({
  queryKey: ['specialty-layers', 'reproductive-followup', 'cycles'],
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
  pregnancyResult: 'PREGNANT' as ArtPregnancyOutcomeWire['pregnancy_result'],
  outcomeDate: '',
  liveBirthCount: 0,
  complications: '',
  recordedAt: new Date().toISOString(),
});
const busy = ref('');
const notice = ref('');

watchEffect(() => {
  if (!form.cycleId && cycles.value[0]) form.cycleId = cycles.value[0].cycle_id;
});

function pregnancyResultLabel(value: string) {
  const map: Record<string, string> = {
    PREGNANT: '临床妊娠', NOT_PREGNANT: '未妊娠', BIOCHEMICAL: '生化妊娠', MISCARRIAGE: '流产',
  };
  return map[value] ?? value;
}

function formatDate(value: string | null | undefined) {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium' }).format(new Date(value)) : '—';
}

async function reload() {
  notice.value = '';
  await recordsQuery.refetch();
}

async function createRecord() {
  const lease = encounterLease.value;
  if (!lease || busy.value || !form.cycleId.trim() || !form.outcomeDate) return;
  busy.value = 'create'; notice.value = '';
  try {
    await createArtPregnancyOutcome(lease, {
      cycle_id: form.cycleId.trim(),
      pregnancy_result: form.pregnancyResult,
      outcome_date: form.outcomeDate,
      live_birth_count: form.liveBirthCount,
      complications: form.complications.trim() || null,
      recorded_at: form.recordedAt,
    });
    form.cycleId = ''; form.outcomeDate = ''; form.liveBirthCount = 0; form.complications = '';
    notice.value = '妊娠结局已创建，妊娠结果与活产数已留痕。';
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
        <p class="eyebrow">专科其余层 / 生殖 · 随访</p>
        <h1>妊娠结局</h1>
        <p>登记周期、妊娠结果、结局日期与活产数。</p>
      </div>
      <div class="admin-inline-tools">
        <button class="button secondary" :disabled="Boolean(busy)" @click="reload">刷新</button>
      </div>
    </div>

    <ClinicalPageState v-if="anyPending" kind="loading" message="正在读取妊娠结局" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reload" />

    <template v-else>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>

      <div class="admin-layout">
        <section class="admin-panel">
          <header>
            <div><h2>妊娠结局台账</h2><p>当前患者辅助生殖结局档案。</p></div>
          </header>
          <div v-if="records.length === 0" class="admin-empty" role="status">暂无妊娠结局，可在右侧新增。</div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>周期ID</th><th>妊娠结果</th><th>结局日期</th><th>活产数</th><th>并发症</th></tr></thead>
              <tbody>
                <tr v-for="record in records" :key="record.outcome_id">
                  <td>{{ record.cycle_id }}</td>
                  <td>{{ pregnancyResultLabel(record.pregnancy_result) }}</td>
                  <td>{{ formatDate(record.outcome_date) }}</td>
                  <td>{{ record.live_birth_count }}</td>
                  <td>{{ record.complications ?? '—' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="admin-panel admin-form-panel">
          <header><div><h2>新增妊娠结局</h2><p>周期ID、妊娠结果与结局日期必填。</p></div></header>
          <form class="admin-form" @submit.prevent="createRecord">
            <label><span>辅助生殖周期</span><select v-model="form.cycleId" required>
              <option value="" disabled>请选择周期</option>
              <option v-for="cycle in cycles" :key="cycle.cycle_id" :value="cycle.cycle_id">
                {{ cycle.cycle_type }} 第 {{ cycle.cycle_number }} 周期 · {{ cycle.status }}
              </option>
            </select></label>
            <p v-if="cycles.length === 0" class="admin-form-hint">当前患者暂无周期，请先在“辅助生殖周期记录”中建档。</p>
            <label><span>妊娠结果</span><select v-model="form.pregnancyResult">
              <option value="PREGNANT">临床妊娠</option>
              <option value="NOT_PREGNANT">未妊娠</option>
              <option value="BIOCHEMICAL">生化妊娠</option>
              <option value="MISCARRIAGE">流产</option>
            </select></label>
            <label><span>结局日期</span><input v-model="form.outcomeDate" type="date" required /></label>
            <label><span>活产数</span><input v-model.number="form.liveBirthCount" type="number" min="0" required /></label>
            <label><span>记录时间</span><input v-model="form.recordedAt" type="datetime-local" required /></label>
            <label><span>并发症</span><textarea v-model="form.complications" rows="3" placeholder="可选" /></label>
            <button class="button primary full" :disabled="Boolean(busy)">{{ busy === 'create' ? '正在创建…' : '创建妊娠结局' }}</button>
          </form>
        </section>
      </div>
    </template>
  </section>
</template>
