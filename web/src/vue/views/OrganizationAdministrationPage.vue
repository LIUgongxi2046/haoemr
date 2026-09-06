<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref, watch } from 'vue';
import type { OrganizationUnitCreateRequestWire, OrganizationUnitWire } from '../../generated/contracts';
import { clinicalContext, createOrganizationUnit, deactivateOrganizationUnit, loadOrganizationUnits } from '../../clinical-api';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import AdminDataPager from '../components/AdminDataPager.vue';
import AdminActionDialog from '../components/AdminActionDialog.vue';
import AdminConfirmDialog from '../components/AdminConfirmDialog.vue';
import AdminOrganizationTree from '../components/AdminOrganizationTree.vue';
import { toClinicalIssue } from '../clinical-error';
import {
  allExpandableOrganizationIds,
  buildOrganizationTree,
  countVisibleOrganizationNodes,
  defaultExpandedOrganizationIds,
  toggleOrganizationTreeId,
} from '../organization-tree';

type UnitType = OrganizationUnitWire['unit_type'];
const unitTypeLabels: Record<UnitType, string> = { ORGANIZATION: '医疗机构', FACILITY: '院区', DEPARTMENT: '科室', WARD: '病区', BED: '床位' };
const query = useQuery({ queryKey: ['admin', 'organization-units'], queryFn: loadOrganizationUnits, retry: false, staleTime: 0, gcTime: 0 });
const issue = computed(() => query.error.value ? toClinicalIssue(query.error.value) : null);
const busy = ref('');
const notice = ref('');
const search = ref('');
const typeFilter = ref<'ALL' | UnitType>('ALL');
const statusFilter = ref<'ACTIVE' | 'INACTIVE' | 'ALL'>('ACTIVE');
const page = ref(1);
const pageSize = 15;
const panel = ref<'NONE' | 'IMPACT'>('NONE');
const createOpen = ref(false);
const importOpen = ref(false);
const deactivateTarget = ref<OrganizationUnitWire | null>(null);
const selectedUnitId = ref('');
const expandedTreeIds = ref<Set<string>>(new Set());
const treeInitialized = ref(false);
const form = reactive({ unitType: 'DEPARTMENT' as UnitType, code: '', name: '', parentId: '', subtype: '' });
const importText = ref('科室,CARD-REHAB,心脏康复中心,\n病区,CARD-REHAB-W1,心脏康复一病区,CARD-REHAB');
const unitTypeByInput: Readonly<Record<string, UnitType>> = Object.freeze({ 医疗机构: 'ORGANIZATION', 机构: 'ORGANIZATION', 院区: 'FACILITY', 科室: 'DEPARTMENT', 病区: 'WARD', 床位: 'BED' });
const units = computed(() => query.data.value ?? []);
const activeUnits = computed(() => units.value.filter((unit) => unit.status === 'ACTIVE'));
const organizationTree = computed(() => buildOrganizationTree(units.value));
const expandableTreeIds = computed(() => allExpandableOrganizationIds(organizationTree.value));
const visibleTreeCount = computed(() => countVisibleOrganizationNodes(organizationTree.value, expandedTreeIds.value));
const totalTreeCount = computed(() => countVisibleOrganizationNodes(organizationTree.value, expandableTreeIds.value));
const treeFullyExpanded = computed(() => expandableTreeIds.value.size > 0
  && [...expandableTreeIds.value].every((unitId) => expandedTreeIds.value.has(unitId)));
const filteredUnits = computed(() => {
  const keyword = search.value.trim().toLowerCase();
  return units.value.filter((unit) => (typeFilter.value === 'ALL' || unit.unit_type === typeFilter.value)
    && (statusFilter.value === 'ALL' || unit.status === statusFilter.value)
    && (!keyword || `${unit.unit_code} ${unit.display_name} ${parentName(unit)}`.toLowerCase().includes(keyword)));
});
const visibleUnits = computed(() => {
  const source = filteredUnits.value;
  if (search.value.trim() || typeFilter.value !== 'ALL') return source;
  if (selectedUnitId.value) {
    const children = source.filter((unit) => unit.parent_unit_id === selectedUnitId.value);
    if (children.length) return children;
  }
  return source.filter((unit) => unit.unit_type !== 'BED');
});
const pagedUnits = computed(() => visibleUnits.value.slice((page.value - 1) * pageSize, page.value * pageSize));
const metrics = computed(() => Object.fromEntries((Object.keys(unitTypeLabels) as UnitType[]).map((type) => [type, activeUnits.value.filter((unit) => unit.unit_type === type).length])) as Record<UnitType, number>);
const parents = computed(() => {
  const type: UnitType | null = form.unitType === 'FACILITY' ? 'ORGANIZATION'
    : form.unitType === 'DEPARTMENT' ? 'DEPARTMENT'
      : form.unitType === 'WARD' ? 'DEPARTMENT'
        : form.unitType === 'BED' ? 'WARD' : 'ORGANIZATION';
  return activeUnits.value.filter((unit) => unit.unit_type === type);
});
const parentRequired = computed(() => ['FACILITY', 'WARD', 'BED'].includes(form.unitType));
watch([search, typeFilter, statusFilter], () => { page.value = 1; });
watch(organizationTree, (nodes) => {
  if (!treeInitialized.value && nodes.length) {
    expandedTreeIds.value = defaultExpandedOrganizationIds(nodes);
    treeInitialized.value = true;
  }
}, { immediate: true });

