<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, ref } from 'vue';
import type { ArchiveCaseWire, MedicalRecordAssetWire } from '../../generated/contracts';
import { issueMedicalRecordAssetLease, listMedicalRecordAssets } from '../../api/records';
import { createArchiveCase, createArchiveExport, issueArchiveLease, loadArchiveReadiness, transitionArchiveCase } from '../../clinical-api';
import BusinessActionDialog from '../components/BusinessActionDialog.vue';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';

const busy = ref('');
const notice = ref('');
const reason = ref('病案管理复核完成');
const purpose = ref('病案复印与院内复核');
const actionDialog = ref<'create' | 'seal' | 'unseal' | 'export' | null>(null);
const selectedAssetId = ref('');
const searchText = ref('');
const mediaFilter = ref('ALL');
const integrityFilter = ref('ALL');

const archiveQuery = useQuery({
  queryKey: ['clinical', 'archive-readiness'],
  queryFn: async () => { const lease = await issueArchiveLease(); return { lease, readiness: await loadArchiveReadiness(lease) }; },
  retry: false, staleTime: 0, gcTime: 0,
});
const assetsQuery = useQuery({
  queryKey: ['clinical', 'medical-record-assets', 'overview'],
  queryFn: async () => { const lease = await issueMedicalRecordAssetLease(); return { lease, assets: await listMedicalRecordAssets(lease) }; },
  retry: false, staleTime: 0, gcTime: 0,
});
const issue = computed(() => archiveQuery.error.value ? toClinicalIssue(archiveQuery.error.value) : assetsQuery.error.value ? toClinicalIssue(assetsQuery.error.value) : null);
const readiness = computed(() => archiveQuery.data.value?.readiness);
const archiveCase = computed(() => readiness.value?.archive_case ?? null);
const assets = computed(() => (assetsQuery.data.value?.assets ?? []).filter((asset) => asset.status !== 'RETIRED'));
const filteredAssets = computed(() => assets.value.filter((asset) => {
  const needle = searchText.value.trim().toLowerCase();
  const matchesText = !needle || `${asset.display_name} ${asset.source_system} ${asset.medical_record_asset_id} ${asset.media_type}`.toLowerCase().includes(needle);
  const matchesMedia = mediaFilter.value === 'ALL' || asset.asset_type === mediaFilter.value;
  const matchesIntegrity = integrityFilter.value === 'ALL' || asset.integrity_status === integrityFilter.value;
  return matchesText && matchesMedia && matchesIntegrity;
}));
const selectedAsset = computed<MedicalRecordAssetWire | null>(() => filteredAssets.value.find((asset) => asset.medical_record_asset_id === selectedAssetId.value) ?? filteredAssets.value[0] ?? null);
const unavailableCount = computed(() => assets.value.filter((asset) => asset.storage_status === 'MISSING' || asset.integrity_status === 'FAILED').length);
const cdaAssets = computed(() => assets.value.filter((asset) => asset.cda_status !== 'NOT_APPLICABLE'));
const cdaRate = computed(() => cdaAssets.value.length ? Math.round(cdaAssets.value.filter((asset) => asset.cda_status === 'VERIFIED').length / cdaAssets.value.length * 1000) / 10 : 100);
const fidelityRate = computed(() => assets.value.length ? Math.round(assets.value.filter((asset) => asset.storage_status !== 'MISSING').length / assets.value.length * 1000) / 10 : 100);

async function execute(label: string, action: (archive: ArchiveCaseWire | null) => Promise<void>) {
  if (busy.value || !archiveQuery.data.value) return;
  busy.value = label; notice.value = '';
  try { await action(archiveCase.value); await archiveQuery.refetch(); }
  catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}
