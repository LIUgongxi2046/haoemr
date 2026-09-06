<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref, watch } from 'vue';
import type { AgentRegistryWire } from '../../generated/contracts';
import { deactivateAgent, issueAiLease, listAgents, publishAgentVersion, registerAgent } from '../../api/ai-platform';
import { issueMedicalAgentCatalogLease, listMedicalAgentCatalog } from '../../api/medical-agents';
import AdminActionDialog from '../components/AdminActionDialog.vue';
import AdminConfirmDialog from '../components/AdminConfirmDialog.vue';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';
import { doctorFacingAiText, doctorFacingTeamName } from '../medical-ai-terminology';

const leaseQuery = useQuery({
  queryKey: ['ai-platform', 'agent-catalog', 'lease'],
  queryFn: () => issueAiLease('AI_PLATFORM_ADMIN'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const agentsQuery = useQuery({
  queryKey: ['ai-platform', 'agent-catalog', 'agents'],
  queryFn: () => listAgents(leaseQuery.data.value!),
  enabled: () => Boolean(leaseQuery.data.value),
  retry: false,
});
const issue = computed(() => (leaseQuery.error.value ?? agentsQuery.error.value)
  ? toClinicalIssue(leaseQuery.error.value ?? agentsQuery.error.value) : null);
const agents = computed(() => agentsQuery.data.value ?? []);
const activeCount = computed(() => agents.value.filter((agent) => agent.status === 'ACTIVE').length);
const medicalCatalogLeaseQuery = useQuery({
  queryKey: ['medical-agent', 'agent-catalog-lease'],
  queryFn: issueMedicalAgentCatalogLease,
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const medicalCatalogQuery = useQuery({
  queryKey: ['medical-agent', 'agent-catalog-families'],
  queryFn: () => listMedicalAgentCatalog(medicalCatalogLeaseQuery.data.value!),
  enabled: () => Boolean(medicalCatalogLeaseQuery.data.value),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const families = computed(() => medicalCatalogQuery.data.value ?? []);
const childAgentCount = computed(() => families.value.reduce((count, family) => count + family.child_agents.length, 0));
const selectedFamilyCode = ref('');
const selectedFamily = computed(() => families.value.find(
  (family) => family.main_agent.agent_code === selectedFamilyCode.value,
));

watch(families, (next) => {
  if (next.length && !next.some((family) => family.main_agent.agent_code === selectedFamilyCode.value)) {
    selectedFamilyCode.value = next[0].main_agent.agent_code;
  }
}, { immediate: true });

const form = reactive({ agentCode: '', agentName: '', agentVersion: '' });
const editingAgent = ref<AgentRegistryWire | null>(null);
const editorOpen = ref(false);
const deactivateTarget = ref<AgentRegistryWire | null>(null);
const busy = ref('');
const notice = ref('');

async function reload() {
  notice.value = '';
  await agentsQuery.refetch();
}

function nextVersion(version: string) {
  const match = version.match(/^(?:v)?(\d+)\.(\d+)\.(\d+)$/);
  return match ? `${match[1]}.${match[2]}.${Number(match[3]) + 1}` : `${version}-next`;
}

function resetForm() {
  editingAgent.value = null;
  form.agentCode = ''; form.agentName = ''; form.agentVersion = '';
}

function openCreate() {
  resetForm();
  editorOpen.value = true;
}

function editAgent(agent: AgentRegistryWire) {
  editingAgent.value = agent;
  form.agentCode = agent.agent_code; form.agentName = doctorFacingAiText(agent.agent_name);
  form.agentVersion = nextVersion(agent.agent_version); notice.value = ''; editorOpen.value = true;
}

async function register() {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || !form.agentCode.trim() || !form.agentName.trim() || !form.agentVersion.trim()) return;
  busy.value = 'create'; notice.value = '';
  try {
    if (editingAgent.value) {
      await publishAgentVersion(lease, editingAgent.value, {
        agent_name: form.agentName.trim(), agent_version: form.agentVersion.trim(),
      });
      notice.value = '新医助版本已发布，旧版本已自动停用，后续任务将使用新版本。';
    } else {
      await registerAgent(lease, {
        agent_code: form.agentCode.trim(), agent_name: form.agentName.trim(), agent_version: form.agentVersion.trim(),
      });
      notice.value = '智能医助已登记，版本记录和操作留痕已同步更新。';
    }
    resetForm();
    editorOpen.value = false;
    await agentsQuery.refetch();
    deactivateTarget.value = null;
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = ''; }
}

async function deactivate(agent: AgentRegistryWire) {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || agent.status !== 'ACTIVE') return;
  busy.value = agent.agent_registry_id; notice.value = '';
  try {
    await deactivateAgent(lease, agent);
    notice.value = `智能医助“${doctorFacingAiText(agent.agent_name)}”已停用。`;
    await agentsQuery.refetch();
    deactivateTarget.value = null;
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = ''; }
}
</script>

<template>
  <section data-page-root class="content admin-content vue-native-page">
    <div class="page-heading admin-heading">
      <div>
        <p class="eyebrow">AI中心 / 医助agent配置</p>
        <h1>医助agent设计与编排</h1>
        <p>5 个医助agent统筹 33 位专科医助，覆盖诊前、接诊、文书、质控、结果闭环和诊疗协同；所有结果均需医生确认。</p>
      </div>
    </div>

    <ClinicalPageState v-if="leaseQuery.isPending.value || agentsQuery.isPending.value" kind="loading" message="正在读取医助agent" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reload" />

    <template v-else>
      <section class="admin-metrics" aria-label="医助agent统计">
        <article><span>医助agent</span><strong>{{ families.length }}</strong><small>诊疗责任团队</small></article>
        <article><span>专科医助</span><strong>{{ childAgentCount }}</strong><small>覆盖各诊疗环节</small></article>
        <article><span>已启用医助</span><strong>{{ activeCount }}</strong><small>当前可用</small></article>
        <article><span>临床使用方式</span><strong>医生确认</strong><small>结果写入前审核</small></article>
      </section>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>

      <section class="admin-panel medical-agent-catalog">
        <header>
          <div><h2>医助agent与诊疗环节</h2><p>选择医助agent，查看专科医助分工、当前任务、结果贡献和处理上限。</p></div>
          <span class="admin-status active">医助协同引擎 1.0</span>
        </header>
        <div v-if="medicalCatalogLeaseQuery.isPending.value || medicalCatalogQuery.isPending.value" class="admin-empty">正在读取医助agent发布目录…</div>
        <div v-else-if="medicalCatalogLeaseQuery.error.value || medicalCatalogQuery.error.value" class="admin-empty">医助agent目录暂时不可用，可继续使用下方版本台账。</div>
        <template v-else-if="selectedFamily">
          <div class="agent-family-tabs" role="tablist" aria-label="医助agent">
            <button v-for="family in families" :key="family.main_agent.agent_code" type="button" role="tab"
              :aria-selected="selectedFamilyCode === family.main_agent.agent_code"
              :class="{ active: selectedFamilyCode === family.main_agent.agent_code }"
              @click="selectedFamilyCode = family.main_agent.agent_code">
              <b>{{ doctorFacingTeamName(family.main_agent.display_name) }}</b><span>{{ family.child_agents.length }} 位医助</span>
            </button>
          </div>
          <article class="agent-family-summary">
            <div><span class="status blue">医生确认</span><b>{{ doctorFacingAiText(selectedFamily.main_agent.display_role) }}</b><code>版本 {{ selectedFamily.main_agent.release_version }}</code></div>
            <p>{{ doctorFacingAiText(selectedFamily.main_agent.description) }}</p>
            <dl><div><dt>当前任务</dt><dd>{{ doctorFacingAiText(selectedFamily.main_agent.current_action) }}</dd></div><div><dt>团队交付</dt><dd>{{ doctorFacingAiText(selectedFamily.main_agent.contribution_label) }}</dd></div><div><dt>处理上限</dt><dd>最多 {{ selectedFamily.main_agent.max_steps }} 个步骤 · {{ selectedFamily.main_agent.max_tool_calls }} 次系统调用 · {{ selectedFamily.main_agent.max_duration_seconds }} 秒</dd></div></dl>
            <div class="agent-catalog-examples"><b>医生可以这样问</b><span v-for="example in selectedFamily.main_agent.question_examples" :key="example">{{ doctorFacingAiText(example) }}</span></div>
          </article>
          <div class="agent-child-grid">
            <article v-for="child in selectedFamily.child_agents" :key="child.agent_code">
              <div><span>专科医助</span><em>医生确认</em></div>
              <h3>{{ doctorFacingAiText(child.display_name) }}</h3>
              <p>{{ doctorFacingAiText(child.description) }}</p>
              <dl><dt>医助职责</dt><dd>{{ doctorFacingAiText(child.display_role) }}</dd><dt>当前任务</dt><dd>{{ doctorFacingAiText(child.current_action) }}</dd><dt>提交结果</dt><dd>{{ doctorFacingAiText(child.contribution_label) }}</dd></dl>
              <div class="agent-catalog-examples"><b>医生可以这样问</b><span v-for="example in child.question_examples" :key="example">{{ doctorFacingAiText(example) }}</span></div>
              <footer>最多 {{ child.max_steps }} 个步骤 · {{ child.max_tool_calls }} 次系统调用 · {{ child.max_duration_seconds }} 秒</footer>
            </article>
          </div>
        </template>
      </section>

      <div>
        <section class="admin-panel">
          <header>
            <div><h2>智能医助版本台账</h2><p>编码与版本不可变；停用后保留历史记录。</p></div>
            <div class="admin-row-actions"><button class="button secondary" @click="agentsQuery.refetch()">刷新</button><button class="button primary" @click="openCreate">新建医助agent</button></div>
          </header>
          <div v-if="agents.length === 0" class="admin-empty" role="status">暂无智能医助，请点击“新建医助agent”。</div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>名称</th><th>编码</th><th>版本</th><th>状态</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="agent in agents" :key="agent.agent_registry_id">
                  <td><strong>{{ doctorFacingAiText(agent.agent_name) }}</strong><small>…{{ agent.agent_registry_id.slice(-8) }}</small></td>
                  <td><code>{{ agent.agent_code }}</code></td>
                  <td><code>{{ agent.agent_version }}</code></td>
                  <td><span class="admin-status" :class="agent.status.toLowerCase()">{{ agent.status === 'ACTIVE' ? '有效' : '已停用' }}</span></td>
                  <td><div class="admin-row-actions"><button class="task-action" :disabled="agent.status !== 'ACTIVE' || Boolean(busy)" @click="editAgent(agent)">编辑</button><button class="task-action danger" :disabled="agent.status !== 'ACTIVE' || Boolean(busy)" @click="deactivateTarget = agent">删除</button></div></td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </div>

      <AdminActionDialog v-model:open="editorOpen" :title="editingAgent ? '编辑并发布医助新版本' : '新建医助agent'" :description="editingAgent ? '编码保持不变；保存后旧版本自动停用，并继承原能力与工具依赖。' : '登记后立即进入医助agent版本台账。'" size="large" :busy="Boolean(busy)" @update:open="!$event && resetForm()">
          <form class="admin-form ai-center-dialog-form" @submit.prevent="register">
            <label><span>医助编码</span><input v-model="form.agentCode" maxlength="128" required :disabled="Boolean(editingAgent)" placeholder="例：TRIAGE-ASSISTANT" /></label>
            <label><span>医助名称</span><input v-model="form.agentName" maxlength="256" required placeholder="例：门诊分诊医助" /></label>
            <label><span>版本</span><input v-model="form.agentVersion" maxlength="64" required placeholder="例：1.0.0" /></label>
            <div class="admin-form-actions"><button class="button secondary" type="button" :disabled="Boolean(busy)" @click="editorOpen = false">取消</button><button class="button primary" :disabled="Boolean(busy)">{{ busy === 'create' ? '正在保存…' : editingAgent ? '发布新版本' : '登记并生效' }}</button></div>
          </form>
      </AdminActionDialog>
      <AdminConfirmDialog :open="Boolean(deactivateTarget)" :title="`删除医助agent ${doctorFacingAiText(deactivateTarget?.agent_name ?? '')}`" description="删除将以安全停用方式执行；该团队不再接收新任务，历史版本、任务结果和审计记录继续保留。" confirm-label="确认删除并停用" :busy="Boolean(busy)" @update:open="!$event && (deactivateTarget = null)" @confirm="deactivateTarget && deactivate(deactivateTarget)"><div v-if="deactivateTarget" class="admin-impact-grid"><div><span>团队编码</span><b>{{ deactivateTarget.agent_code }}</b></div><div><span>当前版本</span><b>{{ deactivateTarget.agent_version }}</b></div><div><span>当前状态</span><b>有效</b></div><div><span>流程影响</span><b>停止接收新任务</b></div></div></AdminConfirmDialog>
    </template>
  </section>
</template>

<style scoped>
.medical-agent-catalog { margin-bottom: 18px; }
.agent-family-tabs { display: grid; grid-template-columns: repeat(5, minmax(150px, 1fr)); gap: 8px; padding: 16px; border-bottom: 1px solid var(--line, #dbe3ea); }
.agent-family-tabs button { display: grid; gap: 4px; padding: 12px; text-align: left; border: 1px solid #d9e3ea; border-radius: 10px; background: #f8fafb; color: #31465a; }
.agent-family-tabs button.active { border-color: #15988d; background: #ebf8f6; box-shadow: inset 0 0 0 1px #15988d; }
.agent-family-tabs span { color: var(--muted, #66798b); font-size: 12px; }
.agent-family-summary { margin: 16px; padding: 16px; border-radius: 12px; background: linear-gradient(135deg, #f0faf8, #f5f8fc); border: 1px solid #d4e9e5; }
.agent-family-summary > div { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.agent-family-summary > p { margin: 10px 0; }
.agent-family-summary dl { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; margin: 0; }
.agent-family-summary dl div { padding: 10px; border-radius: 8px; background: #fff; }
.agent-family-summary dt, .agent-child-grid dt { color: var(--muted, #66798b); font-size: 12px; }
.agent-family-summary dd, .agent-child-grid dd { margin: 4px 0 0; }
.agent-catalog-examples { display: grid; gap: 6px; margin-top: 12px; padding: 10px; border-radius: 8px; background: #f1f9f7; }
.agent-catalog-examples b { color: #087c75; font-size: 12px; }
.agent-catalog-examples span { color: #40586e; font-size: 12px; line-height: 1.5; }
.agent-child-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 12px; padding: 0 16px 16px; }
.agent-child-grid > article { padding: 14px; border: 1px solid #dce5eb; border-radius: 12px; background: #fff; box-shadow: 0 4px 12px rgb(16 55 78 / 5%); }
.agent-child-grid > article > div { display: flex; justify-content: space-between; color: #087c75; font-size: 12px; font-weight: 700; }
.agent-child-grid h3 { margin: 9px 0 6px; font-size: 16px; }
.agent-child-grid p { min-height: 42px; margin: 0 0 10px; color: #526579; }
.agent-child-grid dl { display: grid; gap: 5px; margin: 0; }
.agent-child-grid footer { margin-top: 12px; padding-top: 10px; border-top: 1px dashed #dce5eb; color: #607587; font-size: 12px; }
@media (max-width: 980px) { .agent-family-tabs { grid-template-columns: repeat(2, 1fr); } .agent-family-summary dl { grid-template-columns: 1fr; } }
@media (max-width: 560px) { .agent-family-tabs { grid-template-columns: 1fr; } .agent-child-grid { grid-template-columns: 1fr; } }
</style>
