import type { MedicalAgentRunWire } from '../generated/contracts';
import { doctorFacingAiText } from './medical-ai-terminology';

export interface MedicalAgentTaskEvent {
  id: string;
  label: string;
  detail: string;
  status: 'running' | 'done' | 'waiting' | 'failed';
}

export function medicalAgentRunStateLabel(state: MedicalAgentRunWire['state']): string {
  return ({
    QUEUED: '已排队', RUNNING: '处理中', WAITING_FOR_REVIEW: '待医生审阅', COMPLETED: '已完成',
    PARTIAL: '部分完成', BLOCKED: '等待处理', FAILED: '未完成', CANCELLED: '已取消',
  } as Record<MedicalAgentRunWire['state'], string>)[state];
}

function text(value: unknown, fallback = ''): string {
  return typeof value === 'string' && value.trim() ? value.trim() : fallback;
}

function number(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function textList(value: unknown): string[] {
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === 'string' && item.trim().length > 0)
    : [];
}

function eventDetail(eventType: string, payload: Record<string, unknown>, contextDetail: string): string {
  if (eventType === 'RunCreated') return contextDetail;
  if (eventType === 'RunClaimed') return `第 ${number(payload.attempt) ?? 1} 次执行已开始`;
  if (eventType === 'RunCancellationRequested') return text(payload.reason, '已请求在安全检查点停止');
  if (eventType === 'RunCancelled') return text(payload.reason, '任务已取消');
  if (eventType === 'RunRetryRequested') return '已获取新的诊疗授权并重新入队';
  if (eventType === 'RunRetryScheduled') return `第 ${number(payload.attempt) ?? 1} 次执行未完成，系统将自动重试`;
  if (eventType === 'RunLeaseExpired') return '执行节点超时，任务已重新排队';
  if (eventType === 'ChildAgentStarted') return text(payload.current_action, '诊疗环节任务已启动');
  if (eventType === 'ToolExecutionStarted') return `调用 ${text(payload.tool_code, '院内诊疗工具')}`;
  if (eventType === 'ToolCompleted') {
    const count = number(payload.item_count);
    const duration = number(payload.duration_ms);
    return `${text(payload.tool_code, '院内诊疗工具')}${count === null ? '' : ` · ${count} 条`}${duration === null ? '' : ` · ${duration}ms`}`;
  }
  if (eventType === 'ModelGenerationStarted') return `调用 ${text(payload.model_display_name, text(payload.model_code, '机构模型'))}`;
  if (eventType === 'ModelGenerationCompleted') {
    const mode = payload.execution_mode === 'LIVE_MODEL' ? '真实模型' : '仿真模型';
    const tokens = number(payload.total_tokens);
    const duration = number(payload.duration_ms);
    return `${mode}${tokens === null ? '' : ` · ${tokens} tokens`}${duration === null ? '' : ` · ${duration}ms`}`;
  }
  if (eventType === 'ModelGenerationFailed') {
    const mode = payload.execution_mode === 'LIVE_MODEL' ? '真实模型已调用' : '模型调用未完成';
    const tokens = number(payload.total_tokens);
    const duration = number(payload.duration_ms);
    return `${mode}${tokens === null ? '' : ` · ${tokens} tokens`}${duration === null ? '' : ` · ${duration}ms`} · ${text(payload.error_code, '响应校验失败')}`;
  }
  if (eventType === 'ChildAgentFailed' || eventType === 'RunFailed') return `错误码：${text(payload.error_code, '执行失败')}`;
  if (eventType === 'BudgetConsumptionRecorded') {
    const tokens = number(payload.tokens_consumed);
    return tokens === null ? '用量记录已完成' : `本次模型用量 ${tokens} tokens`;
  }
  return text(payload.current_action, '运行记录已更新');
}

