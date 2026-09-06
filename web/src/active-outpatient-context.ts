export interface ActiveOutpatientContext {
  ownerUserId: string;
  patientId: string;
  encounterId: string;
  patientDisplayName: string;
  documentId: string | null;
  selectedAt: string;
}

const STORAGE_KEY = 'haoemr.active-outpatient-context.v1';
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function validUuid(value: unknown): value is string {
  return typeof value === 'string' && UUID_PATTERN.test(value);
}

export function restoreActiveOutpatientContext(ownerUserId: string): ActiveOutpatientContext | null {
  if (typeof sessionStorage === 'undefined' || !validUuid(ownerUserId)) return null;
  try {
    const value = JSON.parse(sessionStorage.getItem(STORAGE_KEY) || 'null') as Partial<ActiveOutpatientContext> | null;
    if (!value || value.ownerUserId !== ownerUserId || !validUuid(value.patientId) || !validUuid(value.encounterId)) {
      sessionStorage.removeItem(STORAGE_KEY);
      return null;
    }
    if (value.documentId != null && !validUuid(value.documentId)) {
      sessionStorage.removeItem(STORAGE_KEY);
      return null;
    }
    return {
      ownerUserId,
      patientId: value.patientId,
      encounterId: value.encounterId,
      patientDisplayName: typeof value.patientDisplayName === 'string' ? value.patientDisplayName.slice(0, 200) : '',
      documentId: value.documentId ?? null,
      selectedAt: typeof value.selectedAt === 'string' ? value.selectedAt : new Date(0).toISOString(),
    };
  } catch {
    sessionStorage.removeItem(STORAGE_KEY);
    return null;
  }
}

export function persistActiveOutpatientContext(value: ActiveOutpatientContext): void {
  if (typeof sessionStorage === 'undefined') return;
  if (!validUuid(value.ownerUserId) || !validUuid(value.patientId) || !validUuid(value.encounterId)
      || (value.documentId != null && !validUuid(value.documentId))) {
    throw new Error('Invalid active outpatient context');
  }
  sessionStorage.setItem(STORAGE_KEY, JSON.stringify(value));
}

export function clearActiveOutpatientContext(): void {
  if (typeof sessionStorage !== 'undefined') sessionStorage.removeItem(STORAGE_KEY);
}
