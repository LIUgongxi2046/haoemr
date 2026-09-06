import { beforeEach, describe, expect, it, vi } from 'vitest';
import { isOpenClinicalTask, pendingDisplay, pendingSources, pendingTotal, requireCompleteWorklist, uniqueTaskScopeRows, type PendingResult } from './pending-work';
import { notifyClinicalWrite, onClinicalWrite } from '../clinical-data-events';
const fixtures = vi.hoisted(() => ({ tasks: [] as { state: string }[], configs: [] as { status: string; payload?: object }[] }));
vi.mock('../clinical-api', () => ({
  issueClinicalTaskLease: vi.fn(async () => ({})), listClinicalTasks: vi.fn(async () => fixtures.tasks),
  issueWardLease: vi.fn(), loadInpatientWorklist: vi.fn(), issueSpecialtySupportLease: vi.fn(), loadSpecialtySupportAssessments: vi.fn(),
}));
vi.mock('../api/config', () => ({ issueConfigurationLease: vi.fn(async () => ({})), listConfigurations: vi.fn(async () => fixtures.configs) }));
beforeEach(() => { fixtures.tasks = []; fixtures.configs = []; });
describe('业务待办统计', () => {
  it('已完成、撤回和过期不会算进未完成任务，领取和分派仍计数', async () => {
    fixtures.tasks = ['PENDING', 'ASSIGNED', 'VIEWED', 'CLAIMED', 'COMPLETED', 'WITHDRAWN', 'EXPIRED'].map(state => ({ state }));
    for (const mode of ['outpatient', 'emergency', 'inpatient']) {
      expect(await pendingSources().find(source => source.id === `tasks-${mode}`)!.load()).toBe(4);
    }
    expect(fixtures.tasks.filter(isOpenClinicalTask)).toHaveLength(4);
    fixtures.tasks = [{ state: 'COMPLETED' }];
    expect(await pendingSources().find(source => source.id === 'tasks-inpatient')!.load()).toBe(0);
  });
  it('已停用和已闭环的质控事项不再作为待办', async () => {
    fixtures.configs = [{ status: 'ACTIVE', payload: { workflow_status: 'OPEN' } }, { status: 'ARCHIVED', payload: { workflow_status: 'OPEN' } }, { status: 'ACTIVE', payload: { workflow_status: 'CLOSED' } }];
    expect(await pendingSources().find(source => source.id === 'quality-cases')!.load()).toBe(1);
  });
  it('草稿、已发布和已归档配置不混入审批发布队列', async () => {
    fixtures.configs = ['DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'ACTIVE', 'ARCHIVED'].map(status => ({ status }));
    expect(await pendingSources().find(source => source.id === 'config-CLINICAL_TASK_RULE')!.load()).toBe(2);
  });
  it('部分接口失败或加载中时不显示成功子集为总数或假零', () => {
    const ready = { state: 'ready' as const, count: 7 };
    expect(pendingTotal([ready, { state: 'error', count: null }])).toBeNull();
    expect(pendingDisplay([ready, { state: 'error', count: null }])).toBe('!');
    expect(pendingDisplay([{ state: 'loading', count: null }])).toBe('…');
    expect(pendingDisplay([{ state: 'ready', count: 0 }])).toBe('0');
    expect(pendingTotal([])).toBeNull();
  });
  it('同一菜单多个队列全部返回后才合计', () => {
    expect(pendingTotal([{ state: 'ready', count: 8 }, { state: 'ready', count: 4 }, { state: 'ready', count: 2 }])).toBe(14);
  });
  it('多个工作域选中同一次就诊时门户不重复累计', () => {
    const rows = ['tasks-outpatient', 'tasks-emergency', 'tasks-inpatient'].map((id, index) => ({ id, count: index === 1 ? 1 : 7, state: 'ready' } as PendingResult));
    const unique = uniqueTaskScopeRows(rows, { 'tasks-outpatient': 'patient-a:encounter-a', 'tasks-emergency': 'patient-b:encounter-b', 'tasks-inpatient': 'patient-a:encounter-a' });
    expect(pendingTotal(unique)).toBe(8);
  });
  it('达到服务端返回上限时不把截断的队列当成完整总数', () => {
    expect(() => requireCompleteWorklist(Array.from({ length: 200 }), 200)).toThrow('返回上限');
    expect(requireCompleteWorklist([1, 2], 200)).toEqual([1, 2]);
  });
  it('所有统计源提供范围和真实处理入口，模拟接口不编造待办', () => {
    const sources = pendingSources();
    expect(new Set(sources.map(source => source.id)).size).toBe(sources.length);
    expect(sources.every(source => source.scope && source.to.startsWith('/'))).toBe(true);
    expect(sources.some(source => source.menu === 'mock-interfaces')).toBe(false);
  });
});
describe('业务写入刷新通知', () => {
  it('查询和授权租约不触发刷新循环，业务写入会通知，卸载后停止通知', () => {
    const refresh = vi.fn(); const unsubscribe = onClinicalWrite(refresh);
    notifyClinicalWrite('/clinical-tasks'); notifyClinicalWrite('/context-leases', 'POST');
    expect(refresh).not.toHaveBeenCalled();
    notifyClinicalWrite('/clinical-tasks/task-one/claims', 'POST');
    notifyClinicalWrite('/configurations/item-one', 'put');
    expect(refresh).toHaveBeenCalledTimes(2);
    unsubscribe(); notifyClinicalWrite('/items/item-one', 'DELETE');
    expect(refresh).toHaveBeenCalledTimes(2);
  });
});
