<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref, watch } from 'vue';
import type { ContextLeaseWire } from '../../generated/contracts';
import {
  createDentalQcReview, createDermatologyQcReview, createEntQcReview, createMentalHealthQcReview,
  createNeonatalQcReview, createObstetricQcReview, createOphthalmologyQcReview, createPediatricQcReview,
  createReproductiveQcReview, createTcmQcReview, issueSpecialtyEncounterLease, issueSpecialtyPatientLease,
  listArtEmbryoTransferRecords, listDentalQcReviews, listDentalTreatmentRecords, listDermatologyBiologicFollowups,
  listDermatologyBiologicScreenings, listDermatologyQcReviews, listEntAirwayRiskHandovers, listEntQcReviews,
  listMentalHealthCrisisFollowups, listMentalHealthCrisisHandovers, listMentalHealthQcReviews,
  listNeonatalQcReviews, listNeonatalScreeningRecords, listNeonatalWristbandVerifications,
  listObstetricAntenatalExams, listObstetricDeliveryRecords, listObstetricQcReviews,
  listOphthalmologyPostopFollowups, listOphthalmologyPreopVerifications, listOphthalmologyQcReviews,
  listPediatricFollowupRecords, listPediatricGrowthRecords, listPediatricQcReviews,
  listReproductiveQcReviews, listTcmFourExaminations, listTcmHerbalPrescriptions, listTcmQcReviews,
} from '../../api/specialty-layers';
import { listArtCycleRecords, listDentalRecords, listEntRecords } from '../../api/specialty';
import { toClinicalIssue } from '../clinical-error';
import { localDateTimeInputValue } from '../date-input';
import ClinicalPageState from './ClinicalPageState.vue';

