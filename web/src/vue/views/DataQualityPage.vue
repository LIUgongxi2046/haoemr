<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref } from 'vue';
import type {
  DataQualityFindingTransitionRequestWire,
  DataQualityFindingWire,
  DataQualityRuleWire,
  DataQualityScanRunWire,
} from '../../generated/contracts';
import {
  deactivateDataQualityRule,
  createDataQualityTriageAdvice,
  issueDataLease,
  listDataQualityEvaluations,
  listDataQualityFindings,
  listDataQualityRules,
  listDataQualityScans,
  listDataQualityTriageAdvice,
  registerDataQualityRule,
  startDataQualityScan,
  transitionDataQualityFinding,
} from '../../api/data';
import AdminActionDialog from '../components/AdminActionDialog.vue';
import AdminConfirmDialog from '../components/AdminConfirmDialog.vue';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';

type Dimension = DataQualityRuleWire['dimension'];
type Severity = DataQualityRuleWire['severity'];

const dimensionOptions: Dimension[] = ['COMPLETENESS', 'CONSISTENCY', 'TIMELINESS', 'UNIQUENESS', 'VALIDITY'];
const severityOptions: Severity[] = ['INFO', 'WARNING', 'BLOCKING'];
const dimensionLabels: Record<Dimension, string> = {
  COMPLETENESS: '完整性', CONSISTENCY: '一致性', TIMELINESS: '及时性', UNIQUENESS: '唯一性', VALIDITY: '有效性',
};
const severityLabels: Record<Severity, string> = { INFO: '提示', WARNING: '警告', BLOCKING: '阻断' };

const dimension = ref('');
const selectedRuleId = ref('');
const selectedScanId = ref('');
const showInactive = ref(false);

