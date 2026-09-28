import { useQuery } from '@tanstack/vue-query';
import { computed, watch } from 'vue';

import { ClinicalApiError, issueDocumentLease, loadCurrentDocument } from '../../clinical-api';
import { captureResponseScope, clinicalResponseScope } from '../clinical-response-scope';
import { useClinicalContextStore } from '../stores/clinical-context';

export function useCurrentDocument() {
  const contextStore = useClinicalContextStore();
  const contextKey = computed(() => ['clinical', 'current-document', clinicalResponseScope(), contextStore.patientId, contextStore.encounterId, contextStore.documentId]);
  const query = useQuery({
    queryKey: contextKey,
    queryFn: async () => {
      const isCurrent = captureResponseScope();
      const lease = await issueDocumentLease();
      if (!isCurrent()) throw new ClinicalApiError('CONTEXT_CHANGED', '上下文已变化，请重新加载当前病历', 409);
      const document = await loadCurrentDocument(lease);
      if (!isCurrent()) throw new ClinicalApiError('CONTEXT_CHANGED', '上下文已变化，请重新加载当前病历', 409);
      return { lease, document };
    },
    staleTime: 0,
    gcTime: 0,
    retry: false,
  });
  watch(() => query.data.value?.lease, (lease) => {
    if (lease) contextStore.replaceFromLease(lease);
  }, { immediate: true });
  return { ...query, contextKey };
}
