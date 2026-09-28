import { describe, expect, it } from 'vitest';
import { knowledgeChunkCitationWireSchema, knowledgeSearchResultWireSchema } from '../generated/contracts';
import { verifyKnowledgeCitation } from './knowledge-citation';

const id = '018f0000-0000-7000-8000-000000001001';
const citation = knowledgeChunkCitationWireSchema.parse({ chunk_id: id, doc_version_id: id,
  version: 'v1', title: '本地核对样例', section_path: '说明/范围', text: '测试片段', content_hash: 'a'.repeat(64), classification: 'INTERNAL' });
const source = { source_id: id, source_version: 'v1', content_hash: 'a'.repeat(64) };
describe('exact knowledge citation', () => {
  it('accepts null effective bounds returned by the actual Java response', () => {
    expect(knowledgeChunkCitationWireSchema.parse({ ...citation, effective_from: null, effective_to: null })).toMatchObject({ effective_from: null, effective_to: null });
  });
  it('accepts the referenced version and hash without selecting a newer version', () => {
    expect(verifyKnowledgeCitation(source, citation)).toBe(citation);
  });
  it.each(['source_id', 'source_version', 'content_hash'] as const)('rejects mismatched %s', field => {
    expect(() => verifyKnowledgeCitation({ ...source, [field]: 'different' }, citation)).toThrow('不一致');
  });
  it('parses server-provided section locators in existing search responses', () => {
    expect(knowledgeSearchResultWireSchema.parse({ references: [{ ...source, source_type: 'DOCUMENT_VERSION',
      locator: { section_path: '说明/范围' }, excerpt: '测试', authorization_watermark: 'test', retrieved_at: '2026-09-10T00:00:00Z' }] })
      .references[0]?.locator).toEqual({ section_path: '说明/范围' });
  });
});
