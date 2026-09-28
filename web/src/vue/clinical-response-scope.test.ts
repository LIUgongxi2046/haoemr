import { beforeEach, expect, it, vi } from 'vitest';
const mock = vi.hoisted(() => ({ context: { tenantId: 't', userId: 'u', roleId: 'r', organizationId: 'o', facilityId: 'f', patientId: 'p', encounterId: 'e', documentId: 'd' } }));
vi.mock('../clinical-api', () => ({ clinicalContext: mock.context }));
import { captureResponseScope } from './clinical-response-scope';
beforeEach(() => Object.assign(mock.context, { tenantId: 't', userId: 'u', roleId: 'r', organizationId: 'o', facilityId: 'f', patientId: 'p', encounterId: 'e', documentId: 'd' }));
it.each(Object.keys(mock.context))('rejects a delayed result after %s changes', async key => {
  const isCurrent = captureResponseScope();
  let release!: () => void;
  const response = new Promise<void>(done => { release = done; }).then(() => isCurrent());
  mock.context[key as keyof typeof mock.context] = 'changed'; release();
  expect(await response).toBe(false);
});
it('accepts a response in its original scope', () => expect(captureResponseScope()()).toBe(true));
