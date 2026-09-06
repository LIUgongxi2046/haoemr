import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import {
  clearActiveOutpatientContext,
  persistActiveOutpatientContext,
  restoreActiveOutpatientContext,
} from './active-outpatient-context';

class MemorySessionStorage implements Storage {
  private readonly values = new Map<string, string>();
  get length() { return this.values.size; }
  clear() { this.values.clear(); }
  getItem(key: string) { return this.values.get(key) ?? null; }
  key(index: number) { return [...this.values.keys()][index] ?? null; }
  removeItem(key: string) { this.values.delete(key); }
  setItem(key: string, value: string) { this.values.set(key, value); }
}

const ownerUserId = '018f0000-0000-7000-8000-00000000aa04';
const context = {
  ownerUserId,
  patientId: '018f0000-0000-7000-8000-000000000011',
  encounterId: '018f0000-0000-7000-8000-000000000111',
  patientDisplayName: '门诊患者甲',
  documentId: '018f0000-0000-7000-8000-000000001111',
  selectedAt: '2026-08-31T08:00:00.000Z',
};

beforeEach(() => {
  Object.defineProperty(globalThis, 'sessionStorage', {
    configurable: true,
    value: new MemorySessionStorage(),
  });
});

afterEach(() => {
  clearActiveOutpatientContext();
  Reflect.deleteProperty(globalThis, 'sessionStorage');
});

describe('门诊活动患者与就诊上下文', () => {
  it('仅对选择该患者的用户恢复上下文', () => {
    persistActiveOutpatientContext(context);

    expect(restoreActiveOutpatientContext(ownerUserId)).toEqual(context);
    expect(restoreActiveOutpatientContext('018f0000-0000-7000-8000-00000000aa06')).toBeNull();
  });

  it('拒绝损坏或非 UUID 上下文并清理会话副本', () => {
    sessionStorage.setItem('haoemr.active-outpatient-context.v1', JSON.stringify({
      ...context,
      patientId: '../../wrong-patient',
    }));

    expect(restoreActiveOutpatientContext(ownerUserId)).toBeNull();
    expect(sessionStorage.length).toBe(0);
  });

  it('不允许把无效证件标识落入浏览器会话', () => {
    expect(() => persistActiveOutpatientContext({ ...context, documentId: 'demo-document' }))
      .toThrow('Invalid active outpatient context');
    expect(sessionStorage.length).toBe(0);
  });
});
