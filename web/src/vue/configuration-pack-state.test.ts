import { describe, expect, it } from 'vitest';
import { canConfirmConfigurationPack, configurationPackFailureAction } from './configuration-pack-state';
import type { ConfigurationPackPrecheckWire } from '../generated/contracts';
const check = { state: 'READY', trusted: true, expires_at: '2026-09-10T12:00:00Z' } as ConfigurationPackPrecheckWire;
describe('configuration installation command guards', () => {
  it('requires trusted, unexpired, unchanged precheck', () => {
    const time = Date.parse('2026-09-10T11:59:00Z');
    expect(canConfirmConfigurationPack(check, false, time)).toBe(true);
    expect(canConfirmConfigurationPack(check, true, time)).toBe(false);
    expect(canConfirmConfigurationPack({ ...check, trusted: false }, false, time)).toBe(false);
    expect(canConfirmConfigurationPack({ ...check, state: 'BLOCKED' }, false, time)).toBe(false);
    expect(canConfirmConfigurationPack(check, false, Date.parse(check.expires_at))).toBe(false);
  });
  it('allows retry only after confirmed rollback', () => {
    expect(configurationPackFailureAction('RUNNING')).toBe('QUERY');
    expect(configurationPackFailureAction('RECOVERY_REQUIRED')).toBe('QUERY');
    expect(configurationPackFailureAction('FAILED_ROLLED_BACK')).toBe('RECHECK');
    expect(configurationPackFailureAction('SUCCEEDED')).toBe('TEMPLATES');
  });
});
