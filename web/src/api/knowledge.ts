import { clinicalContext, issueContextLease, request, wardHeaders } from '../clinical-api';
import {
  knowledgeDocumentCreateRequestWireSchema,
  knowledgeDocumentVersionWireSchema,
  knowledgeDocumentWireSchema,
  knowledgeFeedbackCreateRequestWireSchema,
  knowledgeImportBatchWireSchema,
  knowledgeImportRequestWireSchema,
  knowledgeGraphImportBatchWireSchema,
  knowledgeGraphImportRequestWireSchema,
  knowledgeSearchRequestWireSchema,
  knowledgeSearchResultWireSchema,
  knowledgeSourceRegisterRequestWireSchema,
  knowledgeSourceWireSchema,
  knowledgeVersionCreateRequestWireSchema,
  knowledgeVersionPublishRequestWireSchema,
  knowledgeVersionRetireRequestWireSchema,
  knowledgeVersionSubmitRequestWireSchema,
  type ContextLeaseWire,
  type KnowledgeDocumentCreateRequestWire,
  type KnowledgeDocumentVersionWire,
  type KnowledgeDocumentWire,
  type KnowledgeFeedbackCreateRequestWire,
  type KnowledgeImportBatchWire,
  type KnowledgeGraphImportBatchWire,
  type KnowledgeGraphImportRequestWire,
  type KnowledgeSearchResultWire,
  type KnowledgeSourceRegisterRequestWire,
  type KnowledgeSourceWire,
  type KnowledgeVersionCreateRequestWire,
  type KnowledgeVersionPublishRequestWire,
  type KnowledgeVersionRetireRequestWire,
  type KnowledgeVersionSubmitRequestWire,
} from '../generated/contracts';

/** 知识中心为机构-院区级上下文（无患者），签发一次租约后复用。 */
export function issueKnowledgeLease(): Promise<ContextLeaseWire> {
  return issueContextLease(null, null, 'KNOWLEDGE');
}

function orgFacility() {
  return { organization_id: clinicalContext.organizationId, facility_id: clinicalContext.facilityId };
}

// ── 知识来源 ─────────────────────────────────────────────
export async function listKnowledgeSources(lease: ContextLeaseWire): Promise<KnowledgeSourceWire[]> {
  return knowledgeSourceWireSchema.array().parse(await request('/knowledge-sources', { headers: wardHeaders(lease) }));
}
export async function registerKnowledgeSource(
  lease: ContextLeaseWire,
  input: Omit<KnowledgeSourceRegisterRequestWire, 'organization_id' | 'facility_id'>,
): Promise<KnowledgeSourceWire> {
  return knowledgeSourceWireSchema.parse(await request('/knowledge-sources', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(knowledgeSourceRegisterRequestWireSchema.parse({ ...orgFacility(), ...input })),
  }));
}
export async function importKnowledgeSource(
  lease: ContextLeaseWire,
  sourceId: string,
  selectionMatrixVersion: string,
): Promise<KnowledgeImportBatchWire> {
  return knowledgeImportBatchWireSchema.parse(await request(`/knowledge-sources/${sourceId}/imports`, {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(knowledgeImportRequestWireSchema.parse({ ...orgFacility(), selection_matrix_version: selectionMatrixVersion })),
  }));
}

/** 受控图谱写入：仅接受已登记来源与审核词典概念，服务端生成批次、清单哈希及审计证据。 */
export async function importKnowledgeGraph(
  lease: ContextLeaseWire,
  input: Omit<KnowledgeGraphImportRequestWire, 'organization_id' | 'facility_id'>,
): Promise<KnowledgeGraphImportBatchWire> {
  return knowledgeGraphImportBatchWireSchema.parse(await request('/knowledge-graph/imports', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(knowledgeGraphImportRequestWireSchema.parse({ ...orgFacility(), ...input })),
  }));
}

// ── 知识文档与版本治理 ─────────────────────────────────────
export async function listKnowledgeDocuments(lease: ContextLeaseWire): Promise<KnowledgeDocumentWire[]> {
  return knowledgeDocumentWireSchema.array().parse(await request('/knowledge-documents', { headers: wardHeaders(lease) }));
}
export async function createKnowledgeDocument(
  lease: ContextLeaseWire,
  input: Omit<KnowledgeDocumentCreateRequestWire, 'organization_id' | 'facility_id'>,
): Promise<KnowledgeDocumentWire> {
  return knowledgeDocumentWireSchema.parse(await request('/knowledge-documents', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(knowledgeDocumentCreateRequestWireSchema.parse({ ...orgFacility(), ...input })),
  }));
}
export async function createKnowledgeVersion(
  lease: ContextLeaseWire,
  documentId: string,
  input: Omit<KnowledgeVersionCreateRequestWire, 'organization_id' | 'facility_id'>,
): Promise<KnowledgeDocumentVersionWire> {
  return knowledgeDocumentVersionWireSchema.parse(await request(`/knowledge-documents/${documentId}/versions`, {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(knowledgeVersionCreateRequestWireSchema.parse({ ...orgFacility(), ...input })),
  }));
}
export async function submitKnowledgeVersion(
  lease: ContextLeaseWire,
  versionId: string,
  input: Omit<KnowledgeVersionSubmitRequestWire, 'organization_id' | 'facility_id'>,
): Promise<KnowledgeDocumentVersionWire> {
  return knowledgeDocumentVersionWireSchema.parse(await request(`/knowledge-versions/${versionId}/submissions`, {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(knowledgeVersionSubmitRequestWireSchema.parse({ ...orgFacility(), ...input })),
  }));
}
export async function publishKnowledgeVersion(
  lease: ContextLeaseWire,
  versionId: string,
  input: Omit<KnowledgeVersionPublishRequestWire, 'organization_id' | 'facility_id'>,
): Promise<KnowledgeDocumentVersionWire> {
  return knowledgeDocumentVersionWireSchema.parse(await request(`/knowledge-versions/${versionId}/publications`, {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(knowledgeVersionPublishRequestWireSchema.parse({ ...orgFacility(), ...input })),
  }));
}
export async function retireKnowledgeVersion(
  lease: ContextLeaseWire,
  versionId: string,
  input: Omit<KnowledgeVersionRetireRequestWire, 'organization_id' | 'facility_id'>,
): Promise<KnowledgeDocumentVersionWire> {
  return knowledgeDocumentVersionWireSchema.parse(await request(`/knowledge-versions/${versionId}/retirements`, {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(knowledgeVersionRetireRequestWireSchema.parse({ ...orgFacility(), ...input })),
  }));
}

// ── 检索与反馈 ─────────────────────────────────────────────
export async function searchKnowledge(lease: ContextLeaseWire, query: string): Promise<KnowledgeSearchResultWire> {
  return knowledgeSearchResultWireSchema.parse(await request('/knowledge-search', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json' },
    body: JSON.stringify(knowledgeSearchRequestWireSchema.parse({ ...orgFacility(), query, limit: 20 })),
  }));
}
export async function createKnowledgeFeedback(
  lease: ContextLeaseWire,
  input: Omit<KnowledgeFeedbackCreateRequestWire, 'organization_id' | 'facility_id'>,
): Promise<void> {
  await request('/knowledge-feedback', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(knowledgeFeedbackCreateRequestWireSchema.parse({ ...orgFacility(), ...input })),
  });
}
