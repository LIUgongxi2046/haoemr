import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';
import { clinicalLeaveGate } from '../clinical-leave-guard';

export function useUnsavedLeave(options: { dirty: () => boolean; busy: () => boolean; save: () => Promise<void>; discard: () => Promise<void> }) {
  const open = ref(false);
  const working = ref(false);
  const message = ref('');
  const busy = computed(() => working.value || options.busy());
  let resolve: ((leave: boolean) => void) | undefined;
  let resolveBusy: ((ready: boolean) => void) | undefined;
  const stopBusyWatch = watch(() => options.busy(), running => {
    if (!running) { const pending = resolveBusy; resolveBusy = undefined; pending?.(true); }
  });
  function finish(leave: boolean) { const pending = resolve; resolve = undefined; open.value = false; pending?.(leave); }
  async function confirm() {
    // Finish an in-flight command before deciding whether there is still an unsaved draft.
    if (options.busy() && !await new Promise<boolean>(done => { resolveBusy = done; })) return false;
    if (!options.dirty()) return true;
    if (resolve) return false;
    message.value = ''; open.value = true;
    return new Promise<boolean>(done => { resolve = done; });
  }
  const dispose = clinicalLeaveGate.register(confirm);
  onBeforeRouteLeave(() => clinicalLeaveGate.confirm());
  function beforeUnload(event: BeforeUnloadEvent) {
    if (options.dirty() || options.busy()) { event.preventDefault(); event.returnValue = ''; }
  }
  onMounted(() => window.addEventListener('beforeunload', beforeUnload));
  onBeforeUnmount(() => { window.removeEventListener('beforeunload', beforeUnload); stopBusyWatch(); resolveBusy?.(false); resolveBusy = undefined; dispose(); finish(false); });
  async function saveAndLeave() {
    if (busy.value) return;
    working.value = true;
    try {
      if (options.dirty()) await options.save();
      if (options.dirty() || options.busy()) message.value = '保存尚未成功，请留在当前病历处理错误或冲突。';
      else finish(true);
    } catch { message.value = '保存失败，请留在当前病历检查后重试。'; }
    finally { working.value = false; }
  }
  async function discardAndLeave() {
    if (busy.value) return;
    working.value = true;
    try { await options.discard(); finish(true); }
    catch { message.value = '未能清除本地修改，请留在当前病历检查。'; }
    finally { working.value = false; }
  }
  return { open, busy, message, saveAndLeave, discardAndLeave, stay: () => { if (!busy.value) finish(false); } };
}
