import {
  clinicalContext,
  issueContextLease,
  request,
  requestBinary,
  wardHeaders,
} from '../clinical-api';
import {
  dataQualityEvaluationRecordRequestWireSchema,
  dataQualityEvaluationWireSchema,
  dataQualityFindingTransitionRequestWireSchema,
  dataQualityFindingWireSchema,
  dataQualityRuleDeactivateRequestWireSchema,
  dataQualityRuleRegisterRequestWireSchema,
  dataQualityRuleWireSchema,
  dataQualityScanRunWireSchema,
  dataQualityScanStartRequestWireSchema,
  dataQualityTriageAdviceWireSchema,
  dataQualityTriageRequestWireSchema,
  releaseDownloadEventCreateRequestWireSchema,
  releaseDownloadEventWireSchema,
  releaseDownloadValidCountWireSchema,
  releaseMetricSnapshotCreateRequestWireSchema,
  releaseMetricSnapshotWireSchema,
  researchCohortDeactivateRequestWireSchema,
  researchCohortDefineRequestWireSchema,
  researchCohortMemberComputeRequestWireSchema,
  researchCohortRecomputeRequestWireSchema,
  researchCohortRecomputeResultWireSchema,
  researchCohortMemberWireSchema,
  researchCohortSnapshotRequestWireSchema,
  researchCohortSnapshotWireSchema,
  researchCohortWireSchema,
  researchDatasetRequestApproveRequestWireSchema,
  researchDatasetRequestCreateRequestWireSchema,
  researchDatasetRequestDestroyRequestWireSchema,
  researchDatasetRequestExportRequestWireSchema,
  researchDatasetRequestWireSchema,
  type ContextLeaseWire,
  type DataQualityEvaluationRecordRequestWire,
  type DataQualityEvaluationWire,
  type DataQualityFindingTransitionRequestWire,
  type DataQualityFindingWire,
  type DataQualityRuleRegisterRequestWire,
  type DataQualityRuleWire,
  type DataQualityScanRunWire,
  type DataQualityTriageAdviceWire,
  type ReleaseDownloadEventCreateRequestWire,
  type ReleaseDownloadEventWire,
  type ReleaseDownloadValidCountWire,
  type ReleaseMetricSnapshotCreateRequestWire,
  type ReleaseMetricSnapshotWire,
  type ResearchCohortDefineRequestWire,
  type ResearchCohortMemberComputeRequestWire,
  type ResearchCohortRecomputeResultWire,
  type ResearchCohortMemberWire,
  type ResearchCohortSnapshotRequestWire,
  type ResearchCohortSnapshotWire,
  type ResearchCohortWire,
  type ResearchDatasetRequestCreateRequestWire,
  type ResearchDatasetRequestWire,
} from '../generated/contracts';

/** 数据中心/科研/开源域为机构-院区级上下文（无患者），签发一次租约后复用。 */
export function issueDataLease(purpose: string): Promise<ContextLeaseWire> {
  return issueContextLease(null, null, purpose);
}

function orgFacility() {
  return {
    organization_id: clinicalContext.organizationId,
    facility_id: clinicalContext.facilityId,
  };
}

// ── 数据质量（规则 + 评估） ──────────────────────────────────
export async function listDataQualityRules(lease: ContextLeaseWire, dimension?: string): Promise<DataQualityRuleWire[]> {
  const q = dimension ? `?dimension=${encodeURIComponent(dimension)}` : '';
  return dataQualityRuleWireSchema.array().parse(await request(`/data-quality-rules${q}`, { headers: wardHeaders(lease) }));
}

export async function registerDataQualityRule(
  lease: ContextLeaseWire,
  input: Omit<DataQualityRuleRegisterRequestWire, 'organization_id' | 'facility_id'>,
): Promise<DataQualityRuleWire> {
  return dataQualityRuleWireSchema.parse(await request('/data-quality-rules', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(dataQualityRuleRegisterRequestWireSchema.parse({ ...orgFacility(), ...input })),
  }));
}

