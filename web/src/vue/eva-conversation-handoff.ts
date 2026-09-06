import { shallowRef, watch } from 'vue';
import { authSession } from '../auth-session';
import type { MedicalAgentRunWire } from '../generated/contracts';
import type { EvaPatientContext } from './use-eva-clinical-context';

export interface EvaChatMessage {
  id: string;
  role: 'user' | 'assistant';
  text: string;
  processing?: boolean;
  agentName?: string;
  events?: Array<{ id: string; label: string; detail: string; status: 'running' | 'done' | 'waiting' | 'failed' }>;
  runId?: string;
  runState?: MedicalAgentRunWire['state'];
  rowVersion?: number;
  modelName?: string;
  eventsCollapsed?: boolean;
  eventsUserToggled?: boolean;
  run?: MedicalAgentRunWire;
}

export interface EvaConversationTransfer {
  messages: EvaChatMessage[];
  patient: EvaPatientContext;
  draft: string;
  agentCode: string;
  stageCode: string;
  modelId: string;
  authorizationLevel: 'READ_ONLY' | 'STANDARD' | 'EXTENDED';
  contextScopes: Array<'RECORDS' | 'ORDERS' | 'RESULTS' | 'TASKS' | 'ATTACHMENTS' | 'CONFIGURATION'>;
  pendingRun: { messageId: string; promise: Promise<MedicalAgentRunWire> } | null;
}

// One-use, in-memory handoff. No clinical content is persisted in browser storage.
export const evaConversationTransfer = shallowRef<EvaConversationTransfer | null>(null);
watch(() => authSession.token, () => { evaConversationTransfer.value = null; }, { flush: 'sync' });

export function publishEvaConversation(transfer: EvaConversationTransfer) {
  const { pendingRun, ...snapshot } = transfer;
  evaConversationTransfer.value = { ...JSON.parse(JSON.stringify(snapshot)), pendingRun };
}

export function takeEvaConversation(): EvaConversationTransfer | null {
  const transfer = evaConversationTransfer.value;
  evaConversationTransfer.value = null;
  return transfer;
}
