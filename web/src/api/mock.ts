import {
  mockInterfaceWireSchema,
  mockInvocationResultWireSchema,
  type ContextLeaseWire,
  type MockInterfaceWire,
  type MockInvocationResultWire,
} from '../generated/contracts';
import { issueContextLease, request, wardHeaders } from '../clinical-api';

export function issueMockLease(): Promise<ContextLeaseWire> {
  return issueContextLease(null, null, 'MOCK_INTERFACE');
}

export async function listMockInterfaces(lease: ContextLeaseWire): Promise<MockInterfaceWire[]> {
  return mockInterfaceWireSchema.array().parse(await request('/mock-interfaces', { headers: wardHeaders(lease) }));
}

export async function invokeMockInterface(
  lease: ContextLeaseWire,
  code: string,
  payload: Record<string, unknown> = {},
  idempotencyKey: string = crypto.randomUUID(),
): Promise<MockInvocationResultWire> {
  return mockInvocationResultWireSchema.parse(await request(`/mock-interfaces/${encodeURIComponent(code)}/invoke`, {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(payload),
  }));
}

export interface MockInterfaceRun {
  run_id: string;
  profile_id: string;
  workbench_id: string;
  interface_code: string;
  scenario: 'SUCCESS' | 'DEGRADED' | 'UNAVAILABLE';
  status: 'COMPLETED' | 'REVIEW_REQUIRED' | 'BLOCKED' | 'FAILED';
  idempotency_key: string;
  profile_version: number;
  record_count: number;
  evidence_hash: string;
  started_at: string;
  completed_at?: string | null;
}

export interface MockInterfaceRunDetail extends MockInterfaceRun {
  request_hash: string;
  payload: Record<string, unknown>;
  agent_assessment: Record<string, unknown>;
  events: Array<Record<string, unknown>>;
  created_by: string;
}

export interface MockInterfaceEvidence {
  run_id: string;
  evidence_hash: string;
  request_hash: string;
  profile_id: string;
  profile_version: number;
  created_by: string;
  started_at: string;
  completed_at: string;
  agent_assessment: Record<string, unknown>;
  events: Array<Record<string, unknown>>;
  verification: string;
}

export async function listMockInterfaceRuns(
  lease: ContextLeaseWire,
  filters: { workbenchId?: string; profileKey?: string } = {},
): Promise<MockInterfaceRun[]> {
  const query = new URLSearchParams();
  if (filters.workbenchId) query.set('workbench_id', filters.workbenchId);
  if (filters.profileKey) query.set('profile_key', filters.profileKey);
  const suffix = query.size ? `?${query.toString()}` : '';
  return await request(`/mock-interfaces/runs${suffix}`, { headers: wardHeaders(lease) }) as MockInterfaceRun[];
}

export async function getMockInterfaceRun(
  lease: ContextLeaseWire,
  runId: string,
): Promise<MockInterfaceRunDetail> {
  return await request(`/mock-interfaces/runs/${encodeURIComponent(runId)}`, {
    headers: wardHeaders(lease),
  }) as MockInterfaceRunDetail;
}

export async function getMockInterfaceEvidence(
  lease: ContextLeaseWire,
  runId: string,
): Promise<MockInterfaceEvidence> {
  return await request(`/mock-interfaces/runs/${encodeURIComponent(runId)}/evidence`, {
    headers: wardHeaders(lease),
  }) as MockInterfaceEvidence;
}
