<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref, watch } from 'vue';
import {
  createMasterDataRecord, deactivateMasterDataRecord, listMasterDataRecords, updateMasterDataRecord,
  type MasterDataInput, type MasterDataRecord,
} from '../../api/administration-runtime';
import { issueConfigurationLease, listConfigurations } from '../../api/config';
import { configurationStudio } from '../configuration-studios';
import { toClinicalIssue } from '../clinical-error';
import AdminActionDialog from '../components/AdminActionDialog.vue';
import AdminConfirmDialog from '../components/AdminConfirmDialog.vue';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import ConfigurationStudioPage from '../components/ConfigurationStudioPage.vue';

const definition = configurationStudio('admin-master-data');
const mode = ref<'RECORDS' | 'CONFIG'>('RECORDS');
const keyword = ref('');
const statusFilter = ref('ACTIVE');
const selectedCatalogId = ref('');
const selectedRecordId = ref('');
const editorOpen = ref(false);
const deactivateTarget = ref<MasterDataRecord | null>(null);
const busy = ref('');
const notice = ref('');
const form = reactive({ nationalCode: '', localCode: '', displayName: '', categoryPath: '', nationalVersion: '2026', authoritativeSource: '国家医疗保障局 / 国家卫生健康委员会', mappingStatus: 'MATCHED', effectiveFrom: new Date().toISOString().slice(0, 10), effectiveUntil: '' });
const leaseQuery = useQuery({ queryKey: ['master-data', 'lease'], queryFn: issueConfigurationLease, retry: false, staleTime: 300_000, gcTime: 0 });
const catalogsQuery = useQuery({
  queryKey: ['master-data', 'catalogs'], queryFn: () => listConfigurations(leaseQuery.data.value!, 'MASTER_DATA'),
  enabled: () => Boolean(leaseQuery.data.value), retry: false,
});
const recordsQuery = useQuery({ queryKey: ['master-data', 'records'], queryFn: () => listMasterDataRecords(), retry: false });
const catalogs = computed(() => catalogsQuery.data.value ?? []);
const activeCatalogs = computed(() => catalogs.value.filter((item) => item.status === 'ACTIVE'));
const records = computed(() => (recordsQuery.data.value ?? []).filter((item) => {
  if (selectedCatalogId.value && item.config_id !== selectedCatalogId.value) return false;
  if (statusFilter.value && item.status !== statusFilter.value) return false;
  const needle = keyword.value.trim().toLocaleLowerCase();
  return !needle || `${item.local_code} ${item.national_code ?? ''} ${item.display_name} ${item.category_path}`.toLocaleLowerCase().includes(needle);
}));
const selectedRecord = computed(() => records.value.find((item) => item.record_id === selectedRecordId.value) ?? records.value[0] ?? null);
const selectedCatalog = computed(() => catalogs.value.find((item) => item.config_id === selectedCatalogId.value) ?? activeCatalogs.value[0] ?? null);
const issue = computed(() => {
  const error = leaseQuery.error.value ?? catalogsQuery.error.value ?? recordsQuery.error.value;
  return error ? toClinicalIssue(error) : null;
});
watch(activeCatalogs, (next) => {
  if (selectedCatalogId.value && !next.some((item) => item.config_id === selectedCatalogId.value)) selectedCatalogId.value = '';
}, { immediate: true });
watch(records, (next) => { if (!selectedRecordId.value && next[0]) selectedRecordId.value = next[0].record_id; }, { immediate: true });

