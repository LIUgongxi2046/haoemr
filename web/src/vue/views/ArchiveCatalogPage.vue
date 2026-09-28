<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import type { MedicalRecordAssetWire } from '../../generated/contracts';
import { downloadMedicalRecordAsset, ingestMedicalRecordAsset, issueMedicalRecordAssetLease, listMedicalRecordAssets, registerMedicalRecordAsset, retireMedicalRecordAsset, updateMedicalRecordAsset } from '../../api/records';
import ArchiveAssetEditorDialog from '../components/ArchiveAssetEditorDialog.vue';
import BusinessActionDialog from '../components/BusinessActionDialog.vue';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';

const assetsQuery = useQuery({ queryKey: ['clinical', 'medical-record-assets', 'catalog'], queryFn: async () => { const lease = await issueMedicalRecordAssetLease(); return { lease, assets: await listMedicalRecordAssets(lease) }; }, retry: false, staleTime: 0, gcTime: 0 });
const issue = computed(() => assetsQuery.error.value ? toClinicalIssue(assetsQuery.error.value) : null);
const assets = computed(() => assetsQuery.data.value?.assets ?? []);
const activeAssets = computed(() => assets.value.filter((asset) => asset.status !== 'RETIRED'));
const retiredAssets = computed(() => assets.value.filter((asset) => asset.status === 'RETIRED'));
const busy = ref(false);
const notice = ref('');
const editorOpen = ref(false);
const rulesOpen = ref(false);
const editing = ref<MedicalRecordAssetWire | null>(null);
const retireTarget = ref<MedicalRecordAssetWire | null>(null);
const retireReason = ref('');
const router = useRouter();
const selectedAssetId = ref('');
const selectedAsset = computed(() => activeAssets.value.find((asset) => asset.medical_record_asset_id === selectedAssetId.value) ?? activeAssets.value.find((asset) => asset.integrity_status !== 'VERIFIED') ?? activeAssets.value[0] ?? null);
const verifiedCount = computed(() => activeAssets.value.filter((asset) => asset.integrity_status === 'VERIFIED').length);
const missingCount = computed(() => activeAssets.value.filter((asset) => asset.storage_status === 'MISSING').length);
const pendingCount = computed(() => activeAssets.value.filter((asset) => asset.integrity_status === 'PENDING').length);
const completion = computed(() => activeAssets.value.length ? Math.round(verifiedCount.value / activeAssets.value.length * 100) : 100);

