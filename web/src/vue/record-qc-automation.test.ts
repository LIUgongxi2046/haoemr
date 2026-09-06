import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { effectScope, ref } from 'vue';
import { useRecordQcAutomation, type RecordQcContext } from './composables/use-record-qc-automation';
import { recordQcWritingHints } from './record-qc';

const scopes: ReturnType<typeof effectScope>[] = [];
function setup() {
  const value = ref({ lease: { tenant_id: 't', user_id: 'u', patient_id: 'p', encounter_id: 'e', lease_id: 'l' },
    document: { document_id: 'd', document_version_id: 'v1', content_hash: 'h1', status: 'DRAFT' } } as RecordQcContext);
  const dirty = ref(false);
  const run = vi.fn().mockResolvedValue([]); const refresh = vi.fn().mockResolvedValue(undefined);
  const scope = effectScope(); scopes.push(scope);
  const qc = scope.run(() => useRecordQcAutomation({ context: () => value.value, dirty: () => dirty.value, run, refresh }))!;
  return { value, dirty, run, refresh, scope, qc };
}
beforeEach(() => vi.useFakeTimers());
afterEach(() => { scopes.splice(0).forEach(scope => scope.stop()); vi.useRealTimers(); });
describe('保存后质控不干扰原保存链', () => {
  it('默认关闭；开启和页面载入本身都不触发写入', async () => {
    const { qc, run } = setup(); qc.afterSave(); await vi.runAllTimersAsync();
    expect(run).not.toHaveBeenCalled(); qc.enabled.value = true; await vi.runAllTimersAsync();
    expect(run).not.toHaveBeenCalled();
  });
  it('保存完成信号不等待检查，重复信号防抖到一次', async () => {
    const { qc, run, refresh, value } = setup(); qc.enabled.value = true;
    expect(qc.afterSave()).toBeUndefined(); qc.afterSave();
    await vi.advanceTimersByTimeAsync(749); expect(run).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1); expect(run).toHaveBeenCalledExactlyOnceWith(value.value);
    expect(refresh).toHaveBeenCalledOnce(); expect(qc.running.value).toBe(false);
  });
  it('重新编辑撤销待执行任务，不检查未保存正文', async () => {
    const { qc, run, dirty } = setup(); qc.enabled.value = true; qc.afterSave(); dirty.value = true;
    await vi.runAllTimersAsync(); expect(run).not.toHaveBeenCalled();
    qc.afterSave(); await vi.runAllTimersAsync(); expect(run).not.toHaveBeenCalled();
  });
  it.each(['patient_id', 'encounter_id', 'user_id', 'organization_id', 'facility_id'] as const)('切换 %s 撤销排队检查', async field => {
    const { qc, run, value } = setup(); qc.enabled.value = true; qc.afterSave();
    value.value.lease[field] = 'changed'; await vi.runAllTimersAsync(); expect(run).not.toHaveBeenCalled();
  });
  it('保存引起同范围租约更新时，只使用最新授权重新排队一次', async () => {
    const { qc, run, value } = setup(); qc.enabled.value = true; qc.afterSave();
    const oldLease = value.value.lease;
    value.value = { ...value.value, lease: { ...oldLease, lease_id: 'renewed', authorization_watermark: 'renewed-mark' } };
    await vi.runAllTimersAsync(); expect(run).toHaveBeenCalledOnce();
    expect(run.mock.calls[0][0].lease).not.toBe(oldLease);
    expect(run.mock.calls[0][0].lease.lease_id).toBe('renewed');
  });
  it('岗位授权变化不可按续租重新排队', async () => {
    const { qc, run, value } = setup(); qc.enabled.value = true; qc.afterSave();
    value.value.lease.role_assignment_ids = ['different-role']; await vi.runAllTimersAsync();
    expect(run).not.toHaveBeenCalled();
  });
  it('连续保存只执行最新版本', async () => {
    const { qc, run, value } = setup(); qc.enabled.value = true; qc.afterSave();
    value.value.document = { ...value.value.document, document_version_id: 'v2', content_hash: 'h2' }; qc.afterSave();
    await vi.runAllTimersAsync(); expect(run).toHaveBeenCalledOnce();
    expect(run.mock.calls[0][0].document.document_version_id).toBe('v2');
  });
  it('运行失败不修改正文，明确报错并允许显式重试', async () => {
    const { qc, run, refresh, value } = setup(); const before = JSON.stringify(value.value);
    run.mockRejectedValueOnce(new Error('503')); qc.enabled.value = true; qc.afterSave();
    await vi.runAllTimersAsync(); expect(qc.error.value).toBeTruthy(); expect(refresh).not.toHaveBeenCalled();
    expect(JSON.stringify(value.value)).toBe(before); qc.afterSave(); await vi.runAllTimersAsync();
    expect(qc.error.value).toBeNull(); expect(refresh).toHaveBeenCalledOnce();
  });
  it.each(['success', 'failure'])('运行中切换患者忽略晚到的 %s', async result => {
    const { qc, run, refresh, value } = setup(); let resolve!: () => void; let reject!: (error: Error) => void;
    run.mockImplementation(() => new Promise<void>((yes, no) => { resolve = yes; reject = no; }));
    qc.enabled.value = true; qc.afterSave(); await vi.advanceTimersByTimeAsync(750);
    value.value = { ...value.value, lease: { ...value.value.lease, patient_id: 'new-patient' } };
    if (result === 'success') resolve(); else reject(new Error('old patient'));
    await Promise.resolve(); await Promise.resolve(); expect(refresh).not.toHaveBeenCalled(); expect(qc.error.value).toBeNull();
  });
  it('关闭开关、离开页面或签署后不自动运行', async () => {
    const { qc, run, scope, value } = setup(); qc.enabled.value = true; qc.afterSave(); qc.enabled.value = false;
    await vi.runAllTimersAsync(); expect(run).not.toHaveBeenCalled();
    qc.enabled.value = true; value.value.document.status = 'SIGNED'; qc.afterSave();
    await vi.runAllTimersAsync(); expect(run).not.toHaveBeenCalled();
    value.value.document.status = 'DRAFT'; qc.afterSave(); scope.stop(); await vi.runAllTimersAsync();
    expect(run).not.toHaveBeenCalled();
  });
});
describe('本地书写提示边界', () => {
  const fields = { a: '主诉', b: '现病史', c: '评估' };
  it('重复章节逐目标提示，不重复计数也不回传正文', () => {
    const hints = recordQcWritingHints({ a: '合成病例重复内容一段', b: ' 合成病例 重复内容一段 ', c: '合成病例重复内容一段' }, fields);
    expect(hints.map(hint => hint.field)).toEqual(['b', 'c']);
    expect(JSON.stringify(hints)).not.toContain('合成病例');
  });
  it('短文本、非字符串、未渲染字段和超长文本不误判', () => {
    expect(recordQcWritingHints({ a: '无', b: '无', c: {} }, fields)).toEqual([]);
    expect(recordQcWritingHints({ a: '12345678', hidden: '12345678' }, fields)).toEqual([]);
    expect(recordQcWritingHints({ a: 'a'.repeat(32_769), b: 'a'.repeat(32_769) }, fields)).toEqual([]);
  });
});
