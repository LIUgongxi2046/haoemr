<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref } from 'vue';
import type { ConfigurationItemWire, ConfigurationLifecycleRequestWire } from '../../generated/contracts';
import { clinicalContext, loadWorkforceIdentities } from '../../clinical-api';
import {
  addAdministrationWorkgroupMember,
  createAdministrationWorkgroup,
  deactivateAdministrationWorkgroup,
  endAdministrationWorkgroupMember,
  listAdministrationWorkgroups,
  type AdministrationWorkgroup,
  type AdministrationWorkgroupMember,
} from '../../api/administration-runtime';
import { analyzeRoleGovernance } from '../../api/system-administration';
import { defineConfiguration, issueConfigurationLease, listConfigurations, transitionConfiguration } from '../../api/config';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import AdminActionDialog from '../components/AdminActionDialog.vue';
import { toClinicalIssue } from '../clinical-error';

const query = useQuery({ queryKey: ['admin', 'roles'], queryFn: loadWorkforceIdentities, retry: false, staleTime: 0, gcTime: 0 });
const leaseQuery = useQuery({ queryKey: ['admin', 'roles', 'config-lease'], queryFn: issueConfigurationLease, retry: false, staleTime: 5 * 60_000, gcTime: 0 });
const catalogQuery = useQuery({ queryKey: ['admin', 'roles', 'catalog'], queryFn: () => listConfigurations(leaseQuery.data.value!, 'ROLE_CATALOG'), enabled: () => Boolean(leaseQuery.data.value), retry: false, staleTime: 0, gcTime: 0 });
const workgroupsQuery = useQuery({ queryKey: ['admin', 'roles', 'workgroups'], queryFn: listAdministrationWorkgroups, retry: false, staleTime: 0, gcTime: 0 });
const issue = computed(() => (query.error.value ?? leaseQuery.error.value ?? catalogQuery.error.value ?? workgroupsQuery.error.value) ? toClinicalIssue(query.error.value ?? leaseQuery.error.value ?? catalogQuery.error.value ?? workgroupsQuery.error.value) : null);
const identities = computed(() => query.data.value ?? []);
const governance = computed(() => analyzeRoleGovernance(identities.value));
const scanNotice = ref('');
const keyword = ref('');
const roleTypeFilter = ref<'ALL' | 'ROLE' | 'WORKGROUP' | 'PRIVILEGED'>('ALL');
const selectedRoleCode = ref('');
const createOpen = ref(false);
const workgroupOpen = ref(false);
const memberOpen = ref(false);
const busy = ref('');
const createNotice = ref('');
const form = reactive({ code: '', name: '', objectType: 'ROLE', parent: '—', purpose: '', scope: '全院', owner: '' });
const workgroupForm = reactive({ code: '', name: '', purpose: '', ownerPersonId: '', departmentId: '', effectiveUntil: '' });
const memberForm = reactive({ personId: '', roleCode: 'CLINICIAN', responsibility: '', effectiveUntil: '' });
const selectedWorkgroupId = ref('');