type Candidate = { type: string; id: string; label: string };
type Review = { review_id: string; reviewed_record_type: string; reviewed_record_id: string; review_conclusion: string; defect_description?: string | null; reviewed_at: string };
type Create = (lease: ContextLeaseWire, input: any) => Promise<unknown>;
type DomainKey = 'ophthalmology' | 'ent' | 'dental' | 'dermatology' | 'mentalhealth' | 'neonatal' | 'obstetrics' | 'pediatrics' | 'reproductive' | 'tcm';
type Domain = {
  purpose: string; eyebrow: string; description: string; types: Array<{ value: string; label: string }>;
  candidates: (lease: ContextLeaseWire) => Promise<Candidate[]>;
  list: (lease: ContextLeaseWire) => Promise<Review[]>; create: Create;
};
const props = defineProps<{ domain: DomainKey }>();
const candidate = (type: string, idKey: string, label: string) => (row: object): Candidate => ({ type, id: String((row as Record<string, unknown>)[idKey]), label });
const two = async (lease: ContextLeaseWire, left: () => Promise<object[]>, right: () => Promise<object[]>, leftType: string, leftId: string, leftLabel: string, rightType: string, rightId: string, rightLabel: string) => {
  void lease;
  return [
  ...(await left()).map(candidate(leftType, leftId, leftLabel)), ...(await right()).map(candidate(rightType, rightId, rightLabel)),
  ];
};
const domains: Record<DomainKey, Domain> = {
  ophthalmology: { purpose: 'OPHTHALMOLOGY_QC', eyebrow: '专科其余层 / 眼科 · 质控', description: '从当前患者的术前核对或术后随访中选择真实记录，FAIL 会自动创建整改任务。', types: [{ value: 'PREOP_VERIFICATION', label: '术前核对' }, { value: 'POSTOP_FOLLOWUP', label: '术后随访' }], candidates: (lease) => two(lease, () => listOphthalmologyPreopVerifications(lease), () => listOphthalmologyPostopFollowups(lease), 'PREOP_VERIFICATION', 'verification_id', '术前核对', 'POSTOP_FOLLOWUP', 'followup_id', '术后随访'), list: listOphthalmologyQcReviews, create: createOphthalmologyQcReview },
  ent: { purpose: 'ENT_QC', eyebrow: '专科其余层 / 耳鼻喉 · 质控', description: '从当前患者的气道风险交接或耳鼻喉记录中选择真实记录，FAIL 会自动创建整改任务。', types: [{ value: 'AIRWAY_RISK_HANDOVER', label: '气道风险交接' }, { value: 'RECORD', label: '耳鼻喉记录' }], candidates: (lease) => two(lease, () => listEntAirwayRiskHandovers(lease), () => listEntRecords(lease), 'AIRWAY_RISK_HANDOVER', 'handover_id', '气道风险交接', 'RECORD', 'ent_record_id', '耳鼻喉记录'), list: listEntQcReviews, create: createEntQcReview },
  dental: { purpose: 'DENTAL_QC', eyebrow: '专科其余层 / 口腔 · 质控', description: '从当前患者的治疗或口腔记录中选择真实记录，FAIL 会自动创建整改任务。', types: [{ value: 'TREATMENT_RECORD', label: '治疗记录' }, { value: 'RECORD', label: '口腔记录' }], candidates: (lease) => two(lease, () => listDentalTreatmentRecords(lease), () => listDentalRecords(lease), 'TREATMENT_RECORD', 'dental_treatment_record_id', '治疗记录', 'RECORD', 'dental_record_id', '口腔记录'), list: listDentalQcReviews, create: createDentalQcReview },
  dermatology: { purpose: 'DERMATOLOGY_QC', eyebrow: '专科其余层 / 皮肤 · 质控', description: '从当前患者的生物制剂筛查或随访中选择真实记录，FAIL 会自动创建整改任务。', types: [{ value: 'BIOLOGIC_SCREENING', label: '生物制剂筛查' }, { value: 'BIOLOGIC_FOLLOWUP', label: '生物制剂随访' }], candidates: (lease) => two(lease, () => listDermatologyBiologicScreenings(lease), () => listDermatologyBiologicFollowups(lease), 'BIOLOGIC_SCREENING', 'screening_id', '生物制剂筛查', 'BIOLOGIC_FOLLOWUP', 'followup_id', '生物制剂随访'), list: listDermatologyQcReviews, create: createDermatologyQcReview },
  mentalhealth: { purpose: 'MENTAL_HEALTH_QC', eyebrow: '专科其余层 / 精神心理 · 质控', description: '从当前患者的危机交接或随访中选择真实记录，FAIL 会自动创建整改任务。', types: [{ value: 'CRISIS_HANDOVER', label: '危机交接' }, { value: 'CRISIS_FOLLOWUP', label: '危机随访' }], candidates: (lease) => two(lease, () => listMentalHealthCrisisHandovers(lease), () => listMentalHealthCrisisFollowups(lease), 'CRISIS_HANDOVER', 'crisis_handover_id', '危机交接', 'CRISIS_FOLLOWUP', 'followup_id', '危机随访'), list: listMentalHealthQcReviews, create: createMentalHealthQcReview },
  neonatal: { purpose: 'NEONATAL_QC', eyebrow: '专科其余层 / 新生儿 · 质控', description: '从当前患者的腕带核验或筛查记录中选择真实记录，FAIL 会自动创建整改任务。', types: [{ value: 'WRISTBAND_VERIFICATION', label: '腕带核验' }, { value: 'SCREENING_RECORD', label: '筛查记录' }], candidates: (lease) => two(lease, () => listNeonatalWristbandVerifications(lease), () => listNeonatalScreeningRecords(lease), 'WRISTBAND_VERIFICATION', 'verification_id', '腕带核验', 'SCREENING_RECORD', 'screening_id', '筛查记录'), list: listNeonatalQcReviews, create: createNeonatalQcReview },
  obstetrics: { purpose: 'OBGYN_QC', eyebrow: '专科其余层 / 妇产 · 质控', description: '从当前患者的分娩或产检记录中选择真实记录，FAIL 会自动创建整改任务。', types: [{ value: 'DELIVERY', label: '分娩记录' }, { value: 'ANTENATAL_EXAM', label: '产检记录' }], candidates: (lease) => two(lease, () => listObstetricDeliveryRecords(lease), () => listObstetricAntenatalExams(lease), 'DELIVERY', 'delivery_record_id', '分娩记录', 'ANTENATAL_EXAM', 'exam_id', '产检记录'), list: listObstetricQcReviews, create: createObstetricQcReview },
  pediatrics: { purpose: 'PEDIATRIC_QC', eyebrow: '专科其余层 / 儿科 · 质控', description: '从当前患者的生长或随访记录中选择真实记录，FAIL 会自动创建整改任务。', types: [{ value: 'GROWTH_RECORD', label: '生长记录' }, { value: 'FOLLOWUP_RECORD', label: '随访记录' }], candidates: (lease) => two(lease, () => listPediatricGrowthRecords(lease), () => listPediatricFollowupRecords(lease), 'GROWTH_RECORD', 'growth_record_id', '生长记录', 'FOLLOWUP_RECORD', 'followup_id', '随访记录'), list: listPediatricQcReviews, create: createPediatricQcReview },
  reproductive: { purpose: 'REPRODUCTIVE_QC', eyebrow: '专科其余层 / 生殖 · 质控', description: '从当前患者的 ART 周期或胚胎移植记录中选择真实记录，FAIL 会自动创建整改任务。', types: [{ value: 'ART_CYCLE', label: 'ART 周期' }, { value: 'EMBRYO_TRANSFER', label: '胚胎移植' }], candidates: (lease) => two(lease, () => listArtCycleRecords(lease), () => listArtEmbryoTransferRecords(lease), 'ART_CYCLE', 'cycle_id', 'ART 周期', 'EMBRYO_TRANSFER', 'embryo_transfer_id', '胚胎移植'), list: listReproductiveQcReviews, create: createReproductiveQcReview },
  tcm: { purpose: 'TCM_QC', eyebrow: '专科其余层 / 中医 · 质控', description: '从当前患者的方药或四诊记录中选择真实记录，FAIL 会自动创建整改任务。', types: [{ value: 'HERBAL_PRESCRIPTION', label: '方药记录' }, { value: 'FOUR_EXAMINATIONS', label: '四诊记录' }], candidates: (lease) => two(lease, () => listTcmHerbalPrescriptions(lease), () => listTcmFourExaminations(lease), 'HERBAL_PRESCRIPTION', 'prescription_id', '方药记录', 'FOUR_EXAMINATIONS', 'exam_id', '四诊记录'), list: listTcmQcReviews, create: createTcmQcReview },
};
const definition = computed(() => domains[props.domain]);
const patientLeaseQuery = useQuery({ queryKey: ['specialty-qc', props.domain, 'patient-lease'], queryFn: () => issueSpecialtyPatientLease(definition.value.purpose), retry: false, staleTime: 5 * 60_000, gcTime: 0 });
const encounterLeaseQuery = useQuery({ queryKey: ['specialty-qc', props.domain, 'encounter-lease'], queryFn: () => issueSpecialtyEncounterLease(definition.value.purpose), retry: false, staleTime: 5 * 60_000, gcTime: 0 });
const reviewsQuery = useQuery({ queryKey: ['specialty-qc', props.domain, 'reviews'], queryFn: () => definition.value.list(patientLeaseQuery.data.value!), enabled: () => Boolean(patientLeaseQuery.data.value), retry: false });
const candidatesQuery = useQuery({ queryKey: ['specialty-qc', props.domain, 'candidates'], queryFn: () => definition.value.candidates(patientLeaseQuery.data.value!), enabled: () => Boolean(patientLeaseQuery.data.value), retry: false });
const errors = computed(() => [patientLeaseQuery.error.value, encounterLeaseQuery.error.value, reviewsQuery.error.value, candidatesQuery.error.value]);
const issue = computed(() => errors.value.find(Boolean) ? toClinicalIssue(errors.value.find(Boolean)) : null);
const form = reactive({ reviewedRecordType: definition.value.types[0].value, reviewedRecordId: '', reviewConclusion: 'PASS', defectDescription: '', reviewedAt: localDateTimeInputValue() });
const candidates = computed(() => (candidatesQuery.data.value ?? []).filter((entry) => entry.type === form.reviewedRecordType));
watch(candidates, (values) => { if (!values.some((entry) => entry.id === form.reviewedRecordId)) form.reviewedRecordId = values[0]?.id ?? ''; }, { immediate: true });
const busy = ref(''); const notice = ref('');
const pending = computed(() => patientLeaseQuery.isPending.value || encounterLeaseQuery.isPending.value || reviewsQuery.isPending.value || candidatesQuery.isPending.value);
const formatDate = (value: string) => new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
async function reload() { notice.value = ''; await Promise.all([reviewsQuery.refetch(), candidatesQuery.refetch()]); }
async function submit() { const lease = encounterLeaseQuery.data.value; if (!lease || busy.value || !form.reviewedRecordId) return; busy.value = 'create'; notice.value = ''; try { await definition.value.create(lease, { reviewed_record_type: form.reviewedRecordType, reviewed_record_id: form.reviewedRecordId, review_conclusion: form.reviewConclusion, defect_description: form.defectDescription.trim() || null, reviewed_at: form.reviewedAt }); notice.value = form.reviewConclusion === 'FAIL' ? '质控复核已创建，整改任务已进入任务中心。' : '质控复核已创建并留痕。'; form.defectDescription = ''; await reviewsQuery.refetch(); } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; } finally { busy.value = ''; } }
</script>

