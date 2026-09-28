<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, ref } from 'vue';
import type { InpatientPathwayInstanceWire, InpatientPathwayTaskWire } from '../../generated/contracts';
import {
  advanceInpatientPathway, completeInpatientPathway, enrollInpatientPathway,
  getInpatientSyntheticActor, inpatientSyntheticActors, issueInpatientLease,
  loadInpatientOverview, loadInpatientPathwayWorkspace, refreshInpatientPathway,
  requestInpatientPathwayVariance, reviewInpatientPathwayVariance,
  type InpatientSyntheticActorKey,
} from '../../clinical-api';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import BusinessActionDialog from '../components/BusinessActionDialog.vue';
import { toClinicalIssue } from '../clinical-error';

const selectedActorKey = ref<InpatientSyntheticActorKey>(getInpatientSyntheticActor()?.key ?? 'AUTHOR');
const busy = ref(false);
const notice = ref('');
const selectedVersionId = ref('');
const enrollOpen = ref(false);
const admissionBasis = ref('主要诊断符合该路径入径标准，已核对禁忌证、合并症与患者意愿。');
const selectedStageCode = ref('');
const varianceOpen = ref(false);
const varianceType = ref<'CONTRAINDICATION' | 'RESOURCE_UNAVAILABLE' | 'PATIENT_REFUSAL' | 'DIAGNOSIS_CHANGED' | 'TASK_FAILED' | 'OTHER'>('CONTRAINDICATION');
const disposition = ref<'CONTINUE' | 'WAIVE_TASK' | 'EXIT_PATHWAY'>('CONTINUE');
const affectedTaskId = ref('');
const varianceReason = ref('');
const reviewNote = ref('');

const workspace = useQuery({
  queryKey: ['clinical', 'inpatient-pathway', selectedActorKey],
  queryFn: async () => {
    const lease = await issueInpatientLease();
    const [overview, pathway] = await Promise.all([
      loadInpatientOverview(lease), loadInpatientPathwayWorkspace(lease),
    ]);
    if (!selectedVersionId.value && pathway.catalog.length) selectedVersionId.value = pathway.catalog[0].pathway_version_id;
    if (pathway.instance && !selectedStageCode.value) selectedStageCode.value = pathway.instance.current_stage_code;
    return { lease, overview, pathway };
  },
  retry: false, staleTime: 0, gcTime: 0,
});

const issue = computed(() => workspace.error.value ? toClinicalIssue(workspace.error.value) : null);
const activeActor = computed(() => inpatientSyntheticActors.find((actor) => actor.key === selectedActorKey.value));
const instance = computed(() => workspace.data.value?.pathway.instance ?? null);
const selectedStage = computed(() => instance.value?.stages.find((stage) => stage.stage_code === selectedStageCode.value)
  ?? instance.value?.stages.find((stage) => stage.stage_code === instance.value?.current_stage_code)
  ?? instance.value?.stages[0]);
const currentStage = computed(() => instance.value?.stages.find((stage) => stage.stage_code === instance.value?.current_stage_code));
const currentStageReady = computed(() => currentStage.value
  ? currentStage.value.completed_task_count === currentStage.value.required_task_count : false);
const isFinalStage = computed(() => instance.value && currentStage.value
  ? currentStage.value.sequence_no === Math.max(...instance.value.stages.map((stage) => stage.sequence_no)) : false);
const pendingVariances = computed(() => instance.value?.variances.filter((variance) => variance.status === 'REQUESTED') ?? []);

async function refetch(next?: InpatientPathwayInstanceWire) {
  if (next) selectedStageCode.value = next.current_stage_code;
  await workspace.refetch();
}


async function enroll() {
  if (!workspace.data.value || busy.value) return;
  busy.value = true; notice.value = '';
  try {
    const created = await enrollInpatientPathway(
      workspace.data.value.lease, selectedVersionId.value, admissionBasis.value.trim(),
    );
    enrollOpen.value = false;
    await refetch(created);
    notice.value = `已按 v${created.version_no} 入径，后续发布的新版本不会静默改写本实例。`;
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = false; }
}

