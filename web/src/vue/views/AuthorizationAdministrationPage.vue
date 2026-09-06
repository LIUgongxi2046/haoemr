<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref } from 'vue';
import type { AuthorizationDecisionWire, AuthorizationPolicyCreateRequestWire, AuthorizationPolicyWire, EmergencyAccessGrantWire } from '../../generated/contracts';
import { clinicalContext, createAuthorizationPolicy, loadAuthorizationPolicies, loadEmergencyAccessForReview, loadWorkforceIdentities, publishAuthorizationPolicy, reviewEmergencyAccess, simulateAuthorization } from '../../clinical-api';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import AdminActionDialog from '../components/AdminActionDialog.vue';
import { adminCodeLabel, authorizationActionOptions, authorizationPurposeOptions, authorizationResourceOptions, authorizationRoleOptions } from '../admin-display';
import { clinicalCodeLabel, joinClinicalCodeLabels } from '../clinical-display';
import { toClinicalIssue } from '../clinical-error';

const query = useQuery({ queryKey: ['admin', 'authorization'], queryFn: async () => ({ policies: await loadAuthorizationPolicies(), grants: await loadEmergencyAccessForReview(), workforce: await loadWorkforceIdentities() }), retry: false, staleTime: 0, gcTime: 0 });
const issue = computed(() => query.error.value ? toClinicalIssue(query.error.value) : null);
const policies = computed(() => query.data.value?.policies ?? []); const grants = computed(() => query.data.value?.grants ?? []);
const busy = ref(''); const notice = ref(''); const decision = ref<AuthorizationDecisionWire | null>(null);
const createOpen = ref(false);
const showDiff = ref(false);
const form = reactive({ name: '', code: '', effect: 'ALLOW' as 'ALLOW' | 'DENY', role: 'CLINICIAN', resource: 'CLINICAL_CONTEXT', action: 'LEASE_ISSUE', relationshipRequired: true, purpose: 'DOCUMENT_DRAFT', priority: 500 });
const simulation = reactive({ userId: clinicalContext.userId, roleId: clinicalContext.roleId, patientId: clinicalContext.patientId, encounterId: clinicalContext.encounterId, purpose: 'DOCUMENT_DRAFT' });
const workforcePeople = computed(() => {
  const grouped = new Map<string, { userId: string; name: string; roles: Array<{ id: string; label: string }> }>();
  for (const item of query.data.value?.workforce ?? []) {
    if (!item.user_id || !item.role_assignment_id || item.account_status !== 'ACTIVE' || item.role_status !== 'ACTIVE') continue;
    const current = grouped.get(item.user_id) ?? { userId: item.user_id, name: item.person_display_name, roles: [] };
    current.roles.push({ id: item.role_assignment_id, label: clinicalCodeLabel(item.role_code ?? item.position_code ?? '有效角色') }); grouped.set(item.user_id, current);
  }
  return [...grouped.values()];
});
const simulationRoles = computed(() => workforcePeople.value.find((item) => item.userId === simulation.userId)?.roles ?? []);
function selectSimulationUser() { simulation.roleId = simulationRoles.value[0]?.id ?? ''; }
const publishedCount = computed(() => policies.value.filter((p) => p.status === 'PUBLISHED').length);
const draftCount = computed(() => policies.value.filter((p) => p.status === 'DRAFT').length);
const pendingReviews = computed(() => grants.value.filter((g) => g.status !== 'REVIEWED').length);
const decisionReasonLabels: Readonly<Record<string, { title: string; detail: string }>> = Object.freeze({
  EXPLICIT_DENY: { title: '显式拒绝策略命中', detail: '高优先级拒绝策略命中，本次访问已阻断。' },
  POLICY_ALLOW: { title: '允许策略命中', detail: '已匹配当前有效角色、范围与用途条件。' },
  EMERGENCY_ACCESS: { title: '紧急访问授权命中', detail: '当前有效的最小必要紧急授权允许访问。' },
  NO_PUBLISHED_POLICY: { title: '无已发布策略', detail: '当前资源与动作没有可用的已发布策略。' },
  CONDITIONS_NOT_MET: { title: '策略条件不满足', detail: '角色、患者关系、范围、用途或资源状态条件未全部满足。' },
});
function decisionReason(code: string) {
  return decisionReasonLabels[code] ?? { title: '未知决策结果', detail: '决策器返回了尚未纳入中文映射的结果，已按安全失败关闭处理。' };
}
const policyDiffs = computed(() => policies.value.filter((policy) => policy.status === 'DRAFT').map((draft) => {
  const current = policies.value.filter((candidate) => candidate.policy_code === draft.policy_code && candidate.status === 'PUBLISHED').sort((a, b) => b.version_no - a.version_no)[0];
  const changes = [
    current?.effect !== draft.effect ? `效果：${current ? clinicalCodeLabel(current.effect) : '无当前版本'} → ${clinicalCodeLabel(draft.effect)}` : '',
    current?.policy_name !== draft.policy_name ? `中文名称：${current?.policy_name ?? '无当前版本'} → ${draft.policy_name}` : '',
    current?.resource_type !== draft.resource_type || current?.action_code !== draft.action_code ? `资源/动作：${adminCodeLabel(draft.resource_type)} / ${adminCodeLabel(draft.action_code)}` : '',
    current?.priority !== draft.priority ? `优先级：${current?.priority ?? '—'} → ${draft.priority}` : '',
  ].filter(Boolean);
  return { draft, current, changes };
}));
function formatDate(value: string | null) { return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short', hour12: false }).format(new Date(value)) : '长期'; }

async function createPolicy() {
  if (busy.value || !form.name.trim() || !form.code.trim()) return; busy.value = 'create'; notice.value = '';
  const input: AuthorizationPolicyCreateRequestWire = { policy_id: crypto.randomUUID(), policy_code: form.code.trim(), policy_name: form.name.trim(), version_no: 1, effect: form.effect, subject_role_code: form.role, resource_type: form.resource, action_code: form.action, organization_id: clinicalContext.organizationId, facility_id: clinicalContext.facilityId, patient_relationship_required: form.relationshipRequired, relationship_types: form.relationshipRequired ? ['CARE_TEAM'] : [], resource_statuses: ['ACTIVE'], purpose_codes: [form.purpose], emergency_override_allowed: true, priority: form.priority, valid_from: new Date().toISOString() };
  try { await createAuthorizationPolicy(input); notice.value = '策略草案已写入数据库，必须由另一名安全管理员审批后才生效。'; form.name = ''; form.code = ''; createOpen.value = false; await query.refetch(); }
  catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; } finally { busy.value = ''; }
}
async function publish(policy: AuthorizationPolicyWire) { busy.value = policy.policy_id; notice.value = ''; try { await publishAuthorizationPolicy(policy); notice.value = '策略已经独立审批并发布。'; await query.refetch(); } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; } finally { busy.value = ''; } }
async function simulate() { busy.value = 'simulate'; notice.value = ''; decision.value = null; try { decision.value = await simulateAuthorization({ target_user_id: simulation.userId, target_role_assignment_ids: [simulation.roleId], resource_type: form.resource, action_code: form.action, organization_id: clinicalContext.organizationId, facility_id: clinicalContext.facilityId, patient_id: simulation.patientId, encounter_id: simulation.encounterId, purpose_code: simulation.purpose, resource_status: 'ACTIVE' }); } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; } finally { busy.value = ''; } }
async function review(grant: EmergencyAccessGrantWire, outcome: 'APPROPRIATE' | 'INAPPROPRIATE' | 'ESCALATED') { busy.value = grant.emergency_access_grant_id; notice.value = ''; try { await reviewEmergencyAccess(grant, outcome, '安全管理员事后复核紧急访问范围与时限'); notice.value = '紧急访问已完成独立复核。'; await query.refetch(); } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; } finally { busy.value = ''; } }
</script>