function openArchiveAction() {
  if (!archiveCase.value) actionDialog.value = 'create';
  else if (archiveCase.value.status !== 'SEALED') actionDialog.value = 'seal';
  else actionDialog.value = 'export';
}
function createCase() { const data = archiveQuery.data.value; if (!data) return; void execute('archive', async () => { await createArchiveCase(data.lease); actionDialog.value = null; notice.value = '不可变病案清单已生成'; }); }
function transition(action: 'seals' | 'unseals') { const data = archiveQuery.data.value; if (!data) return; void execute(action, async (current) => { if (current) await transitionArchiveCase(data.lease, current, action, reason.value); actionDialog.value = null; notice.value = action === 'seals' ? '病案已由独立岗位封存' : '病案已授权解封'; }); }
function createExport() { const data = archiveQuery.data.value; if (!data) return; void execute('export', async (current) => { if (current) await createArchiveExport(data.lease, current, purpose.value); actionDialog.value = null; notice.value = '独立可读导出包已固化'; }); }
function archiveStatusLabel(value: string) { return ({ ARCHIVED: '已归档待封存', SEALED: '已封存', UNSEALED: '授权解封中' } as Record<string, string>)[value] || value; }
function typeLabel(value: string) { return ({ PAPER: '纸质原件', SCAN: '扫描件', DIGITAL: '电子文书' } as Record<string, string>)[value] || value; }
function integrityLabel(value: string) { return ({ PENDING: '待验真', VERIFIED: '验真通过', FAILED: '隔离' } as Record<string, string>)[value] || value; }
function integrityClass(value: string) { return value === 'VERIFIED' ? 'green' : value === 'FAILED' ? 'red' : 'amber'; }
function cdaLabel(value: string) { return ({ NOT_APPLICABLE: '不适用', PENDING: '待校验', VERIFIED: '模式+语义通过', FAILED: '校验失败' } as Record<string, string>)[value] || value; }
function cdaClass(value: string) { return value === 'VERIFIED' || value === 'NOT_APPLICABLE' ? 'green' : value === 'FAILED' ? 'red' : 'amber'; }
</script>

