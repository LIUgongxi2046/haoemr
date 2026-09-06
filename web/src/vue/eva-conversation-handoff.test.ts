import { afterEach, describe, expect, it } from 'vitest';
import { authSession } from '../auth-session';
import type { MedicalAgentRunWire } from '../generated/contracts';
import { evaConversationTransfer, publishEvaConversation, takeEvaConversation, type EvaConversationTransfer } from './eva-conversation-handoff';

function conversation(): EvaConversationTransfer {
  return {
    messages: [{ id: 'user-message', role: 'user', text: '核对当前资料' }],
    patient: { patientId: '', encounterId: '', patientName: '未选择患者', patientSummary: '', scene: '未绑定', label: '待绑定' },
    draft: '下一轮草稿', agentCode: 'SUMMARY', stageCode: 'REVIEW', modelId: 'model',
    authorizationLevel: 'READ_ONLY', contextScopes: ['RECORDS'], pendingRun: null,
  };
}
afterEach(() => { takeEvaConversation(); authSession.token = ''; });

describe('EVA conversation handoff', () => {
  it('transfers every message and selection once, isolated from the closing popup', () => {
    const source = conversation();
    publishEvaConversation(source);
    source.messages[0]!.text = '弹窗已修改';
    source.contextScopes.push('TASKS');
    const restored = takeEvaConversation();
    expect(restored?.messages[0]?.text).toBe('核对当前资料');
    expect(restored?.contextScopes).toEqual(['RECORDS']);
    expect(restored?.draft).toBe('下一轮草稿');
    expect(restored?.patient.patientId).toBe('');
    expect(takeEvaConversation()).toBeNull();
  });

  it('keeps the original pending submission promise instead of creating another task', async () => {
    let finish!: (run: MedicalAgentRunWire) => void;
    const promise = new Promise<MedicalAgentRunWire>(resolve => { finish = resolve; });
    publishEvaConversation({ ...conversation(), pendingRun: { messageId: 'assistant-message', promise } });
    const transferred = takeEvaConversation()!;
    expect(transferred.pendingRun?.promise).toBe(promise);
    const run = { run_id: 'original-run' } as MedicalAgentRunWire;
    finish(run);
    await expect(transferred.pendingRun!.promise).resolves.toBe(run);
  });

  it('clears unconsumed clinical content when the authenticated session changes', () => {
    authSession.token = 'test-session-a';
    publishEvaConversation(conversation());
    authSession.token = 'test-session-b';
    expect(evaConversationTransfer.value).toBeNull();
  });
});
