<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref, watch } from 'vue';
import {
  createSpecialtyExecutionCase, issueSpecialtyExecutionLease, listSpecialtyExecutionCases,
  transitionSpecialtyExecutionCase, updateSpecialtyExecutionCase,
} from '../../api/execution-center';
import { issueMedicalAgentRunLease, listMedicalAgentRuns } from '../../api/medical-agents';
import type { SpecialtyExecutionCaseWire } from '../../generated/contracts';
import type { SpecialtyExecutionWorkbenchDefinition } from '../specialty-execution-workbenches';
import { toClinicalIssue } from '../clinical-error';
import BusinessActionDialog from './BusinessActionDialog.vue';
import ClinicalPageState from './ClinicalPageState.vue';
import ExecutionPatientContextBar from './ExecutionPatientContextBar.vue';

const props = defineProps<{ definition: SpecialtyExecutionWorkbenchDefinition }>();
type Tab = 'CASES' | 'DETAIL' | 'TIMELINE' | 'AGENT' | 'EVIDENCE';
type Action = 'MARK_READY' | 'START' | 'REQUEST_REVIEW' | 'COMPLETE' | 'CANCEL';
const activeTab = ref<Tab>('CASES');
const selectedId = ref('');
const editorOpen = ref(false);
const transitionOpen = ref(false);
const editing = ref<SpecialtyExecutionCaseWire | null>(null);
const pendingAction = ref<Action>('MARK_READY');
const transitionNote = ref('');
const busy = ref(false);
const notice = ref('');
const form = reactive({ title: '', priority: 'ROUTINE' as 'ROUTINE' | 'URGENT' | 'EMERGENCY', plannedAt: '', values: {} as Record<string, string | number | boolean> });

const leaseQuery = useQuery({ queryKey: computed(() => ['specialty-execution-lease', props.definition.domain]), queryFn: issueSpecialtyExecutionLease, retry: false, staleTime: 5 * 60_000, gcTime: 0 });
const casesQuery = useQuery({ queryKey: computed(() => ['specialty-execution-cases', props.definition.domain]), queryFn: () => listSpecialtyExecutionCases(leaseQuery.data.value!, props.definition.domain), enabled: () => Boolean(leaseQuery.data.value), retry: false, gcTime: 0 });
const cases = computed(() => casesQuery.data.value ?? []);
const agentQuery = useQuery({ queryKey: computed(() => ['specialty-execution-agent-runs', props.definition.domain]), queryFn: async () => listMedicalAgentRuns(await issueMedicalAgentRunLease(cases.value[0]?.patient_id ?? '', cases.value[0]?.encounter_id ?? ''), cases.value[0]?.patient_id ?? '', cases.value[0]?.encounter_id ?? ''), enabled: () => activeTab.value === 'AGENT' && cases.value.length > 0, retry: false });
const selected = computed(() => cases.value.find((item) => item.specialty_execution_case_id === selectedId.value) ?? cases.value[0] ?? null);
const issue = computed(() => { const error = leaseQuery.error.value ?? casesQuery.error.value; return error ? toClinicalIssue(error) : null; });
const pendingCount = computed(() => cases.value.filter((item) => !['COMPLETED', 'CANCELLED'].includes(item.status)).length);
const reviewCount = computed(() => cases.value.filter((item) => item.status === 'PENDING_REVIEW').length);
const completedCount = computed(() => cases.value.filter((item) => item.status === 'COMPLETED').length);

watch(cases, (items) => { if (!items.some((item) => item.specialty_execution_case_id === selectedId.value)) selectedId.value = items[0]?.specialty_execution_case_id ?? ''; }, { immediate: true });
watch(() => props.definition.domain, () => { selectedId.value = ''; activeTab.value = 'CASES'; notice.value = ''; });

