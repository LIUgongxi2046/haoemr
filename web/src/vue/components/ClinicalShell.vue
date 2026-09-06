<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { clinicalContext } from '../../clinical-api';
import { authSession, logoutClinicalSession } from '../../auth-session';
import { brandConfig } from '../../brand-config';
import { specialtyGuardRouteIds } from '../route-registry';
import { mockInterfaceSubmenus } from '../simulation-workbenches';
import { evaReviewBridge } from '../eva-review-bridge';
import { usePendingWork } from '../composables/use-pending-work';
import { pendingDisplay, pendingTotal } from '../pending-work';

import PendingWorkPopover from './PendingWorkPopover.vue';

const GlobalAiAssistantDialog = defineAsyncComponent(() => import('./GlobalAiAssistantDialog.vue'));

const route = useRoute();
const router = useRouter();
const routeId = computed(() => String(route.meta.contractId ?? 'safe-not-found'));
const taskCountMode = computed(() => ['outpatient', 'emergency', 'inpatient'].includes(String(route.query.mode)) ? String(route.query.mode) : 'inpatient');
const pendingWork = usePendingWork(taskCountMode);
const pendingGroups = computed(() => navigation.filter(item => item.id !== 'clinical')
  .map(item => ({ id: item.id, label: item.label, rows: pendingWork.forMenu(item.id) }))
  .filter(group => group.rows.length));
const pendingLauncher = ref<HTMLButtonElement | null>(null);
async function closePendingWork() {
  activeMenu.value = null;
  await nextTick();
  pendingLauncher.value?.focus();
}
function navCountTitle(id: string, label: string) {
  const rows = pendingWork.forMenu(id);
  if (!rows.length) return `${label} · 无独立业务待办队列`;
  return `${label} · ${rows.map(row => `${row.label}：${row.state === 'error' ? '统计暂不可用' : row.count ?? '加载中'}（${row.scope}）`).join('；')}`;
}
const assistantOpen = ref(false);
const sidebarCollapsed = ref(false);
const sidebarCollapseStorageKey = 'openemr.sidebar-collapsed';
type AssistantDisplayMode = 'center' | 'side';
const assistantMode = ref<AssistantDisplayMode>('center');
const assistantLauncher = ref<HTMLButtonElement | null>(null);
type TopbarMenu = 'role' | 'account' | 'pending' | null;
const activeMenu = ref<TopbarMenu>(null);
const doctorName = computed(() => authSession.user?.display_name ?? '未登录');
const doctorInitial = computed(() => doctorName.value === '未登录' ? '未' : doctorName.value.slice(0, 1));
const shiftDisplay = computed(() => authSession.user?.shift_display ?? '请登录后加载班次');
const hospitalDisplay = computed(() => authSession.user?.organization_name ?? '未加载机构');
const roleLabels: Record<string, string> = {
  CHIEF_PHYSICIAN: '主任医师', ATTENDING_PHYSICIAN: '主治医师', SURGEON: '外科医师',
  EMERGENCY_PHYSICIAN: '急诊医师', PEDIATRICIAN: '儿科医师', ICU_PHYSICIAN: '重症医师',
  RADIOLOGIST: '影像诊断医师', NURSE_MANAGER: '护士长', REGISTERED_NURSE: '注册护士',
  PHARMACIST: '药师', LAB_TECHNICIAN: '检验技师', IMAGING_TECHNICIAN: '影像技师',
  PATHOLOGY_TECHNICIAN: '病理技师', CLINICAL_ADMIN: '医务管理', MEDICAL_RECORDS: '病案人员',
  SECURITY_AUDITOR: '安全审计员', REGISTRAR: '门诊服务人员', RESEARCHER: '临床研究人员',
  CLINICIAN: '临床医生', ADMINISTRATOR: '系统管理员', QUALITY_MANAGER: '质量管理员',
};
const roleOptions = computed(() => (authSession.user?.role_assignment_ids ?? []).map((id, index) => {
  const code = authSession.user?.role_codes[index] ?? 'UNKNOWN_ROLE';
  return { id, code, label: `${roleLabels[code] ?? code} · ${code}` };
}));
const selectedRoleId = ref(clinicalContext.roleId || null);
const searchText = ref('');
const guideOpen = ref(false);
const guideDialog = ref<HTMLDialogElement | null>(null);
const guideLauncher = ref<HTMLButtonElement | null>(null);
const guideCloseAction = ref<'focus' | 'assistant' | 'navigate'>('focus');
const guideTarget = ref<string | null>(null);