export async function deactivateDataQualityRule(
  lease: ContextLeaseWire,
  rule: DataQualityRuleWire,
): Promise<DataQualityRuleWire> {
  return dataQualityRuleWireSchema.parse(await request(
    `/data-quality-rules/${rule.data_quality_rule_id}/deactivations`, {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(dataQualityRuleDeactivateRequestWireSchema.parse({ ...orgFacility() })),
    },
  ));
}

export async function listDataQualityEvaluations(lease: ContextLeaseWire, ruleId: string): Promise<DataQualityEvaluationWire[]> {
  return dataQualityEvaluationWireSchema.array().parse(await request(
    `/data-quality-evaluations?data_quality_rule_id=${encodeURIComponent(ruleId)}`,
    { headers: wardHeaders(lease) },
  ));
}

export async function recordDataQualityEvaluation(
  lease: ContextLeaseWire,
  input: Omit<DataQualityEvaluationRecordRequestWire, 'organization_id' | 'facility_id'>,
): Promise<DataQualityEvaluationWire> {
  return dataQualityEvaluationWireSchema.parse(await request('/data-quality-evaluations', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(dataQualityEvaluationRecordRequestWireSchema.parse({ ...orgFacility(), ...input })),
  }));
}

// ── 队列构建器（研究队列 + 快照 + 成员） ─────────────────────
export async function startDataQualityScan(
  lease: ContextLeaseWire,
  ruleId: string,
): Promise<DataQualityScanRunWire> {
  return dataQualityScanRunWireSchema.parse(await request(`/data-quality-rules/${ruleId}/scans`, {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(dataQualityScanStartRequestWireSchema.parse(orgFacility())),
  }));
}

export async function listDataQualityScans(
  lease: ContextLeaseWire,
  ruleId: string,
): Promise<DataQualityScanRunWire[]> {
  return dataQualityScanRunWireSchema.array().parse(await request(
    `/data-quality-scans?data_quality_rule_id=${encodeURIComponent(ruleId)}`,
    { headers: wardHeaders(lease) },
  ));
}

export async function listDataQualityFindings(
  lease: ContextLeaseWire,
  scanId?: string,
): Promise<DataQualityFindingWire[]> {
  return dataQualityFindingWireSchema.array().parse(await request(
    `/data-quality-findings${scanId ? `?data_quality_scan_id=${encodeURIComponent(scanId)}` : ''}`,
    { headers: wardHeaders(lease) },
  ));
}

export async function transitionDataQualityFinding(
  lease: ContextLeaseWire,
  findingId: string,
  input: Omit<DataQualityFindingTransitionRequestWire, 'organization_id' | 'facility_id'>,
): Promise<DataQualityFindingWire> {
  return dataQualityFindingWireSchema.parse(await request(
    `/data-quality-findings/${findingId}/transitions`, {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(dataQualityFindingTransitionRequestWireSchema.parse({ ...orgFacility(), ...input })),
    },
  ));
}

export async function listDataQualityTriageAdvice(
  lease: ContextLeaseWire,
  scanId: string,
): Promise<DataQualityTriageAdviceWire[]> {
  return dataQualityTriageAdviceWireSchema.array().parse(await request(
    `/data-quality-scans/${scanId}/triage-advice`, { headers: wardHeaders(lease) },
  ));
}

export async function createDataQualityTriageAdvice(
  lease: ContextLeaseWire,
  scanId: string,
): Promise<DataQualityTriageAdviceWire> {
  return dataQualityTriageAdviceWireSchema.parse(await request(
    `/data-quality-scans/${scanId}/triage-advice`, {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(dataQualityTriageRequestWireSchema.parse(orgFacility())),
    },
  ));
}

