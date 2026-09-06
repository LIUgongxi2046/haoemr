import { computed, onScopeDispose, ref, shallowRef, watch } from 'vue';
import { loadDocumentGovernance } from '../../clinical-api';
import type { ContextLeaseWire, DocumentGovernanceSnapshotWire, DocumentVersionWire } from '../../generated/contracts';
import { currentRecordQc } from '../record-qc';

type Context = { lease: ContextLeaseWire; document: DocumentVersionWire };

/** Isolated read model: never refetches/replaces the editable document or writes QC. */
export function useRecordQcSnapshot(context: () => Context | undefined, dirty: () => boolean,
  loader: (value: Context) => Promise<DocumentGovernanceSnapshotWire> = value =>
    loadDocumentGovernance(value.lease, value.document.document_id, value.document.document_version_id)) {
  const snapshot = shallowRef<DocumentGovernanceSnapshotWire | null>(null);
  const loading = ref(false);
  const error = shallowRef<unknown>(null);
  let requestGeneration = 0;
  let contextGeneration = 0;
  let disposed = false;
  const key = computed(() => {
    const value = context();
    return value ? JSON.stringify([
      value.lease.lease_id, value.lease.tenant_id, value.lease.user_id,
      value.lease.organization_id, value.lease.facility_id, value.lease.authorization_watermark,
      value.lease.patient_id, value.lease.encounter_id,
      value.document.document_id, value.document.document_version_id, value.document.content_hash,
    ]) : '';
  });

  // A dirty -> saved round-trip must not revive an earlier in-flight response.
  function captureContext() {
    const generation = contextGeneration;
    const capturedKey = key.value;
    return () => !disposed && generation === contextGeneration && capturedKey === key.value && !dirty();
  }

  async function refresh() {
    const value = context();
    const request = ++requestGeneration;
    const isCurrent = captureContext();
    snapshot.value = null;
    error.value = null;
    loading.value = false;
    if (!value || dirty() || disposed) return;
    loading.value = true;
    try {
      const next = await loader(value);
      if (request !== requestGeneration || !isCurrent()) return;
      if (next.document_id !== value.document.document_id
        || !currentRecordQc(next, value.document.document_version_id, value.document.content_hash, false)) {
        throw new Error('质控证据版本不匹配，请重新加载');
      }
      snapshot.value = next;
    } catch (cause) {
      if (request === requestGeneration && isCurrent()) error.value = cause;
    } finally {
      if (request === requestGeneration && isCurrent()) loading.value = false;
    }
  }

  watch([key, dirty], () => {
    contextGeneration += 1;
    void refresh();
  }, { immediate: true, flush: 'sync' });
  onScopeDispose(() => { disposed = true; contextGeneration += 1; requestGeneration += 1; });
  const findings = computed(() => snapshot.value?.quality_findings.filter(item =>
    item.document_version_id === context()?.document.document_version_id
    && ['OPEN', 'ACKNOWLEDGED'].includes(item.state)) ?? []);
  return { snapshot, findings, loading, error, refresh, captureContext };
}
