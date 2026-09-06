import { ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { getMedicalAgentRun, issueMedicalAgentRunLease } from '../../api/medical-agents';
import type { MedicalAgentRunWire } from '../../generated/contracts';
import { toClinicalIssue } from '../clinical-error';

export function useAgentDraftHandoff(context: () => { patientId: string | null; encounterId: string | null }) {
  const route = useRoute();
  const run = ref<MedicalAgentRunWire | null>(null);
  const notice = ref('');
  let generation = 0;
  watch(() => [route.query.agent_run, context().patientId, context().encounterId], async ([id, patient, encounter]) => {
    const current = ++generation;
    run.value = null; notice.value = '';
    if (typeof id !== 'string' || typeof patient !== 'string' || typeof encounter !== 'string') return;
    try {
      const lease = await issueMedicalAgentRunLease(patient, encounter);
      const result = await getMedicalAgentRun(lease, patient, encounter, id);
      if (current !== generation) return;
      if (result.patient_id !== patient || result.encounter_id !== encounter || result.root_agent_code !== 'DOCUMENT_DRAFTER') {
        notice.value = '医助候选与当前患者、就诊或文书用途不一致，请重新选择。'; return;
      }
      run.value = result;
    } catch (error) { if (current === generation) notice.value = toClinicalIssue(error).message; }
  }, { immediate: true });
  return { run, notice };
}