// 与高保真原型一致的侧栏导航（coverage.js + specialty 拼接）
interface NavItem { id: string; label: string; icon: string; group: string }
const navigation: NavItem[] = [
  { id: 'clinical', label: '临床业务门户', icon: '⌂', group: '临床工作域' },
  { id: 'outpatient', label: '门诊工作台', icon: '◫', group: '临床工作域' },
  { id: 'emergency', label: '急诊工作台', icon: '✚', group: '临床工作域' },
  { id: 'inpatient', label: '住院工作站', icon: '▥', group: '临床工作域' },
  { id: 'record', label: '全院病历中心', icon: '▤', group: '病历与质量' },
  { id: 'quality-center', label: '医疗质量中心', icon: '◈', group: '病历与质量' },
  { id: 'archive-assets', label: '病案资产中心', icon: '▣', group: '病历与质量' },
  { id: 'care-operations', label: '诊疗执行中心', icon: '✚', group: '业务协同' },
  { id: 'clinical-tasks', label: '任务中心', icon: '☑', group: '业务协同' },
  { id: 'data-center', label: '数据中心', icon: '⌁', group: '平台中心' },
  { id: 'ai-assistant', label: 'AI中心', icon: '✦', group: '平台中心' },
  { id: 'knowledge-center', label: '知识中心', icon: '⬡', group: '平台中心' },
  { id: 'workflow', label: '业务配置', icon: '⌘', group: '管理与配置' },
  { id: 'admin', label: '系统管理', icon: '⚙', group: '管理与配置' },
  { id: 'mock-interfaces', label: '模拟接口', icon: '⇄', group: '管理与配置' },
];

const groups = computed(() => {
  const order: string[] = [];
  const map = new Map<string, NavItem[]>();
  for (const item of navigation) {
    if (!map.has(item.group)) { map.set(item.group, []); order.push(item.group); }
    map.get(item.group)!.push(item);
  }
  return order.map((group) => ({ group, items: map.get(group)! }));
});

// 路由域归类（coverage.js + app.js）
const recordRoutes = ['record', 'record-editor', 'record-sources', 'record-qc', 'record-versions', 'record-sign', 'record-diff', 'lis-report', 'pacs-viewer'];
const outpatientRoutes = ['outpatient', 'opd-record', 'opd-diagnosis', 'opd-orders', 'opd-results', 'opd-consult', 'opd-followup'];
const emergencyRoutes = [
  'emergency', 'er-triage', 'er-record', 'er-observation', 'er-nursing', 'er-handoff',
  'er-patient-overview', 'er-clinical-timeline', 'er-safety-gates', 'er-transfer-readiness', 'er-evidence-ledger',
];
const inpatientDocRoutes = ['inpatient-doc-editor', 'inpatient-doc-qc', 'inpatient-doc-versions'];
const inpatientRoutes = ['inpatient', 'inpatient-overview', 'inpatient-course', ...inpatientDocRoutes, 'ip-orders', 'ip-results', 'ip-consult', 'ip-pathway', 'inpatient-discharge', 'ward'];
const inpatientAgentContextRoutes = [...inpatientRoutes, 'inpatient-pharmacy'];
const archiveRoutes = ['archive-assets', 'archive-catalog', 'archive-scan', 'archive-integrity', 'archive-borrow', 'archive-preservation', 'asset-detail'];

