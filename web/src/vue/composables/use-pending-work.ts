import { computed, onMounted, onBeforeUnmount, watch, type Ref } from 'vue';
import { useQueries, useQueryClient } from '@tanstack/vue-query';
import { authSession } from '../../auth-session';
import { clinicalContext } from '../../clinical-api';
import { onClinicalWrite } from '../../clinical-data-events';
import { pendingSources, uniqueTaskScopeRows, type PendingResult } from '../pending-work';

// Bound background fan-out so the navigation cannot starve the active workbench.
let running = 0;
const waiting: Array<() => void> = [];
async function limited<T>(work: () => Promise<T>): Promise<T> {
  if (running >= 4) await new Promise<void>(resolve => waiting.push(resolve));
  else running++;
  try { return await work(); }
  finally { const next = waiting.shift(); if (next) next(); else running--; }
}
export function usePendingWork(mode: Ref<string>) {
  const client = useQueryClient();
  const sources = pendingSources();
  const scope = computed(() => [authSession.user?.user_id, clinicalContext.tenantId, clinicalContext.organizationId,
    clinicalContext.facilityId, clinicalContext.roleId, clinicalContext.adminRoleId,
    clinicalContext.patientId, clinicalContext.encounterId, clinicalContext.emergencyPatientId,
    clinicalContext.emergencyEncounterId, clinicalContext.inpatientPatientId, clinicalContext.inpatientEncounterId,
    clinicalContext.inpatientWardId]);
  const queries = useQueries({ queries: computed(() => {
    const queryScope = [...scope.value];
    const expectedScope = JSON.stringify(queryScope);
    return sources.map(source => ({
    queryKey: ['pending-work', ...queryScope, source.id],
    queryFn: () => {
      return limited(async () => {
        if (JSON.stringify(scope.value) !== expectedScope) throw new Error('统计范围已切换');
        const count = await source.load();
        if (JSON.stringify(scope.value) !== expectedScope) throw new Error('统计范围已切换');
        return count;
      });
    },
    enabled: Boolean(authSession.user), staleTime: 30_000, gcTime: 0, retry: false,
    refetchOnWindowFocus: true,
  })); }) });
  const rows = computed<PendingResult[]>(() => sources.map((source, index) => {
    const query = queries.value[index];
    return { ...source, count: query?.isError ? null : query?.data ?? null,
      state: query?.isError ? 'error' : query?.data === undefined ? 'loading' : 'ready' };
  }));
  function forMenu(menu: string) {
    if (menu === 'clinical-tasks') return rows.value.filter(row => row.id === `tasks-${mode.value}`);
    if (menu === 'clinical') return uniqueTaskScopeRows(rows.value.filter(row => row.id.startsWith('tasks-')), {
      'tasks-outpatient': `${clinicalContext.patientId}:${clinicalContext.encounterId}`,
      'tasks-emergency': `${clinicalContext.emergencyPatientId}:${clinicalContext.emergencyEncounterId}`,
      'tasks-inpatient': `${clinicalContext.inpatientPatientId}:${clinicalContext.inpatientEncounterId}`,
    });
    // Inpatient documentation also generates clinical tasks: keep these as separate queues,
    // and use the ward document worklist for the inpatient badge to avoid double-counting.
    if (menu === 'inpatient') return rows.value.filter(row => row.id === 'ward-documents');
    return rows.value.filter(row => row.menu === menu);
  }
  function refresh() {
    return client.invalidateQueries({ predicate: query => query.queryKey[0] === 'pending-work'
      || !query.queryKey.some(part => typeof part === 'string' && part.includes('lease')) });
  }
  let debounce: ReturnType<typeof setTimeout> | undefined;
  const unsubscribe = onClinicalWrite(() => {
    clearTimeout(debounce);
    debounce = setTimeout(() => { void refresh(); }, 250);
  });
  let interval: ReturnType<typeof setInterval> | undefined;
  onMounted(() => {
    interval = setInterval(() => {
      if (authSession.user && document.visibilityState === 'visible') void refresh();
    }, 60_000);
  });
  watch(scope, () => {
    // Static query keys in older workbenches must not retain the previous patient/role's data.
    void client.resetQueries({ predicate: query => query.queryKey[0] !== 'pending-work' });
  });
  watch(() => authSession.user?.user_id, () => {
    client.removeQueries({ queryKey: ['pending-work'], type: 'inactive' });
  });
  onBeforeUnmount(() => { unsubscribe(); clearTimeout(debounce); clearInterval(interval); });
  return { forMenu, refresh };
}
