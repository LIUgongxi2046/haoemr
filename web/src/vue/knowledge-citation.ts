import type { KnowledgeChunkCitationWire } from '../generated/contracts';
import { ClinicalApiError } from '../clinical-api';

export function verifyKnowledgeCitation(source: { source_id: string; source_version: string; content_hash: string }, citation: KnowledgeChunkCitationWire) {
  if (source.source_id !== citation.chunk_id || source.source_version !== citation.version
      || !source.content_hash || source.content_hash !== citation.content_hash) {
    throw new ClinicalApiError('KNOWLEDGE_CITATION_MISMATCH', '引用与当前读取的版本或内容指纹不一致，请重新检索并核对依据。', 409);
  }
  return citation;
}
