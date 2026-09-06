<script setup lang="ts">
import EvaMessageContent from './EvaMessageContent.vue';
import { ref, watch } from 'vue';
import AgentCandidateReview from './AgentCandidateReview.vue';
import { createMedicalAgentRun, getMedicalAgentRun, issueMedicalAgentRunLease } from '../../api/medical-agents';
import type { MedicalAgentRunWire } from '../../generated/contracts';
import { toClinicalIssue } from '../clinical-error';
import { medicalAgentRunStateLabel, presentMedicalAgentResult } from '../medical-agent-run-presenter';

type ContextScope = 'RECORDS' | 'ORDERS' | 'RESULTS' | 'TASKS' | 'ATTACHMENTS' | 'CONFIGURATION';
type AuthorizationLevel = 'READ_ONLY' | 'STANDARD' | 'EXTENDED';
type TargetType = 'ENCOUNTER' | 'DOCUMENT' | 'RESULT' | 'TASK' | 'CARE_PLAN';

const props = withDefaults(defineProps<{
  agentCode: string;
  stageCode: string;
  objective: string;
  patientId: string | null;
  encounterId: string | null;
  targetType?: TargetType | null;
  targetId?: string | null;
  title?: string;
  contextScopes?: ContextScope[];
  authorizationLevel?: AuthorizationLevel;
  sourceRoute?: string | null;
  allowDocumentAdoption?: boolean;
}>(), {
  targetType: null,
  targetId: null,
  title: '医助候选',
  contextScopes: () => ['RECORDS', 'ORDERS', 'RESULTS', 'TASKS', 'CONFIGURATION'],
  authorizationLevel: 'READ_ONLY',
  sourceRoute: null,
});

const emit = defineEmits<{ settled: [run: MedicalAgentRunWire]; 'adopt-draft': [sections: Record<string, string>, run: MedicalAgentRunWire] }>();

const busy = ref(false);
const run = ref<MedicalAgentRunWire | null>(null);
const notice = ref('');
let generation = 0;
watch(() => [props.patientId, props.encounterId, props.targetId, props.agentCode, props.stageCode], () => { generation += 1; run.value = null; busy.value = false; notice.value = ''; });

const terminalStates = new Set<MedicalAgentRunWire['state']>([
  'WAITING_FOR_REVIEW', 'COMPLETED', 'PARTIAL', 'BLOCKED', 'FAILED', 'CANCELLED',
]);
const reviewableCandidateState: MedicalAgentRunWire['state'] = 'WAITING_FOR_REVIEW';

async function runAgent() {
  if (busy.value) return null;
  const currentGeneration = ++generation;
  busy.value = true;
  notice.value = '';
  try {
    const lease = await issueMedicalAgentRunLease(props.patientId, props.encounterId);
    let current = await createMedicalAgentRun(lease, {
      patientId: props.patientId,
      encounterId: props.encounterId,
      mainAgentCode: props.agentCode,
      stageCode: props.stageCode,
      sourceRoute: props.sourceRoute,
      targetType: props.targetType,
      targetId: props.targetId,
      objective: props.objective,
      authorizationLevel: props.authorizationLevel,
      contextScopes: props.contextScopes,
    });
    if (currentGeneration !== generation) return null;
    run.value = current;
    for (let poll = 0; poll < 90 && !terminalStates.has(current.state); poll += 1) {
      await new Promise((resolve) => window.setTimeout(resolve, 800));
      current = await getMedicalAgentRun(lease, props.patientId, props.encounterId, current.run_id);
      if (currentGeneration !== generation) return null;
    run.value = current;
    }
    emit('settled', current);
    notice.value = current.state === reviewableCandidateState
      ? 'AI 候选已就绪，仅供人工复核，不会自动写入业务终态。'
      : terminalStates.has(current.state)
        ? 'AI 复核未形成可采用候选；运行记录已保存，请按下方原因转人工处理或修复后重试。'
        : 'AI 复核仍在后台运行，运行记录已保存。';
    return current;
  } catch (error) {
    if (currentGeneration !== generation) return null;
    const next = toClinicalIssue(error);
    notice.value = `${next.code}：${next.message}`;
    return null;
  } finally {
    if (currentGeneration === generation) busy.value = false;
  }
}

defineExpose({ runAgent });
</script>

<template>
  <section class="agent-inline-review card">
    <header class="agent-inline-head">
      <div><h2>{{ title }}</h2><p>医助建议仅供人工复核，不自动写入病历、医嘱、结果或任务。</p></div>
      <button class="btn" type="button" :disabled="busy" @click="runAgent">{{ busy ? '医助复核中…' : '启动医助复核' }}</button>
    </header>
    <p v-if="notice" class="inline-notice" role="status">{{ notice }}</p>
    <div v-if="run" class="agent-inline-body">
      <div class="agent-inline-state"><span class="status" :class="['FAILED', 'BLOCKED'].includes(run.state) ? 'red' : terminalStates.has(run.state) ? 'green' : 'amber'">{{ medicalAgentRunStateLabel(run.state) }}</span><small>运行编号 …{{ run.run_id.slice(-8) }} · {{ run.target_type }} / {{ run.target_id }}</small></div>
      <div class="notice info"><div class="notice-title">{{ run.state === reviewableCandidateState ? '仅供人工复核候选' : '运行记录（未形成可采用候选）' }}</div><EvaMessageContent :text="presentMedicalAgentResult(run, 'markdown')" :processing="busy && !terminalStates.has(run.state)" /></div>
      <AgentCandidateReview :run="run" :allow-document-adoption="allowDocumentAdoption" @adopt-draft="(sections, candidate) => emit('adopt-draft', sections, candidate)" />
      <slot name="actions" :run="run" />
    </div>
    <EvaMessageContent v-else-if="busy" text="" processing />
    <div v-else class="agent-inline-empty">尚未运行。点击「运行 AI 复核」生成针对当前对象的候选建议。</div>
  </section>
</template>

<style scoped>
.agent-inline-review { overflow: hidden; }
.agent-inline-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 14px 16px; border-bottom: 1px solid #e2e9f1; background: #f8fbff; }
.agent-inline-head h2 { margin: 0; color: #263b53; font-size: 14px; }
.agent-inline-head p { margin: 3px 0 0; color: #72849a; font-size: 11px; }
.agent-inline-head .btn { flex: 0 0 auto; }
.agent-inline-body { display: grid; gap: 10px; padding: 14px 16px; }
.agent-inline-state { display: flex; align-items: center; gap: 10px; }
.agent-inline-state small { color: #7a8998; font-size: 10px; }
.agent-inline-body .notice { margin: 0; font-size: 12px; line-height: 1.7; white-space: normal; }
.agent-inline-empty { padding: 18px 16px; color: #72849a; font-size: 12px; }
.inline-notice { margin: 0; padding: 10px 16px; color: #76500b; background: #fff5db; font-size: 11px; }
</style>
