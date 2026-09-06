<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref, watch } from 'vue';

import type { ConfigurationItemWire, ConfigurationLifecycleRequestWire } from '../../generated/contracts';
import {
  defineConfiguration,
  issueConfigurationLease,
  listConfigurations,
  transitionConfiguration,
  updateConfiguration,
} from '../../api/config';
import type { ConfigurationFieldDefinition, ConfigurationStudioDefinition } from '../configuration-studios';
import { adminCodeLabel, adminValueLabel } from '../admin-display';
import { toClinicalIssue } from '../clinical-error';
import AdminEditorSurface from './AdminEditorSurface.vue';
import AdminConfirmDialog from './AdminConfirmDialog.vue';

const props = defineProps<{ definition: ConfigurationStudioDefinition }>();
const leaseQuery = useQuery({
  queryKey: ['config', 'lease'],
  queryFn: () => issueConfigurationLease(),
  retry: false,
  staleTime: 5 * 60_000,
  gcTime: 0,
});
const itemsQuery = useQuery({
  queryKey: ['config', 'items', props.definition.configType],
  queryFn: () => listConfigurations(leaseQuery.data.value!, props.definition.configType),
  enabled: () => Boolean(leaseQuery.data.value),
  retry: false,
});
const issue = computed(() => (leaseQuery.error.value ?? itemsQuery.error.value)
  ? toClinicalIssue(leaseQuery.error.value ?? itemsQuery.error.value)
  : null);
const items = computed(() => itemsQuery.data.value ?? []);
const adminKeyword = ref('');
const adminScopeStatus = ref('ALL');
const adminSort = ref<'RISK' | 'RECENT' | 'NAME'>('RISK');
const displayItems = computed(() => {
  let result = props.definition.routeId === 'admin-parameters'
    ? items.value.filter((item) => !/^(?:syn-)?auth-/.test(item.config_key)
      && !/^(?:syn-)?admin-session-v1$/.test(item.config_key))
    : [...items.value];
  if (isAdministrationView.value && adminKeyword.value.trim()) {
    const needle = adminKeyword.value.trim().toLocaleLowerCase();
    result = result.filter((item) => `${item.config_key} ${item.display_name}`.toLocaleLowerCase().includes(needle));
  }
  if (props.definition.routeId === 'admin-parameters' && adminScopeStatus.value !== 'ALL') {
    result = result.filter((item) => payloadText(item, 'scope') === adminScopeStatus.value);
  }
  if (props.definition.routeId === 'admin-jobs' && adminScopeStatus.value !== 'ALL') {
    result = result.filter((item) => item.status === adminScopeStatus.value);
  }
  return result.sort((left, right) => adminSort.value === 'NAME'
    ? left.display_name.localeCompare(right.display_name, 'zh-CN')
    : adminSort.value === 'RECENT'
      ? new Date(right.updated_at ?? 0).getTime() - new Date(left.updated_at ?? 0).getTime()
      : itemRisk(right) - itemRisk(left));
});
const selected = ref<ConfigurationItemWire | null>(null);
const form = reactive({ name: '', key: '', description: '' });
const values = reactive<Record<string, string>>(Object.fromEntries(
  props.definition.fields.map((item) => [item.key, item.defaultValue]),
));
const reason = ref('完成配置生命周期操作');
const busy = ref('');
const notice = ref('');
const showEditor = ref(false);
const archiveConfirmOpen = ref(false);
const showAdministrationAnalysis = ref(false);
const administrationAnalysisMode = ref<'PRIMARY' | 'CHANNELS'>('PRIMARY');
const isAdministrationView = computed(() => ['admin-master-data', 'admin-parameters', 'admin-jobs'].includes(props.definition.routeId));
const isMedicalAiView = computed(() => ['agent-compose', 'agent-context', 'agent-evals'].includes(props.definition.routeId));
const isManagedLedger = computed(() => ['agent-compose', 'agent-context', 'config-release', 'config-upgrade']
  .includes(props.definition.routeId));
const isReleaseUpgradeLedger = computed(() => ['config-release', 'config-upgrade'].includes(props.definition.routeId));
const isAiCenterConfiguration = computed(() => ['agent-evals'].includes(props.definition.routeId));
const isModalEditor = computed(() => isAdministrationView.value || isAiCenterConfiguration.value);
const adminVariant = computed(() => props.definition.routeId.replace('admin-', ''));
const currentItem = computed(() => selected.value && displayItems.value.some((item) => item.config_id === selected.value?.config_id)
  ? selected.value : displayItems.value[0] ?? null);
