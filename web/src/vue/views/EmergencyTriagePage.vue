<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref } from 'vue';
import { clinicalContext, setEmergencyClinicalContext } from '../../clinical-api';
import type { EmergencyPreadmissionWire, EmergencyTriageAssessmentWire, WaitingQueueEntryWire } from '../../generated/contracts';
import {
  createEmergencyTriageAssessment,
  issueEmergencyEncounterLease,
  issueEmergencyFacilityLease,
  issueEmergencyLease,
  listEmergencyPreadmissions,
  listEmergencyTriageAssessments,
  listWaitingQueue,
  voidEmergencyTriageAssessment,
  linkEmergencyPreadmission,
  registerEmergencyPreadmission,
  updateEmergencyPreadmission,
  voidEmergencyPreadmission,
} from '../../api/emergency';
import AgentInlineReview from '../components/AgentInlineReview.vue';
import AdminActionDialog from '../components/AdminActionDialog.vue';
import AdminConfirmDialog from '../components/AdminConfirmDialog.vue';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';

const leaseQuery = useQuery({ queryKey: ['emergency', 'triage', 'lease'], queryFn: () => issueEmergencyLease('EMERGENCY_TRIAGE'), retry: false, staleTime: 5 * 60_000, gcTime: 0 });
const commandLeaseQuery = useQuery({ queryKey: ['emergency', 'triage', 'command-lease'], queryFn: () => issueEmergencyEncounterLease('EMERGENCY_TRIAGE_COMMAND'), retry: false, staleTime: 5 * 60_000, gcTime: 0 });
const facilityLeaseQuery = useQuery({ queryKey: ['emergency', 'triage', 'facility-lease'], queryFn: () => issueEmergencyFacilityLease('EMERGENCY_TRIAGE_BOARD'), retry: false, staleTime: 5 * 60_000, gcTime: 0 });
const itemsQuery = useQuery({ queryKey: ['emergency', 'triage', 'items'], queryFn: () => listEmergencyTriageAssessments(leaseQuery.data.value!), enabled: () => Boolean(leaseQuery.data.value), retry: false });
const queueQuery = useQuery({ queryKey: ['emergency', 'triage', 'queue'], queryFn: () => listWaitingQueue(facilityLeaseQuery.data.value!), enabled: () => Boolean(facilityLeaseQuery.data.value), retry: false });
const preadmissionQuery = useQuery({ queryKey: ['emergency', 'triage', 'preadmissions'], queryFn: () => listEmergencyPreadmissions(facilityLeaseQuery.data.value!), enabled: () => Boolean(facilityLeaseQuery.data.value), retry: false });
const issue = computed(() => {
  const error = leaseQuery.error.value ?? commandLeaseQuery.error.value ?? facilityLeaseQuery.error.value ?? itemsQuery.error.value ?? queueQuery.error.value ?? preadmissionQuery.error.value;
  return error ? toClinicalIssue(error) : null;
});
const items = computed(() => itemsQuery.data.value ?? []);
const activeCount = computed(() => items.value.filter((i) => i.status === 'ACTIVE' && !i.voided_at).length);
const currentAssessment = computed(() => items.value.find((item) => item.status === 'ACTIVE' && !item.voided_at) ?? null);
const queue = computed(() => queueQuery.data.value ?? []);
const waitingCount = computed(() => queue.value.filter((item) => item.status === 'WAITING').length);
const unregisteredCount = computed(() => (preadmissionQuery.data.value ?? []).filter((item) => item.status === 'UNREGISTERED').length);
const levelLabels: Record<string, string> = { LEVEL_1: '一级(A) · 濒危', LEVEL_2: '二级(B) · 危重', LEVEL_3: '三级(C) · 急症', LEVEL_4: '四级(D) · 非急症' };
const levelTone: Record<string, string> = { LEVEL_1: 'red', LEVEL_2: 'amber', LEVEL_3: 'blue', LEVEL_4: 'gray' };

const form = reactive({ triage_level: 'LEVEL_3' as 'LEVEL_1' | 'LEVEL_2' | 'LEVEL_3' | 'LEVEL_4', chief_complaint: '', immediate_action_required: false, triaged_at: new Date().toISOString().slice(0, 16) });
const editorOpen = ref(false);
const rulesOpen = ref(false);
const editingTarget = ref<EmergencyTriageAssessmentWire | null>(null);
const voidTarget = ref<EmergencyTriageAssessmentWire | null>(null);
const voidReason = ref('');
const busy = ref('');
const notice = ref('');
const preadmissionForm = reactive({ temporary_identifier: '', reason: '' });
const preadmissionEditorOpen = ref(false);
const editingPreadmission = ref<EmergencyPreadmissionWire | null>(null);
const voidPreadmissionTarget = ref<EmergencyPreadmissionWire | null>(null);
const linkPreadmissionTarget = ref<EmergencyPreadmissionWire | null>(null);
const preadmissionVoidReason = ref('');
const preadmissions = computed(() => preadmissionQuery.data.value ?? []);

