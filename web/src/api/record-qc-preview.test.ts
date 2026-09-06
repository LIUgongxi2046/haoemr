import { beforeEach, describe, expect, it, vi } from 'vitest';
import { clinicalContext, issueContextLease, request } from '../clinical-api';
import type { ContextLeaseWire, DocumentGovernanceSnapshotWire, DocumentVersionWire } from '../generated/contracts';
import { loadRecordQcPreview } from './record-qc-preview';

vi.mock('../clinical-api', () => ({
  clinicalContext: { organizationId: 'org', facilityId: 'facility', userId: 'doctor', patientId: 'active-patient', encounterId: 'active-encounter', documentId: 'active-doc' },
  issueContextLease: vi.fn(), request: vi.fn(),
  ClinicalApiError: class extends Error {
    constructor(public code: string, message: string, public status: number) { super(message); }
  },
}));
const id = (n: number) => `018f0000-0000-7000-8000-${String(n).padStart(12, '0')}`;
const target = () => ({ document_id: id(1), document_version_id: id(2), patient_id: id(3), encounter_id: id(4), content_hash: 'hash' });
const lease = () => ({ lease_id: id(5), organization_id: 'org', facility_id: 'facility', user_id: 'doctor',
  patient_id: id(3), encounter_id: id(4), authorization_watermark: 'watermark' } as ContextLeaseWire);
const document = (): DocumentVersionWire => ({ document_id: id(1), document_version_id: id(2), encounter_id: id(4),
  template_version_id: id(6), template_version_no: 1, version_no: 1, status: 'DRAFT', sections: { chief_complaint: '合成预览内容' },
  content_hash: 'hash', row_version: 1, created_at: '2026-09-06' });
const governance = (): DocumentGovernanceSnapshotWire => ({ document_id: id(1), document_version_id: id(2), document_status: 'DRAFT',
  content_hash: 'hash', quality_findings: [], quality_run: null, signatures: [], review_decisions: [], data_watermark: 'watermark' });
const issue = vi.mocked(issueContextLease);
const read = vi.mocked(request);
beforeEach(() => {
  vi.clearAllMocks();
  Object.assign(clinicalContext, { organizationId: 'org', facilityId: 'facility', userId: 'doctor', patientId: 'active-patient', encounterId: 'active-encounter', documentId: 'active-doc' });
  issue.mockResolvedValue(lease());
  read.mockImplementation(async (url) => String(url).includes('/governance?') ? governance() : document());
});

describe('工作台质控只读预览 API', () => {
  it('使用所选患者独立租约和明确 headers，不改变当前接诊上下文', async () => {
    const before = { ...clinicalContext };
    const value = await loadRecordQcPreview(target());
    expect(issue).toHaveBeenCalledWith(id(3), id(4), 'DOCUMENT_DRAFT');
    expect(read).toHaveBeenCalledTimes(2);
    for (const [, options] of read.mock.calls) {
      expect(options?.method).toBeUndefined();
      expect(options?.headers).toMatchObject({ 'X-Patient-Context': id(3), 'X-Encounter-Context': id(4),
        'X-Organization-Context': 'org', 'X-Facility-Context': 'facility', 'X-Context-Lease-Id': id(5) });
    }
    expect(value.document.sections?.chief_complaint).toBe('合成预览内容');
    expect(value.governance.quality_run).toBeNull();
    expect(clinicalContext).toEqual(before);
  });
  it.each(['patient_id', 'encounter_id', 'organization_id', 'facility_id', 'user_id'] as const)('租约 %s 不匹配时禁止继续读取正文', async field => {
    issue.mockResolvedValue({ ...lease(), [field]: 'wrong' });
    await expect(loadRecordQcPreview(target())).rejects.toMatchObject({ code: 'CONTEXT_MISMATCH' });
    expect(read).not.toHaveBeenCalled();
  });
  it.each(['document_id', 'document_version_id', 'encounter_id', 'content_hash'] as const)('文书 %s 与工作台快照不同则拒绝混版展示', async field => {
    read.mockImplementation(async url => String(url).includes('/governance?') ? governance() : { ...document(), [field]: id(99) });
    await expect(loadRecordQcPreview(target())).rejects.toMatchObject({ code: 'VERSION_CONFLICT' });
  });
  it.each(['document_id', 'document_version_id', 'content_hash'] as const)('治理证据 %s 不匹配则拒绝展示', async field => {
    read.mockImplementation(async url => String(url).includes('/governance?') ? { ...governance(), [field]: id(99) } : document());
    await expect(loadRecordQcPreview(target())).rejects.toMatchObject({ code: 'VERSION_CONFLICT' });
  });
  it('权限拒绝直接向 UI 报错，不降级到其他患者或绕过租约', async () => {
    const forbidden = Object.assign(new Error('forbidden'), { code: 'ACCESS_DENIED' });
    issue.mockRejectedValue(forbidden);
    await expect(loadRecordQcPreview(target())).rejects.toBe(forbidden);
    expect(read).not.toHaveBeenCalled();
  });
  it('读取期间岗位切换时丢弃结果', async () => {
    read.mockImplementation(async url => {
      clinicalContext.userId = 'another-doctor';
      return String(url).includes('/governance?') ? governance() : document();
    });
    await expect(loadRecordQcPreview(target())).rejects.toMatchObject({ code: 'CONTEXT_MISMATCH' });
  });
  it('读取期间切换接诊患者不改变所选预览文书的 scope', async () => {
    issue.mockImplementation(async () => {
      clinicalContext.patientId = 'other-active-patient'; clinicalContext.encounterId = 'other-active-encounter';
      return lease();
    });
    await loadRecordQcPreview(target());
    for (const [, options] of read.mock.calls) expect(options?.headers).toMatchObject({ 'X-Patient-Context': id(3), 'X-Encounter-Context': id(4) });
    expect(clinicalContext.patientId).toBe('other-active-patient');
  });
});
