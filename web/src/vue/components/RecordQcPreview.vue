<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, nextTick } from 'vue';
import { clinicalContext } from '../../clinical-api';
import type { RecordCenterWorklistItem } from '../../api/record-center';
import { loadRecordQcPreview } from '../../api/record-qc-preview';
import BusinessActionDialog from './BusinessActionDialog.vue';
import ClinicalPageState from './ClinicalPageState.vue';
import RecordQcPanel from './RecordQcPanel.vue';
import EncounterRecordQcPanel from './EncounterRecordQcPanel.vue';
import { toClinicalIssue } from '../clinical-error';

const props = defineProps<{ target: RecordCenterWorklistItem }>();
const emit = defineEmits<{ close: []; refresh: [] }>();
const query = useQuery({
  queryKey: computed(() => ['clinical', 'record-qc-preview', clinicalContext.userId,
    clinicalContext.organizationId, clinicalContext.facilityId,
    props.target.patient_id, props.target.encounter_id, props.target.document_id,
    props.target.document_version_id, props.target.content_hash]),
  queryFn: () => loadRecordQcPreview({ ...props.target }),
  retry: false, staleTime: 0, gcTime: 0,
});
const issue = computed(() => query.error.value ? toClinicalIssue(query.error.value) : null);
const labels: Record<string, string> = {
  chief_complaint: '主诉', present_illness: '现病史', past_history: '既往史', allergy_history: '过敏史',
  physical_exam: '体格检查', auxiliary_exam: '辅助检查', assessment: '诊断与评估', treatment_plan: '诊疗计划',
  followup_plan: '复诊与随访计划', diagnostic_basis: '诊断依据', differential_diagnosis: '鉴别诊断',
  case_features: '病例特点', risk_assessment: '风险评估', provisional_diagnosis: '初步诊断', communication: '沟通记录',
};
const fields = computed(() => Object.fromEntries(Object.keys(query.data.value?.document.sections ?? {}).map(key => [key, labels[key] ?? key])));
async function locate(field: string) {
  await nextTick();
  window.document.getElementById(`qc-preview-field-${field}`)?.focus();
}
function text(value: unknown) { return typeof value === 'string' ? value : value == null ? '' : JSON.stringify(value, null, 2); }
</script>

<template>
  <BusinessActionDialog class="record-qc-preview" open :title="`病历质控预览 · ${target.patient_name}`" description="仅读取所选文书，不切换当前接诊患者、不运行质控、不修改正文。" eyebrow="病历工作台 / 质控" confirm-label="完成查看" width="wide" @confirm="emit('close')" @cancel="emit('close')">
    <p>{{ target.document_type_code }} · 工作台版本 v{{ target.version_no }}。预览不代表已完成临床审查。</p>
    <ClinicalPageState v-if="query.isPending.value" kind="loading" message="正在核验所选文书和质控证据" />
    <template v-else-if="issue"><ClinicalPageState kind="error" :code="issue.code" :message="issue.message" @retry="query.refetch()" /><button type="button" class="btn" @click="emit('refresh')">关闭并刷新工作台</button></template>
    <template v-else-if="query.data.value">
      <RecordQcPanel :snapshot="query.data.value.governance" :version-id="target.document_version_id" :content-hash="target.content_hash" :fields="fields" @locate="locate" />
      <EncounterRecordQcPanel :lease="query.data.value.lease" />
      <section aria-label="质控文书只读正文" class="card card-body">
        <h3>对应文书章节（只读）</h3>
        <p v-if="Object.keys(fields).length === 0">当前文书没有可定位的结构化章节。</p>
        <label v-for="(label, key) in fields" :key="key">{{ label }}<textarea :id="`qc-preview-field-${key}`" :aria-label="`只读章节：${label}`" :value="text(query.data.value.document.sections?.[key])" rows="3" readonly /></label>
      </section>
    </template>
  </BusinessActionDialog>
</template>

<style scoped>
.record-qc-preview { margin: auto; }
</style>