function openCreate() {
  editing.value = null; form.title = ''; form.priority = 'ROUTINE'; form.plannedAt = '';
  form.values = Object.fromEntries(props.definition.fields.map((field) => [field.key, field.type === 'boolean' ? false : '']));
  editorOpen.value = true;
}
function openEdit(item: SpecialtyExecutionCaseWire) {
  editing.value = item; form.title = item.title; form.priority = item.priority;
  form.plannedAt = item.planned_at ? item.planned_at.slice(0, 16) : '';
  form.values = Object.fromEntries(props.definition.fields.map((field) => [field.key, (item.payload[field.key] as string | number | boolean | undefined) ?? (field.type === 'boolean' ? false : '')]));
  editorOpen.value = true;
}
function payload() { return Object.fromEntries(Object.entries(form.values).filter(([, value]) => value !== '')); }
async function save() {
  const lease = leaseQuery.data.value; if (!lease || busy.value || form.title.trim().length < 2) return;
  busy.value = true; notice.value = '';
  try {
    const input = { title: form.title.trim(), priority: form.priority, planned_at: form.plannedAt ? new Date(form.plannedAt).toISOString() : null, payload: payload() };
    const result = editing.value
      ? await updateSpecialtyExecutionCase(lease, editing.value, input)
      : await createSpecialtyExecutionCase(lease, { domain: props.definition.domain, ...input });
    editorOpen.value = false; selectedId.value = result.specialty_execution_case_id;
    notice.value = editing.value ? '草稿已更新并写入不可变事件时间轴。' : `${props.definition.entityLabel}已创建，进入执行前必须完成双标识与专业必填项。`;
    await casesQuery.refetch();
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = false; }
}
function requestTransition(item: SpecialtyExecutionCaseWire, action: Action) {
  selectedId.value = item.specialty_execution_case_id; pendingAction.value = action;
  transitionNote.value = action === 'CANCEL' ? '' : `${actionLabel(action)}：已核对当前患者、就诊和业务证据`;
  transitionOpen.value = true;
}
async function applyTransition() {
  const lease = leaseQuery.data.value; const item = selected.value;
  if (!lease || !item || busy.value || transitionNote.value.trim().length < 2) return;
  busy.value = true; notice.value = '';
  try {
    await transitionSpecialtyExecutionCase(lease, item, pendingAction.value, transitionNote.value.trim());
    transitionOpen.value = false; notice.value = `${item.business_number} 已${actionLabel(pendingAction.value)}，事件与操作者已留痕。`;
    await casesQuery.refetch();
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = false; }
}
function nextAction(item: SpecialtyExecutionCaseWire): Action | null { return ({ DRAFT: 'MARK_READY', READY: 'START', IN_PROGRESS: 'REQUEST_REVIEW', PENDING_REVIEW: 'COMPLETE' } as Record<string, Action>)[item.status] ?? null; }
function actionLabel(action: Action) { return ({ MARK_READY: '标记就绪', START: '开始执行', REQUEST_REVIEW: '提交复核', COMPLETE: '复核完成', CANCEL: '取消' } as Record<Action, string>)[action]; }
function statusLabel(status: string) { return ({ DRAFT: '草稿', READY: '待执行', IN_PROGRESS: '执行中', PENDING_REVIEW: '待复核', COMPLETED: '已完成', CANCELLED: '已取消' } as Record<string, string>)[status] ?? status; }
function priorityLabel(priority: string) { return ({ ROUTINE: '常规', URGENT: '加急', EMERGENCY: '急诊' } as Record<string, string>)[priority] ?? priority; }
function displayValue(value: unknown) { if (typeof value === 'boolean') return value ? '是' : '否'; return value == null || value === '' ? '—' : String(value); }
function updateTextField(key: string, event: Event) {
  form.values[key] = (event.target as HTMLTextAreaElement).value;
}
function updateBooleanField(key: string, event: Event) {
  form.values[key] = (event.target as HTMLInputElement).checked;
}
function fieldId(key: string) { return `specialty-execution-field-${props.definition.domain.toLowerCase()}-${key}`; }
</script>