const mappingLabel: Readonly<Record<string, string>> = Object.freeze({ MATCHED: '已匹配国家/行业编码', UNMATCHED: '待匹配', CONFLICT: '映射冲突', LOCAL_ONLY: '院内扩展' });
function formatDate(value: string | null) { return value ? new Date(value).toLocaleDateString('zh-CN') : '长期有效'; }
function openCreate() {
  if (!selectedCatalogId.value && activeCatalogs.value[0]) selectedCatalogId.value = activeCatalogs.value[0].config_id;
  Object.assign(form, { nationalCode: '', localCode: '', displayName: '', categoryPath: '', nationalVersion: '2026', authoritativeSource: '国家医疗保障局 / 国家卫生健康委员会', mappingStatus: 'MATCHED', effectiveFrom: new Date().toISOString().slice(0, 10), effectiveUntil: '' });
  selectedRecordId.value = ''; editorOpen.value = true; notice.value = '';
}
function openEdit(record: MasterDataRecord) {
  selectedCatalogId.value = record.config_id;
  selectedRecordId.value = record.record_id;
  Object.assign(form, { nationalCode: record.national_code ?? '', localCode: record.local_code, displayName: record.display_name, categoryPath: record.category_path, nationalVersion: record.national_version ?? '', authoritativeSource: record.authoritative_source, mappingStatus: record.mapping_status, effectiveFrom: record.effective_from.slice(0, 10), effectiveUntil: record.effective_until?.slice(0, 10) ?? '' });
  editorOpen.value = true; notice.value = '';
}
function input(): MasterDataInput {
  const catalog = selectedCatalog.value;
  if (!catalog) throw new Error('请先发布主数据目录');
  return {
    config_id: catalog.config_id, code_system: String(catalog.payload?.code_system ?? ''),
    national_code: form.nationalCode.trim() || null, local_code: form.localCode.trim(), display_name: form.displayName.trim(),
    category_path: form.categoryPath.trim(), national_version: form.nationalVersion.trim() || null,
    authoritative_source: form.authoritativeSource.trim(), mapping_status: form.mappingStatus,
    effective_from: new Date(`${form.effectiveFrom}T00:00:00+08:00`).toISOString(),
    effective_until: form.effectiveUntil ? new Date(`${form.effectiveUntil}T00:00:00+08:00`).toISOString() : null,
    attributes: { maintained_by: '主数据管理岗', source_page: 'ADMIN_MASTER_DATA' },
  };
}
async function save() {
  if (busy.value) return; busy.value = 'save'; notice.value = '';
  try {
    const existing = selectedRecord.value && selectedRecordId.value ? selectedRecord.value : null;
    const result = existing ? await updateMasterDataRecord(existing, input()) : await createMasterDataRecord(input());
    selectedRecordId.value = result.record_id; editorOpen.value = false;
    notice.value = existing ? '主数据记录已按乐观锁更新，并写入审计与事务事件。' : '主数据记录已写入数据库，并建立审计与事务事件。';
    await recordsQuery.refetch();
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}
async function deactivate() {
  const target = deactivateTarget.value; if (!target || busy.value) return; busy.value = 'deactivate'; notice.value = '';
  try { await deactivateMasterDataRecord(target, '主数据管理员确认停用，历史临床引用继续保留'); deactivateTarget.value = null; notice.value = '主数据记录已停用，历史引用未删除。'; await recordsQuery.refetch(); }
  catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}
</script>

<template>
  <div v-if="mode === 'CONFIG'"><div class="auth-backbar content"><button class="btn" type="button" @click="mode = 'RECORDS'">← 返回主数据记录</button></div><ConfigurationStudioPage :definition="definition" /></div>
  <section v-else data-page-root class="content admin-content vue-native-page">
    <div class="page-head"><div class="page-title"><h1>医院主数据与标准编码</h1><p>目录版本与记录值分层管理，维护国家/行业编码、本地编码、权威来源、有效期和映射状态</p></div><div class="head-actions"><button class="btn" type="button" @click="recordsQuery.refetch()">同步刷新</button><button class="btn" type="button" @click="mode = 'CONFIG'">目录版本管理</button><button class="btn primary" type="button" :disabled="!activeCatalogs.length" @click="openCreate">新建主数据记录</button></div></div>
    <ClinicalPageState v-if="leaseQuery.isPending.value || catalogsQuery.isPending.value || recordsQuery.isPending.value" kind="loading" message="正在读取主数据目录与记录" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="recordsQuery.refetch()" />
    <template v-else>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>
      <section class="admin-metrics"><article><span>已发布目录</span><strong>{{ activeCatalogs.length }}</strong><small>可承载运行数据</small></article><article><span>在用记录</span><strong>{{ (recordsQuery.data.value ?? []).filter(item => item.status === 'ACTIVE').length }}</strong><small>真实数据库记录</small></article><article><span>标准已匹配</span><strong>{{ (recordsQuery.data.value ?? []).filter(item => item.mapping_status === 'MATCHED').length }}</strong><small>国家/行业映射</small></article><article><span>待治理映射</span><strong>{{ (recordsQuery.data.value ?? []).filter(item => ['UNMATCHED','CONFLICT'].includes(item.mapping_status)).length }}</strong><small>可由治理任务发现</small></article></section>
      <section class="admin-panel admin-form-panel"><div class="toolbar"><select v-model="selectedCatalogId" class="select" aria-label="已发布目录"><option value="">全部已发布目录</option><option v-for="catalog in activeCatalogs" :key="catalog.config_id" :value="catalog.config_id">{{ catalog.display_name }} · {{ catalog.payload?.code_system }}</option></select><input v-model="keyword" class="search" placeholder="名称、本地编码、国家编码或分类"><select v-model="statusFilter" class="select" aria-label="主数据状态"><option value="">全部状态</option><option value="ACTIVE">在用</option><option value="INACTIVE">已停用</option></select></div><div v-if="!activeCatalogs.length" class="notice hard">没有已发布目录。记录级 CRUD 会安全失败关闭，请先在目录版本管理中完成校验、独立审批和发布。</div></section>
      <div class="grid admin-list-detail">
        <section class="card"><div class="card-head">主数据记录清单 <span class="status blue">{{ records.length }} 条</span></div><div v-if="!records.length" class="admin-empty">当前筛选条件下没有记录。</div><div v-else class="admin-table-wrap"><table class="table"><thead><tr><th>名称 / 分类</th><th>本地编码</th><th>国家/行业编码</th><th>映射</th><th>有效期</th></tr></thead><tbody><tr v-for="record in records" :key="record.record_id" :class="{ selected: selectedRecord?.record_id === record.record_id }" @click="selectedRecordId = record.record_id"><td><b>{{ record.display_name }}</b><br><span class="meta">{{ record.category_path }}</span></td><td>{{ record.local_code }}<br><span class="meta">{{ record.code_system }}</span></td><td>{{ record.national_code ?? '未映射' }}<br><span class="meta">{{ record.national_version ?? '无版本' }}</span></td><td><span class="status" :class="record.mapping_status === 'MATCHED' ? 'green' : record.mapping_status === 'CONFLICT' ? 'red' : 'amber'">{{ mappingLabel[record.mapping_status] ?? record.mapping_status }}</span></td><td>{{ formatDate(record.effective_from) }} — {{ formatDate(record.effective_until) }}</td></tr></tbody></table></div></section>
        <aside v-if="selectedRecord" class="card"><div class="card-head">{{ selectedRecord.display_name }} · 记录详情 <span class="status" :class="selectedRecord.status === 'ACTIVE' ? 'green' : 'amber'">{{ selectedRecord.status === 'ACTIVE' ? '在用' : '已停用' }}</span></div><div class="card-body"><div class="folder-row">权威来源<span>{{ selectedRecord.authoritative_source }}</span></div><div class="folder-row">编码体系<span>{{ selectedRecord.code_system }}</span></div><div class="folder-row">映射状态<span>{{ mappingLabel[selectedRecord.mapping_status] }}</span></div><div class="folder-row">数据库版本<span>v{{ selectedRecord.row_version }}</span></div><div class="folder-row">最后更新<span>{{ formatDate(selectedRecord.updated_at) }}</span></div><div class="notice info">停用只影响新业务选择，历史处方、医嘱、检验和病历中的编码引用不会被物理删除。</div><div class="admin-actions vertical"><button class="btn primary" type="button" :disabled="selectedRecord.status !== 'ACTIVE'" @click="openEdit(selectedRecord)">编辑记录</button><button class="btn" type="button" :disabled="selectedRecord.status !== 'ACTIVE'" @click="deactivateTarget = selectedRecord">停用记录</button></div></div></aside>
      </div>
    </template>
    <AdminActionDialog v-model:open="editorOpen" :title="selectedRecordId ? '编辑主数据记录' : '新建主数据记录'" description="记录必须归属已发布目录；已匹配状态必须填写国家或行业编码。" size="large" :busy="Boolean(busy)"><form class="admin-form" @submit.prevent="save"><label><span>目录</span><select v-model="selectedCatalogId" required><option v-for="catalog in activeCatalogs" :key="catalog.config_id" :value="catalog.config_id">{{ catalog.display_name }} · {{ catalog.payload?.code_system }}</option></select></label><label><span>中文名称</span><input v-model="form.displayName" required autofocus placeholder="例：冠状动脉粥样硬化性心脏病" /></label><label><span>本地编码</span><input v-model="form.localCode" required placeholder="例：HOSP-DX-I25.10" /></label><label><span>国家/行业编码</span><input v-model="form.nationalCode" :required="form.mappingStatus === 'MATCHED'" placeholder="例：I25.10" /></label><label><span>分类路径</span><input v-model="form.categoryPath" required placeholder="诊断>循环系统疾病>缺血性心脏病" /></label><label><span>标准版本</span><input v-model="form.nationalVersion" placeholder="例：医保版 2.0" /></label><label><span>权威来源</span><input v-model="form.authoritativeSource" required /></label><label><span>映射状态</span><select v-model="form.mappingStatus"><option value="MATCHED">已匹配国家/行业编码</option><option value="UNMATCHED">待匹配</option><option value="CONFLICT">映射冲突</option><option value="LOCAL_ONLY">院内扩展</option></select></label><label><span>生效日期</span><input v-model="form.effectiveFrom" type="date" required /></label><label><span>失效日期（可选）</span><input v-model="form.effectiveUntil" type="date" /></label><button class="button primary" :disabled="Boolean(busy)">{{ busy ? '正在保存…' : '保存数据库记录' }}</button></form></AdminActionDialog>
    <AdminConfirmDialog :open="Boolean(deactivateTarget)" title="停用主数据记录" description="停用后该值不再进入新业务选择，但历史引用和审计证据永久保留。" confirm-label="确认停用" :busy="Boolean(busy)" @update:open="!$event && (deactivateTarget = null)" @confirm="deactivate" />
  </section>
</template>
