<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref, watch } from 'vue';
import type { SourceFieldMappingWire } from '../../generated/contracts';
import { deactivateSourceFieldMapping, issueGovernanceLease, listSourceFieldMappings, listSourceSystems, registerSourceFieldMapping } from '../../api/governance';
import AdminActionDialog from '../components/AdminActionDialog.vue';
import AdminConfirmDialog from '../components/AdminConfirmDialog.vue';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';

const leaseQuery = useQuery({ queryKey: ['governance', 'integration', 'lease'], queryFn: () => issueGovernanceLease('INTEGRATION_MAPPING'), retry: false, staleTime: 5 * 60_000, gcTime: 0 });
const sourcesQuery = useQuery({ queryKey: ['governance', 'integration', 'sources'], queryFn: () => listSourceSystems(leaseQuery.data.value!), enabled: () => Boolean(leaseQuery.data.value), retry: false });
const sources = computed(() => sourcesQuery.data.value ?? []);
const selectedSourceId = ref('');
watch(sources, (items) => { if (!selectedSourceId.value && items[0]) selectedSourceId.value = items[0].source_system_id; }, { immediate: true });
const mappingsQuery = useQuery({ queryKey: ['governance', 'integration', 'mappings', selectedSourceId], queryFn: () => listSourceFieldMappings(leaseQuery.data.value!, selectedSourceId.value), enabled: () => Boolean(leaseQuery.data.value && selectedSourceId.value), retry: false });
const issue = computed(() => (leaseQuery.error.value ?? sourcesQuery.error.value ?? mappingsQuery.error.value) ? toClinicalIssue(leaseQuery.error.value ?? sourcesQuery.error.value ?? mappingsQuery.error.value) : null);
const mappings = computed(() => mappingsQuery.data.value ?? []);
const form = reactive({ sourceField: '', targetEntity: '', targetField: '' });
const busy = ref(false);
const notice = ref('');
const editorOpen = ref(false);
const deleteOpen = ref(false);
const selectedMapping = ref<SourceFieldMappingWire | null>(null);

function openCreate() {
  form.sourceField = ''; form.targetEntity = ''; form.targetField = '';
  editorOpen.value = true;
}

function requestDeactivate(mapping: SourceFieldMappingWire) {
  selectedMapping.value = mapping;
  deleteOpen.value = true;
}

async function register() {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || !selectedSourceId.value || !form.sourceField.trim() || !form.targetEntity.trim() || !form.targetField.trim()) return;
  busy.value = true; notice.value = '';
  try {
    await registerSourceFieldMapping(lease, { source_system_id: selectedSourceId.value, source_field: form.sourceField.trim(), target_entity: form.targetEntity.trim(), target_field: form.targetField.trim(), registered_at: new Date().toISOString() });
    notice.value = '源字段映射已登记并立即参与后续历史迁移转换。';
    editorOpen.value = false;
    await mappingsQuery.refetch();
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = false; }
}

async function deactivate() {
  const lease = leaseQuery.data.value;
  const mapping = selectedMapping.value;
  if (!lease || !mapping || busy.value) return;
  busy.value = true; notice.value = '';
  try {
    await deactivateSourceFieldMapping(lease, mapping);
    notice.value = `${mapping.source_field} → ${mapping.target_entity}.${mapping.target_field} 已停用；历史转换证据继续保留。`;
    deleteOpen.value = false;
    await mappingsQuery.refetch();
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = false; }
}
</script>