function openCreate() { editing.value = null; editorOpen.value = true; }
function openEdit(asset: MedicalRecordAssetWire) { editing.value = asset; editorOpen.value = true; }
function openCorrection() { if (selectedAsset.value) void router.push(`/archive-assets/${selectedAsset.value.medical_record_asset_id}/actions?create=1`); else openCreate(); }
async function save(draft: any) {
  const data = assetsQuery.data.value; if (!data || busy.value) return; busy.value = true; notice.value = '';
  try {
    if (editing.value) await updateMedicalRecordAsset(data.lease, editing.value, { display_name: draft.displayName, media_type: draft.mediaType, page_count: draft.pageCount, source_system: draft.sourceSystem, custody_location: draft.location, cda_status: draft.cdaStatus, scan_status: draft.scanStatus, preservation_status: draft.preservationStatus, retention_years: draft.retentionYears });
    else if (draft.file) await ingestMedicalRecordAsset(data.lease, { assetType: draft.assetType, location: draft.location, displayName: draft.displayName, mediaType: draft.mediaType, pageCount: draft.pageCount, sourceSystem: draft.sourceSystem, file: draft.file, cdaStatus: draft.cdaStatus, retentionYears: draft.retentionYears });
    else await registerMedicalRecordAsset(data.lease, { assetType: draft.assetType, location: draft.location, contentHash: draft.contentHash, displayName: draft.displayName, mediaType: draft.mediaType, pageCount: draft.pageCount, sourceSystem: draft.sourceSystem, cdaStatus: draft.cdaStatus, scanStatus: draft.scanStatus, preservationStatus: draft.preservationStatus, retentionYears: draft.retentionYears });
    notice.value = editing.value ? '目录项已更正，原始内容哈希未被覆盖。' : draft.file ? '原始文件已写入并完成资产编目。' : '目录项已建立并固化内容哈希锚点。';
    editorOpen.value = false; editing.value = null; await assetsQuery.refetch();
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; } finally { busy.value = false; }
}
async function download(asset: MedicalRecordAssetWire) { const data = assetsQuery.data.value; if (!data || busy.value) return; busy.value = true; try { const result = await downloadMedicalRecordAsset(data.lease, asset.medical_record_asset_id); const url = URL.createObjectURL(result.blob); const anchor = document.createElement('a'); anchor.href = url; anchor.download = result.filename; anchor.click(); URL.revokeObjectURL(url); notice.value = `已从存储重读原件，SHA-256 ${result.contentHash?.slice(0, 16) ?? '未返回'}…`; } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; } finally { busy.value = false; } }
async function retire() { const data = assetsQuery.data.value; if (!data || !retireTarget.value || busy.value || retireReason.value.trim().length < 4) return; busy.value = true; notice.value = ''; try { await retireMedicalRecordAsset(data.lease, retireTarget.value, retireReason.value.trim()); notice.value = '目录资产已作废并退出借阅、归档与保存流程，证据仍可追溯。'; retireTarget.value = null; retireReason.value = ''; await assetsQuery.refetch(); } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; } finally { busy.value = false; } }
function groupLabel(value: string) { return ({ PAPER: '纸质原件', SCAN: '扫描资料', DIGITAL: '电子文书' } as Record<string, string>)[value] || value; }
function statusLabel(asset: MedicalRecordAssetWire) { return asset.status === 'RETIRED' ? '已作废' : asset.status === 'BORROWED' ? '借阅中' : asset.status === 'ARCHIVED' ? '已归档' : asset.status; }
function integrityLabel(value: string) { return ({ PENDING: '待复核', VERIFIED: '通过', FAILED: '阻断' } as Record<string, string>)[value] || value; }
function integrityClass(value: string) { return value === 'VERIFIED' ? 'green' : value === 'FAILED' ? 'red' : 'amber'; }
</script>