<template>
  <section data-page-root class="content vue-native-page archive-prototype-page">
    <div class="page-head">
      <div class="page-title"><h1>病案资产与共享文档中心</h1><p>患者级电子文书、附件、扫描签字件、原格式和 CDA 的统一资产清单</p></div>
      <div class="head-actions"><RouterLink class="btn" to="/archive-integrity">批量验真</RouterLink><RouterLink class="btn" to="/archive-integrity?scope=cda">CDA 校验报告</RouterLink><button class="btn primary" :disabled="Boolean(busy) || (!archiveCase && !readiness?.ready)" @click="openArchiveAction">创建归档包</button></div>
    </div>
    <ClinicalPageState v-if="archiveQuery.isPending.value || assetsQuery.isPending.value" kind="loading" message="正在读取病案资产与归档证据" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="archiveQuery.refetch(); assetsQuery.refetch()" />
    <template v-else-if="readiness">
      <div class="metric-grid" style="margin-bottom:14px"><div class="metric"><div class="name">待归档病案</div><div class="value">{{ archiveCase ? 0 : readiness.document_count }}</div><div class="trend">当前病历 {{ readiness.document_count }} 份</div></div><div class="metric"><div class="name">缺失/隔离资产</div><div class="value" style="color:var(--red)">{{ unavailableCount }}</div><div class="trend">阻断归档 {{ readiness.blockers.length }}</div></div><div class="metric"><div class="name">CDA 校验通过率</div><div class="value">{{ cdaRate }}%</div><div class="trend">模式+术语+关联</div></div><div class="metric"><div class="name">历史原件保真</div><div class="value">{{ fidelityRate }}%</div><div class="trend">原格式与存储对象可重读</div></div></div>
      <div v-if="notice" class="notice archive-notice" role="status">{{ notice }}</div>
      <div class="grid archive-overview-layout">
        <section class="card scroll-card">
          <div class="toolbar"><input v-model="searchText" class="search" placeholder="病案号 / 资产 ID / 文书类型"><select v-model="mediaFilter" class="select" aria-label="归档载体"><option value="ALL">全部载体</option><option value="PAPER">纸质</option><option value="SCAN">扫描件</option><option value="DIGITAL">电子文书</option></select><select v-model="integrityFilter" class="select" aria-label="完整性校验状态"><option value="ALL">全部校验状态</option><option value="PENDING">待验真</option><option value="VERIFIED">已通过</option><option value="FAILED">隔离</option></select></div>
          <div class="archive-table-wrap"><table class="table"><thead><tr><th>资产 ID</th><th>临床对象</th><th>载体/格式</th><th>版本/页数</th><th>完整性/签名</th><th>CDA</th></tr></thead><tbody><tr v-for="asset in filteredAssets" :key="asset.medical_record_asset_id" :class="{ 'prototype-row-active': selectedAsset?.medical_record_asset_id === asset.medical_record_asset_id }" @click="selectedAssetId = asset.medical_record_asset_id"><td><b>…{{ asset.medical_record_asset_id.slice(-8) }}</b></td><td>{{ asset.display_name }}</td><td>{{ typeLabel(asset.asset_type) }}/{{ asset.media_type }}</td><td>{{ asset.page_count }} 页</td><td><span class="status" :class="integrityClass(asset.integrity_status)">{{ integrityLabel(asset.integrity_status) }}</span></td><td><span class="status" :class="cdaClass(asset.cda_status)">{{ cdaLabel(asset.cda_status) }}</span></td></tr><tr v-if="!filteredAssets.length"><td colspan="6" class="prototype-empty">没有符合当前筛选条件的病案资产</td></tr></tbody></table></div>
          <div class="card-body"><div class="notice info"><div class="notice-title">资产不可变事实</div>来源系统、源标识、原格式、转换版本、页数、校验值、签名/验签、保管期限、归档包和访问审计始终可追溯。</div></div>
        </section>
        <aside class="card scroll-card"><div class="card-head">{{ selectedAsset ? `…${selectedAsset.medical_record_asset_id.slice(-8)} · ${selectedAsset.display_name}` : '资产详情' }} <span v-if="selectedAsset" class="status" :class="integrityClass(selectedAsset.integrity_status)">{{ integrityLabel(selectedAsset.integrity_status) }}</span></div><div v-if="selectedAsset" class="card-body"><div class="form-row" style="grid-template-columns:95px 1fr"><div class="label">源对象</div><div class="field">{{ selectedAsset.source_system }}/{{ selectedAsset.original_filename || selectedAsset.location }}</div></div><div class="form-row" style="grid-template-columns:95px 1fr"><div class="label">原格式</div><div class="field">{{ selectedAsset.media_type }} · {{ selectedAsset.byte_size ?? '—' }} B</div></div><div class="form-row" style="grid-template-columns:95px 1fr"><div class="label">内容哈希</div><div class="field archive-detail-code">{{ selectedAsset.content_hash }}</div></div><div v-if="selectedAsset.integrity_status !== 'VERIFIED' || selectedAsset.storage_status === 'MISSING'" class="notice hard"><div class="notice-title">当前资产阻断归档</div>{{ selectedAsset.storage_status === 'MISSING' ? '原始存储对象不可重读。' : '内容哈希尚未通过服务端重读验真。' }} 禁止以派生件替代原件归档。</div><div v-else class="notice info"><div class="notice-title">原件可重读且哈希一致</div>最近验真 {{ selectedAsset.last_verified_at ? new Date(selectedAsset.last_verified_at).toLocaleString('zh-CN') : '已记录' }}。</div><div class="section-title" style="margin-top:16px">外部调阅与复制</div><div class="queue-item"><div class="queue-title">复制申请<span class="status" :class="selectedAsset.status === 'BORROWED' ? 'amber' : 'green'">{{ selectedAsset.status === 'BORROWED' ? '借出中' : '可申请' }}</span></div></div><div class="queue-item"><div class="queue-title">授权与复制范围<span class="status amber">申请时核验</span></div></div><div class="queue-item"><div class="queue-title">水印与访问码<span class="status gray">交付服务返回后更新</span></div></div><div class="queue-item"><div class="queue-title">依法封存<span class="status" :class="selectedAsset.object_lock_status === 'LOCKED' ? 'green' : 'gray'">{{ selectedAsset.object_lock_status === 'LOCKED' ? `${selectedAsset.storage_provider} 已记录锁证据` : '未封存' }}</span></div></div><RouterLink class="btn" style="width:100%;margin-top:10px;text-align:center" :to="`/asset-detail?asset=${selectedAsset.medical_record_asset_id}`">打开资产证据详情</RouterLink></div><div v-else class="prototype-empty">尚无可展示资产</div></aside>
      </div>
      <div class="grid archive-workflow-grid"><section class="card"><div class="card-head">真实归档流程 <span class="status" :class="archiveCase?.status === 'SEALED' ? 'green' : 'amber'">{{ archiveCase ? archiveStatusLabel(archiveCase.status) : readiness.ready ? '可归档' : '存在阻断' }}</span></div><div class="card-body"><div class="prototype-action-row"><button v-if="!archiveCase" class="btn primary" :disabled="!readiness.ready || Boolean(busy)" @click="actionDialog = 'create'">生成不可变清单</button><button v-else-if="archiveCase.status !== 'SEALED'" class="btn primary" :disabled="Boolean(busy)" @click="actionDialog = 'seal'">由独立岗位封存</button><button v-else class="btn" :disabled="Boolean(busy)" @click="actionDialog = 'unseal'">授权解封</button><button class="btn" :disabled="!archiveCase || archiveCase.status !== 'SEALED' || Boolean(busy)" @click="actionDialog = 'export'">生成独立可读导出包</button></div><p class="prototype-data-note">归档、封存、解封与导出均调用服务端状态机；未通过验真的资产会真实阻断归档。</p></div></section><aside class="card"><div class="card-head">清单证据</div><div class="card-body"><div class="folder-row">病案号<span>{{ archiveCase?.archive_no ?? '尚未生成' }}</span></div><div class="folder-row">清单文书<span>{{ archiveCase?.items.length ?? 0 }}</span></div><div class="folder-row">审计事件<span>{{ archiveCase?.events.length ?? 0 }}</span></div><div class="folder-row">导出包<span>{{ archiveCase?.export_packages.length ?? 0 }}</span></div></div></aside></div>
    </template>
    <BusinessActionDialog :open="actionDialog === 'create'" title="新建病案归档清单" description="将当前已签且质控通过的版本固化为不可变清单。" eyebrow="病案资产 / 归档" confirm-label="生成清单" :busy="Boolean(busy)" @cancel="actionDialog = null" @confirm="createCase"><p class="dialog-warning">关联资产验真失败或未验真时，服务端会拒绝归档。</p></BusinessActionDialog>
    <BusinessActionDialog :open="actionDialog === 'seal' || actionDialog === 'unseal'" :title="actionDialog === 'seal' ? '封存病案' : '授权解封病案'" description="操作将生成不可变事件，不覆盖原始清单。" eyebrow="病案资产 / 封存" :confirm-label="actionDialog === 'seal' ? '确认封存' : '确认解封'" :danger="actionDialog === 'unseal'" :busy="Boolean(busy)" :confirm-disabled="reason.trim().length < 4" @cancel="actionDialog = null" @confirm="transition(actionDialog === 'seal' ? 'seals' : 'unseals')"><label>{{ actionDialog === 'seal' ? '复核说明' : '解封理由' }}<textarea v-model="reason" rows="4" /></label></BusinessActionDialog>
    <BusinessActionDialog :open="actionDialog === 'export'" title="新建独立可读导出包" description="导出包带精确字节数和 SHA-256，可脱离系统复算。" eyebrow="病案资产 / 导出" confirm-label="生成导出包" :busy="Boolean(busy)" :confirm-disabled="purpose.trim().length < 2" @cancel="actionDialog = null" @confirm="createExport"><label>导出用途<input v-model="purpose" /></label></BusinessActionDialog>
  </section>
</template>
