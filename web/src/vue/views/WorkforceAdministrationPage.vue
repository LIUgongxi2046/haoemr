<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref, watch } from 'vue';
import { RouterLink } from 'vue-router';
import type { WorkforceIdentityWire, WorkforceOnboardingRequestWire } from '../../generated/contracts';
import { assignWorkforceRole, clinicalContext, deactivateWorkforceAccount, endWorkforceRole, loadWorkforceIdentities, onboardWorkforceIdentity } from '../../clinical-api';
import { issueAuditLease, listAuditEvents } from '../../api/audit';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import AdminDataPager from '../components/AdminDataPager.vue';
import AdminActionDialog from '../components/AdminActionDialog.vue';
import AdminConfirmDialog from '../components/AdminConfirmDialog.vue';
import { clinicalCodeLabel } from '../clinical-display';
import { toClinicalIssue } from '../clinical-error';

const query = useQuery({ queryKey: ['admin', 'workforce'], queryFn: loadWorkforceIdentities, retry: false, staleTime: 0, gcTime: 0 });
const loginQuery = useQuery({ queryKey: ['admin', 'workforce', 'last-logins'], queryFn: async () => listAuditEvents(await issueAuditLease(), { action_code: 'LOGIN_SUCCEEDED' }), retry: false, staleTime: 0, gcTime: 0 });
const issue = computed(() => query.error.value ? toClinicalIssue(query.error.value) : null);
const identities = computed(() => query.data.value ?? []);
const activePeople = computed(() => new Set(identities.value.filter((item) => item.person_status === 'ACTIVE').map((item) => item.person_id)).size);
const activeAccounts = computed(() => new Set(identities.value.filter((item) => item.account_status === 'ACTIVE').map((item) => item.user_id)).size);
const activeRoles = computed(() => identities.value.filter((item) => item.role_status === 'ACTIVE').length);
const credentials = computed(() => new Set(identities.value.filter((item) => item.active_credential_count > 0).map((item) => item.person_id)).size);
const busy = ref(''); const notice = ref('');
const keyword = ref('');
const statusFilter = ref('ACTIVE');
const sortMode = ref<'RISK' | 'NAME' | 'RECENT'>('RISK');
const page = ref(1);
const pageSize = 15;
const panel = ref<'NONE' | 'RULES'>('NONE');
const createOpen = ref(false);
const importOpen = ref(false);
const roleOpen = ref(false);
const actionTarget = ref<{ item: WorkforceIdentityWire; action: 'account' | 'role' } | null>(null);
const selectedPersonId = ref('');
const form = reactive({ personCode: '', displayName: '', subject: '', roleCode: 'CLINICIAN', positionCode: 'PHYSICIAN', registrationNumber: '' });
const roleForm = reactive({ roleCode: 'CLINICIAN', positionCode: 'PHYSICIAN', validUntil: '' });
const importText = ref('DOC-2026-101,张宁 / Ning Zhang,zhang.ning,临床医师,医师,PHY-2026-101\nNUR-2026-102,李雯 / Wen Li,li.wen,护士,护士,');
const roleCodeByInput: Readonly<Record<string, string>> = Object.freeze({ 临床医师: 'CLINICIAN', 护士: 'NURSE', 临床管理员: 'CLINICAL_ADMIN', 系统管理员: 'SYSTEM_ADMIN', 安全管理员: 'SECURITY_ADMIN' });
const positionCodeByInput: Readonly<Record<string, string>> = Object.freeze({ 医师: 'PHYSICIAN', 主治医师: 'ATTENDING_PHYSICIAN', 主任医师: 'CHIEF_PHYSICIAN', 护士: 'NURSE' });
function short(value: string | null) { return value ? `…${value.slice(-8)}` : '—'; }
function formatDate(value: string | null) { return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium' }).format(new Date(value)) : '长期'; }
const people = computed(() => {
  const grouped = new Map<string, { primary: WorkforceIdentityWire; roles: WorkforceIdentityWire[] }>();
  for (const identity of identities.value) {
    const current = grouped.get(identity.person_id);
    if (current) current.roles.push(identity);
    else grouped.set(identity.person_id, { primary: identity, roles: [identity] });
  }
  return [...grouped.values()];
});
const filteredPeople = computed(() => {
  const needle = keyword.value.trim().toLocaleLowerCase();
  return people.value.filter(({ primary, roles }) => {
    if (statusFilter.value && primary.account_status !== statusFilter.value) return false;
    if (!needle) return true;
    return [primary.person_display_name, primary.person_code, primary.external_subject, ...roles.flatMap((item) => [item.role_code, item.position_code])]
      .filter(Boolean).some((value) => String(value).toLocaleLowerCase().includes(needle));
  }).sort((left, right) => sortMode.value === 'NAME'
    ? left.primary.person_display_name.localeCompare(right.primary.person_display_name, 'zh-CN')
    : sortMode.value === 'RECENT'
      ? (lastLoginByUser.value.get(right.primary.user_id ?? '') ?? '').localeCompare(lastLoginByUser.value.get(left.primary.user_id ?? '') ?? '')
      : workforceRisk(right) - workforceRisk(left));
});
const pagedPeople = computed(() => filteredPeople.value.slice((page.value - 1) * pageSize, page.value * pageSize));
const selectedPerson = computed(() => people.value.find((item) => item.primary.person_id === selectedPersonId.value) ?? filteredPeople.value[0] ?? null);
watch([keyword, statusFilter], () => { page.value = 1; });
function roleSummary(roles: WorkforceIdentityWire[]) {
  const values = [...new Set(roles.filter((item) => item.role_status === 'ACTIVE' && item.role_code).map((item) => clinicalCodeLabel(item.role_code!)))];
  return values.length ? values.join('、') : '暂无有效角色';
}
function positionSummary(roles: WorkforceIdentityWire[]) {
  const values = [...new Set(roles.filter((item) => item.role_status === 'ACTIVE' && item.position_code).map((item) => clinicalCodeLabel(item.position_code!)))];
  return values.length ? values.join('、') : '—';
}
function activeRole(roles: WorkforceIdentityWire[]) { return roles.find((item) => item.role_status === 'ACTIVE') ?? roles[0]; }
const lastLoginByUser = computed(() => {
  const result = new Map<string, string>();
  for (const event of loginQuery.data.value ?? []) if (event.actor_user_id && !result.has(event.actor_user_id)) result.set(event.actor_user_id, event.occurred_at);
  return result;
});
function lastLogin(item: WorkforceIdentityWire) { return item.user_id && lastLoginByUser.value.get(item.user_id) ? formatDateTime(lastLoginByUser.value.get(item.user_id)!) : '从未登录'; }
function formatDateTime(value: string) { return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value)); }
function workforceRisk(person: { primary: WorkforceIdentityWire; roles: WorkforceIdentityWire[] }) {
  return (person.primary.account_status !== 'ACTIVE' ? 100 : 0)
    + (person.roles.some((item) => ['SYSTEM_ADMIN', 'SECURITY_AUDITOR', 'AUTHORIZATION_ADMIN'].includes(item.role_code ?? '')) ? 20 : 0)
    + (!person.primary.user_id || !lastLoginByUser.value.has(person.primary.user_id) ? 5 : 0);
}

