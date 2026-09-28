/** One active editor owns the leave decision; a second click cannot bypass a pending decision. */
export function createClinicalLeaveGate() {
  let guard: (() => Promise<boolean>) | undefined;
  let pending = false;
  return {
    register(next: () => Promise<boolean>) {
      guard = next;
      return () => { if (guard === next) guard = undefined; };
    },
    async confirm() {
      if (pending) return false;
      if (!guard) return true;
      pending = true;
      try { return await guard(); } catch { return false; } finally { pending = false; }
    },
  };
}
export const clinicalLeaveGate = createClinicalLeaveGate();
