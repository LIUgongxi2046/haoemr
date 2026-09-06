<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref, watch } from 'vue';
import type { ModelEvaluationWire } from '../../generated/contracts';
import { issueAiLease, listModelDeployments, listModelEvaluations, recordModelEvaluation } from '../../api/ai-platform';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';

const leaseQuery = useQuery({
  queryKey: ['ai-platform', 'model-evaluation', 'lease'],
  queryFn: () => issueAiLease('AI_PLATFORM_ADMIN'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const modelsQuery = useQuery({
  queryKey: ['ai-platform', 'model-evaluation', 'models'],
  queryFn: () => listModelDeployments(leaseQuery.data.value!),
  enabled: () => Boolean(leaseQuery.data.value),
  retry: false,
});

const selectedModelId = ref('');
watch(() => modelsQuery.data.value, (models) => {
  if (!models?.length) { selectedModelId.value = ''; return; }
  if (!models.some((model) => model.model_deployment_id === selectedModelId.value)) {
    selectedModelId.value = models.find((model) => model.status === 'ACTIVE')?.model_deployment_id ?? models[0].model_deployment_id;
  }
});

const evaluationsQuery = useQuery({
  queryKey: ['ai-platform', 'model-evaluation', 'evaluations', selectedModelId],
  queryFn: () => listModelEvaluations(leaseQuery.data.value!, selectedModelId.value),
  enabled: () => Boolean(leaseQuery.data.value && selectedModelId.value),
  retry: false,
});
const issue = computed(() => (leaseQuery.error.value ?? modelsQuery.error.value ?? evaluationsQuery.error.value)
  ? toClinicalIssue(leaseQuery.error.value ?? modelsQuery.error.value ?? evaluationsQuery.error.value) : null);
const models = computed(() => modelsQuery.data.value ?? []);
const evaluations = computed(() => evaluationsQuery.data.value ?? []);
const passedCount = computed(() => evaluations.value.filter((evaluation) => evaluation.status === 'PASSED').length);

const form = reactive({ evalName: '', score: 0.9, threshold: 0.8, evaluatedAt: new Date().toISOString() });
const busy = ref('');
const notice = ref('');

function formatDate(value: string | null | undefined) {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—';
}

async function reload() {
  notice.value = '';
  await Promise.all([modelsQuery.refetch(), evaluationsQuery.refetch()]);
}

async function record() {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || !selectedModelId.value || !form.evalName.trim()) return;
  if (form.score < 0 || form.score > 1 || form.threshold < 0 || form.threshold > 1) {
    notice.value = '分值与阈值必须在 0 到 1 之间。'; return;
  }
  busy.value = 'create'; notice.value = '';
  try {
    await recordModelEvaluation(lease, {
      model_deployment_id: selectedModelId.value,
      eval_name: form.evalName.trim(),
      score: form.score,
      threshold: form.threshold,
      evaluated_at: form.evaluatedAt,
    });
    form.evalName = '';
    notice.value = form.score >= form.threshold
      ? '评估记录已通过阈值：该记录仅作为评测证据，模型保持“评测中”，不会自动进入 AI医助 EVA 路由。'
      : '评估未通过：模型已退出 AI医助 EVA 路由，请整改后重新评估。';
    await Promise.all([modelsQuery.refetch(), evaluationsQuery.refetch()]);
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = ''; }
}
</script>

<template>
  <section data-page-root class="content admin-content vue-native-page">
    <div class="page-heading admin-heading">
      <div>
        <p class="eyebrow">AI 平台 / 模型评估、影子、灰度与隔离</p>
        <h1>模型评估</h1>
        <p>按模型部署记录不可变评估证据；手工分值不能直接发布模型，未通过会立即退出 AI医助 EVA 路由。</p>
      </div>
      <div class="admin-inline-tools">
        <label class="admin-code-input"><span>模型部署</span>
          <select v-model="selectedModelId">
            <option value="" disabled>请选择模型</option>
            <option v-for="model in models" :key="model.model_deployment_id" :value="model.model_deployment_id">{{ model.display_name }}（{{ model.model_code }}）</option>
          </select>
        </label>
      </div>
    </div>

    <ClinicalPageState v-if="leaseQuery.isPending.value || modelsQuery.isPending.value || evaluationsQuery.isPending.value" kind="loading" message="正在读取模型评估结果" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reload" />

    <template v-else>
      <section class="admin-metrics" aria-label="评估统计">
        <article><span>评估记录</span><strong>{{ evaluations.length }}</strong><small>当前模型</small></article>
        <article><span>通过</span><strong>{{ passedCount }}</strong></article>
      </section>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>

      <div class="admin-layout">
        <section class="admin-panel">
          <header>
            <div><h2>评估台账</h2><p>分值 ≥ 阈值即判定为通过。</p></div>
            <button class="button secondary" @click="evaluationsQuery.refetch()">刷新</button>
          </header>
          <div v-if="!selectedModelId" class="admin-empty" role="status">请先选择模型部署。</div>
          <div v-else-if="evaluations.length === 0" class="admin-empty" role="status">该模型暂无评估记录，可在右侧记录。</div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>评估名称</th><th>分值</th><th>阈值</th><th>评估时间</th><th>结论</th></tr></thead>
              <tbody>
                <tr v-for="evaluation in evaluations" :key="evaluation.model_evaluation_id">
                  <td><strong>{{ evaluation.eval_name }}</strong><small>…{{ evaluation.model_evaluation_id.slice(-8) }}</small></td>
                  <td><code>{{ evaluation.score }}</code></td>
                  <td><code>{{ evaluation.threshold }}</code></td>
                  <td>{{ formatDate(evaluation.evaluated_at) }}</td>
                  <td><span class="admin-status" :class="evaluation.status.toLowerCase()">{{ evaluation.status === 'PASSED' ? '通过' : '未通过' }}</span></td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="admin-panel admin-form-panel">
          <header><div><h2>记录评估证据</h2><p>分值与阈值均为 0–1；通过只形成证据，不自动批准模型。</p></div></header>
          <form class="admin-form" @submit.prevent="record">
            <label><span>评估名称</span><input v-model="form.evalName" maxlength="256" required placeholder="例：临床问答准确率" /></label>
            <label><span>分值（0–1）</span><input v-model.number="form.score" type="number" min="0" max="1" step="0.01" required /></label>
            <label><span>阈值（0–1）</span><input v-model.number="form.threshold" type="number" min="0" max="1" step="0.01" required /></label>
            <label><span>评估时间</span><input v-model="form.evaluatedAt" type="datetime-local" required /></label>
            <button class="button primary full" :disabled="Boolean(busy) || !selectedModelId">{{ busy === 'create' ? '正在记录…' : '记录并判定' }}</button>
          </form>
        </section>
      </div>
    </template>
  </section>
</template>
