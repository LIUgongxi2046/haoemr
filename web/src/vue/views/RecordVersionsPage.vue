<script setup lang="ts">
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { computed, ref } from 'vue';
import type {
  ContextLeaseWire,
  DocumentCorrectionPropagationWire,
  DocumentGovernanceSnapshotWire,
  DocumentVersionWire,
  SignatureEvidenceDetailWire,
} from '../../generated/contracts';
import {
  clinicalContext,
  createDocumentCorrection,
  issueDocumentLease,
  loadDocumentCorrections,
  loadDocumentGovernance,
  loadDocumentVersions,
  loadEncounterDocuments,
  retryDocumentCorrectionPropagation,
  revokeDocumentSignature,
} from '../../clinical-api';
import { verifyDocumentSignatures, type SignatureVerificationRun } from '../../api/signature-verification';
import BusinessActionDialog from '../components/BusinessActionDialog.vue';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import RecordPatientStrip from '../components/RecordPatientStrip.vue';
import { toClinicalIssue } from '../clinical-error';

const queryClient = useQueryClient();
const busy = ref(false);
const actionError = ref('');
const actionNotice = ref('');
const correctionOpen = ref(false);
const correctionType = ref<'CORRECTION' | 'ADDENDUM'>('CORRECTION');
const correctionReason = ref('');
const correctionSections = ref({ chief_complaint: '', present_illness: '', assessment: '', treatment_plan: '' });
const revokeTarget = ref<SignatureEvidenceDetailWire | null>(null);
const revokeReason = ref('');
const verificationRuns = ref<SignatureVerificationRun[]>([]);
const versionKey = ['clinical', 'record-versions'] as const;

async function loadSelected(lease: ContextLeaseWire, documents: DocumentVersionWire[], documentId: string) {
  const versions = await loadDocumentVersions(lease, documentId);
  const [corrections, governance] = await Promise.all([
    loadDocumentCorrections(lease, documentId),
    Promise.all(versions.map((version) => loadDocumentGovernance(lease, documentId, version.document_version_id))),
  ]);
  return { lease, documents, selected: documentId, versions, corrections, governance };
}

const versionsQuery = useQuery({
  queryKey: versionKey,
  queryFn: async () => {
    const lease = await issueDocumentLease();
    const documents = await loadEncounterDocuments(lease);
    const selected = documents.find((item) => item.document_id === clinicalContext.documentId)?.document_id
      ?? documents[0]?.document_id ?? null;
    if (!selected) return { lease, documents, selected, versions: [], corrections: [], governance: [] };
    return loadSelected(lease, documents, selected);
  },
  retry: false, staleTime: 0, gcTime: 0,
});

const issue = computed(() => versionsQuery.error.value ? toClinicalIssue(versionsQuery.error.value) : null);
const current = computed(() => versionsQuery.data.value?.versions[0]);
const signatures = computed(() => {
  const seen = new Set<string>();
  return (versionsQuery.data.value?.governance ?? []).flatMap((snapshot) => snapshot.signatures)
    .filter((signature) => !seen.has(signature.signature_id) && seen.add(signature.signature_id));
});
const canCreateCorrection = computed(() => current.value && ['SIGNED', 'VOID'].includes(current.value.status));

async function selectDocument(documentId: string) {
  const data = versionsQuery.data.value;
  if (!data || data.selected === documentId || busy.value) return;
  await runAction(async () => {
    queryClient.setQueryData(versionKey, await loadSelected(data.lease, data.documents, documentId));
    correctionOpen.value = false;
    revokeTarget.value = null;
  });
}

async function reloadSelected() {
  const data = versionsQuery.data.value;
  if (!data?.selected) return;
  const documents = await loadEncounterDocuments(data.lease);
  queryClient.setQueryData(versionKey, await loadSelected(data.lease, documents, data.selected));
}

async function runAction(action: () => Promise<void>) {
  busy.value = true;
  actionError.value = '';
  actionNotice.value = '';
  try { await action(); }
  catch (error) { actionError.value = toClinicalIssue(error).message; }
  finally { busy.value = false; }
}

function beginCorrection() {
  if (!current.value) return;
  const sections = current.value.sections ?? {};
  const text = (key: string) => typeof sections[key] === 'string' ? sections[key] as string : '';
  correctionSections.value = {
    chief_complaint: text('chief_complaint'), present_illness: text('present_illness'),
    assessment: text('assessment'), treatment_plan: text('treatment_plan'),
  };
  correctionReason.value = '';
  correctionType.value = 'CORRECTION';
  correctionOpen.value = true;
}

