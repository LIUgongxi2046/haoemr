import { ClinicalApiError, clinicalContext, issueContextLease, request } from '../clinical-api';
import { documentGovernanceSnapshotWireSchema, documentVersionWireSchema } from '../generated/contracts';
import type { RecordCenterWorklistItem } from './record-center';

export type RecordQcPreviewTarget = Pick<RecordCenterWorklistItem,
  'document_id' | 'document_version_id' | 'content_hash' | 'patient_id' | 'encounter_id'>;

/** The worklist selection is not the clinician's active patient context. */
export async function loadRecordQcPreview(target: RecordQcPreviewTarget) {
  target = { ...target };
  const scope = { organization: clinicalContext.organizationId, facility: clinicalContext.facilityId, user: clinicalContext.userId };
  const lease = await issueContextLease(target.patient_id, target.encounter_id, 'DOCUMENT_DRAFT');
  if (lease.patient_id !== target.patient_id || lease.encounter_id !== target.encounter_id
    || lease.organization_id !== scope.organization || lease.facility_id !== scope.facility || lease.user_id !== scope.user) {
    throw new ClinicalApiError('CONTEXT_MISMATCH', '文书预览上下文不匹配，请重新选择病历。', 403);
  }
  const headers = {
    'X-Context-Lease-Id': lease.lease_id,
    'X-Authorization-Watermark': lease.authorization_watermark,
    'X-Organization-Context': lease.organization_id,
    'X-Facility-Context': lease.facility_id,
    'X-Patient-Context': lease.patient_id,
    'X-Encounter-Context': lease.encounter_id,
  };
  const id = encodeURIComponent(target.document_id);
  const query = new URLSearchParams({ document_version_id: target.document_version_id });
  const [rawDocument, rawGovernance] = await Promise.all([
    request(`/documents/${id}`, { headers }),
    request(`/documents/${id}/governance?${query}`, { headers }),
  ]);
  const document = documentVersionWireSchema.parse(rawDocument);
  const governance = documentGovernanceSnapshotWireSchema.parse(rawGovernance);
  if (clinicalContext.organizationId !== scope.organization || clinicalContext.facilityId !== scope.facility
    || clinicalContext.userId !== scope.user) {
    throw new ClinicalApiError('CONTEXT_MISMATCH', '岗位或院区已变化，请重新选择病历。', 403);
  }
  if (document.document_id !== target.document_id || document.encounter_id !== target.encounter_id
    || document.document_version_id !== target.document_version_id || document.content_hash !== target.content_hash
    || governance.document_id !== target.document_id
    || governance.document_version_id !== target.document_version_id || governance.content_hash !== target.content_hash
    || (governance.quality_run && (governance.quality_run.document_version_id !== target.document_version_id
      || governance.quality_run.content_hash !== target.content_hash))) {
    throw new ClinicalApiError('VERSION_CONFLICT', '文书已变化或证据版本不匹配。请刷新工作台后重新查看，未沿用旧质控结论。', 409);
  }
  return { lease, document, governance };
}
