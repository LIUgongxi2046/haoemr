import {
  clinicalContext,
  explicitContextHeaders,
  issueContextLease,
  request,
  scopedHeaders,
  streamText,
} from '../clinical-api';
import {
  actionApprovalDecideRequestWireSchema,
  actionApprovalProposeRequestWireSchema,
  actionApprovalWireSchema,
  actionExecutionCreateRequestWireSchema,
  actionExecutionTransitionRequestWireSchema,
  actionExecutionWireSchema,
  clinicalReminderAcknowledgeRequestWireSchema,
  clinicalReminderConversionCreateRequestWireSchema,
  clinicalReminderConversionWireSchema,
  clinicalReminderCreateRequestWireSchema,
  clinicalReminderSilenceRequestWireSchema,
  clinicalReminderWireSchema,
  type ActionApprovalWire,
  type ActionExecutionWire,
  type ClinicalReminderWire,
  type ClinicalReminderConversionWire,
  type ContextLeaseWire,
} from '../generated/contracts';

export function issueAssistantPatientLease(purpose: string): Promise<ContextLeaseWire> {
  return issueContextLease(clinicalContext.patientId, null, purpose);
}
export function issueAssistantEncounterLease(purpose: string): Promise<ContextLeaseWire> {
  return issueContextLease(clinicalContext.patientId, clinicalContext.encounterId, purpose);
}

function orgFacility() {
  return { organization_id: clinicalContext.organizationId, facility_id: clinicalContext.facilityId };
}
function scoped() {
  return { ...orgFacility(), patient_id: clinicalContext.patientId, encounter_id: clinicalContext.encounterId };
}

