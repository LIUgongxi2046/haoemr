<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref, watch } from 'vue';
import type { AgentRunBudgetWire } from '../../generated/contracts';
import {
  deactivateAgentRunBudget,
  defineAgentRunBudget,
  getAgentRunBudgetSummary,
  issueAiLease,
  listAgentRunBudgetConsumptions,
  listAgentRunBudgets,
  listMedicalAgentOperationsRuns,
  listMedicalAgentOperationsToolInvocations,
  updateAgentRunBudget,
  type MedicalAgentOperationsRun,
} from '../../api/ai-platform';
import AdminActionDialog from '../components/AdminActionDialog.vue';
import AdminConfirmDialog from '../components/AdminConfirmDialog.vue';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';
import { doctorFacingAiText } from '../medical-ai-terminology';

const leaseQuery = useQuery({
  queryKey: ['ai-platform', 'aiops', 'lease'],
  queryFn: () => issueAiLease('AI_PLATFORM_ADMIN'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const budgetsQuery = useQuery({
  queryKey: ['ai-platform', 'aiops', 'budgets'],
  queryFn: () => listAgentRunBudgets(leaseQuery.data.value!),
  enabled: () => Boolean(leaseQuery.data.value),
  retry: false,
});
const runsQuery = useQuery({
  queryKey: ['ai-platform', 'aiops', 'medical-agent-runs'],
  queryFn: () => listMedicalAgentOperationsRuns(leaseQuery.data.value!, 100),
  enabled: () => Boolean(leaseQuery.data.value),
  retry: false,
  refetchInterval: 15_000,
});

const selectedBudgetId = ref('');
watch(() => budgetsQuery.data.value, (budgets) => {
  if (!budgets?.length) { selectedBudgetId.value = ''; return; }
  if (!budgets.some((budget) => budget.budget_id === selectedBudgetId.value)) {
    selectedBudgetId.value = budgets.find((budget) => budget.status === 'ACTIVE')?.budget_id ?? budgets[0].budget_id;
  }
});

const summaryQuery = useQuery({
  queryKey: ['ai-platform', 'aiops', 'summary', selectedBudgetId],
  queryFn: () => getAgentRunBudgetSummary(leaseQuery.data.value!, selectedBudgetId.value),
  enabled: () => Boolean(leaseQuery.data.value && selectedBudgetId.value),
  retry: false,
});
const consumptionsQuery = useQuery({
  queryKey: ['ai-platform', 'aiops', 'consumptions', selectedBudgetId],
  queryFn: () => listAgentRunBudgetConsumptions(leaseQuery.data.value!, selectedBudgetId.value),
  enabled: () => Boolean(leaseQuery.data.value && selectedBudgetId.value),
  retry: false,
});
const issue = computed(() => (leaseQuery.error.value ?? budgetsQuery.error.value ?? runsQuery.error.value ?? summaryQuery.error.value ?? consumptionsQuery.error.value)
  ? toClinicalIssue(leaseQuery.error.value ?? budgetsQuery.error.value ?? runsQuery.error.value ?? summaryQuery.error.value ?? consumptionsQuery.error.value) : null);
const budgets = computed(() => budgetsQuery.data.value ?? []);
const summary = computed(() => summaryQuery.data.value ?? null);
const consumptions = computed(() => consumptionsQuery.data.value ?? []);
const activeCount = computed(() => budgets.value.filter((budget) => budget.status === 'ACTIVE').length);
const runs = computed(() => runsQuery.data.value ?? []);
const runningCount = computed(() => runs.value.filter((run) => ['QUEUED', 'RUNNING'].includes(run.state)).length);
const attentionCount = computed(() => runs.value.filter((run) => ['PARTIAL', 'BLOCKED', 'FAILED'].includes(run.state)).length);
const successfulCount = computed(() => runs.value.filter((run) => ['COMPLETED', 'WAITING_FOR_REVIEW'].includes(run.state)).length);
const selectedRun = ref<MedicalAgentOperationsRun | null>(null);
const toolInvocationsQuery = useQuery({
  queryKey: ['ai-platform', 'aiops', 'tool-invocations', computed(() => selectedRun.value?.run_id ?? '')],
  queryFn: () => listMedicalAgentOperationsToolInvocations(leaseQuery.data.value!, selectedRun.value!.run_id),
  enabled: () => Boolean(leaseQuery.data.value && selectedRun.value),
  retry: false,
});

const defineForm = reactive({ budgetCode: '', budgetName: '', maxTokens: 1_000_000, maxDurationSeconds: 600 });
const editingBudget = ref<AgentRunBudgetWire | null>(null);
const editorOpen = ref(false);
const deactivateTarget = ref<AgentRunBudgetWire | null>(null);
const busy = ref('');
const notice = ref('');

function formatDate(value: string | null | undefined) {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—';
}
function formatInt(value: number | null | undefined) {
  return value == null ? '—' : new Intl.NumberFormat('zh-CN').format(value);
}

async function reload() {
  notice.value = '';
  await Promise.all([budgetsQuery.refetch(), runsQuery.refetch(), summaryQuery.refetch(), consumptionsQuery.refetch()]);
}

function stateLabel(state: MedicalAgentOperationsRun['state']) {
  return ({ QUEUED: '排队中', RUNNING: '处理中', WAITING_FOR_REVIEW: '待医生确认', COMPLETED: '已完成',
    PARTIAL: '部分完成', BLOCKED: '已阻断', FAILED: '失败', CANCELLED: '已取消' } as const)[state];
}

function stateClass(state: MedicalAgentOperationsRun['state']) {
  if (['COMPLETED', 'WAITING_FOR_REVIEW'].includes(state)) return 'active';
  if (['QUEUED', 'RUNNING'].includes(state)) return 'evaluating';
  return 'inactive';
}

function authorizationLabel(level: MedicalAgentOperationsRun['authorization_level']) {
  return ({ READ_ONLY: '仅查看', STANDARD: '标准诊疗辅助', EXTENDED: '扩展诊疗辅助' } as const)[level];
}

async function defineBudget() {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || !defineForm.budgetCode.trim() || !defineForm.budgetName.trim()) return;
  busy.value = 'create'; notice.value = '';
  try {
    if (editingBudget.value) {
      await updateAgentRunBudget(lease, editingBudget.value, {
        budget_name: defineForm.budgetName.trim(), max_tokens: Math.floor(defineForm.maxTokens),
        max_duration_seconds: Math.floor(defineForm.maxDurationSeconds),
      });
      notice.value = '医助处理额度已更新，后续任务立即按新上限执行。';
    } else {
      await defineAgentRunBudget(lease, {
        budget_code: defineForm.budgetCode.trim(), budget_name: defineForm.budgetName.trim(),
        max_tokens: Math.floor(defineForm.maxTokens), max_duration_seconds: Math.floor(defineForm.maxDurationSeconds),
      });
      notice.value = '医助处理额度已定义，版本记录和操作留痕已同步更新。';
    }
    resetBudgetForm();
    editorOpen.value = false;
    await budgetsQuery.refetch();
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = ''; }
}

function editBudget(budget: AgentRunBudgetWire) {
  editingBudget.value = budget;
  defineForm.budgetCode = budget.budget_code; defineForm.budgetName = doctorFacingAiText(budget.budget_name);
  defineForm.maxTokens = budget.max_tokens; defineForm.maxDurationSeconds = budget.max_duration_seconds;
  notice.value = '';
  editorOpen.value = true;
}

function openCreate() {
  resetBudgetForm();
  editorOpen.value = true;
}

function resetBudgetForm() {
  editingBudget.value = null;
  defineForm.budgetCode = ''; defineForm.budgetName = '';
  defineForm.maxTokens = 1_000_000; defineForm.maxDurationSeconds = 600;
}

async function deactivate(budget: AgentRunBudgetWire) {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || budget.status !== 'ACTIVE') return;
  busy.value = budget.budget_id; notice.value = '';
  try {
    await deactivateAgentRunBudget(lease, budget);
    notice.value = `医助处理额度“${doctorFacingAiText(budget.budget_name)}”已停用。`;
    await budgetsQuery.refetch();
    deactivateTarget.value = null;
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = ''; }
}

</script>

<template>
  <section data-page-root class="content admin-content vue-native-page">
    <div class="page-heading admin-heading">
      <div>
        <p class="eyebrow">AI中心 / 医助运行监测</p>
        <h1>医助运行监测</h1>
        <p>查看真实医助任务、模型调用、工具调用、处理耗时与异常，再维护任务额度；页面每 15 秒自动刷新。</p>
      </div>
      <div class="admin-inline-tools">
        <label class="admin-code-input"><span>处理额度</span>
          <select v-model="selectedBudgetId">
            <option value="" disabled>请选择处理额度</option>
            <option v-for="budget in budgets" :key="budget.budget_id" :value="budget.budget_id">{{ doctorFacingAiText(budget.budget_name) }}（{{ budget.budget_code }}）</option>
          </select>
        </label>
      </div>
    </div>

    <ClinicalPageState v-if="leaseQuery.isPending.value || budgetsQuery.isPending.value || runsQuery.isPending.value" kind="loading" message="正在读取医助运行台账" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reload" />

    <template v-else>
      <section class="admin-metrics" aria-label="医助运行统计">
        <article><span>近期任务</span><strong>{{ runs.length }}</strong><small>当前院区最近 100 条</small></article>
        <article><span>正在处理</span><strong>{{ runningCount }}</strong><small>排队或运行中</small></article>
        <article><span>待确认 / 完成</span><strong>{{ successfulCount }}</strong><small>需医生确认后进入临床记录</small></article>
        <article><span>需关注</span><strong>{{ attentionCount }}</strong><small>部分完成、阻断或失败</small></article>
      </section>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>

      <section class="admin-panel">
        <header>
          <div><h2>真实医助任务台账</h2><p>来源于医疗医助运行库，不包含仿真工作台结果；患者身份不在管理页展示。</p></div>
          <button class="button secondary" @click="runsQuery.refetch()">刷新任务</button>
        </header>
        <div v-if="runs.length === 0" class="admin-empty" role="status">当前院区暂无真实医助任务。医生在 AI医助 EVA 发起任务后，这里会记录模型、工具、耗时与结果状态。</div>
        <div v-else class="admin-table-wrap">
          <table class="admin-table">
            <thead><tr><th>任务 / 医助</th><th>模型</th><th>运行状态</th><th>模型与工具调用</th><th>用量 / 耗时</th><th>发起时间</th><th>明细</th></tr></thead>
            <tbody>
              <tr v-for="run in runs" :key="run.run_id">
                <td><strong>{{ doctorFacingAiText(run.root_agent_name) }}</strong><small><code>…{{ run.run_id.slice(-8) }}</code> · {{ run.requested_stage }} · {{ authorizationLabel(run.authorization_level) }}</small></td>
                <td><strong>{{ run.model_display_name ?? '未解析模型' }}</strong><small>{{ run.provider_code ?? '—' }}<template v-if="run.provider_code && !run.external_processing_approved"> · 缺少外部处理授权</template></small></td>
                <td><span class="admin-status" :class="stateClass(run.state)">{{ stateLabel(run.state) }}</span><small v-if="run.failure_code"><code>{{ run.failure_code }}</code></small></td>
                <td>{{ run.model_request_count }} 次模型 · {{ run.tool_call_count }} 次工具<small v-if="run.tool_failure_count">{{ run.tool_failure_count }} 次工具失败</small></td>
                <td>{{ formatInt(run.model_total_tokens) }} tokens<small>{{ formatInt(run.actual_duration_ms) }} ms</small></td>
                <td>{{ formatDate(run.created_at) }}<small>第 {{ run.attempt }}/{{ run.max_attempts }} 次尝试</small></td>
                <td><button class="task-action" @click="selectedRun = run">工具明细</button></td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <AdminActionDialog :open="Boolean(selectedRun)" title="任务工具调用明细" description="仅展示工具代码、调用结果和耗时；临床数据内容不会在平台管理页展开。" size="large" @update:open="!$event && (selectedRun = null)">
        <div v-if="toolInvocationsQuery.isPending.value" class="admin-empty" role="status">正在读取工具调用台账…</div>
        <div v-else-if="toolInvocationsQuery.error.value" class="admin-empty" role="alert">{{ toClinicalIssue(toolInvocationsQuery.error.value).message }}</div>
        <div v-else-if="(toolInvocationsQuery.data.value ?? []).length === 0" class="admin-empty" role="status">该任务尚无工具调用记录。</div>
        <div v-else class="admin-table-wrap">
          <table class="admin-table">
            <thead><tr><th>工具</th><th>结果</th><th>返回项</th><th>耗时</th><th>调用时间</th></tr></thead>
            <tbody><tr v-for="item in toolInvocationsQuery.data.value" :key="item.invocation_id">
              <td><strong>{{ item.tool_code }}</strong><small>版本 {{ item.tool_version }} · …{{ item.child_run_id.slice(-8) }}</small></td>
              <td><span class="admin-status" :class="item.outcome === 'SUCCEEDED' ? 'active' : 'inactive'">{{ item.outcome === 'SUCCEEDED' ? '成功' : item.outcome === 'DENIED' ? '已拒绝' : '失败' }}</span><small v-if="item.error_code"><code>{{ item.error_code }}</code></small></td>
              <td>{{ item.item_count }}</td><td>{{ formatInt(item.duration_ms) }} ms</td><td>{{ formatDate(item.invoked_at) }}</td>
            </tr></tbody>
          </table>
        </div>
      </AdminActionDialog>

      <div>
        <section class="admin-panel">
          <header>
            <div><h2>处理额度台账</h2><p>编码不可变；停用后保留历史记录。</p></div>
            <div class="admin-row-actions"><button class="button secondary" @click="budgetsQuery.refetch()">刷新</button><button class="button primary" @click="openCreate">新建处理额度</button></div>
          </header>
          <div v-if="budgets.length === 0" class="admin-empty" role="status">暂无处理额度方案，请点击“新建处理额度”。</div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>名称 / 编码</th><th>最大生成额度</th><th>最长处理时间</th><th>状态</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="budget in budgets" :key="budget.budget_id">
                  <td><strong>{{ doctorFacingAiText(budget.budget_name) }}</strong><small><code>{{ budget.budget_code }}</code> · …{{ budget.budget_id.slice(-8) }}</small></td>
                  <td>{{ formatInt(budget.max_tokens) }}</td>
                  <td>{{ formatInt(budget.max_duration_seconds) }} 秒</td>
                  <td><span class="admin-status" :class="budget.status.toLowerCase()">{{ budget.status === 'ACTIVE' ? '有效' : '已停用' }}</span></td>
                  <td><div class="admin-row-actions"><button class="task-action" :disabled="budget.status !== 'ACTIVE' || Boolean(busy)" @click="editBudget(budget)">编辑</button><button class="task-action danger" :disabled="budget.status !== 'ACTIVE' || Boolean(busy)" @click="deactivateTarget = budget">删除</button></div></td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

      </div>

      <AdminActionDialog v-model:open="editorOpen" :title="editingBudget ? '编辑处理额度' : '新建处理额度'" description="额度编码必须为 BUDGET_ 加医助编码；服务端会在任务、工具和模型阶段执行硬门，超限任务会阻断而不是重试。" size="large" :busy="Boolean(busy)" @update:open="!$event && resetBudgetForm()">
        <form class="admin-form ai-center-dialog-form" @submit.prevent="defineBudget">
            <label><span>方案编码</span><input v-model="defineForm.budgetCode" maxlength="128" pattern="BUDGET_[A-Z0-9_]+" required :disabled="Boolean(editingBudget)" placeholder="例：BUDGET_DOCUMENT_DRAFTER" /><small>与医助编码一一对应，编码不可变。</small></label>
            <label><span>方案名称</span><input v-model="defineForm.budgetName" maxlength="256" required placeholder="例：门诊标准额度" /></label>
            <label><span>最大生成额度</span><input v-model.number="defineForm.maxTokens" type="number" min="0" step="1" required /></label>
            <label><span>最长处理时间（秒）</span><input v-model.number="defineForm.maxDurationSeconds" type="number" min="0" step="1" required /></label>
            <div class="admin-form-actions"><button class="button secondary" type="button" :disabled="Boolean(busy)" @click="editorOpen = false">取消</button><button class="button primary" :disabled="Boolean(busy)">{{ busy === 'create' ? '正在保存…' : editingBudget ? '保存额度变更' : '定义并生效' }}</button></div>
          </form>
      </AdminActionDialog>
      <AdminConfirmDialog :open="Boolean(deactivateTarget)" :title="`删除处理额度 ${doctorFacingAiText(deactivateTarget?.budget_name ?? '')}`" description="删除将以安全停用方式执行；该额度不再约束新任务，历史用量和审计记录继续保留。" confirm-label="确认删除并停用" :busy="Boolean(busy)" @update:open="!$event && (deactivateTarget = null)" @confirm="deactivateTarget && deactivate(deactivateTarget)"><div v-if="deactivateTarget" class="admin-impact-grid"><div><span>方案编码</span><b>{{ deactivateTarget.budget_code }}</b></div><div><span>最大生成额度</span><b>{{ formatInt(deactivateTarget.max_tokens) }}</b></div><div><span>最长处理时间</span><b>{{ deactivateTarget.max_duration_seconds }} 秒</b></div><div><span>流程影响</span><b>停止新任务引用</b></div></div></AdminConfirmDialog>

      <div class="admin-layout">
        <section class="admin-panel">
          <header>
            <div><h2>任务用量总览</h2><p>对比当前额度方案的累计用量与上限。</p></div>
            <button class="button secondary" :disabled="!selectedBudgetId" @click="summaryQuery.refetch()">刷新</button>
          </header>
          <div v-if="!selectedBudgetId" class="admin-empty" role="status">请先选择处理额度方案。</div>
          <div v-else-if="summaryQuery.isPending.value" class="admin-empty" role="status">正在计算任务用量…</div>
          <div v-else-if="summary" class="admin-metrics" aria-label="任务用量总览">
            <article><span>已用生成额度</span><strong>{{ formatInt(summary.total_tokens) }}</strong><small>上限 {{ formatInt(summary.max_tokens) }}</small></article>
            <article><span>累计处理时间</span><strong>{{ formatInt(summary.total_duration_seconds) }} 秒</strong><small>上限 {{ formatInt(summary.max_duration_seconds) }} 秒</small></article>
          </div>
        </section>
      </div>

      <div class="admin-layout">
        <section class="admin-panel">
          <header>
            <div><h2>任务用量明细</h2><p>记录每次医助任务的生成额度和处理时间。</p></div>
            <button class="button secondary" :disabled="!selectedBudgetId" @click="consumptionsQuery.refetch()">刷新</button>
          </header>
          <div v-if="!selectedBudgetId" class="admin-empty" role="status">请先选择处理额度方案。</div>
          <div v-else-if="consumptions.length === 0" class="admin-empty" role="status">该方案暂无实际任务用量；医助任务完成后会自动记录。</div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>任务编号</th><th>生成额度</th><th>处理时间</th><th>记录时间</th></tr></thead>
              <tbody>
                <tr v-for="consumption in consumptions" :key="consumption.consumption_id">
                  <td><code>{{ consumption.run_id }}</code></td>
                  <td>{{ formatInt(consumption.tokens_consumed) }}</td>
                  <td>{{ formatInt(consumption.duration_seconds) }} 秒</td>
                  <td>{{ formatDate(consumption.recorded_at) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <aside class="admin-panel aiops-flow-note"><header><div><h2>自动用量采集</h2><p>每次医助任务完成后，系统按实际运行记录任务编号、生成额度和处理时间，无需人工补录。</p></div><span class="admin-status active">已联动</span></header></aside>
      </div>
    </template>
  </section>
</template>
