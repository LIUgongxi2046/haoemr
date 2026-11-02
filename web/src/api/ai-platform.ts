import {
  clinicalContext,
  issueContextLease,
  request,
  wardHeaders,
} from '../clinical-api';
import {
  agentRegistryDeactivateRequestWireSchema,
  agentRegistryRegisterRequestWireSchema,
  agentRegistryVersionRequestWireSchema,
  agentRegistryWireSchema,
  agentRunBudgetConsumptionRecordRequestWireSchema,
  agentRunBudgetConsumptionWireSchema,
  agentRunBudgetDeactivateRequestWireSchema,
  agentRunBudgetDefineRequestWireSchema,
  agentRunBudgetUpdateRequestWireSchema,
  agentRunBudgetSummaryWireSchema,
  agentRunBudgetWireSchema,
  aiRunSnapshotWireSchema,
  modelDeploymentDeactivateRequestWireSchema,
  modelDeploymentConnectionTestRequestWireSchema,
  modelDeploymentPublishRequestWireSchema,
  modelDeploymentRegisterRequestWireSchema,
  modelDeploymentUpdateRequestWireSchema,
  modelDeploymentWireSchema,
  modelEvaluationRecordRequestWireSchema,
  modelEvaluationWireSchema,
  skillRegistryDeactivateRequestWireSchema,
  skillRegistryRegisterRequestWireSchema,
  skillRegistryVersionRequestWireSchema,
  skillRegistryWireSchema,
  toolRegistryDeactivateRequestWireSchema,
  toolRegistryRegisterRequestWireSchema,
  toolRegistryVersionRequestWireSchema,
  toolRegistryWireSchema,
  type AgentRegistryRegisterRequestWire,
  type AgentRegistryVersionRequestWire,
  type AgentRegistryWire,
  type AgentRunBudgetConsumptionRecordRequestWire,
  type AgentRunBudgetConsumptionWire,
  type AgentRunBudgetDefineRequestWire,
  type AgentRunBudgetUpdateRequestWire,
  type AgentRunBudgetSummaryWire,
  type AgentRunBudgetWire,
  type AIRunSnapshotWire,
  type ContextLeaseWire,
  type ModelDeploymentRegisterRequestWire,
  type ModelDeploymentUpdateRequestWire,
  type ModelDeploymentWire,
  type ModelEvaluationRecordRequestWire,
  type ModelEvaluationWire,
  type SkillRegistryRegisterRequestWire,
  type SkillRegistryVersionRequestWire,
  type SkillRegistryWire,
  type ToolRegistryRegisterRequestWire,
  type ToolRegistryVersionRequestWire,
  type ToolRegistryWire,
} from '../generated/contracts';
import { parseClinicalResponse } from '../clinical-contract';
import { z } from 'zod';

/** AI 平台域为机构-院区级上下文（无患者），签发一次租约后复用。 */
export function issueAiLease(purpose: string): Promise<ContextLeaseWire> {
  return issueContextLease(null, null, purpose);
}

/** Agent 受控运行列表（机构-院区级）。 */
export async function listAiRuns(lease: ContextLeaseWire): Promise<AIRunSnapshotWire[]> {
  return aiRunSnapshotWireSchema.array().parse(await request('/ai/runs', { headers: wardHeaders(lease) }));
}

function orgFacility() {
  return {
    organization_id: clinicalContext.organizationId,
    facility_id: clinicalContext.facilityId,
  };
}

// ── 基座模型目录（Model Deployments） ─────────────────────────
export async function listModelDeployments(lease: ContextLeaseWire): Promise<ModelDeploymentWire[]> {
  return modelDeploymentWireSchema.array().parse(await request('/model-deployments', { headers: wardHeaders(lease) }));
}

export async function registerModelDeployment(
  lease: ContextLeaseWire,
  input: Omit<ModelDeploymentRegisterRequestWire, 'organization_id' | 'facility_id'>,
): Promise<ModelDeploymentWire> {
  return modelDeploymentWireSchema.parse(await request('/model-deployments', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(modelDeploymentRegisterRequestWireSchema.parse({ ...orgFacility(), ...input })),
  }));
}

