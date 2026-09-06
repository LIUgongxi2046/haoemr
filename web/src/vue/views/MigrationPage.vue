<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref } from 'vue';
import type { HistoricalMigrationBatchWire, HistoricalMigrationSourceImportWire, SourceFieldMappingWire, SourceSystemInventoryWire } from '../../generated/contracts';
import {
  activateSourceSystem, configureSourceSystem, deactivateSourceFieldMapping, issueGovernanceLease,
  archiveHistoricalMigrationBatch,
  importSourcePatientMatchCandidatesCsv, listHistoricalMigrationBatches, listHistoricalMigrationCheckpoints,
  listSourceFieldMappings, listSourcePatientMatchCandidateImports,
  listSourcePatientMatchCandidates, listSourceSystems, reconcileHistoricalMigrationBatch,
  recordHistoricalMigrationCheckpoint, recordSourcePatientMatchCandidate, registerSourceFieldMapping,
  registerSourceSystem, resolveSourcePatientMatchCandidate, retireSourceSystem, rollbackHistoricalMigrationBatch,
  startHistoricalMigrationBatch, startHistoricalMigrationObservation, switchHistoricalMigrationBatch,
} from '../../api/governance';
import AdminActionDialog from '../components/AdminActionDialog.vue';
import AdminConfirmDialog from '../components/AdminConfirmDialog.vue';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';

