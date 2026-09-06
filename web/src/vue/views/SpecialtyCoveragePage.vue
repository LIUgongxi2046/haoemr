<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref, watch } from 'vue';
import { RouterLink } from 'vue-router';
import type { DepartmentSupportAssessmentWire } from '../../generated/contracts';
import { createSpecialtySupportAssessment, deleteSpecialtySupportAssessment, issueSpecialtySupportLease, loadSpecialtySupportAssessments, updateSpecialtySupportAssessment } from '../../clinical-api';
import { toClinicalIssue } from '../clinical-error';
import BusinessActionDialog from '../components/BusinessActionDialog.vue';

type CoverageDialog = null | 'create' | 'edit' | 'delete';
const leaseQuery = useQuery({ queryKey: ['specialty', 'coverage', 'lease'], queryFn: issueSpecialtySupportLease, retry: false, staleTime: 300000, gcTime: 0 });
const assessmentsQuery = useQuery({ queryKey: ['specialty', 'coverage', 'assessments'], queryFn: () => loadSpecialtySupportAssessments(leaseQuery.data.value!), enabled: () => Boolean(leaseQuery.data.value), retry: false });
const assessments = computed(() => assessmentsQuery.data.value ?? []);
const issue = computed(() => (leaseQuery.error.value ?? assessmentsQuery.error.value) ? toClinicalIssue(leaseQuery.error.value ?? assessmentsQuery.error.value) : null);
const selected = ref<DepartmentSupportAssessmentWire | null>(null);
const filter = ref('ALL');
const busy = ref(false);
const notice = ref('');
const coverageDialog = ref<CoverageDialog>(null);
const form = reactive({ department_id: '', clinical_scope_code: '', support_level: 'PACK_PENDING', pack_release_id: '', evidence_bundle_hash: '', gates: '', expires_at: '' });
const dimensions = ['公共内核', '专业工作台', '专科字段', '专科流程', '设备接口', '质量控制', '恢复演练', '迁移证据'];
const scopeName: Record<string, string> = {
  GENERAL_MEDICINE: '全科医学', CARDIOLOGY: '心血管内科', PEDIATRICS: '儿科',
  MENTAL_HEALTH: '精神心理科', EMERGENCY_MEDICINE: '急诊医学科', NEUROLOGY: '神经内科',
  GENERAL_SURGERY: '普通外科', LABORATORY_MEDICINE: '医学检验科', RADIOLOGY: '医学影像科',
  OBSTETRICS: '妇产科', ONCOLOGY: '肿瘤科', CRITICAL_CARE: '重症医学科',
  ANESTHESIOLOGY: '麻醉科', PATHOLOGY: '病理科', PHARMACY: '药学部', REHABILITATION: '康复医学科',
};
const filtered = computed(() => filter.value === 'ALL' ? assessments.value : assessments.value.filter(item => item.support_level === filter.value));
function levelLabel(level: string) { return ({ GENERAL_AVAILABLE: '通用可用', BASIC_CLOSED_LOOP: '基础闭环', PACK_PENDING: '能力包待配', UNSUPPORTED: '暂不支持' } as Record<string, string>)[level] ?? level; }
function dimensionChecks(item: DepartmentSupportAssessmentWire) {
  const gates = item.missing_safety_gates;
  const positive = ['GENERAL_AVAILABLE', 'BASIC_CLOSED_LOOP'].includes(item.support_level);
  const expiryValid = Boolean(item.expires_at && new Date(item.expires_at).getTime() > Date.now());
  return [
    { name: dimensions[0], passed: item.support_level !== 'UNSUPPORTED' },
    { name: dimensions[1], passed: positive },
    { name: dimensions[2], passed: /^[A-Z][A-Z0-9_-]{1,63}$/.test(item.clinical_scope_code) },
    { name: dimensions[3], passed: Boolean(item.pack_release_id) },
    { name: dimensions[4], passed: !gates.some(gate => gate.includes('DEVICE') || gate.includes('INTERFACE')) },
    { name: dimensions[5], passed: gates.length === 0 },
    { name: dimensions[6], passed: !gates.some(gate => gate.includes('RECOVERY')) },
    { name: dimensions[7], passed: Boolean(item.evidence_bundle_hash?.match(/^[a-f0-9]{64}$/)) && expiryValid && !gates.some(gate => gate.includes('MIGRATION')) },
  ];
}
function coverage(item: DepartmentSupportAssessmentWire) {
  const checks = dimensionChecks(item);
  return Math.round(checks.filter(check => check.passed).length / checks.length * 100);
}
function select(item: DepartmentSupportAssessmentWire) { selected.value = item; notice.value = ''; }
function fillForm(item?: DepartmentSupportAssessmentWire | null) {
  form.department_id = item?.department_id ?? assessments.value[0]?.department_id ?? '';
  form.clinical_scope_code = item?.clinical_scope_code ?? '';
  form.support_level = item?.support_level ?? 'PACK_PENDING';
  form.pack_release_id = item?.pack_release_id ?? '';
  form.evidence_bundle_hash = item?.evidence_bundle_hash ?? '';
  form.gates = item?.missing_safety_gates.join('\n') ?? 'SPECIALTY_PACK_NOT_VERIFIED\nSYNTHETIC_CASE_REPLAY_PENDING';
  form.expires_at = item?.expires_at ? new Date(item.expires_at).toISOString().slice(0, 16) : '';
}
function openCreate() { fillForm(); coverageDialog.value = 'create'; }
function openEdit() { if (selected.value) { fillForm(selected.value); coverageDialog.value = 'edit'; } }
function openDelete() { if (selected.value) coverageDialog.value = 'delete'; }
watch(assessments, value => {
  if (!value.length) { selected.value = null; return; }
  selected.value = value.find(item => item.department_support_assessment_id === selected.value?.department_support_assessment_id) ?? value[0];
}, { immediate: true });