export async function deactivateModelDeployment(
  lease: ContextLeaseWire,
  model: ModelDeploymentWire,
): Promise<ModelDeploymentWire> {
  return modelDeploymentWireSchema.parse(await request(
    `/model-deployments/${model.model_deployment_id}/deactivations`, {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(modelDeploymentDeactivateRequestWireSchema.parse({
        ...orgFacility(), expected_row_version: model.row_version,
      })),
    },
  ));
}

export async function purgeModelDeployment(
  lease: ContextLeaseWire,
  model: ModelDeploymentWire,
): Promise<void> {
  await request(`/model-deployments/${model.model_deployment_id}/purges`, {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(modelDeploymentDeactivateRequestWireSchema.parse({
      ...orgFacility(), expected_row_version: model.row_version,
    })),
  });
}

export async function updateModelDeployment(
  lease: ContextLeaseWire,
  model: ModelDeploymentWire,
  input: Omit<ModelDeploymentUpdateRequestWire, 'organization_id' | 'facility_id' | 'expected_row_version'>,
): Promise<ModelDeploymentWire> {
  return modelDeploymentWireSchema.parse(await request(`/model-deployments/${model.model_deployment_id}`, {
    method: 'PUT',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(modelDeploymentUpdateRequestWireSchema.parse({
      ...orgFacility(), ...input, expected_row_version: model.row_version,
    })),
  }));
}

export async function testModelDeploymentConnection(
  lease: ContextLeaseWire,
  model: ModelDeploymentWire,
): Promise<ModelDeploymentWire> {
  return modelDeploymentWireSchema.parse(await request(
    `/model-deployments/${model.model_deployment_id}/connection-tests`, {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(modelDeploymentConnectionTestRequestWireSchema.parse({
        ...orgFacility(), expected_row_version: model.row_version,
      })),
    },
  ));
}

export async function publishModelDeployment(
  lease: ContextLeaseWire,
  model: ModelDeploymentWire,
): Promise<ModelDeploymentWire> {
  return modelDeploymentWireSchema.parse(await request(
    `/model-deployments/${model.model_deployment_id}/publications`, {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(modelDeploymentPublishRequestWireSchema.parse({
        ...orgFacility(), expected_row_version: model.row_version,
      })),
    },
  ));
}

const modelDataProcessingApprovalSchema = z.object({
  approval_id: z.string().uuid(),
  model_deployment_id: z.string().uuid(),
  legal_basis: z.string(),
  pia_reference: z.string().nullable(),
  processor_agreement_reference: z.string().nullable(),
  endpoint_region: z.string().nullable(),
  retention_days: z.number().int().min(0).nullable(),
  approval_kind: z.enum(['CLINICAL', 'SYNTHETIC_DEMO']),
  allowed_context_scopes: z.array(z.enum(['RECORDS', 'ORDERS', 'RESULTS', 'TASKS', 'ATTACHMENTS', 'CONFIGURATION'])),
  status: z.enum(['ACTIVE', 'REVOKED']),
  approved_by: z.string().uuid(),
  approved_at: z.string(),
  expires_at: z.string(),
  revoked_by: z.string().uuid().nullable().optional(),
  revoked_at: z.string().nullable().optional(),
  revocation_reason: z.string().nullable().optional(),
  row_version: z.number().int().positive(),
});

export type ModelDataProcessingApproval = z.infer<typeof modelDataProcessingApprovalSchema>;
export type MedicalAgentContextScope = ModelDataProcessingApproval['allowed_context_scopes'][number];

const medicalAgentOperationsRunSchema = z.object({
  run_id: z.string().uuid(),
  root_agent_code: z.string(),
  root_agent_name: z.string(),
  requested_stage: z.string(),
  state: z.enum(['QUEUED', 'RUNNING', 'WAITING_FOR_REVIEW', 'COMPLETED', 'PARTIAL', 'BLOCKED', 'FAILED', 'CANCELLED']),
  model_display_name: z.string().nullable(),
  provider_code: z.string().nullable(),
  authorization_level: z.enum(['READ_ONLY', 'STANDARD', 'EXTENDED']),
  model_total_tokens: z.number().int().nonnegative(),
  actual_duration_ms: z.number().int().nonnegative(),
  model_request_count: z.number().int().nonnegative(),
  tool_call_count: z.number().int().nonnegative(),
  tool_failure_count: z.number().int().nonnegative(),
  attempt: z.number().int().nonnegative(),
  max_attempts: z.number().int().positive(),
  failure_code: z.string().nullable(),
  created_at: z.string(),
  completed_at: z.string().nullable(),
  external_processing_approved: z.boolean(),
  assistant_policy_environment: z.string().nullable(),
});

