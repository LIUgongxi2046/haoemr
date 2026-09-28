<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, ref } from 'vue';
import type { ModelDeploymentWire } from '../../generated/contracts';
import { issueAiLease, listModelDeployments, testModelDeploymentConnection } from '../../api/ai-platform';
import AdminConfirmDialog from '../components/AdminConfirmDialog.vue';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';

const leaseQuery = useQuery({ queryKey: ['ai-platform', 'model-connection', 'lease'],
  queryFn: () => issueAiLease('AI_PLATFORM_ADMIN'), retry: false, staleTime: 5 * 60_000, gcTime: 0 });
const modelsQuery = useQuery({ queryKey: ['ai-platform', 'model-connection', 'models'],
  queryFn: () => listModelDeployments(leaseQuery.data.value!), enabled: () => Boolean(leaseQuery.data.value), retry: false });
const models = computed(() => modelsQuery.data.value ?? []);
const issue = computed(() => leaseQuery.error.value ?? modelsQuery.error.value
  ? toClinicalIssue(leaseQuery.error.value ?? modelsQuery.error.value) : null);
const testTarget = ref<ModelDeploymentWire | null>(null);
const busy = ref(false);
const notice = ref('');

function formatDate(value: string | null | undefined) {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '尚未检测';
}
function connectionLabel(value: ModelDeploymentWire['connection_status']) {
  return ({ NOT_CONFIGURED: '未配置', UNVERIFIED: '待检测', READY: '可用', FAILED: '失败' } as const)[value];
}
function connectionClass(value: ModelDeploymentWire['connection_status']) {
  return value === 'READY' ? 'active' : value === 'UNVERIFIED' ? 'evaluating' : 'inactive';
}
function isRetiredSynthetic(model: ModelDeploymentWire) {
  return model.provider_code === 'SYNTHETIC' || model.endpoint_url?.includes('.example/') === true;
}
function testLabel(model: ModelDeploymentWire) { return isRetiredSynthetic(model) ? '已退役' : '真实检测'; }
async function runConnectionTest() {
  if (!leaseQuery.data.value || !testTarget.value || busy.value) return;
  busy.value = true; notice.value = '';
  try {
    const result = await testModelDeploymentConnection(leaseQuery.data.value, testTarget.value);
    notice.value = result.connection_status === 'READY'
      ? `“${result.display_name}”连接检测通过，真实服务响应 ${result.last_connection_latency_ms ?? 0} ms。`
      : `“${result.display_name}”连接检测失败：${result.last_connection_error_code ?? '未返回错误码'}。`;
    testTarget.value = null;
    await modelsQuery.refetch();
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = false; }
}
</script>

<template>
  <section data-page-root class="content admin-content vue-native-page">
    <div class="page-heading admin-heading"><div><p class="eyebrow">AI中心 / 模型服务 / 连接检测</p><h1>模型连接与凭据状态</h1><p>对真实模型提供方发起服务端最小连接检测；API Key 只显示脱敏引用，不返回明文。开发验收的保留示例地址已退役，不可检测或路由。</p></div></div>
    <ClinicalPageState v-if="leaseQuery.isPending.value || modelsQuery.isPending.value" kind="loading" message="正在读取模型连接状态" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="modelsQuery.refetch()" />
    <template v-else>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>
      <section class="admin-metrics" aria-label="模型连接统计">
        <article><span>模型服务</span><strong>{{ models.length }}</strong><small>数据库实时配置</small></article>
        <article><span>运行时可用</span><strong>{{ models.filter((item) => !isRetiredSynthetic(item) && item.status === 'ACTIVE' && item.connection_status === 'READY').length }}</strong><small>启用且连接通过</small></article>
        <article><span>需处理</span><strong>{{ models.filter((item) => isRetiredSynthetic(item) || item.connection_status !== 'READY').length }}</strong><small>未配置、待检测、失败或已退役开发配置</small></article>
      </section>
      <section class="admin-panel">
        <header><div><h2>连接检测台账</h2><p>真实 Provider 的检测会发出最小请求，并记录时间、时延和错误码；不把本地配置校验表述为外部服务可用。</p></div><button class="button secondary" @click="modelsQuery.refetch()">刷新</button></header>
        <div v-if="models.length === 0" class="admin-empty">暂无模型服务，请先在“模型服务”中新建配置。</div>
        <div v-else class="admin-table-wrap"><table class="admin-table">
          <thead><tr><th>模型</th><th>服务地址</th><th>凭据</th><th>连接状态</th><th>最近检测</th><th>操作</th></tr></thead>
          <tbody><tr v-for="model in models" :key="model.model_deployment_id">
            <td><strong>{{ model.display_name }}</strong><small>{{ model.provider_code }} · {{ model.model_code }}</small></td>
            <td><code>{{ model.endpoint_url ?? '未配置' }}</code></td>
            <td>{{ model.credential_configured ? model.credential_hint : '未配置' }}</td>
            <td><span class="admin-status" :class="connectionClass(model.connection_status)">{{ connectionLabel(model.connection_status) }}</span><small v-if="model.last_connection_error_code"><code>{{ model.last_connection_error_code }}</code></small></td>
            <td>{{ formatDate(model.last_connection_tested_at) }}<small v-if="model.last_connection_latency_ms != null">{{ model.last_connection_latency_ms }} ms</small></td>
            <td><button class="task-action" :disabled="isRetiredSynthetic(model) || model.status !== 'ACTIVE' || !model.credential_configured || busy" @click="testTarget = model">{{ testLabel(model) }}</button></td>
          </tr></tbody>
        </table></div>
      </section>
      <AdminConfirmDialog :open="Boolean(testTarget)" :title="`检测 ${testTarget?.display_name ?? ''} 的真实连接`" description="系统将使用已加密保存的凭据向 Provider 发出最小请求，并把检测结果写入运行台账。" confirm-label="开始检测" :busy="busy" @update:open="!$event && (testTarget = null)" @confirm="runConnectionTest" />
    </template>
  </section>
</template>
