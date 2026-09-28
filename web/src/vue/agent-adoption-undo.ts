export interface AgentAdoptionSnapshot {
  documentId: string;
  runId: string;
  before: Record<string, { present: boolean; value: unknown }>;
  adopted: Record<string, string>;
}
const clone = <T>(value: T): T => value === undefined ? value : JSON.parse(JSON.stringify(value));
export function captureAgentAdoption(documentId: string, runId: string, sections: Record<string, unknown>, draft: Record<string, string>): AgentAdoptionSnapshot {
  return { documentId, runId, adopted: { ...draft }, before: Object.fromEntries(Object.keys(draft).map(key => [key, { present: Object.hasOwn(sections, key), value: clone(sections[key]) }])) };
}
export function undoAgentAdoption(snapshot: AgentAdoptionSnapshot, documentId: string, current: Record<string, unknown>, restoreConflicts: string[] = []) {
  if (snapshot.documentId !== documentId) throw new Error('不能在另一份病历撤销候选');
  const sections = { ...current }; const conflicts: string[] = []; const restored: string[] = [];
  for (const [key, adopted] of Object.entries(snapshot.adopted)) {
    if (current[key] !== adopted) {
      conflicts.push(key);
      if (!restoreConflicts.includes(key)) continue;
    }
    const before = snapshot.before[key]!;
    if (before.present) sections[key] = clone(before.value); else delete sections[key];
    restored.push(key);
  }
  return { sections, conflicts, restored };
}
