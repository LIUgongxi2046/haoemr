import type { DocumentTemplateWire, DocumentTemplateVersionCreateRequestWire } from '../generated/contracts';

/** A restoration is a new draft; copy the complete historical schema, including nested field rules. */
export function templateRestorePayload(
  source: DocumentTemplateWire,
  latest: DocumentTemplateWire,
): Omit<DocumentTemplateVersionCreateRequestWire, 'expected_template_row_version'> {
  if (source.template_id !== latest.template_id || latest.lifecycle_status !== 'ACTIVE') {
    throw new Error('只能在同一个有效模板内恢复历史版本');
  }
  if (source.version_no >= latest.version_no) throw new Error('请选择早于当前最新版本的历史版本');
  return JSON.parse(JSON.stringify({
    restore_from_version_id: source.template_version_id,
    section_schema: source.section_schema,
    required_fields: source.required_fields,
    display_rules: source.display_rules,
  }));
}
