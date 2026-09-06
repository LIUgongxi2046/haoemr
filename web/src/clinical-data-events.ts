/** Notify counters after a successful business write; lease issuance is not a business change. */
const listeners = new Set<() => void>();
export function notifyClinicalWrite(path: string, method = 'GET') {
  if (['GET', 'HEAD'].includes(method.toUpperCase()) || path === '/context-leases') return;
  for (const listener of listeners) listener();
}
export function onClinicalWrite(listener: () => void): () => void {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}
