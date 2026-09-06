<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref } from 'vue';
import { useRoute } from 'vue-router';
import type { PatientDemographicVersionWire, PatientMatchCandidateWire } from '../../generated/contracts';
import { clinicalContext, correctPatientDemographics, detectPatientMatchCandidate, loadPatientDemographicVersions, loadPatientMatchCandidates, issuePatientSearchLease, searchPatientsForAdmission } from '../../clinical-api';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';

const route = useRoute();
const patientSearchTerm = computed(() => typeof route.query.q === 'string' ? route.query.q.trim() : '');
const patientSearch = useQuery({
  queryKey: ['mpi', 'patient-search', patientSearchTerm, computed(() => clinicalContext.organizationId), computed(() => clinicalContext.facilityId)],
  queryFn: async () => searchPatientsForAdmission(await issuePatientSearchLease(), patientSearchTerm.value),
  enabled: () => Boolean(patientSearchTerm.value), retry: false, staleTime: 0, gcTime: 0,
});
const patientSearchIssue = computed(() => patientSearch.error.value ? toClinicalIssue(patientSearch.error.value) : null);

const query = useQuery({ queryKey: ['mpi', 'candidates'], queryFn: () => loadPatientMatchCandidates('OPEN'), retry: false, staleTime: 0, gcTime: 0 });
const issue = computed(() => query.error.value ? toClinicalIssue(query.error.value) : null);
const candidates = computed(() => query.data.value ?? []);
const detectForm = reactive({ patientA: clinicalContext.patientId, patientB: clinicalContext.inpatientPatientId });
const editForm = reactive({ patientId: '', displayName: '', sexCode: '', birthDate: '', status: 'ACTIVE' as 'ACTIVE' | 'PENDING_VERIFICATION' | 'POSSIBLE_DUPLICATE', reason: '' });
const history = ref<PatientDemographicVersionWire[]>([]);
const busy = ref(''); const notice = ref('');
const highRisk = computed(() => candidates.value.filter((item) => item.match_score >= 0.8).length);

