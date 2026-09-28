<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { clinicalContext, ClinicalApiError } from '../../clinical-api';
import { configurationPackScope, loadConfigurationPacks, precheckConfigurationPack, installConfigurationPack, loadConfigurationPackOperations, reconcileConfigurationPackOperation } from '../../api/configuration-packs';
import type { ConfigurationPackCatalogItemWire, ConfigurationPackPrecheckWire, ConfigurationPackOperationWire } from '../../generated/contracts';
import { canConfirmConfigurationPack, configurationPackFailureAction, configurationPackStatus } from '../configuration-pack-state';
import { toClinicalIssue } from '../clinical-error';
import AdminActionDialog from './AdminActionDialog.vue';
import ClinicalPageState from './ClinicalPageState.vue';
import AdminDataPager from './AdminDataPager.vue';

const props = defineProps<{ mode: 'initialize' | 'operations' }>();
const route = useRoute(); const router = useRouter();
const scope = computed(configurationPackScope);
const catalog = useQuery({ queryKey: ['configuration-pack-catalog', scope], queryFn: loadConfigurationPacks, retry: false, gcTime: 0, enabled: () => props.mode === 'initialize' });
const operations = useQuery({ queryKey: ['configuration-pack-operations', scope], queryFn: loadConfigurationPackOperations, retry: false, gcTime: 0,
  refetchInterval: (query) => query.state.data?.some((x: ConfigurationPackOperationWire) => x.status === 'RUNNING') ? 2000 : false });
const typeFilter = ref(''); const compatibilityFilter = ref(''); const installedFilter = ref('');
const keyword = ref(''); const page = ref(1); const pageSize = 10;
const raw = ref(''); const selected = ref<ConfigurationPackCatalogItemWire | null>(null);
const check = ref<ConfigurationPackPrecheckWire | null>(null); const keep = ref<string[]>([]); const dirty = ref(false);
const drafts = ref<string[]>([]);
const busy = ref(false); const notice = ref(''); const failure = ref(''); const open = ref(false);
const stage = ref<'DETAIL' | 'CHECK' | 'CONFIRM' | 'INSTALLING' | 'UNKNOWN'>('DETAIL');
const operation = ref<ConfigurationPackOperationWire | null>(null); const detailsOpen = ref(false);
const pendingKey = ref(''); const pendingPrecheck = ref(''); const pendingScope = ref('');
const now = ref(Date.now()); const timer = window.setInterval(() => { now.value = Date.now(); }, 1000);
onBeforeUnmount(() => window.clearInterval(timer));
const canConfirm = computed(() => !busy.value && canConfirmConfigurationPack(check.value, dirty.value, now.value));
const activeIssue = computed(() => { const error = props.mode === 'initialize' ? catalog.error.value : operations.error.value; return error ? toClinicalIssue(error) : null; });
function isInstalled(item: ConfigurationPackCatalogItemWire) { return operations.data.value?.some(op => op.pack_code === item.code && op.pack_version === item.version && op.status === 'SUCCEEDED') ?? false; }
const filteredCatalog = computed(() => (catalog.data.value ?? []).filter(item => `${item.name} ${item.code} ${item.version}`.toLowerCase().includes(keyword.value.trim().toLowerCase())
  && (!typeFilter.value || item.type === typeFilter.value)
  && (!compatibilityFilter.value || (compatibilityFilter.value === 'YES' ? item.compatible === true : item.compatible === false))
  && (!installedFilter.value || (operations.data.value && !operations.error.value && (installedFilter.value === 'YES' ? isInstalled(item) : !isInstalled(item))))));
