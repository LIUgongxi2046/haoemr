<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router';
import { configurationPackScope, loadConfigurationPackOperation } from '../../api/configuration-packs';
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref, watch } from 'vue';
import type { DocumentTemplateCreateRequestWire, DocumentTemplateWire } from '../../generated/contracts';
import {
  clinicalContext, createDocumentTemplate, createDocumentTemplateVersion,
  deactivateDocumentTemplate, loadDocumentTemplates, publishDocumentTemplateVersion,
} from '../../clinical-api';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import AdminDataPager from '../components/AdminDataPager.vue';
import AdminActionDialog from '../components/AdminActionDialog.vue';
import AdminConfirmDialog from '../components/AdminConfirmDialog.vue';
import { documentFieldLabel, documentTypeLabel } from '../admin-display';
import { toClinicalIssue } from '../clinical-error';
import { templateRestorePayload } from '../template-restore';

const route = useRoute();
const router = useRouter();
const sourceInstall = computed(() => typeof route.query.source_install === 'string' ? route.query.source_install : '');
const sourceScope = computed(configurationPackScope);
const sourceQuery = useQuery({ queryKey: ['configuration-pack-template-source', sourceInstall, sourceScope], enabled: () => Boolean(sourceInstall.value), retry: false, gcTime: 0,
 queryFn: () => loadConfigurationPackOperation(sourceInstall.value) });
type Scope = 'GLOBAL' | 'ORGANIZATION' | 'FACILITY' | 'DEPARTMENT';
const query = useQuery({ queryKey: ['admin', 'document-templates'], queryFn: loadDocumentTemplates, retry: false, staleTime: 0, gcTime: 0 });
const issue = computed(() => query.error.value ? toClinicalIssue(query.error.value) : null);
const templates = computed(() => query.data.value ?? []);
const busy = ref('');
const notice = ref('');
const batchPreview = ref(false);
const keyword = ref('');
const lifecycleFilter = ref('ACTIVE');
const page = ref(1);
const pageSize = 10;
const selected = ref<DocumentTemplateWire | null>(null);
const showEditor = ref(false);
const deactivateTarget = ref<DocumentTemplateWire | null>(null);
const restoreTarget = ref<DocumentTemplateWire | null>(null);
function latestVersion(item: DocumentTemplateWire) {
  return templates.value.filter(candidate => candidate.template_id === item.template_id)
    .sort((left, right) => right.version_no - left.version_no)[0] ?? item;
}
const mode = ref<'CREATE' | 'VERSION'>('CREATE');
const form = reactive({
  code: '', name: '', documentType: '', scope: 'FACILITY' as Scope, departmentId: '',
  fields: 'chief_complaint,present_illness,assessment,treatment_plan',
  required: 'chief_complaint,present_illness', layout: 'two-column', formConfigKey: '',
});
const activeCount = computed(() => new Set(templates.value.filter((item) => item.lifecycle_status === 'ACTIVE').map((item) => item.template_id)).size);
const publishedCount = computed(() => templates.value.filter((item) => item.version_status === 'PUBLISHED').length);
const draftCount = computed(() => templates.value.filter((item) => item.version_status === 'DRAFT').length);
const retiredCount = computed(() => templates.value.filter((item) => item.version_status === 'RETIRED').length);
const filteredTemplates = computed(() => {
  const needle = keyword.value.trim().toLocaleLowerCase();
  return templates.value.filter((item) => {
    if (sourceInstall.value && !sourceQuery.data.value?.template_version_ids.includes(item.template_version_id)) return false;
    if (lifecycleFilter.value === 'DRAFT' && item.version_status !== 'DRAFT') return false;
    if (lifecycleFilter.value && lifecycleFilter.value !== 'DRAFT' && item.lifecycle_status !== lifecycleFilter.value) return false;
    return !needle || [item.display_name, item.template_code, item.document_type_code]
      .some((value) => value.toLocaleLowerCase().includes(needle));
  });
});
const pagedTemplates = computed(() => filteredTemplates.value.slice((page.value - 1) * pageSize, page.value * pageSize));
const currentTemplate = computed(() => sourceInstall.value
  ? filteredTemplates.value.find(item => item.template_version_id === selected.value?.template_version_id) ?? pagedTemplates.value[0] ?? null
  : templates.value.find(item => item.template_version_id === selected.value?.template_version_id) ?? pagedTemplates.value[0] ?? null);
