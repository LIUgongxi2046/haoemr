<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed } from 'vue';
import { analyzeRoleGovernance, loadSystemAdministrationSnapshot } from '../../api/system-administration';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { adminCodeLabel } from '../admin-display';
import { toClinicalIssue } from '../clinical-error';

const query = useQuery({
  queryKey: ['admin', 'system-administration-snapshot'], queryFn: loadSystemAdministrationSnapshot,
  retry: false, staleTime: 0, gcTime: 0,
});
const issue = computed(() => query.error.value ? toClinicalIssue(query.error.value) : null);
const snapshot = computed(() => query.data.value);
const roleGovernance = computed(() => analyzeRoleGovernance(snapshot.value?.workforce ?? []));
const activePeople = computed(() => new Set((snapshot.value?.workforce ?? []).filter((item) => item.person_status === 'ACTIVE').map((item) => item.person_id)).size);
const uniqueAccounts = computed(() => new Set((snapshot.value?.workforce ?? []).filter((item) => item.user_id).map((item) => item.user_id)).size);
const inactiveAccounts = computed(() => new Set((snapshot.value?.workforce ?? []).filter((item) => item.user_id && item.account_status !== 'ACTIVE').map((item) => item.user_id)).size);
const activeAccounts = computed(() => Math.max(0, uniqueAccounts.value - inactiveAccounts.value));
const pendingPolicies = computed(() => (snapshot.value?.policies ?? []).filter((item) => item.status === 'DRAFT').length);
const pendingEmergencyReviews = computed(() => (snapshot.value?.emergencyAccess ?? []).filter((item) => !item.reviewed_at).length);
const configurations = computed(() => [...(snapshot.value?.masterData ?? []), ...(snapshot.value?.parameters ?? []), ...(snapshot.value?.jobs ?? [])]);
const pendingConfigurations = computed(() => configurations.value.filter((item) => item.status !== 'ACTIVE').length);
const invalidJobs = computed(() => (snapshot.value?.jobs ?? []).filter((item) => item.validation_state === 'INVALID').length);
const metrics = computed(() => [
  ['待审批管理变更', String(pendingPolicies.value + pendingConfigurations.value), `${pendingPolicies.value} 条权限策略 · ${pendingConfigurations.value} 条配置版本`, ''],
  ['当前活跃账户', String(activeAccounts.value), `对应 ${activePeople.value} 个有效人员主档`, '',],
  ['权限治理风险', String(roleGovernance.value.conflicts.length + pendingEmergencyReviews.value), `${roleGovernance.value.conflicts.length} 项职责冲突 · ${pendingEmergencyReviews.value} 项紧急访问待复核`, ''],
  ['校验失败任务定义', String(invalidJobs.value), `任务定义共 ${snapshot.value?.jobs.length ?? 0} 条`, invalidJobs.value ? 'danger' : ''],
] as const);

const modules = computed(() => {
  const units = snapshot.value?.organizationUnits ?? [];
  const policies = snapshot.value?.policies ?? [];
  const dictionaries = snapshot.value?.dictionaryItems ?? [];
  return [
    ['组织机构', `${units.filter((item) => item.unit_type === 'FACILITY' && item.status === 'ACTIVE').length} 院区 · ${units.filter((item) => item.unit_type === 'DEPARTMENT' && item.status === 'ACTIVE').length} 科室`, `${units.filter((item) => item.unit_type === 'BED' && item.status === 'ACTIVE').length} 张在用床位`, 'admin-org', 'blue'],
    ['用户账户', `${activePeople.value} 人 · ${activeAccounts.value} 个活跃账户`, '人员、账号与任期关联完整', 'admin-users', 'blue'],
    ['角色与工作组', `${new Set((snapshot.value?.workforce ?? []).map((item) => item.role_code).filter(Boolean)).size} 类角色 · ${roleGovernance.value.assignmentCount} 个有效任期`, `${roleGovernance.value.conflicts.length} 项职责冲突`, 'admin-roles', roleGovernance.value.conflicts.length ? 'red' : 'blue'],
    ['权限策略', `${policies.filter((item) => item.status === 'PUBLISHED').length} 条已发布`, `${pendingPolicies.value} 条草稿待审批`, 'admin-permissions', pendingPolicies.value ? 'amber' : 'blue'],
    ['字典术语', `性别值集等 · ${dictionaries.length} 个条目`, `${dictionaries.filter((item) => item.status === 'INACTIVE').length} 项已停用`, 'admin-dictionaries', 'blue'],
    ['医院主数据', `${snapshot.value?.masterData.length ?? 0} 个版本化条目`, `${(snapshot.value?.masterData ?? []).filter((item) => item.validation_state === 'INVALID').length} 项校验失败`, 'admin-master-data', 'blue'],
    ['参数与开关', `${snapshot.value?.parameters.length ?? 0} 个版本化参数`, `${(snapshot.value?.parameters ?? []).filter((item) => item.status !== 'ACTIVE').length} 项未发布`, 'admin-parameters', 'amber'],
    ['后台任务', `${snapshot.value?.jobs.length ?? 0} 个任务定义`, `${invalidJobs.value} 项校验失败`, 'admin-jobs', invalidJobs.value ? 'red' : 'blue'],
  ] as const;
});