<template>
  <section data-page-root class="content admin-content vue-native-page permission-admin-page"><div class="page-head"><div class="page-title"><h1>权限策略与访问模拟</h1><p>主体—资源—动作—范围—条件—效果；显式拒绝与安全不变量优先</p></div><div class="head-actions"><button class="btn" type="button" @click="showDiff = !showDiff">比较当前/草稿</button><button class="btn primary" type="button" @click="createOpen = true">新建权限策略</button></div></div>
    <ClinicalPageState v-if="query.isPending.value" kind="loading" message="正在读取策略版本和紧急访问复核队列" /><ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="query.refetch()" />
    <template v-else><p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>
      <section v-if="showDiff" class="admin-panel admin-form-panel"><header><div><h2>当前版本 / 草稿差异</h2><p>仅比较数据库中同一策略编码的已发布版本与草稿，不会读取患者正文。</p></div></header><div v-if="!policyDiffs.length" class="admin-empty">当前没有待比较的策略草稿。</div><div v-else class="admin-diff-list"><article v-for="item in policyDiffs" :key="item.draft.policy_id"><b>{{ item.draft.policy_name }} · 草稿 v{{ item.draft.version_no }}</b><span class="meta">技术编码：{{ item.draft.policy_code }}</span><span v-if="item.current">当前发布 v{{ item.current.version_no }}</span><span v-else>新增策略</span><ul><li v-for="change in item.changes" :key="change">{{ change }}</li><li v-if="!item.changes.length">未发现决策字段差异</li></ul></article></div></section>
      <div class="grid permission-layout"><section class="card"><div class="card-head">权限策略目录 <span class="sub">{{ publishedCount }} 已发布 · {{ draftCount }} 草稿</span></div><div v-if="!policies.length" class="admin-empty">暂无授权策略。</div><div v-else class="admin-table-wrap"><table class="table"><thead><tr><th>策略名称</th><th>授权结果</th><th>资源 / 操作</th><th>适用范围</th><th>使用条件</th><th>版本 / 操作</th></tr></thead><tbody><tr v-for="policy in policies" :key="policy.policy_id"><td><b>{{ policy.policy_name }}</b><br><span class="meta">技术编码：{{ policy.policy_code }} · 优先级 {{ policy.priority }}</span></td><td><span class="status" :class="policy.effect === 'DENY' ? 'red' : 'green'">{{ adminCodeLabel(policy.effect) }}</span></td><td>{{ adminCodeLabel(policy.resource_type) }} / {{ adminCodeLabel(policy.action_code) }}</td><td>{{ policy.department_id ? '指定科室' : policy.facility_id ? '当前院区' : policy.organization_id ? '当前机构' : '全系统' }}</td><td>{{ policy.patient_relationship_required ? `必须属于${joinClinicalCodeLabels(policy.relationship_types)}` : '不要求患者关系' }}</td><td>v{{ policy.version_no }} · {{ adminCodeLabel(policy.status) }}<br><button class="task-action" :disabled="policy.status !== 'DRAFT' || policy.created_by === clinicalContext.userId || Boolean(busy)" @click="publish(policy)">{{ policy.created_by === clinicalContext.userId ? '创建人不能审批' : '独立审批' }}</button></td></tr></tbody></table></div></section><aside class="card simulation-card"><div class="card-head">访问模拟器 <span class="status" :class="decision?.allowed ? 'green' : 'red'">{{ decision ? (decision.allowed ? '最终允许' : '最终拒绝') : '待运行' }}</span></div><div class="card-body"><div class="form-row"><div class="label">用户</div><select v-model="simulation.userId" class="field" aria-label="模拟用户" @change="selectSimulationUser"><option v-for="person in workforcePeople" :key="person.userId" :value="person.userId">{{ person.name }}</option></select></div><div class="form-row"><div class="label">有效角色</div><select v-model="simulation.roleId" class="field" aria-label="模拟角色"><option v-for="role in simulationRoles" :key="role.id" :value="role.id">{{ role.label }}</option></select></div><div class="form-row"><div class="label">资源 / 操作</div><div class="field">{{ adminCodeLabel(form.resource) }} / {{ adminCodeLabel(form.action) }}</div></div><div class="form-row"><div class="label">使用目的</div><select v-model="simulation.purpose" class="field" aria-label="模拟使用目的"><option v-for="option in authorizationPurposeOptions" :key="option[0]" :value="option[0]">{{ option[1] }}</option></select></div><div class="decision-chain"><template v-if="decision"><div :class="decision.allowed ? 'pass' : 'fail'">{{ decision.allowed ? '✓' : '×' }} {{ decisionReason(decision.reason_code).detail }}</div><b>最终：{{ decision.allowed ? '允许' : '拒绝' }} · {{ decisionReason(decision.reason_code).title }}</b></template><template v-else><div>使用与运行时相同的策略决策器，不读取病历正文。</div><b>最终：待运行模拟</b></template></div><button class="btn primary" type="button" style="width:100%" :disabled="Boolean(busy)" @click="simulate">重新运行模拟</button></div></aside></div>
      <section class="admin-panel emergency-review"><header><div><h2>紧急访问事后复核</h2><p>请求人不能复核自己的紧急访问。</p></div></header><div class="admin-table-wrap"><table class="admin-table"><thead><tr><th>请求人 / 患者</th><th>最小范围</th><th>理由</th><th>时限</th><th>状态 / 复核</th></tr></thead><tbody><tr v-for="grant in grants" :key="grant.emergency_access_grant_id"><td><code>用户 …{{ grant.user_id.slice(-8) }}</code><small>患者 …{{ grant.patient_id.slice(-8) }}</small></td><td>{{ joinClinicalCodeLabels(grant.resource_types) }}<small>{{ joinClinicalCodeLabels(grant.action_codes) }}</small></td><td>{{ grant.reason }}</td><td>{{ formatDate(grant.expires_at) }}</td><td><span class="admin-status" :class="grant.status === 'ACTIVE' ? 'active' : ''">{{ clinicalCodeLabel(grant.status) }}</span><div v-if="grant.status !== 'REVIEWED'" class="review-actions"><button :disabled="grant.user_id === clinicalContext.userId || Boolean(busy)" @click="review(grant, 'APPROPRIATE')">合理</button><button :disabled="grant.user_id === clinicalContext.userId || Boolean(busy)" @click="review(grant, 'ESCALATED')">升级</button></div><small v-else>{{ clinicalCodeLabel(grant.review_outcome, '待复核') }}</small></td></tr></tbody></table></div></section>
    </template>
    <AdminActionDialog v-model:open="createOpen" title="新建权限策略" description="中文名称用于页面展示，策略编码用于系统识别；发布前必须独立审批。" size="large" :busy="Boolean(busy)"><form class="admin-form compact-admin-form" @submit.prevent="createPolicy"><label><span>策略中文名称</span><input v-model="form.name" autofocus minlength="2" maxlength="256" required placeholder="例：临床病历查看权限" /></label><label><span>策略编码（系统唯一）</span><input v-model="form.code" required placeholder="例：CLINICAL-DOCUMENT-READ" /></label><label><span>授权结果</span><select v-model="form.effect"><option value="ALLOW">允许</option><option value="DENY">明确拒绝</option></select></label><label><span>适用角色</span><select v-model="form.role"><option v-for="option in authorizationRoleOptions" :key="option[0]" :value="option[0]">{{ option[1] }}</option></select></label><label><span>受控资源</span><select v-model="form.resource"><option v-for="option in authorizationResourceOptions" :key="option[0]" :value="option[0]">{{ option[1] }}</option></select></label><label><span>允许操作</span><select v-model="form.action"><option v-for="option in authorizationActionOptions" :key="option[0]" :value="option[0]">{{ option[1] }}</option></select></label><label><span>使用目的</span><select v-model="form.purpose"><option v-for="option in authorizationPurposeOptions" :key="option[0]" :value="option[0]">{{ option[1] }}</option></select></label><button class="button primary" :disabled="Boolean(busy)">保存待审批草案</button></form></AdminActionDialog>
  </section>
</template>