function date(value: string) { return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short', hour12: false }).format(new Date(value)); }
async function detect() {
  if (busy.value || !detectForm.patientA || !detectForm.patientB) return;
  busy.value = 'detect'; notice.value = '';
  try { await detectPatientMatchCandidate({ patient_a_id: detectForm.patientA, patient_b_id: detectForm.patientB }); notice.value = '候选已按 MPI-RULES-1 重新评分并进入人工复核队列。'; await query.refetch(); }
  catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}
async function openHistory(patientId: string) {
  busy.value = `history-${patientId}`; notice.value = '';
  try {
    history.value = await loadPatientDemographicVersions(patientId);
    const current = history.value[0];
    if (current) Object.assign(editForm, { patientId, displayName: current.display_name, sexCode: current.sex_code, birthDate: current.birth_date, status: current.patient_status === 'MERGED' ? 'ACTIVE' : current.patient_status, reason: '' });
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}
async function correct() {
  const current = history.value[0];
  if (!current || !editForm.reason.trim() || busy.value) return;
  busy.value = 'correct'; notice.value = '';
  try {
    await correctPatientDemographics(editForm.patientId, { expected_row_version: current.patient_row_version, display_name: editForm.displayName, sex_code: editForm.sexCode, birth_date: editForm.birthDate, status: editForm.status, reason: editForm.reason });
    notice.value = '人口学信息已生成不可篡改的新版本；旧值仍完整保留。'; await openHistory(editForm.patientId);
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}
</script>

<template>
  <section data-page-root class="content admin-content vue-native-page mpi-page">
    <div class="page-heading admin-heading"><div><p class="eyebrow">病历中心 / 患者主索引</p><h1>患者主索引与身份核验</h1><p>统一处理相似患者、待核验身份、人口学纠错和重复建档；任何候选都必须人工确认，不以算法分数直接合并患者。</p></div><div class="toolbar-actions"><RouterLink class="button secondary" to="/patient-merge">合并与撤销</RouterLink><RouterLink class="button secondary" to="/patient-timeline">纵向时间轴</RouterLink></div></div>
    <section v-if="patientSearchTerm" class="admin-panel" aria-label="患者搜索结果">
      <header><div><h2>患者搜索结果</h2><p>关键词：{{ patientSearchTerm }} · 最多显示 20 条授权范围内的匹配记录</p></div></header>
      <ClinicalPageState v-if="patientSearch.isPending.value" kind="loading" message="正在搜索患者" />
      <ClinicalPageState v-else-if="patientSearchIssue" kind="error" :code="patientSearchIssue.code" :message="patientSearchIssue.message" @retry="patientSearch.refetch()" />
      <div v-else class="admin-table-wrap"><table class="admin-table mpi-table"><thead><tr><th>姓名</th><th>患者号</th><th>出生日期</th><th>操作</th></tr></thead><tbody>
        <tr v-for="patient in patientSearch.data.value ?? []" :key="patient.patient_id"><td><strong>{{ patient.display_name }}</strong></td><td>…{{ patient.patient_id.slice(-8) }}</td><td>{{ patient.birth_date }}</td><td><button class="button secondary" :disabled="Boolean(busy)" @click="openHistory(patient.patient_id)">核验身份</button></td></tr>
        <tr v-if="!patientSearch.data.value?.length"><td colspan="4" class="mpi-empty">未找到匹配患者，请核对姓名或标识后重试。</td></tr>
      </tbody></table></div>
    </section>
    <ClinicalPageState v-if="query.isPending.value" kind="loading" message="正在读取 MPI 人工复核队列" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="query.refetch()" />
    <template v-else>
      <section class="admin-metrics mpi-metrics"><article><span>开放候选</span><strong>{{ candidates.length }}</strong><small>等待人工判定</small></article><article><span>高相似度</span><strong>{{ highRisk }}</strong><small>评分 ≥ 0.80</small></article><article><span>自动合并</span><strong>0</strong><small>制度性禁止</small></article><article><span>算法版本</span><strong class="metric-code">R1</strong><small>MPI-RULES-1</small></article></section>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>
      <div class="mpi-layout"><section class="admin-panel"><header><div><h2>重复身份候选队列</h2><p>算法只呈现证据；姓名、出生日期、性别和标识符信号逐项可解释。</p></div><button class="button secondary" @click="query.refetch()">刷新</button></header><div class="admin-table-wrap"><table class="admin-table mpi-table"><thead><tr><th>候选患者 A</th><th>候选患者 B</th><th>评分 / 证据</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="item in candidates" :key="item.candidate_id"><td><strong>{{ item.patient_a_name }}</strong><small>{{ item.patient_a_id }}</small></td><td><strong>{{ item.patient_b_name }}</strong><small>{{ item.patient_b_id }}</small></td><td><strong>{{ Math.round(item.match_score * 100) }}%</strong><small>{{ item.match_signals.same_normalized_name ? '姓名一致 · ' : '' }}{{ item.match_signals.same_birth_date ? '生日一致 · ' : '' }}{{ item.match_signals.same_sex_code ? '性别一致' : '' }}</small></td><td><span class="admin-status active">{{ item.status }}</span><small>{{ date(item.detected_at) }} · v{{ item.row_version }}</small></td><td><div class="mpi-actions"><button @click="openHistory(item.patient_a_id)">核验 A</button><button @click="openHistory(item.patient_b_id)">核验 B</button><RouterLink :to="`/patient-merge?candidate=${item.candidate_id}`">创建合并案</RouterLink></div></td></tr><tr v-if="!candidates.length"><td colspan="5" class="mpi-empty">当前没有开放的重复患者候选。</td></tr></tbody></table></div></section>
        <div class="mpi-side"><section class="admin-panel admin-form-panel"><header><div><h2>发起候选检测</h2><p>用于迁移、注册和人工排查发现的两份档案。</p></div></header><form class="admin-form" @submit.prevent="detect"><label><span>患者 A ID</span><input v-model="detectForm.patientA" required /></label><label><span>患者 B ID</span><input v-model="detectForm.patientB" required /></label><button class="button primary full" :disabled="Boolean(busy)">{{ busy === 'detect' ? '正在评分…' : '计算并登记候选' }}</button></form></section>
          <section v-if="history.length" class="admin-panel mpi-history"><header><div><h2>身份纠错与版本</h2><p>患者 {{ editForm.patientId }}</p></div><span>当前行 v{{ history[0]?.patient_row_version }}</span></header><form class="admin-form" @submit.prevent="correct"><label><span>姓名</span><input v-model="editForm.displayName" required /></label><div class="split-fields"><label><span>性别码</span><input v-model="editForm.sexCode" required /></label><label><span>出生日期</span><input v-model="editForm.birthDate" type="date" required /></label></div><label><span>核验状态</span><select v-model="editForm.status"><option value="ACTIVE">已核验有效</option><option value="PENDING_VERIFICATION">待核验</option><option value="POSSIBLE_DUPLICATE">疑似重复</option></select></label><label><span>纠错依据</span><textarea v-model="editForm.reason" required minlength="4" rows="2" placeholder="证件、原系统或患者本人核验依据"></textarea></label><button class="button primary full" :disabled="Boolean(busy)">保存为新版本</button></form><ol class="identity-version-list"><li v-for="version in history" :key="version.demographic_version_id"><strong>v{{ version.version_no }} · {{ version.display_name }}</strong><span>{{ version.change_type }} · {{ version.patient_status }}</span><small>{{ version.change_reason }} · {{ date(version.created_at) }}</small></li></ol></section></div></div>
    </template>
  </section>
</template>
