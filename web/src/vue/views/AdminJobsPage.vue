<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, ref, watch } from 'vue';
import {
  cancelAdministrationJob, listAdministrationFindings, listAdministrationJobRuns,
  resolveAdministrationFinding, retryAdministrationJob, startAdministrationJob,
  type AdministrationGovernanceFinding, type AdministrationJobRun,
} from '../../api/administration-runtime';
import { issueConfigurationLease, listConfigurations } from '../../api/config';
import { configurationStudio } from '../configuration-studios';
import { toClinicalIssue } from '../clinical-error';
import AdminActionDialog from '../components/AdminActionDialog.vue';
import ConfigurationStudioPage from '../components/ConfigurationStudioPage.vue';
import ClinicalPageState from '../components/ClinicalPageState.vue';

const definition = configurationStudio('admin-jobs');
const mode = ref<'RUNTIME' | 'CONFIG'>('RUNTIME');
const selectedConfigId = ref('');
const selectedRunId = ref('');
const busy = ref('');
const notice = ref('');
const resolutionTarget = ref<AdministrationGovernanceFinding | null>(null);
const resolution = ref('已核对责任人与证据，完成整改并记录复核结论');
const leaseQuery = useQuery({ queryKey: ['admin-jobs', 'lease'], queryFn: issueConfigurationLease, retry: false, staleTime: 300_000, gcTime: 0 });
const configQuery = useQuery({
  queryKey: ['admin-jobs', 'configurations'], queryFn: () => listConfigurations(leaseQuery.data.value!, 'JOB'),
  enabled: () => Boolean(leaseQuery.data.value), retry: false,
});
const runsQuery = useQuery({
  queryKey: ['admin-jobs', 'runs'], queryFn: () => listAdministrationJobRuns(), retry: false,
  refetchInterval: 2000,
});
const findingsQuery = useQuery({
  queryKey: ['admin-jobs', 'findings', selectedRunId], queryFn: () => listAdministrationFindings(selectedRunId.value),
  enabled: () => Boolean(selectedRunId.value), retry: false,
});
const configs = computed(() => configQuery.data.value ?? []);
const activeConfigs = computed(() => configs.value.filter((item) => item.status === 'ACTIVE'));
const runs = computed<AdministrationJobRun[]>(() => runsQuery.data.value ?? []);
const selectedRun = computed(() => runs.value.find((item) => item.run_id === selectedRunId.value) ?? runs.value[0] ?? null);
const findings = computed(() => findingsQuery.data.value ?? []);
const issue = computed(() => {
  const error = leaseQuery.error.value ?? configQuery.error.value ?? runsQuery.error.value ?? findingsQuery.error.value;
  return error ? toClinicalIssue(error) : null;
});

watch(activeConfigs, (next) => { if (!selectedConfigId.value && next[0]) selectedConfigId.value = next[0].config_id; }, { immediate: true });
watch(runs, (next) => { if (!selectedRunId.value && next[0]) selectedRunId.value = next[0].run_id; }, { immediate: true });
watch(selectedRun, (run) => { if (run && selectedRunId.value !== run.run_id) selectedRunId.value = run.run_id; });

const statusLabel: Readonly<Record<string, string>> = Object.freeze({
  QUEUED: '排队中', RUNNING: '执行中', SUCCEEDED: '执行成功', PARTIAL: '部分成功', FAILED: '执行失败', CANCELLED: '已取消',
});
const kindLabel: Readonly<Record<string, string>> = Object.freeze({
  ADMIN_GOVERNANCE_AGENT: '系统治理智能体（规则引擎）', AUDIT_CHAIN_VERIFY: '审计链完整性核验',
  ROLE_CONFLICT_REVIEW: '角色职责冲突复核', CREDENTIAL_EXPIRY_REVIEW: '执业资质到期复核',
  MASTER_DATA_RECONCILIATION: '主数据映射对账', NOTIFICATION_RECONCILIATION: '通知与事务事件对账',
});
const severityLabel: Readonly<Record<string, string>> = Object.freeze({ INFO: '提示', LOW: '低', MEDIUM: '中', HIGH: '高', CRITICAL: '严重' });
function formatDate(value: string | null) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'; }
function tone(status: string) { return status === 'SUCCEEDED' ? 'green' : status === 'FAILED' ? 'red' : ['RUNNING', 'QUEUED'].includes(status) ? 'blue' : 'amber'; }