const tasks = computed(() => {
  const rows: Array<readonly [string, string, string, string, string, string]> = [];
  for (const conflict of roleGovernance.value.conflicts) rows.push(['职责分离冲突', `${conflict.personDisplayName} / ${conflict.personCode}`, '安全管理员', '立即处理', '阻断', 'red']);
  for (const grant of (snapshot.value?.emergencyAccess ?? []).filter((item) => !item.reviewed_at).slice(0, 2)) rows.push(['紧急访问待复核', `授权 …${grant.emergency_access_grant_id.slice(-8)}`, '安全管理员', formatDateTime(grant.expires_at), '待复核', 'amber']);
  for (const policy of (snapshot.value?.policies ?? []).filter((item) => item.status === 'DRAFT').slice(0, 2)) rows.push(['权限策略草稿待审批', policy.policy_name, '权限管理员', formatDateTime(policy.valid_from), '草稿', 'amber']);
  for (const item of configurations.value.filter((config) => config.status !== 'ACTIVE').slice(0, 3)) rows.push(['配置版本尚未发布', item.display_name, '配置管理员', formatDateTime(item.updated_at), statusLabel(item.status), 'blue']);
  if (!rows.length) rows.push(['当前无高优先级待办', '数据库实时检查完成', '—', '—', '正常', 'green']);
  return rows.slice(0, 6);
});

const releaseActions = new Set(['CONFIGURATION_PUBLISHED', 'CONFIGURATION_ROLLED_BACK', 'AUTHORIZATION_POLICY_PUBLISHED', 'DOCUMENT_TEMPLATE_PUBLISHED', 'ORGANIZATION_UNIT_DEACTIVATED', 'WORKFORCE_ACCOUNT_DEACTIVATED']);
const releases = computed(() => (snapshot.value?.auditEvents ?? [])
  .filter((event) => releaseActions.has(event.action_code) || event.action_code.includes('PUBLISH') || event.action_code.includes('ROLLBACK'))
  .slice(0, 5)
  .map((event) => [formatDateTime(event.occurred_at), auditObject(event), auditActionLabel(event.action_code), event.action_code.includes('ROLLBACK') ? 'amber' : 'green'] as const));

function statusLabel(status: string) { return ({ DRAFT: '草稿', PENDING_APPROVAL: '待审批', APPROVED: '已批准', ACTIVE: '已生效', ARCHIVED: '已归档' } as Record<string, string>)[status] ?? status; }
function auditActionLabel(action: string) {
  const labels: Record<string, string> = { CONFIGURATION_PUBLISHED: '配置已发布', CONFIGURATION_ROLLED_BACK: '配置已回退', AUTHORIZATION_POLICY_PUBLISHED: '权限策略已发布', DOCUMENT_TEMPLATE_PUBLISHED: '模板已发布', ORGANIZATION_UNIT_DEACTIVATED: '组织单元已停用', WORKFORCE_ACCOUNT_DEACTIVATED: '账户已停用' };
  return labels[action] ?? adminCodeLabel(action, '管理操作');
}
function auditObject(event: { resource_type: string; resource_id: string; details?: Record<string, unknown> }) {
  const display = event.details?.display_name ?? event.details?.config_key ?? event.details?.policy_code;
  return typeof display === 'string' ? display : `${adminCodeLabel(event.resource_type)} · 记录号 …${event.resource_id.slice(-8)}`;
}
function formatDateTime(value: string | null | undefined) { return value ? new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value)) : '—'; }
</script>