watch([sourceInstall, sourceScope], () => { selected.value = null; page.value = 1; });
watch([keyword, lifecycleFilter], () => { page.value = 1; selected.value = null; });

function splitFields(value: string) { return [...new Set(value.split(',').map((item) => item.trim()).filter(Boolean))]; }
function formatDate(value: string | null) {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return '—';
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short', hour12: false }).format(parsed);
}
function statusLabel(value: string) {
  return ({ ACTIVE: '有效', INACTIVE: '已停用', DRAFT: '草稿', PUBLISHED: '已发布', RETIRED: '已退役' } as Record<string, string>)[value] ?? '未知状态';
}
function scopeLabel(item: DocumentTemplateWire) {
  if (item.department_id) return `指定科室（记录号 …${item.department_id.slice(-8)}）`;
  if (item.facility_id) return item.facility_id === clinicalContext.facilityId ? '当前院区' : `指定院区（记录号 …${item.facility_id.slice(-8)}）`;
  if (item.organization_id) return item.organization_id === clinicalContext.organizationId ? '当前机构' : `指定机构（记录号 …${item.organization_id.slice(-8)}）`;
  return '全系统通用';
}
function fieldsOf(item: DocumentTemplateWire) {
  return Object.keys((item.section_schema.properties as Record<string, unknown> | undefined) ?? {});
}
function chooseForVersion(item: DocumentTemplateWire) {
  const latest = templates.value.filter((candidate) => candidate.template_id === item.template_id)
    .sort((left, right) => right.version_no - left.version_no)[0] ?? item;
  selected.value = latest;
  mode.value = 'VERSION';
  showEditor.value = true;
  form.fields = fieldsOf(latest).join(',');
  form.required = latest.required_fields.join(',');
  form.layout = String(latest.display_rules.layout ?? 'two-column');
  form.formConfigKey = String(latest.display_rules.form_config_key ?? '');
  notice.value = `已选择 ${latest.display_name}，将在 v${latest.version_no} 上创建新草案。`;
}
function openCreate() {
  selected.value = null; mode.value = 'CREATE'; showEditor.value = true; notice.value = '';
}
function versionPayload() {
  const fields = splitFields(form.fields);
  const required = splitFields(form.required);
  if (!fields.length || required.some((field) => !fields.includes(field))) throw new Error('必填项必须是已定义的字段');
  return {
    section_schema: { type: 'object', properties: Object.fromEntries(fields.map((field) => [field, { type: 'string' }])), additionalProperties: false },
    required_fields: required,
    display_rules: {
      layout: form.layout, field_order: fields,
      ...(form.formConfigKey.trim() ? { form_config_key: form.formConfigKey.trim() } : {}),
    },
  };
}
async function submit() {
  if (busy.value) return;
  busy.value = 'save'; notice.value = '';
  try {
    const payload = versionPayload();
    if (mode.value === 'VERSION' && selected.value) {
      await createDocumentTemplateVersion(selected.value, payload);
      notice.value = '新版本草案已保存；必须由另一名管理员独立发布。';
    } else {
      const input: DocumentTemplateCreateRequestWire = {
        template_code: form.code.trim(), display_name: form.name.trim(),
        document_type_code: form.documentType.trim(), ...payload,
      };
      if (form.scope === 'ORGANIZATION') input.organization_id = clinicalContext.organizationId;
      if (form.scope === 'FACILITY') { input.organization_id = clinicalContext.organizationId; input.facility_id = clinicalContext.facilityId; }
      if (form.scope === 'DEPARTMENT') {
        input.organization_id = clinicalContext.organizationId; input.facility_id = clinicalContext.facilityId;
        input.department_id = form.departmentId.trim();
      }
      await createDocumentTemplate(input);
      notice.value = '模板 v1 草案已创建，当前创建人不能自己发布。';
      form.code = ''; form.name = ''; form.documentType = ''; form.formConfigKey = '';
    }
    showEditor.value = false; await query.refetch();
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = ''; }
}
async function publish(item: DocumentTemplateWire) {
  if (busy.value || item.version_status !== 'DRAFT') return;
  busy.value = item.template_version_id; notice.value = '';
  try {
    await publishDocumentTemplateVersion(item);
    notice.value = `v${item.version_no} 已发布，新建病历开始绑定该版本。`;
    await query.refetch();
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}
async function restoreHistory() {
  if (busy.value || !restoreTarget.value) return;
  const source = restoreTarget.value;
  busy.value = 'restore'; notice.value = '';
  try {
    const latest = latestVersion(source);
    const saved = await createDocumentTemplateVersion(latest, templateRestorePayload(source, latest));
    restoreTarget.value = null;
    selected.value = saved;
    notice.value = `已从 v${source.version_no} 完整恢复为 v${saved.version_no} 草稿。现有发布版本继续生效，须由另一名管理员审批发布。`;
    await query.refetch();
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}
async function deactivate(item: DocumentTemplateWire, confirmed = false) {
  if (!confirmed) { deactivateTarget.value = item; return; }
  if (busy.value || item.lifecycle_status !== 'ACTIVE') return;
  busy.value = item.template_id; notice.value = '';
  try {
    await deactivateDocumentTemplate(item, '模板管理员确认停用');
    notice.value = `${item.display_name}已停用；历史病历保留原版本语义。`;
    deactivateTarget.value = null;
    await query.refetch();
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}
</script>

<template>
  <section data-page-root class="content admin-content vue-native-page template-admin-page">
    <div class="page-head"><div class="page-title"><h1>模板、编号与输出管理</h1><p>统一管理目录、版本、适用范围、依赖、预览和患者身份安全字段</p></div><div class="head-actions"><button class="btn" type="button" @click="batchPreview = !batchPreview">{{ batchPreview ? '关闭批量预览' : '批量预览' }}</button><button class="btn primary" type="button" @click="openCreate">新建模板/规则</button></div></div>
    <div v-if="sourceInstall" class="notice info">当前按安装来源筛选导入版本，审批仍使用原流程。<button class="btn" @click="router.replace({path: route.path, query: {}})">查看全部模板</button><span v-if="sourceQuery.isPending.value">读取来源中…</span><span v-if="sourceQuery.error.value" role="alert">来源读取失败或无权访问，请重试。</span><button v-if="sourceQuery.error.value" class="btn" @click="sourceQuery.refetch()">重试来源读取</button></div>
    <ClinicalPageState v-if="query.isPending.value" kind="loading" message="正在读取模板和不可变版本链" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="query.refetch()" />
    <template v-else>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>
      <section v-if="batchPreview" class="admin-panel admin-form-panel"><header><div><h2>当前页模板批量预览</h2><p>预览使用数据库中的模板版本、字段和适用范围，不改变发布状态。</p></div></header><div class="template-preview-grid"><article v-for="item in pagedTemplates" :key="item.template_version_id" class="paper-mini"><b>文书版式预览</b><span>{{ item.display_name }} · v{{ item.version_no }}</span><hr><small>姓名：[患者姓名]</small><div>{{ fieldsOf(item).slice(0,3).map(documentFieldLabel).join('　') || '结构化文书字段' }}</div></article></div></section>
      <AdminActionDialog v-model:open="showEditor" :title="mode === 'CREATE' ? '创建模板' : `创建 ${selected?.display_name} 新版本`" description="保存将真实写入数据库草案，发布仍需独立审批。若绑定表单配置，系统会把其必填字段固化到此模板版本，后续配置升级不会改写历史版本。" size="large" :busy="Boolean(busy)"><form class="admin-form compact-admin-form" @submit.prevent="submit"><template v-if="mode === 'CREATE'"><label><span>模板编码（系统唯一）</span><input v-model="form.code" autofocus required placeholder="OPD-GENERAL-V1" /></label><label><span>模板名称</span><input v-model="form.name" required placeholder="通用门诊病历" /></label><label><span>文书类型</span><input v-model="form.documentType" list="document-type-options" required placeholder="选择或填写文书类型编码" /><datalist id="document-type-options"><option value="OPD_NOTE">门诊病历</option><option value="IPD_ADMISSION_NOTE">住院入院记录</option><option value="IPD_COURSE_NOTE">住院病程记录</option><option value="DISCHARGE_SUMMARY">出院记录</option><option value="NURSING_NOTE">护理记录</option><option value="SURGERY_NOTE">手术记录</option></datalist></label><label><span>适用范围</span><select v-model="form.scope"><option value="GLOBAL">全系统通用</option><option value="ORGANIZATION">当前机构</option><option value="FACILITY">当前院区</option><option value="DEPARTMENT">指定科室</option></select></label></template><label><span>结构化字段编码（逗号分隔）</span><textarea v-model="form.fields" rows="3" required placeholder="例：chief_complaint,present_illness；页面预览会显示为主诉、现病史" /></label><label><span>必填字段编码（必须已在上方定义）</span><textarea v-model="form.required" rows="3" /></label><label><span>已发布表单配置键（可选）</span><input v-model="form.formConfigKey" placeholder="例：form-outpatient-record-v1" /><small>仅可绑定已发布配置；该配置的必填字段必须已在本模板字段中定义。</small></label><label><span>页面布局</span><select v-model="form.layout"><option value="single-column">单列</option><option value="two-column">双列</option><option value="timeline">时间轴</option><option value="specialty">专科分区</option></select></label><button class="button primary" :disabled="Boolean(busy)">{{ mode === 'CREATE' ? '保存 v1 草案' : '保存新版本草案' }}</button></form></AdminActionDialog>
      <div class="grid admin-list-detail"><section class="card"><div class="toolbar"><input v-model="keyword" class="search" placeholder="模板编码、名称或文书类型" /><select v-model="lifecycleFilter" class="select" aria-label="模板状态"><option value="">全部模板状态</option><option value="ACTIVE">有效</option><option value="DRAFT">草稿</option><option value="INACTIVE">已停用</option></select></div><div class="admin-table-wrap"><table class="table"><thead><tr><th>模板名称 / 编码</th><th>文书类型</th><th>适用范围</th><th>版本</th><th>状态</th></tr></thead><tbody><tr v-for="item in pagedTemplates" :key="item.template_version_id" :class="{ selected: currentTemplate?.template_version_id === item.template_version_id }" @click="selected = item"><td><b>{{ item.display_name }}</b><br><span class="meta">技术编码：{{ item.template_code }}</span></td><td>{{ documentTypeLabel(item.document_type_code) }}<br><span class="meta">{{ item.document_type_code }}</span></td><td>{{ scopeLabel(item) }}</td><td>v{{ item.version_no }}</td><td><span class="status" :class="item.version_status === 'PUBLISHED' ? 'green' : item.version_status === 'DRAFT' ? 'amber' : 'gray'">{{ statusLabel(item.version_status) }}</span></td></tr><tr v-if="!filteredTemplates.length"><td colspan="5" class="mpi-empty">暂无匹配模板。</td></tr></tbody></table><AdminDataPager v-model:page="page" :page-size="pageSize" :total="filteredTemplates.length" /></div></section><aside v-if="currentTemplate" class="card"><div class="card-head">{{ currentTemplate.display_name }} v{{ currentTemplate.version_no }}</div><div class="card-body"><div class="paper-mini"><b>文书版式预览</b><span>{{ currentTemplate.display_name }}</span><hr><small>姓名：[患者姓名]</small><div>{{ fieldsOf(currentTemplate).slice(0,4).map(documentFieldLabel).join('　') || '结构化文书字段' }}</div></div><div class="folder-row">模板编码<span>{{ currentTemplate.template_code }}</span></div><div class="folder-row">文书类型<span>{{ documentTypeLabel(currentTemplate.document_type_code) }}</span></div><div class="folder-row">适用范围<span>{{ scopeLabel(currentTemplate) }}</span></div><div class="folder-row">结构化字段<span>{{ fieldsOf(currentTemplate).length }} 个</span></div><div class="folder-row">必填字段<span>{{ currentTemplate.required_fields.length }} 个</span></div><div class="notice hard"><div class="notice-title">患者身份字段必须最小化输出</div>历史病历继续绑定原模板版本，发布后只影响新建病历。</div><div class="admin-actions vertical"><button class="btn" :disabled="Boolean(busy) || currentTemplate.lifecycle_status !== 'ACTIVE'" @click="chooseForVersion(currentTemplate)">打开模板设计与修正</button><button v-if="currentTemplate.version_no < latestVersion(currentTemplate).version_no" class="btn" :disabled="Boolean(busy) || currentTemplate.lifecycle_status !== 'ACTIVE'" @click="restoreTarget = currentTemplate; notice = ''">恢复此历史版本为新草稿</button><button class="btn primary" :disabled="Boolean(busy) || currentTemplate.version_status !== 'DRAFT' || currentTemplate.created_by === clinicalContext.userId" @click="publish(currentTemplate)">{{ currentTemplate.created_by === clinicalContext.userId ? '创建人不能审批' : '审批发布' }}</button><button class="btn" :disabled="Boolean(busy) || currentTemplate.lifecycle_status !== 'ACTIVE'" @click="deactivate(currentTemplate)">停用模板</button></div></div></aside></div>
    </template>
    <AdminActionDialog :open="Boolean(restoreTarget)" title="恢复历史模板为新草稿" description="完整复制所选历史版本的字段、校验与版式。当前发布版本继续生效，新草稿仍须独立审批。" :busy="Boolean(busy)" @update:open="!$event && (restoreTarget = null)">
      <div v-if="restoreTarget" class="admin-impact-grid"><div><span>模板</span><b>{{ restoreTarget.display_name }}</b></div><div><span>恢复来源</span><b>v{{ restoreTarget.version_no }}</b></div><div><span>当前最新版本</span><b>v{{ latestVersion(restoreTarget).version_no }}</b></div></div>
      <p v-if="notice" role="status">{{ notice }}</p>
      <template #footer="{ close }"><button class="btn" :disabled="Boolean(busy)" @click="close">取消</button><button class="btn primary" :disabled="Boolean(busy)" @click="restoreHistory">{{ busy === 'restore' ? '正在创建…' : '确认创建恢复草稿' }}</button></template>
    </AdminActionDialog>
    <AdminConfirmDialog :open="Boolean(deactivateTarget)" :title="`停用模板 ${deactivateTarget?.display_name ?? ''}`" description="停用后新建病历不再选用该模板，历史病历继续绑定原版本。" :busy="Boolean(busy)" @update:open="!$event && (deactivateTarget = null)" @confirm="deactivateTarget && deactivate(deactivateTarget, true)"><div v-if="deactivateTarget" class="admin-impact-grid"><div><span>模板编码</span><b>{{ deactivateTarget.template_code }}</b></div><div><span>文书类型</span><b>{{ documentTypeLabel(deactivateTarget.document_type_code) }}</b></div><div><span>当前版本</span><b>v{{ deactivateTarget.version_no }}</b></div><div><span>当前状态</span><b>{{ statusLabel(deactivateTarget.lifecycle_status) }}</b></div></div></AdminConfirmDialog>
  </section>
</template>