const leaseQuery = useQuery({
  queryKey: ['data', 'data-quality', 'lease'],
  queryFn: () => issueDataLease('DATA_QUALITY_ADMIN'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const rulesQuery = useQuery({
  queryKey: ['data', 'data-quality', 'rules', dimension],
  queryFn: () => listDataQualityRules(leaseQuery.data.value!, dimension.value || undefined),
  enabled: () => Boolean(leaseQuery.data.value),
  retry: false,
});
const evaluationsQuery = useQuery({
  queryKey: ['data', 'data-quality', 'evaluations', selectedRuleId],
  queryFn: () => listDataQualityEvaluations(leaseQuery.data.value!, selectedRuleId.value),
  enabled: () => Boolean(leaseQuery.data.value && selectedRuleId.value),
  retry: false,
});
const scansQuery = useQuery({
  queryKey: ['data', 'data-quality', 'scans', selectedRuleId],
  queryFn: () => listDataQualityScans(leaseQuery.data.value!, selectedRuleId.value),
  enabled: () => Boolean(leaseQuery.data.value && selectedRuleId.value),
  retry: false,
});
const allFindingsQuery = useQuery({
  queryKey: ['data', 'data-quality', 'findings', 'work-queue'],
  queryFn: () => listDataQualityFindings(leaseQuery.data.value!),
  enabled: () => Boolean(leaseQuery.data.value),
  retry: false,
});
const findingsQuery = useQuery({
  queryKey: ['data', 'data-quality', 'findings', selectedScanId],
  queryFn: () => listDataQualityFindings(leaseQuery.data.value!, selectedScanId.value),
  enabled: () => Boolean(leaseQuery.data.value && selectedScanId.value),
  retry: false,
});
const adviceQuery = useQuery({
  queryKey: ['data', 'data-quality', 'triage-advice', selectedScanId],
  queryFn: () => listDataQualityTriageAdvice(leaseQuery.data.value!, selectedScanId.value),
  enabled: () => Boolean(leaseQuery.data.value && selectedScanId.value),
  retry: false,
});
const issue = computed(() => (leaseQuery.error.value ?? rulesQuery.error.value)
  ? toClinicalIssue(leaseQuery.error.value ?? rulesQuery.error.value) : null);
const allRules = computed(() => rulesQuery.data.value ?? []);
const rules = computed(() => allRules.value.filter((rule) => showInactive.value || rule.status === 'ACTIVE'));
const evaluations = computed(() => evaluationsQuery.data.value ?? []);
const scans = computed(() => scansQuery.data.value ?? []);
const findings = computed(() => findingsQuery.data.value ?? []);
const allFindings = computed(() => allFindingsQuery.data.value ?? []);
const advice = computed(() => adviceQuery.data.value?.[0] ?? null);
const selectedScan = computed(() => scans.value.find((scan) => scan.data_quality_scan_id === selectedScanId.value) ?? null);
const selectedRule = computed(() => rules.value.find((rule) => rule.data_quality_rule_id === selectedRuleId.value) ?? null);
const activeCount = computed(() => rules.value.filter((rule) => rule.status === 'ACTIVE').length);
const blockingCount = computed(() => allFindings.value.filter((finding) => finding.status !== 'CLOSED' && finding.severity === 'BLOCKING').length);
const warningCount = computed(() => allFindings.value.filter((finding) => finding.status !== 'CLOSED' && finding.severity === 'WARNING').length);
const workQueue = computed(() => [...rules.value]
  .filter((rule) => rule.status === 'ACTIVE')
  .sort((left, right) => severityOptions.indexOf(right.severity) - severityOptions.indexOf(left.severity))
  .slice(0, 5));
const operationsIssue = computed(() => {
  const error = scansQuery.error.value ?? findingsQuery.error.value ?? adviceQuery.error.value;
  return error ? toClinicalIssue(error) : null;
});

const form = reactive({
  ruleCode: '', ruleName: '', dimension: 'COMPLETENESS' as Dimension,
  targetEntity: '', threshold: 0.9, severity: 'WARNING' as Severity,
});
const busy = ref('');
const notice = ref('');
const createOpen = ref(false);
const deactivateOpen = ref(false);
const scanOpen = ref(false);
const transitionOpen = ref(false);
const pendingDeactivate = ref<DataQualityRuleWire | null>(null);
const pendingScanRule = ref<DataQualityRuleWire | null>(null);
const transitionFinding = ref<DataQualityFindingWire | null>(null);
const transitionForm = reactive({
  action: 'ASSIGN' as DataQualityFindingTransitionRequestWire['action'],
  note: '',
});

function formatDate(value: string | null | undefined) {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—';
}

function initiatorLabel(scan: DataQualityScanRunWire) {
  return scan.initiator_kind === 'SCHEDULED' ? '系统定时扫描' : '人工发起';
}

async function reload() {
  notice.value = '';
  await Promise.all([rulesQuery.refetch(), allFindingsQuery.refetch()]);
}

async function selectRule(ruleId: string) {
  selectedRuleId.value = ruleId;
  selectedScanId.value = '';
  notice.value = '';
  if (ruleId) await Promise.all([evaluationsQuery.refetch(), scansQuery.refetch()]);
}

function requestScan(rule: DataQualityRuleWire) {
  pendingScanRule.value = rule;
  scanOpen.value = true;
}

async function runFactScan() {
  const lease = leaseQuery.data.value;
  const rule = pendingScanRule.value;
  if (!lease || !rule || busy.value) return;
  busy.value = 'scan'; notice.value = '';
  try {
    const scan = await startDataQualityScan(lease, rule.data_quality_rule_id);
    selectedRuleId.value = rule.data_quality_rule_id;
    selectedScanId.value = scan.data_quality_scan_id;
    scanOpen.value = false;
    notice.value = scan.status === 'NO_DATA'
      ? '事实扫描已完成，当前院区无可评估样本，请先核验源系统入湖水位。'
      : `事实扫描完成：${scan.total_count} 条，失败 ${scan.failed_count} 条。`;
    await Promise.all([scansQuery.refetch(), findingsQuery.refetch(), adviceQuery.refetch()]);
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = ''; }
}

async function selectScan(scan: DataQualityScanRunWire) {
  selectedScanId.value = scan.data_quality_scan_id;
  notice.value = '';
  await Promise.all([findingsQuery.refetch(), adviceQuery.refetch()]);
}

async function createTriage() {
  const lease = leaseQuery.data.value;
  if (!lease || !selectedScanId.value || busy.value) return;
  busy.value = 'triage'; notice.value = '';
  try {
    await createDataQualityTriageAdvice(lease, selectedScanId.value);
    notice.value = '治理助手已基于扫描证据生成候选处置建议，未自动修改临床事实或工单状态。';
    await adviceQuery.refetch();
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = ''; }
}

function requestFindingTransition(
  finding: DataQualityFindingWire,
  action: DataQualityFindingTransitionRequestWire['action'],
) {
  transitionFinding.value = finding;
  transitionForm.action = action;
  transitionForm.note = '';
  transitionOpen.value = true;
}

async function submitFindingTransition() {
  const lease = leaseQuery.data.value;
  const finding = transitionFinding.value;
  if (!lease || !finding || busy.value || transitionForm.note.trim().length < 2) return;
  busy.value = 'transition'; notice.value = '';
  try {
    await transitionDataQualityFinding(lease, finding.data_quality_finding_id, {
      action: transitionForm.action,
      assignee_id: null,
      note: transitionForm.note.trim(),
      row_version: finding.row_version,
    });
    transitionOpen.value = false;
    notice.value = '质量问题状态已更新，版本、操作人和审计事件已同步记录。';
    await findingsQuery.refetch();
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = ''; }
}

async function createRule() {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || !form.ruleCode.trim() || !form.ruleName.trim() || !form.targetEntity.trim()) return;
  busy.value = 'create'; notice.value = '';
  try {
    await registerDataQualityRule(lease, {
      rule_code: form.ruleCode.trim(),
      rule_name: form.ruleName.trim(),
      dimension: form.dimension,
      target_entity: form.targetEntity.trim(),
      threshold: form.threshold,
      severity: form.severity,
    });
    form.ruleCode = ''; form.ruleName = ''; form.targetEntity = '';
    notice.value = '数据质量规则已注册，审计链与事件出箱已同步记录。';
    createOpen.value = false;
    await rulesQuery.refetch();
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = ''; }
}

function requestDeactivate(rule: DataQualityRuleWire) {
  pendingDeactivate.value = rule;
  deactivateOpen.value = true;
}

async function deactivatePending() {
  if (!pendingDeactivate.value) return;
  await deactivate(pendingDeactivate.value);
  deactivateOpen.value = false;
}

async function deactivate(rule: DataQualityRuleWire) {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || rule.status !== 'ACTIVE') return;
  busy.value = rule.data_quality_rule_id; notice.value = '';
  try {
    await deactivateDataQualityRule(lease, rule);
    notice.value = `规则 ${rule.rule_name} 已停用。`;
    await rulesQuery.refetch();
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = ''; }
}

</script>

<template>
  <section data-page-root class="content admin-content vue-native-page data-quality-page">
    <div class="page-heading admin-heading">
      <div>
        <p class="eyebrow">数据中心 / 数据质量</p>
        <h1>全院数据质量与问题工单</h1>
        <p>覆盖可执行事实扫描、问题队列、整改复核与审计追踪；未注册扫描器的旧规则已停用，历史证据持续保留。</p>
      </div>
      <div class="admin-inline-tools">
        <label class="admin-code-input"><span>维度筛选</span>
          <select v-model="dimension"><option value="">全部维度</option><option v-for="dim in dimensionOptions" :key="dim" :value="dim">{{ dimensionLabels[dim] }}</option></select>
        </label>
        <button class="button secondary" :disabled="Boolean(busy)" @click="reload">查询</button>
        <button class="button primary" :disabled="Boolean(busy)" @click="createOpen = true">新建质量规则</button>
      </div>
    </div>

    <ClinicalPageState v-if="leaseQuery.isPending.value || rulesQuery.isPending.value" kind="loading" message="正在读取数据质量规则" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reload" />

    <template v-else>
      <section class="admin-metrics" aria-label="数据质量统计">
        <article><span>规则总数</span><strong>{{ rules.length }}</strong><small>当前筛选</small></article>
        <article><span>有效规则</span><strong>{{ activeCount }}</strong></article>
        <article><span>阻断级问题</span><strong>{{ blockingCount }}</strong><small>影响新业务流程</small></article>
        <article><span>警告级问题</span><strong>{{ warningCount }}</strong><small>进入整改队列</small></article>
      </section>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>

      <section class="quality-operations">
        <div class="admin-panel quality-work-queue">
           <header><div><h2>质量问题工作队列</h2><p>按规则严重级别安排事实扫描；实际问题只来自扫描结果，再按分派、整改、复核、关闭处置。</p></div></header>
          <div v-if="workQueue.length === 0" class="admin-empty">当前筛选下没有待处理规则。</div>
          <div v-else class="quality-queue-list">
            <button v-for="rule in workQueue" :key="`queue-${rule.data_quality_rule_id}`" class="quality-queue-row" @click="selectRule(rule.data_quality_rule_id)">
              <span><strong>{{ rule.rule_name }}</strong><small>{{ dimensionLabels[rule.dimension] }} · {{ rule.target_entity }}</small></span>
              <span class="quality-queue-meta"><b>{{ severityLabels[rule.severity] }}</b><small>阈值 {{ rule.threshold }}</small></span>
            </button>
          </div>
          <ol class="quality-flow" aria-label="质量整改流程"><li>发现</li><li>分派</li><li>整改</li><li>复核</li><li>关闭</li></ol>
        </div>
        <aside class="admin-panel quality-safeguards">
          <header><div><h2>安全与恢复</h2><p>质量规则直接影响流程，所有动作保留审计证据。</p></div></header>
          <div class="folder-row"><span>规则变更</span><strong>版本化新增</strong></div>
          <div class="folder-row"><span>阻断级失败</span><strong>进入人工复核</strong></div>
          <div class="folder-row"><span>失败重试</span><strong>幂等记录</strong></div>
          <div class="folder-row"><span>规则停用</span><strong>历史证据保留</strong></div>
          <button class="button secondary quality-refresh" @click="reload">刷新工作队列</button>
        </aside>
      </section>

      <section class="admin-panel">
          <header>
            <div><h2>规则台账</h2><p>阈值范围 0–1；ACTIVE 仅表示已有只读事实扫描器的规则，默认只展示可执行规则。</p></div>
            <label class="admin-code-input"><span>历史</span><input v-model="showInactive" type="checkbox" /> 包含已停用</label>
            <button class="button secondary" @click="rulesQuery.refetch()">刷新</button>
          </header>
          <div v-if="rules.length === 0" class="admin-empty" role="status">暂无数据质量规则，可在右侧注册。</div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>规则编码</th><th>名称 / 维度</th><th>目标实体</th><th>阈值</th><th>严重级别</th><th>状态</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="rule in rules" :key="rule.data_quality_rule_id">
                  <td><code>{{ rule.rule_code }}</code></td>
                  <td><strong>{{ rule.rule_name }}</strong><small>{{ dimensionLabels[rule.dimension] }} · …{{ rule.data_quality_rule_id.slice(-8) }}</small></td>
                  <td>{{ rule.target_entity }}</td>
                  <td>{{ rule.threshold }}</td>
                  <td>{{ severityLabels[rule.severity] }}</td>
                  <td><span class="admin-status" :class="rule.status.toLowerCase()">{{ rule.status === 'ACTIVE' ? '有效' : '已停用' }}</span></td>
                  <td class="admin-actions">
                    <button class="task-action" :disabled="Boolean(busy)" @click="selectRule(rule.data_quality_rule_id)">查看扫描</button>
                    <button class="task-action" :disabled="rule.status !== 'ACTIVE' || Boolean(busy)" @click="requestScan(rule)">运行事实扫描</button>
                    <button class="task-action" :disabled="rule.status !== 'ACTIVE' || Boolean(busy)" @click="requestDeactivate(rule)">{{ busy === rule.data_quality_rule_id ? '处理中…' : '停用' }}</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
      </section>

      <section v-if="selectedRule" class="admin-panel quality-scan-console">
        <header>
          <div><h2>事实扫描 · {{ selectedRule.rule_code }}</h2><p>扫描器只读院内业务事实表；不支持的规则会明确阻断，不伪造分数。</p></div>
          <div class="toolbar-actions"><button class="button primary" :disabled="selectedRule.status !== 'ACTIVE' || Boolean(busy)" @click="requestScan(selectedRule)">运行事实扫描</button><button class="button secondary" @click="selectRule('')">关闭</button></div>
        </header>
        <ClinicalPageState v-if="scansQuery.isPending.value" kind="loading" message="正在读取事实扫描记录" />
        <ClinicalPageState v-else-if="operationsIssue" kind="error" :code="operationsIssue.code" :message="operationsIssue.message" @retry="scansQuery.refetch()" />
        <div v-else-if="scans.length === 0" class="admin-empty" role="status">该规则尚未运行事实扫描。历史人工评估只作旧证据保留，不再作为新的执行入口。</div>
        <div v-else class="admin-table-wrap">
          <table class="admin-table">
            <thead><tr><th>扫描时间</th><th>发起方式</th><th>样本</th><th>通过</th><th>失败</th><th>质量分</th><th>状态</th><th>操作</th></tr></thead>
            <tbody><tr v-for="scan in scans" :key="scan.data_quality_scan_id">
              <td>{{ formatDate(scan.started_at) }}</td><td>{{ initiatorLabel(scan) }}</td><td>{{ scan.total_count }}</td><td>{{ scan.passed_count }}</td><td>{{ scan.failed_count }}</td>
              <td>{{ (scan.score * 100).toFixed(2) }}%</td><td><span class="admin-status" :class="scan.status.toLowerCase()">{{ scan.status === 'NO_DATA' ? '无样本' : '已完成' }}</span></td>
              <td><button class="task-action" @click="selectScan(scan)">进入问题详情</button></td>
            </tr></tbody>
          </table>
        </div>

        <section v-if="selectedScan" class="quality-scan-detail" aria-label="扫描问题详情">
          <header><div><h3>问题工单 · …{{ selectedScan.data_quality_scan_id.slice(-8) }}</h3><p>{{ initiatorLabel(selectedScan) }}；详情不展示姓名、证件号或病历正文，仅保留实体引用与原因代码。</p></div><button class="button secondary" :disabled="Boolean(busy)" @click="createTriage">{{ busy === 'triage' ? '分析中…' : '治理助手研判' }}</button></header>
          <div v-if="advice" class="quality-agent-advice"><strong>治理候选建议 · {{ advice.risk_level }}</strong><p>{{ advice.summary }}</p><ol><li v-for="action in advice.prioritized_actions" :key="action">{{ action }}</li></ol><small>证据指纹 …{{ advice.evidence_hash.slice(-12) }} · 仅建议，无自动写入权</small></div>
          <div v-if="findingsQuery.isPending.value" class="admin-empty">正在读取问题工单…</div>
          <div v-else-if="findings.length === 0" class="admin-empty">本次扫描未生成失败工单。</div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table"><thead><tr><th>实体引用</th><th>原因</th><th>级别</th><th>状态</th><th>责任人</th><th>操作</th></tr></thead>
              <tbody><tr v-for="finding in findings" :key="finding.data_quality_finding_id">
                <td><code>…{{ finding.target_entity_id.slice(-8) }}</code></td><td><strong>{{ finding.reason_code }}</strong><small>{{ finding.reason_detail }}</small></td>
                <td>{{ severityLabels[finding.severity] }}</td><td><span class="admin-status" :class="finding.status.toLowerCase()">{{ finding.status }}</span></td><td>{{ finding.assigned_to ? `…${finding.assigned_to.slice(-8)}` : '待分派' }}</td>
                <td class="admin-actions">
                  <button v-if="finding.status === 'OPEN'" class="task-action" @click="requestFindingTransition(finding, 'ASSIGN')">分派</button>
                  <button v-if="finding.status === 'OPEN' || finding.status === 'ASSIGNED'" class="task-action" @click="requestFindingTransition(finding, 'REMEDIATE')">记录整改</button>
                  <button v-if="finding.status === 'REMEDIATED'" class="task-action" @click="requestFindingTransition(finding, 'VERIFY')">复核</button>
                  <button v-if="finding.status === 'VERIFIED'" class="task-action" @click="requestFindingTransition(finding, 'CLOSE')">关闭</button>
                  <button v-if="finding.status === 'REMEDIATED' || finding.status === 'VERIFIED' || finding.status === 'CLOSED'" class="task-action" @click="requestFindingTransition(finding, 'REOPEN')">重开</button>
                </td>
              </tr></tbody>
            </table>
          </div>
        </section>

        <details v-if="evaluations.length" class="quality-legacy-evidence"><summary>历史人工评估（只读）</summary><p>共 {{ evaluations.length }} 条。为保留审计证据不删除，新流程已改用事实扫描。</p></details>
      </section>
    </template>

    <AdminActionDialog v-model:open="createOpen" title="新建数据质量规则" description="仅可登记已有只读事实扫描器的规则编码及对应目标实体；不支持的规则不会被错误启用。规则身份与阈值不可覆盖修改，变更需停用旧规则并创建新版本。" size="large" :busy="busy === 'create'">
      <form class="admin-form quality-dialog-form" @submit.prevent="createRule">
        <label><span>规则编码</span><input v-model="form.ruleCode" maxlength="96" required placeholder="例：DQ-PATIENT-NAME" /></label>
        <label><span>规则名称</span><input v-model="form.ruleName" maxlength="256" required placeholder="例：患者姓名完整性" /></label>
        <label><span>维度</span><select v-model="form.dimension"><option v-for="dim in dimensionOptions" :key="dim" :value="dim">{{ dimensionLabels[dim] }}</option></select></label>
        <label><span>目标实体</span><input v-model="form.targetEntity" maxlength="96" required placeholder="例：patient / encounter" /></label>
        <label><span>阈值（0–1）</span><input v-model.number="form.threshold" type="number" min="0" max="1" step="0.01" required /></label>
        <label><span>严重级别</span><select v-model="form.severity"><option v-for="severity in severityOptions" :key="severity" :value="severity">{{ severityLabels[severity] }}</option></select></label>
      </form>
      <template #footer="{ close }"><button class="button secondary" :disabled="busy === 'create'" @click="close">取消</button><button class="button primary" :disabled="busy === 'create'" @click="createRule">{{ busy === 'create' ? '正在注册…' : '注册并生效' }}</button></template>
    </AdminActionDialog>
    <AdminConfirmDialog v-model:open="scanOpen" :title="`运行事实扫描 · ${pendingScanRule?.rule_code ?? ''}`" description="系统将使用服务端白名单扫描器读取当前院区业务事实，生成不含 PHI 的问题工单与审计证据。" confirm-label="确认扫描" :busy="busy === 'scan'" @confirm="runFactScan" />
    <AdminActionDialog v-model:open="transitionOpen" :title="`问题处置 · ${transitionForm.action}`" description="每次变更使用行版本防止静默覆盖，处置说明与操作人进入不可变事件链。" :busy="busy === 'transition'">
      <form class="admin-form" @submit.prevent="submitFindingTransition"><label><span>处置说明</span><textarea v-model="transitionForm.note" minlength="2" maxlength="2000" required rows="5" placeholder="说明分派依据、整改内容或复核结论" /></label></form>
      <template #footer="{ close }"><button class="button secondary" :disabled="busy === 'transition'" @click="close">取消</button><button class="button primary" :disabled="busy === 'transition' || transitionForm.note.trim().length < 2" @click="submitFindingTransition">{{ busy === 'transition' ? '正在提交…' : '确认处置' }}</button></template>
    </AdminActionDialog>
    <AdminConfirmDialog v-model:open="deactivateOpen" :title="`停用规则 ${pendingDeactivate?.rule_code ?? ''}`" description="停用后新的数据质量评估不再执行该规则，历史失败记录、整改证据和审计链继续保留。" confirm-label="确认停用" :busy="Boolean(busy)" @confirm="deactivatePending" />
  </section>
</template>

<style scoped>
.data-quality-page { display: grid; align-content: start; gap: 14px; }
.data-quality-page > .page-heading,
.data-quality-page > .admin-metrics,
.data-quality-page > .admin-notice,
.data-quality-page > .admin-panel { margin: 0; }
.quality-dialog-form { grid-template-columns: repeat(2, minmax(0, 1fr)); padding: 0; }
.admin-inline-tools { display: flex; flex-wrap: wrap; align-items: end; gap: 10px; }
.data-quality-page td.admin-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; min-width: 176px; }
.toolbar-actions { gap: 8px; }
.quality-operations { display: grid; grid-template-columns: minmax(0, 1.7fr) minmax(260px, .8fr); gap: 14px; }
.quality-work-queue, .quality-safeguards { margin: 0; }
.quality-queue-list { display: grid; gap: 8px; margin: 14px 16px 0; }
.quality-queue-row { display: flex; width: 100%; align-items: center; justify-content: space-between; gap: 14px; padding: 10px 12px; border: 1px solid var(--line); border-radius: 8px; background: var(--card); color: inherit; text-align: left; cursor: pointer; }
.quality-queue-row > span { display: grid; gap: 3px; min-width: 0; }
.quality-queue-row small { color: var(--muted); }
.quality-queue-meta { flex: 0 0 auto; text-align: right; }
.quality-flow { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 8px; margin: 14px 16px 16px; padding: 0; list-style: none; }
.quality-flow li { padding: 8px 6px; border-radius: 999px; background: var(--blue-50); color: var(--blue); font-size: 11px; font-weight: 700; text-align: center; }
.quality-safeguards > .folder-row { padding: 10px 16px; }
.quality-refresh { width: calc(100% - 32px); margin: 14px 16px 16px; }
.quality-scan-console { display: grid; gap: 14px; }
.quality-scan-console > header { margin-bottom: 0; }
.quality-scan-detail { display: grid; gap: 12px; padding: 14px; border: 1px solid var(--line); border-radius: 10px; background: var(--surface-muted, #f8fafc); }
.quality-scan-detail > header { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; margin: 0; }
.quality-scan-detail h3 { margin: 0 0 4px; }
.quality-scan-detail p { margin: 0; color: var(--muted); }
.quality-agent-advice { display: grid; gap: 8px; padding: 12px 14px; border: 1px solid color-mix(in srgb, var(--blue) 26%, var(--line)); border-radius: 9px; background: var(--blue-50); }
.quality-agent-advice p, .quality-agent-advice ol { margin: 0; }
.quality-agent-advice ol { display: grid; gap: 5px; padding-left: 20px; }
.quality-agent-advice small, .quality-legacy-evidence p { color: var(--muted); }
.quality-legacy-evidence { padding: 10px 12px; border: 1px dashed var(--line); border-radius: 8px; }
.quality-legacy-evidence summary { cursor: pointer; font-weight: 700; }
@media (max-width: 900px) { .quality-operations { grid-template-columns: minmax(0, 1fr); } }
@media (max-width: 700px) { .quality-dialog-form { grid-template-columns: minmax(0, 1fr); } .admin-inline-tools { align-items: stretch; } .admin-inline-tools .button { width: 100%; } .quality-flow { grid-template-columns: repeat(3, minmax(0, 1fr)); } .data-quality-page td.admin-actions { min-width: 154px; } .quality-scan-detail > header { flex-direction: column; } }
</style>