async function submitCorrection() {
  const data = versionsQuery.data.value;
  const document = current.value;
  if (!data || !document || correctionReason.value.trim().length < 4) return;
  await runAction(async () => {
    await createDocumentCorrection(data.lease, document, correctionType.value, correctionReason.value, {
      ...(document.sections ?? {}), ...correctionSections.value,
    });
    correctionOpen.value = false;
    await reloadSelected();
    actionNotice.value = '更正草稿已创建，原签名版本保持不可变；请到专注编辑完成质控与重新签署。';
  });
}

async function retryPropagation(propagation: DocumentCorrectionPropagationWire) {
  const data = versionsQuery.data.value;
  if (!data?.selected) return;
  await runAction(async () => {
    const result = await retryDocumentCorrectionPropagation(data.lease, data.selected!, propagation);
    await reloadSelected();
    actionNotice.value = result.status === 'FAILED'
      ? `传播未完成：${result.last_error_code || '外部适配器不可用'}。失败证据已保留，可在接口配置完成后重试。`
      : '更正传播任务已重新执行。';
  });
}

async function confirmRevocation() {
  const data = versionsQuery.data.value;
  if (!data || !revokeTarget.value || revokeReason.value.trim().length < 4) return;
  await runAction(async () => {
    // Re-read the current document so the optimistic row_version is fresh: the
    // correction propagation can advance the document version asynchronously
    // after the correction is signed, which otherwise yields VERSION_CONFLICT.
    await reloadSelected();
    const fresh = versionsQuery.data.value;
    const document = current.value;
    if (!fresh || !document) throw new Error('病历状态不可用，请刷新后重试');
    await revokeDocumentSignature(fresh.lease, document, revokeTarget.value!.signature_id, revokeReason.value);
    await reloadSelected();
    revokeTarget.value = null;
    revokeReason.value = '';
    actionNotice.value = '签名已撤销并生成不可变撤销证据；已签正文和原签名记录均未被删除。';
  });
}

async function verifyAllSignatures() {
  const data = versionsQuery.data.value;
  if (!data?.selected || busy.value) return;
  await runAction(async () => {
    // Sequential on purpose: concurrent verify transactions contend on the tenant
    // audit-hash-chain lock (`select tenant for update`) and deadlock.
    const runs = [];
    for (const version of data.versions) {
      runs.push(await verifyDocumentSignatures(data.lease, data.selected!, version.document_version_id));
    }
    verificationRuns.value = runs;
    const invalid = verificationRuns.value.filter((run) => run.outcome !== 'VALID').length;
    actionNotice.value = invalid === 0
      ? `验签完成：${verificationRuns.value.length} 个版本均已通过，验证证据已写入。`
      : `验签完成：${invalid} 个版本未通过，系统未伪造成功状态。`;
  });
}

function governanceFor(versionId: string): DocumentGovernanceSnapshotWire | undefined {
  return versionsQuery.data.value?.governance.find((item) => item.document_version_id === versionId);
}
function diffRoute(versions: DocumentVersionWire[]) {
  const [to, from] = versions;
  if (!from || !to) return '/record-versions';
  return `/record-diff/${to.document_id}/${from.document_version_id}/${to.document_version_id}`;
}
function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'short', hour12: false }).format(new Date(value));
}
function statusLabel(status: string) {
  return ({ DRAFT: '草稿', SIGNED: '已签署', VOID: '已作废', PENDING: '待传播', SUCCEEDED: '已送达', FAILED: '传播失败', VALID: '有效', PENDING_CA_EVIDENCE: '待 CA 证据', REVOKED: '已撤销' } as Record<string, string>)[status] ?? status;
}
</script>