function requestPayload() {
  return {
    support_level: form.support_level as 'GENERAL_AVAILABLE' | 'BASIC_CLOSED_LOOP' | 'PACK_PENDING' | 'UNSUPPORTED',
    pack_release_id: form.pack_release_id.trim() || null,
    evidence_bundle_hash: form.evidence_bundle_hash.trim() || null,
    missing_safety_gates: form.gates.split(/[\n,，]+/).map(value => value.trim()).filter(Boolean),
    expires_at: form.expires_at ? new Date(form.expires_at).toISOString() : null,
  };
}
async function confirmDialog() {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || !coverageDialog.value) return;
  busy.value = true; notice.value = '';
  try {
    if (coverageDialog.value === 'create') {
      const result = await createSpecialtySupportAssessment(lease, form.department_id.trim(), form.clinical_scope_code.trim().toUpperCase(), requestPayload());
      await assessmentsQuery.refetch(); selected.value = result; notice.value = '科室支持声明已新建，并参与运行时支持等级判定。';
    } else if (coverageDialog.value === 'edit' && selected.value) {
      const result = await updateSpecialtySupportAssessment(lease, selected.value, requestPayload());
      await assessmentsQuery.refetch(); selected.value = result; notice.value = `支持声明已更新并重新计算安全门 · v${result.row_version}`;
    } else if (coverageDialog.value === 'delete' && selected.value) {
      await deleteSpecialtySupportAssessment(lease, selected.value);
      const removedName = scopeName[selected.value.clinical_scope_code] ?? selected.value.clinical_scope_code;
      selected.value = null; await assessmentsQuery.refetch(); notice.value = `${removedName} 支持声明已删除，相关专科入口将不再通过支持门禁。`;
    }
    coverageDialog.value = null;
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = false; }
}
const evidenceChecks = computed(() => selected.value ? [
  { name: '公共内核与安全门', passed: selected.value.support_level !== 'UNSUPPORTED' },
  { name: '专科包兼容性', passed: Boolean(selected.value.pack_release_id) },
  { name: '合成病例回放', passed: selected.value.missing_safety_gates.length === 0 },
  { name: '证据哈希可追溯', passed: Boolean(selected.value.evidence_bundle_hash?.match(/^[a-f0-9]{64}$/)) },
  { name: '恢复与迁移演练', passed: !selected.value.missing_safety_gates.some(gate => gate.includes('RECOVERY') || gate.includes('MIGRATION')) },
] : []);
const selectedDimensions = computed(() => selected.value ? dimensionChecks(selected.value) : []);
const dialogTitle = computed(() => coverageDialog.value === 'create' ? '新建科室支持声明' : coverageDialog.value === 'edit' ? `编辑${selected.value ? (scopeName[selected.value.clinical_scope_code] ?? selected.value.clinical_scope_code) : ''}支持声明` : '删除科室支持声明');
</script>