const agentPatientId = computed(() => clinicalContext.emergencyPatientId);
const agentEncounterId = computed(() => clinicalContext.emergencyEncounterId);
const triageSummaryObjective = computed(() => '基于当前急诊分诊信息汇总病情摘要候选，仅供医生审阅。');

function formatDate(value: string) { return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short', hour12: false }).format(new Date(value)); }
function resetForm() { Object.assign(form, { triage_level: 'LEVEL_3', chief_complaint: '', immediate_action_required: false, triaged_at: new Date().toISOString().slice(0, 16) }); editingTarget.value = null; }
function openCreate() { resetForm(); editorOpen.value = true; }
function openEdit(item: EmergencyTriageAssessmentWire) { editingTarget.value = item; Object.assign(form, { triage_level: item.triage_level, chief_complaint: item.chief_complaint, immediate_action_required: item.immediate_action_required, triaged_at: new Date(item.triaged_at).toISOString().slice(0, 16) }); editorOpen.value = true; }
async function reload() { notice.value = ''; await Promise.all([itemsQuery.refetch(), queueQuery.refetch(), preadmissionQuery.refetch()]); }

async function saveAssessment() {
  const lease = commandLeaseQuery.data.value;
  if (!lease || busy.value || !form.chief_complaint.trim()) return;
  busy.value = 'save'; notice.value = '';
  try {
    await createEmergencyTriageAssessment(lease, { triage_level: form.triage_level, chief_complaint: form.chief_complaint.trim(), triaged_at: new Date(form.triaged_at).toISOString(), immediate_action_required: form.immediate_action_required });
    notice.value = editingTarget.value ? `已完成复评并生成新版${levelLabels[form.triage_level]}分诊，原评估只读保留。` : `已按${levelLabels[form.triage_level]}完成分诊。`;
    editorOpen.value = false; resetForm(); await itemsQuery.refetch();
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; } finally { busy.value = ''; }
}

async function confirmVoid() {
  const lease = commandLeaseQuery.data.value; const target = voidTarget.value;
  if (!lease || !target || busy.value || voidReason.value.trim().length < 4) return;
  busy.value = 'void'; notice.value = '';
  try { await voidEmergencyTriageAssessment(lease, target, voidReason.value.trim()); notice.value = '分诊评估已逻辑作废，不再影响当前分诊流程，历史审计仍保留。'; voidTarget.value = null; voidReason.value = ''; await itemsQuery.refetch(); }
  catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; } finally { busy.value = ''; }
}

function openPreadmissionCreate() { editingPreadmission.value = null; Object.assign(preadmissionForm, { temporary_identifier: `ER-${new Date().toISOString().replace(/\D/g, '').slice(0, 14)}`, reason: '' }); preadmissionEditorOpen.value = true; }
function openPreadmissionEdit(item: EmergencyPreadmissionWire) { editingPreadmission.value = item; Object.assign(preadmissionForm, { temporary_identifier: item.temporary_identifier, reason: item.reason }); preadmissionEditorOpen.value = true; }
async function savePreadmission() { const lease = facilityLeaseQuery.data.value; if (!lease || busy.value || !preadmissionForm.temporary_identifier.trim() || !preadmissionForm.reason.trim()) return; busy.value = 'preadmission-save'; notice.value = ''; try { const input = { temporary_identifier: preadmissionForm.temporary_identifier.trim(), reason: preadmissionForm.reason.trim() }; if (editingPreadmission.value) await updateEmergencyPreadmission(lease, editingPreadmission.value, input); else await registerEmergencyPreadmission(lease, input); notice.value = editingPreadmission.value ? '临时急诊登记已生成更正版本。' : '临时急诊登记已创建，待完成身份核验。'; preadmissionEditorOpen.value = false; editingPreadmission.value = null; await preadmissionQuery.refetch(); } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; } finally { busy.value = ''; } }
async function confirmVoidPreadmission() { const lease = facilityLeaseQuery.data.value; const target = voidPreadmissionTarget.value; if (!lease || !target || busy.value || preadmissionVoidReason.value.trim().length < 4) return; busy.value = 'preadmission-void'; notice.value = ''; try { await voidEmergencyPreadmission(lease, target, preadmissionVoidReason.value.trim()); notice.value = '临时急诊登记已逻辑删除，不再计入待核身份。'; voidPreadmissionTarget.value = null; preadmissionVoidReason.value = ''; await preadmissionQuery.refetch(); } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; } finally { busy.value = ''; } }
async function confirmLinkPreadmission() { const lease = facilityLeaseQuery.data.value; const target = linkPreadmissionTarget.value; if (!lease || !target || busy.value) return; busy.value = 'preadmission-link'; notice.value = ''; try { await linkEmergencyPreadmission(lease, target, clinicalContext.emergencyPatientId); notice.value = '临时登记已与当前急诊患者建立正式关联。'; linkPreadmissionTarget.value = null; await preadmissionQuery.refetch(); } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; } finally { busy.value = ''; } }
function switchEmergencyPatient(entry: WaitingQueueEntryWire) {
  if (entry.patient_id === clinicalContext.emergencyPatientId && entry.encounter_id === clinicalContext.emergencyEncounterId) return;
  setEmergencyClinicalContext(entry.patient_id, entry.encounter_id);
  window.location.reload();
}
</script>