const roleLabels: Readonly<Record<string, string>> = Object.freeze({
  SYSTEM_ADMIN: '系统管理员', CLINICAL_ADMIN: '临床管理员', CLINICIAN: '临床医师',
  NURSE: '护士', ATTENDING_PHYSICIAN: '主治医师', CHIEF_PHYSICIAN: '科主任',
  REGISTERED_NURSE: '注册护士', NURSE_MANAGER: '护士长', PHARMACIST: '药师',
  LAB_TECHNICIAN: '检验技师', RADIOLOGIST: '影像医师', REGISTRAR: '挂号与入院登记员',
  MEDICAL_RECORDS: '病案管理员',
  SECURITY_AUDITOR: '安全审计员', AUTHORIZATION_ADMIN: '授权管理员',
  CONFIG_AUTHOR: '配置作者', CONFIG_APPROVER: '配置审批人',
});
function roleLabel(value: string | null) { return value ? (roleLabels[value] ?? value) : '未授权'; }
const roleCatalog = computed(() => {
  const configs = new Map((catalogQuery.data.value ?? []).map((item) => [item.config_key, item]));
  const grouped = new Map<string, { code: string; name: string; objectType: string; parent: string; purpose: string; owner: string; members: Set<string>; positions: Set<string>; scopes: Set<string>; privileged: boolean; status: string; config: ConfigurationItemWire | null }>();
  for (const identity of identities.value.filter((item) => item.role_status === 'ACTIVE' && item.role_code)) {
    const code = identity.role_code!;
    const config = configs.get(code);
    const current = grouped.get(code) ?? { code, name: config?.display_name ?? roleLabel(code), objectType: String(config?.payload?.object_type ?? 'ROLE'), parent: String(config?.payload?.parent_role_code ?? '—'), purpose: String(config?.payload?.permission_summary ?? '岗位授权'), owner: String(config?.payload?.owner ?? '信息中心'), members: new Set<string>(), positions: new Set<string>(), scopes: new Set<string>(), privileged: false, status: config?.status ?? 'ACTIVE', config: config ?? null };
    current.members.add(identity.person_id);
    if (identity.position_code) current.positions.add(identity.position_code);
    if (identity.department_id) current.scopes.add(`科室 ${identity.department_id.slice(-4)}`);
    else if (identity.facility_id) current.scopes.add('全院');
    current.privileged ||= ['SYSTEM_ADMIN', 'CLINICAL_ADMIN', 'SECURITY_AUDITOR', 'AUTHORIZATION_ADMIN', 'CONFIG_APPROVER'].includes(code);
    grouped.set(code, current);
  }
  for (const config of configs.values()) if (!grouped.has(config.config_key)) grouped.set(config.config_key, {
    code: config.config_key, name: config.display_name, objectType: String(config.payload?.object_type ?? 'ROLE'),
    parent: String(config.payload?.parent_role_code ?? '—'), purpose: String(config.payload?.permission_summary ?? '未配置'), owner: String(config.payload?.owner ?? '未指定'),
    members: new Set<string>(), positions: new Set<string>(), scopes: new Set([String(config.payload?.scope ?? '全院')]),
    privileged: /ADMIN|AUDIT|SECURITY/.test(config.config_key), status: config.status, config,
  });
  const needle = keyword.value.trim().toLocaleLowerCase();
  return [...grouped.values()]
    .filter((role) => !needle || `${role.code} ${role.name}`.toLocaleLowerCase().includes(needle))
    .filter((role) => roleTypeFilter.value === 'ALL'
      || roleTypeFilter.value === 'PRIVILEGED' && role.privileged
      || roleTypeFilter.value === role.objectType)
    .sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'));
});
const selectedRole = computed(() => roleCatalog.value.find((role) => role.code === selectedRoleCode.value)
  ?? roleCatalog.value.find((role) => role.code === 'SYSTEM_ADMIN') ?? roleCatalog.value[0] ?? null);
const workgroups = computed(() => workgroupsQuery.data.value ?? []);
const selectedWorkgroup = computed(() => workgroups.value.find((item) => item.workgroup_id === selectedWorkgroupId.value)
  ?? workgroups.value.find((item) => item.status === 'ACTIVE') ?? workgroups.value[0] ?? null);
const people = computed(() => {
  const unique = new Map<string, (typeof identities.value)[number]>();
  for (const identity of identities.value) if (!unique.has(identity.person_id)) unique.set(identity.person_id, identity);
  return [...unique.values()].sort((a, b) => a.person_display_name.localeCompare(b.person_display_name, 'zh-CN'));
});
function personLabel(personId: string) {
  const person = people.value.find((item) => item.person_id === personId);
  return person ? `${person.person_display_name}（${person.person_code}）` : personId;
}
function localDateTime(value: string) { return new Date(value).toLocaleString('zh-CN', { hour12: false }); }
async function scanConflicts() {
  scanNotice.value = '正在从数据库重新读取有效角色任期…';
  await query.refetch();
  scanNotice.value = governance.value.conflicts.length
    ? `职责冲突扫描完成：发现 ${governance.value.conflicts.length} 项互斥角色组合，未自动变更授权。`
    : '职责冲突扫描完成：当前有效角色任期未发现互斥组合。';
}
async function createCatalogItem() {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || !form.code.trim() || !form.name.trim() || !form.purpose.trim() || !form.owner.trim()) return;
  busy.value = 'create'; createNotice.value = '';
  try {
    await defineConfiguration(lease, { config_type: 'ROLE_CATALOG', config_key: form.code.trim().toUpperCase(), display_name: form.name.trim(), payload: { schema_version: 1, object_type: form.objectType, parent_role_code: form.parent.trim() || '—', permission_summary: form.purpose.trim(), scope: form.scope.trim(), owner: form.owner.trim(), description: `${form.name.trim()}目录定义` } });
    createNotice.value = '角色/工作组草案已写入数据库；发布前需执行校验、审批与职责分离。';
    Object.assign(form, { code: '', name: '', objectType: 'ROLE', parent: '—', purpose: '', scope: '全院', owner: '' });
    createOpen.value = false;
    await catalogQuery.refetch();
  } catch (error) { const next = toClinicalIssue(error); createNotice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}