<template>
  <section data-page-root class="content vue-native-page specialty-page">
    <div class="page-head"><div class="page-title"><p class="eyebrow">业务配置 / 科室适配</p><h1>科室与专科适配工作台</h1><p>按科室核对公共内核、专业工作台、字段、流程、设备、质控、恢复、迁移与证据，阻断项自动降级。</p></div><div class="head-actions"><details v-if="selected" class="config-technical-details"><summary>技术详情</summary><RouterLink class="btn" :to="`/business-config/specialty-coverage/${selected.department_support_assessment_id}`">查看配置详情</RouterLink></details><button class="btn" @click="assessmentsQuery.refetch()">刷新证据</button><button class="btn" :disabled="!selected" @click="openEdit">编辑</button><button class="btn danger" :disabled="!selected" @click="openDelete">删除</button><button class="btn primary" @click="openCreate">新建支持声明</button></div></div>
    <div v-if="issue" class="specialty-notice error">{{ issue.code }}：{{ issue.message }}。数据库不可用时不展示伪造回退数据。</div><div v-if="notice" class="specialty-notice">{{ notice }}</div>
    <div class="coverage-summary"><article><span>已评估专科</span><b>{{ assessments.length }}</b><small>数据库真实记录</small></article><article><span>通用 / 闭环</span><b>{{ assessments.filter(item=>['GENERAL_AVAILABLE','BASIC_CLOSED_LOOP'].includes(item.support_level)).length }}</b><small>影响专科入口门禁</small></article><article><span>待补安全门</span><b>{{ assessments.reduce((count,item)=>count+item.missing_safety_gates.length,0) }}</b><small>自动阻断升级</small></article><article><span>证据即将到期</span><b>{{ assessments.filter(item=>item.expires_at&&new Date(item.expires_at).getTime()-Date.now()<120*86400000).length }}</b><small>120 天内</small></article></div>
    <div v-if="assessments.length" class="coverage-shell"><aside class="scope-list"><header><b>科室 / 专科范围</b><select v-model="filter" aria-label="专科支持等级"><option value="ALL">全部等级</option><option value="GENERAL_AVAILABLE">通用可用</option><option value="BASIC_CLOSED_LOOP">基础闭环</option><option value="PACK_PENDING">能力包待配</option><option value="UNSUPPORTED">暂不支持</option></select></header><button v-for="item in filtered" :key="item.department_support_assessment_id" :class="{active:selected?.department_support_assessment_id===item.department_support_assessment_id}" @click="select(item)"><span><b>{{ scopeName[item.clinical_scope_code]??item.clinical_scope_code }}</b><small>{{ item.clinical_scope_code }}</small></span><em :class="item.support_level.toLowerCase()">{{ levelLabel(item.support_level) }}</em><i><strong>{{ coverage(item) }}%</strong><small>{{ item.missing_safety_gates.length }} 个缺口</small></i></button></aside>
      <section v-if="selected" class="coverage-main"><section class="matrix"><header><div><h2>{{ scopeName[selected.clinical_scope_code]??selected.clinical_scope_code }} · 覆盖矩阵</h2><p>数据库版本 v{{ selected.row_version }} · 评估时间 {{ new Date(selected.assessed_at).toLocaleString('zh-CN',{hour12:false}) }} · 百分比按当前证据字段实时计算</p></div><span :class="selected.support_level.toLowerCase()">{{ levelLabel(selected.support_level) }}</span></header><div class="dimension-grid"><article v-for="(dimension,index) in selectedDimensions" :key="dimension.name" :class="{gap:!dimension.passed}"><span>{{ String(index+1).padStart(2,'0') }}</span><b>{{ dimension.name }}</b><em>{{ dimension.passed?'证据已核验':'待补齐' }}</em><small>{{ index===0?'平台公共内核':index===1?'科室角色工作台':index===2?'字段和值集':index===3?'路径与任务':index===4?'设备/LIS/PACS':index===5?'质控指标与门禁':index===6?'恢复演练报告':'迁移与联合签署' }}</small></article></div></section>
        <div class="coverage-lower"><section class="evidence-panel"><h3>证据与发布门禁</h3><div v-for="check in evidenceChecks" :key="check.name" class="evidence-row"><span :class="{passed:check.passed}">{{ check.passed?'通过':'阻断' }}</span><b>{{ check.name }}</b></div><h3>缺失安全门</h3><ul v-if="selected.missing_safety_gates.length"><li v-for="gate in selected.missing_safety_gates" :key="gate">{{ gate }}</li></ul><p v-else class="ok">无缺失安全门</p></section><section class="runtime-panel"><h3>运行时流程影响</h3><div class="impact-rating"><span>当前支持等级</span><b>{{ levelLabel(selected.support_level) }}</b></div><p>专科页面和流程入口读取该等级；证据过期或安全门未清零时自动降级并阻断发布。</p><p>删除声明后，此科室专科范围不再进入有效支持集合，但审计事件保留。</p><div class="panel-actions"><button class="btn" @click="openEdit">编辑声明</button><button class="btn danger" @click="openDelete">删除声明</button></div></section></div>
      </section></div>
    <section v-else class="coverage-empty"><b>暂无科室支持声明</b><p>数据库中没有可用记录。请新建真实科室范围，不再使用前端硬编码演示数据。</p><button class="btn primary" @click="openCreate">新建第一条声明</button></section>

    <BusinessActionDialog :open="Boolean(coverageDialog)" :title="dialogTitle" :description="coverageDialog==='delete'?'删除后立即退出科室支持门禁，历史审计证据继续保留。':'声明会经过科室、角色、能力包、证据哈希和有效期校验。'" :confirm-label="coverageDialog==='delete'?'确认删除':'验证并保存'" :danger="coverageDialog==='delete'" :busy="busy" width="wide" @cancel="coverageDialog=null" @confirm="confirmDialog">
      <p v-if="coverageDialog==='delete'" class="dialog-warning">确认删除“{{ selected ? (scopeName[selected.clinical_scope_code] ?? selected.clinical_scope_code) : '' }}”的支持声明？此操作会生成审计和 Outbox 事件。</p>
      <template v-else><div class="dialog-grid"><label>科室 ID<input v-model="form.department_id" required :disabled="coverageDialog==='edit'" placeholder="有效 clinical_department UUID" /></label><label>专科范围编码<input v-model="form.clinical_scope_code" required :disabled="coverageDialog==='edit'" placeholder="如 CARDIOLOGY" /></label><label>支持等级<select v-model="form.support_level"><option value="GENERAL_AVAILABLE">通用可用</option><option value="BASIC_CLOSED_LOOP">基础闭环</option><option value="PACK_PENDING">能力包待配</option><option value="UNSUPPORTED">暂不支持</option></select></label><label>专科能力包发布 ID<input v-model="form.pack_release_id" placeholder="已激活 specialty_pack_release UUID" /></label><label>证据包 SHA-256<input v-model="form.evidence_bundle_hash" maxlength="64" placeholder="64 位小写 SHA-256" /></label><label>证据有效期<input v-model="form.expires_at" type="datetime-local" /></label></div><label>缺失安全门<textarea v-model="form.gates" rows="4" placeholder="每行一个安全门编码" /></label><p class="dialog-warning">正向声明必须具备有效证据哈希、未来有效期、兼容的 ACTIVE 专科包且缺口为零。</p></template>
    </BusinessActionDialog>
  </section>
