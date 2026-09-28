<script setup lang="ts">
import { useRoute } from 'vue-router';
import { useQuery } from '@tanstack/vue-query';
import { computed, ref, watch } from 'vue';
import type { AuditEventWire } from '../../generated/contracts';
import { issueAuditLease, listAuditEvents } from '../../api/audit';
import { defineConfiguration, issueConfigurationLease } from '../../api/config';
import { loadWorkforceIdentities } from '../../clinical-api';
import { analyzeRoleGovernance } from '../../api/system-administration';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import AdminDataPager from '../components/AdminDataPager.vue';
import AdminActionDialog from '../components/AdminActionDialog.vue';
import { adminCodeLabel, auditActionOptions, auditResourceOptions } from '../admin-display';
import { toClinicalIssue } from '../clinical-error';

const route = useRoute();
const actionCode = ref(typeof route.query.action_code === 'string' ? route.query.action_code : '');
const resourceType = ref(typeof route.query.resource_type === 'string' ? route.query.resource_type : '');
const resourceId = computed(() => typeof route.query.resource_id === 'string' ? route.query.resource_id : undefined);

const leaseQuery = useQuery({
  queryKey: ['audit', 'lease'],
  queryFn: () => issueAuditLease(),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const eventsQuery = useQuery({
  queryKey: ['audit', 'events', actionCode, resourceType, resourceId],
  queryFn: () => listAuditEvents(leaseQuery.data.value!, {
    action_code: actionCode.value.trim() || undefined,
    resource_type: resourceType.value.trim() || undefined,
    resource_id: resourceId.value,
  }),
  enabled: () => Boolean(leaseQuery.data.value), retry: false,
});
const workforceQuery = useQuery({ queryKey: ['admin', 'audit', 'workforce-review'], queryFn: loadWorkforceIdentities, retry: false, staleTime: 0, gcTime: 0 });
const configLeaseQuery = useQuery({ queryKey: ['admin', 'audit', 'config-lease'], queryFn: issueConfigurationLease, retry: false, staleTime: 5 * 60_000, gcTime: 0 });
const issue = computed(() => (leaseQuery.error.value ?? eventsQuery.error.value)
  ? toClinicalIssue(leaseQuery.error.value ?? eventsQuery.error.value) : null);
const events = computed(() => eventsQuery.data.value ?? []);
const highRiskCount = computed(() => events.value.filter((event) => /FAILED|BLOCKED|DISABLED|DEACTIVATED|REJECTED/.test(event.action_code)).length);
const uniqueActors = computed(() => new Set(events.value.map((event) => event.actor_user_id).filter(Boolean)).size);
const roleGovernance = computed(() => analyzeRoleGovernance(workforceQuery.data.value ?? []));
const privilegedMembers = computed(() => new Set((workforceQuery.data.value ?? []).filter((item) => item.role_status === 'ACTIVE' && ['SYSTEM_ADMIN', 'SECURITY_AUDITOR', 'AUTHORIZATION_ADMIN', 'CONFIG_APPROVER'].includes(item.role_code ?? '')).map((item) => item.person_id)).size);
const reviewNotice = ref('');
const reviewBusy = ref(false);
const reviewOpen = ref(false);
const page = ref(1);
const pageSize = 20;
const pagedEvents = computed(() => events.value.slice((page.value - 1) * pageSize, page.value * pageSize));
watch([actionCode, resourceType], () => { page.value = 1; });

function formatDate(value: string | null | undefined) {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short', hour12: false }).format(new Date(value)) : '—';
}
function shortId(value: string | null | undefined) {
  return value ? `…${value.slice(-8)}` : '—';
}
function actionLabel(value: AuditEventWire['action_code']) { return adminCodeLabel(value, '管理操作'); }
function exportEvidence() {
  const blob = new Blob([JSON.stringify({ exported_at: new Date().toISOString(), events: events.value }, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob); const anchor = document.createElement('a');
  anchor.href = url; anchor.download = `haoemr-admin-audit-${new Date().toISOString().slice(0, 10)}.json`; anchor.click(); URL.revokeObjectURL(url);
}
async function createReviewTask() {
  const lease = configLeaseQuery.data.value;
  if (!lease || reviewBusy.value) return;
  reviewBusy.value = true; reviewNotice.value = '';
  try {
    const suffix = new Date().toISOString().replace(/[-:TZ.]/g, '').slice(0, 14);
    await defineConfiguration(lease, { config_type: 'JOB', config_key: `permission-quarterly-review-${suffix}`, display_name: `权限季度复核 ${new Date().toLocaleDateString('zh-CN')}`, payload: { schema_version: 1, schedule: 'MANUAL', batch_size: privilegedMembers.value, retry_policy: '逐人复核失败项可重试，已确认项不重复', reconciliation_rule: `高权成员 ${privilegedMembers.value} 人，互斥职责冲突 ${roleGovernance.value.conflicts.length} 人`, description: '系统管理审计页创建的权限季度复核任务' } });
    reviewNotice.value = '权限复核任务已写入数据库草稿，可在“通知任务”中继续校验、审批和发布。'; reviewOpen.value = false;
  } catch (error) { const next = toClinicalIssue(error); reviewNotice.value = `${next.code}：${next.message}`; }
  finally { reviewBusy.value = false; }
}
</script>

<template>
  <section data-page-root class="content vue-native-page">
    <div class="page-head">
      <div class="page-title"><h1>管理审计与权限复核</h1><p>操作证据使用防篡改哈希链、重复提交保护和事务事件记录，可追溯且不可删除或改写</p></div>
      <div class="head-actions"><button class="btn" type="button" :disabled="reviewBusy || !configLeaseQuery.data.value" @click="reviewOpen = true">创建复核任务</button><button class="btn primary" type="button" :disabled="!events.length" @click="exportEvidence">导出受控证据包</button></div>
    </div>

    <ClinicalPageState v-if="leaseQuery.isPending.value || eventsQuery.isPending.value" kind="loading" message="正在读取审计事件链" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="eventsQuery.refetch()" />

    <template v-else>
      <p v-if="reviewNotice" class="admin-notice" role="status">{{ reviewNotice }}</p>
      <section class="admin-metrics compact-metrics" aria-label="审计指标"><article><span>当前管理事件</span><strong>{{ events.length }}</strong><small>按当前筛选</small></article><article><span>高风险事件</span><strong>{{ highRiskCount }}</strong><small>失败、阻断或停用</small></article><article><span>涉及操作者</span><strong>{{ uniqueActors }}</strong><small>数据库主体去重</small></article><article><span>审计链健康</span><strong>{{ events.every((event) => event.event_hash) ? '100%' : '异常' }}</strong><small>事件哈希完整</small></article></section>
      <div class="grid admin-list-detail">
        <section class="admin-panel">
          <header>
            <div><h2>审计事件链</h2><p>最近 500 条 · 按发生时间倒序</p></div>
            <div class="toolbar-actions">
              <select v-model="actionCode" class="select" aria-label="按操作类型筛选"><option v-for="option in auditActionOptions" :key="option[0]" :value="option[0]">{{ option[1] }}</option></select>
              <select v-model="resourceType" class="select" aria-label="按业务对象筛选"><option v-for="option in auditResourceOptions" :key="option[0]" :value="option[0]">{{ option[1] }}</option></select>
            </div>
          </header>
          <div v-if="events.length === 0" class="empty-state"><span>审</span><p>暂无匹配的审计事件</p><small>业务写操作会同步写入哈希链审计事件</small></div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>发生时间</th><th>操作</th><th>业务对象</th><th>操作者</th><th>请求追踪号</th><th>防篡改摘要</th></tr></thead>
              <tbody>
                <tr v-for="event in pagedEvents" :key="event.audit_event_id">
                  <td>{{ formatDate(event.occurred_at) }}</td>
                  <td><strong>{{ actionLabel(event.action_code) }}</strong><small>技术编码：{{ event.action_code }}</small></td>
                  <td><strong>{{ adminCodeLabel(event.resource_type) }}</strong><small>记录号 {{ shortId(event.resource_id) }}</small></td>
                  <td>{{ shortId(event.actor_user_id) }}</td>
                  <td><code>{{ shortId(event.trace_id) }}</code></td>
                  <td><code class="event-hash">{{ event.event_hash.slice(0, 12) }}…</code></td>
                </tr>
              </tbody>
            </table>
            <AdminDataPager v-model:page="page" :page-size="pageSize" :total="events.length" />
          </div>
        </section>
        <aside class="card"><div class="card-head">权限季度复核</div><div class="card-body"><div class="folder-row">高权角色成员<span>{{ privilegedMembers }} 人</span></div><div class="folder-row">互斥职责冲突<span>{{ roleGovernance.conflicts.length }} 人</span></div><div class="folder-row">有效角色任期<span>{{ roleGovernance.assignmentCount }} 项</span></div><div class="folder-row">高权角色任期<span>{{ roleGovernance.privilegedAssignmentCount }} 项</span></div><div class="folder-row">审计事件来源<span>{{ events.length }} 条</span></div><div class="folder-row">复核截止<span>本季度末</span></div><div class="notice hard"><div class="notice-title">审计缺失时不得给出“无异常”</div>任一来源中断或事件哈希缺失将阻断复核关闭。</div><RouterLink class="btn primary" style="width:100%;box-sizing:border-box;text-align:center" to="/admin-roles">进入逐人复核</RouterLink></div></aside>
      </div>
    </template>
    <AdminActionDialog v-model:open="reviewOpen" title="创建权限季度复核任务" description="任务以草稿写入通知任务配置，继续校验、独立审批和发布后执行。" :busy="reviewBusy"><div class="admin-impact-grid"><div><span>高权角色成员</span><b>{{ privilegedMembers }} 人</b></div><div><span>互斥职责冲突</span><b>{{ roleGovernance.conflicts.length }} 人</b></div><div><span>有效角色任期</span><b>{{ roleGovernance.assignmentCount }} 项</b></div><div><span>调度方式</span><b>手动发布</b></div></div><template #footer="{ close }"><button class="button secondary" type="button" :disabled="reviewBusy" @click="close">取消</button><button class="button primary" type="button" :disabled="reviewBusy || !configLeaseQuery.data.value" @click="createReviewTask">{{ reviewBusy ? '正在创建…' : '创建草稿' }}</button></template></AdminActionDialog>
  </section>
</template>