const clinicalFoundationRoutes = ['clinical', 'unified-home', 'patient-registry', 'patient-merge', 'patient-timeline', 'emergency-access', 'appointment-registration', 'admission-bed'];
const careOperationRoutes = ['care-operations', 'billing', 'outpatient-pharmacy', 'inpatient-pharmacy', 'lab-workbench', 'pathology-workbench', 'imaging-workbench', 'therapy-workbench', 'surgery-schedule', 'anesthesia-workbench', 'device-monitoring', 'transfusion'];
const qualityCenterRoutes = ['quality-center', 'department-qc', 'quality-rating', 'infection-events', 'credentials'];
const dataCenterRoutes = ['data-center', 'integration', 'integration-connectors', 'integration-mapping', 'integration-messages', 'migration', 'data-quality', 'devices', 'research', 'cohort-builder', 'research-stats', 'research-dataset'];
const knowledgeCenterRoutes = ['knowledge-center', 'pathway-graph', 'pathway-review', 'pathway-versions'];
const aiPlatformRoutes = ['ai-assistant', 'ai-reminder-detail', 'ai-capture', 'models', 'model-connection', 'model-routing', 'model-evaluation', 'agent-catalog', 'agent', 'agent-context', 'tool-catalog', 'skill-catalog', 'agent-compose', 'agent-evals', 'aiops'];
const configurationRoutes = ['workflow', 'capability-pack', 'specialty-coverage', 'form-designer', 'rule-center', 'scope-designer', 'config-release', 'config-upgrade'];
const operationRoutes = ['install', 'backup', 'operations', 'release-gates', 'opensource'];
const adminRoutes = ['admin', 'admin-org', 'admin-users', 'admin-roles', 'admin-permissions', 'admin-auth', 'admin-dictionaries', 'admin-master-data', 'admin-templates', 'admin-parameters', 'admin-jobs', 'admin-audit'];
const adminNavigation: ReadonlyArray<{ href: string; routeId: string; label: string }> = [
  { href: '/admin', routeId: 'admin', label: '管理工作台' }, { href: '/admin-org', routeId: 'admin-org', label: '组织机构' }, { href: '/admin-users', routeId: 'admin-users', label: '用户账户' },
  { href: '/admin-roles', routeId: 'admin-roles', label: '角色工作组' }, { href: '/admin-permissions', routeId: 'admin-permissions', label: '权限策略' }, { href: '/admin-auth', routeId: 'admin-auth', label: '认证安全' },
  { href: '/admin-dictionaries', routeId: 'admin-dictionaries', label: '字典术语' }, { href: '/admin-master-data', routeId: 'admin-master-data', label: '主数据' }, { href: '/admin-templates', routeId: 'admin-templates', label: '模板输出' },
  { href: '/admin-parameters', routeId: 'admin-parameters', label: '参数开关' }, { href: '/admin-parameters?view=branding', routeId: 'admin-parameters', label: '品牌与名称' }, { href: '/admin-jobs', routeId: 'admin-jobs', label: '通知任务' }, { href: '/admin-audit', routeId: 'admin-audit', label: '管理审计' },
];
const isAdminDomain = computed(() => adminRoutes.includes(routeId.value));

function isAdminNavigationActive(item: { href: string; routeId: string }) {
  if (item.routeId !== routeId.value) return false;
  const branding = route.query.view === 'branding';
  return item.href.includes('view=branding') ? branding : !branding;
}

const isTaskConfigurationView = computed(() => routeId.value === 'clinical-tasks'
  && (route.query.view === 'pathway' || route.query.view === 'rules'));

function isActive(navId: string): boolean {
  const c = routeId.value;
  switch (navId) {
    case 'outpatient': return outpatientRoutes.includes(c) && !recordRoutes.includes(c);
    case 'emergency': return emergencyRoutes.includes(c);
    case 'inpatient': return inpatientRoutes.includes(c);
    case 'record': return recordRoutes.includes(c);
    case 'quality-center': return qualityCenterRoutes.includes(c);
    case 'archive-assets': return archiveRoutes.includes(c);
    case 'care-operations': return careOperationRoutes.includes(c);
    case 'clinical-tasks': return c === 'clinical-tasks' && !isTaskConfigurationView.value;
    case 'data-center': return dataCenterRoutes.includes(c);
    case 'ai-assistant': return aiPlatformRoutes.includes(c);
    case 'knowledge-center': return knowledgeCenterRoutes.includes(c);
    case 'workflow': return configurationRoutes.includes(c) || isTaskConfigurationView.value;
    case 'admin': return adminRoutes.includes(c) || operationRoutes.includes(c);
    case 'clinical': return clinicalFoundationRoutes.includes(c) || specialtyGuardRouteIds.has(c);
    case 'mock-interfaces': return c === 'mock-interfaces' || c === 'mock-interface-workbench';
    default: return c === navId;
  }
}

