<script setup lang="ts">
import { computed, ref } from 'vue';
import type { DocumentGovernanceSnapshotWire } from '../../generated/contracts';
import { currentRecordQc, recordQcField, recordQcWritingHints } from '../record-qc';

const props = withDefaults(defineProps<{
  snapshot?: DocumentGovernanceSnapshotWire | null;
  versionId: string;
  contentHash: string;
  fields: Record<string, string>;
  dirty?: boolean;
  loading?: boolean;
  error?: unknown;
  sections?: Record<string, unknown>;
  autoEnabled?: boolean;
  autoRunning?: boolean;
  autoError?: unknown;
}>(), { autoEnabled: undefined });
const emit = defineEmits<{ locate: [field: string]; retry: []; 'update:autoEnabled': [value: boolean]; retryAuto: [] }>();
const severity = ref('ALL');
const current = computed(() => props.error || props.loading || props.autoRunning || props.autoError ? null
  : currentRecordQc(props.snapshot, props.versionId, props.contentHash, Boolean(props.dirty)));
const active = computed(() => current.value?.quality_findings.filter(item =>
  item.document_version_id === props.versionId && ['OPEN', 'ACKNOWLEDGED'].includes(item.state)) ?? []);
const visible = computed(() => active.value.filter(item => severity.value === 'ALL' || item.severity === severity.value)
  .sort((a, b) => rank[a.severity] - rank[b.severity]));
const rank = { BLOCKING: 0, WARNING: 1, INFO: 2 };
const labels = { BLOCKING: '阻断', WARNING: '警告', INFO: '提示' };
const outcome = computed(() => current.value?.quality_run?.outcome);
const writingHints = computed(() => props.dirty && props.sections
  ? recordQcWritingHints(props.sections, props.fields) : []);
const history = computed(() => (current.value?.quality_run_history ?? []).filter(run =>
  run.document_version_id === props.versionId && run.content_hash === props.contentHash));
const stages: Record<string, string> = { MANUAL: '手动检查', POST_SAVE: '保存后检查', PRE_SIGN: '签署前检查', TERMINAL: '终末检查' };
function locate(path?: string | null) {
  const field = recordQcField(path, props.fields);
  if (current.value && field) emit('locate', field);
}
</script>

