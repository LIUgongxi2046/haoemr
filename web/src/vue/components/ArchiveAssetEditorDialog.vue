<script setup lang="ts">
import { markRaw, reactive, watch } from 'vue';
import type { MedicalRecordAssetWire } from '../../generated/contracts';
import BusinessActionDialog from './BusinessActionDialog.vue';

type AssetDraft = {
  assetType: MedicalRecordAssetWire['asset_type'];
  displayName: string;
  mediaType: string;
  pageCount: number;
  sourceSystem: string;
  location: string;
  contentHash: string;
  cdaStatus: MedicalRecordAssetWire['cda_status'];
  scanStatus: MedicalRecordAssetWire['scan_status'];
  preservationStatus: MedicalRecordAssetWire['preservation_status'];
  retentionYears: number;
  file: File | null;
};

const props = withDefaults(defineProps<{
  open: boolean;
  asset?: MedicalRecordAssetWire | null;
  presetType?: MedicalRecordAssetWire['asset_type'];
  busy?: boolean;
}>(), { asset: null, presetType: 'DIGITAL', busy: false });
const emit = defineEmits<{ cancel: []; save: [draft: AssetDraft] }>();

const form = reactive<AssetDraft>({
  assetType: 'DIGITAL', displayName: '', mediaType: 'application/xml', pageCount: 1,
  sourceSystem: 'haoemr', location: '', contentHash: '', cdaStatus: 'NOT_APPLICABLE',
  scanStatus: 'NOT_APPLICABLE', preservationStatus: 'NOT_SCHEDULED', retentionYears: 30,
  file: null,
});

watch(() => [props.open, props.asset, props.presetType] as const, ([open, asset, preset]) => {
  if (!open) return;
  form.assetType = asset?.asset_type ?? preset;
  form.displayName = asset?.display_name ?? (preset === 'SCAN' ? '纸质病历扫描件' : '病案资产');
  form.mediaType = asset?.media_type ?? (preset === 'SCAN' ? 'application/pdf' : 'application/xml');
  form.pageCount = asset?.page_count ?? 1;
  form.sourceSystem = asset?.source_system ?? 'haoemr';
  form.location = asset?.custody_location ?? '';
  form.contentHash = asset?.content_hash ?? '';
  form.cdaStatus = asset?.cda_status ?? 'NOT_APPLICABLE';
  form.scanStatus = asset?.scan_status ?? (preset === 'SCAN' ? 'CAPTURED' : 'NOT_APPLICABLE');
  form.preservationStatus = asset?.preservation_status ?? 'NOT_SCHEDULED';
  form.retentionYears = asset?.retention_years ?? 30;
  form.file = null;
}, { immediate: true });

function selectFile(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0] ?? null;
  form.file = file ? markRaw(file) : null;
  if (!file) return;
  form.mediaType = file.type || form.mediaType || 'application/octet-stream';
  if (!form.displayName.trim() || form.displayName === '病案资产' || form.displayName === '纸质病历扫描件') {
    form.displayName = file.name;
  }
}

function valid() {
  return form.displayName.trim().length >= 2 && form.mediaType.trim().length >= 3
    && form.sourceSystem.trim().length >= 2 && form.location.trim().length >= 2
    && form.pageCount >= 1 && form.retentionYears >= 15
    && Boolean(props.asset || form.file || (form.assetType === 'PAPER' && /^[0-9a-fA-F]{64}$/.test(form.contentHash.trim())));
}
</script>

<template>
  <BusinessActionDialog :open="open" :title="asset ? '编辑病案资产' : '新建病案资产'"
    :description="asset ? '只更正可变的描述与保管信息；原始载体、原始位置和内容哈希不会被覆盖。' : '新建后内容哈希成为不可变验真锚点。'"
    eyebrow="病案资产 / 编目" :confirm-label="asset ? '保存更正' : '完成编目'" :busy="busy" :confirm-disabled="!valid()" width="wide"
    @cancel="emit('cancel')" @confirm="emit('save', { ...form })">
    <div class="dialog-grid">
      <label>载体类型<select v-model="form.assetType" :disabled="Boolean(asset)"><option value="PAPER">纸质原件</option><option value="SCAN">扫描件</option><option value="DIGITAL">数字原生</option></select></label>
      <label>资产名称<input v-model="form.displayName" maxlength="256" /></label>
      <label>媒体类型<input v-model="form.mediaType" maxlength="96" placeholder="application/pdf" /></label>
      <label>页数 / 对象数<input v-model.number="form.pageCount" type="number" min="1" max="100000" /></label>
      <label>来源系统<input v-model="form.sourceSystem" maxlength="128" /></label>
      <label>当前保管位置<input v-model="form.location" maxlength="128" /></label>
      <label>CDA 状态<select v-model="form.cdaStatus" :disabled="Boolean(asset)"><option value="NOT_APPLICABLE">不适用</option><option value="PENDING">待服务端校验</option><option v-if="asset?.cda_status === 'VERIFIED'" value="VERIFIED">服务端校验通过</option><option v-if="asset?.cda_status === 'FAILED'" value="FAILED">服务端校验失败</option></select></label>
      <label>扫描流程<select v-model="form.scanStatus" disabled><option value="NOT_APPLICABLE">不适用</option><option value="CAPTURED">已采集</option><option value="OCR_REVIEWED">OCR 已复核</option><option value="INDEXED">已编目</option></select></label>
      <label>长期保存<select v-model="form.preservationStatus" disabled><option value="NOT_SCHEDULED">未纳入</option><option value="SCHEDULED">已排期</option><option value="SEALED">已封包</option><option value="VERIFIED">恢复验证通过</option></select></label>
      <label>保管年限<input v-model.number="form.retentionYears" type="number" min="15" max="100" /><small>门急诊不少于15年；住院及患者级资产不少于30年，服务端按就诊类型强制校验。</small></label>
    </div>
    <label v-if="!asset">原始文件（最大 50 MiB）<input data-testid="asset-file" type="file" @change="selectFile" /></label>
    <p v-if="!asset && form.file" class="dialog-warning">服务端将从 {{ form.file.name }} 的实际字节计算 SHA-256，完成恶意特征检查后再不可变写入文件存储。</p>
    <label v-if="!asset && !form.file && form.assetType === 'PAPER'">纸质载体观测哈希（SHA-256）<input v-model="form.contentHash" minlength="64" maxlength="64" placeholder="64 位十六进制哈希" /></label>
    <p v-if="asset" class="dialog-warning">不可变原始哈希：{{ asset.content_hash }}。需要替换内容时，请新建资产并作废旧资产。</p>
  </BusinessActionDialog>
</template>
