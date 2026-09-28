<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import type { DocumentVersionWire } from '../../generated/contracts';

import {
  createRecordReviewCase,
  issueRecordCenterLease,
  listRecordCenterWorklist,
  listRecordReviewCases,
  transitionRecordReviewCase,
  type RecordCenterWorklistItem,
  type RecordReviewCase,
} from '../../api/record-center';
import {
  clinicalContext,
  createClinicalDocument,
  issueDocumentLease,
  loadDocument,
  saveDocumentDraft,
  selectOutpatientContext,
  voidClinicalDocument,
} from '../../clinical-api';
import BusinessActionDialog from '../components/BusinessActionDialog.vue';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';
import { captureResponseScope, clinicalOrganizationScope } from '../clinical-response-scope';
import RecordQcPreview from '../components/RecordQcPreview.vue';

const router = useRouter();
const busy = ref(false);
const notice = ref('');
const commandError = ref('');
const dialog = ref<'create-review' | 'transition-review' | 'create-document' | 'edit-document' | 'void-document' | null>(null);
const selected = ref<RecordCenterWorklistItem | null>(null);
const selectedDocumentVersion = ref<DocumentVersionWire | null>(null);
const selectedReview = ref<RecordReviewCase | null>(null);
const statusFilter = ref('ALL');
const encounterTypeFilter = ref('ALL');
const searchText = ref('');
const showAll = ref(false);
const qcPreview = ref<RecordCenterWorklistItem | null>(null);
const qualityFilter = ref('ALL');
function refreshQcQueue() { qcPreview.value = null; void centerQuery.refetch(); }
const reviewScope = ref<RecordReviewCase['review_scope']>('RANDOM');
const reviewPriority = ref<RecordReviewCase['priority']>('ROUTINE');
const reviewReason = ref('');
const reviewDueAt = ref('');
const targetReviewStatus = ref<RecordReviewCase['status']>('IN_REVIEW');
const transitionReason = ref('');
const assigneeUserId = ref('');
const documentTypeCode = ref('WS445.2.OUTPATIENT_RECORD');
const documentSections = ref<Record<string, string>>({});
const documentVoidReason = ref('');
const organizationScope = computed(clinicalOrganizationScope);

watch(organizationScope, () => {
  busy.value = false;
  notice.value = '';
  commandError.value = '';
  dialog.value = null;
  selected.value = null;
  selectedDocumentVersion.value = null;
  selectedReview.value = null;
  qcPreview.value = null;
});

const sectionFields = [
  ['chief_complaint', '主诉'], ['present_illness', '现病史'], ['past_history', '既往史'],
  ['allergy_history', '过敏史'], ['physical_exam', '体格检查'], ['auxiliary_exam', '辅助检查'],
  ['assessment', '诊断与评估'], ['treatment_plan', '治疗计划'], ['followup_plan', '复诊与随访计划'],
] as const;

const centerQuery = useQuery({
  queryKey: computed(() => ['clinical', 'record-center-worklist-v2', organizationScope.value]),
  queryFn: async () => {
    const current = captureResponseScope(clinicalOrganizationScope);
    const lease = await issueRecordCenterLease();
    if (!current()) throw new Error('机构范围已切换');
    const [worklist, reviewCases] = await Promise.all([
      listRecordCenterWorklist(lease),
      listRecordReviewCases(lease),
    ]);
    if (!current()) throw new Error('机构范围已切换');
    return { lease, worklist, reviewCases };
  },
  retry: false,
  staleTime: 0,
  gcTime: 0,
});

