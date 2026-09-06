<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref } from 'vue';
import type { ResearchCohortWire } from '../../generated/contracts';
import { clinicalContext } from '../../clinical-api';
import {
  computeResearchCohortMember,
  defineResearchCohort,
  deactivateResearchCohort,
  issueDataLease,
  listResearchCohortMembers,
  listResearchCohortSnapshots,
  listResearchCohorts,
  recomputeResearchCohort,
  recordResearchCohortSnapshot,
} from '../../api/data';
import AdminActionDialog from '../components/AdminActionDialog.vue';
import AdminConfirmDialog from '../components/AdminConfirmDialog.vue';
import AgentInlineReview from '../components/AgentInlineReview.vue';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';

const status = ref('ACTIVE');
const selectedCohortId = ref('');

const leaseQuery = useQuery({
  queryKey: ['data', 'cohort-builder', 'lease'],
  queryFn: () => issueDataLease('COHORT_BUILDER_ADMIN'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const cohortsQuery = useQuery({
  queryKey: ['data', 'cohort-builder', 'cohorts', status],
  queryFn: () => listResearchCohorts(leaseQuery.data.value!, status.value || undefined),
  enabled: () => Boolean(leaseQuery.data.value),
  retry: false,
});
const snapshotsQuery = useQuery({
  queryKey: ['data', 'cohort-builder', 'snapshots', selectedCohortId],
  queryFn: () => listResearchCohortSnapshots(leaseQuery.data.value!, selectedCohortId.value),
  enabled: () => Boolean(leaseQuery.data.value && selectedCohortId.value),
  retry: false,
});
const membersQuery = useQuery({
  queryKey: ['data', 'cohort-builder', 'members', selectedCohortId],
  queryFn: () => listResearchCohortMembers(leaseQuery.data.value!, selectedCohortId.value),
  enabled: () => Boolean(leaseQuery.data.value && selectedCohortId.value),
  retry: false,
});
const issue = computed(() => (leaseQuery.error.value ?? cohortsQuery.error.value)
  ? toClinicalIssue(leaseQuery.error.value ?? cohortsQuery.error.value) : null);
const cohorts = computed(() => cohortsQuery.data.value ?? []);
const snapshots = computed(() => snapshotsQuery.data.value ?? []);
const members = computed(() => membersQuery.data.value ?? []);
const selectedCohort = computed(() => cohorts.value.find((cohort) => cohort.research_cohort_id === selectedCohortId.value) ?? null);
const activeCount = computed(() => cohorts.value.filter((cohort) => cohort.status === 'ACTIVE').length);
const detailIssue = computed(() => (snapshotsQuery.error.value ?? membersQuery.error.value)
  ? toClinicalIssue(snapshotsQuery.error.value ?? membersQuery.error.value) : null);

const agentPatientId = computed(() => clinicalContext.patientId || null);
const agentEncounterId = computed(() => clinicalContext.encounterId || null);
const researchFollowupObjective = computed(() => '对当前科研队列进行入组与随访建议，输出候选，仅供医生审阅。');

const form = reactive({ cohortCode: '', cohortName: '', inclusionCriteria: '', exclusionCriteria: '' });
const memberForm = reactive({ patientId: clinicalContext.patientId });
const busy = ref('');
const notice = ref('');
const createOpen = ref(false);
const snapshotOpen = ref(false);
const memberOpen = ref(false);
const deactivateOpen = ref(false);
const pendingDeactivate = ref<ResearchCohortWire | null>(null);

function formatDate(value: string | null | undefined) {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—';
}

async function reload() {
  notice.value = '';
  await cohortsQuery.refetch();
}

async function selectCohort(cohortId: string) {
  selectedCohortId.value = cohortId;
  notice.value = '';
  if (cohortId) await Promise.all([snapshotsQuery.refetch(), membersQuery.refetch()]);
}

async function createCohort() {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || !form.cohortCode.trim() || !form.cohortName.trim() || !form.inclusionCriteria.trim()) return;
  busy.value = 'create'; notice.value = '';
  try {
    await defineResearchCohort(lease, {
      cohort_code: form.cohortCode.trim(),
      cohort_name: form.cohortName.trim(),
      inclusion_criteria: form.inclusionCriteria.trim(),
      exclusion_criteria: form.exclusionCriteria.trim() ? form.exclusionCriteria.trim() : undefined,
    });
    form.cohortCode = ''; form.cohortName = ''; form.inclusionCriteria = ''; form.exclusionCriteria = '';
    notice.value = '研究队列已定义，审计链与事件出箱已同步记录。';
    createOpen.value = false;
    await cohortsQuery.refetch();
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = ''; }
}

function requestDeactivate(cohort: ResearchCohortWire) { pendingDeactivate.value = cohort; deactivateOpen.value = true; }
async function deactivatePending() { if (pendingDeactivate.value) await deactivate(pendingDeactivate.value); deactivateOpen.value = false; }

async function deactivate(cohort: ResearchCohortWire) {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || cohort.status !== 'ACTIVE') return;
  busy.value = cohort.research_cohort_id; notice.value = '';
  try {
    await deactivateResearchCohort(lease, cohort);
    notice.value = `队列 ${cohort.cohort_name} 已停用。`;
    await cohortsQuery.refetch();
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = ''; }
}

async function recordSnapshot() {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || !selectedCohortId.value) return;
  busy.value = 'snapshot'; notice.value = '';
  try {
    await recordResearchCohortSnapshot(lease, {
      research_cohort_id: selectedCohortId.value,
      member_count: members.value.length,
      computed_at: new Date().toISOString(),
    });
    notice.value = `队列快照已由服务端按 ${members.value.length} 名实际成员生成，判据哈希已固化。`;
    snapshotOpen.value = false;
    await snapshotsQuery.refetch();
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = ''; }
}