// 域内子导航（原型 domain-nav + center-nav）
type SubNavItem = [string, string]
interface SubNav { kind: 'domain' | 'center'; title: string; active: string; items: SubNavItem[] }
const subNav = computed<SubNav | null>(() => {
  const c = routeId.value;
  if (c === 'mock-interfaces' || c === 'mock-interface-workbench') {
    const workbenchId = typeof route.params.workbenchId === 'string' ? route.params.workbenchId : 'mock-interfaces';
    return {
      kind: 'center',
      title: '模拟接口',
      active: workbenchId === 'mock-interfaces' ? 'mock-interfaces' : `mock-interfaces/${workbenchId}`,
      items: mockInterfaceSubmenus.map(([id, label]) => [id === 'mock-interfaces' ? id : `mock-interfaces/${id}`, label]),
    };
  }
  if (recordRoutes.includes(c)) {
    const active = c === 'record-sign' ? 'record-qc' : c === 'record-diff' ? 'record-versions' : c;
    return {
      kind: 'center',
      title: '全院病历中心',
      active,
      items: [
        ['record', '病历工作台'], ['record-editor', '专注编辑'], ['record-sources', '来源证据'],
        ['record-qc', '质控审签'], ['record-versions', '版本证据'],
        ['lis-report', 'LIS 报告'], ['pacs-viewer', 'PACS 影像'],
      ],
    };
  }
  if (outpatientRoutes.includes(c) && !recordRoutes.includes(c)) {
    return { kind: 'domain', title: '门诊工作台', active: c, items: [['outpatient', '门诊工作台'], ['opd-record', '门诊病历'], ['opd-diagnosis', '诊断'], ['opd-orders', '医嘱处方'], ['opd-results', '检查检验'], ['opd-consult', '会诊转诊'], ['opd-followup', '随访终诊']] };
  }
  if (emergencyRoutes.includes(c)) {
    return { kind: 'domain', title: '急诊工作台', active: c, items: [['emergency', '急诊工作台'], ['er-triage', '预检分诊'], ['er-record', '急诊病历'], ['er-observation', '抢救留观'], ['er-nursing', '急诊护理'], ['er-handoff', '急会诊与交接']] };
  }
  if (inpatientRoutes.includes(c)) {
    return { kind: 'domain', title: '住院工作站', active: c.startsWith('inpatient-doc') ? 'inpatient-course' : c, items: [['inpatient', '患者列表'], ['inpatient-overview', '患者总览'], ['inpatient-course', '病历文书'], ['ip-orders', '医嘱与用药'], ['ip-results', '检查检验'], ['ip-consult', '查房会诊'], ['ip-pathway', '临床路径'], ['ward', '护理摘要'], ['inpatient-discharge', '出院病案']] };
  }
  if (c === 'clinical-tasks' && !isTaskConfigurationView.value) {
    const requestedView = typeof route.query.view === 'string' ? route.query.view : 'overview';
    const activeView = ['overview', 'team', 'collaboration', 'notifications'].includes(requestedView)
      ? requestedView
      : 'overview';
    return {
      kind: 'center',
      title: '任务中心',
      active: `clinical-tasks?view=${activeView}`,
      items: [
        ['clinical-tasks?view=overview', '任务总览'], ['clinical-tasks?view=team', '团队队列'],
        ['clinical-tasks?view=collaboration', '委托协作'], ['clinical-tasks?view=notifications', '消息通知'],
      ],
    };
  }
  if (careOperationRoutes.includes(c)) {
    return {
      kind: 'center',
      title: '诊疗执行中心',
      active: c,
      items: [
        ['care-operations', '执行总览'], ['billing', '费用结算'],
        ['outpatient-pharmacy', '门诊药房'], ['inpatient-pharmacy', '住院药房'],
        ['lab-workbench', '检验'], ['pathology-workbench', '病理'],
        ['imaging-workbench', '检查影像'], ['therapy-workbench', '治疗'],
        ['surgery-schedule', '手术'], ['anesthesia-workbench', '麻醉'],
        ['transfusion', '输血'], ['device-monitoring', '设备监护'],
      ],
    };
  }
  if (qualityCenterRoutes.includes(c)) {
    return { kind: 'center', title: '医疗质量中心', active: c, items: [['quality-center', '质量总览'], ['department-qc', '院科质控'], ['quality-rating', '评级取证'], ['infection-events', '院感事件'], ['credentials', '临床资质']] };
  }
  if (archiveRoutes.includes(c)) {
    return {
      kind: 'center', title: '病案资产中心', active: c === 'asset-detail' ? 'archive-integrity' : c,
      items: [['archive-assets', '总览'], ['archive-catalog', '病案目录'], ['archive-scan', '扫描编目'], ['archive-integrity', '完整性与验真'], ['archive-borrow', '借阅复制'], ['archive-preservation', '长期保存']],
    };
  }
  if (dataCenterRoutes.includes(c)) {
    return { kind: 'center', title: '数据中心', active: c, items: [['data-center', '数据总览'], ['integration', '集成交换'], ['migration', '历史迁移'], ['data-quality', '数据质量'], ['devices', '设备接入'], ['research', '科研统计']] };
  }
  if (aiPlatformRoutes.includes(c)) {
    return { kind: 'center', title: 'AI中心', active: c, items: [['ai-assistant', 'AI医助 EVA'], ['models', '模型服务'], ['agent-catalog', '医助agent'], ['skill-catalog', '医助skill'], ['tool-catalog', '医助tool'], ['agent-evals', '评测发布'], ['aiops', '运行监测']] };
  }
  if (knowledgeCenterRoutes.includes(c)) {
    return { kind: 'center', title: '知识中心', active: c, items: [['knowledge-center', '临床路径知识库'], ['pathway-graph', '知识图谱'], ['pathway-review', '审核队列'], ['pathway-versions', '版本历史']] };
  }
  if (configurationRoutes.includes(c) || isTaskConfigurationView.value) {
    return { kind: 'center', title: '业务配置', active: isTaskConfigurationView.value ? `clinical-tasks?view=${route.query.view}` : c, items: [['workflow', '流程设计'], ['capability-pack', '能力包'], ['specialty-coverage', '科室适配'], ['form-designer', '表单模板'], ['rule-center', '规则时限'], ['scope-designer', '职责范围'], ['clinical-tasks?view=pathway', '临床路径配置'], ['clinical-tasks?view=rules', '任务规则']] };
  }
  if (clinicalFoundationRoutes.includes(c)) {
    return { kind: 'center', title: '临床业务门户', active: c, items: [['clinical', '业务门户'], ['appointment-registration', '预约挂号'], ['admission-bed', '入院床位'], ['emergency-access', '紧急访问']] };
  }
  return null;
});

