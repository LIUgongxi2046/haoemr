import { ClinicalApiError, clinicalContext, request } from '../clinical-api';
import { encounterRecordQualityWireSchema, type ContextLeaseWire } from '../generated/contracts';

export async function loadEncounterRecordQc(lease: ContextLeaseWire) {
  lease = { ...lease };
  const current = () => lease.user_id === clinicalContext.userId
    && lease.organization_id === clinicalContext.organizationId && lease.facility_id === clinicalContext.facilityId;
  if (!lease.patient_id || !lease.encounter_id || !current()) {
    throw new ClinicalApiError('CONTEXT_MISMATCH', '请在当前岗位重新取得本次就诊授权。', 403);
  }
  const result = encounterRecordQualityWireSchema.parse(await request(
    `/encounters/${encodeURIComponent(lease.encounter_id)}/record-quality`, { headers: {
      'X-Context-Lease-Id': lease.lease_id, 'X-Authorization-Watermark': lease.authorization_watermark,
      'X-Organization-Context': lease.organization_id, 'X-Facility-Context': lease.facility_id,
      'X-Patient-Context': lease.patient_id, 'X-Encounter-Context': lease.encounter_id,
    } }));
  if (!current() || result.patient_id !== lease.patient_id || result.encounter_id !== lease.encounter_id) {
    throw new ClinicalApiError('CONTEXT_MISMATCH', '就诊或岗位已变化，未显示旧质控汇总。', 403);
  }
  if (new Set(result.documents.map(item => item.document_id)).size !== result.documents.length
    || result.total_documents < result.documents.length
    || result.has_more !== (result.total_documents > result.documents.length)) {
    throw new ClinicalApiError('QUALITY_SUMMARY_INVALID', '质控汇总不完整，请重新读取。', 409);
  }
  return result;
}