const masterDataDomains = computed(() => displayItems.value.filter((item) => item.config_key !== 'hospital-master-data-v1'));
const adminCreateLabel = computed(() => ({
  'admin-master-data': '新建主数据变更', 'admin-parameters': '创建参数变更', 'admin-jobs': '新建批量任务',
} as Record<string, string>)[props.definition.routeId] ?? '新建草稿');
const adminSecondaryLabel = computed(() => ({
  'admin-master-data': '同步对账', 'admin-parameters': '作用域差异', 'admin-jobs': '调度策略',
} as Record<string, string>)[props.definition.routeId] ?? '刷新');
const administrationAnalysisTitle = computed(() => administrationAnalysisMode.value === 'CHANNELS' ? '通知渠道' : ({
  'admin-master-data': '同步对账结果', 'admin-parameters': '作用域差异', 'admin-jobs': '调度策略',
} as Record<string, string>)[props.definition.routeId] ?? '配置分析');
const administrationAnalysisRows = computed(() => displayItems.value.map((item) => ({
  key: item.config_key,
  name: item.display_name,
  left: administrationAnalysisMode.value === 'CHANNELS' ? payloadDisplay(item, 'notification_channels', '未配置') : adminVariant.value === 'master-data' ? payloadDisplay(item, 'authoritative_source', payloadDisplay(item, 'code_system')) : adminVariant.value === 'parameters' ? payloadDisplay(item, 'scope') : payloadDisplay(item, 'schedule'),
  right: administrationAnalysisMode.value === 'CHANNELS' ? payloadDisplay(item, 'channel_owner', '未指定责任人') : adminVariant.value === 'master-data' ? validationLabel[item.validation_state] : adminVariant.value === 'parameters' ? payloadDisplay(item, 'inheritance') : payloadDisplay(item, 'retry_policy'),
  state: item.validation_state === 'INVALID' ? '需处理' : item.status === 'ACTIVE' ? '已生效' : statusLabel[item.status],
})));
const administrationAnalysisHeaders = computed(() => administrationAnalysisMode.value === 'CHANNELS'
  ? ['通知渠道', '渠道责任人']
  : adminVariant.value === 'master-data' ? ['权威来源', '对账校验']
    : adminVariant.value === 'parameters' ? ['作用域', '继承链'] : ['调度', '失败重试']);

const statusLabel: Readonly<Record<string, string>> = Object.freeze({
  DRAFT: '草稿', PENDING_APPROVAL: '待审批', APPROVED: '已批准', ACTIVE: '已发布', ARCHIVED: '已归档',
});
const validationLabel: Readonly<Record<string, string>> = Object.freeze({
  NOT_VALIDATED: '未校验', VALID: '校验通过', INVALID: '校验失败',
});
const approvalLabel: Readonly<Record<string, string>> = Object.freeze({
  NOT_REQUIRED: '暂无审批', PENDING: '待审批', APPROVED: '已批准', REJECTED: '已驳回',
});
const lifecycleActionLabel: Readonly<Record<string, string>> = Object.freeze({
  VALIDATE: '静态校验', SUBMIT: '提交审批', APPROVE: '职责分离批准',
  PUBLISH: '发布', ROLLBACK: '回退', ARCHIVE: '归档停用',
});

const previewEntries = computed(() => props.definition.fields.map((field) => ({
  label: field.label,
  values: previewValues(field, selected.value?.payload?.[field.key] ?? parseValue(field, values[field.key] ?? '')),
})));

function parseValue(field: ConfigurationFieldDefinition, raw: string): unknown {
  if (field.kind === 'list') return raw.split(/[,\n，]+/).map((item) => item.trim()).filter(Boolean);
  if (field.kind === 'number') return Number(raw);
  if (field.kind === 'boolean') return raw === 'true';
  return raw.trim();
}

function previewValues(field: ConfigurationFieldDefinition, value: unknown): string[] {
  if (Array.isArray(value)) return value.map(adminValueLabel);
  if (field.kind === 'textarea') return String(value ?? '').split(/[;；\n]+/).map((item) => item.trim()).filter(Boolean);
  return [adminValueLabel(value)];
}

function payload(): Record<string, unknown> {
  return {
    schema_version: 1,
    description: form.description.trim(),
    ...Object.fromEntries(props.definition.fields.map((field) => [field.key, parseValue(field, values[field.key] ?? '')])),
  };
}

