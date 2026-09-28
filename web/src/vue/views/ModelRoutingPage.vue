<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed } from 'vue';
import { issueAiLease, listModelDataProcessingApprovals, listModelDeployments } from '../../api/ai-platform';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';

const leaseQuery = useQuery({ queryKey: ['ai-platform', 'model-routing', 'lease'],
  queryFn: () => issueAiLease('AI_PLATFORM_ADMIN'), retry: false, staleTime: 5 * 60_000, gcTime: 0 });
const routingQuery = useQuery({ queryKey: ['ai-platform', 'model-routing', 'readiness'], enabled: () => Boolean(leaseQuery.data.value), retry: false,
  queryFn: async () => {
    const lease = leaseQuery.data.value!;
    const models = await listModelDeployments(lease);
    const approvalPairs = await Promise.all(models.filter((model) => model.residency_policy === 'CLOUD_ALLOWED')
      .map(async (model) => [model.model_deployment_id, await listModelDataProcessingApprovals(lease, model.model_deployment_id)] as const));
    return { models, approvals: new Map(approvalPairs) };
  } });
const models = computed(() => routingQuery.data.value?.models ?? []);
const issue = computed(() => leaseQuery.error.value ?? routingQuery.error.value
  ? toClinicalIssue(leaseQuery.error.value ?? routingQuery.error.value) : null);
const activeApproval = (id: string) => routingQuery.data.value?.approvals.get(id)?.some((approval) =>
  approval.status === 'ACTIVE' && new Date(approval.expires_at).getTime() > Date.now()) ?? false;
const readiness = (model: (typeof models.value)[number]) => {
  if (model.provider_code === 'SYNTHETIC' || model.endpoint_url?.includes('.example/') === true)
    return { ready: false, label: '开发验收配置已退役', reason: '保留示例地址不会进入生产医助模型候选集' };
  if (model.status !== 'ACTIVE') return { ready: false, label: '服务已停用', reason: '不会进入医助模型候选集' };
  if (model.evaluation_status !== 'APPROVED') return { ready: false, label: '尚未发布', reason: '需要先发布模型服务' };
  if (model.connection_status !== 'READY') return { ready: false, label: '连接不可用', reason: '需要通过真实连接检测' };
  if (model.residency_policy === 'CLOUD_ALLOWED' && !activeApproval(model.model_deployment_id))
    return { ready: false, label: '缺少云端处理授权', reason: '需补充个人信息保护影响评估与委托处理依据' };
  return { ready: true, label: '可供医助选择', reason: model.residency_policy === 'CLOUD_ALLOWED' ? '已满足云端处理授权' : '院内/本地模型无需云端处理授权' };
};
const eligibleCount = computed(() => models.value.filter((model) => readiness(model).ready).length);
function residencyLabel(value: string) {
  return ({ ON_PREM_ONLY: '仅院内部署', LOCAL_PREFERRED: '院内优先', CLOUD_ALLOWED: '允许云端处理' } as Record<string, string>)[value] ?? value;
}
</script>

<template>
  <section data-page-root class="content admin-content vue-native-page">
    <div class="page-heading admin-heading"><div><p class="eyebrow">AI中心 / 模型服务 / 医助模型路由</p><h1>医助模型候选与数据边界</h1><p>按运行时真实门禁核对模型：启用、评测通过、连接可用，并在云端处理时具备有效合规授权。</p></div></div>
    <ClinicalPageState v-if="leaseQuery.isPending.value || routingQuery.isPending.value" kind="loading" message="正在核对模型路由门禁" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="routingQuery.refetch()" />
    <template v-else>
      <section class="admin-metrics" aria-label="模型路由门禁统计">
        <article><span>已配置模型</span><strong>{{ models.length }}</strong><small>真实数据库记录</small></article>
        <article><span>可供医助选择</span><strong>{{ eligibleCount }}</strong><small>已通过全部运行门禁</small></article>
        <article><span>被门禁阻断</span><strong>{{ models.length - eligibleCount }}</strong><small>不会静默降级为成功</small></article>
      </section>
      <section class="admin-panel">
        <header><div><h2>运行时模型候选集</h2><p>医生在 AI医助 EVA 中明确选择模型时使用该模型；未选择时，服务端只从真实可用且非示例地址的候选中选取。</p></div><button class="button secondary" @click="routingQuery.refetch()">重新核对</button></header>
        <div v-if="models.length === 0" class="admin-empty">暂无模型服务，医助任务会被明确阻断。</div>
        <div v-else class="admin-table-wrap"><table class="admin-table">
          <thead><tr><th>模型</th><th>部署边界</th><th>评测 / 连接</th><th>云端处理授权</th><th>路由结论</th></tr></thead>
          <tbody><tr v-for="model in models" :key="model.model_deployment_id">
            <td><strong>{{ model.display_name }}</strong><small>{{ model.provider_code }} · {{ model.model_code }}</small></td>
            <td>{{ residencyLabel(model.residency_policy) }}<small><code>{{ model.endpoint_url ?? '院内部署地址未公开' }}</code></small></td>
            <td>{{ model.evaluation_status === 'APPROVED' ? (model.release_evidence_ready ? '已评测通过' : '未进行评测') : model.evaluation_status === 'REJECTED' ? '评测拒绝' : '评测中' }} · {{ model.connection_status === 'READY' ? '连接可用' : '连接不可用' }}</td>
            <td>{{ model.residency_policy !== 'CLOUD_ALLOWED' ? '不适用' : activeApproval(model.model_deployment_id) ? '有效' : '缺失或已过期' }}</td>
            <td><span class="admin-status" :class="readiness(model).ready ? 'active' : 'inactive'">{{ readiness(model).label }}</span><small>{{ readiness(model).reason }}</small></td>
          </tr></tbody>
        </table></div>
      </section>
      <aside class="admin-panel"><header><div><h2>医疗数据使用要求</h2><p>云模型不能只靠配置密钥启用。使用前还需确认可处理的数据范围、合规授权、保存期限和授权有效期。</p></div><span class="admin-status active">系统检查</span></header></aside>
    </template>
  </section>
</template>