export function presentMedicalAgentEvents(run: MedicalAgentRunWire, contextDetail: string): MedicalAgentTaskEvent[] {
  const labels: Record<string, string> = {
    RunCreated: '任务与授权范围已记录',
    RunClaimed: '后台医助已接单',
    RunCancellationRequested: '正在停止任务',
    RunCancelled: '任务已取消',
    RunRetryRequested: '任务已重新发起',
    RunRetryScheduled: '任务将自动重试',
    RunLeaseExpired: '执行节点已切换',
    MainAgentStarted: '主医助开始规划',
    ChildAgentStarted: '诊疗环节医助开始处理',
    ToolExecutionStarted: '诊疗工具开始读取',
    ToolCompleted: '诊疗工具读取完成',
    ModelGenerationStarted: '机构模型开始分析',
    ModelGenerationCompleted: '机构模型分析完成',
    ModelGenerationFailed: '机构模型未产生有效结果',
    ChildContributionReady: '诊疗环节结果已生成',
    ChildHandoffReceived: 'AI医助 EVA 已接收医助结果',
    ChildAgentFailed: '诊疗环节医助执行失败',
    RunReadyForReview: '结果已完成核对',
    RunFailed: '医助任务未完成',
    BudgetConsumptionRecorded: '本次模型用量已记录',
  };
  return run.events.map((event) => {
    const failed = event.event_type === 'ChildAgentFailed'
      || event.event_type === 'RunFailed'
      || event.event_type === 'ModelGenerationFailed'
      || (event.event_type === 'ChildContributionReady' && event.payload.state === 'FAILED');
    return {
      id: `${run.run_id}-${event.sequence}`,
      label: labels[event.event_type] ?? '任务状态已更新',
      detail: eventDetail(event.event_type, event.payload, contextDetail),
      status: failed ? 'failed' : 'done',
    };
  });
}

export function presentMedicalAgentResult(run: MedicalAgentRunWire, format: 'text' | 'markdown' = 'text'): string {
  if (run.state === 'QUEUED') return '任务已进入后台队列，AI医助 EVA 会持续更新处理进度。';
  if (run.state === 'RUNNING') return `AI医助 EVA 正在调度医助团队执行（第 ${run.attempt} 次）。`;
  if (run.state === 'CANCELLED') return '任务已取消，未产生可采用的诊疗结果。';
  if (run.state === 'FAILED') return '任务未完成，可获取新授权后重试。';
  const rich = format === 'markdown';
  const rootSummary = doctorFacingAiText(text(run.output.summary, 'AI医助 EVA 已完成本次医助任务。'));
  const sections = run.child_runs.map((child) => {
    const contribution = child.contribution;
    const heading = doctorFacingAiText(child.display_name);
    const summary = doctorFacingAiText(text(contribution.summary, '未生成摘要'));
    const lines = [rich ? `### ${heading}\n\n${summary}` : `【${heading}】${summary}`];
    const facts = textList(contribution.facts).map(doctorFacingAiText);
    const gaps = textList(contribution.gaps).map(doctorFacingAiText);
    const warnings = textList(contribution.warnings).map(doctorFacingAiText);
    if (facts.length) lines.push(rich ? '\n#### 关键依据\n' : '关键依据：', ...facts.map((item) => rich ? `- ${item.replace(/\n/g, '\n  ')}` : `• ${item}`));
    if (gaps.length) lines.push(rich ? '\n#### 待确认\n' : '待确认：', ...gaps.map((item) => rich ? `- ${item.replace(/\n/g, '\n  ')}` : `• ${item}`));
    if (warnings.length) lines.push(rich ? '\n#### 注意事项\n' : '注意事项：', ...warnings.map((item) => rich ? `- ${item.replace(/\n/g, '\n  ')}` : `• ${item}`));
    return lines.join('\n');
  });
  const executionNotice = run.output.execution_mode === 'SYNTHETIC_MODEL'
    ? '运行说明：当前为开发环境测试模型，不是临床结论。'
    : '';
  return [rootSummary, ...sections, executionNotice].filter(Boolean).join('\n\n');
}
