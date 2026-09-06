<script setup lang="ts">
import { ref } from 'vue';
import type { EncounterWire, MedicalAgentRunWire, PatientSummaryWire } from '../../generated/contracts';

interface ActivePatientContext {
  patientId: string;
  encounterId: string;
  patientName: string;
  patientSummary: string;
  label: string;
  scene: string;
}

defineProps<{
  current: ActivePatientContext;
  defaults: ActivePatientContext[];
  results: PatientSummaryWire[];
  encounters: EncounterWire[];
  selectedPatientId: string;
  searching?: boolean;
  loadingEncounters?: boolean;
  notice?: string;
  compact?: boolean;
  history?: MedicalAgentRunWire[];
  historyLoading?: boolean;
}>();

const emit = defineEmits<{
  search: [query: string];
  'select-default': [context: ActivePatientContext];
  'select-patient': [patient: PatientSummaryWire];
  'select-encounter': [encounter: EncounterWire];
  'select-history': [run: MedicalAgentRunWire];
  unbind: [];
}>();

const query = ref('');
const encounterTypeLabel: Record<string, string> = { OUTPATIENT: '门诊', EMERGENCY: '急诊', INPATIENT: '住院' };
const runStateLabel: Record<string, string> = {
  QUEUED: '排队中', RUNNING: '进行中', WAITING_FOR_REVIEW: '待复核',
  COMPLETED: '已完成', PARTIAL: '部分完成', BLOCKED: '已阻断', FAILED: '失败', CANCELLED: '已取消',
};
function submit() { if (query.value.trim()) emit('search', query.value.trim()); }
function age(birthDate: string) { return Math.max(0, new Date().getFullYear() - new Date(birthDate).getFullYear()); }
function formatDate(value: string) { return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit' }).format(new Date(value)); }
function formatTime(value: string) { return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value)); }
</script>

<template>
  <aside class="eva-patient-picker" :class="{ compact }" aria-label="选择患者">
    <header><div><strong>选择患者</strong><span>搜索并选择本次就诊</span></div><b>{{ current.scene }}</b></header>
    <form role="search" @submit.prevent="submit"><input v-model="query" type="search" placeholder="姓名 / 病历号 / 证件号" aria-label="搜索患者" /><button :disabled="searching || !query.trim()">{{ searching ? '…' : '搜索' }}</button></form>

    <section class="eva-current-patient"><span>当前患者</span><div class="eva-current-patient-row"><i>{{ current.patientName.slice(0,1) }}</i><strong>{{ current.patientName }}</strong><b>{{ current.patientId ? '已绑定' : '未绑定' }}</b><button v-if="current.patientId" type="button" class="eva-unbind" @click="emit('unbind')">取消绑定</button></div><small>{{ current.patientSummary }} · {{ current.label }}</small></section>

    <div v-if="results.length" class="eva-search-results">
      <span>搜索结果</span>
      <button v-for="patient in results" :key="patient.patient_id" type="button" :class="{ selected: patient.patient_id === selectedPatientId }" @click="emit('select-patient', patient)"><i>{{ patient.display_name.slice(0,1) }}</i><span><b>{{ patient.display_name }}</b><small>{{ patient.sex_code === 'M' ? '男' : patient.sex_code === 'F' ? '女' : '未知' }} · {{ age(patient.birth_date) }}岁 · …{{ patient.patient_id.slice(-8) }}</small></span></button>
    </div>

    <div v-if="loadingEncounters" class="eva-patient-state">正在读取患者就诊…</div>
    <div v-else-if="encounters.length" class="eva-encounters"><span>选择就诊</span><button v-for="encounter in encounters" :key="encounter.encounter_id" type="button" :aria-pressed="encounter.encounter_id === current.encounterId" @click="emit('select-encounter', encounter)"><b>{{ encounterTypeLabel[encounter.encounter_type] }}</b><small>{{ formatDate(encounter.started_at) }} · {{ encounter.status === 'IN_PROGRESS' ? '诊疗中' : encounter.status === 'FINISHED' ? '已结束' : encounter.status }}</small></button></div>

    <div v-if="!results.length" class="eva-default-patients"><span>最近患者</span><button v-for="context in defaults" :key="context.encounterId" type="button" :aria-pressed="context.encounterId === current.encounterId" @click="emit('select-default', context)"><i>{{ context.patientName.slice(0,1) }}</i><span><b>{{ context.patientName }}</b><small>{{ context.patientSummary }} · {{ context.label }}</small></span></button></div>
    <p v-if="notice" class="eva-patient-notice" role="status">{{ notice }}</p>

    <section class="eva-run-history" aria-label="聊天记录">
      <header><span>聊天记录</span><small v-if="current.patientId">基于 {{ current.patientName }}</small><small v-else>绑定患者后显示</small></header>
      <div v-if="historyLoading" class="eva-patient-state">正在读取聊天记录…</div>
      <template v-else-if="history?.length">
        <button v-for="run in history" :key="run.run_id" type="button" @click="emit('select-history', run)"><span><b>{{ run.objective }}</b><small>{{ formatTime(run.created_at) }} · {{ runStateLabel[run.state] ?? run.state }}</small></span><i :class="run.state">{{ runStateLabel[run.state] ?? run.state }}</i></button>
      </template>
      <div v-else class="eva-patient-state">{{ current.patientId ? '该患者暂无医助聊天记录。' : '绑定患者后可查看其医助聊天记录。' }}</div>
    </section>
  </aside>
