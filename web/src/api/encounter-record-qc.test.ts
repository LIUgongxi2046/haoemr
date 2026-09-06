import { beforeEach, describe, expect, it, vi } from 'vitest';
import { clinicalContext, request } from '../clinical-api';
import type { ContextLeaseWire, EncounterRecordQualityWire } from '../generated/contracts';
import { loadEncounterRecordQc } from './encounter-record-qc';

vi.mock('../clinical-api', () => ({ clinicalContext: { userId: 'u', organizationId: 'o', facilityId: 'f' }, request: vi.fn(),
  ClinicalApiError: class extends Error { constructor(public code: string, message: string, public status: number) { super(message); } } }));
const id = (n: number) => `018f0000-0000-7000-8000-${String(n).padStart(12, '0')}`;
const lease = () => ({ lease_id: id(1), user_id: 'u', organization_id: 'o', facility_id: 'f',
  patient_id: id(2), encounter_id: id(3), authorization_watermark: 'm' } as ContextLeaseWire);
const summary = (): EncounterRecordQualityWire => ({ patient_id: id(2), encounter_id: id(3), documents: [{
  document_id: id(4), document_version_id: id(5), content_hash: 'a'.repeat(64), document_type_code: 'TEST',
  document_status: 'DRAFT', quality_state: 'NOT_RUN', open_finding_count: 0, blocking_count: 0,
}], total_documents: 1, has_more: false });
beforeEach(() => { vi.resetAllMocks(); Object.assign(clinicalContext, { userId: 'u', organizationId: 'o', facilityId: 'f' }); vi.mocked(request).mockResolvedValue(summary()); });
describe('就诊汇总仅绑定明确租约', () => {
  it('仅读取所选就诊，不读取或修改当前门诊患者', async () => {
    const before = { ...clinicalContext }; const result = await loadEncounterRecordQc(lease());
    expect(result.documents[0].quality_state).toBe('NOT_RUN'); expect(clinicalContext).toEqual(before);
    expect(request).toHaveBeenCalledExactlyOnceWith(`/encounters/${id(3)}/record-quality`, { headers: {
      'X-Context-Lease-Id': id(1), 'X-Authorization-Watermark': 'm', 'X-Organization-Context': 'o', 'X-Facility-Context': 'f',
      'X-Patient-Context': id(2), 'X-Encounter-Context': id(3),
    } });
  });
  it.each(['patient_id', 'encounter_id'] as const)('缺失 %s 不发请求', async field => {
    await expect(loadEncounterRecordQc({ ...lease(), [field]: null })).rejects.toMatchObject({ code: 'CONTEXT_MISMATCH' });
    expect(request).not.toHaveBeenCalled();
  });
  it.each(['patient_id', 'encounter_id'] as const)('返回其他 %s 不显示', async field => {
    vi.mocked(request).mockResolvedValue({ ...summary(), [field]: id(99) });
    await expect(loadEncounterRecordQc(lease())).rejects.toMatchObject({ code: 'CONTEXT_MISMATCH' });
  });
  it.each(['userId', 'organizationId', 'facilityId'] as const)('等待时切换 %s 拒绝旧响应', async field => {
    vi.mocked(request).mockImplementation(async () => { clinicalContext[field] = 'other'; return summary(); });
    await expect(loadEncounterRecordQc(lease())).rejects.toMatchObject({ code: 'CONTEXT_MISMATCH' });
  });
  it.each(['duplicates', 'count', 'truncation'])('拒绝无效汇总 %s', async kind => {
    const invalid = summary();
    if (kind === 'duplicates') invalid.documents.push(invalid.documents[0]);
    if (kind === 'count') invalid.total_documents = 0;
    if (kind === 'truncation') invalid.total_documents = 2;
    vi.mocked(request).mockResolvedValue(invalid);
    await expect(loadEncounterRecordQc(lease())).rejects.toMatchObject({ code: 'QUALITY_SUMMARY_INVALID' });
  });
  it('部分结果必须明确标记，不能误报完整', async () => {
    vi.mocked(request).mockResolvedValue({ ...summary(), total_documents: 101, has_more: true });
    expect((await loadEncounterRecordQc(lease())).has_more).toBe(true);
  });
});