const filteredOperations = computed(() => (operations.data.value ?? []).filter(item => `${item.pack_code} ${item.pack_version} ${configurationPackStatus[item.status]}`.toLowerCase().includes(keyword.value.trim().toLowerCase())));
watch(keyword, () => { page.value = 1; });
watch(scope, () => { raw.value = ''; selected.value = null; check.value = null; keep.value = []; drafts.value = []; open.value = false; detailsOpen.value = false; operation.value = null; failure.value = ''; pendingKey.value = ''; pendingPrecheck.value = ''; });
watch(() => operations.data.value, (rows) => {
  const id = String(route.query.operation ?? ''); const found = rows?.find(item => item.operation_id === id);
  if (found) { operation.value = found; detailsOpen.value = true; }
  else if (operation.value) operation.value = rows?.find(item => item.operation_id === operation.value?.operation_id) ?? null;
});
function decision(code: string) { return keep.value.includes(code) ? 'KEEP' : drafts.value.includes(code) ? 'DRAFT' : ''; }
function decide(code: string, event: Event) {
  const value = (event.target as HTMLSelectElement).value;
  keep.value = keep.value.filter(item => item !== code); drafts.value = drafts.value.filter(item => item !== code);
  if (value === 'KEEP') keep.value.push(code);
  if (value === 'DRAFT') drafts.value.push(code);
  dirty.value = true;
}
function describe(error: unknown) { const value = toClinicalIssue(error); return `${value.code}：${value.message}`; }
function choose(item: ConfigurationPackCatalogItemWire) { selected.value = item; prepare(item.manifest_json); }
function prepare(value: string) { raw.value = value; check.value = null; keep.value = []; drafts.value = []; stage.value = 'DETAIL'; failure.value = ''; dirty.value = false; open.value = true; }
async function upload(event: Event) {
  const input = event.target as HTMLInputElement; const file = input.files?.[0]; if (!file) return;
  if (file.size > 10 * 1024 * 1024 || !file.name.toLowerCase().endsWith('.json')) { failure.value = '请选择不超过 10 MB 的 JSON 配置文件'; input.value = ''; return; }
  const before = scope.value; const value = await file.text(); if (scope.value !== before) return;
  selected.value = null; prepare(value); input.value = '';
}
async function runCheck() {
  if (busy.value) return; busy.value = true; failure.value = ''; const before = scope.value;
  try { const result = await precheckConfigurationPack({ organization_id: clinicalContext.organizationId, facility_id: clinicalContext.facilityId, manifest_json: raw.value, keep_codes: keep.value, draft_codes: drafts.value }, crypto.randomUUID());
    if (before !== scope.value) return; check.value = result; stage.value = 'CHECK'; dirty.value = false;
  } catch (error) { if (before === scope.value) failure.value = describe(error); }
  finally { busy.value = false; }
}
async function submit(replay = false) {
  if (busy.value || (!replay && !canConfirm.value)) return;
  if (!replay) { pendingKey.value = crypto.randomUUID(); pendingPrecheck.value = check.value!.precheck_id; pendingScope.value = scope.value; }
  if (pendingScope.value !== scope.value || !pendingKey.value) return;
  busy.value = true; failure.value = ''; stage.value = 'INSTALLING';
  try { const result = await installConfigurationPack({ precheck_id: pendingPrecheck.value, retry_of: route.query.retry_of ? String(route.query.retry_of) : null }, pendingKey.value);
    if (pendingScope.value !== scope.value) return;
    open.value = false; await router.push({ path: '/admin-jobs', query: { view: 'installations', operation: result.operation_id } });
  } catch (error) { if (pendingScope.value === scope.value) { failure.value = describe(error); if (error instanceof ClinicalApiError && error.status < 500) { stage.value = 'CHECK'; dirty.value = true; } else stage.value = 'UNKNOWN'; } }
  finally { busy.value = false; }
}
async function reconcile(item: ConfigurationPackOperationWire) {
  if (busy.value) return; busy.value = true; failure.value = ''; const before = scope.value;
  try { const result = await reconcileConfigurationPackOperation(item.operation_id); if (before !== scope.value) return; operation.value = result; await operations.refetch(); notice.value = '已查询原操作；执行中的任务继续等待，未创建第二次安装。'; }
  catch (error) { if (before === scope.value) failure.value = describe(error); } finally { busy.value = false; }
}
function exportEvidence(item: ConfigurationPackOperationWire) {
  const blob = new Blob([JSON.stringify(item, null, 2)], { type: 'application/json' }); const url = URL.createObjectURL(blob);
  const link = document.createElement('a'); link.href = url; link.download = `configuration-install-${item.operation_id}.json`; link.click(); URL.revokeObjectURL(url);
}
function tone(status: string) { return status === 'SUCCEEDED' ? 'green' : status === 'FAILED_ROLLED_BACK' ? 'red' : 'amber'; }
function format(value: string | null) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'; }
</script>

