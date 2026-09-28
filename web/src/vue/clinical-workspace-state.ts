type Context = { organizationId: string; facilityId: string; patientId: string; encounterId: string;
  inpatientPatientId: string; inpatientEncounterId: string; inpatientAdmissionId: string; inpatientWardId: string };
export function workspaceContextState(context: Context, mode: 'outpatient' | 'inpatient') {
  if (!context.organizationId || !context.facilityId) return 'unconfigured';
  if (mode === 'outpatient') return context.patientId && context.encounterId ? 'ready' : 'no-encounter';
  return context.inpatientPatientId && context.inpatientEncounterId && context.inpatientAdmissionId && context.inpatientWardId ? 'ready' : 'no-encounter';
}
