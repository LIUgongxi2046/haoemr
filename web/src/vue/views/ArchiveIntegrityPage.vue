<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, ref, watch } from 'vue';
import type { MedicalRecordAssetWire } from '../../generated/contracts';
import { issueMedicalRecordAssetLease, listMedicalRecordAssets, verifyMedicalRecordAssetIntegrity, verifyMedicalRecordAssetStorage } from '../../api/records';
import { ClinicalApiError, clinicalContext, issueArchiveLease, loadArchiveReadiness } from '../../clinical-api';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import BusinessActionDialog from '../components/BusinessActionDialog.vue';
import { toClinicalIssue } from '../clinical-error';
import { captureResponseScope, clinicalResponseScope } from '../clinical-response-scope';

const notice = ref('');
const verifying = ref(false);
const verifyTarget = ref<MedicalRecordAssetWire | null>(null);
const observedHash = ref('');
const scope = computed(clinicalResponseScope);
const scopeEpoch = ref(0);
function capturePageScope() {
  const isCurrent = captureResponseScope();
  const epoch = scopeEpoch.value;
  return () => isCurrent() && scopeEpoch.value === epoch;
}
watch(scope, () => {
  scopeEpoch.value += 1;
  verifying.value = false;
  verifyTarget.value = null;
  observedHash.value = '';
  notice.value = '';
}, { flush: 'sync' });
const integrityQuery = useQuery({
  queryKey: computed(() => ['clinical', 'archive-integrity', scope.value, scopeEpoch.value]),
  queryFn: async () => {
    const isCurrent = capturePageScope();
    const patientId = clinicalContext.patientId;
    const encounterId = clinicalContext.encounterId;
    const [assetLease, archiveLease] = await Promise.all([issueMedicalRecordAssetLease(), issueArchiveLease()]);
    if (!isCurrent()) throw new ClinicalApiError('CONTEXT_CHANGED', '就诊范围已切换，请重新读取病案证据', 409);
    const [assets, readiness] = await Promise.all([listMedicalRecordAssets(assetLease), loadArchiveReadiness(archiveLease)]);
    if (!isCurrent()) throw new ClinicalApiError('CONTEXT_CHANGED', '就诊范围已切换，请重新读取病案证据', 409);
    if (readiness.patient_id !== patientId || readiness.encounter_id !== encounterId) {
      throw new ClinicalApiError('ARCHIVE_CONTEXT_MISMATCH', '归档证据不属于当前患者与就诊', 409);
    }
    return { assetLease, assets, readiness };
  },
  enabled: () => Boolean(clinicalContext.patientId && clinicalContext.encounterId),
  retry: false, staleTime: 0, gcTime: 0,
});
const issue = computed(() => integrityQuery.error.value ? toClinicalIssue(integrityQuery.error.value) : null);
const assets = computed(() => integrityQuery.data.value?.assets ?? []);
const readiness = computed(() => integrityQuery.data.value?.readiness);
const archiveCase = computed(() => readiness.value?.archive_case ?? null);

const verifiedCount = computed(() => assets.value.filter((asset) => asset.integrity_status === 'VERIFIED').length);
const signatureCount = computed(() => archiveCase.value?.items.filter((item) => item.signature_summary_hash.length === 64).length ?? 0);
const blockerCount = computed(() => readiness.value?.blockers.length ?? 0);
const sealed = computed(() => archiveCase.value?.status === 'SEALED');
const problemAsset = computed(() => assets.value.find((asset) => asset.integrity_status !== 'VERIFIED') ?? assets.value[0] ?? null);

async function verify() {
  if (verifying.value) return;
  const isCurrent = capturePageScope();
  verifying.value = true; notice.value = '';
  try {
    const data = integrityQuery.data.value; if (!data) return;
    const stored = data.assets.filter((asset) => asset.status !== 'RETIRED' && asset.storage_status !== 'MISSING');
    let passed = 0;
    for (const asset of stored) {
      if (!isCurrent()) return;
      const event = await verifyMedicalRecordAssetStorage(data.assetLease, asset);
      if (!isCurrent()) return;
      if (event.result === 'VERIFIED') passed += 1;
    }
    await integrityQuery.refetch();
    if (!isCurrent()) return;
    notice.value = `已从存储重新读取 ${stored.length} 份原件并服务端复算 SHA-256，${passed} 份通过。`;
  } catch (error) {
    if (!isCurrent()) return;
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { if (isCurrent()) verifying.value = false; }
}

async function runAssetVerification() {
  const data = integrityQuery.data.value;
  const target = verifyTarget.value;
  if (!data || !target || verifying.value || (target.storage_status === 'MISSING' && !/^[0-9a-fA-F]{64}$/.test(observedHash.value))) return;
  const isCurrent = capturePageScope();
  verifying.value = true; notice.value = '';
  try {
    const event = target.storage_status === 'MISSING'
      ? await verifyMedicalRecordAssetIntegrity(data.assetLease, target, observedHash.value)
      : await verifyMedicalRecordAssetStorage(data.assetLease, target);
    if (!isCurrent()) return;
    notice.value = event.result === 'VERIFIED'
      ? '观测哈希与编目锚点一致，该资产已解除借阅与归档阻断。'
      : '观测哈希不一致，已记录失败证据并持续阻断借阅与归档。';
    verifyTarget.value = null; observedHash.value = ''; await integrityQuery.refetch();
  } catch (error) { if (isCurrent()) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; } }
  finally { if (isCurrent()) verifying.value = false; }
}

