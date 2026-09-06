<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { issueMedicalRecordAssetLease, listMedicalRecordAssetIntegrityEvents, listMedicalRecordAssets, retireMedicalRecordAsset, updateMedicalRecordAsset, verifyMedicalRecordAssetStorage, validateMedicalRecordAssetCda } from '../../api/records';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import ArchiveAssetEditorDialog from '../components/ArchiveAssetEditorDialog.vue';
import BusinessActionDialog from '../components/BusinessActionDialog.vue';
import { toClinicalIssue } from '../clinical-error';

const route = useRoute();
const selectedId = ref(typeof route.params.assetId === 'string' ? route.params.assetId : typeof route.query.asset === 'string' ? route.query.asset : '');
const busy = ref(false); const notice = ref(''); const editorOpen = ref(false); const retireOpen = ref(false); const retireReason = ref(''); const verifyOpen = ref(false);
const assetsQuery = useQuery({
  queryKey: ['clinical', 'medical-record-assets', 'detail'],
  queryFn: async () => {
    const lease = await issueMedicalRecordAssetLease();
    const assets = await listMedicalRecordAssets(lease);
    const id = selectedId.value || assets[0]?.medical_record_asset_id || '';
    const events = id ? await listMedicalRecordAssetIntegrityEvents(lease, id) : [];
    return { lease, assets, events };
  },
  retry: false, staleTime: 0, gcTime: 0,
});
const issue = computed(() => assetsQuery.error.value ? toClinicalIssue(assetsQuery.error.value) : null);
const assets = computed(() => assetsQuery.data.value?.assets ?? []);
const selected = computed(() => assets.value.find((asset) => asset.medical_record_asset_id === selectedId.value) ?? assets.value[0] ?? null);
const events = computed(() => assetsQuery.data.value?.events ?? []);
watch(selectedId, () => { void assetsQuery.refetch(); });
const patientBadge = computed(() => selected.value?.patient_id ? `患者 · …${selected.value.patient_id.slice(-8)}` : '未关联患者');

async function save(draft:any){const data=assetsQuery.data.value,asset=selected.value;if(!data||!asset)return;busy.value=true;try{await updateMedicalRecordAsset(data.lease,asset,{display_name:draft.displayName,media_type:draft.mediaType,page_count:draft.pageCount,source_system:draft.sourceSystem,custody_location:draft.location,cda_status:draft.cdaStatus,scan_status:draft.scanStatus,preservation_status:draft.preservationStatus,retention_years:draft.retentionYears});notice.value='资产可变元数据已更正，原始证据未改动。';editorOpen.value=false;await assetsQuery.refetch();}catch(error){const next=toClinicalIssue(error);notice.value=`${next.code}：${next.message}`;}finally{busy.value=false;}}
async function retire(){const data=assetsQuery.data.value,asset=selected.value;if(!data||!asset||retireReason.value.trim().length<4)return;busy.value=true;try{await retireMedicalRecordAsset(data.lease,asset,retireReason.value.trim());notice.value='资产已作废，原始证据与作废理由保留。';retireOpen.value=false;await assetsQuery.refetch();}catch(error){const next=toClinicalIssue(error);notice.value=`${next.code}：${next.message}`;}finally{busy.value=false;}}
async function verify(){const data=assetsQuery.data.value,asset=selected.value;if(!data||!asset)return;busy.value=true;try{await verifyMedicalRecordAssetStorage(data.lease,asset);notice.value='服务端已从对象存储重读原件并写入验真证据；未采用人工录入哈希。';verifyOpen.value=false;await assetsQuery.refetch();}catch(error){const next=toClinicalIssue(error);notice.value=`${next.code}：${next.message}`;}finally{busy.value=false;}}
async function validateCda(){const data=assetsQuery.data.value,asset=selected.value;if(!data||!asset)return;busy.value=true;try{await validateMedicalRecordAssetCda(data.lease,asset);notice.value='CDA 状态已由服务端校验器生成并绑定证据摘要。';await assetsQuery.refetch();}catch(error){const next=toClinicalIssue(error);notice.value=`${next.code}：${next.message}`;}finally{busy.value=false;}}