const routeRoleContext = computed(() => {
  const id = routeId.value;
  if (id === 'ward') return '病区护士 · 心内科一病区';
  if (id === 'inpatient-pharmacy') return '住院药师 · 住院药房';
  if (inpatientRoutes.includes(id)) return '住院医生 · 心内科一病区';
  if (emergencyRoutes.includes(id)) return '急诊医生 · 抢救区';
  if (id === 'clinical-tasks') return isTaskConfigurationView.value ? '业务配置' : '任务中心';
  if (archiveRoutes.includes(id)) return '病案资产与长期保存';
  if (recordRoutes.includes(id)) return '全院病历中心 · 当前门诊就诊';
  if (outpatientRoutes.includes(id)) return '病历中心 · 当前门诊就诊';
  return '管理与治理工作台';
});
const roleContext = computed(() => roleOptions.value.find((role) => role.id === selectedRoleId.value)?.label
  ?? roleOptions.value[0]?.label
  ?? routeRoleContext.value);

const assistantContext = computed(() => {
  const id = routeId.value;
  if (inpatientAgentContextRoutes.includes(id)) {
    return {
      label: `${roleContext.value} · 当前住院就诊`,
      patientId: clinicalContext.inpatientPatientId || null,
      encounterId: clinicalContext.inpatientEncounterId || null,
    };
  }
  if (emergencyRoutes.includes(id)) {
    return {
      label: `${roleContext.value} · 当前急诊就诊`,
      patientId: clinicalContext.emergencyPatientId || null,
      encounterId: clinicalContext.emergencyEncounterId || null,
    };
  }
  if (outpatientRoutes.includes(id) || recordRoutes.includes(id) || archiveRoutes.includes(id)) {
    return {
      label: `${roleContext.value} · 当前患者/就诊`,
      patientId: clinicalContext.patientId || null,
      encounterId: clinicalContext.encounterId || null,
    };
  }
  return { label: `${roleContext.value} · 机构级`, patientId: null, encounterId: null };
});
const assistantTaskId = computed(() => {
  const value = route.query.task_id;
  return typeof value === 'string' && value ? value : null;
});

watch(routeId, () => {
  if (evaReviewBridge.armed) {
    // 复核跳转：自动打开右侧窗对照病历，不因路由切换而关闭
    evaReviewBridge.armed = false;
    assistantMode.value = 'side';
    assistantOpen.value = true;
    activeMenu.value = null;
    return;
  }
  assistantOpen.value = false;
  activeMenu.value = null;
});

function toggleMenu(menu: Exclude<TopbarMenu, null>) {
  activeMenu.value = activeMenu.value === menu ? null : menu;
}

function selectRole(roleId: string) {
  if (!roleOptions.value.some((role) => role.id === roleId)) return;
  selectedRoleId.value = roleId;
  clinicalContext.roleId = roleId;
  activeMenu.value = null;
}

function selectRoleFromEvent(event: Event) {
  selectRole((event.target as HTMLSelectElement).value);
}

async function submitSearch() {
  const query = searchText.value.trim();
  if (!query) return;
  activeMenu.value = null;
  await router.push({ path: '/patient-registry', query: { q: query } });
}

