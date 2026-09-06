<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref, watch } from 'vue';
import { RouterLink } from 'vue-router';
import type { CapabilityPackReleaseWire, CapabilityPackWire } from '../../generated/contracts';
import type { ConfigurationItemWire } from '../../generated/contracts';
import {
  createCapabilityPackRelease, defineCapabilityPack, deactivateCapabilityPack, issueGovernanceLease,
  listCapabilityPackReleases, listCapabilityPacks, promoteCapabilityPackRelease, retireCapabilityPackRelease,
  rollbackCapabilityPackRelease, startCapabilityPackReleaseCanary, updateCapabilityPack,
} from '../../api/governance';
import { defineConfiguration, issueConfigurationLease, listConfigurations, updateConfiguration } from '../../api/config';
import { toClinicalIssue } from '../clinical-error';
import BusinessActionDialog from '../components/BusinessActionDialog.vue';

type CapabilityDialog = null | 'pack-create' | 'pack-edit' | 'pack-delete' | 'composition-edit' | 'release-create';

const leaseQuery = useQuery({
  queryKey: ['governance', 'capability', 'lease'],
  queryFn: () => issueGovernanceLease('CAPABILITY_ADMIN'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const packsQuery = useQuery({
  queryKey: ['governance', 'capability', 'packs'],
  queryFn: () => listCapabilityPacks(leaseQuery.data.value!),
  enabled: () => Boolean(leaseQuery.data.value), retry: false,
});
const issue = computed(() => (leaseQuery.error.value ?? packsQuery.error.value)
  ? toClinicalIssue(leaseQuery.error.value ?? packsQuery.error.value) : null);
const packs = computed<CapabilityPackWire[]>(() => packsQuery.data.value ?? []);

const selectedPackId = ref('');
const selectedPack = computed(() => packs.value.find((p) => p.capability_pack_id === selectedPackId.value) ?? null);
const releasesQuery = useQuery({
  queryKey: ['governance', 'capability', 'releases', selectedPackId],
  queryFn: () => listCapabilityPackReleases(leaseQuery.data.value!, selectedPackId.value || undefined),
  enabled: () => Boolean(leaseQuery.data.value), retry: false,
});
const releases = computed(() => releasesQuery.data.value ?? []);

const packForm = reactive({ packCode: '', packName: '', inheritsFrom: '' });
const releaseForm = reactive({ releaseVersion: '', releasedAt: new Date().toISOString().slice(0, 16) });
const busy = ref('');
const notice = ref('');
const capabilityDialog = ref<CapabilityDialog>(null);
const targetPack = ref<CapabilityPackWire | null>(null);
const compositionLease = useQuery({ queryKey: ['capability', 'composition-lease'], queryFn: issueConfigurationLease, retry: false, staleTime: 300000, gcTime: 0 });
const compositionsQuery = useQuery({ queryKey: ['capability', 'compositions'], queryFn: () => listConfigurations(compositionLease.data.value!, 'CAPABILITY_PACK_COMPOSITION'), enabled: () => Boolean(compositionLease.data.value), retry: false });
const composition = ref<ConfigurationItemWire | null>(null);
type CapabilityModule = { code: string; name: string; group: string; protected: boolean; requires: string[] };
const selectedModules = ref<string[]>([]);
const moduleCatalog = computed<CapabilityModule[]>(() => {
  const configs = compositionsQuery.data.value ?? [];
  const raw = composition.value?.payload?.module_catalog
    ?? configs.find(item => Array.isArray(item.payload?.module_catalog))?.payload?.module_catalog;
  if (!Array.isArray(raw)) return [];
  return raw.flatMap((value) => {
    if (!value || typeof value !== 'object') return [];
    const row = value as Record<string, unknown>;
    const code = String(row.code ?? '').trim(); const name = String(row.name ?? '').trim();
    if (!code || !name) return [];
    return [{ code, name, group: String(row.group ?? '未分组'), protected: row.protected === true,
      requires: Array.isArray(row.requires) ? row.requires.map(String) : [] }];
  });
});
const missingDependencies = computed(() => moduleCatalog.value.flatMap(module => selectedModules.value.includes(module.code)
  ? module.requires.filter(required => !selectedModules.value.includes(required)).map(required => `${module.name} 依赖 ${moduleCatalog.value.find(item => item.code === required)?.name ?? required}`) : []));
const conflicts = computed(() => selectedModules.value.includes('LEGACY_EXPORT')
  && selectedModules.value.some(code => code === 'QUALITY_RESEARCH' || code.startsWith('SPECIALTY_'))
  ? ['旧版全量导出与专科/质控的最小必要数据范围冲突'] : []);
const rating = computed(() => {
  if (missingDependencies.value.length || conflicts.value.length) return '阻断发布';
  if (!moduleCatalog.value.length) return '数据库模块目录缺失';
  const selected = selectedModules.value.length; const total = moduleCatalog.value.length;
  return `内部运行就绪度 ${Math.round(selected / total * 100)}%`;
});

watch(packs, (value) => { if (!selectedPackId.value && value[0]) selectedPackId.value = value[0].capability_pack_id; }, { immediate: true });
watch([selectedPack, () => compositionsQuery.data.value], ([pack, configs]) => {
  if (!pack) return;
  const key = `composition-${pack.pack_code.toLowerCase()}`;
  composition.value = (configs ?? []).find(item => item.config_key === key) ?? null;
  const modules = composition.value?.payload?.selected_modules;
  selectedModules.value = Array.isArray(modules) ? modules.map(String) : moduleCatalog.value.filter(item => item.protected).map(item => item.code);
}, { immediate: true });

async function saveComposition() {
  const lease = compositionLease.data.value; const pack = selectedPack.value;
  if (!lease || !pack || busy.value) return;
  busy.value = 'composition'; notice.value = '';
  const payload = {
    schema_version: 3, capability_pack_id: pack.capability_pack_id, inherits_from: pack.inherits_from,
    module_catalog: moduleCatalog.value,
    selected_modules: selectedModules.value,
    dependencies: moduleCatalog.value.flatMap(module => module.requires.map(required => ({ module: module.code, requires: required }))),
    conflicts: [{ left: 'LEGACY_EXPORT', right: 'QUALITY_RESEARCH' }],
    protected_modules: moduleCatalog.value.filter(module => module.protected).map(module => module.code),
    scope_overrides: [{ scope: pack.pack_name, module: selectedModules.value.at(-1) ?? 'CORE_PATIENT', effect: 'ENABLE' }],
    rating_framework: { code: 'INTERNAL_OPERATIONAL_READINESS_V1', official_hospital_grade: false, description: '内部配置完整度与发布就绪度，不代表医院等级评审结论' },
    rating_impact: rating.value, rollout_tasks: ['运行依赖解析', '合成病例回放', '科室负责人联合签署'],
  };
  try {
    composition.value = composition.value
      ? await updateConfiguration(lease, composition.value.config_id, { display_name: `${pack.pack_name} · 能力组合`, payload, expected_version: composition.value.row_version })
      : await defineConfiguration(lease, { config_type: 'CAPABILITY_PACK_COMPOSITION', config_key: `composition-${pack.pack_code.toLowerCase()}`, display_name: `${pack.pack_name} · 能力组合`, payload });
    notice.value = '能力组合、依赖、范围覆盖和评级影响已写入数据库。'; await compositionsQuery.refetch();
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}

function statusLabel(status: string) {
  const map: Record<string, string> = {
    ACTIVE: '有效', INACTIVE: '已停用', DRAFT: '草稿', CANARY: '灰度', RETIRED: '已退休', ROLLED_BACK: '已回退',
  };
  return map[status] ?? status;
}

function openPackCreate() {
  packForm.packCode = ''; packForm.packName = ''; packForm.inheritsFrom = '';
  targetPack.value = null; capabilityDialog.value = 'pack-create';
}
function openPackEdit(pack: CapabilityPackWire) {
  targetPack.value = pack; packForm.packCode = pack.pack_code; packForm.packName = pack.pack_name;
  packForm.inheritsFrom = pack.inherits_from ?? ''; capabilityDialog.value = 'pack-edit';
}
function openPackDelete(pack: CapabilityPackWire) { targetPack.value = pack; capabilityDialog.value = 'pack-delete'; }

async function createPack() {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || !packForm.packCode.trim() || !packForm.packName.trim()) return;
  busy.value = 'pack'; notice.value = '';
  try {
    await defineCapabilityPack(lease, {
      pack_code: packForm.packCode.trim(), pack_name: packForm.packName.trim(),
      inherits_from: packForm.inheritsFrom.trim() || null,
    });
    packForm.packCode = ''; packForm.packName = ''; packForm.inheritsFrom = '';
    notice.value = '能力包已定义。'; await packsQuery.refetch(); capabilityDialog.value = null;
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}

async function editPack() {
  const lease = leaseQuery.data.value; const pack = targetPack.value;
  if (!lease || !pack || busy.value || !packForm.packName.trim()) return;
  busy.value = 'pack'; notice.value = '';
  try {
    const result = await updateCapabilityPack(lease, pack, { pack_name: packForm.packName.trim(), inherits_from: packForm.inheritsFrom.trim() || null });
    notice.value = `${result.pack_name} 已更新并重新解析继承链。`; await packsQuery.refetch(); capabilityDialog.value = null;
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}

async function deactivate(pack: CapabilityPackWire) {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || pack.status !== 'ACTIVE') return;
  busy.value = pack.capability_pack_id; notice.value = '';
  try { await deactivateCapabilityPack(lease, pack); notice.value = `${pack.pack_name} 已停用并退出运行时能力解析。`; await packsQuery.refetch(); capabilityDialog.value = null; }
  catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}

async function createRelease() {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || !selectedPack.value || !releaseForm.releaseVersion.trim()) return;
  busy.value = 'release'; notice.value = '';
  try {
    await createCapabilityPackRelease(lease, {
      capability_pack_id: selectedPack.value.capability_pack_id,
      release_version: releaseForm.releaseVersion.trim(), released_at: new Date(releaseForm.releasedAt).toISOString(),
    });
    releaseForm.releaseVersion = ''; notice.value = '发布已创建。'; await releasesQuery.refetch(); capabilityDialog.value = null;
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}

async function confirmCapabilityDialog() {
  if (capabilityDialog.value === 'pack-create') await createPack();
  else if (capabilityDialog.value === 'pack-edit') await editPack();
  else if (capabilityDialog.value === 'pack-delete' && targetPack.value) await deactivate(targetPack.value);
  else if (capabilityDialog.value === 'composition-edit') { await saveComposition(); if (!notice.value.includes('：')) capabilityDialog.value = null; }
  else if (capabilityDialog.value === 'release-create') await createRelease();
}

const capabilityDialogTitle = computed(() => ({
  'pack-create': '新建能力包', 'pack-edit': '编辑能力包', 'pack-delete': '删除并停用能力包',
  'composition-edit': '编辑能力组合', 'release-create': '新建灰度发布',
} as Record<string, string>)[capabilityDialog.value ?? ''] ?? '能力包操作');

async function transition(release: CapabilityPackReleaseWire, action: 'canary' | 'promote' | 'retire' | 'rollback') {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value) return;
  busy.value = release.release_id; notice.value = '';
  try {
    if (action === 'canary') await startCapabilityPackReleaseCanary(lease, release);
    else if (action === 'promote') await promoteCapabilityPackRelease(lease, release);
    else if (action === 'retire') await retireCapabilityPackRelease(lease, release);
    else await rollbackCapabilityPackRelease(lease, release, '灰度回退（管理员确认）');
    notice.value = `发布 ${release.release_version} 状态已推进。`; await releasesQuery.refetch();
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}
</script>

<template>
  <section data-page-root class="content admin-content vue-native-page">
    <div class="page-heading admin-heading">
      <div><p class="eyebrow">配置中心 / 机构能力包</p><h1>能力包与灰度发布</h1><p>能力包按机构差异化配置，继承不可自指；发布走草稿→灰度→全量→退休状态机，回退必附原因。</p></div>
      <div class="page-actions"><button class="button secondary" @click="packsQuery.refetch()">刷新</button><button class="button primary" @click="openPackCreate">新建能力包</button></div>
    </div>

    <p v-if="issue" class="admin-notice" role="alert">{{ issue.code }}：{{ issue.message }}。数据库不可用时不展示前端伪造能力包。</p>
    <template v-if="true">
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>
      <div class="capability-stack">
        <section class="admin-panel">
          <header><div><h2>能力包台账</h2><p>编码唯一；编辑会重新解析继承链；删除采用停用并保留历史。</p></div><button class="button primary" @click="openPackCreate">新建能力包</button></header>
          <div v-if="packs.length === 0" class="admin-empty">暂无能力包，可在下方新增。</div>
          <div v-else class="admin-table-wrap"><table class="admin-table"><thead><tr><th>编码 / 名称</th><th>继承</th><th>状态</th><th>操作</th></tr></thead><tbody>
            <tr v-for="pack in packs" :key="pack.capability_pack_id" :class="{ 'is-selected': pack.capability_pack_id === selectedPackId }">
              <td><button class="link-button" @click="selectedPackId = pack.capability_pack_id"><strong>{{ pack.pack_name }}</strong><small><code>{{ pack.pack_code }}</code></small></button></td>
              <td>{{ pack.inherits_from ? '继承自其他包' : '独立' }}</td>
              <td><span class="admin-status" :class="pack.status.toLowerCase()">{{ statusLabel(pack.status) }}</span></td>
              <td><div class="row-actions"><button class="task-action" :disabled="pack.status !== 'ACTIVE' || Boolean(busy)" @click="openPackEdit(pack)">编辑</button><button class="task-action danger" :disabled="pack.status !== 'ACTIVE' || Boolean(busy)" @click="openPackDelete(pack)">删除</button></div></td>
            </tr>
          </tbody></table></div>
        </section>

      </div>

      <section class="admin-panel composition-panel" v-if="selectedPack">
        <header><div><h2>能力组合与依赖解析 · {{ selectedPack.pack_name }}</h2><p>模块目录与组合均取自数据库；就绪度仅用于内部发布门禁，不代表医院等级评审结论。</p></div><div class="row-actions"><details v-if="composition" class="config-technical-details"><summary>技术详情</summary><RouterLink class="button secondary" :to="`/business-config/capability-pack/${composition.config_id}`">查看配置详情</RouterLink></details><button class="button primary" :disabled="Boolean(busy)||!moduleCatalog.length" @click="capabilityDialog='composition-edit'">编辑能力组合</button></div></header>
        <div class="composition-layout">
          <div class="module-catalog module-catalog--readonly"><p v-if="!moduleCatalog.length" class="admin-empty">数据库尚未登记能力模块目录，禁止用页面常量回退。</p><article v-for="module in moduleCatalog" :key="module.code" :class="{ selected: selectedModules.includes(module.code), protected: module.protected }"><span><b>{{ module.name }}</b><code>{{ module.code }}</code></span><em>{{ selectedModules.includes(module.code) ? '已启用' : '未启用' }} · {{ module.group }}</em></article></div>
          <aside class="composition-summary"><h3>最终生效解析</h3><div class="rating" :class="{ blocked: missingDependencies.length || conflicts.length || !moduleCatalog.length }"><span>内部运行就绪度</span><b>{{ rating }}</b></div><section><b>继承链</b><p>平台安全基线 → {{ selectedPack.inherits_from || '独立能力包' }} → {{ selectedPack.pack_code }} → 科室范围覆盖</p></section><section><b>依赖与冲突</b><p v-if="moduleCatalog.length&&!missingDependencies.length&&!conflicts.length" class="ok">依赖完整，无互斥冲突</p><ul><li v-for="item in missingDependencies" :key="item">{{ item }}</li><li v-for="item in conflicts" :key="item">{{ item }}</li></ul></section><section><b>发布任务</b><p>合成病例回放 · 科室负责人联合签署 · 灰度观察 · 审计留痕</p></section></aside>
        </div>
      </section>

      <section class="admin-panel" v-if="selectedPack">
        <header><div><h2>灰度发布 · {{ selectedPack.pack_name }}</h2><p>同一能力包至多一个 ACTIVE 发布。</p></div><button class="button primary" :disabled="Boolean(busy)" @click="capabilityDialog='release-create'">新建发布</button></header>
        <div v-if="releases.length === 0" class="admin-empty">该能力包暂无发布。</div>
        <div v-else class="admin-table-wrap"><table class="admin-table"><thead><tr><th>版本</th><th>状态</th><th>发布时间</th><th>操作</th></tr></thead><tbody>
          <tr v-for="release in releases" :key="release.release_id">
            <td><strong>v{{ release.release_version }}</strong><small>…{{ release.release_id.slice(-8) }} · v{{ release.row_version }}</small></td>
            <td><span class="admin-status" :class="release.lifecycle_status.toLowerCase()">{{ statusLabel(release.lifecycle_status) }}</span></td>
            <td>{{ new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium' }).format(new Date(release.released_at)) }}</td>
            <td>
              <button v-if="release.lifecycle_status === 'DRAFT'" class="task-action" :disabled="Boolean(busy)" @click="transition(release, 'canary')">启动灰度</button>
              <template v-else-if="release.lifecycle_status === 'CANARY'">
                <button class="task-action" :disabled="Boolean(busy)" @click="transition(release, 'promote')">提升全量</button>
                <button class="task-action danger" :disabled="Boolean(busy)" @click="transition(release, 'rollback')">回退</button>
              </template>
              <button v-else-if="release.lifecycle_status === 'ACTIVE'" class="task-action" :disabled="Boolean(busy)" @click="transition(release, 'retire')">退休</button>
              <span v-else>—</span>
            </td>
          </tr>
        </tbody></table></div>
      </section>
    </template>

    <BusinessActionDialog :open="Boolean(capabilityDialog)" :title="capabilityDialogTitle" :description="capabilityDialog==='pack-delete'?'停用后将退出运行时能力解析，但保留继承与发布历史。':'确认后写入数据库，并生成审计与可靠事件证据。'" :confirm-label="capabilityDialog==='pack-delete'?'确认删除':'确认保存'" :danger="capabilityDialog==='pack-delete'" :busy="Boolean(busy)" width="wide" @cancel="capabilityDialog=null" @confirm="confirmCapabilityDialog">
      <template v-if="capabilityDialog==='pack-create'||capabilityDialog==='pack-edit'"><div class="dialog-grid"><label>能力包编码<input v-model="packForm.packCode" maxlength="96" required :disabled="capabilityDialog==='pack-edit'" placeholder="例：PACK-TERTIARY" /></label><label>能力包名称<input v-model="packForm.packName" maxlength="256" required placeholder="例：三级医院标准包" /></label></div><label>继承编码（可选）<select v-model="packForm.inheritsFrom"><option value="">独立能力包</option><option v-for="pack in packs.filter(item=>item.status==='ACTIVE'&&item.pack_code!==packForm.packCode)" :key="pack.pack_code" :value="pack.pack_code">{{ pack.pack_name }}（{{ pack.pack_code }}）</option></select></label><p class="dialog-warning">继承链会做存在性、自继承和循环依赖校验。</p></template>
      <p v-else-if="capabilityDialog==='pack-delete'" class="dialog-warning">确认删除“{{ targetPack?.pack_name }}”？系统会安全停用能力包，已发布业务和历史证据仍绑定原版本。</p>
      <template v-else-if="capabilityDialog==='composition-edit'"><div class="module-catalog"><label v-for="module in moduleCatalog" :key="module.code" :class="{ selected: selectedModules.includes(module.code), protected: module.protected }"><input v-model="selectedModules" type="checkbox" :value="module.code" :disabled="module.protected" /><span><b>{{ module.name }}</b><code>{{ module.code }}</code></span><em>{{ module.group }}{{ module.protected ? ' · 受保护' : '' }}</em></label></div><p v-if="missingDependencies.length||conflicts.length" class="dialog-warning">{{ [...missingDependencies,...conflicts].join('；') }}</p></template>
      <template v-else-if="capabilityDialog==='release-create'"><div class="dialog-grid"><label>发布版本<input v-model="releaseForm.releaseVersion" maxlength="64" required placeholder="例：1.2.0" /></label><label>计划发布时间<input v-model="releaseForm.releasedAt" type="datetime-local" required /></label></div><p class="dialog-warning">创建后先进入草稿，需经过灰度和提升才能影响运行时流程。</p></template>
    </BusinessActionDialog>
  </section>
</template>

<style scoped>
.config-technical-details summary { cursor: pointer; color: #42627c; }.config-technical-details[open] > summary { margin-bottom: 8px; }
.admin-content{min-width:0}.page-actions,.row-actions{display:flex;flex-wrap:wrap;gap:8px}.capability-stack{display:grid;gap:14px}.capability-stack .admin-table-wrap{max-height:430px;overflow:auto}.capability-stack .admin-table thead{position:sticky;z-index:1;top:0;background:#f7f9fb}.composition-panel{margin-top:14px}.composition-layout{display:grid;grid-template-columns:minmax(0,1.5fr) minmax(260px,.8fr);gap:14px;padding:14px;min-width:0}.module-catalog{display:grid;grid-template-columns:1fr 1fr;gap:9px;min-width:0}.module-catalog label,.module-catalog article{display:grid;grid-template-columns:auto minmax(0,1fr) auto;gap:9px;align-items:center;padding:10px;border:1px solid #dce4eb;border-radius:8px;background:#fff;min-width:0}.module-catalog label{cursor:pointer}.module-catalog label.selected,.module-catalog article.selected{border-color:#75acd3;background:#f2f8fc}.module-catalog label.protected,.module-catalog article.protected{border-color:#dfc682;background:#fffaf0}.module-catalog--readonly{max-height:520px;padding-right:4px;overflow:auto}.module-catalog--readonly article{grid-template-columns:minmax(0,1fr) auto}.module-catalog span{display:grid;gap:4px;min-width:0}.module-catalog code{font-size:9px;color:#6c7887;overflow-wrap:anywhere}.module-catalog em{font-style:normal;font-size:9px;color:#657487}.composition-summary{display:grid;gap:11px;padding:14px;border:1px solid #dce4eb;border-radius:9px;background:#f7f9fb;min-width:0}.composition-summary h3{margin:0;font-size:14px}.composition-summary section{padding-top:10px;border-top:1px solid #dfe6ec}.composition-summary p{margin:6px 0;color:#647386;font-size:11px;line-height:1.55;overflow-wrap:anywhere}.composition-summary ul{margin:7px 0 0;padding-left:18px;color:#a33131;font-size:11px}.rating{display:flex;justify-content:space-between;gap:10px;padding:11px;border:1px solid #abd9bf;border-radius:7px;background:#edf9f2;color:#236a42}.rating.blocked{border-color:#edb7b7;background:#fff0f0;color:#9b3131}.ok{color:#28764a!important}.task-action.danger{color:#a43131}@media(max-width:800px){.composition-layout{grid-template-columns:1fr}.module-catalog{grid-template-columns:1fr}.module-catalog--readonly{max-height:460px}}
.capability-stack .admin-table-wrap{max-height:clamp(320px,42vh,430px)}
.composition-layout{gap:12px;padding:12px}
.module-catalog--readonly{max-height:clamp(380px,48vh,500px)}
.composition-summary{gap:10px;padding:12px}
.composition-summary section{padding-top:9px}
.rating{padding:10px}
</style>