function toggleTreeUnit(unitId: string) {
  expandedTreeIds.value = toggleOrganizationTreeId(expandedTreeIds.value, unitId);
}
function selectTreeUnit(unitId: string) { selectedUnitId.value = unitId; page.value = 1; }
function expandAllTreeUnits() { expandedTreeIds.value = new Set(expandableTreeIds.value); }
function collapseAllTreeUnits() { expandedTreeIds.value = new Set(); }

function label(unit: OrganizationUnitWire) { return `${unitTypeLabels[unit.unit_type]} · ${unit.display_name}`; }
function formatDate(value: string | null) { return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium' }).format(new Date(value)) : '长期有效'; }
function parentName(unit: OrganizationUnitWire) { return units.value.find((candidate) => candidate.unit_id === unit.parent_unit_id)?.display_name ?? '—'; }

const selectedUnit = computed(() => units.value.find((unit) => unit.unit_id === selectedUnitId.value) ?? activeUnits.value[0] ?? null);
function impactFor(unit: OrganizationUnitWire | null) {
  if (!unit) return { directChildren: 0, descendants: 0, activeBeds: 0 };
  const descendants = new Set<string>();
  const visit = (id: string) => units.value.filter((item) => item.parent_unit_id === id).forEach((child) => {
    if (!descendants.has(child.unit_id)) { descendants.add(child.unit_id); visit(child.unit_id); }
  });
  visit(unit.unit_id);
  return {
    directChildren: units.value.filter((item) => item.parent_unit_id === unit.unit_id && item.status === 'ACTIVE').length,
    descendants: [...descendants].filter((id) => units.value.find((item) => item.unit_id === id)?.status === 'ACTIVE').length,
    activeBeds: [...descendants].filter((id) => { const item = units.value.find((candidate) => candidate.unit_id === id); return item?.unit_type === 'BED' && item.status === 'ACTIVE'; }).length,
  };
}
const selectedImpact = computed(() => impactFor(selectedUnit.value));
const deactivateImpact = computed(() => impactFor(deactivateTarget.value));

function createInput(unitType: UnitType, code: string, name: string, parentId: string, subtype = ''): OrganizationUnitCreateRequestWire {
  const input: OrganizationUnitCreateRequestWire = {
    unit_type: unitType, unit_id: crypto.randomUUID(), unit_code: code.trim(), display_name: name.trim(), effective_from: new Date().toISOString(),
  };
  if (subtype.trim()) input.subtype = subtype.trim();
  if (unitType === 'ORGANIZATION' && parentId) input.parent_unit_id = parentId;
  if (unitType === 'FACILITY') input.organization_id = parentId;
  if (unitType === 'DEPARTMENT') { input.facility_id = clinicalContext.facilityId; if (parentId) input.parent_unit_id = parentId; }
  if (unitType === 'WARD') { input.facility_id = clinicalContext.facilityId; input.department_id = parentId; }
  if (unitType === 'BED') input.parent_unit_id = parentId;
  return input;
}

async function createUnit() {
  if (busy.value || !form.code.trim() || !form.name.trim() || (parentRequired.value && !form.parentId)) return;
  busy.value = 'create'; notice.value = '';
  const input = createInput(form.unitType, form.code, form.name, form.parentId, form.subtype);
  try {
    await createOrganizationUnit(input); Object.assign(form, { code: '', name: '', parentId: '', subtype: '' });
    notice.value = `${unitTypeLabels[input.unit_type]}已生效，审计事件和事务事件记录已同步保存。`; createOpen.value = false; await query.refetch();
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}

async function importUnits() {
  if (busy.value) return;
  const rows = importText.value.split(/\r?\n/).map((line) => line.split(',').map((value) => value.trim())).filter((row) => row.some(Boolean));
  if (!rows.length) { notice.value = '请至少填写一行组织数据。'; return; }
  busy.value = 'import'; notice.value = '';
  const idByCode = new Map(units.value.map((unit) => [unit.unit_code, unit.unit_id]));
  let created = 0;
  try {
    for (const [rawType, code, name, parentCode = '', subtype = ''] of rows) {
      const unitType = (unitTypeByInput[rawType ?? ''] ?? rawType?.toUpperCase()) as UnitType;
      if (!(unitType in unitTypeLabels) || !code || !name) throw new Error(`第 ${created + 1} 行格式不正确`);
      const parentId = parentCode ? idByCode.get(parentCode) ?? '' : '';
      if (['FACILITY', 'WARD', 'BED'].includes(unitType) && !parentId) throw new Error(`${code} 找不到上级编码 ${parentCode}`);
      const result = await createOrganizationUnit(createInput(unitType, code, name, parentId, subtype));
      idByCode.set(result.unit_code, result.unit_id); created += 1;
    }
    notice.value = `导入完成：${created} 个组织单元已逐项写入数据库，并生成审计与事务事件记录。`;
    importOpen.value = false; await query.refetch();
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `已成功 ${created} 项；第 ${created + 1} 项停止：${next.code}：${next.message}`;
    await query.refetch();
  } finally { busy.value = ''; }
}

async function deactivate(unit: OrganizationUnitWire) {
  if (busy.value || unit.status !== 'ACTIVE') return;
  busy.value = unit.unit_id; notice.value = '';
  try { await deactivateOrganizationUnit(unit, '组织管理员确认停用'); notice.value = `${unit.display_name}已停用。`; deactivateTarget.value = null; await query.refetch(); }
  catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}
</script>

<template>
  <section data-page-root class="content admin-content vue-native-page organization-admin-page">
    <div class="page-head"><div class="page-title"><h1>组织机构与工作单元</h1><p>机构、院区、科室、病区和床位的统一版本化层级</p></div><div class="head-actions"><button class="btn" type="button" @click="importOpen = true">导入组织</button><button class="btn" type="button" @click="panel = panel === 'IMPACT' ? 'NONE' : 'IMPACT'">查看变更影响</button><button class="btn primary" type="button" @click="createOpen = true">新建组织节点</button></div></div>
    <ClinicalPageState v-if="query.isPending.value" kind="loading" message="正在读取组织有效期层级" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="query.refetch()" />
    <template v-else>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>
      <div class="grid admin-master-detail"><aside class="card org-tree"><div class="card-head"><span>组织树 <span class="sub">{{ metrics.ORGANIZATION }} 个机构 · 实时</span></span><span class="org-tree-node-count">{{ visibleTreeCount }} / {{ totalTreeCount }} 节点</span></div><div class="org-tree-toolbar"><button class="task-action" type="button" :disabled="treeFullyExpanded" @click="expandAllTreeUnits">全部展开</button><button class="task-action" type="button" :disabled="expandedTreeIds.size === 0" @click="collapseAllTreeUnits">全部收起</button></div><button class="tree-row" :class="{ active: !selectedUnitId }" type="button" @click="selectedUnitId = ''; page = 1"><b>全部组织单元</b><span>{{ activeUnits.length }} 项</span></button><div class="org-tree-scroll" role="region" aria-label="组织树节点列表" tabindex="0"><AdminOrganizationTree :nodes="organizationTree" :expanded-ids="expandedTreeIds" :selected-unit-id="selectedUnitId" @toggle="toggleTreeUnit" @select="selectTreeUnit" /></div></aside><section class="card"><div class="toolbar"><input v-model="search" class="search" placeholder="编码、名称或上级"><select v-model="typeFilter" class="select" aria-label="组织类型"><option value="ALL">全部组织类型</option><option v-for="(name, type) in unitTypeLabels" :key="type" :value="type">{{ name }}</option></select><select v-model="statusFilter" class="select" aria-label="组织状态"><option value="ACTIVE">有效优先</option><option value="INACTIVE">已停用</option><option value="ALL">全部状态</option></select></div><div v-if="!visibleUnits.length" class="admin-empty">暂无匹配组织单元。</div><div v-else class="admin-table-wrap"><table class="table"><thead><tr><th>编码</th><th>名称</th><th>类型</th><th>上级</th><th>有效期</th><th>状态 / 操作</th></tr></thead><tbody><tr v-for="unit in pagedUnits" :key="unit.unit_id"><td><b>{{ unit.unit_code }}</b></td><td>{{ unit.display_name }}</td><td>{{ unitTypeLabels[unit.unit_type] }}</td><td>{{ parentName(unit) }}</td><td>{{ formatDate(unit.effective_until) }}</td><td><span class="status" :class="unit.status === 'ACTIVE' ? 'green' : 'amber'">{{ unit.status === 'ACTIVE' ? '有效' : '已停用' }}</span><button class="task-action" :disabled="unit.status !== 'ACTIVE' || Boolean(busy)" @click="deactivateTarget = unit">{{ busy === unit.unit_id ? '处理中…' : '停用' }}</button></td></tr></tbody></table><AdminDataPager v-model:page="page" :page-size="pageSize" :total="visibleUnits.length" /></div><div class="card-body"><div class="notice hard"><div class="notice-title">已引用组织不能直接删除</div>组织、科室、病区和床位均保留历史语义；停用操作写入数据库审计链，并使用终止时间退出新业务选择范围。</div></div></section></div>
      <section v-if="panel === 'IMPACT' && selectedUnit" class="admin-panel admin-form-panel"><header><div><h2>{{ selectedUnit.display_name }} · 变更影响</h2><p>影响范围由当前数据库组织层级实时计算。</p></div></header><div class="admin-impact-grid"><div><span>直接下级</span><b>{{ selectedImpact.directChildren }}</b></div><div><span>有效后代单元</span><b>{{ selectedImpact.descendants }}</b></div><div><span>有效床位</span><b>{{ selectedImpact.activeBeds }}</b></div><div><span>处理原则</span><b>{{ selectedImpact.descendants ? '先迁移或停用下级' : '可提交停用' }}</b></div></div></section>
    </template>
    <AdminActionDialog v-model:open="createOpen" title="新建组织节点" description="根据类型选择必要上级范围，保存后立即进入新业务选择范围。" :busy="Boolean(busy)"><form class="admin-form compact-admin-form" @submit.prevent="createUnit"><label><span>组织类型</span><select v-model="form.unitType" autofocus @change="form.parentId = ''"><option v-for="(name, type) in unitTypeLabels" :key="type" :value="type">{{ name }}</option></select></label><label><span>组织编码（系统唯一）</span><input v-model="form.code" maxlength="96" required placeholder="例：CARD-WARD-02" /></label><label><span>组织名称</span><input v-model="form.name" maxlength="256" required placeholder="例：心内二病区" /></label><label><span>上级组织{{ parentRequired ? '' : '（可选）' }}</span><select v-model="form.parentId" :required="parentRequired"><option value="">无上级</option><option v-for="parent in parents" :key="parent.unit_id" :value="parent.unit_id">{{ label(parent) }}</option></select></label><label><span>业务分类（可选）</span><input v-model="form.subtype" placeholder="例：护理单元" /></label><button class="button primary" :disabled="Boolean(busy)">{{ busy === 'create' ? '正在创建…' : '创建并生效' }}</button></form></AdminActionDialog>
    <AdminActionDialog v-model:open="importOpen" title="批量导入组织" description="每行填写组织类型、编码、名称、上级编码和业务分类。" size="large" :busy="Boolean(busy)"><form class="admin-form import-admin-form" @submit.prevent="importUnits"><textarea v-model="importText" rows="8" required autofocus aria-label="组织导入内容" /><button class="button primary" :disabled="Boolean(busy)">{{ busy === 'import' ? '正在导入…' : '校验并导入数据库' }}</button></form></AdminActionDialog>
    <AdminConfirmDialog :open="Boolean(deactivateTarget)" :title="`停用${deactivateTarget?.display_name ?? '组织单元'}`" description="停用后将退出新建业务的组织选择范围，有效下级必须先迁移或停用。" :busy="Boolean(busy)" @update:open="!$event && (deactivateTarget = null)" @confirm="deactivateTarget && deactivate(deactivateTarget)"><div v-if="deactivateTarget" class="admin-impact-grid"><div><span>类型</span><b>{{ unitTypeLabels[deactivateTarget.unit_type] }}</b></div><div><span>编码</span><b>{{ deactivateTarget.unit_code }}</b></div><div><span>直接下级</span><b>{{ deactivateImpact.directChildren }}</b></div><div><span>有效后代</span><b>{{ deactivateImpact.descendants }}</b></div></div></AdminConfirmDialog>
  </section>
</template>
