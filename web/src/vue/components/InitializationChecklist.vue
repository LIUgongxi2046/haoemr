<script setup lang="ts">
import { computed } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import { configurationPackScope, loadConfigurationPackOperations } from '../../api/configuration-packs';
import { clinicalContext, loadOrganizationUnits, loadDocumentTemplates } from '../../clinical-api';
import { hasActiveDepartment, applicableTemplateReadiness } from '../initialization-state';
const scope = computed(configurationPackScope);
const organizations = useQuery({ queryKey: ['initialization-organizations', scope], queryFn: loadOrganizationUnits, retry: false, gcTime: 0 });
const operations = useQuery({ queryKey: ['configuration-pack-operations', scope], queryFn: loadConfigurationPackOperations, retry: false, gcTime: 0 });
const templates = useQuery({ queryKey: ['initialization-templates', scope], queryFn: loadDocumentTemplates, retry: false, gcTime: 0 });
const hasDepartment = computed(() => hasActiveDepartment(organizations.data.value ?? [], clinicalContext.organizationId, clinicalContext.facilityId));
const installed = computed(() => operations.data.value?.some(x => x.status === 'SUCCEEDED'));
const templateState = computed(() => applicableTemplateReadiness(templates.data.value ?? [], clinicalContext.organizationId, clinicalContext.facilityId));
function checkedAt(value: number) { return value ? `本次读取：${new Date(value).toLocaleString('zh-CN', { hour12: false })}` : '尚无成功读取记录'; }
async function refresh() { await Promise.allSettled([organizations.refetch(), operations.refetch(), templates.refetch()]); }
</script>
<template>
  <details class="card" :open="!hasDepartment || !installed || !templateState.published">
    <summary class="card-head">初始化检查</summary>
    <div class="card-body">
      <p>当前机构 …{{ clinicalContext.organizationId.slice(-8) }} / 院区 …{{ clinicalContext.facilityId.slice(-8) }}。以下为配置检查，正式临床可用性须另行验收。</p>
      <div class="folder-row"><div><b>机构、院区与科室</b><p>{{ organizations.isPending.value ? '读取中…' : organizations.error.value ? '无法核对组织状态，请在原组织页面重试' : hasDepartment ? '当前机构和院区下已存在有效科室' : '请在原组织树补齐院区与科室' }}</p><small>{{ checkedAt(organizations.dataUpdatedAt.value) }}</small></div><RouterLink class="btn" to="/admin-org">组织机构</RouterLink></div>
      <div class="folder-row"><div><b>模块配置</b><p>{{ operations.isPending.value ? '读取中…' : operations.error.value ? '安装状态暂不可用，请查询安装记录' : installed ? '已导入配置；导入草稿仍须独立审批' : '尚未导入配置包' }}</p><small>{{ checkedAt(operations.dataUpdatedAt.value) }}</small></div><RouterLink class="btn" to="/admin-parameters?view=initialization">模块初始化</RouterLink></div>
      <div class="folder-row"><div><b>当前范围的文书模板</b><p>{{ templates.isPending.value ? '读取中…' : templates.error.value ? '无法核对模板状态，请重试' : `已发布 ${templateState.published} 个模板，待审批 ${templateState.drafts} 个草稿版本` }}</p><small>{{ checkedAt(templates.dataUpdatedAt.value) }}</small></div><RouterLink class="btn" to="/admin-templates">模板审批</RouterLink></div>
      <div class="folder-row"><div><b>认证、签名、存储与外部业务</b><p>待专项验证；本检查不使用服务存活或安装成功代替真实业务验证。</p></div><RouterLink class="btn" to="/admin-auth">认证安全</RouterLink></div>
      <button class="btn" :disabled="organizations.isFetching.value || operations.isFetching.value || templates.isFetching.value" @click="refresh">重新核对配置</button>
      <div class="notice info">安装完成不代表临床就绪。认证、签名、存储与外部业务须在原入口分别验证。</div>
      <div class="head-actions"><RouterLink class="btn" to="/admin-auth">认证安全</RouterLink><RouterLink class="btn" to="/integration">接口集成</RouterLink><RouterLink class="btn" to="/admin-templates">模板输出</RouterLink><RouterLink class="btn" to="/admin-jobs?view=installations">安装记录</RouterLink></div>
    </div>
  </details>
</template>
<style scoped>
.folder-row p{margin:5px 0;color:var(--muted);font-size:11px}.head-actions{flex-wrap:wrap}@media(max-width:720px){.folder-row{flex-wrap:wrap;gap:8px}.btn{min-height:44px!important}}
</style>
