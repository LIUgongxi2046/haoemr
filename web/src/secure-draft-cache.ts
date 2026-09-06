export interface SecureDocumentDraft {
  contextFingerprint: string;
  documentId: string;
  baseVersionId: string;
  baseRowVersion: number;
  sections: Record<string, unknown>;
  updatedAt: string;
}

interface StoredCiphertext {
  id: string;
  iv: string;
  ciphertext: string;
  updatedAt: string;
}

const DATABASE_NAME = 'haoemr-session-drafts';
const STORE_NAME = 'encrypted-drafts';
const KEY_NAME = 'haoemr.session-draft-key.v1';

export function clinicalJsonSnapshot(source: Record<string, unknown>): Record<string, unknown> {
  // Document sections are JSON by API contract. This also strips framework proxies
  // before the snapshot crosses WebCrypto, IndexedDB, or HTTP boundaries.
  return JSON.parse(JSON.stringify(source)) as Record<string, unknown>;
}

export async function clinicalContextFingerprint(parts: readonly string[]): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(parts.join('|')));
  return hex(new Uint8Array(digest));
}

export async function encryptDraftPayload(
  rawKey: Uint8Array,
  storageId: string,
  draft: SecureDocumentDraft,
): Promise<StoredCiphertext> {
  const key = await importKey(rawKey);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ciphertext = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv, additionalData: new TextEncoder().encode(storageId) },
    key,
    new TextEncoder().encode(JSON.stringify(draft)),
  );
  return {
    id: storageId,
    iv: base64(iv),
    ciphertext: base64(new Uint8Array(ciphertext)),
    updatedAt: draft.updatedAt,
  };
}

export async function decryptDraftPayload(
  rawKey: Uint8Array,
  stored: StoredCiphertext,
): Promise<SecureDocumentDraft> {
  const key = await importKey(rawKey);
  const plaintext = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv: fromBase64(stored.iv).buffer as ArrayBuffer, additionalData: new TextEncoder().encode(stored.id) },
    key,
    fromBase64(stored.ciphertext).buffer as ArrayBuffer,
  );
  return JSON.parse(new TextDecoder().decode(plaintext)) as SecureDocumentDraft;
}

export async function saveSecureDocumentDraft(storageId: string, draft: SecureDocumentDraft): Promise<void> {
  const stored = await encryptDraftPayload(sessionKey(), storageId, draft);
  await withStore('readwrite', (store) => store.put(stored));
}

export async function loadSecureDocumentDraft(
  storageId: string,
  expectedContextFingerprint: string,
): Promise<SecureDocumentDraft | null> {
  const stored = await withStore<StoredCiphertext | undefined>('readonly', (store) => store.get(storageId));
  if (!stored) return null;
  try {
    const draft = await decryptDraftPayload(sessionKey(), stored);
    if (draft.contextFingerprint !== expectedContextFingerprint || draft.documentId.length === 0) {
      await clearSecureDocumentDraft(storageId);
      return null;
    }
    return draft;
  } catch {
    await clearSecureDocumentDraft(storageId);
    return null;
  }
}

export async function clearSecureDocumentDraft(storageId: string): Promise<void> {
  await withStore('readwrite', (store) => store.delete(storageId));
}

function sessionKey(): Uint8Array {
  let encoded = sessionStorage.getItem(KEY_NAME);
  if (!encoded) {
    const generated = crypto.getRandomValues(new Uint8Array(32));
    encoded = base64(generated);
    sessionStorage.setItem(KEY_NAME, encoded);
  }
  return fromBase64(encoded);
}

async function importKey(rawKey: Uint8Array): Promise<CryptoKey> {
  return crypto.subtle.importKey('raw', rawKey as BufferSource, 'AES-GCM', false, ['encrypt', 'decrypt']);
}

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE_NAME, 1);
    request.onupgradeneeded = () => request.result.createObjectStore(STORE_NAME, { keyPath: 'id' });
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error('Secure draft database is unavailable'));
  });
}

async function withStore<T = IDBValidKey>(
  mode: IDBTransactionMode,
  operation: (store: IDBObjectStore) => IDBRequest<T>,
): Promise<T> {
  const database = await openDatabase();
  try {
    return await new Promise<T>((resolve, reject) => {
      const transaction = database.transaction(STORE_NAME, mode);
      const request = operation(transaction.objectStore(STORE_NAME));
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error ?? new Error('Secure draft operation failed'));
      transaction.onabort = () => reject(transaction.error ?? new Error('Secure draft transaction aborted'));
    });
  } finally {
    database.close();
  }
}

function base64(value: Uint8Array): string {
  let binary = '';
  for (const byte of value) binary += String.fromCharCode(byte);
  return btoa(binary);
}

function fromBase64(value: string): Uint8Array {
  return Uint8Array.from(atob(value), (character) => character.charCodeAt(0));
}

function hex(value: Uint8Array): string {
  return Array.from(value, (byte) => byte.toString(16).padStart(2, '0')).join('');
}
