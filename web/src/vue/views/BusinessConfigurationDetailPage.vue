<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed } from 'vue';
import { RouterLink, useRoute } from 'vue-router';
import type { ConfigurationItemWire, ConfigurationRevisionWire, ConfigurationRuntimeEvidenceWire, DepartmentSupportAssessmentWire } from '../../generated/contracts';
import {
  getConfiguration, getConfigurationRuntimeExecution, issueConfigurationLease,
  listConfigurationRevisions, listConfigurationRuntimeEvidence, listConfigurations,
  listConfigurationRuntimeExecutions, type ConfigurationRuntimeExecution,
} from '../../api/config';
import { issueSpecialtySupportLease, loadSpecialtySupportAssessments } from '../../clinical-api';
import { toClinicalIssue } from '../clinical-error';

const route = useRoute();
const moduleCode = computed(() => String(route.params.module ?? 'workflow'));
const configId = computed(() => String(route.params.configId ?? ''));
const section = computed(() => String(route.params.section || 'overview'));
const executionId = computed(() => String(route.params.executionId ?? ''));
const evidenceId = computed(() => String(route.params.evidenceId ?? ''));
const auditId = computed(() => String(route.params.auditId ?? ''));
const modules: Record<string, { title: string; type: string; base: string }> = {
  workflow: { title: '流程设计', type: 'WORKFLOW', base: '/workflow' },
  'capability-pack': { title: '能力包', type: 'CAPABILITY_PACK_COMPOSITION', base: '/capability-pack' },
  'specialty-coverage': { title: '科室适配', type: 'SPECIALTY_SUPPORT', base: '/specialty-coverage' },
  'form-designer': { title: '表单模板', type: 'FORM_TEMPLATE', base: '/form-designer' },
  'rule-center': { title: '规则时限', type: 'RULE', base: '/rule-center' },
  'scope-designer': { title: '职责范围', type: 'SCOPE', base: '/scope-designer' },
};
const moduleDefinition = computed(() => modules[moduleCode.value] ?? modules.workflow);
const specialty = computed(() => moduleDefinition.value.type === 'SPECIALTY_SUPPORT');

const configLease = useQuery({ queryKey: ['business-config-deep', 'lease'], queryFn: issueConfigurationLease, enabled: () => !specialty.value, retry: false, staleTime: 300000 });
const specialtyLease = useQuery({ queryKey: ['business-config-deep', 'specialty-lease'], queryFn: issueSpecialtySupportLease, enabled: specialty, retry: false, staleTime: 300000 });
const configs = useQuery({
  queryKey: computed(() => ['business-config-deep', moduleCode.value, moduleDefinition.value.type]),
  queryFn: () => listConfigurations(configLease.data.value!, moduleDefinition.value.type),
  enabled: () => Boolean(configLease.data.value && !specialty.value), retry: false,
});
const assessments = useQuery({
  queryKey: ['business-config-deep', 'specialty-assessments'],
  queryFn: () => loadSpecialtySupportAssessments(specialtyLease.data.value!),
  enabled: () => Boolean(specialtyLease.data.value && specialty.value), retry: false,
});
const configuration = useQuery({
  queryKey: computed(() => ['business-config-deep', 'item', configId.value]),
  queryFn: () => getConfiguration(configLease.data.value!, configId.value),
  enabled: () => Boolean(configLease.data.value && configId.value && !specialty.value), retry: false,
});
const revisions = useQuery({
  queryKey: computed(() => ['business-config-deep', 'revisions', configId.value]),
  queryFn: () => listConfigurationRevisions(configLease.data.value!, configId.value),
  enabled: () => Boolean(configLease.data.value && configId.value && !specialty.value), retry: false,
});
const executions = useQuery({
  queryKey: computed(() => ['business-config-deep', 'executions', configId.value]),
  queryFn: () => listConfigurationRuntimeExecutions(configLease.data.value!, moduleDefinition.value.type, configuration.data.value!.config_key),
  enabled: () => Boolean(configLease.data.value && configuration.data.value && ['WORKFLOW', 'FORM_TEMPLATE', 'RULE', 'SCOPE'].includes(moduleDefinition.value.type)), retry: false,
});
const execution = useQuery({
  queryKey: computed(() => ['business-config-deep', 'execution', executionId.value]),
  queryFn: () => getConfigurationRuntimeExecution(configLease.data.value!, executionId.value),
  enabled: () => Boolean(configLease.data.value && executionId.value && !specialty.value), retry: false,
});
const evidence = useQuery({
  queryKey: computed(() => ['business-config-deep', 'evidence', executionId.value]),
  queryFn: () => listConfigurationRuntimeEvidence(configLease.data.value!, executionId.value),
  enabled: () => Boolean(configLease.data.value && executionId.value && evidenceId.value && !specialty.value), retry: false,
});

