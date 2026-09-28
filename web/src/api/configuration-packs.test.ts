import { beforeEach, expect, it, vi } from 'vitest';
const mock = vi.hoisted(() => ({ context: { tenantId: 'tenant-a', userId: 'user-a', adminRoleId: 'role-a', organizationId: 'org-a', facilityId: 'facility-a' }, request: vi.fn() }));
vi.mock('../clinical-api', () => ({ clinicalContext: mock.context, adminRequest: mock.request, ClinicalApiError: class extends Error { constructor(public code: string, message: string, public status: number) { super(message); } } }));
import { loadConfigurationPacks, loadConfigurationPackOperation } from './configuration-packs';
beforeEach(() => { mock.request.mockReset(); mock.context.facilityId = 'facility-a'; });
it('rejects a late response after the facility changes', async () => {
  let complete!: (value: unknown) => void;
  mock.request.mockReturnValue(new Promise(resolve => { complete = resolve; }));
  const pending = loadConfigurationPacks();
  mock.context.facilityId = 'facility-b'; complete([]);
  await expect(pending).rejects.toMatchObject({ code: 'CONFIG_PACK_CONTEXT_CHANGED' });
});
it('keeps authorization failure distinct from an empty catalog', async () => {
  const denied = Object.assign(new Error('denied'), { status: 403 });
  mock.request.mockRejectedValue(denied);
  await expect(loadConfigurationPacks()).rejects.toBe(denied);
});
it('applies the same late-context guard to installation source details', async () => {
  mock.request.mockImplementation(async () => { mock.context.facilityId = 'facility-b'; return {}; });
  await expect(loadConfigurationPackOperation('sample-operation')).rejects.toMatchObject({ code: 'CONFIG_PACK_CONTEXT_CHANGED' });
});