async function execute(action: 'refresh' | 'advance' | 'complete') {
  if (!workspace.data.value || !instance.value || busy.value) return;
  busy.value = true; notice.value = '';
  try {
    const lease = workspace.data.value.lease;
    const next = action === 'refresh' ? await refreshInpatientPathway(lease, instance.value)
      : action === 'advance' ? await advanceInpatientPathway(lease, instance.value)
        : await completeInpatientPathway(lease, instance.value);
    await refetch(next);
    notice.value = action === 'refresh' ? '已从真实文书与医嘱对象重新核验任务状态。'
      : action === 'advance' ? '当前阶段证据完整，已推进到下一阶段。'
        : '最终阶段已完成，路径实例和版本证据已固化。';
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = false; }
}

function openVariance(task?: InpatientPathwayTaskWire) {
  varianceOpen.value = true;
  affectedTaskId.value = task?.pathway_task_id ?? '';
  disposition.value = task ? 'WAIVE_TASK' : 'CONTINUE';
  varianceReason.value = '';
}

async function submitVariance() {
  if (!workspace.data.value || !instance.value || busy.value) return;
  busy.value = true; notice.value = '';
  try {
    const next = await requestInpatientPathwayVariance(workspace.data.value.lease, instance.value, {
      varianceType: varianceType.value, reason: varianceReason.value.trim(), disposition: disposition.value,
      affectedTaskId: disposition.value === 'WAIVE_TASK' ? affectedTaskId.value : null,
    });
    varianceOpen.value = false; varianceReason.value = '';
    await refetch(next);
    notice.value = '路径变异已提出，必须由独立临床岗位审核后才会影响任务或路径状态。';
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = false; }
}

async function review(varianceId: string, decision: 'APPROVE' | 'REJECT') {
  if (!workspace.data.value || !instance.value || busy.value) return;
  busy.value = true; notice.value = '';
  try {
    const next = await reviewInpatientPathwayVariance(
      workspace.data.value.lease, instance.value, varianceId, decision, reviewNote.value.trim(),
    );
    reviewNote.value = '';
    await refetch(next);
    notice.value = decision === 'APPROVE' ? '变异已独立审核通过，处置结果已写入证据链。' : '变异已驳回，原路径任务保持不变。';
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = false; }
}

function actorName(userId?: string | null) {
  if (!userId) return '—';
  return inpatientSyntheticActors.find((actor) => actor.userId === userId)?.displayName ?? `用户…${userId.slice(-6)}`;
}
function stageStatusLabel(status: 'COMPLETED' | 'CURRENT' | 'UPCOMING') {
  return { COMPLETED: '已完成', CURRENT: '当前阶段', UPCOMING: '未开始' }[status];
}
function taskStateLabel(status: 'PENDING' | 'COMPLETED' | 'WAIVED') {
  return { PENDING: '待来源证据', COMPLETED: '来源已完成', WAIVED: '变异豁免' }[status];
}
function sourceLabel(type: 'DOCUMENT_TASK' | 'ORDER_ITEM') {
  return type === 'DOCUMENT_TASK' ? '住院文书任务' : '住院医嘱项目';
}
function varianceTypeLabel(value: string) {
  return { CONTRAINDICATION: '禁忌证', RESOURCE_UNAVAILABLE: '资源不可用', PATIENT_REFUSAL: '患者拒绝', DIAGNOSIS_CHANGED: '诊断变更', TASK_FAILED: '任务失败', OTHER: '其他' }[value] ?? value;
}
function dispositionLabel(value: string) {
  return { CONTINUE: '记录后继续', WAIVE_TASK: '申请豁免任务', EXIT_PATHWAY: '申请退出路径' }[value] ?? value;
}
function formatDate(value?: string | null) {
  return value ? new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value)) : '—';
}
</script>