const leaseQuery = useQuery({
  queryKey: ['governance', 'migration', 'lease'],
  queryFn: () => issueGovernanceLease('MIGRATION_ADMIN'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const sourcesQuery = useQuery({
  queryKey: ['governance', 'migration', 'sources'],
  queryFn: () => listSourceSystems(leaseQuery.data.value!),
  enabled: () => Boolean(leaseQuery.data.value), retry: false,
});
const batchesQuery = useQuery({
  queryKey: ['governance', 'migration', 'batches'],
  queryFn: () => listHistoricalMigrationBatches(leaseQuery.data.value!),
  enabled: () => Boolean(leaseQuery.data.value), retry: false,
});
const selectedSourceId = ref('');
const selectedBatchId = ref('');
const candidatesQuery = useQuery({
  queryKey: ['governance', 'migration', 'candidates', selectedSourceId],
  queryFn: () => listSourcePatientMatchCandidates(leaseQuery.data.value!, selectedSourceId.value),
  enabled: () => Boolean(leaseQuery.data.value && selectedSourceId.value), retry: false,
});
const importsQuery = useQuery({
  queryKey: ['governance', 'migration', 'source-imports', selectedSourceId],
  queryFn: () => listSourcePatientMatchCandidateImports(leaseQuery.data.value!, selectedSourceId.value),
  enabled: () => Boolean(leaseQuery.data.value && selectedSourceId.value), retry: false,
});
const issue = computed(() => (leaseQuery.error.value ?? sourcesQuery.error.value ?? batchesQuery.error.value)
  ? toClinicalIssue(leaseQuery.error.value ?? sourcesQuery.error.value ?? batchesQuery.error.value) : null);

const sources = computed(() => sourcesQuery.data.value ?? []);
const registeredSourceCodes = computed(() => new Set(sources.value.map((source) => source.source_code)));
const batches = computed(() => (batchesQuery.data.value ?? [])
  .filter((batch) => registeredSourceCodes.value.has(batch.source_system)));
const candidates = computed(() => candidatesQuery.data.value ?? []);
const sourceImports = computed(() => importsQuery.data.value ?? []);
const totalRecords = computed(() => batches.value.reduce((sum, batch) => sum + batch.record_count, 0));
const totalMismatches = computed(() => batches.value.reduce((sum, batch) => sum + batch.mismatch_count, 0));
const reconciledCount = computed(() => batches.value.filter((batch) => ['RECONCILED', 'SWITCHED', 'OBSERVING', 'ARCHIVED'].includes(batch.batch_status)).length);
const differenceQueue = computed(() => batches.value.filter((batch) => batch.mismatch_count > 0));
const migrationSteps = ['源盘点', '字段映射', '患者匹配', '试迁', '对账', '切换', '观察', '归档'];
const currentStep = computed(() => {
  if (batches.value.some((batch) => batch.batch_status === 'ARCHIVED')) return 8;
  if (batches.value.some((batch) => batch.batch_status === 'OBSERVING')) return 7;
  if (batches.value.some((batch) => batch.batch_status === 'SWITCHED')) return 6;
  if (batches.value.some((batch) => batch.batch_status === 'RECONCILED')) return 5;
  if (batches.value.some((batch) => batch.batch_status === 'TRIAL')) return 4;
  if (sources.value.some((source) => source.connection_status === 'ACTIVE')) return 3;
  if (sources.value.length) return 2;
  return 1;
});

const mappingsQuery = useQuery({
  queryKey: ['governance', 'migration', 'mappings', selectedSourceId],
  queryFn: () => listSourceFieldMappings(leaseQuery.data.value!, selectedSourceId.value),
  enabled: () => Boolean(leaseQuery.data.value && selectedSourceId.value), retry: false,
});
const checkpointsQuery = useQuery({
  queryKey: ['governance', 'migration', 'checkpoints', selectedBatchId],
  queryFn: () => listHistoricalMigrationCheckpoints(leaseQuery.data.value!, selectedBatchId.value),
  enabled: () => Boolean(leaseQuery.data.value && selectedBatchId.value), retry: false,
});
const mappings = computed(() => mappingsQuery.data.value ?? []);
const checkpoints = computed(() => checkpointsQuery.data.value ?? []);

const sourceForm = reactive({ sourceCode: '', displayName: '', systemType: 'EMR' });
const mappingForm = reactive({ sourceField: '', targetEntity: '', targetField: '' });
const candidateForm = reactive({ sourcePatientIdentifier: '', displayName: '', sexCode: 'M', birthDate: '' });
const importForm = reactive({ file: null as File | null });
const batchForm = reactive({ sourceSystem: '', sourceImportId: '' });
const checkpointForm = reactive({ processedRecords: 0, lastSourceKey: '' });
const archiveForm = reactive({ observationSummary: '' });
const busy = ref('');
const notice = ref('');
const dialog = ref<'' | 'source' | 'mapping' | 'candidate' | 'import' | 'batch' | 'checkpoint' | 'archive'>('');
const archiveBatch = ref<HistoricalMigrationBatchWire | null>(null);
const riskOpen = ref(false);
const riskKind = ref<'' | 'retire-source' | 'deactivate-mapping' | 'rollback-batch'>('');
const riskSource = ref<SourceSystemInventoryWire | null>(null);
const riskMapping = ref<SourceFieldMappingWire | null>(null);
const riskBatch = ref<HistoricalMigrationBatchWire | null>(null);

function statusLabel(s: string) {
  const map: Record<string, string> = { REGISTERED: '已注册', CONFIGURED: '已配置', ACTIVE: '激活', RETIRED: '已退休', TRIAL: '试迁', RECONCILED: '已对账', SWITCHED: '已切换', OBSERVING: '观察中', ARCHIVED: '已归档', ROLLED_BACK: '已回退', PENDING: '待复核', RESOLVED: '已确认' };
  return map[s] ?? s;
}

async function createSource() {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || !sourceForm.sourceCode.trim() || !sourceForm.displayName.trim()) return;
  busy.value = 'source'; notice.value = '';
  try {
    await registerSourceSystem(lease, {
      source_code: sourceForm.sourceCode.trim(), display_name: sourceForm.displayName.trim(),
      system_type: sourceForm.systemType as 'EMR', registered_at: new Date().toISOString(),
    });
    sourceForm.sourceCode = ''; sourceForm.displayName = '';
    notice.value = '源系统已注册。'; dialog.value = ''; await sourcesQuery.refetch();
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}

async function transitionSource(source: SourceSystemInventoryWire, action: 'configure' | 'activate' | 'retire') {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value) return;
  busy.value = source.source_system_id; notice.value = '';
  try {
    if (action === 'configure') await configureSourceSystem(lease, source);
    else if (action === 'activate') await activateSourceSystem(lease, source);
    else await retireSourceSystem(lease, source);
    notice.value = `源系统 ${source.display_name} 状态已推进。`; await sourcesQuery.refetch();
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}

async function createMapping() {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || !selectedSourceId.value || !mappingForm.sourceField.trim() || !mappingForm.targetEntity.trim() || !mappingForm.targetField.trim()) return;
  busy.value = 'mapping'; notice.value = '';
  try {
    await registerSourceFieldMapping(lease, {
      source_system_id: selectedSourceId.value, source_field: mappingForm.sourceField.trim(),
      target_entity: mappingForm.targetEntity.trim(), target_field: mappingForm.targetField.trim(),
      registered_at: new Date().toISOString(),
    });
    mappingForm.sourceField = ''; mappingForm.targetEntity = ''; mappingForm.targetField = '';
    notice.value = '源字段映射已登记。'; dialog.value = ''; await mappingsQuery.refetch();
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}

async function createCandidate() {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || !selectedSourceId.value || !candidateForm.sourcePatientIdentifier.trim() || !candidateForm.displayName.trim() || !candidateForm.birthDate) return;
  busy.value = 'candidate'; notice.value = '';
  try {
    await recordSourcePatientMatchCandidate(lease, {
      source_system_id: selectedSourceId.value, source_patient_identifier: candidateForm.sourcePatientIdentifier.trim(),
      display_name: candidateForm.displayName.trim(), sex_code: candidateForm.sexCode, birth_date: candidateForm.birthDate,
    });
    candidateForm.sourcePatientIdentifier = ''; candidateForm.displayName = '';
    notice.value = '迁移源患者候选已登记。'; dialog.value = ''; await candidatesQuery.refetch();
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}

function selectCsvFile(event: Event) {
  importForm.file = (event.target as HTMLInputElement).files?.[0] ?? null;
}

async function importCandidatesCsv() {
  const lease = leaseQuery.data.value;
  const file = importForm.file;
  if (!lease || busy.value || !selectedSourceId.value || !file) return;
  if (!file.name.toLowerCase().endsWith('.csv') || file.size > 1_048_576) {
    notice.value = '请选择不超过 1 MiB 的 CSV 文件。'; return;
  }
  busy.value = 'import'; notice.value = '';
  try {
    const imported = await importSourcePatientMatchCandidatesCsv(lease, {
      source_system_id: selectedSourceId.value, file_name: file.name, csv_content: await file.text(),
    });
    batchForm.sourceImportId = imported.source_import_id;
    notice.value = `已导入并固化 ${imported.row_count} 条源患者记录，可据此开始可续迁批次。`;
    importForm.file = null; dialog.value = '';
    await Promise.all([candidatesQuery.refetch(), importsQuery.refetch()]);
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}

async function createBatch() {
  const lease = leaseQuery.data.value;
  const source = sources.value.find((item) => item.source_code === batchForm.sourceSystem);
  if (!lease || busy.value || !source || selectedSourceId.value !== source.source_system_id || !batchForm.sourceImportId) return;
  const sourceImport = sourceImports.value.find((item) => item.source_import_id === batchForm.sourceImportId);
  if (!sourceImport) return;
  busy.value = 'batch'; notice.value = '';
  try {
    await startHistoricalMigrationBatch(lease, {
      source_system: source.source_code, source_import_id: sourceImport.source_import_id,
      record_count: sourceImport.row_count, started_at: new Date().toISOString(),
    });
    batchForm.sourceSystem = '';
    notice.value = `历史迁移批次已绑定受控导入 ${sourceImport.file_name}（${sourceImport.row_count} 条）并开始试迁。`; dialog.value = ''; await batchesQuery.refetch();
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}

function selectBatchSource(sourceCode: string) {
  batchForm.sourceSystem = sourceCode;
  batchForm.sourceImportId = '';
  selectedSourceId.value = sources.value.find((item) => item.source_code === sourceCode)?.source_system_id ?? '';
}

function handleBatchSourceChange(event: Event) {
  selectBatchSource((event.target as HTMLSelectElement).value);
}

async function transitionBatch(batch: HistoricalMigrationBatchWire, action: 'reconcile' | 'switch' | 'observe' | 'rollback') {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value) return;
  busy.value = batch.batch_id; notice.value = '';
  try {
    if (action === 'reconcile') await reconcileHistoricalMigrationBatch(lease, batch, 0);
    else if (action === 'switch') await switchHistoricalMigrationBatch(lease, batch);
    else if (action === 'observe') await startHistoricalMigrationObservation(lease, batch);
    else await rollbackHistoricalMigrationBatch(lease, batch);
    notice.value = `批次 ${batch.source_system} 状态已推进。`; await batchesQuery.refetch();
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}

function openArchive(batch: HistoricalMigrationBatchWire) {
  archiveBatch.value = batch;
  archiveForm.observationSummary = '';
  dialog.value = 'archive';
}

async function archiveBatchAfterObservation() {
  const lease = leaseQuery.data.value;
  const batch = archiveBatch.value;
  const summary = archiveForm.observationSummary.trim();
  if (!lease || !batch || busy.value || summary.length < 10) return;
  busy.value = batch.batch_id; notice.value = '';
  try {
    await archiveHistoricalMigrationBatch(lease, batch, summary);
    notice.value = `批次 ${batch.source_system} 的观察结论已固化，迁移证据已归档。`;
    archiveForm.observationSummary = ''; archiveBatch.value = null; dialog.value = '';
    await batchesQuery.refetch();
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}

async function createCheckpoint() {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || !selectedBatchId.value || checkpointForm.processedRecords < 0) return;
  busy.value = 'checkpoint'; notice.value = '';
  try {
    await recordHistoricalMigrationCheckpoint(lease, {
      batch_id: selectedBatchId.value, processed_records: checkpointForm.processedRecords,
      last_source_key: checkpointForm.lastSourceKey.trim() || null, checkpointed_at: new Date().toISOString(),
    });
    checkpointForm.processedRecords = 0; checkpointForm.lastSourceKey = '';
    notice.value = '断点已记录。'; dialog.value = ''; await checkpointsQuery.refetch();
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}

function exportReconciliationReport() {
  const header = ['批次ID', '源系统', '记录数', '差异数', '状态', '开始时间'];
  const rows = batches.value.map((batch) => [
    batch.batch_id, batch.source_system, batch.record_count, batch.mismatch_count,
    statusLabel(batch.batch_status), batch.started_at,
  ]);
  const csv = [header, ...rows]
    .map((row) => row.map((value) => `"${String(value ?? '').replaceAll('"', '""')}"`).join(','))
    .join('\n');
  const url = URL.createObjectURL(new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8' }));
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `历史迁移对账报告-${new Date().toISOString().slice(0, 10)}.csv`;
  anchor.click();
  URL.revokeObjectURL(url);
  notice.value = `已导出 ${rows.length} 个批次的对账报告。`;
}

function requestRiskAction(kind: 'retire-source' | 'deactivate-mapping' | 'rollback-batch', item: SourceSystemInventoryWire | SourceFieldMappingWire | HistoricalMigrationBatchWire) {
  riskKind.value = kind;
  riskSource.value = kind === 'retire-source' ? item as SourceSystemInventoryWire : null;
  riskMapping.value = kind === 'deactivate-mapping' ? item as SourceFieldMappingWire : null;
  riskBatch.value = kind === 'rollback-batch' ? item as HistoricalMigrationBatchWire : null;
  riskOpen.value = true;
}

async function confirmRiskAction() {
  if (riskKind.value === 'retire-source' && riskSource.value) await transitionSource(riskSource.value, 'retire');
  if (riskKind.value === 'rollback-batch' && riskBatch.value) await transitionBatch(riskBatch.value, 'rollback');
  if (riskKind.value === 'deactivate-mapping' && riskMapping.value && leaseQuery.data.value) {
    busy.value = riskMapping.value.mapping_id; notice.value = '';
    try { await deactivateSourceFieldMapping(leaseQuery.data.value, riskMapping.value); notice.value = '源字段映射已停用，后续批次不再使用。'; await mappingsQuery.refetch(); }
    catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
    finally { busy.value = ''; }
  }
  riskOpen.value = false;
}

const riskTitle = computed(() => riskKind.value === 'retire-source' ? '退休迁移源系统' : riskKind.value === 'rollback-batch' ? '回退迁移批次' : '停用源字段映射');
const riskDescription = computed(() => riskKind.value === 'retire-source'
  ? '退休后不能再建立新映射、患者候选或迁移批次；既有证据保持只读。'
  : riskKind.value === 'rollback-batch'
    ? '回退会阻止该批次继续切换，已产生的迁移证据和差异对账结果仍保留。'
    : '停用后新的集成转换和迁移批次不再使用该映射；历史转换结果不会被覆盖。');
</script>

<template>
  <section data-page-root class="content admin-content vue-native-page migration-page">
    <div class="page-heading admin-heading">
      <div><p class="eyebrow">数据中心 / 历史迁移</p><h1>历史数据迁移与上线切换</h1><p>源系统盘点、字段映射、患者匹配与断点重跑；切换须对账一致，进度单调可续迁。</p></div><div class="toolbar-actions migration-head-actions"><button class="button secondary" :disabled="batches.length === 0" @click="exportReconciliationReport">导出对账报告</button><button class="button secondary" @click="batchesQuery.refetch()">刷新</button><button class="button primary" @click="dialog = 'batch'">开始迁移批次</button></div>
    </div>

    <ClinicalPageState v-if="leaseQuery.isPending.value || sourcesQuery.isPending.value" kind="loading" message="正在读取迁移上下文" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="sourcesQuery.refetch()" />
    <template v-else>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>

      <section class="card migration-overview" aria-label="迁移控制台总览">
        <div class="card-head">迁移控制台 <span class="sub">按原型八阶段执行，生产切换受零差异门禁控制</span></div>
        <div class="card-body">
          <ol class="migration-stepper">
            <li v-for="(step, index) in migrationSteps" :key="step" :class="{ done: index + 1 < currentStep, active: index + 1 === currentStep }"><span>{{ index + 1 }}</span><b>{{ step }}</b></li>
          </ol>
        </div>
      </section>

      <section class="admin-metrics" aria-label="迁移批次指标">
        <article><span>源系统</span><strong>{{ sources.length }}</strong><small>{{ sources.filter((source) => source.connection_status === 'ACTIVE').length }} 个已激活</small></article>
        <article><span>迁移记录</span><strong>{{ totalRecords.toLocaleString('zh-CN') }}</strong><small>{{ batches.length }} 个有效批次</small></article>
        <article><span>已完成对账</span><strong>{{ reconciledCount }}</strong><small>含已切换批次</small></article>
        <article><span>待处理差异</span><strong>{{ totalMismatches }}</strong><small>切换前必须清零</small></article>
      </section>

      <section class="migration-operations">
        <div class="admin-panel migration-reconciliation">
          <header><div><h2>对象对账</h2><p>实时汇总有效迁移批次，点击批次可进入断点记录。</p></div></header>
          <div v-if="batches.length === 0" class="admin-empty">暂无有效迁移批次。</div>
          <div v-else class="admin-table-wrap"><table class="admin-table"><thead><tr><th>源系统</th><th>记录</th><th>差异</th><th>状态</th></tr></thead><tbody>
            <tr v-for="batch in batches.slice(0, 5)" :key="`overview-${batch.batch_id}`"><td><button class="link-button" @click="selectedBatchId = batch.batch_id">{{ batch.source_system }}</button></td><td>{{ batch.record_count.toLocaleString('zh-CN') }}</td><td>{{ batch.mismatch_count }}</td><td><span class="admin-status" :class="batch.batch_status.toLowerCase()">{{ statusLabel(batch.batch_status) }}</span></td></tr>
          </tbody></table></div>
        </div>
        <aside class="admin-panel migration-differences">
          <header><div><h2>差异队列</h2><p>有差异的批次不能执行生产切换。</p></div></header>
          <div v-if="differenceQueue.length === 0" class="admin-empty migration-clean">当前有效批次无待处理差异。</div>
          <button v-for="batch in differenceQueue.slice(0, 4)" :key="`difference-${batch.batch_id}`" class="difference-row" @click="selectedBatchId = batch.batch_id"><span>{{ batch.source_system }}</span><strong>{{ batch.mismatch_count }} 项</strong></button>
          <button class="button secondary export-button" :disabled="batches.length === 0" @click="exportReconciliationReport">导出对账报告</button>
        </aside>
      </section>

      <section class="admin-panel">
        <header><div><h2>源系统盘点</h2><p>编码唯一，生命周期 已注册→已配置→激活→退休。</p></div><button class="button primary" @click="dialog = 'source'">注册源系统</button></header>
        <div v-if="sources.length === 0" class="admin-empty">暂无源系统。</div>
        <div v-else class="admin-table-wrap"><table class="admin-table"><thead><tr><th>编码 / 名称</th><th>类型</th><th>状态</th><th>操作</th></tr></thead><tbody>
          <tr v-for="source in sources" :key="source.source_system_id">
            <td><button class="link-button" @click="selectedSourceId = source.source_system_id"><strong>{{ source.display_name }}</strong><small><code>{{ source.source_code }}</code></small></button></td>
            <td>{{ source.system_type }}</td>
            <td><span class="admin-status" :class="source.connection_status.toLowerCase()">{{ statusLabel(source.connection_status) }}</span></td>
            <td>
              <button v-if="source.connection_status === 'REGISTERED'" class="task-action" :disabled="Boolean(busy)" @click="transitionSource(source, 'configure')">配置</button>
              <button v-else-if="source.connection_status === 'CONFIGURED'" class="task-action" :disabled="Boolean(busy)" @click="transitionSource(source, 'activate')">激活</button>
              <button v-else-if="source.connection_status === 'ACTIVE'" class="task-action danger" :disabled="Boolean(busy)" @click="requestRiskAction('retire-source', source)">退休</button>
              <span v-else>—</span>
            </td>
          </tr>
        </tbody></table></div>
      </section>

      <section class="admin-panel" v-if="selectedSourceId">
        <header><div><h2>源字段映射</h2><p>仅已配置/激活源可登记，映射唯一。</p></div><button class="button primary" @click="dialog = 'mapping'">新建映射</button></header>
        <div v-if="mappings.length === 0" class="admin-empty">该源暂无字段映射。</div>
        <div v-else class="admin-table-wrap"><table class="admin-table"><thead><tr><th>源字段</th><th>目标实体</th><th>目标字段</th><th>状态</th><th>操作</th></tr></thead><tbody>
          <tr v-for="mapping in mappings" :key="mapping.mapping_id">
            <td><code>{{ mapping.source_field }}</code></td><td>{{ mapping.target_entity }}</td><td><code>{{ mapping.target_field }}</code></td>
            <td><span class="admin-status" :class="mapping.status.toLowerCase()">{{ mapping.status === 'ACTIVE' ? '有效' : '已停用' }}</span></td>
            <td><button class="task-action" :disabled="mapping.status !== 'ACTIVE' || Boolean(busy)" @click="requestRiskAction('deactivate-mapping', mapping)">停用</button></td>
          </tr>
        </tbody></table></div>

        <header style="margin-top:16px"><div><h2>迁移源患者匹配</h2><p>CSV 受控导入会固化文件哈希、行数与操作者；单条登记仅兼容既有存量流程。</p></div><div class="toolbar-actions"><button class="button secondary" @click="dialog = 'candidate'">登记单条候选</button><button class="button primary" @click="dialog = 'import'">导入 CSV</button></div></header>
        <div v-if="sourceImports.length" class="migration-import-list" aria-label="受控源数据导入批次"><span v-for="sourceImport in sourceImports.slice(0, 3)" :key="sourceImport.source_import_id">{{ sourceImport.file_name }} · {{ sourceImport.row_count }} 条 · <code>…{{ sourceImport.content_hash.slice(-10) }}</code></span></div>
        <div v-if="candidates.length === 0" class="admin-empty">暂无患者匹配候选。</div>
        <div v-else class="admin-table-wrap"><table class="admin-table"><thead><tr><th>源标识</th><th>姓名</th><th>匹配分</th><th>复核状态</th><th>操作</th></tr></thead><tbody>
          <tr v-for="candidate in candidates" :key="candidate.candidate_id">
            <td><code>{{ candidate.source_patient_identifier }}</code></td>
            <td>{{ candidate.display_name }}</td>
            <td>{{ candidate.match_score }}</td>
            <td><span class="admin-status" :class="candidate.review_status.toLowerCase()">{{ statusLabel(candidate.review_status) }}</span></td>
            <td><button v-if="candidate.review_status === 'PENDING' && candidate.matched_patient_id" class="task-action" :disabled="Boolean(busy)" @click="async () => { busy = candidate.candidate_id; try { await resolveSourcePatientMatchCandidate(leaseQuery.data.value!, candidate, candidate.matched_patient_id ?? null); await candidatesQuery.refetch(); } catch (error) { notice = `${toClinicalIssue(error).code}：${toClinicalIssue(error).message}`; } finally { busy = ''; } }">确认匹配</button><small v-else-if="candidate.review_status === 'PENDING'">需主索引人工选择</small><span v-else>—</span></td>
          </tr>
        </tbody></table></div>
      </section>

      <section class="admin-panel">
        <header><div><h2>历史迁移批次</h2><p>试迁→对账→切换→观察→归档；切换须差异数为 0，观察结论会与批次证据一同固化。</p></div><button class="button primary" @click="dialog = 'batch'">开始试迁</button></header>
        <div v-if="batches.length === 0" class="admin-empty">暂无迁移批次。</div>
        <div v-else class="admin-table-wrap"><table class="admin-table"><thead><tr><th>源系统 / 受控输入</th><th>记录数</th><th>差异数</th><th>状态</th><th>操作</th></tr></thead><tbody>
          <tr v-for="batch in batches" :key="batch.batch_id">
            <td><button class="link-button" @click="selectedBatchId = batch.batch_id"><strong>{{ batch.source_system }}</strong><small>{{ batch.source_import_id ? `导入 …${batch.source_import_id.slice(-8)}` : `兼容批次 …${batch.batch_id.slice(-8)}` }}</small></button></td>
            <td>{{ batch.record_count }}</td><td>{{ batch.mismatch_count }}</td>
            <td><span class="admin-status" :class="batch.batch_status.toLowerCase()">{{ statusLabel(batch.batch_status) }}</span></td>
            <td>
              <button v-if="batch.batch_status === 'TRIAL'" class="task-action" :disabled="Boolean(busy)" @click="transitionBatch(batch, 'reconcile')">对账</button>
              <button v-else-if="batch.batch_status === 'RECONCILED'" class="task-action" :disabled="Boolean(busy)" @click="transitionBatch(batch, 'switch')">切换</button>
              <button v-else-if="batch.batch_status === 'SWITCHED'" class="task-action" :disabled="Boolean(busy)" @click="transitionBatch(batch, 'observe')">开始观察</button>
              <button v-else-if="batch.batch_status === 'OBSERVING'" class="task-action" :disabled="Boolean(busy)" @click="openArchive(batch)">归档观察结论</button>
              <button v-if="batch.batch_status === 'RECONCILED'" class="task-action danger" :disabled="Boolean(busy)" @click="requestRiskAction('rollback-batch', batch)">回退</button>
              <span v-else>—</span>
            </td>
          </tr>
        </tbody></table></div>
      </section>

      <section class="admin-panel" v-if="selectedBatchId">
        <header><div><h2>断点重跑</h2><p>进度单调递增，可续迁批次记录检查点。</p></div><button class="button primary" @click="dialog = 'checkpoint'">记录检查点</button></header>
        <div v-if="checkpoints.length === 0" class="admin-empty">该批次暂无检查点。</div>
        <div v-else class="admin-table-wrap"><table class="admin-table"><thead><tr><th>已处理记录数</th><th>最后源键</th><th>记录时间</th></tr></thead><tbody>
          <tr v-for="checkpoint in checkpoints" :key="checkpoint.checkpoint_id">
            <td>{{ checkpoint.processed_records }}</td><td><code>{{ checkpoint.last_source_key ?? '—' }}</code></td>
            <td>{{ new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(checkpoint.checkpointed_at)) }}</td>
          </tr>
        </tbody></table></div>
      </section>
    </template>

    <AdminActionDialog :open="dialog === 'source'" title="注册迁移源系统" description="源系统身份编码创建后不可覆盖修改；后续配置、激活和退休会直接控制迁移流程。" @update:open="dialog = $event ? 'source' : ''"><form class="admin-form" @submit.prevent="createSource"><label><span>源编码</span><input v-model="sourceForm.sourceCode" maxlength="96" required /></label><label><span>显示名</span><input v-model="sourceForm.displayName" maxlength="256" required /></label><label><span>系统类型</span><select v-model="sourceForm.systemType"><option>EMR</option><option>LIS</option><option>PACS</option><option>PHARMACY</option><option>BILLING</option><option>OTHER</option></select></label></form><template #footer="{ close }"><button class="button secondary" @click="close">取消</button><button class="button primary" @click="createSource">注册</button></template></AdminActionDialog>
    <AdminActionDialog :open="dialog === 'mapping'" title="新建源字段映射" description="映射会参与后续迁移转换；已登记的身份字段不可覆盖修改。" @update:open="dialog = $event ? 'mapping' : ''"><form class="admin-form" @submit.prevent="createMapping"><label><span>源字段</span><input v-model="mappingForm.sourceField" required /></label><label><span>目标实体</span><input v-model="mappingForm.targetEntity" required /></label><label><span>目标字段</span><input v-model="mappingForm.targetField" required /></label></form><template #footer="{ close }"><button class="button secondary" @click="close">取消</button><button class="button primary" @click="createMapping">登记</button></template></AdminActionDialog>
    <AdminActionDialog :open="dialog === 'candidate'" title="登记迁移源患者候选" description="候选会进入确定性匹配与人工复核队列，不会直接合并患者主索引。" @update:open="dialog = $event ? 'candidate' : ''"><form class="admin-form" @submit.prevent="createCandidate"><label><span>源患者标识</span><input v-model="candidateForm.sourcePatientIdentifier" required /></label><label><span>姓名</span><input v-model="candidateForm.displayName" required /></label><label><span>性别</span><select v-model="candidateForm.sexCode"><option value="M">男</option><option value="F">女</option></select></label><label><span>出生日期</span><input v-model="candidateForm.birthDate" type="date" required /></label></form><template #footer="{ close }"><button class="button secondary" @click="close">取消</button><button class="button primary" @click="createCandidate">登记</button></template></AdminActionDialog>
    <AdminActionDialog :open="dialog === 'import'" title="导入受控迁移 CSV" description="CSV 仅接受 source_patient_identifier、display_name、sex_code、birth_date 四列。导入后保存不可变哈希和行数，供试迁批次固定引用。" @update:open="dialog = $event ? 'import' : ''"><form class="admin-form" @submit.prevent="importCandidatesCsv"><label><span>CSV 文件（最多 1 MiB / 1,000 条）</span><input type="file" accept=".csv,text/csv" required @change="selectCsvFile" /></label><small v-if="importForm.file">已选：{{ importForm.file.name }}（{{ importForm.file.size.toLocaleString('zh-CN') }} bytes）</small></form><template #footer="{ close }"><button class="button secondary" @click="close">取消</button><button class="button primary" :disabled="!importForm.file || Boolean(busy)" @click="importCandidatesCsv">导入并固化</button></template></AdminActionDialog>
    <AdminActionDialog :open="dialog === 'batch'" title="开始历史迁移批次" description="试迁必须绑定一个已固化的 CSV 导入。后续新增候选不会改变该批次的对账输入；对账会按有效映射和人工复核结果逐条生成不可变证据。" @update:open="dialog = $event ? 'batch' : ''"><form class="admin-form" @submit.prevent="createBatch"><label><span>源系统</span><select :value="batchForm.sourceSystem" required @change="handleBatchSourceChange"><option value="" disabled>请选择已激活源系统</option><option v-for="source in sources.filter((item) => item.connection_status === 'ACTIVE')" :key="source.source_system_id" :value="source.source_code">{{ source.display_name }} · {{ source.source_code }}</option></select></label><label><span>受控 CSV 导入</span><select v-model="batchForm.sourceImportId" :disabled="!batchForm.sourceSystem" required><option value="" disabled>请选择已固化的 CSV 导入</option><option v-for="sourceImport in sourceImports" :key="sourceImport.source_import_id" :value="sourceImport.source_import_id">{{ sourceImport.file_name }} · {{ sourceImport.row_count }} 条 · …{{ sourceImport.content_hash.slice(-8) }}</option></select></label><div class="migration-server-count"><span>固定导入记录</span><strong>{{ sourceImports.find((item) => item.source_import_id === batchForm.sourceImportId)?.row_count ?? 0 }}</strong><small v-if="batchForm.sourceSystem && sourceImports.length === 0">请先为该源导入受控 CSV</small></div></form><template #footer="{ close }"><button class="button secondary" @click="close">取消</button><button class="button primary" :disabled="!batchForm.sourceImportId || Boolean(busy)" @click="createBatch">开始试迁</button></template></AdminActionDialog>
    <AdminActionDialog :open="dialog === 'checkpoint'" title="记录迁移检查点" description="检查点进度必须单调递增，用于失败恢复与断点续迁。" @update:open="dialog = $event ? 'checkpoint' : ''"><form class="admin-form" @submit.prevent="createCheckpoint"><label><span>已处理记录数</span><input v-model.number="checkpointForm.processedRecords" type="number" min="0" required /></label><label><span>最后源键（可选）</span><input v-model="checkpointForm.lastSourceKey" /></label></form><template #footer="{ close }"><button class="button secondary" @click="close">取消</button><button class="button primary" @click="createCheckpoint">记录检查点</button></template></AdminActionDialog>
    <AdminActionDialog :open="dialog === 'archive'" title="归档迁移观察结论" description="归档会关闭观察期并固化本次结论、操作者、时间与审计证据；提交后不可修改。" @update:open="dialog = $event ? 'archive' : ''"><form class="admin-form" @submit.prevent="archiveBatchAfterObservation"><label><span>观察结论</span><textarea v-model="archiveForm.observationSummary" minlength="10" maxlength="1000" required placeholder="说明观察期间的核验结果、已处理问题或未发现异常。" /></label><small>至少 10 个字符；请勿填写患者身份信息或临床事实正文。</small></form><template #footer="{ close }"><button class="button secondary" @click="close">取消</button><button class="button primary" :disabled="archiveForm.observationSummary.trim().length < 10 || Boolean(busy)" @click="archiveBatchAfterObservation">固化并归档</button></template></AdminActionDialog>
    <AdminConfirmDialog v-model:open="riskOpen" :title="riskTitle" :description="riskDescription" confirm-label="确认执行" :busy="Boolean(busy)" @confirm="confirmRiskAction" />
  </section>
</template>

<style scoped>
.migration-page { display: grid; align-content: start; gap: 14px; }
.migration-page > .page-heading,
.migration-page > .admin-metrics,
.migration-page > .admin-notice,
.migration-page > .admin-panel { margin: 0; }
.migration-head-actions { gap: 10px; }
.migration-overview { margin: 0; }
.migration-stepper { display: grid; grid-template-columns: repeat(8, minmax(0, 1fr)); gap: 8px; margin: 0; padding: 0; list-style: none; }
.migration-stepper li { position: relative; display: grid; justify-items: center; gap: 6px; min-width: 0; color: var(--muted); font-size: 11px; text-align: center; }
.migration-stepper li::after { content: ''; position: absolute; top: 13px; left: calc(50% + 17px); right: calc(-50% + 17px); height: 2px; background: var(--line); }
.migration-stepper li:last-child::after { display: none; }
.migration-stepper span { position: relative; z-index: 1; display: grid; place-items: center; width: 28px; height: 28px; border: 1px solid var(--line); border-radius: 50%; background: var(--card); }
.migration-stepper li.done span, .migration-stepper li.active span { border-color: var(--blue); background: var(--blue); color: white; }
.migration-stepper li.done::after { background: var(--blue); }
.migration-stepper li.active b { color: var(--blue); }
.migration-operations { display: grid; grid-template-columns: minmax(0, 1.7fr) minmax(260px, .8fr); gap: 14px; }
.migration-reconciliation, .migration-differences { margin: 0; }
.migration-differences { align-content: start; }
.difference-row { display: flex; width: calc(100% - 32px); align-items: center; justify-content: space-between; gap: 12px; margin: 8px 16px 0; padding: 10px 12px; border: 1px solid var(--line); border-radius: 8px; background: var(--card); color: inherit; cursor: pointer; }
.difference-row strong { color: var(--red); }
.migration-clean { color: var(--green); }
.export-button { width: calc(100% - 32px); margin: 12px 16px 16px; }
.migration-server-count { display: grid; grid-template-columns: 1fr auto; align-items: center; gap: 6px 14px; padding: 12px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface-soft); }
.migration-server-count strong { font-size: 20px; color: var(--blue); }
.migration-server-count small { grid-column: 1 / -1; color: var(--red); }
.migration-import-list { display: flex; flex-wrap: wrap; gap: 8px; margin: 14px 16px 12px; color: var(--muted); font-size: 12px; }
.migration-import-list span { padding: 6px 8px; border: 1px solid var(--line); border-radius: 6px; background: var(--surface-soft); }
@media (max-width: 900px) { .migration-operations { grid-template-columns: minmax(0, 1fr); } .migration-stepper { grid-template-columns: repeat(4, minmax(0, 1fr)); row-gap: 16px; } .migration-stepper li:nth-child(4)::after { display: none; } }
@media (max-width: 600px) {
  .migration-page > .page-heading { min-width: 0; }
  .migration-page > .page-heading > div { min-width: 0; }
  .migration-page > .page-heading .toolbar-actions { display: grid; grid-template-columns: minmax(0, 1fr); width: 100%; }
  .migration-stepper { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .migration-stepper li:nth-child(even)::after { display: none; }
  .migration-head-actions { align-items: stretch; }
  .migration-head-actions .button { width: 100%; }
}
</style>