async function openGuide() {
  activeMenu.value = null;
  guideOpen.value = true;
  await nextTick();
  guideDialog.value?.showModal();
}

function closeGuide(action: 'focus' | 'assistant' | 'navigate' = 'focus') {
  guideCloseAction.value = action;
  guideDialog.value?.close();
}

async function onGuideClosed() {
  guideOpen.value = false;
  await nextTick();
  if (guideCloseAction.value === 'assistant') assistantOpen.value = true;
  else if (guideCloseAction.value === 'navigate' && guideTarget.value) await router.push(guideTarget.value);
  else guideLauncher.value?.focus();
  guideCloseAction.value = 'focus';
  guideTarget.value = null;
}

function navigateFromGuide(path: string) {
  guideTarget.value = path;
  closeGuide('navigate');
}

function openAssistantFromGuide() {
  closeGuide('assistant');
}

async function openLoginContext() {
  activeMenu.value = null;
  await logoutClinicalSession();
  await router.push('/login');
}

function onDocumentPointerDown(event: PointerEvent) {
  const target = event.target;
  if (target instanceof Element && !target.closest('.topbar-menu-control')) activeMenu.value = null;
}

function onDocumentKeyDown(event: KeyboardEvent) {
  if (event.key === 'Escape' && activeMenu.value === 'pending') void closePendingWork();
  else if (event.key === 'Escape' && activeMenu.value) activeMenu.value = null;
}

function toggleSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value;
}

watch(sidebarCollapsed, (collapsed) => {
  try {
    window.localStorage.setItem(sidebarCollapseStorageKey, collapsed ? '1' : '0');
  } catch {
    // 本地存储不可用时仍保留当前会话内的折叠状态。
  }
});

onMounted(() => {
  try {
    sidebarCollapsed.value = window.localStorage.getItem(sidebarCollapseStorageKey) === '1';
  } catch {
    // 隐私模式或禁用存储时使用默认展开状态。
  }
  document.addEventListener('pointerdown', onDocumentPointerDown);
  document.addEventListener('keydown', onDocumentKeyDown);
});

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onDocumentPointerDown);
  document.removeEventListener('keydown', onDocumentKeyDown);
});

async function closeAssistant() {
  assistantOpen.value = false;
  await nextTick();
  assistantLauncher.value?.focus();
}
</script>