<template>
  <section data-page-root class="content admin-content vue-native-page">
    <div class="page-head"><div class="page-title"><h1>{{ mode === 'initialize' ? '系统参数与功能开关' : '通知任务与治理执行中心' }}</h1><p>{{ mode === 'initialize' ? '模块初始化只导入配置草稿，发布继续沿用原模板审批流程。' : '配置安装记录保留真实结果；关闭页面不会取消已提交的安装。' }}</p></div><div class="head-actions"><button class="btn" :disabled="busy" @click="mode === 'initialize' ? catalog.refetch() : operations.refetch()">刷新</button></div></div>
    <div v-if="failure" class="notice hard" role="alert">{{ failure }}</div><div v-if="notice" class="notice info" role="status">{{ notice }}</div>
    <ClinicalPageState v-if="mode === 'initialize' ? catalog.isPending.value : operations.isPending.value" kind="loading" message="正在读取配置安装信息" />
    <ClinicalPageState v-else-if="activeIssue" kind="error" :code="activeIssue.code" :message="activeIssue.message" @retry="mode === 'initialize' ? catalog.refetch() : operations.refetch()" />
    <template v-else>
      <section class="card"><div class="card-head">{{ mode === 'initialize' ? '模块初始化' : '配置安装记录' }}</div><div class="toolbar"><input v-model="keyword" class="search" :aria-label="mode === 'initialize' ? '筛选配置包' : '筛选安装记录'" placeholder="按名称、编码或版本筛选"><label v-if="mode === 'initialize'" class="btn">选择 JSON 配置文件<input type="file" accept=".json,application/json" :disabled="busy" aria-label="选择 JSON 配置文件" @change="upload"></label></div>
        <div v-if="mode === 'initialize'" class="card-body">
          <div class="notice info">配置来源须由部署方认可。包不含患者或账号，安装后到原“模板输出”由另一名授权管理员审批发布。</div>
          <div v-if="!filteredCatalog.length" class="admin-empty">{{ keyword ? '没有匹配的配置包。' : '尚无预置可安装包，可选择部署方提供的受控 JSON 文件。' }}<button v-if="keyword" class="btn" @click="keyword = ''">清除筛选</button></div>
          <div class="toolbar"><select v-model="typeFilter" class="select" aria-label="配置包类型"><option value="">全部类型</option><option value="CONFIG">配置包</option><option value="DEMO">演示包（不能从此入口安装）</option></select><select v-model="compatibilityFilter" class="select" aria-label="应用兼容性"><option value="">全部兼容状态</option><option value="YES">兼容当前应用</option><option value="NO">不兼容当前应用</option></select><select v-model="installedFilter" class="select" aria-label="配置包安装状态" :disabled="operations.isPending.value || Boolean(operations.error.value)"><option value="">全部安装状态</option><option value="YES">当前院区已安装</option><option value="NO">当前院区未安装</option></select></div>
          <p v-if="operations.error.value" role="alert">安装状态读取失败，无法判断是否已安装。<button class="btn" @click="operations.refetch()">重试安装状态</button></p>
          <div v-for="item in filteredCatalog" :key="`${item.code}-${item.version}`" class="folder-row"><div><b>{{ item.name }}</b><p>{{ item.code }} · {{ item.version }} · {{ item.type === 'DEMO' ? '演示包' : '配置包' }} · {{ item.template_count ?? '—' }} 个模板</p><small>{{ item.compatible === true ? '兼容当前应用' : item.compatible === false ? '不兼容当前应用' : '兼容性待预检' }} · {{ item.trusted ? '部署方已认可' : '来源待认可' }} · {{ operations.error.value || operations.isPending.value ? '安装状态待核对' : isInstalled(item) ? '当前院区已安装' : '当前院区未安装' }}</small></div><button class="btn primary" @click="choose(item)">查看并预检</button></div>
          <RouterLink class="btn" to="/admin-jobs?view=installations">配置安装记录</RouterLink> <RouterLink class="btn" to="/capability-pack">原能力包与灰度发布</RouterLink>
        </div>
        <template v-else><div v-if="!filteredOperations.length" class="admin-empty">{{ keyword ? '没有匹配的安装记录。' : '尚无配置安装记录。' }}<RouterLink class="btn" to="/admin-parameters?view=initialization">前往模块初始化</RouterLink></div>
          <div v-else class="admin-table-wrap"><table class="table"><thead><tr><th>配置包 / 版本</th><th>结果</th><th>发起人</th><th>时间</th><th>操作</th></tr></thead><tbody><tr v-for="item in filteredOperations.slice((page-1)*pageSize,page*pageSize)" :key="item.operation_id"><td>{{ item.pack_code }} · {{ item.pack_version }}</td><td><span class="status" :class="tone(item.status)">{{ configurationPackStatus[item.status] }}</span></td><td>…{{ item.requested_by.slice(-8) }}</td><td>{{ format(item.created_at) }}</td><td><button class="btn" @click="operation = item; detailsOpen = true">查看安装详情</button></td></tr></tbody></table></div>
          <p v-if="(operations.data.value?.length ?? 0) === 500" class="notice info">当前显示最近 500 条记录，更早证据请到管理审计按时间查询。</p>
          <AdminDataPager v-model:page="page" :total="filteredOperations.length" :page-size="pageSize" />
        </template>
      </section>
    </template>
    <AdminActionDialog v-model:open="open" :title="stage === 'DETAIL' ? '配置包详情' : stage === 'CONFIRM' ? '确认导入配置草稿' : stage === 'UNKNOWN' ? '安装结果待确认' : stage === 'INSTALLING' ? '正在导入配置' : '配置导入预检'" size="large" :busy="busy && stage !== 'INSTALLING'">
      <div v-if="failure" class="notice hard" role="alert">{{ failure }}</div>
      <template v-if="stage === 'DETAIL'"><div class="folder-row">配置名称<span>{{ selected?.name ?? '本地选择的配置文件，等待服务端预检' }}</span></div><div class="folder-row">发布来源<span>{{ selected?.publisher ?? '待验证' }}</span></div><div class="folder-row">许可<span>{{ selected?.license ?? '待解析' }}</span></div><div v-if="selected" class="folder-row">应用版本 / 条目数<span>{{ selected.app_version ?? '待预检' }} / {{ selected.template_count ?? '待预检' }}</span></div><div class="notice info">完整性和来源可信分别核验；首版只接受受约束 JSON，不能执行 SQL 或脚本。</div><button class="btn primary" :disabled="busy" @click="runCheck">运行预检</button></template>
      <template v-else-if="(stage === 'CHECK' || stage === 'CONFIRM') && check">
        <div class="folder-row">目标机构 / 院区<span>…{{ check.organization_id.slice(-8) }} / …{{ check.facility_id.slice(-8) }}</span></div><div class="folder-row">包与版本<span>{{ check.pack_code }} · {{ check.pack_version }}</span></div><div class="folder-row">来源可信<span>{{ check.trusted ? '部署方已认可' : '尚未认可' }}</span></div><details><summary>文件完整性 SHA-256</summary><p class="pack-hash">{{ check.manifest_hash }}</p></details>
        <div v-for="item in check.issues" :key="item" class="notice hard">{{ item }}</div>
        <div v-if="dirty || Date.parse(check.expires_at) <= now" class="notice hard">预检已失效或决策已变化，请重新预检。</div>
        <div class="admin-table-wrap"><table class="table"><thead><tr><th>条目</th><th>处理</th><th>说明与差异</th></tr></thead><tbody><tr v-for="item in check.differences" :key="item.code"><td>{{ item.name }}</td><td>{{ {NEW:'新增草稿',UPDATE:'新版本草稿',KEEP:'保留医院版本',CONFLICT:'待处理'}[item.action] }}<label v-if="item.local_content && stage === 'CHECK'" class="pack-keep"><select :aria-label="`${item.name}的冲突处理`" :value="decision(item.code)" @change="decide(item.code, $event)"><option value="">按预检判断</option><option value="KEEP">保留医院版本</option><option value="DRAFT">候选内容另建草稿</option></select></label></td><td>{{ item.reason }}<details><summary>查看三方内容</summary><b>原包</b><pre>{{ item.base_content || '来源未知' }}</pre><b>医院当前</b><pre>{{ item.local_content || '尚无条目' }}</pre><b>候选内容</b><pre>{{ item.incoming_content }}</pre></details></td></tr></tbody></table></div>
        <div class="notice info">新增 {{ check.differences.filter(x=>x.action==='NEW').length }} 项，更新 {{ check.differences.filter(x=>x.action==='UPDATE').length }} 项，保留 {{ check.differences.filter(x=>x.action==='KEEP').length }} 项。仅生成草稿，不自动发布或创建患者。</div>
        <div class="head-actions"><button class="btn" :disabled="busy" @click="runCheck">重新预检</button><button v-if="stage === 'CHECK'" class="btn primary" :disabled="!canConfirm" @click="stage = 'CONFIRM'">确认导入</button><button v-else class="btn primary" :disabled="!canConfirm" @click="submit()">开始导入</button></div>
      </template>
      <div v-else-if="stage === 'INSTALLING'" class="notice info">正在写入配置草稿；关闭此窗口不取消已提交的安装，可在通知任务中查询结果。</div>
      <template v-else-if="stage === 'UNKNOWN'"><div class="notice hard">响应未确认，请先查询安装记录。使用原请求键确认结果不会创建第二次安装。</div><button class="btn primary" :disabled="busy" @click="submit(true)">使用原请求确认结果</button> <RouterLink class="btn" to="/admin-jobs?view=installations">查看原安装记录</RouterLink></template>
    </AdminActionDialog>
    <AdminActionDialog v-model:open="detailsOpen" title="配置安装详情" size="large">
      <template v-if="operation"><div class="folder-row">操作编号<span>{{ operation.operation_id }}</span></div><div class="folder-row">配置包<span>{{ operation.pack_code }} · {{ operation.pack_version }}</span></div><div class="folder-row">状态<span class="status" :class="tone(operation.status)">{{ configurationPackStatus[operation.status] }}</span></div><div class="folder-row">完成时间<span>{{ format(operation.finished_at) }}</span></div><div class="folder-row">结果<span>导入 {{ operation.imported_count }} 项 / 保留 {{ operation.retained_count }} 项</span></div><div v-if="operation.retry_of" class="folder-row">原操作<span>{{ operation.retry_of }}</span></div>
        <div v-if="operation.error_code" class="notice hard">{{ operation.error_code }}：配置写入已回滚，原已发布版本保持有效。重新预检后再重试。</div>
        <div class="head-actions"><RouterLink v-if="configurationPackFailureAction(operation.status) === 'TEMPLATES'" class="btn primary" :to="{path:'/admin-templates',query:{source_install:operation.operation_id}}">定位导入模板</RouterLink><RouterLink v-else-if="configurationPackFailureAction(operation.status) === 'RECHECK'" class="btn primary" :to="{path:'/admin-parameters',query:{view:'initialization',retry_of:operation.operation_id}}">重新预检后重试</RouterLink><button v-else class="btn primary" :disabled="busy" @click="reconcile(operation)">查询原操作</button><button class="btn" @click="exportEvidence(operation)">导出安装证据</button><RouterLink class="btn" :to="{path:'/admin-audit',query:{resource_type:'CONFIGURATION_PACK',resource_id:operation.operation_id}}">管理审计</RouterLink></div>
      </template>
    </AdminActionDialog>
  </section>
</template>

<style scoped>
.pack-hash { overflow-wrap: anywhere; }.pack-keep { display: flex; gap: 6px; align-items: center; margin-top: 8px; }pre { white-space: pre-wrap; overflow-wrap: anywhere; max-width: 520px; font-size: 11px; }input[type=file] { max-width: 220px; } .folder-row p { margin: 4px 0; color: var(--muted); }@media(max-width:720px){.head-actions{flex-wrap:wrap}.btn{min-height:44px!important}.folder-row{flex-wrap:wrap;gap:8px}}
</style>