const issue = computed(() => centerQuery.error.value ? toClinicalIssue(centerQuery.error.value) : null);
const documents = computed(() => centerQuery.data.value?.worklist ?? []);
const reviewCases = computed(() => centerQuery.data.value?.reviewCases ?? []);
const activeDocuments = computed(() => documents.value.filter((item) => item.status !== 'VOID'));
const filteredDocuments = computed(() => documents.value.filter((item) => {
  const matchesStatus = statusFilter.value === 'ALL' || item.status === statusFilter.value;
  const matchesEncounter = encounterTypeFilter.value === 'ALL' || item.encounter_type === encounterTypeFilter.value;
  const needle = searchText.value.trim().toLowerCase();
  const matchesText = !needle || [item.patient_name, item.encounter_id, item.document_type_code,
    item.document_id, item.author_name, item.department_name]
    .some((value) => String(value ?? '').toLowerCase().includes(needle));
  const matchesQuality = qualityFilter.value === 'ALL'
    || (qualityFilter.value === 'BLOCKING' && item.has_blocking_finding)
    || (qualityFilter.value === 'OPEN' && item.open_finding_count > 0)
    || (qualityFilter.value === 'NONE' && item.open_finding_count === 0);
  return matchesStatus && matchesEncounter && matchesText && matchesQuality;
}));
const visibleDocuments = computed(() => showAll.value ? filteredDocuments.value : filteredDocuments.value.slice(0, 10));
const draftCount = computed(() => activeDocuments.value.filter((item) => item.status === 'DRAFT').length);
const blockingCount = computed(() => activeDocuments.value.filter((item) => item.has_blocking_finding).length);
const overdueReviewCount = computed(() => reviewCases.value.filter((item) => !['CLOSED', 'VOID'].includes(item.status)
  && new Date(item.due_at).getTime() < Date.now()).length);

function futureLocalDate(hours = 24) {
  const date = new Date(Date.now() + hours * 60 * 60 * 1000);
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60 * 1000);
  return local.toISOString().slice(0, 16);
}

function beginCreateReview(document?: RecordCenterWorklistItem) {
  const candidate = document ?? filteredDocuments.value.find((item) => !item.review_case_id && item.status !== 'VOID');
  if (!candidate) {
    notice.value = '当前筛选结果中没有可创建抽查的病历；已有活动抽查需先闭环或作废。';
    return;
  }
  selected.value = candidate;
  reviewScope.value = candidate.status === 'CORRECTED' ? 'CORRECTION' : 'RANDOM';
  reviewPriority.value = candidate.has_blocking_finding ? 'URGENT' : 'ROUTINE';
  reviewReason.value = candidate.has_blocking_finding ? '针对现有阻断性质控问题发起病历专项抽查' : '';
  reviewDueAt.value = futureLocalDate(candidate.has_blocking_finding ? 4 : 24);
  assigneeUserId.value = '';
  dialog.value = 'create-review';
}

function beginTransition(document: RecordCenterWorklistItem) {
  const review = reviewCases.value.find((item) => item.review_case_id === document.review_case_id);
  if (!review) {
    commandError.value = 'RECORD_REVIEW_NOT_FOUND：活动抽查单尚未加载，请刷新后重试。';
    return;
  }
  selected.value = document;
  selectedReview.value = review;
  targetReviewStatus.value = nextStatuses(review.status)[0] ?? 'IN_REVIEW';
  transitionReason.value = '';
  assigneeUserId.value = review.assignee_user_id ?? clinicalContext.userId;
  dialog.value = 'transition-review';
}

function blankSections(source?: Record<string, unknown>) {
  return Object.fromEntries(sectionFields.map(([key]) => [key, String(source?.[key] ?? '')]));
}

function selectDocumentContext(document: RecordCenterWorklistItem, documentId: string | null = document.document_id) {
  selectOutpatientContext({
    patientId: document.patient_id,
    encounterId: document.encounter_id,
    patientDisplayName: document.patient_name,
    documentId,
  });
}

function beginCreateDocument(document: RecordCenterWorklistItem) {
  selected.value = document;
  selectedDocumentVersion.value = null;
  documentTypeCode.value = document.document_type_code;
  documentSections.value = blankSections();
  dialog.value = 'create-document';
}

async function beginEditDocument(document: RecordCenterWorklistItem) {
  if (busy.value || document.status !== 'DRAFT') return;
  const current = captureResponseScope(clinicalOrganizationScope);
  busy.value = true; commandError.value = '';
  try {
    selectDocumentContext(document);
    const lease = await issueDocumentLease();
    if (!current()) return;
    const version = await loadDocument(lease, document.document_id);
    if (!current()) return;
    selected.value = document;
    selectedDocumentVersion.value = version;
    documentSections.value = blankSections(version.sections);
    dialog.value = 'edit-document';
  } catch (error) {
    if (current()) { const failure = toClinicalIssue(error); commandError.value = `${failure.code}：${failure.message}`; }
  } finally { if (current()) busy.value = false; }
}

function beginVoidDocument(document: RecordCenterWorklistItem) {
  selected.value = document;
  selectedDocumentVersion.value = null;
  documentVoidReason.value = '';
  dialog.value = 'void-document';
}