</template>

<style scoped>
.config-technical-details summary { cursor: pointer; color: #42627c; }.config-technical-details[open] > summary { margin-bottom: 8px; }
.specialty-page{color:#17283a;min-width:0}.page-head{display:flex;align-items:flex-start;justify-content:space-between;gap:20px;margin-bottom:14px}.page-title{min-width:0}.page-title h1{margin:2px 0 4px}.page-title p:last-child{margin:0;color:#647488;font-size:12px}.eyebrow{margin:0;color:#1769aa;font-size:10px;font-weight:700}.head-actions,.panel-actions{display:flex;flex-wrap:wrap;gap:8px}.btn{padding:9px 13px;border:1px solid #cbd7df;border-radius:7px;background:#fff;color:#294052}.btn.primary{border-color:#1769aa;background:#1769aa;color:#fff}.btn.danger{color:#a43131}.specialty-notice{margin-bottom:12px;padding:11px 13px;border:1px solid #b9d8ed;background:#eef7fd;border-radius:8px;color:#19567e}.specialty-notice.error{border-color:#efbcbc;background:#fff1f1;color:#8d2c2c}.coverage-summary{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;margin-bottom:12px}.coverage-summary article{display:grid;gap:4px;padding:12px;border:1px solid #dce5ec;border-radius:8px;background:#fff;min-width:0}.coverage-summary span,.coverage-summary small{font-size:10px;color:#647488}.coverage-summary b{font-size:23px;color:#1769aa}.coverage-shell{display:grid;grid-template-columns:230px minmax(0,1fr);height:650px;border:1px solid #dce5ec;border-radius:10px;overflow:hidden;background:#fff}.scope-list{padding:12px;background:#f7f9fb;border-right:1px solid #dce5ec;display:flex;flex-direction:column;gap:8px;overflow:auto;overscroll-behavior:contain}.scope-list header{display:grid;gap:8px;position:sticky;z-index:1;top:-12px;padding:12px 0 8px;background:#f7f9fb}.scope-list select{padding:8px;border:1px solid #ccd7df;border-radius:6px}.scope-list>button{display:grid;gap:7px;padding:10px;border:1px solid transparent;border-radius:8px;background:none;text-align:left;color:inherit}.scope-list>button.active{border-color:#a6cae4;background:#fff}.scope-list>button>span{display:grid}.scope-list small{color:#647488;font-size:9px}.scope-list em,.matrix header>span{justify-self:start;padding:4px 8px;border-radius:9px;font-size:9px;font-style:normal;background:#edf2f6}.scope-list em.general_available,.matrix .general_available{background:#e8f7ef;color:#267249}.scope-list em.basic_closed_loop,.matrix .basic_closed_loop{background:#e9f3fb;color:#1769aa}.scope-list em.pack_pending,.matrix .pack_pending{background:#fff3df;color:#935c10}.scope-list em.unsupported,.matrix .unsupported{background:#fdeaea;color:#a43131}.scope-list i{display:flex;justify-content:space-between;font-style:normal}.coverage-main{min-width:0;background:#fafbfd;padding:14px;overflow:auto}.matrix,.evidence-panel,.runtime-panel{border:1px solid #dce5ec;border-radius:9px;background:#fff;padding:14px}.matrix>header{display:flex;justify-content:space-between;align-items:flex-start;gap:14px}.matrix h2{margin:0;font-size:16px}.matrix header p{margin:5px 0;color:#647488;font-size:10px}.dimension-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:9px;margin-top:12px}.dimension-grid article{display:grid;gap:5px;padding:10px;border:1px solid #c2dfcf;border-radius:7px;background:#f2faf5;min-width:0}.dimension-grid article.gap{border-color:#edc4c4;background:#fff3f3}.dimension-grid span{font-size:9px;color:#647488}.dimension-grid b{font-size:11px}.dimension-grid em{font-size:9px;color:#267249;font-style:normal}.dimension-grid .gap em{color:#a43131}.dimension-grid small{font-size:9px;color:#647488}.coverage-lower{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-top:12px}.coverage-lower h3{margin:0 0 10px;font-size:13px}.evidence-row{display:grid;grid-template-columns:42px minmax(0,1fr);gap:9px;padding:7px 0;border-bottom:1px solid #edf1f4}.evidence-row span{padding:3px 5px;border-radius:7px;background:#fdeaea;color:#a43131;font-size:9px;text-align:center}.evidence-row span.passed{background:#e8f7ef;color:#267249}.evidence-row b{font-size:10px}.evidence-panel ul{padding-left:18px;color:#a43131;font-size:10px}.ok{color:#267249;font-size:10px}.runtime-panel{display:grid;align-content:start;gap:11px}.runtime-panel p{margin:0;color:#647488;font-size:11px;line-height:1.6}.impact-rating{display:flex;justify-content:space-between;gap:10px;padding:11px;border:1px solid #abd9bf;border-radius:7px;background:#edf9f2;color:#236a42}.coverage-empty{display:grid;justify-items:center;gap:10px;padding:60px 20px;border:1px dashed #cbd8e1;border-radius:10px;background:#fff;text-align:center}.coverage-empty p{margin:0;color:#647488;font-size:12px}@media(max-width:950px){.dimension-grid{grid-template-columns:1fr 1fr}.coverage-lower{grid-template-columns:1fr}}@media(max-width:700px){.page-head{display:grid}.coverage-summary{grid-template-columns:1fr 1fr}.coverage-shell{grid-template-columns:1fr;height:auto}.scope-list{max-height:250px;overflow:auto;border-right:0;border-bottom:1px solid #dce5ec}.coverage-main{overflow:visible}.dimension-grid{grid-template-columns:1fr}}
.coverage-shell{height:clamp(560px,calc(100vh - 250px),650px)}
.coverage-main,.matrix,.evidence-panel,.runtime-panel{padding:12px}
.matrix>header{gap:12px}
.dimension-grid{margin-top:10px}
.coverage-lower{gap:10px;margin-top:10px}
.runtime-panel{gap:10px}
.impact-rating{padding:10px}
@media(max-width:700px){.coverage-shell{height:auto}}
</style>
