import { afterEach, describe, expect, it, vi } from 'vitest';
const hooks = vi.hoisted(() => ({ unmount: [] as Array<() => void> }));
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn() }));
vi.mock('vue', async importOriginal => ({ ...await importOriginal<typeof import('vue')>(),
  onMounted: vi.fn(), onBeforeUnmount: (cleanup: () => void) => hooks.unmount.push(cleanup) }));
import { nextTick, ref } from 'vue';
import { useUnsavedLeave } from './use-unsaved-leave';
import { clinicalLeaveGate } from '../clinical-leave-guard';
afterEach(() => { vi.stubGlobal('window', { removeEventListener: vi.fn() }); hooks.unmount.splice(0).forEach(cleanup => cleanup()); vi.unstubAllGlobals(); });
describe('navigation during a clinical command', () => {
  it('waits for signing or QC readback without showing a false unsaved dialog', async () => {
    const busy = ref(true);
    const leave = useUnsavedLeave({ dirty: () => false, busy: () => busy.value, save: async () => {}, discard: async () => {} });
    const pending = clinicalLeaveGate.confirm();
    expect(leave.open.value).toBe(false); expect(await clinicalLeaveGate.confirm()).toBe(false);
    busy.value = false; await nextTick();
    expect(await pending).toBe(true); expect(leave.open.value).toBe(false);
  });
  it('keeps failed-save changes pending after the busy operation ends', async () => {
    const busy = ref(true);
    const leave = useUnsavedLeave({ dirty: () => true, busy: () => busy.value, save: async () => {}, discard: async () => {} });
    const pending = clinicalLeaveGate.confirm(); busy.value = false; await nextTick(); await nextTick();
    expect(leave.open.value).toBe(true); leave.stay(); expect(await pending).toBe(false);
  });
  it('closes an outstanding command wait when the editor unmounts', async () => {
    useUnsavedLeave({ dirty: () => false, busy: () => true, save: async () => {}, discard: async () => {} });
    const pending = clinicalLeaveGate.confirm(); vi.stubGlobal('window', { removeEventListener: vi.fn() });
    hooks.unmount.splice(0).forEach(cleanup => cleanup()); expect(await pending).toBe(false);
  });
});