<template>
  <section data-page-root class="content vue-native-page pathway-page">
    <div class="page-heading"><div><p class="eyebrow">住院 / 标准诊疗</p><h1>住院临床路径执行中心</h1><p>入径版本固定；任务由真实文书和医嘱状态驱动；变异实行提出与独立审核分离。</p></div><div class="toolbar-actions"><button v-if="instance?.status === 'ACTIVE'" class="button" :disabled="busy" @click="execute('refresh')">核验业务来源</button><button v-if="instance?.status === 'ACTIVE'" class="button primary" :disabled="busy" @click="openVariance()">记录路径变异</button></div></div>
    <ClinicalPageState v-if="workspace.isPending.value" kind="loading" message="正在加载住院路径和处理记录" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="workspace.refetch()" />
    <template v-else-if="workspace.data.value">
      <section class="patient-strip" aria-label="住院患者上下文"><div class="patient-avatar">{{ workspace.data.value.overview.patient_display_name.slice(0, 1) }}</div><div><strong>{{ workspace.data.value.overview.patient_display_name }}</strong><span>{{ workspace.data.value.overview.ward_display_name }} · {{ workspace.data.value.overview.bed_label?.endsWith('床') ? workspace.data.value.overview.bed_label : `${workspace.data.value.overview.bed_label}床` }}</span></div><dl><div><dt>住院状态</dt><dd>{{ workspace.data.value.overview.admission.status }}</dd></div><div><dt>当前身份</dt><dd>{{ activeActor?.displayName ?? '当前登录人' }}</dd></div><div><dt>路径事实</dt><dd>{{ instance ? `${instance.pathway_code} · v${instance.version_no}` : '尚未入径' }}</dd></div></dl><span class="lease-badge">…{{ workspace.data.value.overview.admission.admission_id.slice(-8) }}</span></section>
      <div v-if="notice" class="inline-notice" :class="{ error: notice.includes('：') }" role="status">{{ notice }}</div>

      <section v-if="!instance" class="pathway-enroll-card"><header><div><p class="eyebrow">入径评估</p><h2>选择已发布临床路径</h2></div><span>入径后固定定义与版本</span></header><div v-if="workspace.data.value.pathway.catalog.length" class="pathway-catalog"><label v-for="item in workspace.data.value.pathway.catalog" :key="item.pathway_version_id" :class="{ selected: selectedVersionId === item.pathway_version_id }"><input v-model="selectedVersionId" type="radio" :value="item.pathway_version_id" /><div><strong>{{ item.display_name }} <em>v{{ item.version_no }}</em></strong><span>{{ item.specialty_code }} · {{ item.diagnosis_code }} · {{ item.stage_count }}阶段 / {{ item.task_count }}任务</span><p>{{ item.admission_criteria }}</p></div></label></div><div v-else class="clinical-empty-state">当前租户还没有已发布的临床路径版本，请先在配置中心完成定义、独立审批与发布。</div><footer><small>路径建议不会自动创建生效医嘱；高风险动作仍走原业务工作流。</small><button type="button" class="button primary" :disabled="busy || !selectedVersionId" @click="enrollOpen = true">新建入径实例</button></footer><BusinessActionDialog :open="enrollOpen" title="新建临床路径入径实例" description="确认后将固定当前已发布版本，后续新版本不会静默改写已入径患者。" confirm-label="确认入径并固定版本" :busy="busy" width="wide" @cancel="enrollOpen = false" @confirm="enroll"><label>入径依据<textarea v-model="admissionBasis" required minlength="4" maxlength="2000" rows="4" placeholder="记录诊断依据、标准核对、禁忌证和患者意愿" /></label><p class="dialog-warning">当前选中版本：{{ workspace.data.value.pathway.catalog.find((item) => item.pathway_version_id === selectedVersionId)?.display_name ?? '未选择' }}</p></BusinessActionDialog></section>

      <template v-else>
        <section class="pathway-summary"><div><p class="eyebrow">路径实例</p><h2>{{ instance.display_name }} <span>v{{ instance.version_no }}</span></h2><p>{{ instance.admission_basis }}</p></div><dl><div><dt>实例状态</dt><dd :class="instance.status.toLowerCase()">{{ instance.status === 'ACTIVE' ? '执行中' : instance.status === 'COMPLETED' ? '已完成' : '已退出' }}</dd></div><div><dt>真实完成度</dt><dd>{{ instance.completed_task_count }} / {{ instance.required_task_count }} · {{ instance.completion_percent }}%</dd></div><div><dt>入径人 / 时间</dt><dd>{{ actorName(instance.enrolled_by) }} · {{ formatDate(instance.enrolled_at) }}</dd></div><div><dt>证据版本</dt><dd>v{{ instance.row_version }} · {{ instance.data_watermark.slice(0, 10) }}…</dd></div></dl></section>
        <div class="pathway-prototype-grid">
        <div class="pathway-stage-strip" role="tablist" aria-label="路径阶段"><button v-for="stage in instance.stages" :key="stage.stage_code" type="button" :class="[stage.status.toLowerCase(), { active: selectedStage?.stage_code === stage.stage_code }]" @click="selectedStageCode = stage.stage_code"><i>{{ stage.sequence_no }}</i><span><b>{{ stage.display_name }}</b><small>第 {{ stage.expected_day_start }}–{{ stage.expected_day_end }} 日 · {{ stageStatusLabel(stage.status) }}</small></span><em>{{ stage.completed_task_count }}/{{ stage.required_task_count }}</em></button></div>

        <div class="pathway-workspace">
          <section class="pathway-task-panel"><header><div><p class="eyebrow">业务对象驱动</p><h2>{{ selectedStage?.display_name }}任务</h2></div><span>不能手工勾选完成</span></header><div class="pathway-task-list"><article v-for="task in selectedStage?.tasks" :key="task.pathway_task_id" :class="task.state.toLowerCase()"><div class="pathway-task-state"><i>{{ task.state === 'COMPLETED' ? '✓' : task.state === 'WAIVED' ? '△' : '○' }}</i><span>{{ taskStateLabel(task.state) }}</span></div><div><strong>{{ task.display_name }} <em v-if="!task.required">推荐</em><em v-else>必需</em></strong><p>{{ sourceLabel(task.source_type) }} · {{ task.source_key }}</p><small v-if="task.source_resource_id">来源 …{{ task.source_resource_id.slice(-8) }} · {{ task.source_status }} · {{ formatDate(task.completed_at) }}</small><small v-else-if="task.waived_by_variance_id">变异 …{{ task.waived_by_variance_id.slice(-8) }} 已审核</small><small v-else>等待对应业务对象完成后重新核验</small></div><div class="pathway-task-actions"><RouterLink v-if="task.state === 'PENDING'" class="button" :to="task.source_type === 'DOCUMENT_TASK' ? '/inpatient-course' : '/ip-orders'">去业务来源</RouterLink><button v-if="task.state === 'PENDING' && instance.status === 'ACTIVE'" class="button quiet" type="button" @click="openVariance(task)">申请豁免</button></div></article><div v-if="!selectedStage?.tasks.length" class="clinical-empty-state">此阶段没有任务定义。</div></div><footer v-if="instance.status === 'ACTIVE'"><div><strong>{{ currentStageReady ? '当前阶段证据已齐备' : '存在必需任务尚无来源证据' }}</strong><span>{{ currentStage?.display_name }} · {{ currentStage?.completed_task_count }}/{{ currentStage?.required_task_count }}</span></div><button v-if="!isFinalStage" class="button primary" :disabled="busy || !currentStageReady" @click="execute('advance')">推进下一阶段</button><button v-else class="button primary" :disabled="busy || !currentStageReady" @click="execute('complete')">完成路径实例</button></footer><div v-else class="pathway-terminal"><strong>{{ instance.status === 'COMPLETED' ? '路径已完成' : '路径已审批退出' }}</strong><span>既有任务、变异与绑定版本保持只读，不会被后续定义覆盖。</span></div></section>

          <aside class="pathway-variance-panel"><header><div><p class="eyebrow">偏离与例外</p><h2>路径变异证据</h2></div><span :class="{ alert: pendingVariances.length }">{{ pendingVariances.length }} 待审核</span></header><BusinessActionDialog :open="varianceOpen" title="新建路径变异申请" description="变异必须由独立临床岗位审核，通过后才会豁免任务或退出路径。" confirm-label="提出变异申请" :busy="busy" width="wide" @cancel="varianceOpen = false" @confirm="submitVariance"><div class="variance-form"><label>变异类型<select v-model="varianceType"><option value="CONTRAINDICATION">禁忌证</option><option value="RESOURCE_UNAVAILABLE">资源不可用</option><option value="PATIENT_REFUSAL">患者拒绝</option><option value="DIAGNOSIS_CHANGED">诊断变更</option><option value="TASK_FAILED">任务失败</option><option value="OTHER">其他</option></select></label><label>建议处置<select v-model="disposition"><option value="CONTINUE">记录后继续</option><option value="WAIVE_TASK">申请豁免任务</option><option value="EXIT_PATHWAY">申请退出路径</option></select></label><label v-if="disposition === 'WAIVE_TASK'">影响任务<select v-model="affectedTaskId" required><option value="">请选择待处理任务</option><template v-for="stage in instance.stages" :key="stage.stage_code"><option v-for="task in stage.tasks.filter((item) => item.state === 'PENDING')" :key="task.pathway_task_id" :value="task.pathway_task_id">{{ stage.display_name }} · {{ task.display_name }}</option></template></select></label><label>临床原因与替代处置<textarea v-model="varianceReason" required minlength="4" maxlength="2000" rows="4" placeholder="说明证据、影响、替代方案和恢复条件" /></label></div></BusinessActionDialog><div class="variance-list"><article v-for="item in instance.variances" :key="item.variance_id" :class="item.status.toLowerCase()"><header><strong>{{ varianceTypeLabel(item.variance_type) }}</strong><span>{{ item.status === 'REQUESTED' ? '待独立审核' : item.status === 'APPROVED' ? '已批准' : '已驳回' }}</span></header><p>{{ item.reason }}</p><dl><div><dt>建议处置</dt><dd>{{ dispositionLabel(item.disposition) }}</dd></div><div><dt>提出人</dt><dd>{{ actorName(item.requested_by) }} · {{ formatDate(item.requested_at) }}</dd></div><div v-if="item.reviewed_by"><dt>审核人</dt><dd>{{ actorName(item.reviewed_by) }} · {{ formatDate(item.reviewed_at) }}</dd></div></dl><blockquote v-if="item.review_note">{{ item.review_note }}</blockquote><div v-if="item.status === 'REQUESTED' && activeActor?.userId !== item.requested_by" class="variance-review"><textarea v-model="reviewNote" rows="2" minlength="4" maxlength="2000" placeholder="审核意见（必填）" /><div><button class="button danger" :disabled="busy || reviewNote.trim().length < 4" @click="review(item.variance_id, 'REJECT')">驳回</button><button class="button primary" :disabled="busy || reviewNote.trim().length < 4" @click="review(item.variance_id, 'APPROVE')">批准</button></div></div><div v-else-if="item.status === 'REQUESTED'" class="variance-self-gate">申请人不得自审，请切换独立临床岗位。</div></article><div v-if="!instance.variances.length" class="clinical-empty-state">暂无路径变异；正常执行不需要制造例外记录。</div></div></aside>
        </div>
        </div>
      </template>
    </template>
  </section>
</template>