const specialtyItem = computed<DepartmentSupportAssessmentWire | null>(() => assessments.data.value?.find(item => item.department_support_assessment_id === configId.value) ?? null);
const selectedEvidence = computed(() => evidence.data.value?.find(item => item.audit_event_id === auditId.value) ?? null);
const error = computed(() => [configLease.error.value, specialtyLease.error.value, configs.error.value, assessments.error.value,
  configuration.error.value, revisions.error.value, executions.error.value, execution.error.value, evidence.error.value].find(Boolean));
const issue = computed(() => error.value ? toClinicalIssue(error.value) : null);
const root = computed(() => `/business-config/${moduleCode.value}`);
const configRoot = computed(() => `${root.value}/${configId.value}`);
const list = computed<Array<ConfigurationItemWire | DepartmentSupportAssessmentWire>>(() => specialty.value ? (assessments.data.value ?? []) : (configs.data.value ?? []));
const revisionRows = computed<ConfigurationRevisionWire[]>(() => revisions.data.value ?? []);
const executionRows = computed<ConfigurationRuntimeExecution[]>(() => executions.data.value ?? []);
const evidenceRows = computed<ConfigurationRuntimeEvidenceWire[]>(() => evidence.data.value ?? []);
const currentConfiguration = computed(() => configuration.data.value ?? null);
const currentExecution = computed(() => execution.data.value ?? null);
const displayName = (item: ConfigurationItemWire | DepartmentSupportAssessmentWire) => 'display_name' in item ? item.display_name : item.clinical_scope_code;
const itemId = (item: ConfigurationItemWire | DepartmentSupportAssessmentWire) => 'config_id' in item ? item.config_id : item.department_support_assessment_id;
</script>