async function createDocument() {
  if (!selected.value || !documentTypeCode.value.trim() || busy.value) return;
  const current = captureResponseScope(clinicalOrganizationScope);
  busy.value = true; commandError.value = '';
  try {
    selectDocumentContext(selected.value, null);
    const lease = await issueDocumentLease();
    if (!current()) return;
    const created = await createClinicalDocument(lease, documentTypeCode.value, documentSections.value);
    if (!current()) return;
    clinicalContext.documentId = created.document_id;
    dialog.value = null;
    notice.value = '新病历草稿已写入所选患者就诊，并立即进入质控与审签流程。';
    await centerQuery.refetch();
  } catch (error) {
    if (current()) { const failure = toClinicalIssue(error); commandError.value = `${failure.code}：${failure.message}`; }
  } finally { if (current()) busy.value = false; }
}

async function updateDocument() {
  if (!selected.value || !selectedDocumentVersion.value || busy.value) return;
  const current = captureResponseScope(clinicalOrganizationScope);
  busy.value = true; commandError.value = '';
  try {
    selectDocumentContext(selected.value);
    const lease = await issueDocumentLease();
    if (!current()) return;
    await saveDocumentDraft(lease, selectedDocumentVersion.value, documentSections.value);
    if (!current()) return;
    dialog.value = null;
    notice.value = '已生成新的不可变草稿版本，旧版本与审计证据保留。';
    await centerQuery.refetch();
  } catch (error) {
    if (current()) { const failure = toClinicalIssue(error); commandError.value = `${failure.code}：${failure.message}`; }
  } finally { if (current()) busy.value = false; }
}

async function voidDocument() {
  if (!selected.value || documentVoidReason.value.trim().length < 4 || busy.value) return;
  const current = captureResponseScope(clinicalOrganizationScope);
  const target = selected.value;
  busy.value = true; commandError.value = '';
  try {
    selectDocumentContext(target);
    const lease = await issueDocumentLease();
    if (!current()) return;
    const currentVersion = await loadDocument(lease, target.document_id);
    if (!current()) return;
    await voidClinicalDocument(lease, currentVersion, documentVoidReason.value);
    if (!current()) return;
    dialog.value = null;
    notice.value = '病历草稿已业务作废；正文、版本和操作证据未物理删除。';
    await centerQuery.refetch();
  } catch (error) {
    if (current()) { const failure = toClinicalIssue(error); commandError.value = `${failure.code}：${failure.message}`; }
  } finally { if (current()) busy.value = false; }
}

function nextStatuses(status: RecordReviewCase['status']): RecordReviewCase['status'][] {
  return ({
    OPEN: ['ASSIGNED', 'IN_REVIEW', 'VOID'],
    ASSIGNED: ['IN_REVIEW', 'VOID'],
    IN_REVIEW: ['REMEDIATION', 'VERIFIED', 'VOID'],
    REMEDIATION: ['IN_REVIEW', 'VERIFIED', 'VOID'],
    VERIFIED: ['CLOSED', 'IN_REVIEW', 'VOID'],
    CLOSED: [], VOID: [],
  } as Record<RecordReviewCase['status'], RecordReviewCase['status'][]>)[status];
}

async function createReview() {
  const data = centerQuery.data.value;
  if (!data || !selected.value || reviewReason.value.trim().length < 4 || !reviewDueAt.value) return;
  const current = captureResponseScope(clinicalOrganizationScope);
  busy.value = true;
  commandError.value = '';
  try {
    await createRecordReviewCase(data.lease, {
      documentId: selected.value.document_id,
      documentVersionId: selected.value.document_version_id,
      reviewScope: reviewScope.value,
      reason: reviewReason.value,
      priority: reviewPriority.value,
      assigneeUserId: assigneeUserId.value || null,
      dueAt: new Date(reviewDueAt.value).toISOString(),
    });
    if (!current()) return;
    dialog.value = null;
    notice.value = '病历抽查单已创建；任务、时限、审计链和流程事件均已写入。';
    await centerQuery.refetch();
  } catch (error) {
    if (current()) { const failure = toClinicalIssue(error); commandError.value = `${failure.code}：${failure.message}`; }
  } finally {
    if (current()) busy.value = false;
  }
}

