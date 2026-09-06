<script setup lang="ts">
interface PatientContextOption {
  code: string;
  label: string;
  patientId: string | null;
  encounterId: string | null;
  patientName: string;
  patientSummary: string;
  scene: string;
}

defineProps<{
  contexts: PatientContextOption[];
  selectedCode: string;
  compact?: boolean;
}>();

const emit = defineEmits<{ select: [code: string] }>();

function shortId(value: string | null) {
  return value ? `…${value.slice(-8)}` : '机构级';
}
</script>

<template>
  <aside class="xiaonan-patient-panel" :class="{ compact }" aria-label="当前患者">
    <header><div><strong>选择患者</strong><span>任务始终绑定当前就诊</span></div><b>{{ contexts.length }}</b></header>
    <div class="xiaonan-patient-list">
      <button
        v-for="context in contexts"
        :key="context.code"
        type="button"
        :class="{ selected: context.code === selectedCode }"
        :aria-pressed="context.code === selectedCode"
        @click="emit('select', context.code)"
      >
        <span><i aria-hidden="true">{{ context.patientName.slice(0, 1) }}</i><strong>{{ context.patientName }}</strong><em>{{ context.scene }}</em></span>
        <small>{{ context.patientSummary }}</small>
        <code>{{ shortId(context.patientId) }} · {{ shortId(context.encounterId) }}</code>
      </button>
    </div>
    <section class="xiaonan-patient-scope">
      <span>当前可用数据</span>
      <div><b>病历与文书</b><b>医嘱与执行</b><b>检验检查</b><b>任务与随访</b></div>
      <p>切换患者后，未发送的内容将被清空。</p>
    </section>
  </aside>
</template>

<style scoped>
.xiaonan-patient-panel { display: grid; align-content: start; width: 244px; min-width: 0; height: 100%; overflow-y: auto; border-left: 1px solid #d8e3ef; background: #f8fafc; }
header { display: flex; align-items: center; justify-content: space-between; gap: 8px; min-height: 58px; padding: 11px 12px; border-bottom: 1px solid #d8e3ef; background: #fff; }
header > div { display: grid; gap: 3px; }
header strong { color: #263f58; font-size: 13px; }
header span { color: #738397; font-size: 9px; }
header > b { display: grid; place-items: center; width: 24px; height: 24px; color: #1769e0; border-radius: 999px; background: #e8f2ff; font-size: 9px; }
.xiaonan-patient-list { display: grid; gap: 8px; padding: 10px; }
.xiaonan-patient-list button { display: grid; gap: 7px; width: 100%; padding: 10px; color: inherit; border: 1px solid #d7e2ec; border-radius: 10px; background: #fff; text-align: left; cursor: pointer; }
.xiaonan-patient-list button:hover { border-color: #99bde5; background: #f5f9ff; }
.xiaonan-patient-list button.selected { border-color: #4f91d5; background: #edf5ff; box-shadow: 0 0 0 2px rgb(23 105 224 / 8%); }
.xiaonan-patient-list button > span { display: grid; grid-template-columns: 30px minmax(0,1fr) auto; align-items: center; gap: 7px; }
.xiaonan-patient-list i { display: grid; place-items: center; width: 30px; height: 30px; color: #fff; border-radius: 50%; background: #426d97; font-size: 11px; font-style: normal; font-weight: 800; }
.xiaonan-patient-list strong { overflow: hidden; color: #2c445c; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.xiaonan-patient-list em { padding: 3px 5px; color: #087c75; border-radius: 999px; background: #e8f7f4; font-size: 7px; font-style: normal; white-space: nowrap; }
.xiaonan-patient-list small { color: #60758b; font-size: 8px; line-height: 1.45; }
.xiaonan-patient-list code { color: #8795a4; font-size: 7px; overflow-wrap: anywhere; }
.xiaonan-patient-scope { display: grid; gap: 9px; padding: 12px; margin: 0 10px 12px; border: 1px solid #d5e1eb; border-radius: 10px; background: #fff; }
.xiaonan-patient-scope > span { color: #4b6278; font-size: 9px; font-weight: 800; }
.xiaonan-patient-scope > div { display: flex; flex-wrap: wrap; gap: 5px; }
.xiaonan-patient-scope b { padding: 4px 6px; color: #365b7d; border-radius: 5px; background: #edf3f8; font-size: 7px; }
.xiaonan-patient-scope p { margin: 0; color: #7b8997; font-size: 8px; line-height: 1.5; }
.xiaonan-patient-panel.compact { width: 214px; }
@media (max-width: 980px) {
  .xiaonan-patient-panel, .xiaonan-patient-panel.compact { width: 100%; height: auto; max-height: 310px; border-left: 0; border-top: 1px solid #d8e3ef; }
  .xiaonan-patient-list { grid-template-columns: repeat(3,minmax(0,1fr)); }
  .xiaonan-patient-scope { margin-top: 0; }
}
@media (max-width: 620px) {
  .xiaonan-patient-list { grid-template-columns: 1fr; }
}
</style>