// ── 动作审批（A02：提议 → 审批 → 执行核验） ─────────────────────
export async function listActionApprovals(lease: ContextLeaseWire): Promise<ActionApprovalWire[]> {
  return actionApprovalWireSchema.array().parse(await request(
    `/action-approvals?patient_id=${encodeURIComponent(clinicalContext.patientId)}`,
    { headers: explicitContextHeaders(lease, clinicalContext.patientId, null) },
  ));
}
export async function proposeActionApproval(
  lease: ContextLeaseWire,
  input: { action_type: string; proposed_action_summary: string; proposed_at: string },
): Promise<ActionApprovalWire> {
  return actionApprovalWireSchema.parse(await request('/action-approvals', {
    method: 'POST',
    headers: { ...scopedHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(actionApprovalProposeRequestWireSchema.parse({ ...scoped(), ...input })),
  }));
}
export async function decideActionApproval(
  lease: ContextLeaseWire,
  approval: ActionApprovalWire,
  decision: string,
): Promise<ActionApprovalWire> {
  return actionApprovalWireSchema.parse(await request(
    `/action-approvals/${approval.action_approval_id}/decisions`, {
      method: 'POST',
      headers: { ...scopedHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(actionApprovalDecideRequestWireSchema.parse({ ...scoped(), expected_row_version: approval.row_version, decision })),
    },
  ));
}
export async function listActionExecutions(lease: ContextLeaseWire, approvalId: string): Promise<ActionExecutionWire[]> {
  return actionExecutionWireSchema.array().parse(await request(
    `/action-executions?action_approval_id=${encodeURIComponent(approvalId)}`,
    { headers: explicitContextHeaders(lease, clinicalContext.patientId, null) },
  ));
}
export async function createActionExecution(lease: ContextLeaseWire, approvalId: string): Promise<ActionExecutionWire> {
  return actionExecutionWireSchema.parse(await request('/action-executions', {
    method: 'POST',
    headers: { ...explicitContextHeaders(lease, clinicalContext.patientId, null), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(actionExecutionCreateRequestWireSchema.parse({ ...orgFacility(), patient_id: clinicalContext.patientId, action_approval_id: approvalId })),
  }));
}
export async function settleActionExecution(
  lease: ContextLeaseWire,
  execution: ActionExecutionWire,
  outcome: 'SUCCEEDED' | 'FAILED',
  note: string,
): Promise<ActionExecutionWire> {
  return actionExecutionWireSchema.parse(await request(
    `/action-executions/${execution.execution_id}/${outcome === 'SUCCEEDED' ? 'successes' : 'failures'}`, {
      method: 'POST',
      headers: { ...explicitContextHeaders(lease, clinicalContext.patientId, null), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(actionExecutionTransitionRequestWireSchema.parse({
        ...orgFacility(), patient_id: clinicalContext.patientId, expected_row_version: execution.row_version, result_note: note,
      })),
    },
  ));
}

// ── 主动提醒（A02：提醒 + 确认 + 静默） ─────────────────────────
export async function listClinicalReminders(lease: ContextLeaseWire): Promise<ClinicalReminderWire[]> {
  return clinicalReminderWireSchema.array().parse(await request(
    `/clinical-reminders?encounter_id=${encodeURIComponent(clinicalContext.encounterId)}`,
    { headers: scopedHeaders(lease) },
  ));
}
export async function createClinicalReminder(
  lease: ContextLeaseWire,
  input: { reminder_type: string; message: string; severity: string; source_task_id?: string | null },
): Promise<ClinicalReminderWire> {
  return clinicalReminderWireSchema.parse(await request('/clinical-reminders', {
    method: 'POST',
    headers: { ...scopedHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(clinicalReminderCreateRequestWireSchema.parse({ ...scoped(), ...input })),
  }));
}
export async function listClinicalReminderConversions(
  lease: ContextLeaseWire,
  reminderId: string,
): Promise<ClinicalReminderConversionWire[]> {
  return clinicalReminderConversionWireSchema.array().parse(await request(
    `/clinical-reminder-conversions?reminder_id=${encodeURIComponent(reminderId)}`,
    { headers: scopedHeaders(lease) },
  ));
}
export async function convertClinicalReminderToTask(
  lease: ContextLeaseWire,
  reminder: ClinicalReminderWire,
): Promise<ClinicalReminderConversionWire> {
  return clinicalReminderConversionWireSchema.parse(await request('/clinical-reminder-conversions', {
    method: 'POST',
    headers: { ...scopedHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(clinicalReminderConversionCreateRequestWireSchema.parse({
      ...scoped(), reminder_id: reminder.reminder_id, converted_at: new Date().toISOString(),
    })),
  }));
}
export async function acknowledgeClinicalReminder(lease: ContextLeaseWire, reminder: ClinicalReminderWire): Promise<ClinicalReminderWire> {
  return clinicalReminderWireSchema.parse(await request(
    `/clinical-reminders/${reminder.reminder_id}/acknowledgements`, {
      method: 'POST',
      headers: { ...scopedHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(clinicalReminderAcknowledgeRequestWireSchema.parse({ ...scoped(), expected_row_version: reminder.row_version })),
    },
  ));
}
export async function silenceClinicalReminder(lease: ContextLeaseWire, reminder: ClinicalReminderWire): Promise<ClinicalReminderWire> {
  return clinicalReminderWireSchema.parse(await request(
    `/clinical-reminders/${reminder.reminder_id}/silences`, {
      method: 'POST',
      headers: { ...scopedHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(clinicalReminderSilenceRequestWireSchema.parse({ ...scoped(), expected_row_version: reminder.row_version })),
    },
  ));
}

// ── 历史全局 AI 医助流式接口（兼容保留） ─────────────────────
export function issueAssistantFacilityLease(): Promise<ContextLeaseWire> {
  return issueContextLease(null, null, 'AI_ASSISTANT');
}

export interface AssistantStreamChunk {
  event: string;
  data: string;
}

export async function streamAssistantResponse(
  lease: ContextLeaseWire,
  message: string,
  patientId: string | null = null,
  encounterId: string | null = null,
): Promise<AssistantStreamChunk[]> {
  const raw = await streamText(
    `/assistant/stream?message=${encodeURIComponent(message)}`,
    explicitContextHeaders(lease, patientId, encounterId),
  );
  const chunks: AssistantStreamChunk[] = [];
  for (const block of raw.split('\n\n')) {
    let event = 'message';
    let data = '';
    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) event = line.slice('event:'.length).trim();
      else if (line.startsWith('data:')) data += line.slice('data:'.length).trim();
    }
    if (data) chunks.push({ event, data });
  }
  return chunks;
}
