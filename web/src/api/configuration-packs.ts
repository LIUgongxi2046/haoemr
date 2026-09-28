import { adminRequest, clinicalContext, ClinicalApiError } from '../clinical-api';
import {
  configurationPackCatalogItemWireSchema, configurationPackPrecheckWireSchema, configurationPackOperationWireSchema,
  type ConfigurationPackPrecheckRequestWire, type ConfigurationPackInstallRequestWire,
} from '../generated/contracts';

export function configurationPackScope() {
  return [clinicalContext.tenantId, clinicalContext.userId, clinicalContext.adminRoleId, clinicalContext.organizationId, clinicalContext.facilityId].join(':');
}
async function scopedRequest(path: string, init?: RequestInit) {
  const context = configurationPackScope();
  const result = await adminRequest(`/admin/configuration-packs${path}`, init);
  if (context !== configurationPackScope()) throw new ClinicalApiError('CONFIG_PACK_CONTEXT_CHANGED', '机构或身份已变化，请重新读取', 409);
  return result;
}
const command = (body: unknown, key: string): RequestInit => ({ method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key }, body: JSON.stringify(body) });
export async function loadConfigurationPacks() { return configurationPackCatalogItemWireSchema.array().parse(await scopedRequest('')); }
export async function precheckConfigurationPack(input: ConfigurationPackPrecheckRequestWire, key: string) {
  return configurationPackPrecheckWireSchema.parse(await scopedRequest('/prechecks', command(input, key)));
}
export async function installConfigurationPack(input: ConfigurationPackInstallRequestWire, key: string) {
  return configurationPackOperationWireSchema.parse(await scopedRequest('/operations', command(input, key)));
}
export async function loadConfigurationPackOperations() {
  const params = new URLSearchParams({ organization_id: clinicalContext.organizationId, facility_id: clinicalContext.facilityId });
  return configurationPackOperationWireSchema.array().parse(await scopedRequest(`/operations?${params}`));
}
export async function reconcileConfigurationPackOperation(id: string) {
  return configurationPackOperationWireSchema.parse(await scopedRequest(`/operations/${encodeURIComponent(id)}/reconcile`, command({}, crypto.randomUUID())));
}

export async function loadConfigurationPackOperation(id: string) {
  return configurationPackOperationWireSchema.parse(await scopedRequest(`/operations/${encodeURIComponent(id)}`));
}
