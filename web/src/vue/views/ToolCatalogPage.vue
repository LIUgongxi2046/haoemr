<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref } from 'vue';
import type { ToolRegistryWire } from '../../generated/contracts';
import { deactivateTool, issueAiLease, listTools, publishToolVersion, registerTool } from '../../api/ai-platform';
import AdminActionDialog from '../components/AdminActionDialog.vue';
import AdminConfirmDialog from '../components/AdminConfirmDialog.vue';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';

type ToolType = ToolRegistryWire['tool_type'];
const toolTypeLabels: Record<ToolType, string> = {
  API: 'API',
  FUNCTION: '函数',
  DATABASE_QUERY: '数据库查询',
  OTHER: '其他',
};

const leaseQuery = useQuery({
  queryKey: ['ai-platform', 'tool-catalog', 'lease'],
  queryFn: () => issueAiLease('AI_PLATFORM_ADMIN'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const toolsQuery = useQuery({
  queryKey: ['ai-platform', 'tool-catalog', 'tools'],
  queryFn: () => listTools(leaseQuery.data.value!),
  enabled: () => Boolean(leaseQuery.data.value),
  retry: false,
});
const issue = computed(() => (leaseQuery.error.value ?? toolsQuery.error.value)
  ? toClinicalIssue(leaseQuery.error.value ?? toolsQuery.error.value) : null);
const tools = computed(() => toolsQuery.data.value ?? []);
const activeCount = computed(() => tools.value.filter((tool) => tool.status === 'ACTIVE').length);

const form = reactive({
  toolCode: '',
  toolName: '',
  toolVersion: '',
  toolType: 'API' as ToolType,
});
const editingTool = ref<ToolRegistryWire | null>(null);
const editorOpen = ref(false);
const deactivateTarget = ref<ToolRegistryWire | null>(null);
const busy = ref('');
const notice = ref('');

async function reload() {
  notice.value = '';
  await toolsQuery.refetch();
}

function nextVersion(version: string) {
  const match = version.match(/^(?:v)?(\d+)\.(\d+)\.(\d+)$/);
  return match ? `${match[1]}.${match[2]}.${Number(match[3]) + 1}` : `${version}-next`;
}

function resetForm() {
  editingTool.value = null;
  form.toolCode = ''; form.toolName = ''; form.toolVersion = ''; form.toolType = 'API';
}

function openCreate() {
  resetForm();
  editorOpen.value = true;
}

function editTool(tool: ToolRegistryWire) {
  editingTool.value = tool;
  form.toolCode = tool.tool_code; form.toolName = tool.tool_name;
  form.toolVersion = nextVersion(tool.tool_version); form.toolType = tool.tool_type; notice.value = '';
  editorOpen.value = true;
}

async function register() {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || !form.toolCode.trim() || !form.toolName.trim() || !form.toolVersion.trim()) return;
  busy.value = 'create'; notice.value = '';
  try {
    if (editingTool.value) {
      await publishToolVersion(lease, editingTool.value, {
        tool_name: form.toolName.trim(), tool_version: form.toolVersion.trim(), tool_type: form.toolType,
      });
      notice.value = '新工具版本已发布，旧版本已自动停用；后续医助任务将使用新版本。';
    } else {
      await registerTool(lease, {
        tool_code: form.toolCode.trim(), tool_name: form.toolName.trim(),
        tool_version: form.toolVersion.trim(), tool_type: form.toolType,
      });
      notice.value = '医助tool已登记，版本记录和操作留痕已同步更新。';
    }
    resetForm();
    editorOpen.value = false;
    await toolsQuery.refetch();
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = ''; }
}

async function deactivate(tool: ToolRegistryWire) {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || tool.status !== 'ACTIVE') return;
  busy.value = tool.tool_registry_id; notice.value = '';
  try {
    await deactivateTool(lease, tool);
    notice.value = `医助tool“${tool.tool_name}”已停用。`;
    await toolsQuery.refetch();
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
        <p class="eyebrow">AI中心 / 医助tool管理</p>
        <h1>医助tool库</h1>
        <p>管理院内系统查询、临床数据读取和业务操作工具；所有变更保留版本和操作记录，高风险操作需人工审批。</p>
      </div>
    </div>

    <ClinicalPageState v-if="leaseQuery.isPending.value || toolsQuery.isPending.value" kind="loading" message="正在读取医助tool库" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reload" />

    <template v-else>
      <section class="admin-metrics" aria-label="医助tool统计">
        <article><span>工具总数</span><strong>{{ tools.length }}</strong><small>全部登记</small></article>
        <article><span>可用工具</span><strong>{{ activeCount }}</strong><small>当前已启用</small></article>
        <article><span>调用鉴权</span><strong>逐次校验</strong><small>按患者与岗位范围</small></article>
        <article><span>异常处理</span><strong>可重试</strong><small>失败结果保留记录</small></article>
      </section>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>

      <div>
        <section class="admin-panel">
          <header>
            <div><h2>医助tool版本台账</h2><p>编码与版本不可变；停用后保留历史记录。</p></div>
            <div class="admin-row-actions"><button class="button secondary" @click="toolsQuery.refetch()">刷新</button><button class="button primary" @click="openCreate">新建医助tool</button></div>
          </header>
          <div v-if="tools.length === 0" class="admin-empty" role="status">暂无医助tool，请点击“新建医助tool”。</div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>名称</th><th>编码</th><th>类型</th><th>版本</th><th>状态</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="tool in tools" :key="tool.tool_registry_id">
                  <td><strong>{{ tool.tool_name }}</strong><small>…{{ tool.tool_registry_id.slice(-8) }}</small></td>
                  <td><code>{{ tool.tool_code }}</code></td>
                  <td>{{ toolTypeLabels[tool.tool_type] }}</td>
                  <td><code>{{ tool.tool_version }}</code></td>
                  <td><span class="admin-status" :class="tool.status.toLowerCase()">{{ tool.status === 'ACTIVE' ? '有效' : '已停用' }}</span></td>
                  <td><div class="admin-row-actions"><button class="task-action" :disabled="tool.status !== 'ACTIVE' || Boolean(busy)" @click="editTool(tool)">编辑</button><button class="task-action danger" :disabled="tool.status !== 'ACTIVE' || Boolean(busy)" @click="deactivateTarget = tool">删除</button></div></td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

      </div>

      <AdminActionDialog v-model:open="editorOpen" :title="editingTool ? '编辑并发布工具新版本' : '新建医助tool'" :description="editingTool ? '编码保持不变；新版本可调整名称、版本和工具类型。' : '登记后可供授权医助agent调用。'" size="large" :busy="Boolean(busy)" @update:open="!$event && resetForm()">
          <form class="admin-form ai-center-dialog-form" @submit.prevent="register">
            <label><span>工具编码</span><input v-model="form.toolCode" maxlength="128" required :disabled="Boolean(editingTool)" placeholder="例：DRUG-INTERACTION" /></label>
            <label><span>工具名称</span><input v-model="form.toolName" maxlength="256" required placeholder="例：药物相互作用查询" /></label>
            <label><span>版本</span><input v-model="form.toolVersion" maxlength="64" required placeholder="例：1.0.0" /></label>
            <label><span>类型</span><select v-model="form.toolType"><option v-for="(name, type) in toolTypeLabels" :key="type" :value="type">{{ name }}</option></select></label>
            <div class="admin-form-actions"><button class="button secondary" type="button" :disabled="Boolean(busy)" @click="editorOpen = false">取消</button><button class="button primary" :disabled="Boolean(busy)">{{ busy === 'create' ? '正在保存…' : editingTool ? '发布新版本' : '登记并生效' }}</button></div>
          </form>
      </AdminActionDialog>
      <AdminConfirmDialog :open="Boolean(deactivateTarget)" :title="`删除医助tool ${deactivateTarget?.tool_name ?? ''}`" description="删除将以安全停用方式执行；新任务不再调用该工具，历史任务和审计记录继续保留。" confirm-label="确认删除并停用" :busy="Boolean(busy)" @update:open="!$event && (deactivateTarget = null)" @confirm="deactivateTarget && deactivate(deactivateTarget)"><div v-if="deactivateTarget" class="admin-impact-grid"><div><span>工具编码</span><b>{{ deactivateTarget.tool_code }}</b></div><div><span>当前版本</span><b>{{ deactivateTarget.tool_version }}</b></div><div><span>工具类型</span><b>{{ toolTypeLabels[deactivateTarget.tool_type] }}</b></div><div><span>流程影响</span><b>停止新任务调用</b></div></div></AdminConfirmDialog>
    </template>
  </section>
</template>
