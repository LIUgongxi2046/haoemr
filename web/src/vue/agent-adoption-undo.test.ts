import { describe, expect, it } from 'vitest';
import { captureAgentAdoption, undoAgentAdoption } from './agent-adoption-undo';
describe('undo the latest adopted candidate', () => {
  it('restores only adopted fields and retains unrelated subsequent doctor edits', () => {
    const snapshot = captureAgentAdoption('doc', 'run', { chief: 'before', assessment: 'old' }, { chief: 'AI', added: 'AI-added' });
    const result = undoAgentAdoption(snapshot, 'doc', { chief: 'AI', added: 'AI-added', assessment: 'doctor-edited' });
    expect(result.sections).toEqual({ chief: 'before', assessment: 'doctor-edited' });
    expect(result.conflicts).toEqual([]);
  });
  it('preserves overlapping doctor edits by default and exposes a conflict', () => {
    const snapshot = captureAgentAdoption('doc', 'run', { chief: 'before' }, { chief: 'AI' });
    expect(undoAgentAdoption(snapshot, 'doc', { chief: 'doctor edit' })).toMatchObject({ sections: { chief: 'doctor edit' }, conflicts: ['chief'], restored: [] });
    expect(undoAgentAdoption(snapshot, 'doc', { chief: 'doctor edit' }, ['chief']).sections).toEqual({ chief: 'before' });
  });
  it('distinguishes absent values from empty fields and never mutates the source', () => {
    const before = { chief: '' }; const snapshot = captureAgentAdoption('doc', 'run', before, { chief: 'AI' });
    const current = { chief: 'AI' }; expect(undoAgentAdoption(snapshot, 'doc', current).sections).toEqual(before);
    expect(current).toEqual({ chief: 'AI' });
  });
  it('refuses to apply a previous document snapshot', () => {
    expect(() => undoAgentAdoption(captureAgentAdoption('a', 'run', {}, { chief: 'AI' }), 'b', {})).toThrow('另一份病历');
  });
});