function typeLabel(value: string) { return ({ PAPER: '纸质原件', SCAN: '扫描件', DIGITAL: '数字原生' } as Record<string, string>)[value] || value; }
function statusLabel(value: string) { return ({ ARCHIVED: '在库', BORROWED: '借出中', RETIRED: '已作废' } as Record<string, string>)[value] || value; }
function formatDate(value: string | null | undefined) { return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short', hour12: false }).format(new Date(value)) : '—'; }
</script>

<template>
  <section data-page-root class="content vue-native-page archive-content">
    <div class="page-heading archive-heading">
      <div><p class="eyebrow">病历与病案 / 病案资产证据</p><h1>病案资产证据详情</h1><p>单份病案资产的不可变身份、内容哈希与借阅状态；原件哈希在编目后即长期验真锚点，不可被前端或后续流程改写。</p></div>
      <div class="toolbar-actions"><RouterLink class="btn" to="/archive-integrity">返回完整性验真</RouterLink><button class="btn" :disabled="!selected || selected.status === 'RETIRED'" @click="editorOpen=true">编辑</button><button class="btn" :disabled="!selected || selected.status === 'RETIRED' || selected.storage_status === 'MISSING'" @click="verifyOpen=true">服务端重读验真</button><button class="btn" :disabled="!selected || selected.cda_status !== 'PENDING' || selected.media_type !== 'application/xml'" @click="validateCda">执行CDA校验</button><button class="btn danger" :disabled="!selected || selected.status !== 'ARCHIVED'" @click="retireOpen=true;retireReason=''">删除</button></div>
    </div>
    <section class="patient-strip" aria-label="患者上下文"><div class="patient-avatar">{{ patientBadge }}</div>
      <div><strong>{{ patientBadge }}</strong><span>病案资产证据 · 患者级上下文</span></div><dl>
        <div><dt>证据口径</dt><dd>服务端事实</dd></div>
        <div><dt>原件保真</dt><dd>内容哈希锚定</dd></div>
        <div><dt>不可变性</dt><dd>触发器兜底</dd></div></dl>
      <span class="lease-badge">已核验</span></section>
    <nav v-if="selected" class="archive-depth-nav" aria-label="病案资产二到七级导航"><RouterLink to="/archive-assets">L3 资产台账</RouterLink><RouterLink :to="`/archive-assets/${selected.medical_record_asset_id}`">L4 资产证据</RouterLink><RouterLink :to="`/archive-assets/${selected.medical_record_asset_id}/actions`">L5 整改动作</RouterLink><RouterLink :to="`/archive-assets/${selected.medical_record_asset_id}/evidence`">L6 证据束</RouterLink><RouterLink :to="`/archive-assets/${selected.medical_record_asset_id}/reviews`">L7 复核 / Agent</RouterLink></nav>
    <ClinicalPageState v-if="assetsQuery.isPending.value" kind="loading" message="正在加载病案资产证据" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="assetsQuery.refetch()" />
    <div v-else-if="assets.length === 0" class="archive-panel archive-empty" style="min-height: 320px"><span>证</span><p>当前患者尚无已编目资产</p><small>编目后才会产生内容哈希证据。</small></div>
    <div v-else><div v-if="notice" class="notice archive-notice">{{ notice }}</div><div class="archive-grid">
      <section class="archive-panel">
        <div class="archive-panel-heading"><div><span class="archive-step">证</span><h2>资产不可变身份</h2></div><span v-if="selected" class="state-chip" :class="{ signed: selected.status === 'ARCHIVED' }">{{ statusLabel(selected.status) }}</span></div>
        <dl class="archive-identity">
          <div><dt>资产 ID</dt><dd><code>{{ selected?.medical_record_asset_id }}</code></dd></div>
          <div><dt>患者归属</dt><dd><code>…{{ selected?.patient_id.slice(-8) }}</code></dd></div>
          <div><dt>关联就诊</dt><dd><code>{{ selected?.encounter_id ? `…${selected.encounter_id.slice(-8)}` : '无（患者级资产）' }}</code></dd></div>
          <div><dt>载体类型</dt><dd>{{ selected ? typeLabel(selected.asset_type) : '—' }}</dd></div>
          <div><dt>原始存放位置</dt><dd>{{ selected?.location }}</dd></div>
          <div><dt>当前保管位置</dt><dd>{{ selected?.custody_location }}</dd></div>
          <div><dt>行版本</dt><dd>{{ selected?.row_version }}</dd></div>
        </dl>
        <div class="archive-pass" style="margin-top: 14px"><span>✓</span><div><strong>原件内容哈希已锚定</strong><p>编目后 patient_id / 载体 / 位置 / 内容哈希 由数据库触发器禁止更新，原件与转换件不可互相替代。</p></div></div>
        <div class="archive-panel-heading" style="margin-top: 18px"><div><span class="archive-step">哈</span><h2>内容哈希（SHA-256）</h2></div></div>
        <div class="archive-table-wrap"><table class="archive-table"><tbody><tr><td style="font-family: ui-monospace, monospace; word-break: break-all">{{ selected?.content_hash }}</td></tr></tbody></table></div>
        <div class="archive-panel-heading" style="margin-top: 18px"><div><span class="archive-step">借</span><h2>借阅状态</h2></div></div>
        <dl class="archive-identity">
          <div><dt>状态</dt><dd>{{ selected ? statusLabel(selected.status) : '—' }}</dd></div>
          <div><dt>借阅人</dt><dd><code>{{ selected?.borrowed_by ? `…${selected.borrowed_by.slice(-8)}` : '—' }}</code></dd></div>
          <div><dt>借出时间</dt><dd>{{ formatDate(selected?.borrowed_at) }}</dd></div>
          <div><dt>到期时间</dt><dd>{{ formatDate(selected?.due_at) }}</dd></div>
        </dl>
      </section>
      <aside class="archive-panel">
        <div class="archive-panel-heading"><div><span class="archive-step">选</span><h2>选择资产</h2></div><span>{{ assets.length }} 份</span></div>
        <div class="ward-list">
          <button v-for="asset in assets" :key="asset.medical_record_asset_id" :class="{ active: selected?.medical_record_asset_id === asset.medical_record_asset_id }" @click="selectedId = asset.medical_record_asset_id">
            <b>{{ typeLabel(asset.asset_type) }} · {{ asset.location }}</b>
            <span>{{ asset.content_hash.slice(0, 22) }}… · {{ statusLabel(asset.status) }}</span>
          </button>
        </div>
        <div class="archive-panel-heading" style="margin-top:18px"><div><span class="archive-step">验</span><h2>验真历史</h2></div></div><div class="archive-timeline"><article v-for="event in events" :key="event.integrity_event_id"><strong>{{ event.result }}</strong><small>{{ new Date(event.verified_at).toLocaleString('zh-CN') }} · …{{ event.verified_by.slice(-8) }}</small><code>{{ event.observed_hash.slice(0,18) }}…</code></article><p v-if="!events.length" class="clinical-empty-state">尚无验真历史</p></div>
      </aside></div></div>
    <ArchiveAssetEditorDialog :open="editorOpen" :asset="selected" :busy="busy" @cancel="editorOpen=false" @save="save"/>
    <BusinessActionDialog :open="verifyOpen" title="服务端重读验真" description="服务端从配置的对象存储重新读取原件并计算 SHA-256；不接受人工填写观测哈希。" eyebrow="资产证据 / 验真" confirm-label="执行重读比对" :busy="busy" @cancel="verifyOpen=false" @confirm="verify"><p class="dialog-warning">{{ selected?.storage_provider }} · {{ selected?.original_filename }}</p></BusinessActionDialog>
    <BusinessActionDialog :open="retireOpen" title="删除病案资产" description="实际执行可审计的业务作废，不物理删除证据。" eyebrow="资产证据 / 删除" confirm-label="确认作废" danger :busy="busy" :confirm-disabled="retireReason.trim().length<4" @cancel="retireOpen=false" @confirm="retire"><label>作废理由<textarea v-model="retireReason" rows="4"/></label></BusinessActionDialog>
  </section>
</template>

<style scoped>
.archive-depth-nav{display:flex;gap:8px;overflow:auto}.archive-depth-nav a{flex:0 0 auto;padding:8px 11px;border:1px solid var(--line);border-radius:8px;background:#fff;color:#42627c;text-decoration:none}.archive-depth-nav a.router-link-active{border-color:var(--blue);background:#eef6ff;color:var(--blue);font-weight:800}
</style>
