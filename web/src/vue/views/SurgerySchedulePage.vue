<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref } from 'vue';
import type { SurgicalProcedureWire } from '../../generated/contracts';
import { clinicalContext } from '../../clinical-api';
import { developmentCopy } from '../../development-copy';
import { issueExecutionLease, issueExecutionPatientLease, listSurgicalProcedures, scheduleSurgicalProcedure, transitionSurgicalProcedure, verifySurgicalProcedureTimeOut } from '../../api/execution';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import AdminActionDialog from '../components/AdminActionDialog.vue';
import ExecutionPatientContextBar from '../components/ExecutionPatientContextBar.vue';
import { toClinicalIssue } from '../clinical-error';

type BodySite = SurgicalProcedureWire['body_site'];
type Laterality = SurgicalProcedureWire['laterality'];
const bodySites: BodySite[] = ['HEAD', 'NECK', 'CHEST', 'ABDOMEN', 'PELVIS', 'SPINE', 'UPPER_EXTREMITY', 'LOWER_EXTREMITY', 'OTHER'];
const lateralities: Laterality[] = ['NONE', 'LEFT', 'RIGHT', 'BILATERAL'];
const bodySiteLabels: Record<BodySite, string> = {
  HEAD: '头', NECK: '颈', CHEST: '胸', ABDOMEN: '腹', PELVIS: '骨盆', SPINE: '脊柱', UPPER_EXTREMITY: '上肢', LOWER_EXTREMITY: '下肢', OTHER: '其他',
};
const lateralityLabels: Record<Laterality, string> = { NONE: '—', LEFT: '左', RIGHT: '右', BILATERAL: '双侧' };
const statusLabels: Record<SurgicalProcedureWire['status'], string> = {
  SCHEDULED: '已排程', TIME_OUT_COMPLETED: '安全核查完成', COMPLETED: '已完成',
};