function blockerLabel(value: string) { return ({ ENCOUNTER_NOT_FINISHED: '就诊尚未结束', ARCHIVE_DOCUMENT_REQUIRED: '缺少可归档病历', DOCUMENT_NOT_SIGNED: '当前版本未签署', DOCUMENT_QUALITY_NOT_PASSED: '当前内容质控未通过', SIGNATURE_EVIDENCE_REQUIRED: '缺少签名证据', SIGNATURE_EVIDENCE_NOT_VALID: '签名证据无效或待补齐', ASSET_INTEGRITY_REQUIRED: '关联资产未通过哈希验真' } as Record<string, string>)[value] || value; }
function archiveStatusLabel(value: string) { return ({ ARCHIVED: '已归档待封存', SEALED: '已封存', UNSEALED: '授权解封中' } as Record<string, string>)[value] || value; }
</script>

<template>
  <section data-page-root class="content vue-native-page archive-prototype-page">
    <div class="page-head"><div class="page-title"><h1>病案完整性、签名与长期验真</h1><p>归档前检查和归档后周期验真使用同一证据链</p></div><div class="head-actions"><button class="btn" :disabled="verifying" @click="verify">{{ verifying ? '正在检查…' : '运行完整性检查' }}</button><RouterLink class="btn primary" :to="problemAsset ? `/asset-detail?asset=${problemAsset.medical_record_asset_id}` : '/archive-catalog'">查看问题资产</RouterLink></div></div>
    <ClinicalPageState v-if="!clinicalContext.patientId || !clinicalContext.encounterId" kind="empty" message="请先选择患者和就诊，再读取病案完整性证据。" />
    <ClinicalPageState v-else-if="integrityQuery.isPending.value" kind="loading" message="正在读取内容哈希与签名摘要锚点" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="integrityQuery.refetch()" />
    <template v-else>
      <div v-if="notice" class="notice archive-notice" role="status">{{ notice }}</div>
      <div class="grid integrity-layout">
        <section class="card"><div class="card-head">当前病案 · 验真批次 {{ archiveCase ? `…${archiveCase.archive_case_id.slice(-8)}` : '待归档' }}</div><div class="card-body">
          <div class="check-row"><span class="check-icon green">✓</span><div class="prototype-check-copy"><b>患者/就诊关联</b><p>{{ readiness?.encounter_status }} · 服务端证据属于当前患者和就诊</p></div><span class="status green">已核对</span></div>
          <div class="check-row"><span class="check-icon" :class="readiness?.ready ? 'green' : 'red'">{{ readiness?.ready ? '✓' : '!' }}</span><div class="prototype-check-copy"><b>应有文书完整性</b><p>{{ readiness?.document_count ?? 0 }} 份文书；{{ blockerCount }} 项服务端归档阻断</p></div><span class="status" :class="readiness?.ready ? 'green' : 'red'">{{ readiness?.ready ? '服务端检查通过' : '暂不可归档' }}</span></div>
          <div class="check-row"><span class="check-icon" :class="assets.some(a => a.asset_type === 'SCAN' && a.scan_status !== 'INDEXED') ? 'amber' : 'green'">{{ assets.some(a => a.asset_type === 'SCAN' && a.scan_status !== 'INDEXED') ? '△' : '✓' }}</span><div class="prototype-check-copy"><b>页序与重复</b><p>{{ assets.filter(a => a.asset_type === 'SCAN' && a.scan_status !== 'INDEXED').length }} 个扫描批次待编目复核</p></div><span class="status" :class="assets.some(a => a.asset_type === 'SCAN' && a.scan_status !== 'INDEXED') ? 'amber' : 'green'">{{ assets.some(a => a.asset_type === 'SCAN' && a.scan_status !== 'INDEXED') ? '待复核' : '通过' }}</span></div>
          <div v-for="asset in assets" :key="asset.medical_record_asset_id" class="check-row"><span class="check-icon" :class="asset.integrity_status === 'VERIFIED' ? 'green' : asset.integrity_status === 'FAILED' ? 'red' : 'amber'">{{ asset.integrity_status === 'VERIFIED' ? '✓' : asset.integrity_status === 'FAILED' ? '!' : '△' }}</span><div class="prototype-check-copy"><b>哈希完整性 · {{ asset.display_name }}</b><p>{{ asset.storage_status }} · SHA-256 {{ asset.content_hash.slice(0, 16) }}…</p></div><span><span class="status" :class="asset.integrity_status === 'VERIFIED' ? 'green' : asset.integrity_status === 'FAILED' ? 'red' : 'amber'">{{ asset.integrity_status }}</span><button class="btn sm" :disabled="asset.status === 'RETIRED'" @click="verifyTarget = asset; observedHash = asset.storage_status === 'MISSING' ? asset.content_hash : ''">{{ asset.storage_status === 'MISSING' ? '载体验真' : '重读' }}</button></span></div>
          <div class="check-row"><span class="check-icon amber">△</span><div class="prototype-check-copy"><b>归档签名摘要</b><p>归档清单记录 {{ signatureCount }} 项摘要；可信验签以服务端证据为准</p></div><span class="status amber">查看摘要</span></div>
          <div class="check-row"><span class="check-icon" :class="assets.some(a => a.cda_status === 'FAILED') ? 'red' : 'amber'">{{ assets.some(a => a.cda_status === 'FAILED') ? '!' : '△' }}</span><div class="prototype-check-copy"><b>CDA 模式/术语</b><p>{{ assets.filter(a => a.cda_status === 'VERIFIED').length }} 份共享文档校验通过</p></div><span class="status" :class="assets.some(a => a.cda_status === 'FAILED') ? 'red' : 'amber'">{{ assets.some(a => a.cda_status === 'FAILED') ? '存在失败' : '以逐份证据为准' }}</span></div>
          <div class="check-row"><span class="check-icon" :class="assets.some(a => a.preservation_status === 'NOT_SCHEDULED') ? 'amber' : 'green'">△</span><div class="prototype-check-copy"><b>长期格式</b><p>{{ assets.filter(a => a.preservation_status === 'NOT_SCHEDULED').length }} 份资产尚未纳入长期保存</p></div><span class="status amber">观察</span></div>
        </div></section>
        <aside class="card"><div class="card-head">证据摘要</div><div class="card-body"><div class="notice info"><div class="notice-title">{{ readiness?.ready ? '服务端归档条件已通过' : '服务端归档条件未通过' }}</div>已验真资产 {{ verifiedCount }} / {{ assets.length }} 份；归档操作仍需服务端再次核对。</div><div class="folder-row">检查规则<span>服务端归档硬门</span></div><div class="folder-row">资产清单哈希<span>{{ archiveCase ? `${archiveCase.manifest_hash.slice(0, 12)}…` : '待生成' }}</span></div><div class="folder-row">签名摘要<span>{{ signatureCount ? `${signatureCount} 项摘要` : '尚无摘要' }}</span></div><div class="folder-row">CDA 校验证据<span>{{ assets.some(a => a.cda_status === 'VERIFIED') ? `${assets.filter(a => a.cda_status === 'VERIFIED').length} 份服务端校验通过` : '尚无通过证据' }}</span></div><div class="folder-row">病案状态<span>{{ archiveCase ? archiveStatusLabel(archiveCase.status) : '未归档' }}</span></div><div class="folder-row">封存状态<span>{{ sealed ? '已封存' : '未封存' }}</span></div><div v-if="blockerCount" class="notice hard" style="margin-top:12px"><div class="notice-title">归档硬门</div><span v-for="blocker in readiness?.blockers || []" :key="blocker.code">{{ blockerLabel(blocker.code) }}；</span></div></div></aside>
      </div>
    </template>
    <BusinessActionDialog :open="Boolean(verifyTarget)" :title="`验真：${verifyTarget?.display_name ?? ''}`" :description="verifyTarget?.storage_status === 'MISSING' ? '纸质或外部载体需录入重新观测的 SHA-256。' : '服务端将从存储重新读取实际字节并复算 SHA-256，不信任浏览器输入。'" eyebrow="病案资产 / 完整性" confirm-label="执行存储验真" :busy="verifying" :confirm-disabled="verifyTarget?.storage_status === 'MISSING' && !/^[0-9a-fA-F]{64}$/.test(observedHash)" @cancel="verifyTarget = null" @confirm="runAssetVerification"><label v-if="verifyTarget?.storage_status === 'MISSING'">观测哈希<input v-model="observedHash" maxlength="64" /></label><p class="dialog-warning">验真失败会立即阻断该资产借阅、归档和长期封包。</p></BusinessActionDialog>
  </section>
</template>