<template>
  <section data-page-root class="content vue-native-page deep-config">
    <nav class="crumbs"><RouterLink to="/workflow">业务配置</RouterLink><span>›</span><RouterLink :to="root">{{ moduleDefinition.title }}</RouterLink><template v-if="configId"><span>›</span><RouterLink :to="configRoot">{{ currentConfiguration?.display_name ?? specialtyItem?.clinical_scope_code ?? configId }}</RouterLink></template><template v-if="section!=='overview'"><span>›</span><RouterLink :to="`${configRoot}/${section}`">{{ section }}</RouterLink></template><template v-if="executionId"><span>›</span><RouterLink :to="`${configRoot}/${section}/${executionId}`">执行实例</RouterLink></template><template v-if="evidenceId"><span>›</span><RouterLink :to="`${configRoot}/${section}/${executionId}/evidence`">证据链</RouterLink></template><template v-if="auditId"><span>›</span><b>审计事件</b></template></nav>
    <header class="page-head"><div><p class="eyebrow">业务配置 / 配置详情</p><h1>{{ moduleDefinition.title }}详情</h1><p>查看配置版本、审批状态与运行记录。</p></div><RouterLink class="btn" :to="moduleDefinition.base">返回工作台</RouterLink></header>
    <p v-if="issue" class="notice error">{{ issue.code }}：{{ issue.message }}</p>

    <section v-if="!configId" class="panel"><header><h2>配置列表</h2><span>{{ list.length }} 项</span></header><div class="object-list"><RouterLink v-for="item in list" :key="itemId(item)" :to="`${root}/${itemId(item)}`"><b>{{ displayName(item) }}</b><code>{{ itemId(item) }}</code><span>row v{{ item.row_version }}</span></RouterLink></div><p v-if="!list.length" class="empty">暂无该模块的配置。</p></section>

    <template v-else-if="specialtyItem"><section class="panel"><header><h2>{{ specialtyItem.clinical_scope_code }}</h2><span>{{ specialtyItem.support_level }}</span></header><dl class="facts"><div><dt>科室</dt><dd><code>{{ specialtyItem.department_id }}</code></dd></div><div><dt>能力包发布</dt><dd><code>{{ specialtyItem.pack_release_id ?? '未绑定' }}</code></dd></div><div><dt>证据哈希</dt><dd><code>{{ specialtyItem.evidence_bundle_hash ?? '未绑定' }}</code></dd></div><div><dt>有效期</dt><dd>{{ specialtyItem.expires_at ?? '未设置' }}</dd></div></dl><h3>安全门</h3><ul><li v-for="gate in specialtyItem.missing_safety_gates" :key="gate">{{ gate }}</li></ul><p v-if="!specialtyItem.missing_safety_gates.length" class="ok">无缺失安全门</p></section></template>

    <template v-else-if="currentConfiguration">
      <nav class="sections"><RouterLink :to="configRoot">概览</RouterLink><details class="technical-details"><summary>技术详情</summary><RouterLink :to="`${configRoot}/payload`">查看配置数据</RouterLink></details><RouterLink :to="`${configRoot}/revisions`">修订历史</RouterLink><RouterLink v-if="['WORKFLOW','FORM_TEMPLATE','RULE','SCOPE'].includes(moduleDefinition.type)" :to="`${configRoot}/runtime`">运行证据</RouterLink></nav>
      <section v-if="section==='overview'" class="panel"><header><h2>{{ currentConfiguration.display_name }}</h2><span>{{ currentConfiguration.status }}</span></header><dl class="facts"><div><dt>配置键</dt><dd><code>{{ currentConfiguration.config_key }}</code></dd></div><div><dt>类型</dt><dd>{{ currentConfiguration.config_type }}</dd></div><div><dt>Schema</dt><dd>v{{ currentConfiguration.schema_version }}</dd></div><div><dt>校验 / 审批</dt><dd>{{ currentConfiguration.validation_state }} / {{ currentConfiguration.approval_state }}</dd></div></dl></section>
      <section v-else-if="section==='payload'" class="panel"><header><h2>版本绑定配置载荷</h2><span>row v{{ currentConfiguration.row_version }}</span></header><details :key="configId" class="technical-details"><summary>展开配置 JSON</summary><pre>{{ JSON.stringify(currentConfiguration.payload, null, 2) }}</pre></details></section>
      <section v-else-if="section==='revisions'" class="panel"><header><h2>不可变修订历史</h2><span>{{ revisionRows.length }} 版</span></header><div class="history"><article v-for="item in revisionRows" :key="item.revision_no"><b>v{{ item.revision_no }} · {{ item.status }}</b><span>{{ item.validation_state }} / {{ item.approval_state }}</span><small>{{ item.change_reason ?? '未填写原因' }} · {{ new Date(item.created_at).toLocaleString('zh-CN',{hour12:false}) }}</small></article></div></section>
      <section v-else-if="section==='runtime'&&!executionId" class="panel"><header><h2>数据库运行实例</h2><span>{{ executionRows.length }} 次</span></header><div class="history"><RouterLink v-for="item in executionRows" :key="item.execution_id" :to="`${configRoot}/runtime/${item.execution_id}`"><b>{{ item.operation }} · {{ item.state }}</b><code>{{ item.configuration_watermark }}</code><small>{{ new Date(item.created_at).toLocaleString('zh-CN',{hour12:false}) }}</small></RouterLink></div><p v-if="!executionRows.length" class="empty">尚无运行证据。</p></section>
      <section v-else-if="currentExecution" class="panel"><header><h2>执行实例 {{ currentExecution.state }}</h2><RouterLink class="btn" :to="`${configRoot}/runtime/${executionId}/evidence`">查看证据链</RouterLink></header><dl class="facts"><div><dt>执行 ID</dt><dd><code>{{ currentExecution.execution_id }}</code></dd></div><div><dt>配置水印</dt><dd><code>{{ currentExecution.configuration_watermark }}</code></dd></div><div><dt>实例版本</dt><dd>v{{ currentExecution.row_version }}</dd></div><div><dt>当前节点</dt><dd>{{ currentExecution.current_node ?? '不适用' }}</dd></div></dl><details :key="executionId" class="technical-details"><summary>展开运行输出 JSON</summary><pre>{{ JSON.stringify(currentExecution.output_payload, null, 2) }}</pre></details><template v-if="evidenceId"><h3>审计证据链</h3><div class="history"><RouterLink v-for="item in evidenceRows" :key="item.audit_event_id" :to="`${configRoot}/runtime/${executionId}/evidence/${item.audit_event_id}`"><b>{{ item.action_code }}</b><code>{{ item.event_hash }}</code><small>{{ item.trace_id }} · {{ new Date(item.occurred_at).toLocaleString('zh-CN',{hour12:false}) }}</small></RouterLink></div><details v-if="selectedEvidence" :key="auditId" class="technical-details"><summary>展开审计事件详情</summary><pre>{{ JSON.stringify(selectedEvidence, null, 2) }}</pre></details></template></section>
      <section v-else class="panel empty">请求的深层对象不存在或不属于当前租户。</section>
    </template>
  </section>
