import { computed, onMounted, onBeforeUnmount, ref, watch, type Ref } from 'vue';
import { useQueries, useQueryClient } from '@tanstack/vue-query';
import { authSession } from '../../auth-session';
import { clinicalContext } from '../../clinical-api';
import { onClinicalWrite } from '../../clinical-data-events';
import { pendingSources, uniqueTaskScopeRows, type PendingResult } from '../pending-work';
import { mayRequestPendingSource } from '../pending-request-policy';

// Bound background fan-out so the navigation cannot starve the active workbench.
let running = 0;
const waiting: Array<() => void> = [];
async function limited<T>(work: () => Promise<T>): Promise<T> {
  if (running >= 4) await new Promise<void>(resolve => waiting.push(resolve));
  else running++;
  try { return await work(); }
  finally { const next = waiting.shift(); if (next) next(); else running--; }
}
export function usePendingWork(mode: Ref<string>, activeWorkspace: Ref<string> = mode) {
  const client = useQueryClient();
  const sources = pendingSources();
  const denied = ref(new Set<string>());
  const selectedRole = computed(() => authSession.user?.role_codes[authSession.user.role_assignment_ids.indexOf(clinicalContext.roleId)]);
  const allowed = (id: string) => (id !== 'ward-documents' || Boolean(authSession.user?.authorized_ward_ids?.includes(clinicalContext.inpatientWardId)))
    && mayRequestPendingSource(id, selectedRole.value, denied.value, { ...clinicalContext, workspaceMode: activeWorkspace.value });
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
        if (!allowed(source.id)) throw new Error('当前统计来源已停止请求');
        let count: number;
        try { count = await source.load(); }
        catch (error) {
          if (JSON.stringify(scope.value) === expectedScope && [401, 403].includes(Number((error as { status?: number })?.status))) denied.value = new Set([...denied.value, source.id]);
          throw error;
        }
        if (JSON.stringify(scope.value) !== expectedScope) throw new Error('统计范围已切换');
        return count;
      });
    },
    enabled: Boolean(authSession.user) && allowed(source.id), staleTime: 30_000, gcTime: 0, retry: false,
    refetchOnWindowFocus: true,
  })); }) });
  const rows = computed<PendingResult[]>(() => sources.map((source, index): PendingResult => {
    const query = queries.value[index];
    return { ...source, count: denied.value.has(source.id) || query?.isError ? null : query?.data ?? null,
      state: denied.value.has(source.id) || query?.isError ? 'error' : query?.data === undefined ? 'loading' : 'ready' };
  }).filter(row => denied.value.has(row.id) || allowed(row.id)));
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
  function invalidate() {
    return client.invalidateQueries({ predicate: query => query.queryKey[0] === 'pending-work'
      || !query.queryKey.some(part => typeof part === 'string' && part.includes('lease')) });
  }
  function refresh() { denied.value = new Set(); return invalidate(); }
  let debounce: ReturnType<typeof setTimeout> | undefined;
  const unsubscribe = onClinicalWrite(() => {
    clearTimeout(debounce);
    debounce = setTimeout(() => { void invalidate(); }, 250);
  });
  let interval: ReturnType<typeof setInterval> | undefined;
  onMounted(() => {
    interval = setInterval(() => {
      if (authSession.user && document.visibilityState === 'visible') void invalidate();
    }, 60_000);
  });
  watch(scope, () => {
    denied.value = new Set();
    // Static query keys in older workbenches must not retain the previous patient/role's data.
    void client.resetQueries({ predicate: query => query.queryKey[0] !== 'pending-work' });
  });
  watch(() => authSession.user?.user_id, () => {
    client.removeQueries({ queryKey: ['pending-work'], type: 'inactive' });
  });
  onBeforeUnmount(() => { unsubscribe(); clearTimeout(debounce); clearInterval(interval); });
  return { forMenu, refresh };
}