function onboardingInput(data: { personCode: string; displayName: string; subject: string; roleCode: string; positionCode: string; registrationNumber?: string }): WorkforceOnboardingRequestWire {
  const input: WorkforceOnboardingRequestWire = {
    person_id: crypto.randomUUID(), person_code: data.personCode.trim(), display_name: data.displayName.trim(),
    user_id: crypto.randomUUID(), external_subject: data.subject.trim(), role_assignment_id: crypto.randomUUID(),
    role_code: roleCodeByInput[data.roleCode.trim()] ?? data.roleCode.trim().toUpperCase(),
    position_code: positionCodeByInput[data.positionCode.trim()] ?? data.positionCode.trim().toUpperCase(), organization_id: clinicalContext.organizationId,
    facility_id: clinicalContext.facilityId, valid_from: new Date().toISOString(),
  };
  if (data.registrationNumber?.trim()) {
    input.credential_id = crypto.randomUUID(); input.credential_type = 'PHYSICIAN_LICENSE';
    input.registration_number = data.registrationNumber.trim(); input.issuing_authority = '机构管理员录入'; input.practice_scope = { source: 'ADMIN_UI' };
  }
  return input;
}

async function onboard() {
  if (busy.value || !form.personCode.trim() || !form.displayName.trim() || !form.subject.trim()) return;
  busy.value = 'onboard'; notice.value = '';
  const input = onboardingInput(form);
  try { await onboardWorkforceIdentity(input); notice.value = `${input.display_name}的人员、账号、角色和工作范围已原子开通。`; Object.assign(form, { personCode: '', displayName: '', subject: '', registrationNumber: '' }); createOpen.value = false; await query.refetch(); }
  catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}

