<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref } from 'vue';
import type { ModelDeploymentWire } from '../../generated/contracts';
import type { MedicalAgentContextScope, ModelDataProcessingApproval } from '../../api/ai-platform';
import { approveModelDataProcessing, deactivateModelDeployment, issueAiLease, listModelDataProcessingApprovals, listModelDeployments, publishModelDeployment, purgeModelDeployment, registerModelDeployment, revokeModelDataProcessingApproval, testModelDeploymentConnection, updateModelDeployment } from '../../api/ai-platform';
import AdminActionDialog from '../components/AdminActionDialog.vue';
import AdminConfirmDialog from '../components/AdminConfirmDialog.vue';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';

type ResidencyPolicy = ModelDeploymentWire['residency_policy'];
const residencyPolicyLabels: Record<ResidencyPolicy, string> = {
  ON_PREM_ONLY: '仅本地部署',
  LOCAL_PREFERRED: '本地优先',
  CLOUD_ALLOWED: '允许云端',
};
const evaluationStatusLabels: Record<ModelDeploymentWire['evaluation_status'], string> = {
  EVALUATING: '评估中',
  APPROVED: '已通过',
  REJECTED: '已拒绝',
};
const connectionStatusLabels: Record<ModelDeploymentWire['connection_status'], string> = {
  NOT_CONFIGURED: '未配置', UNVERIFIED: '待验证', READY: '已连通', FAILED: '连接失败',
};
const providerOptions = [
  { code: 'DEEPSEEK', label: 'DeepSeek', endpoint: 'https://api.deepseek.com', model: 'deepseek-chat' },
  { code: 'QWEN', label: '阿里云百炼（通义千问）', endpoint: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-plus' },
  { code: 'GLM', label: '智谱开放平台', endpoint: 'https://open.bigmodel.cn/api/paas/v4', model: 'glm-4-plus' },
  { code: 'DOUBAO', label: '火山方舟（豆包）', endpoint: 'https://ark.cn-beijing.volces.com/api/v3', model: '' },
  { code: 'OPENAI_COMPATIBLE', label: '其他兼容接口', endpoint: '', model: '' },
];

const leaseQuery = useQuery({
  queryKey: ['ai-platform', 'models', 'lease'],
  queryFn: () => issueAiLease('AI_PLATFORM_ADMIN'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const modelsQuery = useQuery({
  queryKey: ['ai-platform', 'models', 'deployments'],
  queryFn: () => listModelDeployments(leaseQuery.data.value!),
  enabled: () => Boolean(leaseQuery.data.value),
  retry: false,
});
const issue = computed(() => (leaseQuery.error.value ?? modelsQuery.error.value)
  ? toClinicalIssue(leaseQuery.error.value ?? modelsQuery.error.value) : null);
const models = computed(() => modelsQuery.data.value ?? []);
const activeCount = computed(() => models.value.filter((model) => model.status === 'ACTIVE').length);
const inactiveCount = computed(() => models.value.filter((model) => model.status === 'INACTIVE').length);
const connectedCount = computed(() => models.value.filter((model) => !isRetiredSynthetic(model)
  && model.connection_status === 'READY').length);
const statusFilter = ref<'ACTIVE' | 'INACTIVE' | 'ALL'>('ACTIVE');
const filteredModels = computed(() => statusFilter.value === 'ALL'
  ? models.value
  : models.value.filter((model) => model.status === statusFilter.value));

const form = reactive({
  modelCode: '',
  providerCode: '',
  displayName: '',
  residencyPolicy: 'LOCAL_PREFERRED' as ResidencyPolicy,
  endpointUrl: '',
  apiKey: '',
  credentialAction: 'KEEP' as 'KEEP' | 'REPLACE' | 'CLEAR',
});
const editingModel = ref<ModelDeploymentWire | null>(null);
const editorOpen = ref(false);
const deactivateTarget = ref<ModelDeploymentWire | null>(null);
const purgeTarget = ref<ModelDeploymentWire | null>(null);
const busy = ref('');
const notice = ref('');
const showApiKey = ref(false);
const approvalTarget = ref<ModelDeploymentWire | null>(null);
const approvalHistory = ref<ModelDataProcessingApproval[]>([]);
const approvalOpen = ref(false);
const revokeApprovalTarget = ref<ModelDataProcessingApproval | null>(null);
const revokeReason = ref('');
const contextScopeOptions: { code: MedicalAgentContextScope; label: string }[] = [
  { code: 'RECORDS', label: '病历文书' }, { code: 'ORDERS', label: '医嘱' },
  { code: 'RESULTS', label: '检查检验结果' }, { code: 'TASKS', label: '诊疗任务' },
  { code: 'ATTACHMENTS', label: '附件' }, { code: 'CONFIGURATION', label: '受控配置' },
];
const approvalForm = reactive({
  legalBasis: '医疗服务合同履行与院内诊疗辅助',
  piaReference: '', processorAgreementReference: '', endpointRegion: '中国境内',
  retentionDays: 0, expiresAt: '',
  allowedContextScopes: ['RECORDS', 'ORDERS', 'RESULTS', 'TASKS'] as MedicalAgentContextScope[],
});
const activeApproval = computed(() => approvalHistory.value.find((item) => item.status === 'ACTIVE'
  && new Date(item.expires_at).getTime() > Date.now()) ?? null);

function formatDate(value: string | null | undefined) {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium' }).format(new Date(value)) : '未配置';
}

function applyProviderPreset() {
  const preset = providerOptions.find((item) => item.code === form.providerCode);
  if (!preset) return;
  form.endpointUrl = preset.endpoint;
  if (!form.modelCode) form.modelCode = preset.model;
  if (!form.displayName) {
    if (preset.code !== 'OPENAI_COMPATIBLE') form.displayName = `${preset.label} 医疗模型`;
  }
}

function isRetiredSynthetic(model: ModelDeploymentWire) {
  return model.provider_code === 'SYNTHETIC' || model.endpoint_url?.includes('.example/') === true;
}

function providerLabel(model: ModelDeploymentWire) {
  return isRetiredSynthetic(model) ? `${model.provider_code}（开发验收配置，已退役）` : model.provider_code;
}

async function reload() {
  notice.value = '';
  await modelsQuery.refetch();
}

function resetForm() {
  editingModel.value = null;
  form.modelCode = ''; form.providerCode = ''; form.displayName = '';
  form.residencyPolicy = 'LOCAL_PREFERRED'; form.endpointUrl = ''; form.apiKey = '';
  form.credentialAction = 'KEEP';
  showApiKey.value = false;
}

function openCreate() {
  resetForm();
  editorOpen.value = true;
}

function edit(model: ModelDeploymentWire) {
  editingModel.value = model;
  form.modelCode = model.model_code; form.providerCode = model.provider_code;
  form.displayName = model.display_name; form.residencyPolicy = model.residency_policy;
  form.endpointUrl = model.endpoint_url ?? ''; form.apiKey = '';
  form.credentialAction = 'KEEP'; notice.value = ''; editorOpen.value = true;
}

async function saveModel() {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || !form.providerCode.trim() || !form.displayName.trim()
    || !form.modelCode.trim()) return;
  busy.value = editingModel.value ? 'update' : 'create'; notice.value = '';
  try {
    if (editingModel.value) {
      await updateModelDeployment(lease, editingModel.value, {
        display_name: form.displayName.trim(), residency_policy: form.residencyPolicy,
        endpoint_url: form.endpointUrl.trim() || null,
        api_key_ref: null,
        api_key: form.credentialAction === 'REPLACE' ? form.apiKey.trim() || null : null,
        credential_action: form.credentialAction,
      });
      notice.value = '模型配置已更新，请执行“测试连接”；验证成功后才会进入 AI医助 EVA 模型路由。';
    } else {
      await registerModelDeployment(lease, {
        model_code: form.modelCode.trim(),
        provider_code: form.providerCode.trim(),
        display_name: form.displayName.trim(), residency_policy: form.residencyPolicy,
        endpoint_url: form.endpointUrl.trim() || null, api_key_ref: null, api_key: form.apiKey.trim() || null,
      });
      notice.value = '模型 API 配置已安全保存。页面不会再显示完整密钥；请继续执行“测试连接”。只有真实提供方的连通、独立评测和发布均通过后，才会进入 AI医助 EVA 路由。';
    }
    resetForm();
    editorOpen.value = false;
    await modelsQuery.refetch();
    deactivateTarget.value = null;
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = ''; }
}

async function deactivate(model: ModelDeploymentWire) {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || model.status !== 'ACTIVE') return;
  busy.value = model.model_deployment_id; notice.value = '';
  try {
    await deactivateModelDeployment(lease, model);
    notice.value = `模型部署 ${model.display_name} 已停用。`;
    await modelsQuery.refetch();
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally {
    busy.value = '';
    deactivateTarget.value = null;
  }
}

async function purge(model: ModelDeploymentWire) {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || model.status !== 'INACTIVE') return;
  busy.value = `purge:${model.model_deployment_id}`; notice.value = '';
  try {
    await purgeModelDeployment(lease, model);
    notice.value = `模型配置 ${model.display_name} 已彻底移除。`;
    await modelsQuery.refetch();
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally {
    busy.value = '';
    purgeTarget.value = null;
  }
}

async function testConnection(model: ModelDeploymentWire) {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || model.status !== 'ACTIVE' || !model.credential_configured) return;
  busy.value = `test:${model.model_deployment_id}`; notice.value = '';
  try {
    const tested = await testModelDeploymentConnection(lease, model);
    notice.value = tested.connection_status === 'READY'
      ? `连接验证成功：${tested.display_name}，延迟 ${tested.last_connection_latency_ms ?? 0} ms。`
      : `连接验证失败：${tested.last_connection_error_code ?? '未知错误'}。`;
    await modelsQuery.refetch();
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = ''; }
}

async function publish(model: ModelDeploymentWire) {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || model.status !== 'ACTIVE') return;
  busy.value = `publish:${model.model_deployment_id}`; notice.value = '';
  try {
    const published = await publishModelDeployment(lease, model);
    notice.value = published.evaluation_status === 'APPROVED'
      ? `模型 ${published.display_name} 已发布，现已进入 AI医助 EVA 模型路由，可在 AI医助 EVA 中选择使用。`
      : '发布结果异常，请刷新后确认。';
    await modelsQuery.refetch();
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = ''; }
}

async function openProcessingApproval(model: ModelDeploymentWire) {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value) return;
  approvalTarget.value = model; approvalOpen.value = true; notice.value = '';
  approvalForm.expiresAt = new Date(Date.now() + 365 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
  busy.value = `approval:list:${model.model_deployment_id}`;
  try { approvalHistory.value = await listModelDataProcessingApprovals(lease, model.model_deployment_id); }
  catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}

async function saveProcessingApproval() {
  const lease = leaseQuery.data.value; const model = approvalTarget.value;
  if (!lease || !model || activeApproval.value || !approvalForm.expiresAt || busy.value) return;
  busy.value = 'approval:create'; notice.value = '';
  try {
    await approveModelDataProcessing(lease, model.model_deployment_id, {
      legal_basis: approvalForm.legalBasis.trim(), pia_reference: approvalForm.piaReference.trim(),
      processor_agreement_reference: approvalForm.processorAgreementReference.trim(),
      endpoint_region: approvalForm.endpointRegion.trim(), retention_days: Number(approvalForm.retentionDays),
      allowed_context_scopes: approvalForm.allowedContextScopes,
      expires_at: new Date(`${approvalForm.expiresAt}T23:59:59+08:00`).toISOString(),
    });
    approvalHistory.value = await listModelDataProcessingApprovals(lease, model.model_deployment_id);
    notice.value = `${model.display_name} 的云端诊疗数据处理授权已生效，Agent 只能读取授权范围。`;
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}

async function revokeProcessingApproval() {
  const lease = leaseQuery.data.value; const approval = revokeApprovalTarget.value;
  if (!lease || !approval || revokeReason.value.trim().length < 2 || busy.value) return;
  busy.value = 'approval:revoke'; notice.value = '';
  try {
    await revokeModelDataProcessingApproval(lease, approval, revokeReason.value.trim());
    approvalHistory.value = await listModelDataProcessingApprovals(lease, approval.model_deployment_id);
    revokeApprovalTarget.value = null; revokeReason.value = '';
    notice.value = '授权已撤销，后续云端模型 Agent 任务将失败关闭。';
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}
</script>

<template>
  <section data-page-root class="content admin-content vue-native-page">
    <div class="page-heading admin-heading">
      <div>
        <p class="eyebrow">AI中心 / 模型 API 配置</p>
        <h1>模型服务与 API 配置</h1>
        <p>在这里配置模型提供方、API 地址和 API Key，供 AI医助 EVA 及医助团队调用。</p>
      </div>
    </div>

    <ClinicalPageState v-if="leaseQuery.isPending.value || modelsQuery.isPending.value" kind="loading" message="正在读取模型部署目录" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reload" />

    <template v-else>
      <section class="admin-metrics" aria-label="模型部署统计">
        <article><span>模型部署</span><strong>{{ models.length }}</strong><small>全部登记</small></article>
        <article><span>有效部署</span><strong>{{ activeCount }}</strong></article>
        <article><span>API 已就绪</span><strong>{{ connectedCount }}</strong><small>已通过提供方连通验证</small></article>
        <article><span>未就绪连接</span><strong>{{ Math.max(activeCount - connectedCount, 0) }}</strong><small>需补充连接配置</small></article>
      </section>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>

      <section class="admin-panel model-api-guide">
        <header><div><h2>模型 API 在哪里配置？</h2><p>当前位置：AI中心 → 模型服务 → 登记模型 API。</p></div><span class="admin-status active">已支持</span></header>
        <div class="model-api-guide-grid">
          <article><b>① 选择模型提供方</b><p>仅可新建真实模型提供方配置。历史开发验收模型已退役，不能用于生产路由或临床操作；DeepSeek、通义千问、智谱、豆包等厂商需真实 API Key 才能调用。</p></article>
          <article><b>② 填写模型名称和 API 地址</b><p>API 地址必须使用 HTTPS；模型名称必须是提供方真实模型名（如 deepseek-chat），会随每次 Agent 调用发送给厂商。</p></article>
          <article><b>③ 输入 API Key</b><p>管理员可直接输入密钥，也可留空稍后补充。密钥由后端受保护存储，数据库不保存明文，后续只显示末四位。系统不会将保留示例地址作为可用模型；其他提供方的“测试连接”会向所填地址发起实际最小请求，必须使用真实可用的密钥。</p></article>
        </div>
      </section>

      <div>
        <section class="admin-panel">
          <header>
            <div><h2>模型部署台账</h2><p>「删除」= 安全停用（保留历史）；已停用记录可「彻底移除」。</p></div>
            <div class="admin-row-actions admin-model-toolbar">
              <div class="admin-filter" role="tablist" aria-label="模型状态筛选">
                <button type="button" :class="{ active: statusFilter === 'ACTIVE' }" @click="statusFilter = 'ACTIVE'">有效 {{ activeCount }}</button>
                <button type="button" :class="{ active: statusFilter === 'INACTIVE' }" @click="statusFilter = 'INACTIVE'">已停用 {{ inactiveCount }}</button>
                <button type="button" :class="{ active: statusFilter === 'ALL' }" @click="statusFilter = 'ALL'">全部 {{ models.length }}</button>
              </div>
              <button class="button secondary" @click="modelsQuery.refetch()">刷新</button><button class="button primary" @click="openCreate">新建模型 API 配置</button>
            </div>
          </header>
          <div v-if="filteredModels.length === 0" class="admin-empty" role="status">{{ statusFilter === 'INACTIVE' ? '暂无已停用的模型配置。' : '暂无模型配置，请点击“新建模型 API 配置”。' }}</div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>模型 / 名称</th><th>提供方</th><th>API 连接</th><th>驻留策略</th><th>评估状态</th><th>状态</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="model in filteredModels" :key="model.model_deployment_id">
                  <td><strong>{{ model.display_name }}</strong><small><code>{{ model.model_code }}</code> · …{{ model.model_deployment_id.slice(-8) }} · v{{ model.row_version }}</small></td>
                  <td><code>{{ providerLabel(model) }}</code></td>
                  <td><span class="admin-status" :class="model.connection_status === 'READY' ? 'active' : model.connection_status === 'FAILED' ? 'rejected' : 'evaluating'">{{ connectionStatusLabels[model.connection_status] }}</span><small v-if="isRetiredSynthetic(model)" class="model-connection-error">开发验收配置已退役，不能用于生产路由</small><small v-else-if="model.credential_hint">{{ model.credential_hint }}</small><small v-if="model.last_connection_tested_at">{{ formatDate(model.last_connection_tested_at) }} · {{ model.last_connection_latency_ms ?? 0 }} ms</small><small v-if="model.last_connection_error_code && !isRetiredSynthetic(model)" class="model-connection-error">{{ model.last_connection_error_code }}</small></td>
                  <td>{{ residencyPolicyLabels[model.residency_policy] }}</td>
                  <td><span class="admin-status" :class="model.test_only ? 'warning' : model.release_evidence_ready ? model.evaluation_status.toLowerCase() : 'rejected'">{{ model.test_only ? '仅开发测试' : model.release_evidence_ready ? evaluationStatusLabels[model.evaluation_status] : '缺少通过评测证据' }}</span><small v-if="model.test_only" class="model-connection-error">仅允许在合成开发环境做最小连接验证；不可发布或进入临床任务路由</small><small v-else-if="!model.release_evidence_ready" class="model-connection-error">运行时已阻断；请由独立人员留存真实通过评测，禁止补写虚构分数</small></td>
                  <td><span class="admin-status" :class="model.status.toLowerCase()">{{ model.status === 'ACTIVE' ? '有效' : '已停用' }}</span></td>
                  <td><div class="admin-row-actions"><template v-if="model.status === 'ACTIVE'"><button class="task-action" :disabled="isRetiredSynthetic(model) || !model.credential_configured || Boolean(busy)" @click="testConnection(model)">{{ busy === `test:${model.model_deployment_id}` ? '验证中…' : '测试连接' }}</button><button v-if="!model.test_only && !isRetiredSynthetic(model) && model.connection_status === 'READY' && model.evaluation_status !== 'APPROVED'" class="task-action publish" :disabled="Boolean(busy)" :title="'发布后进入 AI医助 EVA 模型路由'" @click="publish(model)">{{ busy === `publish:${model.model_deployment_id}` ? '发布中…' : '发布' }}</button><button v-if="!isRetiredSynthetic(model) && model.residency_policy === 'CLOUD_ALLOWED'" class="task-action" :disabled="Boolean(busy)" @click="openProcessingApproval(model)">云端处理授权</button><button v-if="!isRetiredSynthetic(model)" class="task-action" :disabled="Boolean(busy)" @click="edit(model)">编辑</button><button class="task-action danger" :disabled="Boolean(busy)" @click="deactivateTarget = model">删除</button></template><button v-else class="task-action danger" :disabled="Boolean(busy)" @click="purgeTarget = model">彻底移除</button></div></td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </div>

      <AdminActionDialog v-model:open="editorOpen" :title="editingModel ? '编辑模型 API' : '新建模型 API 配置'" description="保存后，AI医助 EVA 及医助团队的后续任务会读取最新有效连接配置。" size="large" :busy="Boolean(busy)" @update:open="!$event && resetForm()">
          <form class="admin-form ai-center-dialog-form" @submit.prevent="saveModel">
            <label><span>模型提供方</span><select v-model="form.providerCode" required :disabled="Boolean(editingModel)" @change="applyProviderPreset"><option value="" disabled>请选择提供方</option><option v-for="provider in providerOptions" :key="provider.code" :value="provider.code">{{ provider.label }}</option></select></label>
            <label><span>模型名称（发送给模型厂商）</span><input v-model="form.modelCode" maxlength="128" required :disabled="Boolean(editingModel)" placeholder="例：deepseek-chat / qwen-plus / glm-4-plus" /><small>必须是提供方控制台认可的真实模型名，会随每次 Agent 调用发送给厂商。</small></label>
            <label><span>显示名称</span><input v-model="form.displayName" maxlength="256" required placeholder="例：DeepSeek 医疗主模型" /></label>
            <label><span>驻留策略</span><select v-model="form.residencyPolicy"><option v-for="(name, policy) in residencyPolicyLabels" :key="policy" :value="policy">{{ name }}</option></select></label>
            <label><span>API 地址</span><input v-model="form.endpointUrl" maxlength="512" required placeholder="例：https://api.deepseek.com" /></label>
            <label v-if="editingModel"><span>密钥处理</span><select v-model="form.credentialAction"><option value="KEEP">保留当前 API Key</option><option value="REPLACE">更换 API Key</option><option value="CLEAR">清除 API Key</option></select><small v-if="editingModel.credential_hint">当前：{{ editingModel.credential_hint }}</small></label>
            <label v-if="!editingModel || form.credentialAction === 'REPLACE'"><span>API Key（可留空，稍后再填）</span><div class="api-key-input-row"><input v-model="form.apiKey" :type="showApiKey ? 'text' : 'password'" maxlength="4096" minlength="8" autocomplete="new-password" placeholder="粘贴 API Key，或开发环境填占位密钥如 sk-demo-12345678（8 位以上）" /><button class="button secondary" type="button" @click="showApiKey = !showApiKey">{{ showApiKey ? '隐藏' : '显示' }}</button></div><small>密钥仅在保存时传输，保存后只显示末四位；未填写时模型暂无法连接，可稍后通过“编辑 → 更换 API Key”补充。</small></label>
            <div class="admin-form-actions"><button class="button secondary" type="button" :disabled="Boolean(busy)" @click="editorOpen = false">取消</button><button class="button primary" :disabled="Boolean(busy)">{{ busy ? '正在保存…' : editingModel ? '保存变更' : '保存 API 配置' }}</button></div>
          </form>
      </AdminActionDialog>
      <AdminActionDialog v-model:open="approvalOpen" :title="`云端诊疗数据处理授权 · ${approvalTarget?.display_name ?? ''}`" description="授权与 API 连通、模型评测相互独立；三者均有效时 AI医助 EVA 才能向云端模型发送授权范围内的诊疗上下文。" size="large" :busy="Boolean(busy)">
        <div v-if="busy.startsWith('approval:list:')" class="admin-empty" role="status">正在读取授权台账…</div>
        <div v-else class="processing-approval-layout">
          <section v-if="activeApproval" class="processing-approval-current">
            <div><span>当前状态</span><b>已授权</b></div><div><span>终端地域</span><b>{{ activeApproval.endpoint_region }}</b></div>
            <div><span>有效期至</span><b>{{ formatDate(activeApproval.expires_at) }}</b></div><div><span>允许范围</span><b>{{ activeApproval.allowed_context_scopes.map(code => contextScopeOptions.find(item => item.code === code)?.label ?? code).join('、') }}</b></div>
            <div><span>影响评估</span><b>{{ activeApproval.pia_reference }}</b></div><div><span>处理协议</span><b>{{ activeApproval.processor_agreement_reference }}</b></div>
            <button class="button danger" type="button" :disabled="Boolean(busy)" @click="revokeApprovalTarget = activeApproval">撤销当前授权</button>
          </section>
          <form v-else class="admin-form ai-center-dialog-form" @submit.prevent="saveProcessingApproval">
            <label><span>处理依据</span><input v-model="approvalForm.legalBasis" minlength="4" maxlength="512" required /></label>
            <label><span>个人信息保护影响评估编号</span><input v-model="approvalForm.piaReference" minlength="4" maxlength="256" required placeholder="例：PIA-AI-2026-001" /></label>
            <label><span>委托处理 / 数据处理协议编号</span><input v-model="approvalForm.processorAgreementReference" minlength="4" maxlength="256" required placeholder="例：DPA-DEEPSEEK-2026-001" /></label>
            <div class="approval-form-grid"><label><span>模型终端地域</span><input v-model="approvalForm.endpointRegion" minlength="2" maxlength="128" required /></label><label><span>供应商保留天数</span><input v-model.number="approvalForm.retentionDays" type="number" min="0" max="3650" required /></label><label><span>授权到期日</span><input v-model="approvalForm.expiresAt" type="date" required /></label></div>
            <fieldset class="approval-scopes"><legend>允许的诊疗上下文</legend><label v-for="scope in contextScopeOptions" :key="scope.code"><input v-model="approvalForm.allowedContextScopes" type="checkbox" :value="scope.code" />{{ scope.label }}</label></fieldset>
            <p class="dialog-warning">授权不会赋予模型签署病历、开立医嘱或改写业务终态的权限。</p>
            <div class="admin-form-actions"><button class="button secondary" type="button" :disabled="Boolean(busy)" @click="approvalOpen = false">取消</button><button class="button primary" :disabled="Boolean(busy) || approvalForm.allowedContextScopes.length === 0">{{ busy === 'approval:create' ? '正在授权…' : '确认授权' }}</button></div>
          </form>
          <section v-if="approvalHistory.length" class="approval-history"><h3>授权历史</h3><article v-for="approval in approvalHistory" :key="approval.approval_id"><span class="admin-status" :class="approval.status === 'ACTIVE' ? 'active' : 'rejected'">{{ approval.status === 'ACTIVE' ? '有效' : '已撤销' }}</span><b>{{ approval.pia_reference }}</b><small>{{ formatDate(approval.approved_at) }} · v{{ approval.row_version }}<template v-if="approval.revocation_reason"> · {{ approval.revocation_reason }}</template></small></article></section>
        </div>
      </AdminActionDialog>
      <AdminConfirmDialog :open="Boolean(deactivateTarget)" :title="`删除模型配置 ${deactivateTarget?.display_name ?? ''}`" description="删除将以安全停用方式执行；后续医助任务不再选择该模型，历史评测、运行记录和审计证据继续保留。" confirm-label="确认删除并停用" :busy="Boolean(busy)" @update:open="!$event && (deactivateTarget = null)" @confirm="deactivateTarget && deactivate(deactivateTarget)"><div v-if="deactivateTarget" class="admin-impact-grid"><div><span>模型标识</span><b>{{ deactivateTarget.model_code }}</b></div><div><span>提供方</span><b>{{ deactivateTarget.provider_code }}</b></div><div><span>当前版本</span><b>v{{ deactivateTarget.row_version }}</b></div><div><span>流程影响</span><b>退出后续模型路由</b></div></div></AdminConfirmDialog>
      <AdminConfirmDialog :open="Boolean(purgeTarget)" :title="`彻底移除模型配置 ${purgeTarget?.display_name ?? ''}`" description="彻底移除将从台账中物理删除该配置及其评测、授权记录；已有的医助运行记录会保留但不再关联该模型。此操作不可撤销。" confirm-label="确认彻底移除" :busy="Boolean(busy)" @update:open="!$event && (purgeTarget = null)" @confirm="purgeTarget && purge(purgeTarget)"><div v-if="purgeTarget" class="admin-impact-grid"><div><span>模型标识</span><b>{{ purgeTarget.model_code }}</b></div><div><span>提供方</span><b>{{ purgeTarget.provider_code }}</b></div><div><span>当前版本</span><b>v{{ purgeTarget.row_version }}</b></div><div><span>流程影响</span><b>物理删除，不可撤销</b></div></div></AdminConfirmDialog>
      <AdminConfirmDialog :open="Boolean(revokeApprovalTarget)" title="撤销云端诊疗数据处理授权" description="撤销后，使用该云端模型的新 Agent 任务会被阻断，已开始任务在下一执行检查点失败关闭。" confirm-label="确认撤销" :busy="Boolean(busy)" @update:open="!$event && (revokeApprovalTarget = null)" @confirm="revokeProcessingApproval"><label class="revoke-reason"><span>撤销原因</span><textarea v-model="revokeReason" minlength="2" maxlength="500" rows="3" placeholder="请说明合规、合同或安全原因" /></label></AdminConfirmDialog>
    </template>
  </section>
</template>

<style scoped>
:deep(.task-action.publish) { color: #0c7d68; border-color: #7fc4b6; background: #eaf8f5; font-weight: 700; }
.admin-model-toolbar { flex-wrap: wrap; }
.admin-filter { display: inline-flex; gap: 2px; padding: 3px; border: 1px solid #d6e1eb; border-radius: 9px; background: #f4f7fb; }
.admin-filter button { border: 0; padding: 4px 10px; border-radius: 6px; background: transparent; color: #5b7188; font: inherit; font-size: 11px; font-weight: 650; cursor: pointer; }
.admin-filter button.active { background: #fff; color: #185b83; box-shadow: 0 1px 2px rgb(20 60 100 / 14%); }
.model-api-guide { margin-bottom: 18px; }
.model-api-guide-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; padding: 16px; }
.model-api-guide-grid article { padding: 14px; border: 1px solid #d8e7e4; border-radius: 10px; background: #f5fbfa; }
.model-api-guide-grid b { color: #087c75; }
.model-api-guide-grid p { margin: 7px 0 0; color: #526579; line-height: 1.6; }
.admin-form label small { color: #7a5b28; line-height: 1.45; }
.model-connection-error { color: #b4232f !important; overflow-wrap: anywhere; }
.api-key-input-row { display: grid; grid-template-columns: minmax(0,1fr) auto; gap: 8px; }.api-key-input-row .button { min-width: 64px; }
.processing-approval-layout { display: grid; gap: 16px; }
.processing-approval-current { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); gap: 12px; padding: 14px; border: 1px solid #b9dcd7; border-radius: 12px; background: #f3fbfa; }
.processing-approval-current div { display: grid; gap: 4px; min-width: 0; }.processing-approval-current span { color: #66778a; font-size: .82rem; }.processing-approval-current b { overflow-wrap: anywhere; }.processing-approval-current .button { justify-self: start; }
.approval-form-grid { display: grid; grid-template-columns: 1.4fr .8fr 1fr; gap: 12px; }
.approval-scopes { display: flex; flex-wrap: wrap; gap: 10px 18px; margin: 0; padding: 14px; border: 1px solid #d7e3e8; border-radius: 10px; }.approval-scopes legend { padding: 0 6px; font-weight: 700; }.approval-scopes label { display: inline-flex; flex-direction: row; align-items: center; gap: 7px; }
.approval-history { display: grid; gap: 8px; border-top: 1px solid #e1e8ed; padding-top: 14px; }.approval-history h3 { margin: 0; }.approval-history article { display: grid; grid-template-columns: auto minmax(0,1fr); align-items: center; gap: 5px 10px; padding: 10px 12px; border: 1px solid #e1e8ed; border-radius: 9px; }.approval-history small { grid-column: 2; color: #66778a; }
.revoke-reason { display: grid; gap: 7px; }.revoke-reason span { font-weight: 700; }.revoke-reason textarea { width: 100%; resize: vertical; }
@media (max-width: 900px) { .model-api-guide-grid { grid-template-columns: 1fr; } }
@media (max-width: 720px) { .processing-approval-current, .approval-form-grid { grid-template-columns: 1fr; } }
</style>