const medicalAgentOperationsToolInvocationSchema = z.object({
  invocation_id: z.string().uuid(),
  child_run_id: z.string().uuid(),
  tool_code: z.string(),
  tool_version: z.string(),
  item_count: z.number().int().nonnegative(),
  outcome: z.enum(['SUCCEEDED', 'DENIED', 'FAILED']),
  duration_ms: z.number().int().nonnegative(),
  error_code: z.string().nullable(),
  invoked_at: z.string(),
  completed_at: z.string(),
});

export type MedicalAgentOperationsRun = z.infer<typeof medicalAgentOperationsRunSchema>;
export type MedicalAgentOperationsToolInvocation = z.infer<typeof medicalAgentOperationsToolInvocationSchema>;

export async function listMedicalAgentOperationsRuns(
  lease: ContextLeaseWire, limit = 100,
): Promise<MedicalAgentOperationsRun[]> {
  return medicalAgentOperationsRunSchema.array().parse(await request(
    `/medical-agents/operations/runs?limit=${Math.max(1, Math.min(Math.floor(limit), 200))}`,
    { headers: wardHeaders(lease) },
  ));
}

export async function listMedicalAgentOperationsToolInvocations(
  lease: ContextLeaseWire, runId: string,
): Promise<MedicalAgentOperationsToolInvocation[]> {
  return medicalAgentOperationsToolInvocationSchema.array().parse(await request(
    `/medical-agents/operations/runs/${encodeURIComponent(runId)}/tool-invocations`,
    { headers: wardHeaders(lease) },
  ));
}

export async function listModelDataProcessingApprovals(
  lease: ContextLeaseWire, modelDeploymentId: string,
): Promise<ModelDataProcessingApproval[]> {
  return parseClinicalResponse(modelDataProcessingApprovalSchema.array(), await request(
    `/model-deployments/${encodeURIComponent(modelDeploymentId)}/data-processing-approvals`,
    { headers: wardHeaders(lease) },
  ));
}

export async function approveModelDataProcessing(
  lease: ContextLeaseWire,
  modelDeploymentId: string,
  input: {
    legal_basis: string;
    pia_reference: string;
    processor_agreement_reference: string;
    endpoint_region: string;
    retention_days: number;
    allowed_context_scopes: MedicalAgentContextScope[];
    expires_at: string;
  },
): Promise<ModelDataProcessingApproval> {
  return parseClinicalResponse(modelDataProcessingApprovalSchema, await request(
    `/model-deployments/${encodeURIComponent(modelDeploymentId)}/data-processing-approvals`, {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ ...orgFacility(), ...input }),
    },
  ));
}

export async function revokeModelDataProcessingApproval(
  lease: ContextLeaseWire,
  approval: ModelDataProcessingApproval,
  reason: string,
): Promise<ModelDataProcessingApproval> {
  return parseClinicalResponse(modelDataProcessingApprovalSchema, await request(
    `/model-deployments/${encodeURIComponent(approval.model_deployment_id)}/data-processing-approvals/${encodeURIComponent(approval.approval_id)}/revocations`, {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ ...orgFacility(), expected_row_version: approval.row_version, reason }),
    },
  ));
}

// ── Agent 目录（Agent Registry） ─────────────────────────────
export async function listAgents(lease: ContextLeaseWire, status?: string): Promise<AgentRegistryWire[]> {
  const q = status ? `?status=${encodeURIComponent(status)}` : '';
  return agentRegistryWireSchema.array().parse(await request(`/agent-registry${q}`, { headers: wardHeaders(lease) }));
}

export async function registerAgent(
  lease: ContextLeaseWire,
  input: Omit<AgentRegistryRegisterRequestWire, 'organization_id' | 'facility_id'>,
): Promise<AgentRegistryWire> {
  return agentRegistryWireSchema.parse(await request('/agent-registry', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(agentRegistryRegisterRequestWireSchema.parse({ ...orgFacility(), ...input })),
  }));
}

