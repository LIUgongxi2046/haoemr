import { describe, expect, it } from 'vitest';

import type { MedicalAgentRunWire } from '../generated/contracts';
import { presentMedicalAgentEvents, presentMedicalAgentResult } from './medical-agent-run-presenter';

function runFixture(): MedicalAgentRunWire {
  return {
    run_id: '10000000-0000-0000-0000-000000000001',
    context_lease_id: '10000000-0000-0000-0000-000000000002',
    root_agent_code: 'EVA', root_agent_version: '1.0.0', composition_code: 'EVA_OPD', composition_version: '1.0.0',
    requested_stage: 'OPD', patient_id: '10000000-0000-0000-0000-000000000003', encounter_id: '10000000-0000-0000-0000-000000000004',
    target_type: 'ENCOUNTER', target_id: '10000000-0000-0000-0000-000000000004', objective: '汇总本次就诊', state: 'WAITING_FOR_REVIEW',
    sequence: 8, output: { summary: 'AI医助 EVA 已完成汇总。', execution_mode: 'SYNTHETIC_MODEL' },
    created_at: '2026-08-30T08:00:00Z', completed_at: '2026-08-30T08:00:01Z', row_version: 2,
    attempt: 1, max_attempts: 3, cancel_requested_at: null, failure_code: null,
    child_runs: [{
      child_run_id: '10000000-0000-0000-0000-000000000005', child_agent_code: 'OPD_SUMMARY', display_name: '门诊摘要医助',
      display_role: '整理就诊依据', current_action: '汇总记录', contribution_label: '就诊摘要', state: 'COMPLETED', critical: true,
      contribution: { summary: '已找到两项临床依据。', facts: ['现病史已读取', '检验结果已读取'], gaps: ['用药史待确认'], warnings: ['需医生审阅'] },
      source_references: [], started_at: '2026-08-30T08:00:00Z', completed_at: '2026-08-30T08:00:01Z',
    }],
    events: [
      { sequence: 1, event_type: 'ToolCompleted', child_run_id: '10000000-0000-0000-0000-000000000005', payload: { tool_code: 'CLINICAL_RESULT_READ', item_count: 2, duration_ms: 17 }, occurred_at: '2026-08-30T08:00:00Z' },
      { sequence: 2, event_type: 'ModelGenerationCompleted', child_run_id: '10000000-0000-0000-0000-000000000005', payload: { execution_mode: 'SYNTHETIC_MODEL', total_tokens: 128, duration_ms: 4 }, occurred_at: '2026-08-30T08:00:01Z' },
    ],
  };
}

describe('medical agent run presenter', () => {
  it('provides structured Markdown for display while preserving plain text for business handoffs', () => {
    const result = presentMedicalAgentResult(runFixture(), 'markdown');
    expect(result).toContain('### 门诊摘要医助\n\n已找到两项临床依据');
    expect(result).toContain('#### 关键依据');
    expect(result).toContain('- 现病史已读取');
    expect(presentMedicalAgentResult(runFixture())).toContain('• 现病史已读取');
  });
  it('shows actual tool and model execution evidence in the conversation trajectory', () => {
    const events = presentMedicalAgentEvents(runFixture(), '机构模型 · 检查检验');
    expect(events[0]).toMatchObject({ label: '诊疗工具读取完成', detail: 'CLINICAL_RESULT_READ · 2 条 · 17ms', status: 'done' });
    expect(events[1].detail).toBe('仿真模型 · 128 tokens · 4ms');
  });

  it('renders child assistant facts, gaps and a visible synthetic-model notice', () => {
    const result = presentMedicalAgentResult(runFixture());
    expect(result).toContain('【门诊摘要医助】已找到两项临床依据');
    expect(result).toContain('• 现病史已读取');
    expect(result).toContain('待确认：');
    expect(result).toContain('当前为开发环境测试模型，不是临床结论。');
  });

  it('renders durable queue, cancellation and retry trajectory in doctor-facing language', () => {
    const queued = { ...runFixture(), state: 'QUEUED' as const, attempt: 0, completed_at: null };
    expect(presentMedicalAgentResult(queued)).toContain('后台队列');
    const cancelled = {
      ...queued,
      state: 'CANCELLED' as const,
      events: [{ sequence: 9, event_type: 'RunCancelled', child_run_id: null, payload: { reason: '医生取消' }, occurred_at: '2026-08-30T08:00:02Z' }],
    };
    expect(presentMedicalAgentResult(cancelled)).toContain('任务已取消');
    expect(presentMedicalAgentEvents(cancelled, '当前就诊')[0]).toMatchObject({ label: '任务已取消', detail: '医生取消' });
  });

  it('distinguishes a real provider call from a demo when final output validation fails', () => {
    const failed = {
      ...runFixture(),
      state: 'PARTIAL' as const,
      events: [{
        sequence: 3,
        event_type: 'ModelGenerationFailed',
        child_run_id: '10000000-0000-0000-0000-000000000005',
        payload: { execution_mode: 'LIVE_MODEL', total_tokens: 389, duration_ms: 1022, error_code: 'MEDICAL_AGENT_MODEL_OUTPUT_TRUNCATED' },
        occurred_at: '2026-08-30T08:00:02Z',
      }],
    };

    expect(presentMedicalAgentEvents(failed, '当前就诊')[0]).toMatchObject({
      label: '机构模型未产生有效结果',
      status: 'failed',
    });
    expect(presentMedicalAgentEvents(failed, '当前就诊')[0].detail).toContain('真实模型已调用 · 389 tokens');
  });
});
