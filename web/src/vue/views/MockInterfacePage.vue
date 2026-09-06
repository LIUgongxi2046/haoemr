<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, ref, watch } from 'vue';
import type { ConfigurationItemWire, MockInterfaceWire, MockInvocationResultWire } from '../../generated/contracts';
import { issueConfigurationLease, listConfigurations } from '../../api/config';
import {
  invokeMockInterface,
  issueMockLease,
  listMockInterfaceRuns,
  listMockInterfaces,
  type MockInterfaceRun,
} from '../../api/mock';
import { toClinicalIssue } from '../clinical-error';
import { mockInterfaceSubmenus, simulationWorkbenches, type SimulationWorkbenchId } from '../simulation-workbenches';

const leaseQuery = useQuery({
  queryKey: ['mock', 'lease'],
  queryFn: () => issueMockLease(),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const interfacesQuery = useQuery({
  queryKey: ['mock', 'interfaces'],
  queryFn: () => listMockInterfaces(leaseQuery.data.value!),
  enabled: () => Boolean(leaseQuery.data.value), retry: false,
});
const configurationLeaseQuery = useQuery({
  queryKey: ['mock', 'configuration-lease'], queryFn: issueConfigurationLease,
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const profilesQuery = useQuery({
  queryKey: ['mock', 'profiles'],
  queryFn: () => listConfigurations(configurationLeaseQuery.data.value!, 'MOCK_INTERFACE_PROFILE'),
  enabled: () => Boolean(configurationLeaseQuery.data.value), retry: false,
});
const issue = computed(() => {
  const error = leaseQuery.error.value ?? interfacesQuery.error.value ?? configurationLeaseQuery.error.value ?? profilesQuery.error.value;
  return error ? toClinicalIssue(error) : null;
});

const selected = ref<MockInterfaceWire | null>(null);
const busyCode = ref('');
const notice = ref('');
const lastResult = ref<MockInvocationResultWire | null>(null);
const lastRun = ref<MockInterfaceRun | null>(null);
const selections = ref<Record<string, { profileKey: string; scenario: 'SUCCESS' | 'DEGRADED' | 'UNAVAILABLE' }>>({});
const replayKeys = ref<Record<string, string>>({});
const submenuCards = mockInterfaceSubmenus
  .filter(([id]) => id !== 'mock-interfaces')
  .map(([id, label]) => {
    const workbenchId = id as SimulationWorkbenchId;
    const delegated = ['ai-capture', 'model-connection', 'model-routing'].includes(workbenchId);
    return { id: workbenchId, label, definition: simulationWorkbenches[workbenchId], delegated, target: delegated ? `/${workbenchId}` : `/mock-interfaces/${workbenchId}` };
  });
const profilesFor = (code: string): ConfigurationItemWire[] => (profilesQuery.data.value ?? []).filter((profile) =>
  profile.status === 'ACTIVE' && profile.validation_state === 'VALID' && profile.approval_state === 'APPROVED'
  && profile.payload?.interface_code === code && profile.payload?.production_adapter_state === 'SYNTHETIC_ONLY');
function selectionFor(code: string) {
  return selections.value[code] ?? { profileKey: '', scenario: 'SUCCESS' as const };
}
function setProfile(code: string, profileKey: string) {
  selections.value[code] = { ...selectionFor(code), profileKey };
}
function setScenario(code: string, scenario: 'SUCCESS' | 'DEGRADED' | 'UNAVAILABLE') {
  selections.value[code] = { ...selectionFor(code), scenario };
}
watch([() => interfacesQuery.data.value, () => profilesQuery.data.value], ([items]) => {
  if (!selected.value && items?.[0]) selected.value = items[0];
  for (const item of items ?? []) {
    if (!selectionFor(item.code).profileKey) setProfile(item.code, profilesFor(item.code)[0]?.config_key ?? '');
  }
}, { immediate: true });

async function invoke(code: string) {
  const lease = leaseQuery.data.value;
  const selection = selectionFor(code);
  if (!lease || busyCode.value) return;
  if (!selection.profileKey) {
    notice.value = 'MOCK_PROFILE_REQUIRED：请先为该接口选择已批准且已发布的仿真配置。';
    return;
  }
  busyCode.value = code; notice.value = '';
  try {
    const replaySlot = `${code}|${selection.profileKey}|${selection.scenario}`;
    const idempotencyKey = replayKeys.value[replaySlot] ?? crypto.randomUUID();
    replayKeys.value[replaySlot] = idempotencyKey;
    lastResult.value = await invokeMockInterface(lease, code, {
      profile_key: selection.profileKey,
      simulation_scenario: selection.scenario,
    }, idempotencyKey);
  } catch (error) {
    const next = toClinicalIssue(error);
    notice.value = `${next.code}：${next.message}`;
  } finally {
    // The invocation response intentionally contains only synthetic payload. Read the persisted
    // run separately so successful and failed scenarios expose the same auditable evidence link.
    try {
      const runs = await listMockInterfaceRuns(lease, { profileKey: selection.profileKey });
      lastRun.value = runs.find((run) => run.interface_code === code && run.scenario === selection.scenario) ?? null;
    } catch (error) {
      const next = toClinicalIssue(error);
      notice.value = notice.value || `${next.code}：调用已结束，但运行证据暂时无法读取。`;
    }
    busyCode.value = '';
  }
}

function select(item: MockInterfaceWire) {
  selected.value = item;
  lastResult.value = null;
  lastRun.value = null;
}

function systemTypeLabel(value: string) {
  return ({ INTEGRATION_LIS: '检验 LIS', INTEGRATION_PACS: '影像 PACS', INTEGRATION_HIS: '医保 HIS', INTEGRATION_CA: '电子签名 CA', INTEGRATION_HIE: '区域平台', MODEL: '模型', DEVICE: '设备', DICTATION: '语音', IDENTITY: '身份 IdP', ARCHIVE_SCAN: '扫描', SECURITY_AV: '杀毒引擎', DOCUMENT_CDA: 'CDA 校验', ARCHIVE_STORAGE: '存储', PATHOLOGY: '病理', ANESTHESIA: '麻醉', THERAPY: '治疗', REPORT_GATEWAY: '直报网关', EMPI: '患者主索引 EMPI' } as Record<string, string>)[value] ?? value;
}
</script>

<template>
  <section data-page-root class="content vue-native-page mock-interface-page">
    <div class="page-head">
      <div class="page-title"><h1>模拟接口</h1><p>依赖外部系统的接口统一注册：确定性合成数据 + 对接标准接口 + 对接文档 · 仅供 dev-synthetic 验证，不进入真实临床事实</p></div>
      <div class="head-actions"><button class="btn" type="button" @click="interfacesQuery.refetch()">刷新</button></div>
    </div>

    <div v-if="leaseQuery.isPending.value || interfacesQuery.isPending.value || configurationLeaseQuery.isPending.value || profilesQuery.isPending.value" class="card"><div class="card-body">正在读取模拟接口注册表与已发布配置…</div></div>
    <div v-else-if="issue" class="card"><div class="card-body">加载失败：{{ issue.code }} {{ issue.message }}</div></div>

    <template v-else>
      <div class="metric-grid" aria-label="模拟接口概览">
        <div class="metric"><div class="name">已注册接口</div><div class="value">{{ interfacesQuery.data.value?.length ?? 0 }}</div><div class="trend">确定性合成 handler</div></div>
        <div class="metric"><div class="name">系统类型</div><div class="value">{{ new Set(interfacesQuery.data.value?.map((i) => i.system_type)).size }}</div><div class="trend">LIS/PACS/HIS/CA/区域平台/模型/设备/语音/IdP/扫描/杀毒/CDA 校验/存储/病理/麻醉/治疗/直报网关/主索引 EMPI</div></div>
        <div class="metric"><div class="name">对接标准接口</div><div class="value">{{ interfacesQuery.data.value?.length ?? 0 }}</div><div class="trend">每个接口含协议名 + 请求/响应 schema + 文档</div></div>
        <div class="metric"><div class="name">替换方式</div><div class="value">契约</div><div class="trend">真实适配器实现同一契约即可替换 mock</div></div>
      </div>

      <section class="admin-panel submenu-catalog">
        <header><div><h2>{{ submenuCards.length }} 个模拟接口子菜单</h2><p>每个子菜单均提供完整接口文档、三级医院仿真配置、弹窗式新建/编辑/删除和真实流程门禁。</p></div></header>
        <div class="submenu-grid">
          <RouterLink v-for="card in submenuCards" :key="card.id" :to="card.target" class="submenu-card">
            <span>{{ card.label }}</span><strong>{{ card.definition.title }}</strong><small>{{ card.definition.subtitle }}</small><b>进入配置与联调 →</b>
          </RouterLink>
        </div>
      </section>

      <div v-if="notice" class="inline-notice error" role="status">{{ notice }}</div>

      <div class="admin-layout">
        <section class="admin-panel">
          <header><div><h2>模拟接口注册表</h2><p>先选择已批准发布的仿真配置和场景；同配置、同场景再次调用会重放同一运行证据。</p></div></header>
          <div class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>接口</th><th>系统类型</th><th>已发布仿真配置</th><th>场景</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="item in interfacesQuery.data.value ?? []" :key="item.code" :class="{ 'selected-row': selected?.code === item.code }">
                  <td><button class="link-button" @click="select(item)"><strong>{{ item.display_name }}</strong><small><code>{{ item.code }}</code></small></button></td>
                  <td><span class="admin-status muted">{{ systemTypeLabel(item.system_type) }}</span></td>
                  <td><select :value="selectionFor(item.code).profileKey" :aria-label="`${item.display_name} 的已发布仿真配置`" @change="setProfile(item.code, ($event.target as HTMLSelectElement).value)"><option value="">无可用配置</option><option v-for="profile in profilesFor(item.code)" :key="profile.config_id" :value="profile.config_key">{{ profile.display_name }} · v{{ profile.row_version }}</option></select></td>
                  <td><select :value="selectionFor(item.code).scenario" :aria-label="`${item.display_name} 的仿真场景`" @change="setScenario(item.code, ($event.target as HTMLSelectElement).value as 'SUCCESS' | 'DEGRADED' | 'UNAVAILABLE')"><option value="SUCCESS">成功</option><option value="DEGRADED">降级</option><option value="UNAVAILABLE">不可用</option></select></td>
                  <td><button class="task-action" :disabled="Boolean(busyCode) || !selectionFor(item.code).profileKey" @click="invoke(item.code)">{{ busyCode === item.code ? '调用中…' : '调用 / 重放' }}</button></td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <aside class="admin-panel">
          <header><div><h2>对接标准接口</h2><p>{{ selected ? selected.display_name : '从左侧点选接口查看' }}</p></div></header>
          <div v-if="!selected" class="empty-state"><span>⇄</span><p>尚未选择接口</p><small>点选左侧接口查看对接标准与文档</small></div>
          <div v-else class="card-body interface-document-body">
            <div class="folder-row">模拟调用地址<span><code>POST /api/v1/mock-interfaces/{{ selected.code }}/invoke</code></span></div>
            <div class="folder-row">对接标准<span><code>{{ selected.standard_interface ?? '—' }}</code></span></div>
            <div class="folder-row">认证与上下文<span>Bearer + 机构/院区上下文</span></div>
            <div class="folder-row">写操作幂等<span><code>Idempotency-Key</code></span></div>
            <div class="section-title">用途与数据边界</div>
            <div class="notice info">{{ selected.description }}。本接口只返回确定性合成数据，禁止传入真实 PHI 或生产凭据。</div>
            <div class="section-title">标准请求 schema</div>
            <pre class="mock-payload">{{ JSON.stringify(selected.request_schema ?? {}, null, 2) }}</pre>
            <div class="section-title">标准响应 schema</div>
            <pre class="mock-payload">{{ JSON.stringify(selected.response_schema ?? {}, null, 2) }}</pre>
            <div class="section-title">对接文档</div>
            <div class="notice info"><div class="notice-title">如何替换真实适配器</div>{{ selected.integration_doc ?? '—' }}</div>
            <div class="section-title">公共错误与恢复</div>
            <div class="api-errors"><code>422 MOCK_SCENARIO_INVALID</code><span>修正 SUCCESS / DEGRADED / UNAVAILABLE 场景参数</span><code>503 MOCK_DEPENDENCY_UNAVAILABLE</code><span>保留上下文并转人工流程，恢复后使用同一业务键重放</span><code>404 MOCK_INTERFACE_UNKNOWN</code><span>核对接口编码和已发布配置</span></div>
          </div>
        </aside>
      </div>

      <section v-if="lastResult" class="admin-panel recent-result-panel">
        <header><div><h2>最近调用结果</h2><p>接口 {{ lastResult.mock_interface_code }} 的确定性合成响应。</p></div></header>
        <div class="card-body">
          <div class="folder-row">请求 ID<span><code>…{{ lastResult.request_id.slice(-8) }}</code></span></div>
          <div class="folder-row">产生时间<span>{{ new Date(lastResult.produced_at).toLocaleString('zh-CN', { hour12: false }) }}</span></div>
          <div class="folder-row">配置与场景<span><code>{{ selectionFor(lastResult.mock_interface_code).profileKey }} · {{ selectionFor(lastResult.mock_interface_code).scenario }}</code></span></div>
          <div class="notice info recent-result-notice"><div class="notice-title">提示</div>{{ lastResult.notice }}</div>
          <pre class="mock-payload">{{ JSON.stringify(lastResult.payload, null, 2) }}</pre>
        </div>
      </section>

      <section v-if="lastRun" class="admin-panel recent-result-panel" aria-label="最近运行证据">
        <header><div><h2>最近运行证据</h2><p>{{ lastRun.interface_code }} · {{ lastRun.scenario }} · {{ lastRun.status }}</p></div><span class="admin-status" :class="lastRun.status === 'COMPLETED' ? 'green' : 'amber'">{{ lastRun.status }}</span></header>
        <div class="card-body">
          <div class="folder-row">配置版本<span><code>v{{ lastRun.profile_version }}</code></span></div>
          <div class="folder-row">合成记录数<span>{{ lastRun.record_count }}</span></div>
          <div class="folder-row">证据摘要<span><code>…{{ lastRun.evidence_hash.slice(-12) }}</code></span></div>
          <div class="folder-row">开始时间<span>{{ new Date(lastRun.started_at).toLocaleString('zh-CN', { hour12: false }) }}</span></div>
          <RouterLink class="button secondary" :to="`/mock-interfaces/${lastRun.workbench_id}/profiles/${selectionFor(lastRun.interface_code).profileKey}/scenarios/${lastRun.scenario}/runs/${lastRun.run_id}/evidence`">查看完整运行与证据</RouterLink>
        </div>
      </section>
    </template>
  </section>
</template>

<style scoped>
.mock-interface-page { display: grid; gap: 18px; width: min(100%, 1280px); margin-inline: auto; }
.mock-interface-page > * { min-width: 0; }
.mock-interface-page .page-head { height: auto; min-height: 64px; margin: 0; padding: 10px 0; }
.mock-interface-page .page-title { min-width: 0; }
.mock-interface-page .page-title p { max-width: 1040px; line-height: 1.55; overflow-wrap: anywhere; }
.mock-interface-page .head-actions { flex: 0 0 auto; gap: 10px; }
.mock-interface-page .metric-grid { gap: 12px; }
.mock-interface-page .metric { display: grid; align-content: center; min-height: 92px; padding: 13px 14px; }
.mock-interface-page .metric .trend { line-height: 1.45; overflow-wrap: anywhere; }
.mock-payload { margin: 8px 0 0; padding: 12px; max-height: 260px; overflow: auto; color: #26384d; border: 1px solid var(--line); border-radius: 8px; background: #f8fafc; font-size: 11px; line-height: 1.55; white-space: pre-wrap; overflow-wrap: anywhere; word-break: break-word; }
.link-button { padding: 0; border: 0; background: transparent; text-align: left; cursor: pointer; }
.selected-row td { background: #f0f6ff; }
.submenu-catalog { margin-top: 0; }
.submenu-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 14px; padding: 16px; }
.submenu-card { display: grid; align-content: start; gap: 6px; min-width: 0; padding: 13px 14px; color: inherit; border: 1px solid var(--line); border-radius: 10px; background: #fff; text-decoration: none; overflow-wrap: anywhere; }
.submenu-card:hover { border-color: #83ace0; background: #f7fbff; }
.submenu-card > span { width: max-content; padding: 3px 7px; color: #245493; border-radius: 999px; background: #eaf3ff; font-size: 10px; }
.submenu-card > small { min-height: 34px; color: var(--muted); line-height: 1.55; }
.submenu-card > b { color: var(--blue); font-size: 11px; }
.mock-interface-page .admin-layout { gap: 16px; }
.mock-interface-page .admin-table { min-width: 760px; }
.interface-document-body { min-width: 0; }
.interface-document-body .folder-row { gap: 14px; }
.interface-document-body .folder-row > span { min-width: 0; text-align: right; overflow-wrap: anywhere; }
.interface-document-body .folder-row code { white-space: normal; overflow-wrap: anywhere; }
.api-errors { display: grid; grid-template-columns: minmax(160px, auto) 1fr; gap: 8px 12px; margin-top: 8px; line-height: 1.5; }
.recent-result-panel, .recent-result-notice { margin-top: 0; }

@media (max-width: 760px) {
  .mock-interface-page { gap: 16px; }
  .mock-interface-page .page-head { align-items: flex-start; flex-direction: column; gap: 12px; padding: 12px 0; }
  .mock-interface-page .head-actions { margin-left: 0; }
  .mock-interface-page .metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
  .mock-interface-page .metric { min-height: 96px; padding: 12px; }
  .submenu-grid { grid-template-columns: minmax(0, 1fr); gap: 12px; padding: 14px; }
  .api-errors { grid-template-columns: minmax(0, 1fr); }
  .mock-interface-page .admin-table { min-width: 680px; }
  .interface-document-body .folder-row { align-items: flex-start; flex-direction: column; gap: 4px; }
  .interface-document-body .folder-row > span { text-align: left; }
}
</style>