async function computeMember() {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || !selectedCohortId.value || !memberForm.patientId.trim()) return;
  busy.value = 'member'; notice.value = '';
  try {
    await computeResearchCohortMember(lease, {
      research_cohort_id: selectedCohortId.value,
      patient_id: memberForm.patientId.trim(),
      computed_at: new Date().toISOString(),
    });
    notice.value = '患者成员已计算并加入队列。';
    memberOpen.value = false;
    await membersQuery.refetch();
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = ''; }
}

async function recomputeAllMembers() {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || !selectedCohortId.value) return;
  busy.value = 'recompute'; notice.value = '';
  try {
    const result = await recomputeResearchCohort(lease, selectedCohortId.value);
    notice.value = `已筛选全库 ${result.evaluated_count} 名活动患者，命中 ${result.matched_count} 名，新增 ${result.new_member_count} 名，并生成快照。`;
    await Promise.all([membersQuery.refetch(), snapshotsQuery.refetch()]);
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}
</script>

<template>
  <section data-page-root class="content admin-content vue-native-page">
    <nav class="admin-breadcrumb" aria-label="科研中心层级导航"><RouterLink to="/research">← 返回科研中心</RouterLink></nav>
    <div class="page-heading admin-heading">
      <div>
        <p class="eyebrow">数据中心 / 科研</p>
        <h1>研究队列构建器</h1>
        <p>定义入排判据的研究队列，记录成员快照与判据哈希，并计算符合入排标准的患者成员；停用不物理删除。</p>
      </div>
      <div class="admin-inline-tools">
        <label class="admin-code-input"><span>状态筛选</span>
          <select v-model="status"><option value="">全部状态</option><option value="ACTIVE">有效</option><option value="INACTIVE">已停用</option></select>
        </label>
        <button class="button secondary" :disabled="Boolean(busy)" @click="reload">查询</button>
        <button class="button primary" :disabled="Boolean(busy)" @click="createOpen = true">新建研究队列</button>
      </div>
    </div>

    <ClinicalPageState v-if="leaseQuery.isPending.value || cohortsQuery.isPending.value" kind="loading" message="正在读取研究队列" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reload" />

    <template v-else>
      <section class="admin-metrics" aria-label="队列统计">
        <article><span>队列总数</span><strong>{{ cohorts.length }}</strong><small>当前筛选</small></article>
        <article><span>有效队列</span><strong>{{ activeCount }}</strong></article>
        <article><span>快照数</span><strong>{{ selectedCohort ? snapshots.length : 0 }}</strong><small>{{ selectedCohort ? selectedCohort.cohort_code : '未选择' }}</small></article>
        <article><span>成员数</span><strong>{{ selectedCohort ? members.length : 0 }}</strong><small>{{ selectedCohort ? selectedCohort.cohort_code : '未选择' }}</small></article>
      </section>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>

      <AgentInlineReview agent-code="RESEARCH_FOLLOWUP" stage-code="COHORT" :objective="researchFollowupObjective" :patient-id="agentPatientId" :encounter-id="agentEncounterId" target-type="ENCOUNTER" :target-id="agentEncounterId" title="AI 科研队列随访候选" source-route="cohort-builder" />

      <section class="admin-panel">
          <header>
            <div><h2>队列台账</h2><p>选择队列后可在下方查看快照与成员。</p></div>
            <button class="button secondary" @click="cohortsQuery.refetch()">刷新</button>
          </header>
          <div v-if="cohorts.length === 0" class="admin-empty" role="status">暂无研究队列，可在右侧定义。</div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>队列编码</th><th>名称</th><th>入排判据</th><th>状态</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="cohort in cohorts" :key="cohort.research_cohort_id">
                  <td><code>{{ cohort.cohort_code }}</code></td>
                  <td><strong>{{ cohort.cohort_name }}</strong><small>…{{ cohort.research_cohort_id.slice(-8) }}</small></td>
                  <td class="admin-criteria"><small>入组：{{ cohort.inclusion_criteria }}</small><small v-if="cohort.exclusion_criteria">排除：{{ cohort.exclusion_criteria }}</small></td>
                  <td><span class="admin-status" :class="cohort.status.toLowerCase()">{{ cohort.status === 'ACTIVE' ? '有效' : '已停用' }}</span></td>
                  <td class="admin-actions">
                    <button class="task-action" :disabled="Boolean(busy)" @click="selectCohort(cohort.research_cohort_id)">详情</button>
                    <button class="task-action" :disabled="cohort.status !== 'ACTIVE' || Boolean(busy)" @click="requestDeactivate(cohort)">{{ busy === cohort.research_cohort_id ? '处理中…' : '停用' }}</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
      </section>

      <section v-if="selectedCohort" class="admin-panel">
        <header>
          <div><h2>快照与成员 · {{ selectedCohort.cohort_code }}</h2><p>快照记录成员数与判据哈希；成员由患者入排计算得出。</p></div>
          <div class="toolbar-actions"><button class="button secondary" :disabled="Boolean(busy)" @click="snapshotOpen = true">记录快照</button><button class="button primary" :disabled="Boolean(busy)" @click="recomputeAllMembers">全库重算</button><button class="button secondary" :disabled="Boolean(busy)" @click="memberOpen = true">计算成员</button><button class="button secondary" @click="selectCohort('')">关闭</button></div>
        </header>
        <ClinicalPageState v-if="snapshotsQuery.isPending.value || membersQuery.isPending.value" kind="loading" message="正在读取队列快照与成员" />
        <ClinicalPageState v-else-if="detailIssue" kind="error" :code="detailIssue.code" :message="detailIssue.message" @retry="snapshotsQuery.refetch()" />
        <div v-else>
          <section>
            <h3>成员快照</h3>
            <div v-if="snapshots.length === 0" class="admin-empty" role="status">暂无快照，可在右侧记录。</div>
            <div v-else class="admin-table-wrap">
              <table class="admin-table">
                <thead><tr><th>成员数</th><th>判据哈希</th><th>计算时间</th></tr></thead>
                <tbody>
                  <tr v-for="snapshot in snapshots" :key="snapshot.research_cohort_snapshot_id">
                    <td>{{ snapshot.member_count }}</td>
                    <td><code>{{ snapshot.criteria_hash }}</code></td>
                    <td>{{ formatDate(snapshot.computed_at) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <h3>成员列表</h3>
            <div v-if="members.length === 0" class="admin-empty" role="status">暂无成员，可在右侧计算。</div>
            <div v-else class="admin-table-wrap">
              <table class="admin-table">
                <thead><tr><th>患者 ID</th><th>计算时间</th></tr></thead>
                <tbody>
                  <tr v-for="member in members" :key="member.cohort_member_id">
                    <td><code>{{ member.patient_id }}</code></td>
                    <td>{{ formatDate(member.computed_at) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>
        </div>
      </section>
    </template>

    <AdminActionDialog v-model:open="createOpen" title="新建研究队列" description="使用可复算 key=value 判据，分号连接；支持 age_gte、age_lte、sex、diagnosis_code、encounter_since。不支持的自然语言判据会被服务端拒绝。" size="large" :busy="busy === 'create'">
      <form class="admin-form cohort-dialog-form" @submit.prevent="createCohort"><label><span>队列编码</span><input v-model="form.cohortCode" maxlength="96" required placeholder="例：COHORT-DM2-2026" /></label><label><span>队列名称</span><input v-model="form.cohortName" maxlength="256" required placeholder="例：2 型糖尿病队列" /></label><label><span>入组判据</span><textarea v-model="form.inclusionCriteria" required placeholder="age_gte=18;diagnosis_code=E11.9" /></label><label><span>排除判据（可选）</span><textarea v-model="form.exclusionCriteria" placeholder="age_lte=17" /></label></form>
      <template #footer="{ close }"><button class="button secondary" :disabled="busy === 'create'" @click="close">取消</button><button class="button primary" :disabled="busy === 'create'" @click="createCohort">{{ busy === 'create' ? '正在定义…' : '定义并生效' }}</button></template>
    </AdminActionDialog>
    <AdminActionDialog v-model:open="snapshotOpen" :title="`记录队列快照 · ${selectedCohort?.cohort_code ?? ''}`" description="成员数由服务端查询不可变队列成员得出，前端不能手填；快照同时固化判据哈希。" :busy="busy === 'snapshot'"><div class="notice info"><div class="notice-title">当前实际成员 {{ members.length }} 名</div>确认后服务端会再次校验数量，发现并发变化将阻断快照。</div><template #footer="{ close }"><button class="button secondary" :disabled="busy === 'snapshot'" @click="close">取消</button><button class="button primary" :disabled="busy === 'snapshot'" @click="recordSnapshot">生成快照</button></template></AdminActionDialog>
    <AdminActionDialog v-model:open="memberOpen" :title="`计算队列成员 · ${selectedCohort?.cohort_code ?? ''}`" description="服务端会重新执行入排判据；只有活动患者且满足条件时才会加入队列。" :busy="busy === 'member'"><form class="admin-form" @submit.prevent="computeMember"><label><span>患者 ID</span><input v-model="memberForm.patientId" maxlength="36" required placeholder="UUID" /></label></form><template #footer="{ close }"><button class="button secondary" :disabled="busy === 'member'" @click="close">取消</button><button class="button primary" :disabled="busy === 'member'" @click="computeMember">计算并加入</button></template></AdminActionDialog>
    <AdminConfirmDialog v-model:open="deactivateOpen" :title="`停用队列 ${pendingDeactivate?.cohort_code ?? ''}`" description="停用后不能生成新快照、计算新成员或发起新的数据交付；既有成员、快照和研究证据保持只读。" confirm-label="确认停用" :busy="Boolean(busy)" @confirm="deactivatePending" />
  </section>
</template>

<style scoped>
.cohort-dialog-form { grid-template-columns: repeat(2, minmax(0, 1fr)); padding: 0; }
@media (max-width: 700px) { .cohort-dialog-form { grid-template-columns: minmax(0, 1fr); } }
</style>
