<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, ref, watch } from 'vue';
import type { ContextLeaseWire } from '../../generated/contracts';
import { loadEncounterRecordQc } from '../../api/encounter-record-qc';
import ClinicalPageState from './ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';

const props = defineProps<{ lease: ContextLeaseWire; revision?: string }>();
const open = ref(false);
const scope = computed(() => JSON.stringify([props.lease.tenant_id, props.lease.organization_id, props.lease.facility_id,
  props.lease.user_id, props.lease.lease_id, props.lease.authorization_watermark, props.lease.patient_id, props.lease.encounter_id]));
watch(scope, () => { open.value = false; }, { flush: 'sync' });
const query = useQuery({ queryKey: computed(() => ['clinical', 'encounter-record-qc', scope.value, props.revision]),
  queryFn: () => loadEncounterRecordQc(props.lease), enabled: () => open.value,
  retry: false, gcTime: 0, staleTime: 0 });
const issue = computed(() => query.error.value ? toClinicalIssue(query.error.value) : null);
const labels = { NOT_RUN: '未检查', STALE: '证据已失效', PASSED: '规则通过', WARNING: '警告', BLOCKED: '阻断' };
</script>

<template>
  <section class="record-qc-panel card card-body" aria-label="本次就诊病历质控汇总">
    <button class="btn" type="button" :aria-expanded="open" @click="open = !open">{{ open ? '收起' : '查看' }}本次就诊质控汇总</button>
    <template v-if="open">
      <p>仅汇总本次就诊已保存的有效文书，不含未保存修改；不等同于跨文书语义检查、出院或归档放行。</p>
      <ClinicalPageState v-if="query.isFetching.value" kind="loading" message="正在读取当前版本及来源水位" />
      <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="query.refetch()" />
      <template v-else-if="query.data.value">
        <p v-if="!query.data.value.total_documents">本次就诊暂无可汇总文书，不代表质控通过。</p>
        <p v-else>本次就诊 {{ query.data.value.total_documents }} 份文书；以下为逐份检查状态。</p>
        <p v-if="query.data.value.has_more" role="alert">当前仅显示前 100 份，不能据此认定整次就诊通过；其余请在病历工作台核对。</p>
        <ul><li v-for="item in query.data.value.documents" :key="item.document_id">
          <strong>{{ item.document_type_code }}</strong> · …{{ item.document_id.slice(-8) }}
          <p><span class="status" :class="item.quality_state === 'BLOCKED' ? 'red' : item.quality_state === 'PASSED' ? 'green' : 'amber'">{{ labels[item.quality_state] }}</span> · 当前版本待处理 {{ item.open_finding_count }} 项 / 阻断 {{ item.blocking_count }} 项</p>
        </li></ul>
        <button class="btn sm" type="button" @click="query.refetch()">刷新本次就诊汇总</button>
      </template>
    </template>
  </section>
</template>

<style scoped>
section { min-width: 0; overflow-wrap: anywhere; }
p, li { font-size: 12px; line-height: 1.65; }
ul { padding-left: 18px; max-height: 360px; overflow-y: auto; }
li { margin-bottom: 12px; }
</style>