const leaseQuery = useQuery({
  queryKey: ['execution', 'surgery-schedule', 'lease'],
  queryFn: () => issueExecutionPatientLease('SURGERY_SCHEDULE'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const writeLeaseQuery = useQuery({
  queryKey: ['execution', 'surgery-schedule', 'write-lease'],
  queryFn: () => issueExecutionLease('SURGERY_SCHEDULE'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const proceduresQuery = useQuery({
  queryKey: ['execution', 'surgery-schedule', 'procedures'],
  queryFn: () => listSurgicalProcedures(leaseQuery.data.value!),
  enabled: () => Boolean(leaseQuery.data.value),
  retry: false,
});
const issue = computed(() => (leaseQuery.error.value ?? writeLeaseQuery.error.value ?? proceduresQuery.error.value)
  ? toClinicalIssue(leaseQuery.error.value ?? writeLeaseQuery.error.value ?? proceduresQuery.error.value) : null);
const procedures = computed(() => proceduresQuery.data.value ?? []);
const timeoutCount = computed(() => procedures.value.filter((p) => p.status === 'TIME_OUT_COMPLETED').length);

const form = reactive({ procedureName: '', bodySite: 'ABDOMEN' as BodySite, laterality: 'NONE' as Laterality, surgeonId: clinicalContext.userId, anesthesiologistId: clinicalContext.collaboratorUserId });
const busy = ref('');
const notice = ref('');
const createDialogOpen = ref(false);
const verificationProcedure = ref<SurgicalProcedureWire | null>(null);
const verificationNote = ref('');

function fieldId(name: string) { return `surgery-schedule-${name}`; }
function formatDate(value: string | null | undefined) {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—';
}

async function reload() { notice.value = ''; await proceduresQuery.refetch(); }

async function schedule() {
  const lease = writeLeaseQuery.data.value;
  if (!lease || busy.value || !form.procedureName.trim() || !form.surgeonId.trim() || !form.anesthesiologistId.trim()) return;
  busy.value = 'schedule'; notice.value = '';
  try {
    await scheduleSurgicalProcedure(lease, {
      procedure_name: form.procedureName.trim(), body_site: form.bodySite, laterality: form.laterality,
      surgeon_id: form.surgeonId.trim(), anesthesiologist_id: form.anesthesiologistId.trim(),
      scheduled_at: new Date().toISOString(),
    });
    form.procedureName = '';
    createDialogOpen.value = false;
    notice.value = '手术已排程，进入安全核查（Time-Out）与完成闭环。';
    await proceduresQuery.refetch();
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}

async function transition(procedure: SurgicalProcedureWire, action: 'TIME_OUT' | 'COMPLETE') {
  const lease = writeLeaseQuery.data.value;
  if (!lease || busy.value) return;
  busy.value = `${action}:${procedure.surgical_procedure_id}`; notice.value = '';
  try {
    await transitionSurgicalProcedure(lease, procedure, action);
    notice.value = action === 'TIME_OUT' ? '安全核查（Time-Out）已完成并留痕。' : '手术已完成，核查与完成时间均已留痕。';
    await proceduresQuery.refetch();
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}

function openVerification(procedure: SurgicalProcedureWire) {
  verificationProcedure.value = procedure; verificationNote.value = '';
}
async function verifyTimeOut() {
  const lease = writeLeaseQuery.data.value; const procedure = verificationProcedure.value;
  if (!lease || !procedure || busy.value || verificationNote.value.trim().length < 2) return;
  busy.value = `verify:${procedure.surgical_procedure_id}`; notice.value = '';
  try {
    await verifySurgicalProcedureTimeOut(lease, procedure, verificationNote.value.trim());
    verificationProcedure.value = null;
    notice.value = '第二人 Time-Out 核验已留痕；请由另一授权账号执行最终安全核查。';
    await proceduresQuery.refetch();
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}
</script>

<template>
  <section data-page-root class="content vue-native-page">
    <div class="page-heading">
      <div><p class="eyebrow">诊疗执行 / 围手术期</p><h1>围手术期排程与安全核查</h1><p>排程 → 独立第二人核验 → 安全核查（Time-Out）→ 完成，术者与麻醉医生显式登记。</p></div>
      <div class="toolbar-actions"><button class="button secondary" :disabled="Boolean(busy)" @click="reload">刷新</button><button class="button primary" @click="createDialogOpen = true">新增手术排程</button></div>
    </div>
    <ExecutionPatientContextBar />
    <section class="patient-strip"><div class="patient-avatar">{{ developmentCopy.patientAvatar }}</div><div><strong>{{ developmentCopy.outpatientPatientName }}</strong><span>当前患者围手术期</span></div><dl><div><dt>安全核查</dt><dd>Time-Out 留痕</dd></div><div><dt>术者 / 麻醉</dt><dd>显式登记</dd></div></dl><span class="lease-badge">当前患者 / 当前就诊</span></section>
    <div v-if="notice" class="inline-notice" :class="{ error: notice.includes('：') }" role="status">{{ notice }}</div>

    <ClinicalPageState v-if="leaseQuery.isPending.value || writeLeaseQuery.isPending.value || proceduresQuery.isPending.value" kind="loading" message="正在读取手术排程" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reload" />

    <template v-else>
      <section class="admin-metrics" aria-label="手术排程统计">
        <article><span>手术台次</span><strong>{{ procedures.length }}</strong><small>当前患者</small></article>
        <article><span>已核查</span><strong>{{ timeoutCount }}</strong><small>TIME_OUT</small></article>
        <article><span>已完成</span><strong>{{ procedures.filter((p) => p.status === 'COMPLETED').length }}</strong></article>
      </section>

      <section class="admin-panel">
          <header><div><h2>手术排程台账</h2><p>Time-Out 必须先取得独立第二人核验；核验人与最终执行人不能是同一账号。</p></div><button class="button secondary" @click="proceduresQuery.refetch()">刷新</button></header>
          <div v-if="procedures.length === 0" class="empty-state"><span>术</span><p>当前患者暂无手术排程</p><small>在右侧录入术式开始排程</small></div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>术式</th><th>部位 / 侧别</th><th>术者 / 麻醉</th><th>排程时间</th><th>第二人核验</th><th>核查时间</th><th>状态</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="procedure in procedures" :key="procedure.surgical_procedure_id">
                  <td><strong>{{ procedure.procedure_name }}</strong><small>…{{ procedure.surgical_procedure_id.slice(-8) }}</small></td>
                  <td>{{ bodySiteLabels[procedure.body_site] }} · {{ lateralityLabels[procedure.laterality] }}</td>
                  <td><small>术者 …{{ procedure.surgeon_id.slice(-8) }}</small><small>麻醉 …{{ procedure.anesthesiologist_id.slice(-8) }}</small></td>
                  <td>{{ formatDate(procedure.scheduled_at) }}</td>
                  <td><template v-if="procedure.time_out_verified_at"><small>…{{ procedure.time_out_verified_by?.slice(-8) }}</small><small>{{ formatDate(procedure.time_out_verified_at) }}</small></template><span v-else>待独立核验</span></td>
                  <td>{{ formatDate(procedure.time_out_at) }}</td>
                  <td><span class="admin-status" :class="procedure.status.toLowerCase()">{{ statusLabels[procedure.status] }}</span></td>
                  <td class="admin-actions">
                    <button v-if="procedure.status === 'SCHEDULED' && !procedure.time_out_verified_at" class="task-action" :disabled="Boolean(busy)" @click="openVerification(procedure)">第二人核验</button>
                    <button v-if="procedure.status === 'SCHEDULED'" class="task-action" :disabled="Boolean(busy)" @click="transition(procedure, 'TIME_OUT')">安全核查</button>
                    <button v-if="procedure.status === 'TIME_OUT_COMPLETED'" class="task-action" :disabled="Boolean(busy)" @click="transition(procedure, 'COMPLETE')">完成</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
      </section>

      <AdminActionDialog v-model:open="createDialogOpen" title="新增手术排程" description="术式、部位、术者和麻醉医生均需显式登记。" eyebrow="诊疗执行 / 围手术期" size="large" :busy="busy === 'schedule'">
        <form class="admin-form" @submit.prevent="schedule">
          <label :for="fieldId('procedure-name')"><span>术式名称</span><input :id="fieldId('procedure-name')" v-model="form.procedureName" maxlength="256" required placeholder="例：腹腔镜胆囊切除术" /></label>
          <label :for="fieldId('body-site')"><span>部位</span><select :id="fieldId('body-site')" v-model="form.bodySite"><option v-for="site in bodySites" :key="site" :value="site">{{ bodySiteLabels[site] }}</option></select></label>
          <label :for="fieldId('laterality')"><span>侧别</span><select :id="fieldId('laterality')" v-model="form.laterality"><option v-for="lat in lateralities" :key="lat" :value="lat">{{ lateralityLabels[lat] }}</option></select></label>
          <label :for="fieldId('surgeon-id')"><span>术者 ID</span><input :id="fieldId('surgeon-id')" v-model="form.surgeonId" maxlength="36" required placeholder="UUID" /></label>
          <label :for="fieldId('anesthesiologist-id')"><span>麻醉医生 ID</span><input :id="fieldId('anesthesiologist-id')" v-model="form.anesthesiologistId" maxlength="36" required placeholder="UUID" /></label>
          <button class="button primary full" :disabled="Boolean(busy)">{{ busy === 'schedule' ? '正在排程…' : '排程手术' }}</button>
        </form>
      </AdminActionDialog>
      <AdminActionDialog :open="Boolean(verificationProcedure)" title="独立第二人 Time-Out 核验" description="核验将记录当前登录账号。该账号之后不能执行本台手术的最终 Time-Out 状态迁移。" eyebrow="围手术期 / 双人安全门禁" size="large" :busy="busy.startsWith('verify:')" @update:open="!$event && (verificationProcedure = null)">
        <form class="admin-form" @submit.prevent="verifyTimeOut"><label class="full" :for="fieldId('verification-note')"><span>核验说明</span><textarea :id="fieldId('verification-note')" v-model="verificationNote" required minlength="2" maxlength="1000" rows="4" placeholder="记录患者身份、术式/部位/侧别、器械和影像资料的独立核对结果" /></label><button class="button primary full" :disabled="Boolean(busy) || verificationNote.trim().length < 2">提交独立核验</button></form>
      </AdminActionDialog>
    </template>
  </section>
</template>
