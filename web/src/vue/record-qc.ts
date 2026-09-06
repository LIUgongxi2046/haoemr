import type { DocumentGovernanceSnapshotWire } from '../generated/contracts';

/** Local writing hints are advisory only: no requests, persisted findings or sign gates. */
export function recordQcWritingHints(sections: Record<string, unknown>, fields: Record<string, string>) {
  const firstByText = new Map<string, string>();
  const hints: Array<{ field: string; message: string }> = [];
  for (const field of Object.keys(fields)) {
    const text = sections[field];
    // Bound work and do not compare truncated text (which could produce false matches).
    if (typeof text !== 'string' || text.length > 32_768) continue;
    const normalized = text.replace(/\s+/g, '');
    if (normalized.length < 8) continue;
    const first = firstByText.get(normalized);
    if (first) hints.push({ field, message: `${fields[field]}与${fields[first]}内容相同，请核对是否误复制。` });
    else firstByText.set(normalized, field);
  }
  return hints;
}

/** Only exact, rendered section keys are locatable; never guess a clinical anchor. */
export function recordQcField(path: string | null | undefined, fields: Record<string, string>): string | null {
  const key = /^sections\.([A-Za-z0-9_]+)$/.exec(path ?? '')?.[1];
  return key && Object.hasOwn(fields, key) ? key : null;
}

export function currentRecordQc(
  snapshot: DocumentGovernanceSnapshotWire | null | undefined,
  versionId: string,
  contentHash: string,
  dirty: boolean,
): DocumentGovernanceSnapshotWire | null {
  if (dirty || !snapshot || snapshot.document_version_id !== versionId || snapshot.content_hash !== contentHash) return null;
  const run = snapshot.quality_run;
  if (run && (run.document_version_id !== versionId || run.content_hash !== contentHash)) return null;
  return snapshot;
}