async function bulkOnboard() {
  if (busy.value) return;
  const rows = importText.value.split(/\r?\n/).map((line) => line.split(',').map((value) => value.trim())).filter((row) => row.some(Boolean));
  if (!rows.length) { notice.value = '请至少填写一行人员数据。'; return; }
  busy.value = 'bulk'; notice.value = ''; let created = 0;
  try {
    for (const [personCode, displayName, subject, roleCode = 'CLINICIAN', positionCode = 'PHYSICIAN', registrationNumber = ''] of rows) {
      if (!personCode || !displayName || !subject) throw new Error(`第 ${created + 1} 行缺少人员编码、姓名或登录主体`);
      await onboardWorkforceIdentity(onboardingInput({ personCode, displayName, subject, roleCode, positionCode, registrationNumber })); created += 1;
    }
    notice.value = `批量导入完成：${created} 人的人员、账号、角色和工作范围已原子写入数据库。`; importOpen.value = false;
  } catch (error) { const next = toClinicalIssue(error); notice.value = `已成功 ${created} 人；第 ${created + 1} 人停止：${next.code}：${next.message}`; }
  finally { await query.refetch(); busy.value = ''; }
}

function exportRevocationChecklist() {
  const person = selectedPerson.value;
  if (!person) return;
  const payload = { generated_at: new Date().toISOString(), person_code: person.primary.person_code, display_name: person.primary.person_display_name, account_status: person.primary.account_status, active_roles: person.roles.filter((item) => item.role_status === 'ACTIVE').map((item) => ({ role_code: item.role_code, position_code: item.position_code, valid_until: item.role_valid_until })), checklist: ['暂停新登录', '结束有效角色任期', '转派未完成任务', '保留历史署名与审计证据'] };
  const url = URL.createObjectURL(new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' }));
  const anchor = document.createElement('a'); anchor.href = url; anchor.download = `${person.primary.person_code}-撤权转派清单.json`; anchor.click(); URL.revokeObjectURL(url);
  notice.value = '撤权与任务转派清单已按当前数据库状态生成。';
}

async function execute(item: WorkforceIdentityWire, action: 'account' | 'role') {
  const key = `${action}-${item.person_id}`; if (busy.value) return; busy.value = key; notice.value = '';
  try { if (action === 'account') await deactivateWorkforceAccount(item, '人员管理员确认停用账号'); else await endWorkforceRole(item, '人员管理员确认结束授权'); notice.value = action === 'account' ? '账号已停用，既有临床上下文授权立即失效。' : '角色与工作范围已同步结束。'; actionTarget.value = null; await query.refetch(); }
  catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}

async function assignRole() {
  const person = selectedPerson.value;
  if (!person?.primary.user_id || busy.value) return;
  const scope = activeRole(person.roles);
  busy.value = 'assign-role'; notice.value = '';
  try {
    await assignWorkforceRole({
      role_assignment_id: crypto.randomUUID(), user_id: person.primary.user_id,
      person_id: person.primary.person_id, role_code: roleForm.roleCode,
      position_code: roleForm.positionCode, organization_id: clinicalContext.organizationId,
      facility_id: clinicalContext.facilityId, department_id: scope.department_id ?? undefined,
      ward_id: scope.ward_id ?? undefined, valid_from: new Date().toISOString(),
      valid_until: roleForm.validUntil ? new Date(`${roleForm.validUntil}T23:59:59+08:00`).toISOString() : undefined,
    });
    roleOpen.value = false; notice.value = '新角色任期与工作范围已原子写入数据库，并立即参与权限决策。';
    await query.refetch();
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}
</script>

<template>
  <section data-page-root class="content admin-content vue-native-page"><div class="page-head"><div class="page-title"><h1>用户、人员与账户管理</h1><p>人员基本档案、登录账号、岗位任期、业务角色和临床资质分开管理</p></div><div class="head-actions"><RouterLink v-if="selectedPerson" class="btn" :to="`/admin/users/${selectedPerson.primary.person_id}`">人员档案下钻</RouterLink><button class="btn" type="button" @click="importOpen = true">批量导入</button><button class="btn" type="button" @click="panel = panel === 'RULES' ? 'NONE' : 'RULES'">生命周期规则</button><button class="btn primary" type="button" @click="createOpen = true">新建人员/账户</button></div></div>
    <ClinicalPageState v-if="query.isPending.value" kind="loading" message="正在核对人员、账号、角色与执业资质" /><ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="query.refetch()" />
    <template v-else><section class="admin-metrics"><article><span>有效人员</span><strong>{{ activePeople }}</strong><small>人员基本档案</small></article><article><span>活跃账号</span><strong>{{ activeAccounts }}</strong><small>统一登录账号</small></article><article><span>有效角色</span><strong>{{ activeRoles }}</strong><small>当前有效期</small></article><article><span>在效资质</span><strong>{{ credentials }}</strong><small>按人员去重</small></article></section><p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>
      <section v-if="panel === 'RULES'" class="admin-panel admin-form-panel"><header><div><h2>账户生命周期规则</h2><p>规则状态根据当前数据库人员、账户、角色有效期与登录审计实时计算。</p></div></header><div class="admin-impact-grid"><div><span>人员离岗</span><b>立即暂停新登录</b></div><div><span>角色到期</span><b>结束工作范围授权</b></div><div><span>历史署名</span><b>永久保留</b></div><div><span>异常账号</span><b>{{ identities.filter((item) => item.account_status !== 'ACTIVE').length }} 条待处置</b></div></div></section>
      <div class="grid admin-list-detail">
        <section class="card">
          <div class="toolbar"><input v-model="keyword" class="search" placeholder="姓名、工号、登录名"><select v-model="statusFilter" class="select" aria-label="账户状态"><option value="">全部账户状态</option><option value="ACTIVE">正常</option><option value="DISABLED">已停用</option><option value="LOCKED">已锁定</option></select><select v-model="sortMode" class="select" aria-label="人员排序"><option value="RISK">风险优先</option><option value="RECENT">最近登录</option><option value="NAME">姓名排序</option></select></div>
          <div v-if="!filteredPeople.length" class="admin-empty">没有符合条件的人员记录。</div>
          <div v-else class="admin-table-wrap"><table class="table workforce-table"><thead><tr><th>人员/账户</th><th>类型/登录名</th><th>岗位/角色</th><th>最后登录</th><th>状态</th></tr></thead><tbody><tr v-for="person in pagedPeople" :key="person.primary.person_id" :class="{ selected: selectedPerson?.primary.person_id === person.primary.person_id }" @click="selectedPersonId = person.primary.person_id"><td><b>{{ person.primary.person_display_name }}</b><br><span class="meta">{{ person.primary.person_code }}</span></td><td>人员账户<br><span class="meta">{{ person.primary.external_subject || '未开户' }}</span></td><td>{{ positionSummary(person.roles) }}<br><span class="meta">{{ roleSummary(person.roles) }}</span></td><td>{{ lastLogin(person.primary) }}</td><td><span class="status" :class="person.primary.account_status === 'ACTIVE' ? 'green' : 'red'">{{ person.primary.account_status ? clinicalCodeLabel(person.primary.account_status) : '未开户' }}</span></td></tr></tbody></table></div>
          <AdminDataPager v-model:page="page" :page-size="pageSize" :total="filteredPeople.length" />
        </section>
        <aside v-if="selectedPerson" class="card"><div class="card-head">{{ selectedPerson.primary.person_display_name }} · 账户处置 <span class="status" :class="selectedPerson.primary.account_status === 'ACTIVE' ? 'green' : 'red'">{{ selectedPerson.primary.account_status ? clinicalCodeLabel(selectedPerson.primary.account_status) : '未开户' }}</span></div><div class="card-body"><div class="folder-row">人员状态<span>{{ selectedPerson.primary.person_status === 'ACTIVE' ? '在职' : '离岗' }}</span></div><div class="folder-row">账户状态<span>{{ selectedPerson.primary.account_status ? clinicalCodeLabel(selectedPerson.primary.account_status) : '未开户' }}</span></div><div class="folder-row">有效角色<span>{{ roleSummary(selectedPerson.roles) }}</span></div><div class="folder-row">最后登录<span>{{ lastLogin(selectedPerson.primary) }}</span></div><div class="folder-row">历史署名<span>保留</span></div><div class="folder-row">角色有效期<span>{{ formatDate(activeRole(selectedPerson.roles).role_valid_until) }}</span></div><div class="notice hard"><div class="notice-title">停用不会删除历史署名</div>账户停用和角色结束均写入数据库；既有病历签名、审计证据与历史关系继续保留。</div><div class="admin-actions vertical"><button class="btn primary" type="button" :disabled="selectedPerson.primary.account_status !== 'ACTIVE'" @click="roleOpen = true">新增角色任期</button><button class="btn" type="button" @click="exportRevocationChecklist">生成撤权与任务转派清单</button><button class="btn" type="button" :disabled="selectedPerson.primary.account_status !== 'ACTIVE' || Boolean(busy)" @click="actionTarget = { item: selectedPerson.primary, action: 'account' }">停用账号</button><button class="btn" type="button" :disabled="activeRole(selectedPerson.roles).role_status !== 'ACTIVE' || Boolean(busy)" @click="actionTarget = { item: activeRole(selectedPerson.roles), action: 'role' }">结束当前角色</button></div></div></aside>
      </div>
    </template>
    <AdminActionDialog v-model:open="createOpen" title="新建人员/账户" description="一次事务建立人员档案、登录账号、角色、工作范围和可选执业资质。" :busy="Boolean(busy)"><form class="admin-form" @submit.prevent="onboard"><label><span>人员编码（系统唯一）</span><input v-model="form.personCode" autofocus required placeholder="例：DOC-2026-001" /></label><label><span>中文名 / English name</span><input v-model="form.displayName" required placeholder="例：王雪 / Xue Wang" /></label><label><span>统一登录账号</span><input v-model="form.subject" required placeholder="例：wang.xue；需与统一身份平台一致" /></label><label><span>业务角色</span><select v-model="form.roleCode"><option value="CLINICIAN">临床医师</option><option value="NURSE">护士</option><option value="CLINICAL_ADMIN">临床管理员</option><option value="SYSTEM_ADMIN">系统管理员</option></select></label><label><span>岗位</span><select v-model="form.positionCode" required><option value="PHYSICIAN">医师</option><option value="ATTENDING_PHYSICIAN">主治医师</option><option value="CHIEF_PHYSICIAN">主任医师</option><option value="NURSE">护士</option></select></label><label><span>执业证号（可选）</span><input v-model="form.registrationNumber" placeholder="填写后同步建立执业资质" /></label><button class="button primary full" :disabled="Boolean(busy)">{{ busy === 'onboard' ? '正在开通…' : '开通人员与授权' }}</button></form></AdminActionDialog>
    <AdminActionDialog v-model:open="importOpen" title="人员与账户批量导入" description="每行填写人员编码、中英文姓名、统一登录账号、角色、岗位和执业证号。" size="large" :busy="Boolean(busy)"><form class="admin-form import-admin-form" @submit.prevent="bulkOnboard"><textarea v-model="importText" rows="8" required autofocus aria-label="人员导入内容" /><button class="button primary" :disabled="Boolean(busy)">{{ busy === 'bulk' ? '正在导入…' : '校验并批量开通' }}</button></form></AdminActionDialog>
    <AdminActionDialog v-model:open="roleOpen" title="新增角色任期" description="角色任期将复用当前人员的机构、院区和科室范围，并立即参与后端权限决策。" :busy="Boolean(busy)"><form class="admin-form" @submit.prevent="assignRole"><label><span>业务角色</span><select v-model="roleForm.roleCode"><option value="CLINICIAN">临床医师</option><option value="NURSE">护士</option><option value="PHARMACIST">药师</option><option value="MEDICAL_RECORDS">病案管理员</option><option value="CONFIG_AUTHOR">配置作者</option><option value="CONFIG_APPROVER">配置审批人</option><option value="AUTHORIZATION_ADMIN">授权管理员</option><option value="SECURITY_AUDITOR">安全审计员</option><option value="SYSTEM_ADMIN">系统管理员</option></select></label><label><span>岗位</span><select v-model="roleForm.positionCode"><option value="PHYSICIAN">医师</option><option value="NURSE">护士</option><option value="PHARMACIST">药师</option><option value="MEDICAL_RECORDS_OFFICER">病案管理员</option><option value="CONFIGURATION_ADMIN">配置管理员</option><option value="SECURITY_OFFICER">安全管理员</option></select></label><label><span>到期日期（可选）</span><input v-model="roleForm.validUntil" type="date" /></label><button class="button primary" :disabled="Boolean(busy)">{{ busy === 'assign-role' ? '正在授权…' : '创建角色任期' }}</button></form></AdminActionDialog>
    <AdminConfirmDialog :open="Boolean(actionTarget)" :title="actionTarget?.action === 'account' ? '停用人员账号' : '结束当前角色'" :description="actionTarget?.action === 'account' ? '停用后新登录和既有临床上下文授权立即失效。' : '结束后岗位工作范围和对应业务权限立即失效。'" :confirm-label="actionTarget?.action === 'account' ? '确认停用账号' : '确认结束角色'" :busy="Boolean(busy)" @update:open="!$event && (actionTarget = null)" @confirm="actionTarget && execute(actionTarget.item, actionTarget.action)"><div v-if="actionTarget" class="admin-impact-grid"><div><span>人员</span><b>{{ actionTarget.item.person_display_name }}</b></div><div><span>工号</span><b>{{ actionTarget.item.person_code }}</b></div><div><span>账号</span><b>{{ actionTarget.item.external_subject ?? '未开户' }}</b></div><div><span>当前角色</span><b>{{ actionTarget.item.role_code ? clinicalCodeLabel(actionTarget.item.role_code) : '无' }}</b></div></div></AdminConfirmDialog>
  </section>
</template>