function selectItem(item: ConfigurationItemWire) {
  selected.value = item;
  form.name = item.display_name;
  form.key = item.config_key;
  form.description = String(item.payload?.description ?? '');
  for (const field of props.definition.fields) {
    const value = item.payload?.[field.key];
    values[field.key] = Array.isArray(value) ? value.join(', ') : String(value ?? field.defaultValue);
  }
  notice.value = '';
}

function openItemEditor(item: ConfigurationItemWire) {
  selectItem(item);
  showEditor.value = true;
}

function requestArchive(item: ConfigurationItemWire) {
  selectItem(item);
  archiveConfirmOpen.value = true;
}

watch(displayItems, (nextItems) => {
  if (!selected.value && nextItems[0]) selectItem(nextItems[0]);
}, { immediate: true });

function resetDraft() {
  selected.value = null;
  form.name = '';
  form.key = '';
  form.description = '';
  for (const field of props.definition.fields) values[field.key] = field.defaultValue;
  notice.value = '';
  showEditor.value = true;
}

function openAdministrationAnalysis(mode: 'PRIMARY' | 'CHANNELS') {
  administrationAnalysisMode.value = mode;
  showAdministrationAnalysis.value = true;
}

function payloadText(item: ConfigurationItemWire, key: string, fallback = '—') {
  const value = item.payload?.[key];
  if (Array.isArray(value)) return value.join('、');
  if (typeof value === 'boolean') return value ? '是' : '否';
  return value == null || value === '' ? fallback : String(value);
}

function payloadDisplay(item: ConfigurationItemWire, key: string, fallback = '—') {
  const value = item.payload?.[key];
  return value == null || value === '' || (Array.isArray(value) && !value.length) ? fallback : adminValueLabel(value);
}

function parameterRisk(item: ConfigurationItemWire) {
  if (/retention|archive/i.test(item.config_key)) return '保护';
  if (/session|secret|password|auth|credential/i.test(item.config_key)) return '高';
  return '中';
}

function itemRisk(item: ConfigurationItemWire) {
  if (item.validation_state === 'INVALID') return 100;
  if (item.status !== 'ACTIVE') return 50;
  return parameterRisk(item) === '保护' ? 30 : parameterRisk(item) === '高' ? 20 : 10;
}

async function save() {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || !form.name.trim() || !form.key.trim()) return;
  busy.value = 'save'; notice.value = '';
  try {
    const result = selected.value
      ? await updateConfiguration(lease, selected.value.config_id, {
          display_name: form.name.trim(), payload: payload(), expected_version: selected.value.row_version,
        })
      : await defineConfiguration(lease, {
          config_type: props.definition.configType,
          config_key: form.key.trim(),
          display_name: form.name.trim(),
          payload: payload(),
        });
    selectItem(result);
    notice.value = selected.value?.row_version === 1 ? '已创建版本化草稿。' : '草稿已保存，校验状态已重置。';
    await itemsQuery.refetch();
    if (isModalEditor.value) showEditor.value = false;
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = ''; }
}

async function lifecycle(action: ConfigurationLifecycleRequestWire['action'], confirmed = false) {
  if (action === 'ARCHIVE' && !confirmed) { archiveConfirmOpen.value = true; return; }
  const lease = leaseQuery.data.value;
  const item = selected.value;
  if (!lease || !item || busy.value) return;
  busy.value = action; notice.value = '';
  try {
    const result = await transitionConfiguration(lease, item.config_id, {
      action, expected_version: item.row_version, reason: reason.value.trim(),
    });
    selectItem(result);
    notice.value = `${lifecycleActionLabel[action] ?? action}已完成，当前版本 v${result.row_version}。`;
    if (action === 'ARCHIVE') archiveConfirmOpen.value = false;
    await itemsQuery.refetch();
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = ''; }
}

function formatDate(value: string | null | undefined) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—';
}

