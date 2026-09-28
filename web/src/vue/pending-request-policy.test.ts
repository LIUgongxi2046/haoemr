import { describe, expect, it } from 'vitest';
import { mayRequestPendingSource } from './pending-request-policy';
describe('background pending requests', () => {
  it('matches credential and archive role gates before starting background requests', () => {
    const context = { patientId: 'p', encounterId: 'e' };
    expect(mayRequestPendingSource('credential-expiring', 'CLINICIAN', new Set(), context)).toBe(false);
    expect(mayRequestPendingSource('credential-expiring', 'SYSTEM_ADMIN', new Set(), context)).toBe(true);
    expect(mayRequestPendingSource('archive-integrity', 'LAB_TECHNICIAN', new Set(), context)).toBe(false);
    expect(mayRequestPendingSource('archive-integrity', 'MEDICAL_RECORDS', new Set(), context)).toBe(true);
  });
  it('loads ward-specific statistics only while working in the inpatient context', () => {
    expect(mayRequestPendingSource('ward-documents', 'CLINICIAN', new Set(), { inpatientWardId: 'ward', workspaceMode: 'outpatient' })).toBe(false);
    expect(mayRequestPendingSource('ward-documents', 'CLINICIAN', new Set(), { inpatientWardId: 'ward', workspaceMode: 'inpatient' })).toBe(true);
  });
  it('stops administrator-only statistics when switching to a clinician role', () => {
    expect(mayRequestPendingSource('admin-failed-jobs', 'SYSTEM_ADMIN', new Set(), {})).toBe(true);
    expect(mayRequestPendingSource('admin-failed-jobs', 'CLINICIAN', new Set(), {})).toBe(false);
    expect(mayRequestPendingSource('admin-failed-jobs', undefined, new Set(), {})).toBe(false);
  });
  it('does not retry a denied source on each automatic refresh', () => {
    expect(mayRequestPendingSource('dataset-review', 'CLINICIAN', new Set(['dataset-review']), {})).toBe(false);
    expect(mayRequestPendingSource('dataset-review', 'CLINICIAN', new Set(), {})).toBe(true);
  });
  it('does not request patient worklists before their contexts exist', () => {
    for (const id of ['tasks-outpatient', 'tasks-emergency', 'tasks-inpatient', 'ward-documents', 'archive-integrity']) expect(mayRequestPendingSource(id, 'CLINICIAN', new Set(), {})).toBe(false);
    expect(mayRequestPendingSource('tasks-outpatient', 'CLINICIAN', new Set(), { patientId: 'p', encounterId: 'e' })).toBe(true);
    expect(mayRequestPendingSource('tasks-emergency', 'CLINICIAN', new Set(), { patientId: 'p', encounterId: 'e' })).toBe(false);
  });
});