<template>
  <section data-page-root class="content vue-native-page production-execution-page">
    <div class="page-heading"><div><p class="eyebrow">诊疗执行中心 · 生产业务状态机</p><h1>{{ definition.title }}</h1><p>{{ definition.subtitle }}</p></div><button class="btn primary" type="button" @click="openCreate">新建{{ definition.entityLabel }}</button></div>
    <ExecutionPatientContextBar />
    <div class="production-safety"><b>操作提示</b><span>系统会检查患者、必填信息和操作权限，并保留操作记录。</span><span>AI医助仅提供建议</span></div>
    <ClinicalPageState v-if="leaseQuery.isPending.value || casesQuery.isPending.value" kind="loading" message="正在校验患者上下文并加载专业执行病例" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="casesQuery.refetch()" />
    <template v-else>
      <section class="execution-metrics"><article><span>病例总数</span><strong>{{ cases.length }}</strong><small>当前患者/就诊</small></article><article><span>执行中待办</span><strong>{{ pendingCount }}</strong><small>不含取消和完成</small></article><article><span>待独立复核</span><strong>{{ reviewCount }}</strong><small>病理/麻醉强制职责分离</small></article><article><span>已完成</span><strong>{{ completedCount }}</strong><small>保留不可变时间轴</small></article></section>
      <p v-if="notice" class="inline-notice" role="status">{{ notice }}</p>
      <nav class="execution-tabs" aria-label="专业执行深层功能"><button v-for="tab in ([['CASES','业务列表'],['DETAIL','执行详情'],['TIMELINE','事件时间轴'],['AGENT','医助建议'],['EVIDENCE','证据审计']] as const)" :key="tab[0]" :class="{ active: activeTab === tab[0] }" @click="activeTab = tab[0]">{{ tab[1] }}</button></nav>

      <section v-if="activeTab === 'CASES'" class="execution-panel"><header><div><h2>{{ definition.entityLabel }}列表</h2><p>编辑仅允许草稿；删除采用“取消”留痕，不物理删除医疗生产记录。</p></div><button class="btn primary" @click="openCreate">新建</button></header><div class="case-table-wrap"><table><thead><tr><th>业务号</th><th>标题</th><th>优先级</th><th>计划时间</th><th>状态</th><th>版本</th><th>操作</th></tr></thead><tbody><tr v-for="item in cases" :key="item.specialty_execution_case_id" :class="{ selected: item.specialty_execution_case_id === selected?.specialty_execution_case_id }" @click="selectedId = item.specialty_execution_case_id"><td><code>{{ item.business_number }}</code></td><td><strong>{{ item.title }}</strong></td><td>{{ priorityLabel(item.priority) }}</td><td>{{ item.planned_at ? new Date(item.planned_at).toLocaleString('zh-CN',{hour12:false}) : '未排程' }}</td><td><span class="state" :class="item.status.toLowerCase()">{{ statusLabel(item.status) }}</span></td><td>v{{ item.row_version }}</td><td><div class="row-actions"><button v-if="item.status === 'DRAFT'" class="btn sm" @click.stop="openEdit(item)">编辑</button><button v-if="nextAction(item)" class="btn sm primary" @click.stop="requestTransition(item,nextAction(item)!)">{{ actionLabel(nextAction(item)!) }}</button><button v-if="!['COMPLETED','CANCELLED'].includes(item.status)" class="btn sm danger" @click.stop="requestTransition(item,'CANCEL')">取消</button><button class="btn sm" @click.stop="selectedId=item.specialty_execution_case_id;activeTab='DETAIL'">详情</button></div></td></tr><tr v-if="!cases.length"><td colspan="7" class="empty">当前患者尚无{{ definition.entityLabel }}；请通过弹窗新建真实业务草稿。</td></tr></tbody></table></div></section>

      <section v-else-if="activeTab === 'DETAIL'" class="execution-panel detail-panel"><header><div><h2>执行详情</h2><p v-if="selected">{{ selected.business_number }} · {{ statusLabel(selected.status) }}</p></div><button v-if="selected?.status === 'DRAFT'" class="btn" @click="openEdit(selected)">编辑草稿</button></header><div v-if="selected" class="detail-grid"><article v-for="field in definition.fields" :key="field.key"><span>{{ field.label }}</span><strong>{{ displayValue(selected.payload[field.key]) }}</strong><small>{{ field.help }}</small></article></div><div v-else class="empty">请先创建或选择病例</div></section>

      <section v-else-if="activeTab === 'TIMELINE'" class="execution-panel"><header><div><h2>不可变事件时间轴</h2><p>所有创建、编辑、状态迁移均保留操作者、时间和当时快照。</p></div></header><ol v-if="selected?.events.length" class="timeline"><li v-for="event in selected.events" :key="event.specialty_execution_event_id"><i></i><div><strong>{{ actionLabel(({READY:'MARK_READY',STARTED:'START',REVIEW_REQUESTED:'REQUEST_REVIEW',COMPLETED:'COMPLETE',CANCELLED:'CANCEL'} as Record<string,Action>)[event.event_type] ?? 'MARK_READY') }}</strong><span>{{ event.from_status ?? '无' }} → {{ event.to_status }}</span><p>{{ event.note }}</p><small>{{ new Date(event.occurred_at).toLocaleString('zh-CN',{hour12:false}) }} · 操作者 {{ event.actor_user_id }}</small></div></li></ol><div v-else class="empty">当前病例暂无事件</div></section>

      <section v-else-if="activeTab === 'AGENT'" class="execution-panel agent-panel"><header><div><h2>AI医助建议</h2><p>{{ definition.agentObjective }}</p></div><RouterLink class="btn primary" :to="{ path:'/ai-assistant', query:{ task_id:selected?.specialty_execution_case_id, objective:definition.agentObjective } }">打开 AI医助 EVA 分析</RouterLink></header><div class="agent-guard"><b>请人工确认</b><span>AI医助会根据当前患者和就诊信息提供建议，不能替代复核或关闭告警。</span></div><ul v-if="agentQuery.data.value?.length" class="agent-runs"><li v-for="run in agentQuery.data.value" :key="run.run_id"><code>{{ run.run_id }}</code><strong>{{ run.objective }}</strong><span>{{ run.state }}</span></li></ul><div v-else class="empty">当前就诊暂无 AI医助任务；可打开 AI医助 EVA 发起分析。</div></section>

      <section v-else class="execution-panel"><header><div><h2>证据与审计</h2><p>面向高层级追溯页面展示业务身份、版本与证据完整性。</p></div></header><dl v-if="selected" class="evidence-grid"><div><dt>业务号</dt><dd>{{ selected.business_number }}</dd></div><div><dt>病例 ID</dt><dd>{{ selected.specialty_execution_case_id }}</dd></div><div><dt>患者 / 就诊</dt><dd>{{ selected.patient_id }} / {{ selected.encounter_id }}</dd></div><div><dt>创建人 / 最近操作者</dt><dd>{{ selected.created_by }} / {{ selected.last_actor_user_id }}</dd></div><div><dt>当前版本</dt><dd>v{{ selected.row_version }}</dd></div><div><dt>不可变事件数</dt><dd>{{ selected.events.length }}</dd></div><div><dt>创建 / 更新</dt><dd>{{ new Date(selected.created_at).toLocaleString('zh-CN',{hour12:false}) }} / {{ new Date(selected.updated_at).toLocaleString('zh-CN',{hour12:false}) }}</dd></div><div><dt>Agent 自动写入</dt><dd>禁止</dd></div></dl><div v-else class="empty">请先选择病例</div></section>
    </template>

    <BusinessActionDialog :open="editorOpen" :title="editing ? `编辑${definition.entityLabel}` : `新建${definition.entityLabel}`" eyebrow="诊疗执行" description="保存为草稿；只有专业必填项和双标识核对完整后才能进入待执行。" confirm-label="保存草稿" :busy="busy" :confirm-disabled="form.title.trim().length < 2" width="wide" @confirm="save" @cancel="editorOpen=false"><div class="dialog-grid"><label class="full" for="specialty-execution-title"><span>业务标题</span><input id="specialty-execution-title" aria-label="业务标题" v-model="form.title" maxlength="256" required /></label><label for="specialty-execution-priority"><span>优先级</span><select id="specialty-execution-priority" aria-label="优先级" v-model="form.priority"><option value="ROUTINE">常规</option><option value="URGENT">加急</option><option value="EMERGENCY">急诊</option></select></label><label for="specialty-execution-planned-at"><span>计划执行时间</span><input id="specialty-execution-planned-at" aria-label="计划执行时间" v-model="form.plannedAt" type="datetime-local" /></label><label v-for="field in definition.fields" :key="field.key" :for="fieldId(field.key)" :class="{ full: field.type === 'textarea' }"><span>{{ field.label }}</span><textarea v-if="field.type === 'textarea'" :id="fieldId(field.key)" :aria-label="field.label" :value="String(form.values[field.key] ?? '')" :placeholder="field.placeholder" @input="updateTextField(field.key,$event)" /><input v-else-if="field.type === 'boolean'" :id="fieldId(field.key)" :aria-label="field.label" type="checkbox" :checked="Boolean(form.values[field.key])" @change="updateBooleanField(field.key,$event)" /><input v-else :id="fieldId(field.key)" :aria-label="field.label" v-model="form.values[field.key]" :type="field.type ?? 'text'" :placeholder="field.placeholder" /><small>{{ field.help }}</small></label></div></BusinessActionDialog>
    <BusinessActionDialog :open="transitionOpen" :title="pendingAction === 'CANCEL' ? `取消${definition.entityLabel}` : actionLabel(pendingAction)" eyebrow="状态迁移" :description="pendingAction === 'COMPLETE' && ['PATHOLOGY','ANESTHESIA'].includes(definition.domain) ? '终审人员不得与创建人为同一账号。' : '操作将写入不可变事件时间轴并增加版本号。'" :confirm-label="actionLabel(pendingAction)" :busy="busy" :danger="pendingAction === 'CANCEL'" :confirm-disabled="transitionNote.trim().length < 2" @confirm="applyTransition" @cancel="transitionOpen=false"><label for="specialty-execution-transition-note"><span>操作说明 / 取消原因</span><textarea id="specialty-execution-transition-note" aria-label="操作说明 / 取消原因" v-model="transitionNote" maxlength="1000" required /></label><p v-if="pendingAction === 'MARK_READY'" class="dialog-warning">进入待执行会校验双标识和全部专业生产必填项，缺项将被服务端阻断。</p></BusinessActionDialog>
  </section>