export async function deactivateAgent(
  lease: ContextLeaseWire,
  agent: AgentRegistryWire,
): Promise<AgentRegistryWire> {
  return agentRegistryWireSchema.parse(await request(
    `/agent-registry/${agent.agent_registry_id}/deactivations`, {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(agentRegistryDeactivateRequestWireSchema.parse({ ...orgFacility() })),
    },
  ));
}

export async function publishAgentVersion(
  lease: ContextLeaseWire,
  agent: AgentRegistryWire,
  input: Omit<AgentRegistryVersionRequestWire, 'organization_id' | 'facility_id'>,
): Promise<AgentRegistryWire> {
  return agentRegistryWireSchema.parse(await request(`/agent-registry/${agent.agent_registry_id}/versions`, {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(agentRegistryVersionRequestWireSchema.parse({ ...orgFacility(), ...input })),
  }));
}

// ── Skill 目录（Skill Registry） ─────────────────────────────
export async function listSkills(lease: ContextLeaseWire, status?: string): Promise<SkillRegistryWire[]> {
  const q = status ? `?status=${encodeURIComponent(status)}` : '';
  return skillRegistryWireSchema.array().parse(await request(`/skill-registry${q}`, { headers: wardHeaders(lease) }));
}

export async function registerSkill(
  lease: ContextLeaseWire,
  input: Omit<SkillRegistryRegisterRequestWire, 'organization_id' | 'facility_id'>,
): Promise<SkillRegistryWire> {
  return skillRegistryWireSchema.parse(await request('/skill-registry', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(skillRegistryRegisterRequestWireSchema.parse({ ...orgFacility(), ...input })),
  }));
}

export async function deactivateSkill(
  lease: ContextLeaseWire,
  skill: SkillRegistryWire,
): Promise<SkillRegistryWire> {
  return skillRegistryWireSchema.parse(await request(
    `/skill-registry/${skill.skill_registry_id}/deactivations`, {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(skillRegistryDeactivateRequestWireSchema.parse({ ...orgFacility() })),
    },
  ));
}

export async function publishSkillVersion(
  lease: ContextLeaseWire,
  skill: SkillRegistryWire,
  input: Omit<SkillRegistryVersionRequestWire, 'organization_id' | 'facility_id'>,
): Promise<SkillRegistryWire> {
  return skillRegistryWireSchema.parse(await request(`/skill-registry/${skill.skill_registry_id}/versions`, {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(skillRegistryVersionRequestWireSchema.parse({ ...orgFacility(), ...input })),
  }));
}

// ── Tool 目录（Tool Registry） ───────────────────────────────
export async function listTools(lease: ContextLeaseWire, status?: string): Promise<ToolRegistryWire[]> {
  const q = status ? `?status=${encodeURIComponent(status)}` : '';
  return toolRegistryWireSchema.array().parse(await request(`/tool-registry${q}`, { headers: wardHeaders(lease) }));
}

export async function registerTool(
  lease: ContextLeaseWire,
  input: Omit<ToolRegistryRegisterRequestWire, 'organization_id' | 'facility_id'>,
): Promise<ToolRegistryWire> {
  return toolRegistryWireSchema.parse(await request('/tool-registry', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(toolRegistryRegisterRequestWireSchema.parse({ ...orgFacility(), ...input })),
  }));
}

export async function deactivateTool(
  lease: ContextLeaseWire,
  tool: ToolRegistryWire,
): Promise<ToolRegistryWire> {
  return toolRegistryWireSchema.parse(await request(
    `/tool-registry/${tool.tool_registry_id}/deactivations`, {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(toolRegistryDeactivateRequestWireSchema.parse({ ...orgFacility() })),
    },
  ));
}

export async function publishToolVersion(
  lease: ContextLeaseWire,
  tool: ToolRegistryWire,
  input: Omit<ToolRegistryVersionRequestWire, 'organization_id' | 'facility_id'>,
): Promise<ToolRegistryWire> {
  return toolRegistryWireSchema.parse(await request(`/tool-registry/${tool.tool_registry_id}/versions`, {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(toolRegistryVersionRequestWireSchema.parse({ ...orgFacility(), ...input })),
  }));
}