async function start() {
  if (!selectedConfigId.value || busy.value) return;
  busy.value = 'start'; notice.value = '';
  try {
    const run = await startAdministrationJob(selectedConfigId.value);
    selectedRunId.value = run.run_id; notice.value = '任务已写入数据库队列，执行器正在领取。';
    await runsQuery.refetch();
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}
async function cancel(run: AdministrationJobRun) {
  if (busy.value) return; busy.value = run.run_id; notice.value = '';
  try { await cancelAdministrationJob(run); notice.value = '排队任务已取消并写入审计链。'; await runsQuery.refetch(); }
  catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}
async function retry(run: AdministrationJobRun) {
  if (busy.value) return; busy.value = run.run_id; notice.value = '';
  try { const nextRun = await retryAdministrationJob(run.run_id); selectedRunId.value = nextRun.run_id; notice.value = '已创建新的重试批次，原执行记录保持不变。'; await runsQuery.refetch(); }
  catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}
async function resolveFinding() {
  const target = resolutionTarget.value; if (!target || busy.value) return;
  busy.value = target.finding_id; notice.value = '';
  try { await resolveAdministrationFinding(target, resolution.value); resolutionTarget.value = null; notice.value = '问题已处置，处置人、时间和说明已写入数据库与审计链。'; await findingsQuery.refetch(); }
  catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}
</script>

<template>
  <div v-if="mode === 'CONFIG'"><div class="auth-backbar content"><button class="btn" type="button" @click="mode = 'RUNTIME'">← 返回任务执行中心</button></div><ConfigurationStudioPage :definition="definition" /></div>
  <section v-else data-page-root class="content admin-content vue-native-page">
    <div class="page-head"><div class="page-title"><h1>通知任务与治理执行中心</h1><p>任务从已发布配置进入数据库队列，由规则执行器产生批次、结果、异常和可处置治理问题</p></div><div class="head-actions"><button class="btn" type="button" @click="runsQuery.refetch()">刷新执行记录</button><button class="btn" type="button" @click="mode = 'CONFIG'">任务配置与发布</button><button class="btn primary" type="button" :disabled="!selectedConfigId || Boolean(busy)" @click="start">立即执行</button></div></div>
    <ClinicalPageState v-if="leaseQuery.isPending.value || configQuery.isPending.value || runsQuery.isPending.value" kind="loading" message="正在读取任务配置与执行批次" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="runsQuery.refetch()" />
    <template v-else>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>
      <section class="admin-panel admin-form-panel"><header><div><h2>创建执行批次</h2><p>只有经过校验、独立审批并发布的任务才能进入队列；“治理规则引擎”是可审计的确定性规则，不冒充真实模型能力。</p></div></header><div class="toolbar"><select v-model="selectedConfigId" class="select" aria-label="已发布任务"><option value="">请选择已发布任务</option><option v-for="item in activeConfigs" :key="item.config_id" :value="item.config_id">{{ item.display_name }} · {{ kindLabel[String(item.payload?.job_kind)] ?? item.payload?.job_kind }}</option></select><span class="status" :class="activeConfigs.length ? 'green' : 'amber'">{{ activeConfigs.length }} 个可执行任务</span></div><div v-if="!activeConfigs.length" class="notice hard">当前没有已发布任务。请进入“任务配置与发布”，补充任务类型并走完校验、独立审批和发布流程。</div></section>
      <section class="admin-metrics"><article><span>执行批次</span><strong>{{ runs.length }}</strong><small>数据库记录</small></article><article><span>执行中</span><strong>{{ runs.filter(item => ['QUEUED','RUNNING'].includes(item.status)).length }}</strong><small>队列与工作器</small></article><article><span>失败批次</span><strong>{{ runs.filter(item => item.status === 'FAILED').length }}</strong><small>支持独立重试</small></article><article><span>未处置问题</span><strong>{{ findings.filter(item => item.status !== 'RESOLVED').length }}</strong><small>当前选中批次</small></article></section>
      <div class="grid admin-list-detail">
        <section class="card"><div class="card-head">批次执行台账 <span class="status blue">真实数据库</span></div><div v-if="!runs.length" class="admin-empty">尚无执行批次。</div><div v-else class="admin-table-wrap"><table class="table"><thead><tr><th>任务类型 / 批次</th><th>状态</th><th>处理结果</th><th>时间</th><th>操作</th></tr></thead><tbody><tr v-for="run in runs" :key="run.run_id" :class="{ selected: selectedRun?.run_id === run.run_id }" @click="selectedRunId = run.run_id"><td><b>{{ kindLabel[run.job_kind] ?? run.job_kind }}</b><br><span class="meta">…{{ run.run_id.slice(-8) }} · 第 {{ run.attempt }} 次</span></td><td><span class="status" :class="tone(run.status)">{{ statusLabel[run.status] ?? run.status }}</span></td><td>{{ run.processed_count }} 项 / 成功 {{ run.succeeded_count }} / 失败 {{ run.failed_count }}<br><span class="meta">发现 {{ run.result.finding_count ?? 0 }} 个治理问题</span></td><td>{{ formatDate(run.started_at ?? run.created_at) }}</td><td><div class="admin-row-actions"><button class="task-action" type="button" :disabled="run.status !== 'QUEUED' || Boolean(busy)" @click.stop="cancel(run)">取消</button><button class="task-action" type="button" :disabled="!['FAILED','PARTIAL','CANCELLED'].includes(run.status) || Boolean(busy)" @click.stop="retry(run)">重试</button></div></td></tr></tbody></table></div></section>
        <aside v-if="selectedRun" class="card"><div class="card-head">批次详情 · …{{ selectedRun.run_id.slice(-8) }}</div><div class="card-body"><div class="folder-row">执行方式<span>{{ selectedRun.result.execution_mode === 'DETERMINISTIC_RULE_ENGINE' ? '确定性规则引擎' : '等待执行' }}</span></div><div class="folder-row">开始时间<span>{{ formatDate(selectedRun.started_at) }}</span></div><div class="folder-row">完成时间<span>{{ formatDate(selectedRun.finished_at) }}</span></div><div class="folder-row">数据库版本<span>v{{ selectedRun.row_version }}</span></div><div class="folder-row">错误代码<span>{{ selectedRun.error_code ?? '无' }}</span></div><div class="notice rule"><div class="notice-title">对账口径</div>处理总数、成功数、失败数和治理问题分别记录；发现风险不伪装成任务技术失败。</div></div></aside>
      </div>
      <section v-if="selectedRun" class="card admin-secondary-ledger"><div class="card-head">治理问题与处置闭环 <span class="status" :class="findings.some(item => item.status !== 'RESOLVED') ? 'amber' : 'green'">{{ findings.length }} 项</span></div><div v-if="findingsQuery.isPending.value" class="admin-empty">正在读取治理问题…</div><div v-else-if="!findings.length" class="admin-empty">本批次未发现需要人工处置的问题。</div><div v-else class="admin-table-wrap"><table class="table"><thead><tr><th>严重度</th><th>问题</th><th>对象</th><th>整改建议</th><th>状态 / 操作</th></tr></thead><tbody><tr v-for="finding in findings" :key="finding.finding_id"><td><span class="status" :class="['CRITICAL','HIGH'].includes(finding.severity) ? 'red' : finding.severity === 'MEDIUM' ? 'amber' : 'blue'">{{ severityLabel[finding.severity] ?? finding.severity }}</span></td><td><b>{{ finding.summary }}</b><br><span class="meta">{{ finding.finding_type }}</span></td><td>{{ finding.resource_type }}<br><span class="meta">{{ finding.resource_id ? `…${finding.resource_id.slice(-8)}` : '集合检查' }}</span></td><td>{{ finding.recommendation }}</td><td><span class="status" :class="finding.status === 'RESOLVED' ? 'green' : 'amber'">{{ finding.status === 'RESOLVED' ? '已处置' : '待处置' }}</span><br><button class="task-action" type="button" :disabled="finding.status === 'RESOLVED' || Boolean(busy)" @click="resolutionTarget = finding">登记处置</button></td></tr></tbody></table></div></section>
    </template>
    <AdminActionDialog :open="Boolean(resolutionTarget)" title="登记治理问题处置" description="处置说明、操作者和时间会写入数据库与审计链，原问题证据不可覆盖。" :busy="Boolean(busy)" @update:open="!$event && (resolutionTarget = null)"><form class="admin-form" @submit.prevent="resolveFinding"><label><span>处置说明（至少 8 个字符）</span><textarea v-model="resolution" minlength="8" rows="5" required autofocus /></label><button class="button primary" :disabled="Boolean(busy)">确认完成处置</button></form></AdminActionDialog>
  </section>
</template>
