import { reactive } from 'vue';
import type { MedicalAgentRunWire } from '../generated/contracts';
import type { EvaPatientContext } from './use-eva-clinical-context';

/** 在完整工作台与全局侧窗之间传递「待复核」对话内容，用于病历对照闭环。 */
export interface EvaReviewPayload {
  objective: string;
  result: string;
  run?: MedicalAgentRunWire;
  patient?: EvaPatientContext;
}

export const evaReviewBridge = reactive<{ payload: EvaReviewPayload | null; armed: boolean }>({
  payload: null,
  armed: false,
});
