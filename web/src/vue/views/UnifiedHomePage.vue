<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed } from 'vue';
import { issueContextLease, issueWardLease, listClinicalTaskTeamQueue, loadInpatientWorklist } from '../../clinical-api';
import { issueEmergencyFacilityLease, listWaitingQueue } from '../../api/emergency';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';

const wardDashboard = useQuery({
  queryKey: ['unified-home', 'ward-worklist'],
  queryFn: async () => loadInpatientWorklist(await issueWardLease()),
  retry: false, staleTime: 0, gcTime: 0,
});
const teamTaskDashboard = useQuery({
  queryKey: ['unified-home', 'team-task-queue'],
  queryFn: async () => listClinicalTaskTeamQueue(await issueContextLease(null, null, 'CLINICAL_TASK_WORKFLOW')),
  retry: false, staleTime: 0, gcTime: 0,
});
const outpatientDashboard = useQuery({
  queryKey: ['unified-home', 'outpatient-queue'],
  queryFn: async () => listWaitingQueue(await issueEmergencyFacilityLease('UNIFIED_HOME_OUTPATIENT')),
  retry: false, staleTime: 0, gcTime: 0,
});

const pending = computed(() => [wardDashboard, teamTaskDashboard, outpatientDashboard].some((query) => query.isPending.value));
const wardIssue = computed(() => wardDashboard.error.value ? toClinicalIssue(wardDashboard.error.value) : null);
const teamIssue = computed(() => teamTaskDashboard.error.value ? toClinicalIssue(teamTaskDashboard.error.value) : null);
const outpatientIssue = computed(() => outpatientDashboard.error.value ? toClinicalIssue(outpatientDashboard.error.value) : null);
const failedSourceCount = computed(() => [wardIssue.value, teamIssue.value, outpatientIssue.value].filter(Boolean).length);
const allSourcesFailed = computed(() => failedSourceCount.value === 3);
const worklist = computed(() => wardDashboard.data.value ?? []);
const teamQueue = computed(() => teamTaskDashboard.data.value ?? []);
const outpatientQueue = computed(() => outpatientDashboard.data.value ?? []);
const enqueuedTasks = computed(() => teamQueue.value.filter((item) => item.queue_status === 'ENQUEUED').length);
const claimedTasks = computed(() => teamQueue.value.filter((item) => item.queue_status === 'CLAIMED').length);
const overdueDocuments = computed(() => worklist.value.reduce((sum, item) => sum + item.overdue_task_count, 0));
const pendingDocuments = computed(() => worklist.value.reduce((sum, item) => sum + item.pending_task_count, 0));
const waitingOutpatients = computed(() => outpatientQueue.value.filter((item) => item.status === 'WAITING').length);
const summaryCount = computed(() => enqueuedTasks.value + claimedTasks.value + overdueDocuments.value);
const facts = computed(() => [
  { label: '科室待认领', value: teamTaskDashboard.isPending.value ? '…' : teamIssue.value ? '—' : enqueuedTasks.value, detail: teamIssue.value ? `当前岗位无团队队列读取范围（${teamIssue.value.code}），未计入汇总` : '当前登录人员具备成员资格的团队队列', unavailable: Boolean(teamIssue.value), to: '/clinical-tasks?view=team' },
  { label: '已领取协作', value: teamTaskDashboard.isPending.value ? '…' : teamIssue.value ? '—' : claimedTasks.value, detail: teamIssue.value ? `当前岗位无团队队列读取范围（${teamIssue.value.code}），未计入汇总` : '团队队列中已领取、尚未完成的临床协作项', unavailable: Boolean(teamIssue.value), to: '/clinical-tasks?view=team' },
  { label: '病区逾期文书', value: wardDashboard.isPending.value ? '…' : wardIssue.value ? '—' : overdueDocuments.value, detail: wardIssue.value ? `当前岗位无病区工作列表范围（${wardIssue.value.code}），未计入汇总` : '当前岗位授权病区工作列表中的逾期任务', unavailable: Boolean(wardIssue.value), to: '/inpatient' },
  { label: '病区待完成', value: wardDashboard.isPending.value ? '…' : wardIssue.value ? '—' : pendingDocuments.value, detail: wardIssue.value ? `当前岗位无病区工作列表范围（${wardIssue.value.code}），未计入汇总` : '当前岗位授权病区的未终态文书任务', unavailable: Boolean(wardIssue.value), to: '/inpatient' },
  { label: '当日门诊候诊', value: outpatientDashboard.isPending.value ? '…' : outpatientIssue.value ? '—' : waitingOutpatients.value, detail: outpatientIssue.value ? `当前岗位无候诊队列读取范围（${outpatientIssue.value.code}），未计入汇总` : '当前院区候诊队列的 WAITING 事实数量', unavailable: Boolean(outpatientIssue.value), to: '/outpatient' },
  { label: '病区在院患者', value: wardDashboard.isPending.value ? '…' : wardIssue.value ? '—' : worklist.value.length, detail: wardIssue.value ? `当前岗位无病区工作列表范围（${wardIssue.value.code}），未计入汇总` : '当前岗位授权病区工作列表中的住院患者', unavailable: Boolean(wardIssue.value), to: '/inpatient' },
]);
const summaryValue = computed(() => pending.value ? '…' : allSourcesFailed.value ? '—' : String(summaryCount.value));
function retry() { void wardDashboard.refetch(); void teamTaskDashboard.refetch(); void outpatientDashboard.refetch(); }
</script>