<template>
  <section data-page-root class="content admin-content vue-native-page emergency-crud-page">
    <div class="page-head"><div class="page-title"><h1>急诊预检分诊与分区</h1><p>快速身份、生命体征、分诊依据、立即处置和动态复评</p></div><div class="head-actions"><button class="btn" type="button" @click="rulesOpen=true">分诊规则说明</button><button class="btn" type="button" @click="openCreate">新增分诊评估</button><button class="btn primary" type="button" @click="openPreadmissionCreate">新增急诊患者</button></div></div>
    <ClinicalPageState v-if="leaseQuery.isPending.value || commandLeaseQuery.isPending.value || facilityLeaseQuery.isPending.value || itemsQuery.isPending.value || queueQuery.isPending.value || preadmissionQuery.isPending.value" kind="loading" message="正在读取分诊评估与院区队列" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reload" />
    <template v-else>
      <div class="metric-grid emergency-metrics" aria-label="分诊统计">
        <div class="metric"><div class="name">待分诊</div><div class="value">{{ waitingCount }}</div><div class="trend danger-text">院区队列 {{ queue.length }} 人</div></div>
        <div class="metric"><div class="name">Ⅰ/Ⅱ级</div><div class="value">{{ currentAssessment?.triage_level === 'LEVEL_1' ? 1 : 0 }} / {{ currentAssessment?.triage_level === 'LEVEL_2' ? 1 : 0 }}</div><div class="trend">需立即处置 {{ currentAssessment?.immediate_action_required ? 1 : 0 }}</div></div>
        <div class="metric"><div class="name">待核身份</div><div class="value">{{ unregisteredCount }}</div><div class="trend">先救治路径已开启</div></div>
        <div class="metric"><div class="name">实时队列</div><div class="value">{{ queue.length }}</div><div class="trend warning-text">未配置区域床位上限时不伪造容量百分比</div></div>
      </div>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>
      <AgentInlineReview agent-code="ENCOUNTER_SUMMARIZER" stage-code="TRIAGE" :objective="triageSummaryObjective" :patient-id="agentPatientId" :encounter-id="agentEncounterId" target-type="ENCOUNTER" :target-id="agentEncounterId" title="AI 分诊摘要候选" source-route="er-triage" />
      <div class="grid secondary-grid emergency-prototype-layout">
        <section class="card scroll-card emergency-prototype-main">
          <div class="card-head">急诊患者队列 <span class="sub">到院顺序与实时状态</span></div>
          <div class="admin-table-wrap"><table class="table emergency-compact-table"><thead><tr><th>患者/到院</th><th>状态</th><th>叫号时间</th><th>当前分级</th><th>处置优先级</th><th>操作</th></tr></thead><tbody>
            <tr v-for="entry in queue.slice(0, 8)" :key="entry.waiting_queue_entry_id"><td><b>{{ entry.patient_display_name }}</b><br><span class="meta">#{{ entry.sequence_no }}</span></td><td>{{ entry.status }}</td><td>{{ entry.called_at ? formatDate(entry.called_at) : '待叫号' }}</td><td><span class="status" :class="entry.patient_id===clinicalContext.emergencyPatientId ? levelTone[currentAssessment?.triage_level ?? 'LEVEL_4'] : 'gray'">{{ entry.patient_id===clinicalContext.emergencyPatientId ? levelLabels[currentAssessment?.triage_level ?? 'LEVEL_4'] : '切换后读取' }}</span></td><td>{{ entry.patient_id===clinicalContext.emergencyPatientId ? (currentAssessment?.immediate_action_required ? '立即处置' : '按院内分区规则') : '—' }}</td><td><button class="btn sm" type="button" :disabled="entry.patient_id===clinicalContext.emergencyPatientId" @click="switchEmergencyPatient(entry)">{{ entry.patient_id===clinicalContext.emergencyPatientId?'当前患者':'切换患者' }}</button></td></tr>
          </tbody></table></div><div v-if="queue.length>8" class="emergency-table-summary">显示优先级最高的 8 人；院区队列共 {{ queue.length }} 人。</div>
          <div class="card-body"><div class="section-title">当前患者 · 分诊版本台账</div><div v-if="!items.length" class="clinical-empty-state compact"><strong>暂无分诊评估</strong><span>新增后分级将立即驱动分区与处置。</span></div><div v-else class="emergency-version-list">
            <article v-for="item in items" :key="item.triage_assessment_id" :class="{ 'is-voided': item.voided_at }"><div><span class="status" :class="levelTone[item.triage_level]">{{ levelLabels[item.triage_level] }}</span><b>{{ item.chief_complaint }}</b><small>{{ formatDate(item.triaged_at) }} · {{ item.voided_at ? '已作废' : item.status === 'ACTIVE' ? '当前生效' : '历史版本' }}</small></div><span class="inline-actions"><button class="btn sm" :disabled="Boolean(busy)||Boolean(item.voided_at)" @click="openEdit(item)">编辑/复评</button><button class="btn sm danger" :disabled="Boolean(busy)||Boolean(item.voided_at)" @click="voidTarget=item">删除</button></span></article>
          </div></div>
        </section>
        <aside class="card scroll-card emergency-prototype-side"><div class="card-head">分诊决策与改区</div><div class="card-body">
          <div class="notice hard"><div class="notice-title">{{ currentAssessment ? levelLabels[currentAssessment.triage_level] : '待完成首次分诊' }}</div>{{ currentAssessment?.chief_complaint ?? '分诊不等待完整身份，先救治后补登。' }}</div>
          <div class="form-row"><div class="label">系统处置提示</div><div class="field">{{ currentAssessment?.immediate_action_required ? '立即抢救/处置' : '按院内分区规则分流' }}</div></div>
          <div class="form-row"><div class="label">分诊时间</div><div class="field">{{ currentAssessment ? formatDate(currentAssessment.triaged_at) : '待记录' }}</div></div>
          <div class="section-title emergency-summary-title">改级/改区必须记录</div>
          <div v-for="rule in ['原级别与原区域','新级别与新区域','触发事实和临床原因','申请人/批准人','事件时间/记录时间','通知与交接结果']" :key="rule" class="folder-row">{{ rule }}<span>必填</span></div>
          <button class="btn danger emergency-full-action" type="button" @click="currentAssessment ? openEdit(currentAssessment) : openCreate()">申请改级或改区</button>
        </div></aside>
      </div>
      <section class="admin-panel emergency-nested-crud"><header><div><h2>先救治后补登</h2><p>临时身份的新建、更正、删除和正式患者关联都会改变待核身份流程。</p></div><button class="button primary" @click="openPreadmissionCreate">新建临时登记</button></header><div v-if="!preadmissions.length" class="admin-empty">暂无待核身份登记。</div><div v-else class="admin-table-wrap"><table class="admin-table"><thead><tr><th>临时标识</th><th>来诊原因</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="item in preadmissions" :key="item.preadmission_id"><td><strong>{{ item.temporary_identifier }}</strong></td><td>{{ item.reason }}</td><td><span class="status" :class="item.status==='UNREGISTERED'?'amber':'green'">{{ item.status==='UNREGISTERED'?'待核身份':'已关联' }}</span></td><td><span class="inline-actions"><button class="task-action" :disabled="item.status!=='UNREGISTERED'||Boolean(busy)" @click="openPreadmissionEdit(item)">编辑</button><button class="task-action" :disabled="item.status!=='UNREGISTERED'||Boolean(busy)" @click="linkPreadmissionTarget=item">关联当前患者</button><button class="task-action danger" :disabled="item.status!=='UNREGISTERED'||Boolean(busy)" @click="voidPreadmissionTarget=item">删除</button></span></td></tr></tbody></table></div></section>
    </template>

    <AdminActionDialog v-model:open="rulesOpen" title="急诊预检分诊原则" description="当前四级语义与 WS/T 390—2012 的 A–D 病情严重程度对应；院内时限、红黄绿分区、危险信号和绿色通道必须以医院已发布制度与规则配置为准，不由一个“立即处置”布尔值代替。" eyebrow="急诊 / 分诊规则"><div class="emergency-rule-dialog"><div v-for="rule in [['Ⅰ级(A)','濒危，立即抢救'],['Ⅱ级(B)','危重，优先处置'],['Ⅲ级(C)','急症，持续候诊与动态复评'],['Ⅳ级(D)','非急症，常规候诊与动态复评']]" :key="rule[0]"><span class="status" :class="rule[0].startsWith('Ⅰ')?'red':rule[0].startsWith('Ⅱ')?'amber':'blue'">{{ rule[0] }}</span><b>{{ rule[1] }}</b></div></div></AdminActionDialog>
    <AdminActionDialog v-model:open="editorOpen" :title="editingTarget ? '编辑分诊并生成复评版本' : '新建分诊评估'" description="保存后立即影响急诊处置优先级；原始临床事实不会被覆盖。院内分区规则未接入前，系统不自动声称已分配红/黄/绿区。" eyebrow="急诊 / 预检分诊" :busy="busy==='save'" @update:open="!$event && resetForm()"><form class="admin-form" @submit.prevent="saveAssessment"><label><span>分诊分级</span><select v-model="form.triage_level"><option value="LEVEL_1">一级(A) · 濒危</option><option value="LEVEL_2">二级(B) · 危重</option><option value="LEVEL_3">三级(C) · 急症</option><option value="LEVEL_4">四级(D) · 非急症</option></select></label><label><span>主诉</span><textarea v-model="form.chief_complaint" rows="4" required placeholder="例：突发胸痛伴大汗 30 分钟" /></label><label><span>分诊时间</span><input v-model="form.triaged_at" type="datetime-local" required /></label><label class="risk-confirm"><input v-model="form.immediate_action_required" type="checkbox" /><span>需要立即抢救/处置</span></label><button class="button primary" :disabled="Boolean(busy) || !form.chief_complaint.trim()">{{ busy === 'save' ? '正在保存…' : '验证并保存' }}</button></form></AdminActionDialog>
    <AdminConfirmDialog :open="Boolean(voidTarget)" title="删除分诊评估" description="删除按医疗审计要求执行为逻辑作废；该评估将停止影响分诊流程。" confirm-label="确认删除并作废" :busy="busy==='void'" @update:open="!$event && (voidTarget=null)" @confirm="confirmVoid"><label class="admin-confirm-reason"><span>作废原因（至少 4 字）</span><textarea v-model="voidReason" rows="3" required placeholder="例：误选患者，记录无效" /></label></AdminConfirmDialog>
    <AdminActionDialog v-model:open="preadmissionEditorOpen" :title="editingPreadmission?'编辑临时急诊登记':'新建临时急诊登记'" description="适用于无名氏或紧急先救治；编辑会生成可追溯新版本。" eyebrow="急诊 / 先救治后补登" :busy="busy==='preadmission-save'"><form class="admin-form" @submit.prevent="savePreadmission"><label><span>临时患者标识</span><input v-model="preadmissionForm.temporary_identifier" autofocus required /></label><label><span>来诊 / 先救治原因</span><textarea v-model="preadmissionForm.reason" rows="3" required /></label><button class="button primary" :disabled="Boolean(busy)||!preadmissionForm.temporary_identifier.trim()||!preadmissionForm.reason.trim()">{{ busy==='preadmission-save'?'正在保存…':'验证并保存' }}</button></form></AdminActionDialog>
    <AdminConfirmDialog :open="Boolean(voidPreadmissionTarget)" title="删除临时急诊登记" description="将退出待核身份流程，历史证据继续保留。" confirm-label="确认删除并作废" :busy="busy==='preadmission-void'" @update:open="!$event&&(voidPreadmissionTarget=null)" @confirm="confirmVoidPreadmission"><label class="admin-confirm-reason"><span>作废原因（至少 4 字）</span><textarea v-model="preadmissionVoidReason" rows="3" required /></label></AdminConfirmDialog>
    <AdminConfirmDialog :open="Boolean(linkPreadmissionTarget)" title="关联正式急诊患者" description="关联后临时登记将退出待核身份队列，流程转入正式患者。" confirm-label="确认关联" :busy="busy==='preadmission-link'" @update:open="!$event&&(linkPreadmissionTarget=null)" @confirm="confirmLinkPreadmission" />
  </section>
</template>
