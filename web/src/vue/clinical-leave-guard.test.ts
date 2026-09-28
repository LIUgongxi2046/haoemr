import { describe, expect, it, vi } from 'vitest';
import { createClinicalLeaveGate } from './clinical-leave-guard';
describe('clinical leave gate', () => {
  it('retains current context when the editor refuses or saving fails', async () => {
    const gate = createClinicalLeaveGate(); const change = vi.fn();
    gate.register(async () => false);
    if (await gate.confirm()) change();
    gate.register(async () => { throw new Error('save failed'); });
    if (await gate.confirm()) change();
    expect(change).not.toHaveBeenCalled();
  });
  it('does not permit repeated clicks to bypass an unresolved decision', async () => {
    const gate = createClinicalLeaveGate(); let resolve!: (value: boolean) => void;
    gate.register(() => new Promise(done => { resolve = done; }));
    const first = gate.confirm(); expect(await gate.confirm()).toBe(false);
    resolve(true); expect(await first).toBe(true);
  });
  it('disposes only its own editor registration', async () => {
    const gate = createClinicalLeaveGate(); const dispose = gate.register(async () => false);
    gate.register(async () => true); dispose(); expect(await gate.confirm()).toBe(true);
    const last = gate.register(async () => false); last(); expect(await gate.confirm()).toBe(true);
  });
});