</template>

<style scoped>
.technical-details summary { cursor: pointer; color: #42627c; font-weight: 600; }.technical-details[open] > summary { margin-bottom: 10px; }
.deep-config{color:#17283a;min-width:0}.crumbs,.sections{display:flex;align-items:center;gap:7px;min-width:0;margin-bottom:12px;overflow:auto;color:#657487;font-size:10px;white-space:nowrap}.crumbs a,.sections a{color:#1769aa;text-decoration:none}.page-head,.panel>header{display:flex;align-items:flex-start;justify-content:space-between;gap:14px}.page-head{margin-bottom:14px}.page-head h1{margin:3px 0 5px;font-size:23px}.page-head p:last-child{margin:0;color:#657487;font-size:11px}.eyebrow{margin:0;color:#1769aa;font-size:10px;font-weight:700}.btn{display:inline-flex;padding:8px 11px;border:1px solid #cbd8e1;border-radius:7px;background:#fff;color:#1769aa;text-decoration:none;white-space:nowrap}.notice,.panel{padding:13px;border:1px solid #dce5ec;border-radius:9px;background:#fff}.notice.error{margin-bottom:12px;border-color:#efbcbc;background:#fff1f1;color:#8d2c2c}.panel{display:grid;gap:12px}.panel h2,.panel h3{margin:0}.panel>header>span{color:#657487;font-size:10px}.object-list,.history{display:grid;gap:8px}.object-list a,.history article,.history a{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:5px 12px;padding:10px;border:1px solid #dde5eb;border-radius:8px;color:inherit;text-decoration:none;min-width:0}.object-list code,.history code{overflow-wrap:anywhere;color:#657487;font-size:9px}.object-list span,.history small{color:#657487;font-size:9px}.history small{grid-column:1/-1}.sections a{padding:8px 11px;border:1px solid #d5dfe6;border-radius:7px;background:#fff}.facts{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;margin:0}.facts>div{display:grid;gap:4px;padding:9px;border-radius:7px;background:#f6f9fb;min-width:0}.facts dt{color:#657487;font-size:9px}.facts dd{margin:0;font-size:11px;overflow-wrap:anywhere}.panel pre{max-height:480px;margin:0;padding:12px;border-radius:8px;background:#152536;color:#dcecf7;font:10px/1.55 ui-monospace,SFMono-Regular,Menlo,monospace;white-space:pre-wrap;overflow:auto}.panel ul{margin:0;padding-left:20px;color:#a43131}.ok{color:#267249}.empty{color:#657487;text-align:center}@media(max-width:700px){.page-head{display:grid}.facts{grid-template-columns:1fr}.object-list a,.history article,.history a{grid-template-columns:1fr}}
</style>
