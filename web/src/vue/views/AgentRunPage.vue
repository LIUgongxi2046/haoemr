<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed } from 'vue';
import type { AIRunSnapshotWire } from '../../generated/contracts';
import { issueAiLease, listAiRuns } from '../../api/ai-platform';
import { toClinicalIssue } from '../clinical-error';

const leaseQuery = useQuery({
  queryKey: ['ai', 'runs', 'lease'],
  queryFn: () => issueAiLease('AI_RUN_GOVERNANCE'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const runsQuery = useQuery({
  queryKey: ['ai', 'runs'],
  queryFn: () => listAiRuns(leaseQuery.data.value!),
  enabled: () => Boolean(leaseQuery.data.value), retry: false,
});
const issue = computed(() => (leaseQuery.error.value ?? runsQuery.error.value)
  ? toClinicalIssue(leaseQuery.error.value ?? runsQuery.error.value) : null);
const runs = computed(() => runsQuery.data.value ?? []);

const terminal = computed(() => runs.value.filter((r) => ['COMPLETED', 'FAILED', 'REJECTED', 'EXPIRED', 'CANCELLED', 'BLOCKED'].includes(r.state)));
const waiting = computed(() => runs.value.filter((r) => r.state === 'WAITING_APPROVAL'));
const active = computed(() => runs.value.filter((r) => !['COMPLETED', 'FAILED', 'REJECTED', 'EXPIRED', 'CANCELLED', 'BLOCKED'].includes(r.state)));

function stateLabel(value: AIRunSnapshotWire['state']) {
  return ({ CREATED: '已创建', ROUTING: '路由中', RETRIEVING: '检索中', PLANNING: '规划中', WAITING_APPROVAL: '待审批', GENERATING: '生成中', VERIFYING: '核验中', READY_FOR_REVIEW: '待审阅', ACCEPTED: '已接受', REJECTED: '已驳回', EXPIRED: '已过期', RETRYING: '重试中', DEGRADED: '已降级', RECONCILING: '对账中', COMPLETED: '已完成', FAILED: '失败', BLOCKED: '阻断', CANCELLED: '已取消' } as Record<string, string>)[value] ?? value;
}
function stateClass(value: AIRunSnapshotWire['state']) {
  if (['COMPLETED', 'ACCEPTED'].includes(value)) return 'green';
  if (['FAILED', 'REJECTED', 'BLOCKED', 'EXPIRED'].includes(value)) return 'danger';
  if (['WAITING_APPROVAL', 'READY_FOR_REVIEW'].includes(value)) return 'amber';
  return 'muted';
}
function formatDate(value: string | null | undefined) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—';
}
</script>

<template>
  <section data-page-root class="content vue-native-page">
    <div class="page-head">
      <div class="page-title"><h1>医助任务运行</h1><p>集中查看医助任务的处理进度、医生确认状态、版本和任务记录</p></div>
      <div class="head-actions"><button class="btn" type="button" @click="runsQuery.refetch()">刷新</button></div>
    </div>

    <div v-if="leaseQuery.isPending.value || runsQuery.isPending.value" class="card"><div class="card-body">正在读取医助任务…</div></div>
    <div v-else-if="issue" class="card"><div class="card-body">加载失败：{{ issue.code }} {{ issue.message }}</div></div>

    <template v-else>
      <div class="metric-grid" aria-label="医助任务概览">
        <div class="metric"><div class="name">任务总数</div><div class="value">{{ runs.length }}</div><div class="trend">最近 500 条</div></div>
        <div class="metric"><div class="name">处理中</div><div class="value">{{ active.length }}</div><div class="trend">尚未结束的任务</div></div>
        <div class="metric"><div class="name">待审批</div><div class="value">{{ waiting.length }}</div><div class="trend">需人工批准</div></div>
        <div class="metric"><div class="name">已结束</div><div class="value">{{ terminal.length }}</div><div class="trend">完成、失败、驳回或过期</div></div>
      </div>

      <div class="admin-layout">
        <section class="admin-panel">
          <header><div><h2>医助任务列表</h2><p>按任务创建时间倒序展示。</p></div></header>
          <div v-if="runs.length === 0" class="empty-state"><span>医</span><p>暂无医助任务</p><small>通过 AI医助 EVA 或医助团队发起任务后在此查看</small></div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>任务编号</th><th>状态</th><th>处理序号</th><th>数据版本</th><th>更新时间</th></tr></thead>
              <tbody>
                <tr v-for="run in runs" :key="run.run_id">
                  <td><code>…{{ run.run_id.slice(-8) }}</code></td>
                  <td><span class="admin-status" :class="stateClass(run.state)">{{ stateLabel(run.state) }}</span></td>
                  <td>{{ run.sequence }}</td>
                  <td><code>{{ run.data_watermark.slice(0, 10) }}…</code></td>
                  <td>{{ formatDate(run.updated_at) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <aside class="admin-panel">
          <header><div><h2>任务运行保障</h2></div></header>
          <div class="card-body">
            <div class="folder-row">诊疗范围<span>限定当前患者与就诊</span></div>
            <div class="folder-row">医生确认<span>临床写入操作需批准</span></div>
            <div class="folder-row">处理上限<span>生成额度与响应时长</span></div>
            <div class="folder-row">结果依据<span>来源可回看、操作可追溯</span></div>
            <RouterLink class="btn" style="width:100%;margin-top:12px" to="/agent-catalog">打开医助团队</RouterLink>
            <RouterLink class="btn" style="width:100%;margin-top:8px" to="/aiops">运行监测</RouterLink>
          </div>
        </aside>
      </div>
    </template>
  </section>
</template>
