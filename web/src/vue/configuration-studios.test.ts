import { describe, expect, it } from 'vitest';

import { configurationStudios } from './configuration-studios';

describe('configuration studio catalog', () => {
  it('defines a structured, versioned editor for all 16 active configuration routes', () => {
    const definitions = Object.values(configurationStudios);
    expect(definitions).toHaveLength(16);
    expect(new Set(definitions.map((item) => item.routeId)).size).toBe(16);
    expect(new Set(definitions.map((item) => item.configType)).size).toBe(16);
    expect(definitions.every((item) => item.fields.length >= 4)).toBe(true);
    expect(definitions.every((item) => item.safetyNote.length >= 12)).toBe(true);
  });

  it('keeps agent configuration explicit about tools, context, evals, and human approval', () => {
    expect(configurationStudios['agent-compose'].fields.map((item) => item.key)).toEqual(expect.arrayContaining([
      'agents', 'skills', 'tools', 'budget_tokens', 'stop_conditions', 'compensation',
    ]));
    expect(configurationStudios['agent-context'].fields.map((item) => item.key)).toEqual(expect.arrayContaining([
      'data_sources', 'allowed_fields', 'redaction_policy', 'freshness_minutes',
    ]));
    expect(configurationStudios['agent-evals'].fields.map((item) => item.key)).toContain('pass_threshold');
  });

  it('requires secret references instead of secret values in system parameters', () => {
    const secretField = configurationStudios['admin-parameters'].fields.find((item) => item.key === 'secret_reference');
    expect(secretField?.defaultValue).toMatch(/^env:\/\//);
  });

  it('does not present release and upgrade records as an automatic delivery engine', () => {
    for (const routeId of ['config-release', 'config-upgrade']) {
      const definition = configurationStudios[routeId];
      expect(definition.subtitle).toContain('受管');
      expect(definition.safetyNote).toContain('不执行');
    }
  });
});
