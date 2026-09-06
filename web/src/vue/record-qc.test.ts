import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createSSRApp, effectScope, h, ref } from 'vue';
import { renderToString } from '@vue/server-renderer';
import type { ContextLeaseWire, DocumentGovernanceSnapshotWire, DocumentVersionWire } from '../generated/contracts';
import { loadDocumentGovernance } from '../clinical-api';
import RecordQcPanel from './components/RecordQcPanel.vue';
import { currentRecordQc, recordQcField } from './record-qc';
import { useRecordQcSnapshot } from './composables/use-record-qc-snapshot';

vi.mock('../clinical-api', () => ({ loadDocumentGovernance: vi.fn() }));
const load = vi.mocked(loadDocumentGovernance);
const fields = { chief_complaint: '主诉', assessment: '临床评估' };
const evidence = (version = 'v1'): DocumentGovernanceSnapshotWire => ({
  document_id: 'doc1', document_version_id: version, document_status: 'DRAFT', content_hash: `hash-${version}`,
  quality_run: {
    quality_run_id: 'run1', document_version_id: version, rule_version: 'test-rules-v1', outcome: 'BLOCKED',
    finding_count: 1, blocking_count: 1, warning_count: 0, content_hash: `hash-${version}`,
    source_watermark: 'synthetic', executed_by: 'doctor1', executed_at: '2026-09-06T08:00:00Z',
  },
  quality_findings: [{ finding_id: 'finding1', document_version_id: version, rule_code: 'REQUIRED',
    rule_version: 'test-rules-v1', severity: 'BLOCKING', message: '主诉未填写', field_path: 'sections.chief_complaint',
    state: 'OPEN', row_version: 1, created_at: '2026-09-06', updated_at: '2026-09-06' }],
  signatures: [], review_decisions: [], data_watermark: 'synthetic',
});
const context = (version = 'v1', patient = 'patient1') => ({
  lease: { lease_id: 'lease1', tenant_id: 'tenant1', user_id: 'doctor1', patient_id: patient, encounter_id: 'encounter1' } as ContextLeaseWire,
  document: { document_id: 'doc1', document_version_id: version, content_hash: `hash-${version}`, sections: {} } as DocumentVersionWire,
});
const scopes: ReturnType<typeof effectScope>[] = [];
function setup() {
  const value = ref<ReturnType<typeof context> | undefined>(context());
  const dirty = ref(false);
  const scope = effectScope(); scopes.push(scope);
  const qc = scope.run(() => useRecordQcSnapshot(() => value.value, () => dirty.value))!;
  return { value, dirty, qc, scope };
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
const flush = async () => { await Promise.resolve(); await Promise.resolve(); };
beforeEach(() => { load.mockReset(); load.mockResolvedValue(evidence()); });
afterEach(() => { scopes.splice(0).forEach(scope => scope.stop()); });

describe('精确章节定位与版本契约', () => {
  it('只接受当前页面实际渲染的完整章节路径', () => {
    expect(recordQcField('sections.chief_complaint', fields)).toBe('chief_complaint');
    for (const path of [null, '', 'chief_complaint', 'sections.unknown', 'sections.assessment.extra', 'sections.__proto__', 'sections.assessment[0]']) {
      expect(recordQcField(path, fields)).toBeNull();
    }
  });
  it('正文修改、版本或内容哈希不同均不沿用检查结论', () => {
    expect(currentRecordQc(evidence(), 'v1', 'hash-v1', false)).not.toBeNull();
    expect(currentRecordQc(evidence(), 'v1', 'hash-v1', true)).toBeNull();
    expect(currentRecordQc(evidence(), 'v2', 'hash-v2', false)).toBeNull();
    expect(currentRecordQc(evidence(), 'v1', 'changed', false)).toBeNull();
    const mixed = evidence(); mixed.quality_run!.document_version_id = 'v0';
    expect(currentRecordQc(mixed, 'v1', 'hash-v1', false)).toBeNull();
  });
});

describe('隔离质控读取，不覆盖草稿', () => {
  it('按租约和不可变版本读取证据', async () => {
    const { qc, value } = setup(); await flush();
    expect(load).toHaveBeenCalledWith(value.value!.lease, 'doc1', 'v1');
    expect(qc.findings.value).toHaveLength(1);
    expect(qc.loading.value).toBe(false);
    expect(value.value!.document.sections).toEqual({});
  });
  it('修改立即清空证据，保存新版本后独立重新读取', async () => {
    const { qc, value, dirty } = setup(); await flush();
    const isCurrent = qc.captureContext();
    dirty.value = true;
    expect(qc.snapshot.value).toBeNull(); expect(qc.findings.value).toEqual([]);
    expect(isCurrent()).toBe(false);
    value.value = context('v2'); load.mockResolvedValue(evidence('v2'));
    dirty.value = false; await flush();
    expect(qc.snapshot.value?.document_version_id).toBe('v2'); expect(isCurrent()).toBe(false);
  });
  it('切换患者后丢弃晚到的成功响应', async () => {
    const first = deferred<DocumentGovernanceSnapshotWire>();
    load.mockReturnValueOnce(first.promise);
    const { qc, value } = setup();
    const next = evidence('v2'); load.mockResolvedValueOnce(next);
    value.value = context('v2', 'patient2'); await flush();
    first.resolve(evidence()); await flush();
    expect(qc.snapshot.value).toEqual(next);
  });
  it('切换患者后丢弃晚到的失败响应', async () => {
    const first = deferred<DocumentGovernanceSnapshotWire>();
    load.mockReturnValueOnce(first.promise);
    const { qc, value } = setup();
    value.value = context('v1', 'patient2'); await flush();
    first.reject(new Error('old patient error')); await flush();
    expect(qc.error.value).toBeNull(); expect(qc.snapshot.value).not.toBeNull();
  });
  it('重新加载期间不显示旧通过状态，只采用最后一次响应', async () => {
    const { qc } = setup(); await flush();
    const older = deferred<DocumentGovernanceSnapshotWire>();
    load.mockReturnValueOnce(older.promise);
    const oldRequest = qc.refresh();
    expect(qc.snapshot.value).toBeNull(); expect(qc.loading.value).toBe(true);
    const latest = evidence(); latest.quality_findings = [];
    load.mockResolvedValueOnce(latest); await qc.refresh();
    older.resolve(evidence()); await oldRequest;
    expect(qc.findings.value).toEqual([]);
  });
  it('读取失败可重试，既不当作通过也不修改病历数据', async () => {
    load.mockRejectedValueOnce(new Error('unavailable'));
    const { qc, value } = setup(); await flush();
    expect(qc.error.value).toBeTruthy(); expect(qc.snapshot.value).toBeNull();
    await qc.refresh();
    expect(qc.error.value).toBeNull(); expect(qc.snapshot.value).not.toBeNull();
    expect(value.value!.document).toEqual(context().document);
  });
  it('拒绝服务端返回的其他文书证据', async () => {
    const wrong = evidence(); wrong.document_id = 'doc-other'; load.mockResolvedValueOnce(wrong);
    const { qc } = setup(); await flush();
    expect(qc.error.value).toBeTruthy(); expect(qc.snapshot.value).toBeNull();
  });
  it('离开页面后不再写入响应', async () => {
    const pending = deferred<DocumentGovernanceSnapshotWire>(); load.mockReturnValueOnce(pending.promise);
    const { qc, scope } = setup(); scope.stop(); pending.resolve(evidence()); await flush();
    expect(qc.snapshot.value).toBeNull();
  });
});

async function render(snapshot: DocumentGovernanceSnapshotWire | null, extra = {}) {
  return renderToString(createSSRApp({ render: () => h(RecordQcPanel, {
    snapshot, versionId: 'v1', contentHash: 'hash-v1', fields, ...extra,
  }) }));
}
describe('质控面板实际组件渲染', () => {
  it('只读预览没有自动运行开关，编辑器显式提供后才出现', async () => {
    expect(await render(evidence())).not.toContain('type="checkbox"');
    expect(await render(evidence(), { autoEnabled: false })).toContain('保存后自动运行规则检查');
  });
  it('自动检查进行中或失败不沿用先前通过标记', async () => {
    const snapshot = evidence(); snapshot.quality_run!.outcome = 'PASSED'; snapshot.quality_findings = [];
    expect(await render(snapshot, { autoRunning: true })).not.toContain('规则检查通过');
    const html = await render(snapshot, { autoEnabled: true, autoError: new Error('503') });
    expect(html).not.toContain('规则检查通过'); expect(html).toContain('正文已保存，但自动质控失败');
  });
  it('历史运行缺少快照保持未知，不用当前问题冒充历史', async () => {
    const snapshot = evidence(); snapshot.quality_run_history = [{ ...snapshot.quality_run!, finding_snapshots: null }];
    const html = await render(snapshot);
    expect(html).toContain('历史运行未保存逐条快照'); expect(html).toContain('历史阶段未记录');
  });
  it('未运行不显示绿勾或通过文案', async () => {
    const snapshot = evidence(); snapshot.quality_run = null; snapshot.quality_findings = [];
    const html = await render(snapshot);
    expect(html).toContain('尚未运行确定性质控'); expect(html).not.toContain('规则检查通过');
  });
  it('只有匹配版本的通过证据才显示规则检查通过', async () => {
    const snapshot = evidence(); snapshot.quality_run!.outcome = 'PASSED'; snapshot.quality_findings = [];
    expect(await render(snapshot)).toContain('规则检查通过');
    for (const extra of [{ dirty: true }, { loading: true }, { error: new Error() }, { versionId: 'v2' }]) {
      expect(await render(snapshot, extra)).not.toContain('规则检查通过');
    }
  });
  it('展示中文严重度、规则依据、章节定位和未知位置降级', async () => {
    const snapshot = evidence();
    expect(await render(snapshot)).toContain('定位：主诉');
    expect(await render(snapshot)).toContain('test-rules-v1');
    snapshot.quality_findings[0].field_path = 'sections.not_in_editor';
    const html = await render(snapshot);
    expect(html).toContain('系统不猜测位置'); expect(html).not.toContain('定位：');
  });
  it('过滤已解决、豁免和其他版本，不隐藏已知悉未解决问题', async () => {
    const snapshot = evidence();
    for (const state of ['RESOLVED', 'WAIVED'] as const) {
      snapshot.quality_findings[0].state = state;
      expect(await render(snapshot)).not.toContain('主诉未填写');
    }
    snapshot.quality_findings[0].state = 'ACKNOWLEDGED';
    expect(await render(snapshot)).toContain('已知悉，未解决');
    snapshot.quality_findings[0].document_version_id = 'v0';
    expect(await render(snapshot)).not.toContain('主诉未填写');
  });
  it('输出转义病历问题消息，不执行外来 HTML', async () => {
    const snapshot = evidence(); snapshot.quality_findings[0].message = '<script>alert(1)</script>';
    const html = await render(snapshot);
    expect(html).not.toContain('<script>'); expect(html).toContain('&lt;script&gt;');
  });
});