<template>
  <div class="shell" :class="{ 'sidebar-collapsed': sidebarCollapsed, 'assistant-side-open': assistantOpen && assistantMode === 'side', 'mock-interface-shell': routeId === 'mock-interfaces' || routeId === 'mock-interface-workbench' }">
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <header class="topbar">
      <RouterLink class="brand" to="/clinical" aria-label="HaoEMR 首页">
        <img class="brand-logo" data-testid="brand-logo" :src="brandConfig.logoUrl" :alt="`${brandConfig.systemName} Logo`" width="38" height="38" />
        <span class="brand-copy"><strong>{{ brandConfig.systemName }}</strong><small>电子病历系统</small></span>
      </RouterLink>
      <div class="context-pill" aria-label="当前医院">{{ hospitalDisplay }}</div>
      <div class="topbar-menu-control context-control role-control">
        <button class="context-pill domain-context" type="button" aria-label="选择角色" aria-haspopup="menu" :aria-expanded="activeMenu === 'role'" @click.stop="toggleMenu('role')">{{ roleContext }}<small aria-hidden="true">⌄</small></button>
        <div v-if="activeMenu === 'role'" class="topbar-popover context-menu" role="menu" aria-label="角色列表">
          <strong>当前授权岗位</strong>
          <button v-for="role in roleOptions" :key="role.id" type="button" role="menuitemradio" :aria-checked="selectedRoleId === role.id" @click="selectRole(role.id)"><span>{{ role.label }}</span><b aria-hidden="true">{{ selectedRoleId === role.id ? '✓' : '' }}</b></button>
          <small>这里只显示您当前可用的岗位。</small>
        </div>
      </div>
      <form class="top-search" role="search" @submit.prevent="submitSearch">
        <input v-model="searchText" type="search" placeholder="搜索患者…" aria-label="搜索患者" />
        <button type="submit" aria-label="提交患者搜索">搜索</button>
      </form>
      <div class="top-actions">
        <button ref="assistantLauncher" class="topbar-ai-assistant" type="button" aria-label="打开 AI医助 EVA" :aria-expanded="assistantOpen" @click="assistantOpen = true"><img class="eva-avatar-motion" src="/brand/ai-medical-assistant-eva.png" alt="" width="28" height="28" /><small>AI医助 EVA</small></button>
        <div class="topbar-menu-control">
          <button ref="pendingLauncher" class="icon-btn topbar-pending-button" title="待办" type="button" aria-label="统一待办" aria-controls="topbar-pending-work" :aria-expanded="activeMenu === 'pending'" @click.stop="toggleMenu('pending')"><svg class="topbar-icon" viewBox="0 0 24 24" aria-hidden="true"><rect x="4" y="3" width="16" height="18" rx="2"/><path d="m7 9 1 1 2-2M13 9h4M7 14h3M13 14h4M7 18h3M13 18h4"/></svg></button>
          <PendingWorkPopover v-if="activeMenu === 'pending'" :groups="pendingGroups" @close="closePendingWork" @refresh="pendingWork.refresh()" />
        </div>
        <button ref="guideLauncher" class="icon-btn" type="button" aria-label="打开操作指引" @click="openGuide"><svg class="topbar-icon" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M9.8 9a2.35 2.35 0 0 1 4.55.8c0 1.8-2.35 2.05-2.35 3.55"/><path d="M12 17.2h.01"/></svg></button>

        <div class="topbar-menu-control">
          <button class="avatar" type="button" aria-label="用户登录与账户" aria-haspopup="dialog" :aria-expanded="activeMenu === 'account'" @click.stop="toggleMenu('account')">{{ doctorInitial }}</button>
          <section v-if="activeMenu === 'account'" class="topbar-popover account-menu" role="region" aria-label="用户账户">
            <header><span class="avatar large" aria-hidden="true">{{ doctorInitial }}</span><div><strong>{{ doctorName }}</strong><small>{{ authSession.user ? '数据库会话有效' : '尚未登录' }}</small></div></header>
            <div class="account-context-summary">
              <div><span>工作医院</span><strong>{{ hospitalDisplay }}</strong></div>
              <label><span>工作角色</span><select :value="selectedRoleId" aria-label="账户菜单选择角色" @change="selectRoleFromEvent"><option v-for="role in roleOptions" :key="role.id" :value="role.id">{{ role.label }}</option></select></label>
            </div>
            <RouterLink to="/admin-users" @click="activeMenu = null">账号与权限</RouterLink>
            <a href="https://github.com/LIUgongxi2046/haoemr" target="_blank" rel="noreferrer">开源许可与源码</a>
            <button type="button" @click="openLoginContext">退出系统</button>
          </section>
        </div>
        <div class="user-meta" aria-label="当前医生与出诊时间"><b>{{ doctorName }}</b><br><small>{{ shiftDisplay }}</small></div>
      </div>
    </header>
    <aside class="sidebar" aria-label="一级导航">
      <div class="sidebar-header">
        <span class="sidebar-header-label">主菜单</span>
        <button
          class="sidebar-collapse-toggle"
          type="button"
          :aria-label="sidebarCollapsed ? '展开一级导航' : '折叠一级导航'"
          :aria-expanded="!sidebarCollapsed"
          :title="sidebarCollapsed ? '展开一级导航' : '折叠一级导航'"
          @click="toggleSidebar"
        >{{ sidebarCollapsed ? '›' : '‹' }}</button>
      </div>
      <template v-for="group in groups" :key="group.group">
        <p class="nav-group">{{ group.group }}</p>
        <RouterLink
          v-for="item in group.items"
          :key="item.id"
          :to="`/${item.id}`"
          class="nav-item"
          :class="{ active: isActive(item.id) }"
          :aria-current="isActive(item.id) ? 'page' : undefined"
          :title="navCountTitle(item.id, item.label)"
          :aria-label="item.label"
          :aria-description="navCountTitle(item.id, item.label)"
        ><span class="nav-icon" aria-hidden="true">{{ item.icon }}</span><span class="nav-label">{{ item.label }}</span><span v-if="pendingWork.forMenu(item.id).length" class="nav-count" :class="{ 'nav-count-unavailable': pendingTotal(pendingWork.forMenu(item.id)) === null, 'nav-count-zero': pendingTotal(pendingWork.forMenu(item.id)) === 0 }" :aria-label="navCountTitle(item.id, item.label)">{{ pendingDisplay(pendingWork.forMenu(item.id)) }}</span></RouterLink>
      </template>
    </aside>
    <main id="main-content" class="main" :class="{ 'has-secondary-nav': Boolean(subNav), 'has-admin-secondary-nav': isAdminDomain }">
      <nav v-if="subNav" class="center-nav" :class="[subNav.kind, { 'task-center-subnav': routeId === 'clinical-tasks', 'archive-subpage-nav': archiveRoutes.includes(routeId) }]" :aria-label="`${subNav.title}二级导航`">
        <b>{{ subNav.title }}</b>
        <RouterLink v-for="[id, label] in subNav.items" :key="id" :to="`/${id}`" :class="{ active: id === subNav.active }" :aria-current="id === subNav.active ? 'page' : undefined">{{ label }}</RouterLink>
      </nav>
      <div v-if="isAdminDomain" class="admin-domain-layout">
        <aside class="admin-domain-nav card" aria-label="系统管理二级导航">
          <div class="admin-nav-title"><b>系统管理</b><span>身份 · 主数据 · 安全 · 运行</span></div>
          <RouterLink
            v-for="item in adminNavigation"
            :key="item.href"
            :to="item.href"
            class="admin-domain-link"
            :class="{ active: isAdminNavigationActive(item) }"
            :aria-current="isAdminNavigationActive(item) ? 'page' : undefined"
          >{{ item.label }}</RouterLink>
          <div class="admin-nav-divider"></div>
          <RouterLink class="admin-domain-external" to="/workflow">业务流程配置 →</RouterLink>
          <RouterLink class="admin-domain-external" to="/integration">接口集成管理 →</RouterLink>
        </aside>
        <section class="admin-domain-content"><slot /></section>
      </div>
      <slot v-else />
    </main>
    <GlobalAiAssistantDialog
      v-if="assistantOpen"
      :open="assistantOpen"
      :route-id="routeId"
      :context-label="assistantContext.label"
      :patient-id="assistantContext.patientId"
      :encounter-id="assistantContext.encounterId"
      :task-id="assistantTaskId"
      :mode="assistantMode"
      @mode-change="assistantMode = $event"
      @close="closeAssistant"
    />
    <dialog v-if="guideOpen" ref="guideDialog" class="operation-guide-dialog" aria-labelledby="operation-guide-title" @close="onGuideClosed">
      <header><i aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H11v16H6.5A2.5 2.5 0 0 0 4 21.5z"/><path d="M20 5.5A2.5 2.5 0 0 0 17.5 3H13v16h4.5a2.5 2.5 0 0 1 2.5 2.5z"/></svg></i><div><span>快速开始</span><h2 id="operation-guide-title">操作指引</h2><p>四步开始处理临床工作</p></div><button type="button" aria-label="关闭操作指引" @click="closeGuide()"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18"/></svg></button></header>
      <ol><li><b>1</b><div><strong>确认医院与角色</strong><span>查看当前医院，并选择工作岗位。</span></div></li><li><b>2</b><div><strong>搜索患者</strong><span>输入姓名或标识，查找要处理的患者。</span></div></li><li><b>3</b><div><strong>进入业务工作台</strong><span>通过左侧导航处理门诊、急诊或住院任务。</span></div></li><li><b>4</b><div><strong>使用 AI医助 EVA</strong><span>AI医助可根据当前页面提供建议，最终由您确认。</span></div></li></ol>
      <section class="guide-quick-actions" aria-label="快捷入口"><strong>立即开始</strong><div><button type="button" @click="navigateFromGuide('/patient-registry')"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M16 21v-2a4 4 0 0 0-4-4H7a4 4 0 0 0-4 4v2"/><circle cx="9.5" cy="7" r="4"/><path d="M19 8v6M16 11h6"/></svg><span>患者查询<small>搜索与登记患者</small></span></button><button type="button" @click="navigateFromGuide('/admin-users')"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 0 16 0"/></svg><span>账号与权限<small>查看用户与岗位授权</small></span></button><button type="button" @click="openAssistantFromGuide"><img src="/brand/ai-medical-assistant-eva.png" alt="" width="24" height="24"/><span>AI医助 EVA<small>根据当前页面提供建议</small></span></button></div></section>
      <footer><span>按 Esc 可随时关闭</span><button class="button secondary" type="button" @click="closeGuide()">稍后再看</button></footer>
    </dialog>
  </div>
</template>

<style scoped>
.sidebar .nav-item .nav-count { display: inline-flex; flex-shrink: 0; }
.nav-count-zero { background: #33485d; color: #dbe6ef; }
.nav-count-unavailable { background: #edf1f5; color: #576675; }
.topbar-pending-button[aria-expanded="true"] { color: #1769aa; background: #edf5ff; }
.sidebar-collapsed .nav-item { position: relative; }
.sidebar-collapsed .nav-count { position: absolute; right: 0; top: 0; font-size: 9px; padding: 1px 3px; }
</style>