</template>

<style scoped>
.production-execution-page{display:grid;gap:16px;min-width:0}.page-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:18px}.page-heading h1{margin:3px 0 7px}.page-heading p{margin:0;color:#68798a}.eyebrow{color:#1769aa!important;font-size:11px;font-weight:750;letter-spacing:.07em}.production-safety{display:flex;align-items:center;gap:14px;padding:12px 15px;border:1px solid #b9ddce;border-radius:10px;background:#f1fbf6;color:#376657;font-size:12px}.production-safety b{color:#176a4d}.production-safety span:last-child{margin-left:auto;padding:4px 9px;border-radius:999px;background:#dff4e9;color:#176a4d;font-weight:700}.execution-metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px}.execution-metrics article{padding:15px 16px;border:1px solid #dce5ec;border-radius:11px;background:#fff}.execution-metrics span,.execution-metrics small{display:block;color:#68798a}.execution-metrics strong{display:block;margin:6px 0 3px;font-size:24px;color:#17324b}.execution-tabs{display:flex;gap:5px;padding:5px;border:1px solid #dce5ec;border-radius:10px;background:#f6f8fa}.execution-tabs button{min-height:36px;padding:7px 14px;border:0;border-radius:7px;background:transparent;color:#53677b;font-weight:700;cursor:pointer}.execution-tabs button.active{background:#fff;color:#1769aa;box-shadow:0 1px 5px rgba(25,55,80,.12)}.execution-panel{min-width:0;overflow:hidden;border:1px solid #dce5ec;border-radius:11px;background:#fff}.execution-panel>header{display:flex;justify-content:space-between;align-items:center;gap:14px;padding:15px 17px;border-bottom:1px solid #e8edf2}.execution-panel h2{margin:0 0 4px;font-size:16px}.execution-panel header p{margin:0;color:#6c7d8e;font-size:12px}.case-table-wrap{overflow:auto}.case-table-wrap table{width:100%;min-width:1050px;border-collapse:collapse}.case-table-wrap th,.case-table-wrap td{padding:12px 14px;border-top:1px solid #edf1f4;text-align:left;font-size:12px}.case-table-wrap th{border-top:0;background:#f7f9fb;color:#66788a}.case-table-wrap tr.selected{background:#f2f8ff}.row-actions{display:flex;gap:6px}.state{display:inline-flex;padding:4px 8px;border-radius:999px;background:#edf2f6}.state.ready,.state.pending_review{background:#fff3d5;color:#8a6419}.state.in_progress{background:#e8f3ff;color:#1769aa}.state.completed{background:#e3f5eb;color:#196b48}.state.cancelled{background:#f0f1f2;color:#737b84}.empty{padding:42px!important;color:#718092;text-align:center}.detail-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;padding:17px}.detail-grid article{display:grid;gap:5px;padding:13px;border:1px solid #e1e8ee;border-radius:9px;background:#fafbfd}.detail-grid span{color:#718092;font-size:11px}.detail-grid strong{overflow-wrap:anywhere;color:#253b50}.detail-grid small{color:#8a97a4}.timeline{display:grid;gap:0;margin:0;padding:18px 22px;list-style:none}.timeline li{display:grid;grid-template-columns:18px 1fr;gap:11px;position:relative;padding-bottom:20px}.timeline li:not(:last-child)::before{content:'';position:absolute;left:6px;top:14px;bottom:0;border-left:2px solid #d8e5ef}.timeline i{z-index:1;width:14px;height:14px;border:3px solid #fff;border-radius:50%;background:#2b7db8;box-shadow:0 0 0 1px #8bb8d7}.timeline li>div{display:grid;gap:3px}.timeline span,.timeline small{color:#758495;font-size:11px}.timeline p{margin:3px 0;color:#40556a}.agent-guard{display:flex;gap:12px;margin:16px;padding:13px;border:1px solid #c9dff1;border-radius:9px;background:#f4f9fd;color:#526a80}.agent-guard b{color:#1769aa;white-space:nowrap}.agent-runs{display:grid;gap:8px;margin:0;padding:0 16px 16px;list-style:none}.agent-runs li{display:grid;grid-template-columns:190px 1fr auto;gap:12px;padding:11px;border:1px solid #e0e7ed;border-radius:8px}.evidence-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;padding:17px}.evidence-grid div{padding:12px;border:1px solid #e1e8ee;border-radius:8px}.evidence-grid dt{color:#718092;font-size:11px}.evidence-grid dd{margin:5px 0 0;overflow-wrap:anywhere}.dialog-grid .full{grid-column:1/-1}.dialog-grid small{color:#7d8996;font-weight:400}.btn.sm{min-height:30px;padding:4px 8px}.btn.danger{color:#a63131}.inline-notice{margin:0;padding:10px 13px;border:1px solid #c8dff0;border-radius:8px;background:#f3f8fd;color:#3f617c}
@media(max-width:900px){.execution-metrics{grid-template-columns:repeat(2,minmax(0,1fr))}.detail-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.production-safety{align-items:flex-start;flex-wrap:wrap}.production-safety span:last-child{margin-left:0}.execution-tabs{overflow-x:auto}.execution-tabs button{white-space:nowrap}}
@media(max-width:600px){.page-heading{flex-direction:column}.execution-metrics,.detail-grid,.evidence-grid{grid-template-columns:1fr}.agent-runs li{grid-template-columns:1fr}.production-execution-page{gap:12px}}
</style>
