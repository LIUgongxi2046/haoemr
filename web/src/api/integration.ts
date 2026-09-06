import {
  integrationMessageCollectRequestWireSchema,
  integrationMessageCollectResultWireSchema,
  integrationMessageReconcileRequestWireSchema,
  integrationMessageWireSchema,
  integrationReconciliationWireSchema,
  outboxReconciliationWireSchema,
  type ContextLeaseWire,
  type IntegrationMessageCollectRequestWire,
  type IntegrationMessageCollectResultWire,
  type IntegrationMessageWire,
  type IntegrationReconciliationWire,
  type OutboxReconciliationWire,
} from '../generated/contracts';
import { ClinicalApiError, clinicalContext, issueContextLease, request, wardHeaders } from '../clinical-api';

export function issueIntegrationLease(): Promise<ContextLeaseWire> {
  return issueContextLease(null, null, 'INTEGRATION_OPERATIONS');
}

export async function listIntegrationMessages(
  lease: ContextLeaseWire,
  filters: { connectorCode?: string; status?: string } = {},
): Promise<IntegrationMessageWire[]> {
  const query = new URLSearchParams();
  if (filters.connectorCode) query.set('connector_code', filters.connectorCode);
  if (filters.status) query.set('status', filters.status);
  const suffix = query.size ? `?${query.toString()}` : '';
  return integrationMessageWireSchema.array().parse(await request(`/integration-messages${suffix}`, {
    headers: wardHeaders(lease),
  }));
}

export async function collectIntegrationMessages(
  lease: ContextLeaseWire,
  input: Omit<IntegrationMessageCollectRequestWire, 'organization_id' | 'facility_id'>,
  idempotencyKey = crypto.randomUUID(),
): Promise<IntegrationMessageCollectResultWire> {
  return integrationMessageCollectResultWireSchema.parse(await request('/integration-messages', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(integrationMessageCollectRequestWireSchema.parse({
      ...orgFacility(),
      ...input,
    })),
  }));
}

export async function reconcileIntegrationMessage(
  lease: ContextLeaseWire,
  messageId: string,
  idempotencyKey = crypto.randomUUID(),
): Promise<IntegrationMessageWire> {
  return integrationMessageWireSchema.parse(await request(
    `/integration-messages/${encodeURIComponent(messageId)}/reconcile`, {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify(integrationMessageReconcileRequestWireSchema.parse(orgFacility())),
    },
  ));
}

export async function listIntegrationReconciliations(
  lease: ContextLeaseWire,
  connectorCode?: string,
): Promise<IntegrationReconciliationWire[]> {
  const suffix = connectorCode ? `?connector_code=${encodeURIComponent(connectorCode)}` : '';
  return integrationReconciliationWireSchema.array().parse(await request(
    `/integration-reconciliations${suffix}`, { headers: wardHeaders(lease) },
  ));
}

export async function getOutboxReconciliation(lease: ContextLeaseWire): Promise<OutboxReconciliationWire | null> {
  try {
    return outboxReconciliationWireSchema.parse(await request('/outbox/reconciliation', {
      headers: wardHeaders(lease),
    }));
  } catch (error) {
    // Older dev-synthetic backends may not expose the read-only outbox
    // reconciliation endpoint yet.  The rest of the integration workbench
    // remains usable; only this optional status card is unavailable.
    if (error instanceof ClinicalApiError && error.status === 404) return null;
    throw error;
  }
}

function orgFacility() {
  return {
    organization_id: clinicalContext.organizationId,
    facility_id: clinicalContext.facilityId,
  };
}
