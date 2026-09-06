import { describe, expect, it } from 'vitest';
import type { MedicalAgentRunWire } from '../generated/contracts';
import type { EvaChatMessage } from './eva-conversation-handoff';
import { canReviewPatientRecord, patientReviewPath } from './eva-patient-review';
import type { EvaPatientContext } from './use-eva-clinical-context';

const context: EvaPatientContext = { patientId: '018f0000-0000-7000-8000-000000000001',
  encounterId: '018f0000-0000-7000-8000-000000000101', patientName: '验收患者', patientSummary: '', scene: '门诊', label: '' };
function message(state: MedicalAgentRunWire['state']): EvaChatMessage {
  return { id: 'answer', role: 'assistant', text: '待复核内容',
    run: { patient_id: context.patientId, encounter_id: context.encounterId, state } as MedicalAgentRunWire };
}

describe('EVA record review entry', () => {
  it.each([['门诊', '/opd-record'], ['急诊', '/er-record'], ['住院', '/inpatient-overview']])('opens the matching %s scene', (scene, path) => {
    expect(patientReviewPath({ ...context, scene })).toBe(path);
  });
  it.each(['WAITING_FOR_REVIEW', 'COMPLETED', 'PARTIAL'] as const)('permits %s results', state => {
    expect(canReviewPatientRecord(message(state), context)).toBe(true);
  });
  it.each(['QUEUED', 'RUNNING', 'BLOCKED', 'FAILED', 'CANCELLED'] as const)('hides the entry for %s', state => {
    expect(canReviewPatientRecord(message(state), context)).toBe(false);
  });
  it('never opens a different patient or encounter, an unbound question, or an unknown scene', () => {
    const answer = message('COMPLETED');
    expect(canReviewPatientRecord(answer, { ...context, patientId: '018f0000-0000-7000-8000-000000000002' })).toBe(false);
    expect(canReviewPatientRecord(answer, { ...context, encounterId: '018f0000-0000-7000-8000-000000000102' })).toBe(false);
    expect(canReviewPatientRecord(answer, { ...context, patientId: '', encounterId: '' })).toBe(false);
    expect(canReviewPatientRecord(answer, { ...context, scene: '未绑定' })).toBe(false);
    expect(canReviewPatientRecord({ id: 'text-only', role: 'assistant', text: '通用回答' }, context)).toBe(false);
  });
});
