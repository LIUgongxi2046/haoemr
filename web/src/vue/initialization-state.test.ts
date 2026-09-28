import { describe, expect, it } from 'vitest';
import type { OrganizationUnitWire, DocumentTemplateWire } from '../generated/contracts';
import { hasActiveDepartment, applicableTemplateReadiness } from './initialization-state';
const unit = (id: string, type: OrganizationUnitWire['unit_type'], parent: string | null, status = 'ACTIVE') => ({ unit_id: id, unit_type: type, parent_unit_id: parent, status }) as OrganizationUnitWire;
describe('initialization checks stay within the selected institution', () => {
  const units = [unit('org', 'ORGANIZATION', null), unit('fac', 'FACILITY', 'org'), unit('other', 'FACILITY', 'org')];
  it('requires a department in the selected active facility', () => {
    expect(hasActiveDepartment([...units, unit('dept', 'DEPARTMENT', 'other')], 'org', 'fac')).toBe(false);
    expect(hasActiveDepartment([...units, unit('dept', 'DEPARTMENT', 'fac')], 'org', 'fac')).toBe(true);
    expect(hasActiveDepartment([...units, unit('dept', 'DEPARTMENT', 'fac', 'INACTIVE')], 'org', 'fac')).toBe(false);
  });
  it('rejects an absent institution, a wrong parent and inactive ancestors', () => {
    expect(hasActiveDepartment([...units, unit('dept', 'DEPARTMENT', 'fac')], 'other-org', 'fac')).toBe(false);
    expect(hasActiveDepartment([unit('org', 'ORGANIZATION', null, 'INACTIVE'), ...units.slice(1), unit('dept', 'DEPARTMENT', 'fac')], 'org', 'fac')).toBe(false);
    expect(hasActiveDepartment([], 'org', 'fac')).toBe(false);
  });
  it('counts published and draft templates separately and excludes other scopes', () => {
    const template = { template_id: 't', lifecycle_status: 'ACTIVE', organization_id: 'org', facility_id: 'fac', department_id: null, version_status: 'DRAFT' } as DocumentTemplateWire;
    expect(applicableTemplateReadiness([template], 'org', 'fac')).toEqual({ published: 0, drafts: 1 });
    expect(applicableTemplateReadiness([template, { ...template, version_status: 'PUBLISHED' }, { ...template, template_id: 'other', facility_id: 'other', version_status: 'PUBLISHED' }, { ...template, department_id: 'dept', version_status: 'PUBLISHED' }], 'org', 'fac')).toEqual({ published: 1, drafts: 1 });
  });
});