async function transitionReview() {
  const data = centerQuery.data.value;
  if (!data || !selectedReview.value || transitionReason.value.trim().length < 4) return;
  const current = captureResponseScope(clinicalOrganizationScope);
  busy.value = true;
  commandError.value = '';
  try {
    await transitionRecordReviewCase(data.lease, selectedReview.value, {
      targetStatus: targetReviewStatus.value,
      reason: transitionReason.value,
      assigneeUserId: targetReviewStatus.value === 'ASSIGNED' ? assigneeUserId.value : null,
    });
    if (!current()) return;
    dialog.value = null;
    notice.value = targetReviewStatus.value === 'VOID'
      ? '抽查单已业务作废，历史事件和审计证据保留。'
      : '抽查状态已流转，并已写入不可变事件与审计链。';
    await centerQuery.refetch();
  } catch (error) {
    if (current()) { const failure = toClinicalIssue(error); commandError.value = `${failure.code}：${failure.message}`; }
  } finally {
    if (current()) busy.value = false;
  }
}

function openDocument(document: RecordCenterWorklistItem, target = '/record-editor') {
  selectDocumentContext(document);
  void router.push(target);
}

function statusLabel(status: string) {
  return ({ DRAFT: '草稿', READY_TO_SIGN: '待签署', SIGNED: '已签署', CORRECTED: '已更正', VOID: '已作废' } as Record<string, string>)[status] || status;
}

function encounterLabel(type: string) {
  return ({ OUTPATIENT: '门诊', EMERGENCY: '急诊', INPATIENT: '住院' } as Record<string, string>)[type] || type;
}

function reviewStatusLabel(status: string | null) {
  if (!status) return '未抽查';
  return ({ OPEN: '待分派', ASSIGNED: '已分派', IN_REVIEW: '审查中', REMEDIATION: '整改中',
    VERIFIED: '已复核', CLOSED: '已闭环', VOID: '已作废' } as Record<string, string>)[status] || status;
}

function formatTime(value: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—';
}
</script>

