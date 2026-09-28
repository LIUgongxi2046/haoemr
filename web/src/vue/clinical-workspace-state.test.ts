import { describe, expect, it } from 'vitest';
import { workspaceContextState } from './clinical-workspace-state';
const context = { organizationId: 'org', facilityId: 'facility', patientId: 'p', encounterId: 'e',
  inpatientPatientId: 'p2', inpatientEncounterId: 'e2', inpatientAdmissionId: 'admission', inpatientWardId: 'ward' };
describe('original workspace context prerequisites', () => {
  it.each(['outpatient', 'inpatient'] as const)('%s requires configured organization and facility', mode => {
    expect(workspaceContextState({ ...context, organizationId: '' }, mode)).toBe('unconfigured');
    expect(workspaceContextState({ ...context, facilityId: '' }, mode)).toBe('unconfigured');
    expect(workspaceContextState(context, mode)).toBe('ready');
  });
  it.each(['patientId', 'encounterId'] as const)('does not request a partial outpatient context: %s', field => {
    expect(workspaceContextState({ ...context, [field]: '' }, 'outpatient')).toBe('no-encounter');
  });
  it.each(['inpatientPatientId', 'inpatientEncounterId', 'inpatientAdmissionId', 'inpatientWardId'] as const)('does not request a partial inpatient context: %s', field => {
    expect(workspaceContextState({ ...context, [field]: '' }, 'inpatient')).toBe('no-encounter');
  });
});
