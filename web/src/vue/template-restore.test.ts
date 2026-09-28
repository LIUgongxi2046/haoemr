import { describe, expect, it } from 'vitest';
import type { DocumentTemplateWire } from '../generated/contracts';
import { templateRestorePayload } from './template-restore';

const source = {
  template_id: 'a', template_version_id: 'v1', version_no: 1, lifecycle_status: 'ACTIVE',
  section_schema: { type: 'object', properties: { severity: { type: 'integer', minimum: 0, maximum: 5 }, detail: { type: 'object', properties: { note: { type: 'string' } } } } },
  required_fields: ['severity'], display_rules: { layout: 'specialty', field_order: ['severity', 'detail'], custom: { width: 80 } },
} as unknown as DocumentTemplateWire;
describe('historical template restoration', () => {
  it('preserves nested schema, validation and layout without mutating history', () => {
    const restored = templateRestorePayload(source, { ...source, version_no: 3 });
    expect(restored).toEqual({ restore_from_version_id: source.template_version_id, section_schema: source.section_schema, required_fields: source.required_fields, display_rules: source.display_rules });
    restored.required_fields.push('detail');
    (restored.display_rules.custom as { width: number }).width = 50;
    expect(source.required_fields).toEqual(['severity']);
    expect(source.display_rules.custom).toEqual({ width: 80 });
  });
  it('rejects a different template, an inactive template and nonhistorical versions', () => {
    expect(() => templateRestorePayload(source, { ...source, template_id: 'b', version_no: 3 })).toThrow('同一个有效模板');
    expect(() => templateRestorePayload(source, { ...source, lifecycle_status: 'INACTIVE', version_no: 3 })).toThrow('同一个有效模板');
    expect(() => templateRestorePayload(source, source)).toThrow('历史版本');
  });
});
