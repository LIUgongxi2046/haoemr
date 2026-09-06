import { clinicalContext, selectOutpatientContext, setEmergencyClinicalContext } from '../clinical-api';
import type { EvaChatMessage } from './eva-conversation-handoff';
import { hasEvaPatientContext, type EvaPatientContext } from './use-eva-clinical-context';

const reviewableStates = new Set(['WAITING_FOR_REVIEW', 'COMPLETED', 'PARTIAL']);

export function patientReviewPath(context: EvaPatientContext): string | null {
  if (!hasEvaPatientContext(context)) return null;
  return ({ 门诊: '/opd-record', 急诊: '/er-record', 住院: '/inpatient-overview' } as Record<string, string>)[context.scene] ?? null;
}

export function canReviewPatientRecord(message: EvaChatMessage, context: EvaPatientContext): boolean {
  return Boolean(message.run && reviewableStates.has(message.run.state) && patientReviewPath(context)
    && message.run.patient_id === context.patientId && message.run.encounter_id === context.encounterId);
}

export function activatePatientReview(context: EvaPatientContext): string | null {
  const path = patientReviewPath(context);
  if (!path) return null;
  const { patientId, encounterId, patientName } = context;
  if (context.scene === '门诊') selectOutpatientContext({ patientId, encounterId, patientDisplayName: patientName });
  else if (context.scene === '急诊') setEmergencyClinicalContext(patientId, encounterId);
  else {
    clinicalContext.inpatientPatientId = patientId;
    clinicalContext.inpatientEncounterId = encounterId;
  }
  return path;
}