<template>
  <section data-page-root class="content vue-native-page">
    <div class="page-heading record-center-heading"><div><h1>全院病历中心</h1><p>按岗位权限聚合门诊、急诊、住院病历；临床人员仅见诊疗关系内患者，病案与质控岗位按院区授权查看</p></div>
      <div class="toolbar-actions"><button class="btn" type="button" @click="centerQuery.refetch()">刷新队列</button><button class="btn primary" type="button" @click="beginCreateReview()">创建病历抽查</button></div></div>

    <ClinicalPageState v-if="centerQuery.isPending.value" kind="loading" message="正在按岗位权限加载全院病历责任队列" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="centerQuery.refetch()" />
    <template v-else>
      <p v-if="notice" class="record-command-message" role="status">{{ notice }}</p><p v-if="commandError" class="record-command-error" role="alert">{{ commandError }}</p>
      <section class="metric-grid record-metrics" aria-label="病历指标"><article class="metric"><span class="name">授权范围病历</span><strong class="value">{{ activeDocuments.length }}</strong><small class="trend">门急住统一队列 · 作废 {{ documents.length - activeDocuments.length }} 份</small></article><article class="metric"><span class="name">待审签草稿</span><strong class="value">{{ draftCount }}</strong><small class="trend">按当前队列快照统计</small></article><article class="metric"><span class="name">质控硬阻断</span><strong class="value danger-text">{{ blockingCount }}</strong><small class="trend">开放 BLOCKING 发现</small></article><article class="metric"><span class="name">逾期抽查</span><strong class="value">{{ overdueReviewCount }}</strong><small class="trend">按当前队列的截止时间统计；归档须查看正式准备检查</small></article></section>
      <section class="card record-prototype-workbench"><div class="toolbar record-center-filters"><select v-model="statusFilter" class="select" aria-label="病历状态"><option value="ALL">全部状态</option><option value="DRAFT">草稿</option><option value="READY_TO_SIGN">待签署</option><option value="SIGNED">已签署</option><option value="CORRECTED">已更正</option><option value="VOID">已作废</option></select><select v-model="encounterTypeFilter" class="select" aria-label="就诊类型"><option value="ALL">门急住全部</option><option value="OUTPATIENT">门诊</option><option value="EMERGENCY">急诊</option><option value="INPATIENT">住院</option></select><select v-model="qualityFilter" class="select" aria-label="质控问题筛选"><option value="ALL">全部质量状态</option><option value="BLOCKING">存在硬阻断</option><option value="OPEN">存在开放问题</option><option value="NONE">暂无开放问题（非通过）</option></select><select class="select" aria-label="排序"><option>按阻断、优先级与时限</option></select><input v-model="searchText" class="search" placeholder="患者、就诊号、病历类型、作者或科室"></div>
        <div v-if="documents.length === 0" class="record-evidence-empty"><h2>授权范围内暂无病历</h2><p>这不是演示空状态：请检查岗位授权、诊疗关系和当前院区上下文。</p></div>
        <div v-else class="record-prototype-table-wrap"><table class="table"><thead><tr><th>业务域/患者</th><th>文书</th><th>状态/证据</th><th>质量</th><th>抽查流程</th><th>更新时间</th><th></th></tr></thead><tbody><tr v-for="document in visibleDocuments" :key="document.document_id" :class="{ voided: document.status === 'VOID' }"><td><span class="status blue">{{ encounterLabel(document.encounter_type) }}</span><br><b>{{ document.patient_name }}</b><br><span class="meta">{{ document.department_name || '未归属科室' }}</span></td><td><b>{{ document.document_type_code }}</b><br><span class="meta">{{ document.author_name }} · v{{ document.version_no }} · …{{ document.document_id.slice(-8) }}</span></td><td>{{ statusLabel(document.status) }}<br><span class="status" :class="document.has_valid_signature ? 'green' : 'amber'">{{ document.has_valid_signature ? '有效签名证据' : '无有效签名证据' }}</span></td><td><span class="status" :class="document.has_blocking_finding ? 'red' : document.open_finding_count ? 'amber' : 'blue'">{{ document.has_blocking_finding ? '硬阻断' : document.open_finding_count ? `${document.open_finding_count} 项开放` : '暂无开放问题（非通过）' }}</span><br><button class="btn sm" type="button" :disabled="busy" @click="qcPreview = { ...document }">查看质控</button></td><td><span class="status" :class="document.review_status ? 'amber' : 'blue'">{{ reviewStatusLabel(document.review_status) }}</span><br><span v-if="document.review_due_at" class="meta">截至 {{ formatTime(document.review_due_at) }}</span></td><td>{{ formatTime(document.updated_at) }}</td><td><div class="record-row-actions"><button class="btn sm" type="button" @click="openDocument(document, '/record-versions')">证据</button><button class="btn sm primary" type="button" :disabled="document.status === 'VOID'" @click="openDocument(document)">处理</button><button class="btn sm" type="button" :disabled="document.status === 'VOID'" @click="beginCreateDocument(document)">同次新建</button><button class="btn sm" type="button" :disabled="document.status !== 'DRAFT' || busy" @click="beginEditDocument(document)">编辑</button><button class="btn sm danger" type="button" :disabled="document.status !== 'DRAFT' || busy" @click="beginVoidDocument(document)">作废</button><button v-if="!document.review_case_id && document.status !== 'VOID'" class="btn sm" type="button" @click="beginCreateReview(document)">抽查</button><button v-else-if="document.review_case_id" class="btn sm" type="button" @click="beginTransition(document)">流转</button></div></td></tr><tr v-if="visibleDocuments.length === 0"><td colspan="7">没有符合当前筛选条件的病历，可调整质量、状态或患者筛选。</td></tr></tbody></table><button v-if="filteredDocuments.length > 10" class="btn record-expand" type="button" @click="showAll = !showAll">{{ showAll ? '收起列表' : `查看全部 ${filteredDocuments.length} 份` }}</button></div>
        <div class="card-body"><div class="notice info"><div class="notice-title">中国医疗生产控制</div>病历不做物理删除；抽查单采用状态流转、乐观锁、幂等键、不可变事件、审计哈希链与出站事件。进入单病历时重新建立患者/就诊上下文。</div></div>
      </section>
    </template>
    <RecordQcPreview v-if="qcPreview" :key="`${qcPreview.document_id}:${qcPreview.document_version_id}`" :target="qcPreview" @close="qcPreview = null" @refresh="refreshQcQueue" />

    <BusinessActionDialog :open="dialog === 'create-review'" :title="`创建病历抽查 · ${selected?.patient_name ?? ''}`" description="创建后立即进入病案质控队列，并受时限、并发版本和审计控制。" eyebrow="全院病历 / 抽查" confirm-label="创建并纳入流程" :busy="busy" :confirm-disabled="reviewReason.trim().length < 4 || !reviewDueAt" width="wide" @cancel="dialog = null" @confirm="createReview"><div class="notice info">{{ selected?.document_type_code }} · v{{ selected?.version_no }} · {{ selected?.document_id }}</div><div class="dialog-grid"><label>抽查类型<select v-model="reviewScope"><option value="RANDOM">随机抽查</option><option value="FOCUSED">专项抽查</option><option value="TERMINAL">终末病历抽查</option><option value="CORRECTION">更正一致性抽查</option></select></label><label>优先级<select v-model="reviewPriority"><option value="ROUTINE">常规</option><option value="HIGH">高</option><option value="URGENT">紧急</option></select></label><label>完成时限<input v-model="reviewDueAt" type="datetime-local"></label><label>分派用户 UUID（可选）<input v-model="assigneeUserId" autocomplete="off" placeholder="留空进入待分派"></label></div><label>抽查依据与范围（至少 4 字）<textarea v-model="reviewReason" rows="4" maxlength="1000" /></label></BusinessActionDialog>
    <BusinessActionDialog :open="dialog === 'transition-review'" :title="`抽查流转 · ${selectedReview?.patient_name ?? ''}`" description="每次流转均校验行版本并追加不可变事件，不覆盖历史。" eyebrow="全院病历 / 抽查流转" :confirm-label="targetReviewStatus === 'VOID' ? '业务作废并留痕' : '确认流转'" :danger="targetReviewStatus === 'VOID'" :busy="busy" :confirm-disabled="transitionReason.trim().length < 4 || (targetReviewStatus === 'ASSIGNED' && !assigneeUserId)" width="wide" @cancel="dialog = null" @confirm="transitionReview"><div class="notice info">当前：{{ reviewStatusLabel(selectedReview?.status ?? null) }} · row {{ selectedReview?.row_version }} · {{ selectedReview?.reason }}</div><div class="dialog-grid"><label>目标状态<select v-model="targetReviewStatus"><option v-for="status in nextStatuses(selectedReview?.status ?? 'VOID')" :key="status" :value="status">{{ reviewStatusLabel(status) }}</option></select></label><label v-if="targetReviewStatus === 'ASSIGNED'">分派用户 UUID<input v-model="assigneeUserId" autocomplete="off"></label></div><label>{{ targetReviewStatus === 'VOID' ? '作废原因' : '流转意见' }}（至少 4 字）<textarea v-model="transitionReason" rows="4" maxlength="1000" /></label></BusinessActionDialog>
    <BusinessActionDialog :open="dialog === 'create-document'" :title="`同次就诊新建病历 · ${selected?.patient_name ?? ''}`" description="将新建独立病历主体和不可变草稿 v1，并绑定所选患者与就诊。" eyebrow="全院病历 / 新建" confirm-label="新建并纳入流程" :busy="busy" :confirm-disabled="!documentTypeCode.trim()" width="wide" @cancel="dialog = null" @confirm="createDocument"><label>文书类型编码<input v-model="documentTypeCode" autocomplete="off"></label><div class="dialog-grid"><label v-for="field in sectionFields" :key="field[0]">{{ field[1] }}<textarea v-model="documentSections[field[0]]" rows="2" /></label></div></BusinessActionDialog>
    <BusinessActionDialog :open="dialog === 'edit-document'" :title="`编辑草稿 · ${selected?.patient_name ?? ''}`" description="保存会追加新的不可变草稿版本，不覆盖旧版本。" eyebrow="全院病历 / 编辑" confirm-label="保存新版本" :busy="busy" width="wide" @cancel="dialog = null" @confirm="updateDocument"><div class="dialog-grid"><label v-for="field in sectionFields" :key="field[0]">{{ field[1] }}<textarea v-model="documentSections[field[0]]" rows="2" /></label></div></BusinessActionDialog>
    <BusinessActionDialog :open="dialog === 'void-document'" :title="`作废病历草稿 · ${selected?.patient_name ?? ''}`" description="仅草稿允许业务作废；已签文书须走撤签和依法更正。" eyebrow="全院病历 / 作废" confirm-label="确认作废并留痕" danger :busy="busy" :confirm-disabled="documentVoidReason.trim().length < 4" @cancel="dialog = null" @confirm="voidDocument"><p class="dialog-warning">不会物理删除正文、版本、签名或审计证据。</p><label>作废原因（至少 4 字）<textarea v-model="documentVoidReason" rows="4" maxlength="2000" /></label></BusinessActionDialog>
  </section>
</template>

<style scoped>
.record-metrics .metric{display:flex;flex-direction:column;align-items:flex-start;gap:5px;min-width:0}.record-metrics .value{line-height:1.2}.record-metrics .trend{line-height:1.5;overflow-wrap:anywhere}
</style>
