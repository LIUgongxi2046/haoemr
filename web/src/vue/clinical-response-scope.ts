import { clinicalContext } from '../clinical-api';

export function clinicalOrganizationScope() {
  return JSON.stringify([clinicalContext.tenantId, clinicalContext.userId, clinicalContext.roleId,
    clinicalContext.organizationId, clinicalContext.facilityId]);
}

export function clinicalResponseScope() {
  return JSON.stringify([clinicalContext.tenantId, clinicalContext.userId, clinicalContext.roleId,
    clinicalContext.organizationId, clinicalContext.facilityId, clinicalContext.patientId,
    clinicalContext.encounterId, clinicalContext.documentId]);
}

export function captureResponseScope(read: () => string = clinicalResponseScope) {
  const before = read();
  return () => read() === before;
}