function downloadJobDifference() {
  const item = currentItem.value;
  if (!item || adminVariant.value !== 'jobs') return;
  const evidence = {
    exported_at: new Date().toISOString(),
    config_id: item.config_id,
    task_code: item.config_key,
    task_name: item.display_name,
    status: statusLabel[item.status] ?? '未知状态',
    validation: validationLabel[item.validation_state] ?? '未知校验状态',
    reconciliation_rule: item.payload?.reconciliation_rule ?? null,
    retry_policy: item.payload?.retry_policy ?? null,
    notification_channels: item.payload?.notification_channels ?? [],
    database_version: item.row_version,
  };
  const href = URL.createObjectURL(new Blob([JSON.stringify(evidence, null, 2)], { type: 'application/json;charset=utf-8' }));
  const anchor = document.createElement('a');
  anchor.href = href;
  anchor.download = `${item.config_key}-差异与修正清单.json`;
  anchor.click();
  URL.revokeObjectURL(href);
  notice.value = '已导出当前数据库版本的差异、对账与失败重试信息。';
}
</script>

<template>
  <section data-page-root class="content vue-native-page config-studio">
    <div class="page-head">
      <div class="page-title"><p class="eyebrow">配置生命周期 / 结构化领域工作台</p><h1>{{ definition.title }}</h1><p>{{ definition.subtitle }}</p></div>
      <div class="head-actions">
        <button v-if="!isAdministrationView" class="btn" type="button" @click="itemsQuery.refetch()">刷新</button>
        <button v-else-if="adminVariant === 'jobs'" class="btn" type="button" @click="openAdministrationAnalysis('CHANNELS')">通知渠道</button>
        <button v-if="isAdministrationView" class="btn" type="button" @click="openAdministrationAnalysis('PRIMARY')">{{ adminSecondaryLabel }}</button>
        <button class="btn primary" type="button" @click="resetDraft">{{ isAdministrationView ? adminCreateLabel : '新建草稿' }}</button>
      </div>
    </div>

    <div v-if="!isModalEditor || showEditor" class="inline-notice config-safety" role="note">
      <strong>{{ isManagedLedger ? '受管台账' : isMedicalAiView ? '发布校验' : '安全契约' }}</strong><span>{{ definition.safetyNote }}</span>
    </div>
    <div v-if="leaseQuery.isPending.value || itemsQuery.isPending.value" class="card"><div class="card-body">正在读取配置版本…</div></div>
    <div v-else-if="issue" class="card"><div class="card-body">加载失败：{{ issue.code }} {{ issue.message }}</div></div>
    <template v-else>
      <div v-if="notice" class="inline-notice" role="status">{{ notice }}</div>
      <section v-if="isAdministrationView && showAdministrationAnalysis" class="admin-panel admin-analysis-panel"><header><div><h2>{{ administrationAnalysisTitle }}</h2><p>结果从当前数据库配置版本、作用域、校验状态和调度字段实时解析。</p></div><button class="task-action" type="button" @click="showAdministrationAnalysis = false">关闭</button></header><div class="admin-table-wrap"><table class="table"><thead><tr><th>配置</th><th>{{ administrationAnalysisHeaders[0] }}</th><th>{{ administrationAnalysisHeaders[1] }}</th><th>结论</th></tr></thead><tbody><tr v-for="row in administrationAnalysisRows" :key="row.key"><td><b>{{ row.name }}</b><br><span class="meta">{{ row.key }}</span></td><td>{{ row.left }}</td><td>{{ row.right }}</td><td><span class="status" :class="row.state === '已生效' ? 'green' : row.state === '需处理' ? 'red' : 'amber'">{{ row.state }}</span></td></tr></tbody></table></div></section>
      <section v-if="isAiCenterConfiguration" class="card ai-center-config-ledger">
        <div class="card-head"><div><h2>版本台账</h2><p>{{ definition.title }} · 配置变更会直接影响后续医助任务</p></div><span class="status">{{ displayItems.length }} 项</span></div>
        <div v-if="displayItems.length === 0" class="empty-state"><span>配</span><p>暂无配置草稿</p><small>点击“新建草稿”开始配置</small></div>
        <div v-else class="table-wrap"><table class="table"><thead><tr><th>名称 / 编码</th><th>生命周期</th><th>版本</th><th>最后更新</th><th>操作</th></tr></thead><tbody><tr v-for="item in displayItems" :key="item.config_id"><td><strong>{{ item.display_name }}</strong><br><code>{{ item.config_key }}</code></td><td><span class="status" :class="item.status === 'ACTIVE' ? 'ok' : item.validation_state === 'INVALID' ? 'critical' : ''">{{ statusLabel[item.status] }}</span><small>{{ validationLabel[item.validation_state] }}</small></td><td>v{{ item.row_version }}</td><td>{{ formatDate(item.updated_at) }}</td><td><div class="admin-row-actions"><button class="task-action" type="button" @click="openItemEditor(item)">编辑 / 版本管理</button><button class="task-action danger" type="button" :disabled="item.status === 'ARCHIVED' || Boolean(busy)" @click="requestArchive(item)">删除</button></div></td></tr></tbody></table></div>
      </section>
      <template v-if="isAdministrationView && !showEditor">
        <template v-if="adminVariant === 'master-data'">
          <div class="admin-domain-grid"><button v-for="item in masterDataDomains" :key="item.config_id" class="domain-data-card" type="button" @click="selectItem(item)"><b>{{ item.display_name }}</b><strong>v{{ item.row_version }}</strong><span>权威方：{{ payloadText(item, 'authoritative_source', payloadText(item, 'code_system', '机构数据库')) }}</span><div><em>{{ validationLabel[item.validation_state] }}</em><i class="status" :class="item.validation_state === 'INVALID' ? 'red' : item.status === 'ACTIVE' ? 'green' : 'amber'">{{ statusLabel[item.status] }}</i></div></button></div>
          <div class="grid admin-overview"><section class="card"><div class="card-head">主数据版本与同步冲突</div><table class="table"><thead><tr><th>编码/名称</th><th>编码体系</th><th>层级</th><th>校验</th><th>状态</th></tr></thead><tbody><tr v-for="item in displayItems" :key="item.config_id" :class="{ selected: currentItem?.config_id === item.config_id }" @click="selectItem(item)"><td><b>{{ item.display_name }}</b><br><span class="meta">{{ item.config_key }}</span></td><td>{{ payloadText(item, 'code_system') }}</td><td>{{ payloadText(item, 'hierarchy') }}</td><td>{{ validationLabel[item.validation_state] }}</td><td><span class="status" :class="item.status === 'ACTIVE' ? 'green' : item.validation_state === 'INVALID' ? 'red' : 'amber'">{{ statusLabel[item.status] }}</span></td></tr></tbody></table></section><aside v-if="currentItem" class="card"><div class="card-head">主从与保护字段</div><div class="card-body"><div v-for="field in definition.fields" :key="field.key" class="folder-row">{{ field.label }}<span>{{ payloadText(currentItem, field.key) }}</span></div><div class="folder-row">数据库版本<span>v{{ currentItem.row_version }}</span></div><div class="folder-row">最后更新<span>{{ formatDate(currentItem.updated_at) }}</span></div><div class="notice info">主数据保留领域属性、状态、版本和审批责任；已被临床事实引用的值不得物理删除。</div><button class="btn primary" type="button" style="width:100%" @click="showEditor = true">打开版本管理</button></div></aside></div>
        </template>
        <div v-else class="grid" :class="adminVariant === 'parameters' ? 'parameter-layout' : 'jobs-layout'">
          <section class="card">
            <div class="toolbar">
              <input v-model="adminKeyword" class="search" :placeholder="adminVariant === 'parameters' ? '参数编码或名称' : '任务编码或名称'" />
              <select v-model="adminScopeStatus" class="select" :aria-label="adminVariant === 'parameters' ? '参数作用域' : '任务状态'">
                <option value="ALL">{{ adminVariant === 'parameters' ? '全部作用域' : '全部状态' }}</option>
                <template v-if="adminVariant === 'parameters'"><option value="GLOBAL">全局</option><option value="ORGANIZATION">机构</option><option value="FACILITY">院区</option></template>
                <template v-else><option value="DRAFT">草稿</option><option value="PENDING_APPROVAL">待审批</option><option value="APPROVED">已批准</option><option value="ACTIVE">已发布</option></template>
              </select>
              <select v-model="adminSort" class="select" aria-label="列表排序"><option value="RISK">风险优先</option><option value="RECENT">最近更新</option><option value="NAME">名称排序</option></select>
            </div>
            <table v-if="adminVariant === 'parameters'" class="table"><thead><tr><th>参数</th><th>数据类型</th><th>当前生效值</th><th>适用范围</th><th>风险</th><th>状态</th></tr></thead><tbody><tr v-for="item in displayItems" :key="item.config_id" :class="{ selected: currentItem?.config_id === item.config_id }" @click="selectItem(item)"><td><b>{{ item.display_name }}</b><br><span class="meta">技术编码：{{ item.config_key }}</span></td><td>{{ payloadDisplay(item, 'value_type') }}</td><td>{{ payloadDisplay(item, 'configured_value', payloadDisplay(item, 'scope')) }}</td><td>{{ payloadDisplay(item, 'scope') }}</td><td>{{ parameterRisk(item) }}</td><td><span class="status" :class="item.status === 'ACTIVE' ? 'green' : item.validation_state === 'INVALID' ? 'red' : 'amber'">{{ statusLabel[item.status] }}</span></td></tr></tbody></table>
            <table v-else class="table"><thead><tr><th>任务</th><th>进度/批次</th><th>状态</th><th>结果/异常</th><th>责任人</th></tr></thead><tbody><tr v-for="item in displayItems" :key="item.config_id" :class="{ selected: currentItem?.config_id === item.config_id }" @click="selectItem(item)"><td><b>{{ item.display_name }}</b><br><span class="meta">{{ item.config_key }}</span></td><td>{{ payloadText(item, 'batch_size') }} 条/批</td><td><span class="status" :class="item.status === 'ACTIVE' ? 'green' : item.validation_state === 'INVALID' ? 'red' : 'amber'">{{ statusLabel[item.status] }}</span></td><td>{{ payloadText(item, 'reconciliation_rule') }}</td><td>{{ payloadText(item, 'channel_owner', '未指定') }}</td></tr></tbody></table>
          </section>
          <aside v-if="currentItem" class="card"><div class="card-head">{{ currentItem.display_name }} · 生效解析</div><div class="card-body"><div class="inherit-chain"><div v-for="field in definition.fields" :key="field.key"><span>{{ field.label }}</span><b>{{ payloadDisplay(currentItem, field.key) }}</b><em class="status blue">→</em></div></div><div class="notice rule"><div class="notice-title">{{ validationLabel[currentItem.validation_state] }}</div>{{ definition.safetyNote }}</div><button v-if="adminVariant === 'parameters'" class="btn" type="button" style="width:100%" :disabled="currentItem.status !== 'ACTIVE' || Boolean(busy)" @click="lifecycle('ROLLBACK')">回滚到上一已发布版本</button><button v-else class="btn primary" type="button" style="width:100%" @click="downloadJobDifference">下载差异与修正清单</button><button class="btn primary" type="button" style="width:100%" @click="showEditor = true">打开版本与生命周期</button></div></aside>
        </div>
      </template>
      <AdminEditorSurface :modal="isModalEditor" :open="!isModalEditor || showEditor" :title="selected ? `编辑${selected.display_name}` : (isAdministrationView ? adminCreateLabel : '新建配置草稿')" :description="isReleaseUpgradeLedger ? '本页保存发布或升级的受管记录；其生命周期状态不触发自动下发、迁移或覆盖。' : isManagedLedger ? '本页保存治理审阅记录；实际运行时由对应专用的已发布配置和上下文机制决定。' : '配置保存为版本化草案，通过校验、独立审批和发布后才影响业务流程。'" :busy="Boolean(busy)" @update:open="showEditor = $event">
      <div class="config-studio-layout" :class="{ 'admin-modal-layout': isModalEditor }">
        <section v-if="!isModalEditor" class="card config-list-panel">
          <div class="card-head"><div><h2>版本台账</h2><p>{{ isMedicalAiView ? `${definition.title} · 版本留痕与操作记录` : `${adminCodeLabel(definition.configType)} · 全程审计与事务事件记录` }}</p></div><span class="status">{{ displayItems.length }} 项</span></div>
          <div v-if="displayItems.length === 0" class="empty-state"><span>配</span><p>暂无配置草稿</p><small>从右侧结构化编辑器创建</small></div>
          <div v-else class="table-wrap">
            <table class="table">
              <thead><tr><th>名称 / 键</th><th>生命周期</th><th>版本</th><th>更新</th></tr></thead>
              <tbody>
                <tr v-for="item in displayItems" :key="item.config_id" :class="{ selected: selected?.config_id === item.config_id }" @click="selectItem(item)">
                  <td><button class="config-row-button" type="button" @click.stop="selectItem(item)"><strong>{{ item.display_name }}</strong><code>{{ item.config_key }}</code></button></td>
                  <td><span class="status" :class="item.status === 'ACTIVE' ? 'ok' : item.validation_state === 'INVALID' ? 'critical' : ''">{{ statusLabel[item.status] }}</span><small>{{ validationLabel[item.validation_state] }}</small></td>
                  <td>v{{ item.row_version }}</td><td>{{ formatDate(item.updated_at) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="card config-editor-panel">
          <div class="card-head"><div><h2>{{ selected ? '编辑草稿' : '创建配置' }}</h2><p>{{ isMedicalAiView ? '数据结构版本 v1；发布后可通过版本回退恢复' : '版本化数据结构 v1；发布后仅能通过回退恢复' }}</p></div><span v-if="selected" class="status">v{{ selected.row_version }}</span></div>
          <form class="config-form" @submit.prevent="save">
            <div class="config-core-fields">
              <label><span>显示名称</span><input v-model="form.name" required :disabled="Boolean(selected && selected.status !== 'DRAFT')" /></label>
              <label><span>配置编码（系统唯一）</span><input v-model="form.key" required :placeholder="definition.keyPlaceholder" :disabled="Boolean(selected)" /></label>
            </div>
            <label><span>说明</span><textarea v-model="form.description" rows="2" :disabled="Boolean(selected && selected.status !== 'DRAFT')" /></label>
            <div class="config-field-grid">
              <label v-for="field in definition.fields" :key="field.key">
                <span>{{ field.label }} <code>技术字段：{{ field.key }}</code></span>
                <select v-if="field.kind === 'boolean'" v-model="values[field.key]" :disabled="Boolean(selected && selected.status !== 'DRAFT')"><option value="true">是</option><option value="false">否</option></select>
                <select v-else-if="field.kind === 'select'" v-model="values[field.key]" :disabled="Boolean(selected && selected.status !== 'DRAFT')"><option v-for="option in field.options ?? []" :key="option.value" :value="option.value">{{ option.label }}</option></select>
                <textarea v-else-if="field.kind === 'textarea' || field.kind === 'list'" v-model="values[field.key]" rows="3" :placeholder="field.placeholder" :disabled="Boolean(selected && selected.status !== 'DRAFT')" />
                <input v-else v-model="values[field.key]" :type="field.kind === 'number' ? 'number' : 'text'" :min="field.minimum" :max="field.maximum" :step="field.kind === 'number' && field.minimum === 0 && field.maximum === 1 ? '0.01' : undefined" :placeholder="field.placeholder" :disabled="Boolean(selected && selected.status !== 'DRAFT')" />
              </label>
            </div>
            <button class="button primary full" :disabled="Boolean(busy) || Boolean(selected && selected.status !== 'DRAFT')">{{ busy === 'save' ? '保存中…' : selected ? '保存新版本' : '创建版本化草稿' }}</button>
          </form>
        </section>
      </div>

      <div class="config-studio-lower">
        <section class="card config-preview">
          <div class="card-head"><div><h2>{{ definition.previewTitle }}</h2><p>当前草稿的可视化语义预览</p></div><span class="status">结构化预览</span></div>
          <div class="config-preview-board">
            <article v-for="entry in previewEntries" :key="entry.label"><strong>{{ entry.label }}</strong><div><span v-for="value in entry.values" :key="value">{{ value }}</span></div></article>
          </div>
        </section>

        <section class="card config-lifecycle">
          <div class="card-head"><div><h2>校验、审批、发布与回退</h2><p>每一步都使用重复提交保护、版本冲突检查、审计链和事务事件记录</p></div></div>
          <label><span>操作原因</span><input v-model="reason" minlength="8" maxlength="500" /></label>
          <div v-if="selected" class="lifecycle-summary">
            <span>状态 <strong>{{ statusLabel[selected.status] }}</strong></span>
            <span>校验 <strong>{{ validationLabel[selected.validation_state] }}</strong></span>
            <span>审批 <strong>{{ approvalLabel[selected.approval_state] ?? '未知状态' }}</strong></span>
            <span>发布 <strong>{{ formatDate(selected.published_at) }}</strong></span>
          </div>
          <ul v-if="selected?.validation_errors?.length" class="validation-errors"><li v-for="error in selected.validation_errors" :key="error">{{ error }}</li></ul>
          <div class="toolbar-actions lifecycle-actions">
            <button class="button secondary" type="button" :disabled="!selected || selected.status !== 'DRAFT' || Boolean(busy)" @click="lifecycle('VALIDATE')">执行静态校验</button>
            <button class="button secondary" type="button" :disabled="!selected || selected.status !== 'DRAFT' || Boolean(busy)" @click="lifecycle('SUBMIT')">提交审批</button>
            <button class="button secondary" type="button" :disabled="!selected || selected.status !== 'PENDING_APPROVAL' || Boolean(busy)" @click="lifecycle('APPROVE')">职责分离批准</button>
            <button class="button primary" type="button" :disabled="!selected || selected.status !== 'APPROVED' || Boolean(busy)" @click="lifecycle('PUBLISH')">发布</button>
            <button class="button danger" type="button" :disabled="!selected || selected.status !== 'ACTIVE' || Boolean(busy)" @click="lifecycle('ROLLBACK')">回退上一版本</button>
            <button class="button danger" type="button" :disabled="!selected || selected.status === 'ARCHIVED' || Boolean(busy)" @click="lifecycle('ARCHIVE')">归档停用</button>
          </div>
          <p class="lifecycle-footnote">作者不能批准自己的配置；开发合成身份如只有一人，批准会安全失败关闭。</p>
        </section>
      </div>
      </AdminEditorSurface>
      <AdminConfirmDialog :open="archiveConfirmOpen" :title="`归档停用${selected?.display_name ?? '配置'}`" description="归档后该版本不再进入新业务流程，历史审批、发布和业务引用继续保留。" confirm-label="确认归档停用" :busy="Boolean(busy)" @update:open="archiveConfirmOpen = $event" @confirm="lifecycle('ARCHIVE', true)"><div v-if="selected" class="admin-impact-grid"><div><span>配置编码</span><b>{{ selected.config_key }}</b></div><div><span>当前版本</span><b>v{{ selected.row_version }}</b></div><div><span>当前状态</span><b>{{ statusLabel[selected.status] }}</b></div><div><span>影响方式</span><b>停止新流程选用</b></div></div></AdminConfirmDialog>
    </template>
  </section>
</template>

<style scoped>
.config-safety{display:flex;gap:12px;align-items:flex-start;margin-bottom:16px}.config-safety span{line-height:1.55}.config-studio-layout{display:grid;grid-template-columns:minmax(420px,.85fr) minmax(520px,1.15fr);gap:16px}.config-studio-layout.admin-modal-layout{grid-template-columns:minmax(0,1fr)}.config-list-panel,.config-editor-panel,.config-preview,.config-lifecycle{min-width:0}.config-row-button{display:grid;gap:4px;border:0;background:none;padding:0;text-align:left;color:inherit;cursor:pointer}.config-row-button code{font-size:11px;color:var(--muted)}.table tbody tr{cursor:pointer}.table tbody tr.selected{background:color-mix(in srgb,var(--blue) 8%,white)}.table td small{display:block;margin-top:4px;color:var(--muted)}.config-form,.config-lifecycle{display:grid;gap:14px;padding:16px}.config-form label,.config-lifecycle label{display:grid;gap:6px;font-size:13px}.config-form label>span,.config-lifecycle label>span{font-weight:700}.config-form code{font-weight:400;color:var(--muted)}.config-core-fields,.config-field-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.config-field-grid label:has(textarea){grid-column:auto}.config-studio-lower{display:grid;grid-template-columns:1fr 1fr;gap:16px;margin-top:16px}.config-preview-board{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;padding:16px}.config-preview-board article{border:1px solid var(--border);border-radius:var(--r);padding:12px;background:var(--card)}.config-preview-board article>div{display:flex;gap:6px;flex-wrap:wrap;margin-top:8px}.config-preview-board span{border:1px solid color-mix(in srgb,var(--blue) 25%,var(--border));background:color-mix(in srgb,var(--blue) 7%,white);border-radius:999px;padding:4px 8px;font-size:12px}.lifecycle-summary{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px}.lifecycle-summary span{display:grid;gap:4px;border:1px solid var(--border);border-radius:var(--r);padding:10px;color:var(--muted)}.lifecycle-summary strong{color:var(--text)}.lifecycle-actions{flex-wrap:wrap}.validation-errors{margin:0;padding:12px 12px 12px 32px;border:1px solid var(--red);border-radius:var(--r);color:var(--red)}.lifecycle-footnote{margin:0;color:var(--muted);font-size:12px;line-height:1.6}.button.danger{border-color:var(--red);color:var(--red);background:white}@media(max-width:1100px){.config-studio-layout,.config-studio-lower{grid-template-columns:1fr}}@media(max-width:700px){.config-core-fields,.config-field-grid,.config-preview-board,.lifecycle-summary{grid-template-columns:1fr}}
</style>