<template>
  <section class="record-qc-panel" aria-label="当前文书质控问题" data-testid="record-qc-panel">
    <header><h2>当前文书质控</h2><span v-if="current?.quality_run" class="status" :class="outcome === 'PASSED' ? 'green' : outcome === 'BLOCKED' ? 'red' : 'amber'">{{ outcome === 'PASSED' ? '规则检查通过' : outcome === 'BLOCKED' ? '存在阻断' : '存在警告' }}</span></header>
    <label v-if="autoEnabled !== undefined" class="qc-filter"><input type="checkbox" :checked="autoEnabled" @change="emit('update:autoEnabled', ($event.target as HTMLInputElement).checked)" />保存后自动运行规则检查（本页有效）</label>
    <p v-if="autoRunning" role="status">正在检查已保存版本；不会阻塞继续书写。</p>
    <div v-if="autoError" role="alert"><p>正文已保存，但自动质控失败，不能视为检查通过。</p><button type="button" class="btn sm" :disabled="dirty || autoRunning" @click="emit('retryAuto')">重试保存后检查</button></div>
    <details v-if="writingHints.length" open><summary>书写提示 · 仅本地预检，不作为正式结论</summary><ul><li v-for="hint in writingHints" :key="hint.field"><p>{{ hint.message }}</p><button class="btn sm" type="button" @click="emit('locate', hint.field)">定位：{{ fields[hint.field] }}</button></li></ul></details>
    <p v-if="dirty" role="status">正文已修改，旧质控结果不再适用。请保存后重新检查。</p>
    <p v-else-if="loading" role="status">正在读取当前版本质控证据…</p>
    <div v-else-if="error" role="alert"><p>质控证据读取失败，不代表检查通过。正文仍可编辑；签署仍由服务端校验。</p><button class="btn sm" type="button" @click="emit('retry')">重新读取质控</button></div>
    <p v-else-if="!current" role="status">暂无与当前版本匹配的质控证据，请重新检查。</p>
    <template v-else>
      <p v-if="!current.quality_run" role="status">当前版本尚未运行确定性质控，不代表检查通过。</p>
      <p v-else-if="active.length === 0" role="status">{{ outcome === 'PASSED' ? '本次规则检查未发现问题；不替代医生核对及签署门禁。' : '当前没有待处理发现；历史运行结论仍以服务端证据为准。' }}</p>
      <template v-if="active.length">
        <label class="qc-filter">待处理 {{ active.length }} 项 <select v-model="severity" aria-label="筛选质控严重程度"><option value="ALL">全部级别</option><option value="BLOCKING">阻断</option><option value="WARNING">警告</option><option value="INFO">提示</option></select></label>
        <p v-if="visible.length === 0">此级别没有待处理问题。</p>
        <ol class="qc-findings"><li v-for="finding in visible" :key="finding.finding_id" :data-severity="finding.severity">
          <strong>{{ labels[finding.severity] }}<small v-if="finding.state === 'ACKNOWLEDGED'"> · 已知悉，未解决</small></strong><p>{{ finding.message }}</p>
          <button v-if="recordQcField(finding.field_path, fields)" class="btn sm" type="button" @click="locate(finding.field_path)">定位：{{ fields[recordQcField(finding.field_path, fields)!] }}</button>
          <small v-else>当前编辑区无对应章节，请核对完整文书；系统不猜测位置。</small>
          <details><summary>规则证据</summary><p>{{ finding.rule_code }} · {{ finding.rule_version }}</p><code>{{ finding.field_path || '文书级问题' }}</code></details>
        </li></ol>
      </template>
      <details v-if="current.quality_run" class="qc-run-evidence"><summary>本次检查依据</summary><p>规则版本：{{ current.quality_run.rule_version }}</p><p>执行时间：{{ current.quality_run.executed_at }}</p><p>内容哈希：{{ current.quality_run.content_hash }}</p></details>
      <details v-if="history.length" class="qc-run-evidence"><summary>当前版本检查历史（最近 {{ history.length }} 次）</summary>
        <ol><li v-for="run in history" :key="run.quality_run_id"><p>{{ stages[run.trigger_stage ?? ''] ?? '历史阶段未记录' }} · {{ run.executed_at }} · {{ run.outcome === 'PASSED' ? '规则通过' : run.outcome === 'BLOCKED' ? '阻断' : '警告' }}</p><small>规则 {{ run.rule_version }}；当时阻断 {{ run.blocking_count }} / 警告 {{ run.warning_count }}</small>
          <p v-if="run.finding_snapshots == null">历史运行未保存逐条快照，不能由当前问题反推。</p>
          <ul v-else><li v-for="finding in run.finding_snapshots" :key="finding.finding_id">{{ labels[finding.severity] }} · {{ finding.message }}</li></ul>
        </li></ol>
      </details>
    </template>
    <small class="qc-boundary">确定性规则结果；AI 候选不在此计分，也不会自动修改正文。</small>
  </section>
</template>

<style scoped>
.record-qc-panel { min-width: 0; padding: 14px; border: 1px solid var(--line, #dce5ed); border-radius: 9px; background: #fff; overflow-wrap: anywhere; }
header { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
h2 { margin: 0; font-size: 14px; }
p { font-size: 12px; line-height: 1.65; margin: 9px 0; }
.qc-filter { display: flex; justify-content: space-between; align-items: center; gap: 8px; margin: 12px 0; font-size: 12px; }
select { max-width: 130px; padding: 5px; border: 1px solid #ccd8e4; border-radius: 5px; background: white; }
.qc-findings { list-style: none; margin: 0; padding: 0; display: grid; gap: 10px; max-height: 360px; overflow-y: auto; }
.qc-findings li { padding: 10px; border-left: 3px solid #4c87bd; background: #f5f9fd; }
li[data-severity="BLOCKING"] { border-color: #b53030; background: #fff6f5; }
li[data-severity="WARNING"] { border-color: #bd801b; background: #fffaf0; }
strong { font-size: 12px; } small, details { font-size: 11px; line-height: 1.6; }
details { margin-top: 9px; } summary { cursor: pointer; } code { white-space: normal; }
.qc-boundary { display: block; margin-top: 12px; color: #607080; }
</style>