<template>
  <section data-page-root class="content vue-native-page archive-prototype-page">
    <div class="page-head"><div class="page-title"><h1>病案目录与完整性</h1><p>围绕“应有—已有—页序—签署—归档”管理完整病案</p></div><div class="head-actions"><button class="btn" @click="rulesOpen = true">目录规则 v5.2</button><button class="btn primary" :disabled="!selectedAsset" @click="openCorrection">生成整改任务</button></div></div>
    <ClinicalPageState v-if="assetsQuery.isPending.value" kind="loading" message="正在加载病案目录" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="assetsQuery.refetch()" />
    <template v-else>
      <div v-if="notice" class="notice archive-notice" role="status">{{ notice }}</div>
      <div class="grid catalog-layout">
        <section class="card"><div class="card-head">当前患者病案目录 <span class="status" :class="completion === 100 ? 'green' : 'amber'">完整度 {{ completion }}%</span></div><div class="archive-table-wrap"><table class="table"><thead><tr><th>目录组</th><th>应有文书</th><th>资产</th><th>状态</th><th>页序</th><th>检查</th></tr></thead><tbody><tr v-for="(asset,index) in assets" :key="asset.medical_record_asset_id" :class="{ 'prototype-row-active': selectedAsset?.medical_record_asset_id === asset.medical_record_asset_id, 'is-voided': asset.status === 'RETIRED' }" @click="selectedAssetId = asset.medical_record_asset_id"><td>{{ groupLabel(asset.asset_type) }}</td><td><b>{{ asset.display_name }}</b><small>{{ asset.source_system }}</small></td><td>…{{ asset.medical_record_asset_id.slice(-8) }}</td><td>{{ statusLabel(asset) }}</td><td>{{ asset.page_count ? `${index + 1}–${index + asset.page_count}` : '—' }}</td><td><span class="status" :class="integrityClass(asset.integrity_status)">{{ integrityLabel(asset.integrity_status) }}</span></td></tr><tr v-if="!assets.length"><td colspan="6" class="prototype-empty">尚无目录资产，请先新建编目。</td></tr></tbody></table></div><div class="prototype-card-actions"><div class="prototype-action-row"><button class="btn primary" @click="openCreate">新建资产</button><button class="btn" :disabled="!selectedAsset" @click="selectedAsset && openEdit(selectedAsset)">编辑</button><button class="btn" :disabled="!selectedAsset || selectedAsset.storage_status === 'MISSING'" @click="selectedAsset && download(selectedAsset)">下载原件</button><button class="btn danger" :disabled="!selectedAsset || selectedAsset.status !== 'ARCHIVED'" @click="retireTarget = selectedAsset; retireReason = ''">删除</button><RouterLink v-if="selectedAsset" class="btn" :to="`/asset-detail?asset=${selectedAsset.medical_record_asset_id}`">证据详情</RouterLink></div></div></section>
        <aside class="card"><div class="card-head">缺失与责任</div><div class="card-body"><div v-if="selectedAsset && selectedAsset.integrity_status !== 'VERIFIED'" class="notice hard"><div class="notice-title">{{ selectedAsset.display_name }}{{ selectedAsset.storage_status === 'MISSING' ? '缺失' : '待复核' }}</div>当前状态会影响借阅、归档与长期保存；责任人需完成原件补录或哈希重读验真。</div><div v-else class="notice info"><div class="notice-title">当前目录无硬性缺失</div>已选资产的目录、页数与验真状态可继续进入归档流程。</div><div class="folder-row">应有目录项<span>{{ activeAssets.length + missingCount }}</span></div><div class="folder-row">已有资产<span>{{ activeAssets.length }}</span></div><div class="folder-row">验真通过<span>{{ verifiedCount }}</span></div><div class="folder-row">待扫描复核<span>{{ pendingCount }}</span></div><div class="folder-row">缺失阻断<span>{{ missingCount }}</span></div><div class="folder-row">已作废留痕<span>{{ retiredAssets.length }}</span></div><button class="btn primary" style="width:100%;margin-top:12px" :disabled="!selectedAsset" @click="openCorrection">分派病案整改</button></div></aside>
      </div>
    </template>
    <ArchiveAssetEditorDialog :open="editorOpen" :asset="editing" :busy="busy" @cancel="editorOpen = false; editing = null" @save="save" />
    <BusinessActionDialog :open="rulesOpen" title="目录规则 v5.2" description="目录规则用于校验应有文书、页序、签署与归档状态。" eyebrow="病案目录 / 规则" confirm-label="已了解" @cancel="rulesOpen = false" @confirm="rulesOpen = false"><div class="folder-row">应有文书<span>按患者就诊类型生成</span></div><div class="folder-row">页序规则<span>连续、无重复、无缺页</span></div><div class="folder-row">硬性门禁<span>签署 + 质控 + 哈希验真</span></div></BusinessActionDialog>
    <BusinessActionDialog :open="Boolean(retireTarget)" :title="`删除资产：${retireTarget?.display_name ?? ''}`" description="作废后立即退出借阅、归档和长期保存待办，但保留全部证据。" eyebrow="病案资产 / 删除" confirm-label="确认作废" danger :busy="busy" :confirm-disabled="retireReason.trim().length < 4" @cancel="retireTarget = null" @confirm="retire"><p class="dialog-warning">已借出或已长期封包的资产不允许作废。</p><label>作废理由（至少 4 字）<textarea v-model="retireReason" rows="4" maxlength="1000" /></label></BusinessActionDialog>
  </section>
</template>