</template>

<style scoped>
.eva-patient-picker { display: grid; align-content: start; width: 248px; min-width: 0; height: 100%; overflow-y: auto; border-left: 1px solid #d8e3ef; background: #f8fafc; }
header { display: flex; align-items: center; justify-content: space-between; gap: 8px; min-height: 58px; padding: 11px 12px; border-bottom: 1px solid #d8e3ef; background: #fff; }
header > div { display: grid; gap: 3px; }
header strong { color: #263f58; font-size: 13px; }
header span { color: #738397; font-size: 9px; }
header > b { padding: 4px 7px; color: #087c75; border-radius: 999px; background: #e8f7f4; font-size: 8px; }
form { display: grid; grid-template-columns: minmax(0,1fr) auto; gap: 5px; padding: 10px; border-bottom: 1px solid #e1e8ef; }
form input { min-width: 0; height: 34px; padding: 0 9px; border: 1px solid #cad7e2; border-radius: 8px; outline: none; font-size: 9px; }
form input:focus { border-color: #4f91d5; box-shadow: 0 0 0 3px rgb(23 105 224 / 9%); }
form button { padding: 0 9px; color: #fff; border: 0; border-radius: 8px; background: #1769e0; font-size: 8px; cursor: pointer; }
.eva-current-patient { display: grid; gap: 4px; padding: 11px 12px; margin: 10px 10px 0; border: 1px solid #89b8e8; border-radius: 10px; background: #edf5ff; }
.eva-current-patient > span, .eva-search-results > span, .eva-encounters > span, .eva-default-patients > span { color: #718397; font-size: 8px; font-weight: 800; }
.eva-current-patient-row { display: flex; align-items: center; gap: 7px; min-width: 0; }
.eva-current-patient-row i { display: grid; place-items: center; width: 26px; height: 26px; flex: 0 0 26px; color: #fff; border-radius: 50%; background: #426d97; font-size: 10px; font-style: normal; font-weight: 800; }
.eva-current-patient-row strong { overflow: hidden; color: #244764; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.eva-current-patient-row b { flex: 0 0 auto; padding: 2px 7px; color: #0c7d68; border-radius: 999px; background: #dcf5ef; font-size: 7px; font-weight: 800; }
.eva-current-patient-row .eva-unbind { flex: 0 0 auto; padding: 2px 7px; margin-left: auto; color: #8a5a12; border: 1px solid #e3c58a; border-radius: 999px; background: #fff7e6; font-size: 7px; font-weight: 800; cursor: pointer; }
.eva-current-patient-row .eva-unbind:hover { border-color: #cfa24c; background: #fdefd0; }
.eva-current-patient small { color: #617a90; font-size: 8px; line-height: 1.45; }
.eva-search-results, .eva-default-patients, .eva-encounters { display: grid; gap: 6px; padding: 10px; }
.eva-search-results button, .eva-default-patients button { display: grid; grid-template-columns: 30px minmax(0,1fr); align-items: center; gap: 7px; padding: 8px; color: inherit; border: 1px solid #d7e2ec; border-radius: 9px; background: #fff; text-align: left; cursor: pointer; }
.eva-search-results button:hover, .eva-default-patients button:hover, .eva-search-results button.selected { border-color: #7caee0; background: #f1f7ff; }
.eva-search-results i, .eva-default-patients i { display: grid; place-items: center; width: 28px; height: 28px; color: #fff; border-radius: 50%; background: #426d97; font-size: 10px; font-style: normal; font-weight: 800; }
.eva-search-results button span, .eva-default-patients button span { display: grid; gap: 3px; min-width: 0; }
.eva-search-results b, .eva-default-patients b { overflow: hidden; color: #304a63; font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.eva-search-results small, .eva-default-patients small { color: #7a8998; font-size: 7px; line-height: 1.4; }
.eva-encounters { border-top: 1px solid #e3eaf1; border-bottom: 1px solid #e3eaf1; }
.eva-encounters button { display: flex; align-items: center; justify-content: space-between; gap: 7px; padding: 8px; color: inherit; border: 1px solid #d7e2ec; border-radius: 8px; background: #fff; cursor: pointer; }
.eva-encounters button[aria-pressed="true"] { border-color: #4f91d5; background: #edf5ff; }
.eva-encounters b { color: #365a7a; font-size: 9px; }
.eva-encounters small { color: #77899a; font-size: 7px; }
.eva-patient-state, .eva-patient-notice { padding: 10px 12px; color: #6f8193; font-size: 8px; line-height: 1.5; }
.eva-patient-notice { margin: 0 10px 10px; border-radius: 7px; background: #fff5db; }
.eva-run-history { display: grid; gap: 6px; margin-top: 8px; padding: 10px; border-top: 1px solid #e3eaf1; background: #f4f8fb; }
.eva-run-history > header { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; min-height: 0; padding: 0; border: 0; background: transparent; }
.eva-run-history > header span { color: #2c4b68; font-size: 11px; font-weight: 800; }
.eva-run-history > header small { color: #8294a6; font-size: 9px; }
.eva-run-history > button { display: grid; grid-template-columns: minmax(0,1fr) auto; align-items: center; gap: 6px; padding: 8px; color: inherit; border: 1px solid #d9e4ee; border-radius: 9px; background: #fff; text-align: left; cursor: pointer; }
.eva-run-history > button:hover { border-color: #7caee0; background: #f1f7ff; }
.eva-run-history > button span { display: grid; gap: 3px; min-width: 0; }
.eva-run-history > button b { overflow: hidden; color: #344e68; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.eva-run-history > button small { color: #7a8998; font-size: 10px; }
.eva-run-history > button i { padding: 2px 6px; color: #7a8998; border-radius: 999px; background: #eef2f6; font-size: 9px; font-style: normal; white-space: nowrap; }
.eva-run-history > button i.COMPLETED, .eva-run-history > button i.WAITING_FOR_REVIEW { color: #0c7d68; background: #dcf5ef; }
.eva-run-history > button i.FAILED, .eva-run-history > button i.BLOCKED { color: #b4232f; background: #fdecec; }
.eva-run-history > button i.RUNNING { color: #1769e0; background: #e7efff; }
.compact { width: 220px; }
@media (max-width: 980px) { .eva-patient-picker, .eva-patient-picker.compact { width: 100%; height: auto; max-height: 330px; border-left: 0; border-top: 1px solid #d8e3ef; } }
</style>
