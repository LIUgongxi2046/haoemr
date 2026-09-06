<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { clinicalContext, issueDocumentLease, loadDocumentDiff, loadDocumentVersions, loadEncounterDocuments } from '../../clinical-api';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';

const route = useRoute();
const diffQuery = useQuery({
  queryKey: ['clinical', 'record-diff', route.params.documentId, route.params.fromVersionId, route.params.toVersionId],
  queryFn: async () => {
    const lease = await issueDocumentLease();
    let documentId = typeof route.params.documentId === 'string' ? route.params.documentId : undefined;
    let fromVersionId = typeof route.params.fromVersionId === 'string' ? route.params.fromVersionId : undefined;
    let toVersionId = typeof route.params.toVersionId === 'string' ? route.params.toVersionId : undefined;
    if (!documentId || !fromVersionId || !toVersionId) {
      const documents = await loadEncounterDocuments(lease);
      documentId = documents.find((item) => item.document_id === clinicalContext.documentId)?.document_id ?? documents[0]?.document_id;
      if (!documentId) return { kind: 'empty' as const, message: '本次就诊尚无可比较的病历文书' };
      const versions = await loadDocumentVersions(lease, documentId);
      if (versions.length < 2) return { kind: 'empty' as const, message: '当前文书只有一个版本，保存新版本后才能比较' };
      [toVersionId, fromVersionId] = [versions[0].document_version_id, versions[1].document_version_id];
    }
    const versions = await loadDocumentVersions(lease, documentId);
    const from = versions.find((item) => item.document_version_id === fromVersionId);
    const to = versions.find((item) => item.document_version_id === toVersionId);
    if (!from || !to) throw new Error('比较版本不属于当前文书上下文');
    return { kind: 'ready' as const, from, to, diff: await loadDocumentDiff(lease, documentId, fromVersionId, toVersionId) };
  },
  retry: false, staleTime: 0, gcTime: 0,
});
const issue = computed(() => diffQuery.error.value ? toClinicalIssue(diffQuery.error.value) : null);
function sectionLabel(field: string) { return ({ chief_complaint: '主诉', present_illness: '现病史', assessment: '诊断与评估', treatment_plan: '治疗与随访计划' } as Record<string, string>)[field] || field; }
function renderValue(value: unknown) { if (value === undefined || value === null || value === '') return '未填写'; return typeof value === 'string' ? value : JSON.stringify(value, null, 2); }
function formatDate(value: string) { return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'short', hour12: false }).format(new Date(value)); }
</script>

<template>
  <section data-page-root class="content vue-native-page"><div class="page-heading"><div><p class="eyebrow">病历主轴 / 版本比较</p><h1>病历版本差异</h1><p>对比两个病历版本，快速查看字段变化。</p></div><RouterLink class="button secondary" to="/record-versions">返回版本链</RouterLink></div><p class="record-center-intro">按结构化字段比较两个不可变版本；差异结果不会写回任何一版。</p>
    <ClinicalPageState v-if="diffQuery.isPending.value" kind="loading" message="正在服务端计算版本差异" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="diffQuery.refetch()" />
    <div v-else-if="diffQuery.data.value?.kind === 'empty'" class="record-evidence-empty"><h2>暂无可比较版本</h2><p>{{ diffQuery.data.value.message }}</p><RouterLink class="button primary" to="/record-versions">查看版本链</RouterLink></div>
    <section v-else-if="diffQuery.data.value?.kind === 'ready'" class="record-diff-card"><header><div><span>基线 v{{ diffQuery.data.value.from.version_no }}</span><b>{{ formatDate(diffQuery.data.value.from.created_at) }}</b><code>…{{ diffQuery.data.value.from.document_version_id.slice(-8) }}</code></div><div class="diff-direction">只读比较 <strong>{{ diffQuery.data.value.diff.changed_fields.length }}</strong> 处</div><div><span>目标 v{{ diffQuery.data.value.to.version_no }}</span><b>{{ formatDate(diffQuery.data.value.to.created_at) }}</b><code>…{{ diffQuery.data.value.to.document_version_id.slice(-8) }}</code></div></header>
      <div class="record-diff-columns"><b>字段</b><b>v{{ diffQuery.data.value.from.version_no }}</b><b>v{{ diffQuery.data.value.to.version_no }}</b></div><div v-if="diffQuery.data.value.diff.changed_fields.length === 0" class="record-no-change">两个版本的结构化内容一致</div><article v-for="field in diffQuery.data.value.diff.changed_fields" v-else :key="field" class="record-diff-row"><strong>{{ sectionLabel(field) }}</strong><div class="removed">{{ renderValue(diffQuery.data.value.diff.from_sections[field]) }}</div><div class="added">{{ renderValue(diffQuery.data.value.diff.to_sections[field]) }}</div></article>
      <footer><span>内容指纹</span><code>v{{ diffQuery.data.value.from.version_no }} {{ diffQuery.data.value.from.content_hash.slice(0, 14) }}…</code><code>v{{ diffQuery.data.value.to.version_no }} {{ diffQuery.data.value.to.content_hash.slice(0, 14) }}…</code><p>如需更正，必须从当前版本发起新版本流程，不允许覆盖已有证据。</p></footer></section>
  </section>
</template>