<template>
  <section data-page-root class="content admin-content vue-native-page admin-overview-page">
    <div class="page-head"><div class="page-title"><h1>系统管理工作台</h1><p>查看机构、账户、权限和系统运行情况。</p></div><div class="head-actions"><button class="btn" type="button" :disabled="query.isFetching.value" @click="query.refetch()">{{ query.isFetching.value ? '刷新中…' : '刷新数据' }}</button><RouterLink class="btn" to="/admin-audit">管理员审计</RouterLink><RouterLink class="btn primary" to="/config-release">创建管理变更单</RouterLink></div></div>
    <ClinicalPageState v-if="query.isPending.value" kind="loading" message="正在聚合系统管理数据" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="query.refetch()" />
    <template v-else>
      <section class="metric-grid" aria-label="系统管理指标"><article v-for="item in metrics" :key="item[0]" class="metric"><div class="name">{{ item[0] }}</div><div class="value" :class="item[3]">{{ item[1] }}</div><div class="trend">{{ item[2] }}</div></article></section>
      <section class="admin-module-grid" aria-label="系统管理模块"><RouterLink v-for="item in modules" :key="item[3]" class="admin-module" :to="`/${item[3]}`"><div><b>{{ item[0] }}</b><span>{{ item[1] }}</span></div><em class="status" :class="item[4]">{{ item[2] }}</em><i aria-hidden="true">→</i></RouterLink></section>

      <div class="grid admin-overview">
        <section class="card"><div class="card-head">高优先级管理待办</div><div class="admin-table-wrap"><table class="table"><thead><tr><th>风险/任务</th><th>对象</th><th>责任人</th><th>时限</th><th>状态</th></tr></thead><tbody><tr v-for="item in tasks" :key="`${item[0]}-${item[1]}`"><td><b>{{ item[0] }}</b></td><td>{{ item[1] }}</td><td>{{ item[2] }}</td><td>{{ item[3] }}</td><td><span class="status" :class="item[5]">{{ item[4] }}</span></td></tr></tbody></table></div></section>
        <aside class="card"><div class="card-head">最近发布与回滚</div><div class="card-body"><div v-if="!releases.length" class="admin-empty">审计链中暂无发布或回退事件。</div><div v-for="item in releases" :key="`${item[0]}-${item[1]}`" class="admin-release-item"><span>{{ item[0] }}</span><i :class="item[3]"></i><div><b>{{ item[1] }}</b><p>{{ item[2] }}</p></div></div><div class="notice info"><div class="notice-title">管理与配置分域</div>本域管理身份、主数据和运行参数；临床流程、表单、规则与接口映射进入业务配置域。</div></div></aside>
      </div>
    </template>
  </section>
</template>

<style scoped>
.admin-overview-page { padding-top: 0; }.admin-module { color: inherit; text-decoration: none; }.value.danger { color: var(--red); }
.development-acceptance-configuration { margin: 0 0 13px; border-color: #e6cd9d; }.development-acceptance-configuration .card-body { display: flex; align-items: center; justify-content: space-between; gap: 20px; }.development-acceptance-configuration b { display: block; color: var(--ink); font-size: 12px; }.development-acceptance-configuration p { max-width: 700px; margin: 5px 0 0; color: var(--muted); font-size: 10px; line-height: 1.6; }.development-acceptance-notice { color: var(--green) !important; }.development-acceptance-notice.error { color: var(--red) !important; }.development-acceptance-toggle { display: flex; align-items: center; gap: 8px; min-width: 92px; color: #775111; font-size: 11px; font-weight: 700; white-space: nowrap; }.development-acceptance-toggle input { width: 16px; height: 16px; accent-color: var(--amber); }
.admin-release-item { display: grid; grid-template-columns: 58px 10px minmax(0,1fr); gap: 8px; align-items: start; padding: 8px 0; }.admin-release-item > span { color: var(--muted); font-size: 9px; }.admin-release-item > i { width: 7px; height: 7px; margin-top: 3px; border-radius: 50%; background: #9ba9b8; }.admin-release-item > i.green { background: var(--green); }.admin-release-item > i.blue { background: var(--blue); }.admin-release-item > i.amber { background: var(--amber); }.admin-release-item b { font-size: 11px; }.admin-release-item p { margin: 3px 0 0; color: var(--muted); font-size: 9px; }
@media(max-width:1100px){.admin-overview{grid-template-columns:minmax(0,1fr)}.admin-module-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:600px){.admin-module-grid,.metric-grid{grid-template-columns:minmax(0,1fr)}.page-head{align-items:flex-start;flex-direction:column}.head-actions{width:100%}.development-acceptance-configuration .card-body{align-items:flex-start;flex-direction:column}.development-acceptance-toggle{min-width:0}}
</style>