export async function listResearchCohorts(lease: ContextLeaseWire, status?: string): Promise<ResearchCohortWire[]> {
  const q = status ? `?status=${encodeURIComponent(status)}` : '';
  return researchCohortWireSchema.array().parse(await request(`/research-cohorts${q}`, { headers: wardHeaders(lease) }));
}

export async function defineResearchCohort(
  lease: ContextLeaseWire,
  input: Omit<ResearchCohortDefineRequestWire, 'organization_id' | 'facility_id'>,
): Promise<ResearchCohortWire> {
  return researchCohortWireSchema.parse(await request('/research-cohorts', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(researchCohortDefineRequestWireSchema.parse({ ...orgFacility(), ...input })),
  }));
}

export async function deactivateResearchCohort(
  lease: ContextLeaseWire,
  cohort: ResearchCohortWire,
): Promise<ResearchCohortWire> {
  return researchCohortWireSchema.parse(await request(
    `/research-cohorts/${cohort.research_cohort_id}/deactivations`, {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(researchCohortDeactivateRequestWireSchema.parse({ ...orgFacility() })),
    },
  ));
}

export async function listResearchCohortSnapshots(lease: ContextLeaseWire, cohortId: string): Promise<ResearchCohortSnapshotWire[]> {
  return researchCohortSnapshotWireSchema.array().parse(await request(
    `/research-cohort-snapshots?research_cohort_id=${encodeURIComponent(cohortId)}`,
    { headers: wardHeaders(lease) },
  ));
}

export async function recordResearchCohortSnapshot(
  lease: ContextLeaseWire,
  input: Omit<ResearchCohortSnapshotRequestWire, 'organization_id' | 'facility_id'>,
): Promise<ResearchCohortSnapshotWire> {
  return researchCohortSnapshotWireSchema.parse(await request('/research-cohort-snapshots', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(researchCohortSnapshotRequestWireSchema.parse({ ...orgFacility(), ...input })),
  }));
}

export async function listResearchCohortMembers(lease: ContextLeaseWire, cohortId: string): Promise<ResearchCohortMemberWire[]> {
  return researchCohortMemberWireSchema.array().parse(await request(
    `/research-cohort-members?research_cohort_id=${encodeURIComponent(cohortId)}`,
    { headers: wardHeaders(lease) },
  ));
}

export async function computeResearchCohortMember(
  lease: ContextLeaseWire,
  input: Omit<ResearchCohortMemberComputeRequestWire, 'organization_id' | 'facility_id'>,
): Promise<ResearchCohortMemberWire> {
  return researchCohortMemberWireSchema.parse(await request('/research-cohort-members', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(researchCohortMemberComputeRequestWireSchema.parse({ ...orgFacility(), ...input })),
  }));
}

export async function recomputeResearchCohort(
  lease: ContextLeaseWire,
  cohortId: string,
): Promise<ResearchCohortRecomputeResultWire> {
  return researchCohortRecomputeResultWireSchema.parse(await request(
    `/research-cohorts/${encodeURIComponent(cohortId)}/recomputations`, {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(researchCohortRecomputeRequestWireSchema.parse({ ...orgFacility(), computed_at: new Date().toISOString() })),
    },
  ));
}

// ── 研究数据集请求（状态机） ─────────────────────────────────
export async function listResearchDatasetRequests(lease: ContextLeaseWire): Promise<ResearchDatasetRequestWire[]> {
  return researchDatasetRequestWireSchema.array().parse(await request('/research-dataset-requests', { headers: wardHeaders(lease) }));
}

export async function createResearchDatasetRequest(
  lease: ContextLeaseWire,
  input: Omit<ResearchDatasetRequestCreateRequestWire, 'organization_id' | 'facility_id'>,
): Promise<ResearchDatasetRequestWire> {
  return researchDatasetRequestWireSchema.parse(await request('/research-dataset-requests', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(researchDatasetRequestCreateRequestWireSchema.parse({ ...orgFacility(), ...input })),
  }));
}

