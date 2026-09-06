import { computed, onScopeDispose, ref, shallowRef, watch } from 'vue';
import type { ContextLeaseWire, DocumentVersionWire } from '../../generated/contracts';

export type RecordQcContext = { lease: ContextLeaseWire; document: DocumentVersionWire };

/** Explicit save completion, not page loading/refetching, is the only automatic trigger. */
export function useRecordQcAutomation(options: {
  context: () => RecordQcContext | null | undefined;
  dirty: () => boolean;
  run: (value: RecordQcContext) => Promise<unknown>;
  refresh: () => Promise<unknown>;
}) {
  // Deliberately session-local and opt-in. No PHI or preference in persistent storage.
  const enabled = ref(false);
  const running = ref(false);
  const error = shallowRef<unknown>(null);
  let generation = 0;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let queuedTarget = '';
  let disposed = false;
  const targetKey = computed(() => {
    const value = options.context();
    return value ? JSON.stringify([value.lease.tenant_id, value.lease.organization_id,
      value.lease.facility_id, value.lease.user_id, value.lease.role_assignment_ids,
      value.lease.patient_id, value.lease.encounter_id,
      value.document.document_id, value.document.document_version_id, value.document.content_hash]) : '';
  });
  const key = computed(() => JSON.stringify([targetKey.value,
    options.context()?.lease.lease_id, options.context()?.lease.authorization_watermark]));
  function cancel() {
    generation += 1; clearTimeout(timer); timer = undefined;
    queuedTarget = '';
    running.value = false; error.value = null;
  }
  watch([key, options.dirty, enabled], (next, previous) => {
    // Successful saves also publish a query invalidation that renews the lease.
    // Rebind only an already queued save to a fresh lease for the identical
    // patient/user/roles/document/version. Never use the superseded credential.
    const rebind = Boolean(timer) && next[0] !== previous[0] && queuedTarget === targetKey.value
      && enabled.value && !options.dirty();
    cancel();
    if (rebind) afterSave();
  }, { flush: 'sync' });
  function afterSave() {
    cancel();
    const value = options.context();
    if (!enabled.value || !value || options.dirty() || value.document.status !== 'DRAFT' || disposed) return;
    const currentGeneration = generation;
    const capturedKey = key.value;
    queuedTarget = targetKey.value;
    const stillCurrent = () => !disposed && enabled.value && currentGeneration === generation
      && capturedKey === key.value && !options.dirty();
    // Collapse rapid saves without making the save transaction wait for quality checks.
    timer = setTimeout(async () => {
      if (!stillCurrent()) return;
      timer = undefined; running.value = true;
      try {
        await options.run(value);
        if (stillCurrent()) await options.refresh();
      } catch (cause) {
        if (stillCurrent()) error.value = cause;
      } finally {
        if (stillCurrent()) running.value = false;
      }
    }, 750);
  }
  onScopeDispose(() => { disposed = true; cancel(); });
  return { enabled, running, error, afterSave, cancel };
}
