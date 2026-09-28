import type { OrganizationUnitWire, DocumentTemplateWire } from '../generated/contracts';

export function hasActiveDepartment(units: OrganizationUnitWire[], organizationId: string, facilityId: string): boolean {
  const organization = units.find(unit => unit.unit_id === organizationId && unit.unit_type === 'ORGANIZATION' && unit.status === 'ACTIVE');
  const facility = units.find(unit => unit.unit_id === facilityId && unit.unit_type === 'FACILITY' && unit.parent_unit_id === organizationId && unit.status === 'ACTIVE');
  return Boolean(organization && facility && units.some(unit => unit.unit_type === 'DEPARTMENT' && unit.status === 'ACTIVE' && unit.parent_unit_id === facilityId));
}

export function applicableTemplateReadiness(templates: DocumentTemplateWire[], organizationId: string, facilityId: string) {
  const applicable = templates.filter(item => item.lifecycle_status === 'ACTIVE' && !item.department_id
    && (!item.organization_id || item.organization_id === organizationId)
    && (!item.facility_id || item.facility_id === facilityId));
  return {
    published: new Set(applicable.filter(item => item.version_status === 'PUBLISHED').map(item => item.template_id)).size,
    drafts: applicable.filter(item => item.version_status === 'DRAFT').length,
  };
}