<template>
  <section data-page-root class="content vue-native-page">
    <div class="page-heading"><div><h1>病历版本与法律证据</h1><p>草稿、签署、更正、归档和传播状态构成不可覆盖的版本链</p></div><div class="toolbar-actions"><button class="btn" type="button" :disabled="busy || versionsQuery.isFetching.value || !versionsQuery.data.value?.selected || !versionsQuery.data.value.versions.length" @click="verifyAllSignatures">{{ busy ? '验证中…' : '批量验签' }}</button><RouterLink v-if="versionsQuery.data.value && versionsQuery.data.value.versions.length >= 2" class="btn primary" :to="diffRoute(versionsQuery.data.value.versions)">比较最近两个版本</RouterLink></div></div>
    <RecordPatientStrip />
    <ClinicalPageState v-if="versionsQuery.isPending.value" kind="loading" message="正在读取病历文书、治理证据与更正台账" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="versionsQuery.refetch()" />
    <div v-else-if="versionsQuery.data.value?.documents.length === 0" class="record-evidence-empty"><h2>本次就诊尚无病历文书</h2><p>创建文书后，版本与法律证据将在此自动汇聚。</p><RouterLink class="button primary" to="/record">进入病历工作台</RouterLink></div>
    <template v-else-if="versionsQuery.data.value">
      <div v-if="actionError || actionNotice" class="legal-action-message" :class="{ error: actionError }" role="status">{{ actionError || actionNotice }}</div>
      <div class="grid version-layout record-real-versions">
        <section class="card"><div class="card-head">版本时间轴 <select :value="versionsQuery.data.value.selected || ''" aria-label="本次就诊文书" @change="selectDocument(($event.target as HTMLSelectElement).value)"><option v-for="item in versionsQuery.data.value.documents" :key="item.document_id" :value="item.document_id">{{ item.document_type_code }} · v{{ item.version_no }}</option></select></div><div class="card-body"><div v-for="(version, index) in versionsQuery.data.value.versions" :key="version.document_version_id" class="version-row" :class="{ active: index === 0 }"><div class="version-node" /><b>v{{ version.version_no }}</b><div><strong>{{ statusLabel(version.status) }}</strong><span>内容指纹 {{ version.content_hash.slice(0, 12) }}…</span></div><span>{{ governanceFor(version.document_version_id)?.signatures.length || 0 }} 份签名</span><time>{{ formatDate(version.created_at) }}</time><RouterLink class="btn sm" :to="`/record/documents/${version.document_id}/versions/${version.document_version_id}`">证据链</RouterLink><RouterLink v-if="index < versionsQuery.data.value.versions.length - 1" class="btn sm" :to="diffRoute([version, versionsQuery.data.value.versions[index + 1]])">比较</RouterLink><RouterLink v-else class="btn sm" to="/record-editor">查看</RouterLink></div></div></section>
        <aside class="card"><div class="card-head">当前版本 · 签名与归档证据</div><div class="card-body"><div class="folder-row">内容哈希<span>{{ current ? `${current.content_hash.slice(0, 18)}…` : '—' }}</span></div><div class="folder-row">文书 ID<span>{{ current ? `…${current.document_id.slice(-8)}` : '—' }}</span></div><div class="folder-row">当前版本<span>{{ current ? `v${current.version_no} / ${statusLabel(current.status)}` : '—' }}</span></div><div class="folder-row">更正记录<span>{{ versionsQuery.data.value.corrections.length }} 条</span></div><div class="folder-row">签名证据<span>{{ signatures.length }} 份</span></div><div class="folder-row">外部传播<span>{{ versionsQuery.data.value.corrections.flatMap(item => item.propagations).filter(item => item.status === 'SUCCEEDED').length }} 已确认</span></div><RouterLink class="btn record-asset-link" to="/archive-assets">跨域：查看病案资产证据</RouterLink></div></aside>
      </div>

      <section class="legal-governance-workspace" aria-label="依法更正与签名治理">
        <article class="legal-correction-card">
          <header><div><p class="eyebrow">受控操作</p><h2>依法更正 / 补记</h2></div><span class="legal-state-chip">原文不覆盖</span></header>
          <div v-if="!canCreateCorrection" class="legal-empty-state"><strong>当前版本尚未签署</strong><p>草稿请在编辑器直接修订；只有已签或已撤签文书才能发起法定更正。</p><RouterLink class="button secondary" to="/record-editor">进入专注编辑</RouterLink></div>
          <div v-else class="legal-operation-summary"><p>系统将基于当前已签版本创建新的更正草稿，并强制记录更正类型、理由、来源版本与重新签署证据。</p><button type="button" class="button primary" :disabled="busy" @click="beginCorrection">发起依法更正</button></div>
        </article>

        <article class="legal-ledger-card">
          <header><div><p class="eyebrow">不可变台账</p><h2>更正与外部传播</h2></div><span>{{ versionsQuery.data.value.corrections.length }} 条</span></header>
          <div v-if="versionsQuery.data.value.corrections.length === 0" class="legal-empty-state compact"><strong>暂无更正记录</strong><p>签后更正将在这里串联来源版本、修订版本和外部传播结果。</p></div>
          <div v-for="correction in versionsQuery.data.value.corrections" v-else :key="correction.correction_id" class="correction-ledger-row">
            <div class="ledger-title"><strong>{{ correction.correction_type === 'CORRECTION' ? '更正' : '补记' }} · {{ correction.status === 'DRAFT' ? '更正草稿' : statusLabel(correction.status) }}</strong><time>{{ formatDate(correction.requested_at) }}</time></div>
            <p>{{ correction.reason }}</p><code>来源 …{{ correction.source_document_version_id.slice(-8) }} → 更正 …{{ correction.correction_document_version_id.slice(-8) }}</code>
            <div v-for="propagation in correction.propagations" :key="propagation.propagation_id" class="propagation-row"><div><span :class="['propagation-status', propagation.status.toLowerCase()]">{{ statusLabel(propagation.status) }}</span><b>{{ propagation.destination_code }}</b><small>尝试 {{ propagation.attempt_count }} 次<span v-if="propagation.last_error_code"> · {{ propagation.last_error_code }}</span></small></div><button v-if="propagation.status !== 'SUCCEEDED'" type="button" class="text-button" :disabled="busy" @click="retryPropagation(propagation)">重试传播</button></div>
          </div>
        </article>
      </section>

      <section class="signature-evidence-card" aria-label="签名与撤销证据">
        <header><div><p class="eyebrow">签名治理</p><h2>签名与撤销证据</h2></div><span>{{ signatures.length }} 份签名</span></header>
        <div v-if="signatures.length === 0" class="legal-empty-state compact"><strong>暂无签名证据</strong><p>完成质控和签署后，这里将展示签署人、角色、内容哈希与证据状态。</p></div>
        <div v-else class="signature-evidence-grid"><article v-for="signature in signatures" :key="signature.signature_id"><div><strong>{{ signature.signer_display_name }}</strong><span>{{ signature.signature_role }} · {{ formatDate(signature.signed_at) }}</span></div><span :class="['signature-status', signature.signature_status.toLowerCase()]">{{ statusLabel(signature.signature_status) }}</span><code>签名 …{{ signature.signature_id.slice(-8) }} · 哈希 {{ signature.content_hash.slice(0, 12) }}…</code><button v-if="signature.signature_status !== 'REVOKED'" type="button" class="text-button danger" :disabled="busy" @click="revokeTarget = signature; revokeReason = ''">撤销签名</button></article></div>
      </section>
      <section v-if="verificationRuns.length" class="signature-evidence-card" aria-label="验签运行证据"><header><div><p class="eyebrow">不可变验证记录</p><h2>本次批量验签</h2></div><span>{{ verificationRuns.length }} 个版本</span></header><div class="signature-evidence-grid"><article v-for="run in verificationRuns" :key="run.verification_run_id"><div><strong>v{{ versionsQuery.data.value.versions.find(item => item.document_version_id === run.document_version_id)?.version_no }}</strong><span>{{ run.provider_code }} · {{ formatDate(run.verified_at) }}</span></div><span :class="['signature-status', run.outcome === 'VALID' ? 'valid' : 'revoked']">{{ run.outcome }}</span><code>通过 {{ run.verified_count }} · 异常 {{ run.invalid_count }} · 运行 …{{ run.verification_run_id.slice(-8) }}</code></article></div></section>
      <BusinessActionDialog :open="correctionOpen" title="新建依法更正 / 补记" description="将基于当前已签版本创建新草稿，原文、签名和内容哈希保持不变。" eyebrow="病历 / 法律证据" confirm-label="创建更正草稿" :busy="busy" width="wide" @cancel="correctionOpen = false" @confirm="submitCorrection"><div class="dialog-grid"><label><span>处理类型</span><select v-model="correctionType"><option value="CORRECTION">更正</option><option value="ADDENDUM">补记</option></select></label><label><span>更正/补记原因（至少 4 字）</span><textarea v-model="correctionReason" rows="3" maxlength="2000" placeholder="说明发现问题的依据及更正原因"></textarea></label><label><span>主诉</span><textarea v-model="correctionSections.chief_complaint" rows="3"></textarea></label><label><span>现病史</span><textarea v-model="correctionSections.present_illness" rows="3"></textarea></label><label><span>评估</span><textarea v-model="correctionSections.assessment" rows="3"></textarea></label><label><span>诊疗计划</span><textarea v-model="correctionSections.treatment_plan" rows="3"></textarea></label></div><p v-if="correctionReason.trim().length < 4" class="dialog-warning">请填写至少 4 个字的更正依据。</p></BusinessActionDialog>
      <BusinessActionDialog :open="Boolean(revokeTarget)" :title="`撤销 ${revokeTarget?.signer_display_name ?? ''} 的签名`" description="撤销后生成独立证据，原签名与正文仍保留；当前版本头状态将按规则转为作废。" eyebrow="病历 / 签名治理" confirm-label="确认撤销并留痕" danger :busy="busy" @cancel="revokeTarget = null" @confirm="confirmRevocation"><p class="dialog-warning">这是可审计的业务作废，不是物理删除。</p><label>撤销原因（至少 4 字）<textarea v-model="revokeReason" rows="3" maxlength="2000" placeholder="填写撤销依据"></textarea></label></BusinessActionDialog>
    </template>
  </section>
</template>

<style scoped>
.record-real-versions .version-row{grid-template-columns:14px 32px minmax(120px,1fr) 60px 100px 58px 50px}.record-real-versions .version-row .btn{min-width:0;white-space:nowrap}
</style>