<template>
  <section data-page-root class="content vue-native-page unified-home-page">
    <div class="page-head"><div class="page-title"><h1>统一首页与任务中心</h1><p>按当前登录人员的团队成员资格、病区权限和院区上下文聚合待办；进入来源页面后仍单独核验。</p></div></div>
    <div class="portal-safety"><b>统一入口</b><span>首页仅聚合已授权的任务与工作量事实；不赋予额外患者、就诊或业务写入权限。</span><span class="status" :class="allSourcesFailed ? 'red' : pending || failedSourceCount ? 'amber' : 'green'">{{ allSourcesFailed ? '聚合服务不可用' : pending ? '正在刷新' : failedSourceCount ? `部分来源无读取范围（${failedSourceCount}）` : '聚合数据已加载' }}</span></div>
    <div class="hub-hero"><div><span>统一首页</span><h2>统一入口，不聚合权限</h2><p>识别当前岗位的团队任务、文书时限和候诊负载；任务终态仍由来源业务事实确认。</p></div><div class="hub-score"><b>{{ summaryValue }}</b><span>当前高优先级待处理</span></div></div>
    <ClinicalPageState v-if="allSourcesFailed" kind="error" code="UNIFIED_HOME_AGGREGATION_UNAVAILABLE" message="无法读取当前岗位的任何聚合数据。页面不使用旧值或写死数字回退。" @retry="retry" />
    <section v-else class="unified-home-facts" aria-label="当前岗位聚合待办">
      <RouterLink v-for="fact in facts" :key="fact.label" :to="fact.to" class="unified-home-fact" :class="{ unavailable: fact.unavailable }"><span>{{ fact.label }}</span><b>{{ fact.value }}</b><small>{{ fact.detail }}</small></RouterLink>
    </section>
    <div class="hub-module-grid">
      <RouterLink class="hub-module" to="/clinical-tasks"><span>☑</span><b>统一临床任务</b><p>危急值、会诊、审签、时限与路径变异</p><i>进入 →</i></RouterLink>
      <RouterLink class="hub-module" to="/clinical"><span>⌂</span><b>临床业务门户</b><p>门诊、急诊、住院三个独立工作域</p><i>进入 →</i></RouterLink>
      <RouterLink class="hub-module" to="/outpatient"><span>◫</span><b>门诊工作台</b><p>当日门诊与病历、医嘱、结果</p><i>进入 →</i></RouterLink>
      <RouterLink class="hub-module" to="/emergency"><span>✚</span><b>急诊工作台</b><p>预检分诊、抢救留观与去向闭环</p><i>进入 →</i></RouterLink>
      <RouterLink class="hub-module" to="/inpatient"><span>▥</span><b>住院工作站</b><p>病区患者、文书任务与出院闭环</p><i>进入 →</i></RouterLink>
      <RouterLink class="hub-module" to="/ward"><span>▦</span><b>病区护理工作台</b><p>交接班与患者级交接清单</p><i>进入 →</i></RouterLink>
    </div>
  </section>
</template>

<style scoped>
.unified-home-facts {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin: 0 0 14px;
}
.unified-home-fact {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 5px 10px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
  text-decoration: none;
}
.unified-home-fact:hover { border-color: #86b2e5; background: #fbfdff; }
.unified-home-fact.unavailable { border-style: dashed; background: #fafafa; }
.unified-home-fact span { color: var(--muted); font-size: 11px; }
.unified-home-fact b { color: var(--ink); font-size: 20px; }
.unified-home-fact small { grid-column: 1 / -1; color: var(--muted); font-size: 10px; line-height: 1.45; }
@media (max-width: 900px) { .unified-home-facts { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 560px) { .unified-home-facts { grid-template-columns: 1fr; } }
</style>