export async function approveResearchDatasetRequest(
  lease: ContextLeaseWire,
  requestWire: ResearchDatasetRequestWire,
): Promise<ResearchDatasetRequestWire> {
  return researchDatasetRequestWireSchema.parse(await request(
    `/research-dataset-requests/${requestWire.request_id}/approvals`, {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(researchDatasetRequestApproveRequestWireSchema.parse({
        ...orgFacility(), expected_row_version: requestWire.row_version,
      })),
    },
  ));
}

export async function exportResearchDatasetRequest(
  lease: ContextLeaseWire,
  requestWire: ResearchDatasetRequestWire,
  watermark: string,
): Promise<ResearchDatasetRequestWire> {
  return researchDatasetRequestWireSchema.parse(await request(
    `/research-dataset-requests/${requestWire.request_id}/exports`, {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(researchDatasetRequestExportRequestWireSchema.parse({
        ...orgFacility(), expected_row_version: requestWire.row_version, watermark,
      })),
    },
  ));
}

export async function destroyResearchDatasetRequest(
  lease: ContextLeaseWire,
  requestWire: ResearchDatasetRequestWire,
): Promise<ResearchDatasetRequestWire> {
  return researchDatasetRequestWireSchema.parse(await request(
    `/research-dataset-requests/${requestWire.request_id}/destructions`, {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(researchDatasetRequestDestroyRequestWireSchema.parse({
        ...orgFacility(), expected_row_version: requestWire.row_version,
      })),
    },
  ));
}

export async function downloadResearchDatasetExport(lease: ContextLeaseWire, requestId: string) {
  return requestBinary(`/research-dataset-requests/${encodeURIComponent(requestId)}/exports/file`, wardHeaders(lease));
}

// ── 开源（发布指标快照 + 下载事件） ──────────────────────────
export async function listReleaseMetricSnapshots(
  lease: ContextLeaseWire,
  metricType: string,
): Promise<ReleaseMetricSnapshotWire[]> {
  return releaseMetricSnapshotWireSchema.array().parse(await request(
    `/release-metric-snapshots?metric_type=${encodeURIComponent(metricType)}`,
    { headers: wardHeaders(lease) },
  ));
}

export async function recordReleaseMetricSnapshot(
  lease: ContextLeaseWire,
  input: Omit<ReleaseMetricSnapshotCreateRequestWire, 'organization_id' | 'facility_id'>,
): Promise<ReleaseMetricSnapshotWire> {
  return releaseMetricSnapshotWireSchema.parse(await request('/release-metric-snapshots', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(releaseMetricSnapshotCreateRequestWireSchema.parse({ ...orgFacility(), ...input })),
  }));
}

export async function listReleaseDownloadEvents(lease: ContextLeaseWire, channel?: string): Promise<ReleaseDownloadEventWire[]> {
  const q = channel ? `?channel=${encodeURIComponent(channel)}` : '';
  return releaseDownloadEventWireSchema.array().parse(await request(`/release-download-events${q}`, { headers: wardHeaders(lease) }));
}

export async function recordReleaseDownloadEvent(
  lease: ContextLeaseWire,
  input: Omit<ReleaseDownloadEventCreateRequestWire, 'organization_id' | 'facility_id'>,
): Promise<ReleaseDownloadEventWire> {
  return releaseDownloadEventWireSchema.parse(await request('/release-download-events', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(releaseDownloadEventCreateRequestWireSchema.parse({ ...orgFacility(), ...input })),
  }));
}

export async function countValidReleaseDownloads(lease: ContextLeaseWire, channel?: string): Promise<ReleaseDownloadValidCountWire> {
  const q = channel ? `?channel=${encodeURIComponent(channel)}` : '';
  return releaseDownloadValidCountWireSchema.parse(await request(`/release-download-events/valid-count${q}`, { headers: wardHeaders(lease) }));
}