async function catalogLifecycle(action: ConfigurationLifecycleRequestWire['action']) {
  const lease = leaseQuery.data.value;
  const config = selectedRole.value?.config;
  if (!lease || !config || busy.value) return;
  busy.value = action; createNotice.value = '';
  try {
    const result = await transitionConfiguration(lease, config.config_id, {
      action,
      expected_version: config.row_version,
      reason: '角色与工作组目录按职责分离流程推进',
    });
    createNotice.value = `目录已执行${({ VALIDATE: '校验', SUBMIT: '提交审批', APPROVE: '独立批准', PUBLISH: '发布', ROLLBACK: '回退' } as Record<string, string>)[action] ?? action}，当前状态：${statusLabel(result.status)}。`;
    await catalogQuery.refetch();
  } catch (error) {
    const next = toClinicalIssue(error); createNotice.value = `${next.code}：${next.message}`;
  } finally { busy.value = ''; }
}
function openNewWorkgroup() {
  const owner = people.value.find((item) => item.role_status === 'ACTIVE') ?? people.value[0];
  Object.assign(workgroupForm, { code: '', name: '', purpose: '', ownerPersonId: owner?.person_id ?? '', departmentId: owner?.department_id ?? '', effectiveUntil: '' });
  workgroupOpen.value = true;
}
async function saveWorkgroup() {
  const owner = identities.value.find((item) => item.person_id === workgroupForm.ownerPersonId);
  if (!owner || busy.value || !workgroupForm.code.trim() || !workgroupForm.name.trim() || !workgroupForm.purpose.trim()) return;
  busy.value = 'workgroup'; createNotice.value = '';
  try {
    const created = await createAdministrationWorkgroup({
      workgroup_id: crypto.randomUUID(), workgroup_code: workgroupForm.code.trim().toUpperCase(),
      display_name: workgroupForm.name.trim(), purpose: workgroupForm.purpose.trim(),
      organization_id: clinicalContext.organizationId, facility_id: clinicalContext.facilityId,
      department_id: workgroupForm.departmentId || undefined, owner_person_id: workgroupForm.ownerPersonId,
      effective_from: new Date().toISOString(), effective_until: workgroupForm.effectiveUntil ? new Date(workgroupForm.effectiveUntil).toISOString() : undefined,
    });
    selectedWorkgroupId.value = created.workgroup_id;
    workgroupOpen.value = false;
    createNotice.value = `工作组“${created.display_name}”已写入数据库，现在可分配成员与职责。`;
    await workgroupsQuery.refetch();
  } catch (error) { const next = toClinicalIssue(error); createNotice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}
function openAddMember() {
  const candidate = people.value.find((person) => !selectedWorkgroup.value?.members.some((member) => member.person_id === person.person_id && member.status === 'ACTIVE'));
  Object.assign(memberForm, { personId: candidate?.person_id ?? '', roleCode: candidate?.role_code ?? 'CLINICIAN', responsibility: '', effectiveUntil: '' });
  memberOpen.value = true;
}
async function saveMember() {
  const group = selectedWorkgroup.value;
  if (!group || busy.value || !memberForm.personId || !memberForm.roleCode.trim() || !memberForm.responsibility.trim()) return;
  busy.value = 'member'; createNotice.value = '';
  try {
    await addAdministrationWorkgroupMember(group.workgroup_id, {
      member_id: crypto.randomUUID(), person_id: memberForm.personId, role_code: memberForm.roleCode.trim().toUpperCase(),
      responsibility: memberForm.responsibility.trim(), effective_from: new Date().toISOString(),
      effective_until: memberForm.effectiveUntil ? new Date(memberForm.effectiveUntil).toISOString() : undefined,
    });
    memberOpen.value = false; createNotice.value = '工作组成员及职责任期已生效。'; await workgroupsQuery.refetch();
  } catch (error) { const next = toClinicalIssue(error); createNotice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}
async function endMember(member: AdministrationWorkgroupMember) {
  const group = selectedWorkgroup.value;
  if (!group || busy.value) return;
  busy.value = member.member_id; createNotice.value = '';
  try { await endAdministrationWorkgroupMember(group.workgroup_id, member); createNotice.value = '成员职责任期已结束，历史记录保留用于审计。'; await workgroupsQuery.refetch(); }
  catch (error) { const next = toClinicalIssue(error); createNotice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}
async function deactivateWorkgroup(group: AdministrationWorkgroup) {
  if (busy.value) return;
  busy.value = group.workgroup_id; createNotice.value = '';
  try { await deactivateAdministrationWorkgroup(group); createNotice.value = `工作组“${group.display_name}”已停用，数据与审计记录仍保留。`; await workgroupsQuery.refetch(); }
  catch (error) { const next = toClinicalIssue(error); createNotice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}
function statusLabel(value: string) { return ({ DRAFT: '草稿', PENDING_APPROVAL: '待审批', APPROVED: '已批准', ACTIVE: '已发布', ARCHIVED: '已归档' } as Record<string, string>)[value] ?? value; }
</script>

<template>
  <section data-page-root class="content admin-content vue-native-page roles-admin-page">
    <div class="page-head"><div class="page-title"><h1>角色、工作组与职责分离</h1><p>角色表达权限模板，工作组表达真实协作分派；岗位任期与临床资质独立治理</p></div><div class="head-actions"><button class="btn" type="button" :disabled="query.isFetching.value" @click="scanConflicts">{{ query.isFetching.value ? '扫描中…' : '职责冲突扫描' }}</button><button class="btn" type="button" @click="openNewWorkgroup">新建业务工作组</button><button class="btn primary" type="button" @click="createOpen = true">新建角色目录</button></div></div>
    <ClinicalPageState v-if="query.isPending.value || leaseQuery.isPending.value || catalogQuery.isPending.value || workgroupsQuery.isPending.value" kind="loading" message="正在读取角色、工作组和有效任期" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="query.refetch()" />
    <template v-else>
      <p v-if="scanNotice" class="admin-notice" role="status">{{ scanNotice }}</p>
      <p v-if="createNotice" class="admin-notice" role="status">{{ createNotice }}</p>
      <div class="grid admin-list-detail"><section class="card"><div class="toolbar"><input v-model="keyword" class="search" placeholder="角色编码或名称" /><select v-model="roleTypeFilter" class="select" aria-label="角色类型"><option value="ALL">全部类型</option><option value="ROLE">业务角色</option><option value="PRIVILEGED">高权角色</option><option value="WORKGROUP">工作组</option></select></div>
        <div v-if="!roleCatalog.length" class="admin-empty">暂无匹配的有效角色。</div>
        <div v-else class="admin-table-wrap"><table class="table"><thead><tr><th>角色名称 / 编码</th><th>类型</th><th>继承自</th><th>有效成员</th><th>允许承担的职责</th><th>授权范围</th><th>状态</th></tr></thead><tbody>
          <tr v-for="role in roleCatalog" :key="role.code" :class="{ selected: selectedRole?.code === role.code }" @click="selectedRoleCode = role.code"><td><b>{{ role.name }}</b><br><span class="meta">技术编码：{{ role.code }}</span></td><td>{{ role.objectType === 'WORKGROUP' ? '工作组' : role.privileged ? '高权角色' : '业务角色' }}</td><td>{{ role.parent === '—' ? '不继承' : roleLabel(role.parent) }}</td><td>{{ role.members.size }}</td><td>{{ role.purpose }}</td><td>{{ [...role.scopes].join('、') || '未限定' }}</td><td><span class="status" :class="role.status === 'ACTIVE' ? role.privileged ? 'amber' : 'green' : 'blue'">{{ role.status === 'ACTIVE' && role.privileged ? '待周期复核' : statusLabel(role.status) }}</span></td></tr>
        </tbody></table></div>
      </section><aside v-if="selectedRole" class="card"><div class="card-head">{{ selectedRole.name }} · 权限风险</div><div class="card-body"><div class="permission-ring"><b>{{ selectedRole.members.size }}</b><span>有效成员</span></div><div class="folder-row">角色责任人<span>{{ selectedRole.owner }}</span></div><div class="folder-row">有效成员<span>{{ selectedRole.members.size }} 人</span></div><div class="folder-row">允许承担的职责<span>{{ selectedRole.purpose }}</span></div><div class="folder-row">继承自角色<span>{{ selectedRole.parent === '—' ? '不继承' : roleLabel(selectedRole.parent) }}</span></div><div class="folder-row">授权范围<span>{{ [...selectedRole.scopes].join('、') || '未限定' }}</span></div><div class="folder-row">扫描范围<span>{{ governance.assignmentCount }} 个有效任期</span></div><div v-if="selectedRole.config" class="toolbar-actions lifecycle-actions"><button class="task-action" type="button" :disabled="selectedRole.config.status !== 'DRAFT' || Boolean(busy)" @click="catalogLifecycle('VALIDATE')">校验</button><button class="task-action" type="button" :disabled="selectedRole.config.status !== 'DRAFT' || Boolean(busy)" @click="catalogLifecycle('SUBMIT')">提交审批</button><button class="task-action" type="button" :disabled="selectedRole.config.status !== 'PENDING_APPROVAL' || Boolean(busy)" @click="catalogLifecycle('APPROVE')">独立批准</button><button class="task-action" type="button" :disabled="selectedRole.config.status !== 'APPROVED' || Boolean(busy)" @click="catalogLifecycle('PUBLISH')">发布</button></div><div v-else class="notice info"><div class="notice-title">内置角色</div>该角色来自已生效岗位任期；如需变更目录定义，请先创建对应的版本化角色目录。</div><div v-if="governance.conflicts.length" class="notice hard"><div class="notice-title">发现 {{ governance.conflicts.length }} 项职责冲突</div><p v-for="conflict in governance.conflicts" :key="`${conflict.personId}-${conflict.roleCodes.join('-')}`">{{ conflict.personDisplayName }}（{{ conflict.personCode }}）：{{ conflict.reason }}</p></div><div v-else class="notice info"><div class="notice-title">当前未发现冲突</div>结论来自数据库中的有效角色任期；扫描不会自动撤权。</div></div></aside></div>
      <section class="card"><div class="card-head"><div><b>业务工作组与成员任期</b><p class="meta">数据库实体，用于病案质控、会诊、合理用药等实际任务分派</p></div><button class="task-action" type="button" @click="openNewWorkgroup">新建工作组</button></div><div class="grid admin-list-detail"><div class="admin-table-wrap"><table class="table"><thead><tr><th>工作组</th><th>责任人</th><th>成员</th><th>用途</th><th>生效时间</th><th>状态</th></tr></thead><tbody><tr v-for="group in workgroups" :key="group.workgroup_id" :class="{ selected: selectedWorkgroup?.workgroup_id === group.workgroup_id }" @click="selectedWorkgroupId = group.workgroup_id"><td><b>{{ group.display_name }}</b><br><span class="meta">{{ group.workgroup_code }}</span></td><td>{{ group.owner_name }}</td><td>{{ group.members.filter((member) => member.status === 'ACTIVE').length }} 人</td><td>{{ group.purpose }}</td><td>{{ localDateTime(group.effective_from) }}</td><td><span class="status" :class="group.status === 'ACTIVE' ? 'green' : 'blue'">{{ group.status === 'ACTIVE' ? '有效' : '已停用' }}</span></td></tr><tr v-if="!workgroups.length"><td colspan="6" class="admin-empty">暂无工作组，可创建后指定责任人和成员。</td></tr></tbody></table></div><aside v-if="selectedWorkgroup" class="card flat-card"><div class="card-head"><b>{{ selectedWorkgroup.display_name }} · 成员职责</b><div class="toolbar-actions"><button class="task-action" type="button" :disabled="selectedWorkgroup.status !== 'ACTIVE'" @click="openAddMember">添加成员</button><button class="task-action danger" type="button" :disabled="selectedWorkgroup.status !== 'ACTIVE' || selectedWorkgroup.members.some((member) => member.status === 'ACTIVE') || Boolean(busy)" @click="deactivateWorkgroup(selectedWorkgroup)">停用工作组</button></div></div><div class="card-body"><div v-for="member in selectedWorkgroup.members" :key="member.member_id" class="folder-row"><span><b>{{ member.person_name }}</b><br><span class="meta">{{ roleLabel(member.role_code) }} · {{ member.responsibility }}</span></span><span><span class="status" :class="member.status === 'ACTIVE' ? 'green' : 'blue'">{{ member.status === 'ACTIVE' ? '有效' : '已结束' }}</span><button v-if="member.status === 'ACTIVE'" class="task-action" type="button" :disabled="Boolean(busy)" @click="endMember(member)">结束任期</button></span></div><div v-if="!selectedWorkgroup.members.length" class="admin-empty">尚未分配成员。</div></div></aside></div></section>
    </template>
    <AdminActionDialog v-model:open="createOpen" title="新建角色目录" description="目录定义写入版本化配置表，发布后再用于人员授权。" size="large" :busy="Boolean(busy)"><form class="admin-form compact-admin-form" @submit.prevent="createCatalogItem"><label><span>角色编码（系统唯一）</span><input v-model="form.code" autofocus required placeholder="ROLE-CARD-IP" /></label><label><span>角色中文名称</span><input v-model="form.name" required placeholder="心内科住院医生" /></label><label><span>对象类型</span><select v-model="form.objectType"><option value="ROLE">业务角色</option></select></label><label><span>继承自角色（可选）</span><select v-model="form.parent"><option value="—">不继承其他角色</option><option v-for="role in roleCatalog" :key="role.code" :value="role.code">{{ role.name }}</option></select></label><label><span>允许承担的职责</span><input v-model="form.purpose" required placeholder="例：查看和签署本科室住院病历" /></label><label><span>授权范围</span><select v-model="form.scope" required><option value="全院">全院</option><option value="当前院区">当前院区</option><option value="本科室">本科室</option></select></label><label><span>业务责任人</span><input v-model="form.owner" required placeholder="例：医务处权限管理员" /></label><button class="button primary" :disabled="Boolean(busy)">{{ busy ? '正在保存…' : '保存目录草案' }}</button></form></AdminActionDialog>
    <AdminActionDialog v-model:open="workgroupOpen" title="新建业务工作组" description="创建真实工作组、负责人和生效任期，供流程分派使用。" size="large" :busy="Boolean(busy)"><form class="admin-form compact-admin-form" @submit.prevent="saveWorkgroup"><label><span>工作组编码</span><input v-model="workgroupForm.code" autofocus required placeholder="WG-MEDICAL-RECORDS-QC" /></label><label><span>中文名称</span><input v-model="workgroupForm.name" required placeholder="病案首页质控组" /></label><label><span>业务用途</span><input v-model="workgroupForm.purpose" required placeholder="病案首页内涵质控与问题閉环" /></label><label><span>责任人</span><select v-model="workgroupForm.ownerPersonId" required><option v-for="person in people" :key="person.person_id" :value="person.person_id">{{ person.person_display_name }}（{{ person.person_code }}）</option></select></label><label><span>归属科室</span><select v-model="workgroupForm.departmentId"><option value="">院级工作组</option><option v-for="person in people.filter((item) => item.department_id)" :key="`${person.person_id}-${person.department_id}`" :value="person.department_id!">科室 {{ person.department_id?.slice(-8) }}（参照 {{ person.person_display_name }}）</option></select></label><label><span>计划失效时间（可选）</span><input v-model="workgroupForm.effectiveUntil" type="datetime-local" /></label><button class="button primary" :disabled="Boolean(busy)">{{ busy ? '正在保存…' : '创建工作组' }}</button></form></AdminActionDialog>
    <AdminActionDialog v-model:open="memberOpen" :title="`向${selectedWorkgroup?.display_name ?? '工作组'}添加成员`" description="成员职责按任期生效，结束后保留可追溯记录。" size="large" :busy="Boolean(busy)"><form class="admin-form compact-admin-form" @submit.prevent="saveMember"><label><span>人员</span><select v-model="memberForm.personId" required><option v-for="person in people" :key="person.person_id" :value="person.person_id">{{ personLabel(person.person_id) }}</option></select></label><label><span>在组内的角色编码</span><input v-model="memberForm.roleCode" required placeholder="CLINICIAN" /></label><label><span>具体职责</span><input v-model="memberForm.responsibility" required placeholder="负责病案问题复核与闭环确认" /></label><label><span>计划结束时间（可选）</span><input v-model="memberForm.effectiveUntil" type="datetime-local" /></label><button class="button primary" :disabled="Boolean(busy)">{{ busy ? '正在保存…' : '添加成员' }}</button></form></AdminActionDialog>
  </section>
</template>
