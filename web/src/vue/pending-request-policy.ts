export function mayRequestPendingSource(id: string, selectedRole: string | undefined, denied: ReadonlySet<string>, context: { patientId?: string | null; encounterId?: string | null; emergencyPatientId?: string | null; emergencyEncounterId?: string | null; inpatientPatientId?: string | null; inpatientEncounterId?: string | null; inpatientWardId?: string | null; workspaceMode?: string }) {
  if (denied.has(id)) return false;
  if (id === 'admin-failed-jobs' && !['SYSTEM_ADMIN', 'CLINICAL_ADMIN', 'SECURITY_AUDITOR', 'AUTHORIZATION_ADMIN'].includes(selectedRole ?? '')) return false;
  if (id === 'credential-expiring' && !['SYSTEM_ADMIN', 'CLINICAL_ADMIN'].includes(selectedRole ?? '')) return false;
  if (id === 'archive-integrity' && !['MEDICAL_RECORDS', 'CLINICAL_ADMIN'].includes(selectedRole ?? '')) return false;
  if (id === 'tasks-outpatient' || id === 'archive-integrity') return Boolean(context.patientId && context.encounterId);
  if (id === 'tasks-emergency') return Boolean(context.emergencyPatientId && context.emergencyEncounterId);
  if (id === 'tasks-inpatient') return Boolean(context.inpatientPatientId && context.inpatientEncounterId);
  if (id === 'ward-documents') return Boolean(context.inpatientWardId && context.workspaceMode === 'inpatient');
  return true;
}