// ── 模型评估（Model Evaluations） ────────────────────────────
export async function listModelEvaluations(
  lease: ContextLeaseWire,
  modelDeploymentId: string,
): Promise<ModelEvaluationWire[]> {
  return modelEvaluationWireSchema.array().parse(await request(
    `/model-evaluations?model_deployment_id=${encodeURIComponent(modelDeploymentId)}`,
    { headers: wardHeaders(lease) },
  ));
}

export async function recordModelEvaluation(
  lease: ContextLeaseWire,
  input: Omit<ModelEvaluationRecordRequestWire, 'organization_id' | 'facility_id'>,
): Promise<ModelEvaluationWire> {
  return modelEvaluationWireSchema.parse(await request('/model-evaluations', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(modelEvaluationRecordRequestWireSchema.parse({
      ...orgFacility(), ...input, evaluated_at: new Date(input.evaluated_at).toISOString(),
    })),
  }));
}

// ── AI 运行预算（Agent Run Budgets） ─────────────────────────
export async function listAgentRunBudgets(lease: ContextLeaseWire, status?: string): Promise<AgentRunBudgetWire[]> {
  const q = status ? `?status=${encodeURIComponent(status)}` : '';
  return agentRunBudgetWireSchema.array().parse(await request(`/agent-run-budgets${q}`, { headers: wardHeaders(lease) }));
}

export async function defineAgentRunBudget(
  lease: ContextLeaseWire,
  input: Omit<AgentRunBudgetDefineRequestWire, 'organization_id' | 'facility_id'>,
): Promise<AgentRunBudgetWire> {
  return agentRunBudgetWireSchema.parse(await request('/agent-run-budgets', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(agentRunBudgetDefineRequestWireSchema.parse({ ...orgFacility(), ...input })),
  }));
}

export async function deactivateAgentRunBudget(
  lease: ContextLeaseWire,
  budget: AgentRunBudgetWire,
): Promise<AgentRunBudgetWire> {
  return agentRunBudgetWireSchema.parse(await request(
    `/agent-run-budgets/${budget.budget_id}/deactivations`, {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(agentRunBudgetDeactivateRequestWireSchema.parse({ ...orgFacility() })),
    },
  ));
}

export async function updateAgentRunBudget(
  lease: ContextLeaseWire,
  budget: AgentRunBudgetWire,
  input: Omit<AgentRunBudgetUpdateRequestWire, 'organization_id' | 'facility_id' | 'expected_row_version'>,
): Promise<AgentRunBudgetWire> {
  return agentRunBudgetWireSchema.parse(await request(`/agent-run-budgets/${budget.budget_id}`, {
    method: 'PUT',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(agentRunBudgetUpdateRequestWireSchema.parse({
      ...orgFacility(), ...input, expected_row_version: budget.row_version,
    })),
  }));
}

export async function getAgentRunBudgetSummary(
  lease: ContextLeaseWire,
  budgetId: string,
): Promise<AgentRunBudgetSummaryWire> {
  return agentRunBudgetSummaryWireSchema.parse(await request(
    `/agent-run-budget-summaries/${budgetId}`,
    { headers: wardHeaders(lease) },
  ));
}

export async function listAgentRunBudgetConsumptions(
  lease: ContextLeaseWire,
  budgetId: string,
): Promise<AgentRunBudgetConsumptionWire[]> {
  return agentRunBudgetConsumptionWireSchema.array().parse(await request(
    `/agent-run-budget-consumptions?budget_id=${encodeURIComponent(budgetId)}`,
    { headers: wardHeaders(lease) },
  ));
}

export async function recordAgentRunBudgetConsumption(
  lease: ContextLeaseWire,
  input: Omit<AgentRunBudgetConsumptionRecordRequestWire, 'organization_id' | 'facility_id'>,
): Promise<AgentRunBudgetConsumptionWire> {
  return agentRunBudgetConsumptionWireSchema.parse(await request('/agent-run-budget-consumptions', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(agentRunBudgetConsumptionRecordRequestWireSchema.parse({ ...orgFacility(), ...input })),
  }));
}
