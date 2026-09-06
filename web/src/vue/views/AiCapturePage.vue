<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, ref } from 'vue';
import { issueAuditLease, listAuditEvents } from '../../api/audit';
import {
  issueAiLease,
  listMedicalAgentOperationsRuns,
  listMedicalAgentOperationsToolInvocations,
  type MedicalAgentOperationsRun,
} from '../../api/ai-platform';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';
import { doctorFacingAiText } from '../medical-ai-terminology';

const operationsLeaseQuery = useQuery({
  queryKey: ['ai-capture', 'operations-lease'],
  queryFn: () => issueAiLease('AI_PLATFORM_ADMIN'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const runsQuery = useQuery({
  queryKey: ['ai-capture', 'runs'],
  queryFn: () => listMedicalAgentOperationsRuns(operationsLeaseQuery.data.value!, 200),
  enabled: () => Boolean(operationsLeaseQuery.data.value), retry: false, refetchInterval: 15_000,
});
const auditLeaseQuery = useQuery({
  queryKey: ['ai-capture', 'audit-lease'], queryFn: issueAuditLease,
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const auditQuery = useQuery({
  queryKey: ['ai-capture', 'audit-events'],
  queryFn: () => listAuditEvents(auditLeaseQuery.data.value!),
  enabled: () => Boolean(auditLeaseQuery.data.value), retry: false, refetchInterval: 30_000,
});

const runs = computed(() => runsQuery.data.value ?? []);
const aiAuditEvents = computed(() => (auditQuery.data.value ?? []).filter((event) =>
  /^(MEDICAL_AGENT|MODEL_|AGENT_|TOOL_|SKILL_|AI_)/.test(event.action_code)
  || /^(MEDICAL_AGENT|MODEL_|AGENT_|TOOL_|SKILL_|AI_)/.test(event.resource_type)));
const modelRequests = computed(() => runs.value.reduce((sum, run) => sum + run.model_request_count, 0));
const toolCalls = computed(() => runs.value.reduce((sum, run) => sum + run.tool_call_count, 0));
const failedRuns = computed(() => runs.value.filter((run) => ['PARTIAL', 'BLOCKED', 'FAILED'].includes(run.state)).length);
const selectedRun = ref<MedicalAgentOperationsRun | null>(null);
const toolsQuery = useQuery({
  queryKey: ['ai-capture', 'tools', computed(() => selectedRun.value?.run_id ?? '')],
  queryFn: () => listMedicalAgentOperationsToolInvocations(operationsLeaseQuery.data.value!, selectedRun.value!.run_id),
  enabled: () => Boolean(operationsLeaseQuery.data.value && selectedRun.value), retry: false,
});
const coreIssue = computed(() => operationsLeaseQuery.error.value ?? runsQuery.error.value);
const auditIssue = computed(() => auditLeaseQuery.error.value ?? auditQuery.error.value);

function formatDate(value: string | null | undefined) {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'medium', hour12: false }).format(new Date(value)) : '—';
}
function shortId(value: string) { return `…${value.slice(-8)}`; }
function stateLabel(state: string) {
  return ({ QUEUED: '排队中', RUNNING: '处理中', WAITING_FOR_REVIEW: '待医生确认', COMPLETED: '已完成', PARTIAL: '部分完成', BLOCKED: '已阻断', FAILED: '失败', CANCELLED: '已取消' } as Record<string, string>)[state] ?? state;
}
</script>

<template>
  <section data-page-root class="content admin-content vue-native-page ai-evidence-capture">
    <div class="page-heading admin-heading">
      <div><p class="eyebrow">AI中心 / 运行证据</p><h1>医助运行证据采集</h1><p>从真实医助任务、模型请求、工具调用和防篡改审计链持续采集运行证据；不生成前端模拟记录。</p></div>
      <button class="button secondary" type="button" @click="runsQuery.refetch(); auditQuery.refetch()">刷新证据</button>
    </div>

    <ClinicalPageState v-if="operationsLeaseQuery.isPending.value || runsQuery.isPending.value" kind="loading" message="正在读取医助运行证据" />
    <ClinicalPageState v-else-if="coreIssue" kind="error" :code="toClinicalIssue(coreIssue).code" :message="toClinicalIssue(coreIssue).message" @retry="runsQuery.refetch()" />
    <template v-else>
      <section class="admin-metrics" aria-label="运行证据统计">
        <article><span>真实医助任务</span><strong>{{ runs.length }}</strong><small>最近 200 条</small></article>
        <article><span>模型请求</span><strong>{{ modelRequests }}</strong><small>服务端运行记录</small></article>
        <article><span>工具调用</span><strong>{{ toolCalls }}</strong><small>逐次留痕</small></article>
        <article><span>需关注任务</span><strong>{{ failedRuns }}</strong><small>部分完成、阻断或失败</small></article>
      </section>

      <div class="admin-layout ai-evidence-grid">
        <section class="admin-panel">
          <header><div><h2>任务证据台账</h2><p>患者身份不在管理台展示；可下钻查看每次工具调用结果。</p></div></header>
          <div v-if="!runs.length" class="admin-empty">暂无真实医助运行记录。</div>
          <div v-else class="admin-table-wrap"><table class="admin-table"><thead><tr><th>任务</th><th>医助</th><th>模型</th><th>调用</th><th>状态</th><th>发生时间</th><th>证据</th></tr></thead><tbody>
            <tr v-for="run in runs" :key="run.run_id">
              <td><code>{{ shortId(run.run_id) }}</code><small>{{ run.requested_stage }}</small></td>
              <td>{{ doctorFacingAiText(run.root_agent_name) }}</td>
              <td>{{ run.model_display_name ?? '未发起模型请求' }}<small v-if="run.provider_code">{{ run.provider_code }}</small></td>
              <td>{{ run.model_request_count }} 次模型 / {{ run.tool_call_count }} 次工具<small>{{ run.model_total_tokens }} tokens · {{ run.actual_duration_ms }} ms</small></td>
              <td><span class="admin-status" :class="['FAILED','BLOCKED'].includes(run.state) ? 'rejected' : run.state === 'COMPLETED' ? 'active' : 'evaluating'">{{ stateLabel(run.state) }}</span><small v-if="run.failure_code">{{ run.failure_code }}</small></td>
              <td>{{ formatDate(run.created_at) }}</td>
              <td><button class="task-action" type="button" @click="selectedRun = run">查看工具链</button></td>
            </tr>
          </tbody></table></div>
        </section>

        <aside class="admin-panel ai-audit-panel">
          <header><div><h2>AI 审计事件链</h2><p>来自数据库审计事件，不含患者明文。</p></div></header>
          <p v-if="auditIssue" class="admin-notice">{{ toClinicalIssue(auditIssue).code }}：当前岗位无审计读取权限，任务证据采集不受影响。</p>
          <div v-else-if="auditLeaseQuery.isPending.value || auditQuery.isPending.value" class="admin-empty">正在读取审计链…</div>
          <div v-else-if="!aiAuditEvents.length" class="admin-empty">暂无 AI 相关审计事件。</div>
          <div v-else class="ai-audit-list"><article v-for="event in aiAuditEvents.slice(0, 30)" :key="event.audit_event_id"><b>{{ event.action_code }}</b><span>{{ event.resource_type }} · {{ shortId(event.resource_id) }}</span><small>{{ formatDate(event.occurred_at) }} · {{ event.event_hash.slice(0, 12) }}…</small></article></div>
        </aside>
      </div>

      <div v-if="selectedRun" class="ai-evidence-modal" role="dialog" aria-modal="true" aria-label="医助工具调用证据">
        <section class="admin-panel"><header><div><h2>工具调用证据 {{ shortId(selectedRun.run_id) }}</h2><p>每次调用的版本、结果、耗时和失败码均由服务端记录。</p></div><button class="button secondary" type="button" @click="selectedRun = null">关闭</button></header>
          <div v-if="toolsQuery.isPending.value" class="admin-empty">正在读取工具调用…</div>
          <p v-else-if="toolsQuery.error.value" class="admin-notice">{{ toClinicalIssue(toolsQuery.error.value).code }}：{{ toClinicalIssue(toolsQuery.error.value).message }}</p>
          <div v-else-if="!toolsQuery.data.value?.length" class="admin-empty">该任务未调用工具。</div>
          <div v-else class="admin-table-wrap"><table class="admin-table"><thead><tr><th>工具</th><th>版本</th><th>结果</th><th>数据项</th><th>耗时</th><th>失败码</th></tr></thead><tbody><tr v-for="item in toolsQuery.data.value" :key="item.invocation_id"><td><code>{{ item.tool_code }}</code></td><td>{{ item.tool_version }}</td><td>{{ item.outcome }}</td><td>{{ item.item_count }}</td><td>{{ item.duration_ms }} ms</td><td>{{ item.error_code ?? '—' }}</td></tr></tbody></table></div>
        </section>
      </div>
    </template>
  </section>
</template>

<style scoped>
.ai-evidence-grid{grid-template-columns:minmax(0,2fr) minmax(260px,.8fr)}.ai-audit-panel{min-width:0}.ai-audit-list{display:grid;gap:8px;max-height:620px;padding:14px 16px;overflow:auto}.ai-audit-list article{display:grid;gap:3px;padding:9px;border:1px solid #dce5ec;border-radius:8px;min-width:0}.ai-audit-list b,.ai-audit-list span,.ai-audit-list small{overflow-wrap:anywhere}.ai-audit-list b{font-size:11px}.ai-audit-list span,.ai-audit-list small{color:#607489;font-size:9px}.ai-evidence-modal{position:fixed;z-index:1200;inset:0;display:grid;place-items:center;padding:24px;background:rgb(16 30 45 / 45%)}.ai-evidence-modal>.admin-panel{width:min(900px,100%);max-height:calc(100vh - 48px);overflow:auto}.admin-table td small{display:block;margin-top:3px;color:#68798b;overflow-wrap:anywhere}@media(max-width:980px){.ai-evidence-grid{grid-template-columns:1fr}}@media(max-width:640px){.ai-evidence-modal{padding:10px}.page-heading{align-items:flex-start;flex-direction:column}}
</style>