<template>
  <section data-page-root class="content admin-content vue-native-page">
    <div class="page-heading admin-heading"><div><p class="eyebrow">{{ definition.eyebrow }}</p><h1>质控复核</h1><p>{{ definition.description }}</p></div><div class="admin-inline-tools"><button class="button secondary" :disabled="Boolean(busy)" @click="reload">刷新</button></div></div>
    <ClinicalPageState v-if="pending" kind="loading" message="正在读取真实质控来源记录" /><ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reload" />
    <template v-else><p v-if="notice" class="admin-notice" role="status">{{ notice }}</p><div class="admin-layout"><section class="admin-panel"><header><div><h2>质控复核台账</h2><p>仅展示当前患者的不可变复核记录。</p></div></header><div v-if="!reviewsQuery.data.value?.length" class="admin-empty">暂无质控复核。</div><div v-else class="admin-table-wrap"><table class="admin-table"><thead><tr><th>复核对象</th><th>结论</th><th>缺陷描述</th><th>复核时间</th></tr></thead><tbody><tr v-for="review in reviewsQuery.data.value" :key="review.review_id"><td>{{ review.reviewed_record_type }} · {{ review.reviewed_record_id }}</td><td>{{ review.review_conclusion === 'PASS' ? '通过' : '不通过' }}</td><td>{{ review.defect_description ?? '—' }}</td><td>{{ formatDate(review.reviewed_at) }}</td></tr></tbody></table></div></section><section class="admin-panel admin-form-panel"><header><div><h2>新增质控复核</h2><p>仅可选择当前患者真实存在且归属当前就诊的记录。</p></div></header><form class="admin-form" @submit.prevent="submit"><label><span>复核记录类型</span><select v-model="form.reviewedRecordType"><option v-for="type in definition.types" :key="type.value" :value="type.value">{{ type.label }}</option></select></label><label><span>被复核记录</span><select v-model="form.reviewedRecordId" required :disabled="!candidates.length"><option v-if="!candidates.length" value="">当前类型暂无可复核记录</option><option v-for="entry in candidates" :key="entry.id" :value="entry.id">{{ entry.label }} · {{ entry.id }}</option></select></label><label><span>复核结论</span><select v-model="form.reviewConclusion"><option value="PASS">通过</option><option value="FAIL">不通过（创建整改任务）</option></select></label><label><span>复核时间</span><input v-model="form.reviewedAt" type="datetime-local" required /></label><label><span>缺陷描述</span><textarea v-model="form.defectDescription" rows="3" :required="form.reviewConclusion === 'FAIL'" :placeholder="form.reviewConclusion === 'FAIL' ? 'FAIL 时必填，将成为整改任务说明' : '可选'" /></label><button class="button primary full" :disabled="Boolean(busy) || !form.reviewedRecordId">{{ busy === 'create' ? '正在创建…' : '创建质控复核' }}</button></form></section></div></template>
  </section>
</template>