<template>
  <section data-page-root class="content admin-content vue-native-page">
    <div class="page-heading admin-heading"><div><p class="eyebrow">数据中心 / 集成平台</p><h1>字段映射与历史迁移转换</h1><p>内部临床对象不随厂商字段分叉；映射由受控历史迁移转换器消费，不把模拟消息台误表述为临床入站链路。</p></div><div class="toolbar-actions"><RouterLink class="button secondary" to="/integration-connectors">连接器目录</RouterLink><button class="button primary" :disabled="!selectedSourceId" @click="openCreate">新建字段映射</button></div></div>
    <ClinicalPageState v-if="leaseQuery.isPending.value || sourcesQuery.isPending.value" kind="loading" message="正在读取源系统与字段映射" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="sourcesQuery.refetch()" />
    <template v-else>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>
      <div class="grid mapping-layout integration-mapping-layout">
        <aside class="card"><div class="card-head">源系统</div><div class="card-body source-system-list"><button v-for="source in sources" :key="source.source_system_id" class="study-item" :class="{ active: selectedSourceId === source.source_system_id }" @click="selectedSourceId = source.source_system_id"><b>{{ source.display_name }}</b><span>{{ source.source_code }} · {{ source.system_type }}</span><em class="status" :class="source.connection_status === 'ACTIVE' ? 'green' : 'amber'">{{ source.connection_status }}</em></button></div></aside>
        <section class="card"><div class="card-head">字段映射 <span class="sub">{{ mappings.length }} 条</span></div><div v-if="!mappings.length" class="card-body admin-empty">该源暂无映射，请新建后再运行样本模拟。</div><table v-else class="table"><thead><tr><th>源字段</th><th>目标实体</th><th>目标字段</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="mapping in mappings" :key="mapping.mapping_id"><td><b>{{ mapping.source_field }}</b></td><td>{{ mapping.target_entity }}</td><td>{{ mapping.target_field }}</td><td><span class="status" :class="mapping.status === 'ACTIVE' ? 'green' : 'gray'">{{ mapping.status }}</span></td><td><button class="btn sm danger" :disabled="mapping.status !== 'ACTIVE' || busy" @click="requestDeactivate(mapping)">停用</button></td></tr></tbody></table><div class="card-body"><div class="notice hard"><div class="notice-title">发布阻断规则</div>未映射状态和值域冲突会阻断发布，不能把更正报告误作最终报告。</div></div></section>
        <aside class="card"><div class="card-head">转换边界</div><div class="card-body"><div v-for="row in [['适用范围','受控历史迁移批次'],['患者主数据','EMR MPI 为权威'],['申请主数据','EMR ServiceRequest 为权威'],['报告正文','LIS 为权威，EMR 存版本引用'],['重复识别','MSH-10 + OBR-3 + 版本'],['失败策略','隔离，不覆盖当前有效报告']]" :key="row[0]" class="folder-row"><span>{{ row[0] }}</span><strong>{{ row[1] }}</strong></div><RouterLink class="btn primary full-route-button" to="/integration-messages">查看受控仿真 Trace</RouterLink></div></aside>
      </div>
    </template>

    <AdminActionDialog v-model:open="editorOpen" title="新建字段映射" description="映射登记后会参与历史迁移转换；身份字段不可覆盖修改，如需替换请先停用旧映射。" :busy="busy">
      <form class="admin-form" @submit.prevent="register"><label><span>源字段</span><input v-model="form.sourceField" required placeholder="例：OBR-25" /></label><label><span>目标实体</span><input v-model="form.targetEntity" required placeholder="例：DiagnosticReport" /></label><label><span>目标字段</span><input v-model="form.targetField" required placeholder="例：status" /></label></form>
      <template #footer="{ close }"><button class="button secondary" :disabled="busy" @click="close">取消</button><button class="button primary" :disabled="busy" @click="register">{{ busy ? '登记中…' : '登记映射' }}</button></template>
    </AdminActionDialog>
    <AdminConfirmDialog v-model:open="deleteOpen" title="停用字段映射" description="停用后新的集成消息与迁移批次不再使用该映射，已生成的临床事实和审计证据不会被删除。" confirm-label="确认停用" :busy="busy" @confirm="deactivate" />
  </section>
</template>

<style scoped>
.integration-mapping-layout { grid-template-columns: 250px minmax(0, 1fr) 290px; }
.source-system-list { display: grid; gap: 8px; }
.study-item { position: relative; width: 100%; padding-right: 80px; }
.study-item em { position: absolute; top: 50%; right: 10px; transform: translateY(-50%); }
.full-route-button { display: block; width: 100%; margin-top: 14px; text-align: center; text-decoration: none; }
@media (max-width: 1050px) { .integration-mapping-layout { grid-template-columns: minmax(0, 1fr); } }
@media (max-width: 600px) { .integration-mapping-layout > * { min-width: 0; } .integration-mapping-layout > section { overflow-x: auto; } .integration-mapping-layout table { min-width: 520px; } }
</style>
